package com.tirso.tirsolador

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.io.File
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import com.swordfish.radialgamepad.library.RadialGamePad
import com.swordfish.radialgamepad.library.event.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The only screen of a Tirso game app: it unpacks the embedded game on first
 * launch, starts the emulator and shows touch controls and a pause menu.
 */
open class GameActivity : AppCompatActivity() {

    private lateinit var config: GameConfig
    private lateinit var saves: SaveManager
    private var console: Console = Console.NDS
    private var gameTitle = ""
    private var gameId = "game"
    private var workspace: File? = null
    private var romLease: String? = null
    private var sourceRom: File? = null
    private var retroView: GLRetroView? = null
    private var leftPad: RadialGamePad? = null
    private var rightPad: RadialGamePad? = null
    private var menuOpen = false
    private var padsWanted = true
    private val hasTouch by lazy { packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN) }
    private var selectDown = false
    private var engineLabel = ""
    private var is64 = true
    private var frames = 0
    private var showSpeed = false
    private var startDown = false
    private var screenMode = "overlay"
    private var aspect = 4f / 3f
    private var activeCore = ""
    private val cheats by lazy { Cheats(this, prefs, isDolphin, gameId, gameTitle, console) }
    private var dsFamily = "stacked"
    private var pictureArea = RectF(0f, 0f, 1f, 1f)
    private var currentTurn: String? = null

    // ------------------------------------------------------------------ DS microphone
    private var micAnswer: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micAnswer?.complete(granted)
    }
    private val usesMic get() = config.core.contains("melonds")
    private fun micGranted() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    private var micOn: Boolean
        get() = prefs.getBoolean("mic_on", true)
        set(v) { prefs.edit().putBoolean("mic_on", v).apply() }

    /** DS games that listen (blow, talk, shout) get the phone's microphone; asked once, the first time. */
    private suspend fun askForMicrophoneOnce() {
        if (!usesMic || micGranted() || prefs.getBoolean("mic_asked", false)) return
        prefs.edit().putBoolean("mic_asked", true).apply()
        val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
        micAnswer = answer
        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        answer.await()
    }

    private fun micVariables() = arrayOf(Variable("melonds_mic_input", if (micOn && micGranted()) "microphone" else "silence"))

    private fun toggleMicrophone() {
        if (!micGranted()) {
            closeMenu()
            lifecycleScope.launch {
                val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
                micAnswer = answer
                micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                if (answer.await()) {
                    micOn = true
                    toast("Microphone allowed. Close and reopen the game to use it.")
                } else toast("Microphone not allowed. You can allow it in the phone's Settings > Apps.")
            }
            return
        }
        micOn = !micOn
        retroView?.updateVariables(*micVariables())
        closeMenu()
        toast(if (micOn) "Microphone on" else "Microphone off")
    }

    private var romSha = ""
    /** N64 runs in its own process so a native crash while closing it can't take the app down with it. */
    private val isolated get() = this is GameActivityN64

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        T.init(this)
        installCrashReporter()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_game)
        hideSystemBars()

        console = runCatching { Console.valueOf(intent.getStringExtra("console") ?: "") }.getOrDefault(Console.NDS)
        gameTitle = intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: console.title
        config = Presets.forConsole(this, console, gameTitle)
        screenMode = config.screenMode
        aspect = config.aspect
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // TVs: ask for "game mode" / ALLM (no picture processing, much less input delay).
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setPreferMinimalPostProcessing(true)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = openMenu()
        })

        showLastCrashIfAny()

        findViewById<TextView>(R.id.loadingText).text = gameTitle
        lifecycleScope.launch {
            val rom = try {
                withContext(Dispatchers.IO) { prepareRom() }
            } catch (e: Exception) {
                Log.e("RetroStream", "Preparing the game failed", e)
                fatal(tr("The game couldn't be prepared.") + "\n\n${e.javaClass.simpleName}: ${e.message}")
                return@launch
            }
            askForMicrophoneOnce()
            startGame(rom.absolutePath)
        }
    }

    /** The ROM RetroStream downloaded (zip files are unpacked) and this game's own save folder. */
    private fun prepareRom(): File {
        val path = intent.getStringExtra("rom") ?: throw java.io.IOException("No game file received")
        val source = File(path).canonicalFile
        val root = filesDir.canonicalPath + File.separator
        val cache = cacheDir.canonicalPath + File.separator
        if (!source.path.startsWith(root) && !source.path.startsWith(cache)) throw java.io.IOException("Game file outside RetroStream's storage")
        sourceRom = source
        romLease = TemporaryRoms.begin(this, source)
        val name = intent.getStringExtra("filename")?.takeIf { it.isNotEmpty() } ?: source.name
        val ws = java.nio.file.Files.createTempDirectory(cacheDir.toPath(), "play-").toFile()
        workspace = ws
        val prepared = ArchiveContent.prepare(source, name, console, ws)
        if (prepared.length() < 1 || prepared.length() > ArchiveContent.limit(console)) throw java.io.IOException("Invalid game size")
        val digest = if (ArchiveContent.disc(console)) GameStorage.quickHash(prepared) else GameStorage.hash(prepared)
        gameId = digest.take(16)
        romSha = digest
        val system = File(filesDir, "bios").apply { mkdirs() }
        saves = SaveManager(File(filesDir, "saves/${Presets.key(console)}/$digest"), system)
        SaveBackups.writeMeta(saves.root, gameTitle, console)
        installSystemFiles(system)
        if (console == Console.MAME) {
            // MAME looks for its BIOS / parent zips next to the game.
            system.listFiles { f -> f.name.endsWith(".zip") }?.forEach { f ->
                val link = File(ws, f.name)
                if (!link.exists()) runCatching { java.nio.file.Files.createLink(link.toPath(), f.toPath()) }.onFailure { f.copyTo(link) }
            }
        }
        return prepared
    }

    /** Engine system files (PSP fonts, Dolphin data) packed in the phone app, unpacked once per app version. */
    private fun installSystemFiles(systemDir: File) {
        val zipName = config.systemZip ?: return
        val marker = "$zipName:${BuildConfig.VERSION_CODE}"
        if (prefs.getString("system_marker_$zipName", null) == marker) return
        val root = systemDir.canonicalFile
        java.util.zip.ZipInputStream(assets.open(zipName).buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(root, e.name).canonicalFile
                if (!out.path.startsWith(root.path)) continue
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { zip.copyTo(it) }
            }
        }
        prefs.edit().putString("system_marker_$zipName", marker).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        val ws = workspace; val lease = romLease; val source = sourceRom; val app = applicationContext
        val kill = isolated
        Thread {
            ws?.let { ArchiveContent.removeTree(it) }; if (lease != null) TemporaryRoms.release()
            if (kill) android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }

    // ------------------------------------------------------------------ game
    private fun startGame(romPath: String) {
        // Pick the engine installed for this device's processor type.
        val libDir = java.io.File(applicationInfo.nativeLibraryDir)
        is64 = libDir.path.contains("64")
        var core = config.core
        var vars = config.variables
        engineLabel = config.core.removePrefix("lib").substringBefore("_libretro")
        if (!java.io.File(libDir, core).exists()) {
            config.altEngines.firstOrNull { java.io.File(libDir, it.core).exists() }?.let {
                core = it.core; vars = it.variables; engineLabel = it.label
            }
        }
        activeCore = core
        // DS "turns with the phone": start with the layout for the way the phone is held now.
        turnFor(resources.configuration)?.let { turn ->
            config.rotateLayouts[turn]?.let { l ->
                vars = vars + l.variables
                screenMode = l.screenMode; aspect = l.aspect; currentTurn = turn
            }
        }
        if (isDs) {
            // DS: the player chooses the screen layout (L3 = full screen, R3 = swap), remembered per game.
            dsFamily = dsFamilyOf(vars)
            val v = dsView()
            vars = vars + v.variables
            screenMode = v.screenMode; aspect = v.aspect
        }
        if (usesMic) vars = vars + mapOf("melonds_mic_input" to (if (micOn && micGranted()) "microphone" else "silence"))
        val data = GLRetroViewData(this).apply {
            coreFilePath = java.io.File(libDir, core).absolutePath
            gameFilePath = romPath
            systemDirectory = saves.systemDir.absolutePath
            savesDirectory = saves.savesDir.absolutePath
            variables = vars.map { Variable(it.key, it.value) }.toTypedArray()
            saveRAMState = saves.readSram()
            shader = shaderFor(screenFilter)
            rumbleEventsEnabled = config.rumble
            enableMicrophone = usesMic && micGranted()
            preferLowLatencyAudio = true
        }

        if (config.preserveGl) runCatching { android.system.Os.setenv("LIBRETRODROID_PRESERVE_GL", "1", true) }
        if (isDolphin) {
            if (prefs.getBoolean("fix_starting", false) && pictureFix != SAFE_PICTURE) {
                // The last start with this graphics choice never got going: use the safe one so the game opens.
                val bad = PICTURE_FIXES.getOrElse(pictureFix) { PICTURE_FIXES[0] }.name
                prefs.edit().putInt("picture_fix_v5", SAFE_PICTURE).putBoolean("fix_starting", false).commit()
                toast("$bad made the game close, so graphics are back to ${PICTURE_FIXES[SAFE_PICTURE].name}")
            }
            if (pictureFix != SAFE_PICTURE) {
                prefs.edit().putBoolean("fix_starting", true).commit()
                lifecycleScope.launch { delay(20_000); prefs.edit().putBoolean("fix_starting", false).apply() }
            }
            val fix = PICTURE_FIXES.getOrElse(pictureFix) { PICTURE_FIXES[0] }
            runCatching { android.system.Os.setenv("TIRSO_GL_OFF", fix.glOff, true) }
            // Vulkan: Dolphin draws with Vulkan and the player shows its pictures (LibretroDroid Vulkan bridge).
            runCatching { android.system.Os.setenv("LIBRETRODROID_VULKAN", if (fix.vulkan) "1" else "0", true) }
            prepareGpuDriver(fix.vulkan && fix.customDriver)
            if (fix.variables.isNotEmpty()) {
                val merged = vars + fix.variables
                data.variables = merged.map { Variable(it.key, it.value) }.toTypedArray()
            }
        }
        val view = GLRetroView(this, data)
        retroView = view
        lifecycle.addObserver(view)
        if (isDolphin) {
            // Added after the view, so it hears "pause" first, while the engine can still save safely.
            lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onPause(owner: androidx.lifecycle.LifecycleOwner) {
                    if (!glPaused) heldState = snapshot()
                    glPaused = true
                    persist()
                }
                override fun onResume(owner: androidx.lifecycle.LifecycleOwner) { glPaused = false }
            })
        }
        val container = findViewById<FrameLayout>(R.id.gameContainer)
        setPictureArea(view, pictureArea)
        container.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.requestFocus()

        lifecycleScope.launch {
            view.getGLRetroErrors()
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { code -> onEmulatorError(code) }
        }
        lifecycleScope.launch {
            view.getGLRetroEvents().filter { it is GLRetroView.GLRetroEvents.FrameRendered }.first()
            if (config.controllerType >= 0) {
                // Plug the controller into port 1 on the emulation thread (between frames).
                val type = config.controllerType
                view.queueEvent { com.swordfish.libretrodroid.LibretroDroid.setControllerType(0, type) }
            }
            findViewById<View>(R.id.loading).visibility = View.GONE
            if (config.autoResume && isDolphin && !prefs.getBoolean("dolphin_state_v2", false)) {
                // Older GameCube/Wii players saved this from the wrong thread, which broke some textures.
                saves.deleteAutoState()
            }
            if (config.autoResume) {
                saves.readAutoState()?.let { state ->
                    val ok = runCatching { view.unserializeState(state) }.getOrDefault(false)
                    if (ok) toast("Continuing where you left off")
                }
            }
        }
        lifecycleScope.launch {
            view.getGLRetroEvents().collect { if (it is GLRetroView.GLRetroEvents.FrameRendered) frames++ }
        }
        // Cheats you turned on earlier: sent once the game is running (Dolphin lists its codes while booting).
        lifecycleScope.launch {
            view.getGLRetroEvents().filter { it is GLRetroView.GLRetroEvents.FrameRendered }.first()
            if (!isDolphin) cheats.ensure { if (cheats.enabled.isNotEmpty() && retroView === view) cheats.apply(view) }
            delay(if (isDolphin) 8000 else 1500)
            if (cheats.enabled.isNotEmpty() && retroView === view) cheats.apply(view)
        }
        lifecycleScope.launch {
            while (true) {
                frames = 0
                delay(1000)
                if (showSpeed) speedView().text = "$frames fps · ${if (is64) "64-bit" else "32-bit"} · $engineLabel"
            }
        }
        lifecycleScope.launch {
            view.getRumbleEvents()
                .flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
                .collect { Haptics.rumble(this@GameActivity, it.strengthWeak, it.strengthStrong) }
        }

        setupPads()
    }

    private fun onEmulatorError(code: Int) {
        val text = when (code) {
            GLRetroView.ERROR_LOAD_LIBRARY -> "The game engine couldn't be loaded on this phone."
            GLRetroView.ERROR_LOAD_GAME -> "The game file couldn't be opened. It may be damaged or unsupported."
            GLRetroView.ERROR_GL_NOT_COMPATIBLE -> "This phone's graphics chip isn't supported (OpenGL ES 3 is required)."
            GLRetroView.ERROR_SERIALIZATION -> { toast("That save couldn't be loaded."); return }
            else -> "The game stopped unexpectedly (error $code)."
        }
        fatal(text)
    }

    // ------------------------------------------------------------------ touch controls
    private fun setupPads() {
        val (leftCfg, rightCfg) = Pads.forName(config.pad)
        val margin = 8f
        val left = RadialGamePad(leftCfg, margin, this).apply { gravityX = -1f; gravityY = 1f }
        val right = RadialGamePad(rightCfg, margin, this).apply { gravityX = 1f; gravityY = 1f }
        val full = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        findViewById<FrameLayout>(R.id.leftPad).addView(left, full)
        findViewById<FrameLayout>(R.id.rightPad).addView(right, FrameLayout.LayoutParams(full))
        leftPad = left
        rightPad = right
        padsWanted = hasTouch && !hasGamepad() && !padsLocked
        updatePadsVisibility()
        if (!hasTouch && !hasGamepad()) {
            Toast.makeText(this, tr("Connect a Bluetooth controller to play. Back opens the menu."), Toast.LENGTH_LONG).show()
        }
        val root = findViewById<View>(R.id.root)
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) root.post { arrangeScreen(root.width, root.height) }
        }
        if (root.width > 0) arrangeScreen(root.width, root.height)

        lifecycleScope.launch {
            merge(left.events(), right.events())
                .flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
                .collect { handlePadEvent(it) }
        }
    }

    private fun handlePadEvent(event: Event) {
        val view = retroView ?: return
        when (event) {
            is Event.Button -> {
                if (event.id == Pads.MENU_ID) {
                    if (event.action == KeyEvent.ACTION_DOWN) openMenu()
                } else if (isDs && (event.id == KeyEvent.KEYCODE_BUTTON_THUMBL || event.id == KeyEvent.KEYCODE_BUTTON_THUMBR)) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (event.id == KeyEvent.KEYCODE_BUTTON_THUMBL) toggleDsFull() else swapDsScreens()
                    }
                } else {
                    view.sendKeyEvent(event.action, event.id)
                }
            }
            is Event.Direction -> view.sendMotionEvent(event.id, event.xAxis, event.yAxis)
        }
    }

    private fun updatePadsVisibility() {
        val v = if (padsWanted) View.VISIBLE else View.GONE
        findViewById<View>(R.id.leftPad).visibility = v
        findViewById<View>(R.id.rightPad).visibility = v
        val root = findViewById<View>(R.id.root)
        if (root.width > 0) arrangeScreen(root.width, root.height)
    }

    /**
     * Places the game picture and the two square control pads. Touches inside a
     * pad go to the pad, so for the DS the bottom screen is never put under one.
     */
    private fun arrangeScreen(w: Int, h: Int) {
        // The game view always fills the screen; only the area the picture is drawn in moves.
        // (Resizing the view itself made Android place the picture wrongly after turning the phone.)
        val left = findViewById<FrameLayout>(R.id.leftPad)
        val right = findViewById<FrameLayout>(R.id.rightPad)
        val portrait = h > w
        var pad: Int
        var area = RectF(0f, 0f, 1f, 1f)
        when {
            !padsWanted && !portrait -> pad = 0  // controller or TV: the game uses the whole screen
            screenMode == "above" || (portrait && screenMode == "beside") -> {
                val gameH = minOf((w / aspect).toInt(), (h * 0.66f).toInt())
                area = RectF(0f, 0f, 1f, gameH.toFloat() / h)
                pad = minOf((w * 0.5f).toInt(), h - gameH)
            }
            screenMode == "beside" && !portrait -> {
                // DS sideways: keep the touch screen (on the right) clear of the right-hand controls,
                // and let the picture use the rest of the screen. The left controls sit over the
                // corner of the top screen, like the N64/PSP controls do.
                pad = minOf((h * 0.55f).toInt(), (w * 0.26f).toInt())
                // The buttons are drawn in the right ~60% of their square; the picture may use the rest.
                val reserved = (pad * 0.64f).toInt()
                area = RectF(0f, 0f, (w - reserved).toFloat() / w, 1f)
            }
            portrait -> pad = (w * 0.5f).toInt()
            else -> pad = minOf((h * 0.62f).toInt(), (w * 0.32f).toInt())
        }
        if (area != pictureArea) {
            pictureArea = area
            retroView?.let { setPictureArea(it, area) }
        }
        for (v in listOf(left, right)) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            if (lp.width != pad || lp.height != pad) {
                lp.width = pad
                lp.height = pad
                v.layoutParams = lp
            }
        }
    }

    /** Where in the game view the picture is drawn (0..1, top-left origin). Runs on the emulation thread. */
    private fun setPictureArea(view: GLRetroView, r: RectF) {
        val x = r.left; val y = r.top; val w = r.width(); val h = r.height()
        view.queueEvent { com.swordfish.libretrodroid.LibretroDroid.setViewport(x, y, w, h) }
    }

    /**
     * After a DS layout change the engine reports a new picture shape a frame or two later;
     * the video library doesn't re-read it by itself, so ask it to a few times.
     */
    private fun refreshPictureShapeSoon() {
        lifecycleScope.launch {
            for (wait in longArrayOf(150, 400, 1000)) {
                kotlinx.coroutines.delay(wait)
                val view = retroView ?: return@launch
                if (frames == 0 || isFinishing || isDestroyed) continue
                view.queueEvent { com.swordfish.libretrodroid.LibretroDroid.refreshAspectRatio() }
            }
        }
    }

    // ------------------------------------------------------------------ DS screens
    private class DsView(val variables: Map<String, String>, val screenMode: String, val aspect: Float)

    private val isDs get() = activeCore.contains("melonds") || activeCore.contains("desmume")
    private var dsFull: Boolean
        get() = prefs.getBoolean("ds_full", false)
        set(v) { prefs.edit().putBoolean("ds_full", v).apply() }
    private var dsSwap: Boolean
        get() = prefs.getBoolean("ds_swap", false)
        set(v) { prefs.edit().putBoolean("ds_swap", v).apply() }

    /** "stacked", "side" or "large", from the layout the game was built with (or the way the phone is held). */
    private fun dsFamilyOf(vars: Map<String, String>): String {
        val l = vars["melonds_screen_layout1"] ?: vars["desmume_screens_layout"] ?: return dsFamily
        return when {
            l.contains("hybrid") -> "large"
            l.contains("left") || l.contains("right") -> "side"
            else -> "stacked"
        }
    }

    private fun dsView(): DsView {
        val swap = dsSwap
        val (layout, mode, shape) = when {
            dsFull -> Triple(if (swap) "bottom" else "top", if (dsFamily == "stacked") "above" else "overlay", 4f / 3f)
            dsFamily == "side" -> Triple(if (swap) "right-left" else "left-right", "beside", 512f / 192f)
            dsFamily == "large" -> Triple(if (swap) "hybrid-bottom" else "hybrid-top", "beside", 2f)
            else -> Triple(if (swap) "bottom-top" else "top-bottom", "above", 256f / 384f)
        }
        val vars = if (activeCore.contains("desmume")) {
            val d = when (layout) {
                "top" -> "top only"; "bottom" -> "bottom only"
                "hybrid-top" -> "hybrid/top"; "hybrid-bottom" -> "hybrid/bottom"
                else -> layout.replace('-', '/')
            }
            mapOf("desmume_screens_layout" to d)
        } else {
            mapOf("melonds_screen_layout1" to layout, "melonds_screen_layout2" to layout,
                "melonds_number_of_screen_layouts" to "1")
        }
        return DsView(vars, mode, shape)
    }

    private fun applyDsView() {
        val v = dsView()
        screenMode = v.screenMode; aspect = v.aspect
        retroView?.updateVariables(*v.variables.map { Variable(it.key, it.value) }.toTypedArray())
        val root = findViewById<View>(R.id.root)
        root.post { if (root.width > 0) arrangeScreen(root.width, root.height) }
        refreshPictureShapeSoon()
    }

    private fun toggleDsFull() {
        dsFull = !dsFull
        applyDsView()
        toast(if (dsFull) "Full screen (R3 switches screen)" else "Both screens")
    }

    private fun swapDsScreens() {
        dsSwap = !dsSwap
        applyDsView()
        if (dsFull) toast(if (dsSwap) "Touch screen" else "Top screen")
    }

    private fun hasGamepad(): Boolean = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id) ?: return@any false
        val s = d.sources
        !d.isVirtual && (s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
        // DS "turns with the phone": switch the screen layout live (the game keeps running).
        val turn = turnFor(newConfig) ?: return
        if (turn == currentTurn) return
        val l = config.rotateLayouts[turn] ?: return
        currentTurn = turn
        if (isDs) {
            dsFamily = dsFamilyOf(l.variables)
            applyDsView()
            return
        }
        screenMode = l.screenMode; aspect = l.aspect
        retroView?.updateVariables(*l.variables.map { Variable(it.key, it.value) }.toTypedArray())
        val root = findViewById<View>(R.id.root)
        root.post { if (root.width > 0) arrangeScreen(root.width, root.height) }
        refreshPictureShapeSoon()
    }

    private fun turnFor(c: android.content.res.Configuration): String? {
        if (config.rotateLayouts.isEmpty()) return null
        return if (c.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape"
    }

    // ------------------------------------------------------------------ physical controllers
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Second safety net: D-pad presses that Android made up from a stick ("fallback" keys)
        // never reach the game. Real D-pad buttons don't carry this flag.
        if (!menuOpen && event.flags and KeyEvent.FLAG_FALLBACK != 0 && event.keyCode in FALLBACK_DPAD) return true
        val fromPad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        if (fromPad && padsWanted && !menuOpen) {
            padsWanted = false
            updatePadsVisibility()
        }
        // Select + Start together opens the menu, for controllers without a Home/Mode button.
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) selectDown = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_START) startDown = event.action == KeyEvent.ACTION_DOWN
        if (selectDown && startDown && event.action == KeyEvent.ACTION_DOWN && !menuOpen) {
            selectDown = false; startDown = false
            retroView?.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_SELECT)
            retroView?.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_START)
            openMenu()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE || event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN) openMenu()
            return true
        }
        if (isDs && !menuOpen && (event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL || event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL) toggleDsFull() else swapDsScreens()
            }
            return true
        }
        if (!menuOpen && fromPad && event.keyCode in PAD_KEYS) {
            // Always player 1. Android numbers controllers itself, and on a TV the remote is often
            // "controller 1", which made a real gamepad player 2 (only its D-pad reached the game).
            val v = retroView
            if (v != null && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
                if (event.repeatCount == 0) v.sendKeyEvent(event.action, retroKey(event.keyCode), 0)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Android's A/B and X/Y sit the other way round from the RetroPad layout the cores expect. */
    private fun retroKey(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
        KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
        KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
        else -> keyCode
    }

    /**
     * Controller sticks, D-pad hat and analog triggers, translated the same way for every
     * controller (PS4, PS5, DualSense Edge, Switch Pro, 8BitDo, GameSir...):
     *  - D-pad hat -> D-pad; left stick -> left analog (DS: left stick -> D-pad)
     *  - right stick -> right analog (Android reports it as Z/RZ or as RX/RY depending on the controller)
     *  - analog triggers -> L2/R2 button presses, for controllers that don't also send them as buttons
     * The event is always claimed, so Android doesn't add its own D-pad presses from the sticks.
     */
    private val triggerDown = BooleanArray(2)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isStick = event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        val view = retroView
        if (menuOpen || !isStick || view == null || event.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        val dev = event.device
        fun axis(a: Int) = event.getAxisValue(a)
        fun has(a: Int) = dev?.getMotionRange(a, event.source) != null

        val hatX = axis(MotionEvent.AXIS_HAT_X)
        val hatY = axis(MotionEvent.AXIS_HAT_Y)
        val lx = axis(MotionEvent.AXIS_X)
        val ly = axis(MotionEvent.AXIS_Y)
        val useZ = has(MotionEvent.AXIS_Z) && has(MotionEvent.AXIS_RZ)
        val rx = if (useZ) axis(MotionEvent.AXIS_Z) else axis(MotionEvent.AXIS_RX)
        val ry = if (useZ) axis(MotionEvent.AXIS_RZ) else axis(MotionEvent.AXIS_RY)

        if (config.pad == "nds") {
            // The DS has no analog stick: the left stick works as the D-pad.
            fun digital(v: Float) = if (v > 0.5f) 1f else if (v < -0.5f) -1f else 0f
            view.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD,
                if (hatX != 0f) hatX else digital(lx), if (hatY != 0f) hatY else digital(ly))
        } else {
            view.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, hatX, hatY)
            view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, lx, ly)
        }
        view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, rx, ry)

        // Analog triggers -> L2 / R2, only for controllers that don't send them as buttons.
        val keys = dev?.hasKeys(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2) ?: booleanArrayOf(false, false)
        val l2 = maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE))
        val r2 = maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS))
        listOf(Triple(0, l2, KeyEvent.KEYCODE_BUTTON_L2), Triple(1, r2, KeyEvent.KEYCODE_BUTTON_R2)).forEach { (i, v, key) ->
            if (keys.getOrElse(i) { false }) return@forEach
            val down = if (triggerDown[i]) v > 0.3f else v > 0.5f
            if (down != triggerDown[i]) {
                triggerDown[i] = down
                view.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, key)
            }
        }

        if (padsWanted) { padsWanted = false; updatePadsVisibility() }
        return true
    }

    /** Hidden from the menu: taps no longer bring the controls back, only Back (menu) does. */
    private var padsLocked: Boolean
        get() = prefs.getBoolean("pads_locked", false)
        set(v) { prefs.edit().putBoolean("pads_locked", v).apply() }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (hasTouch && !padsWanted && !padsLocked && !menuOpen && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            padsWanted = true
            updatePadsVisibility()
        }
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------------ pause menu
    private fun pauseEmulation() {
        if (isDolphin && !glPaused) heldState = snapshot()
        glPaused = true
        retroView?.let {
            it.onPause()
            com.swordfish.libretrodroid.LibretroDroid.pause()
        }
    }

    private fun resumeEmulation() {
        glPaused = false
        retroView?.let {
            com.swordfish.libretrodroid.LibretroDroid.resume()
            it.onResume()
            it.requestFocus()
        }
        hideSystemBars()
    }

    private fun openMenu() {
        if (menuOpen || retroView == null) return
        menuOpen = true
        pauseEmulation()
        // Each item carries its own action, so items shown only for some games can't shift the others.
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        entries += "Resume" to { closeMenu() }
        entries += "Game information" to { showGameInfo() }
        entries += "Walkthrough" to { walkthrough() }
        if (cheats.available()) {
            val n = cheats.enabled.size
            entries += (if (n > 0) "Cheats ($n on)" else "Cheats") to { toast("Looking for cheats…"); cheats.ensure { cheats.show(retroView) { closeMenu() } } }
        }
        entries += "Save game state" to { chooseSlot(save = true) }
        entries += "Load game state" to { chooseSlot(save = false) }
        if (usesMic && isDs) entries += (if (!micGranted()) "Microphone: allow" else if (micOn) "Microphone: on" else "Microphone: off") to { toggleMicrophone() }
        if (isDs) {
            entries += (if (dsFull) "Show both screens (L3)" else "Full screen (L3)") to { closeMenu(); toggleDsFull() }
            entries += (if (dsFull) "Switch screen (R3)" else "Swap screens (R3)") to { closeMenu(); swapDsScreens() }
        }
        if (!hasTouch) entries += "Controller help" to { showControllerHelp() }
        else if (padsWanted) {
            entries += "Hide touch controls" to {
                padsWanted = false; padsLocked = true; updatePadsVisibility(); closeMenu()
                Toast.makeText(this, tr("Touch controls hidden. Press Back (or swipe back) for the menu to show them again."), Toast.LENGTH_LONG).show()
            }
        } else entries += "Show touch controls" to { padsWanted = true; padsLocked = false; updatePadsVisibility(); closeMenu() }
        if (isDolphin) entries += "Graphics: ${PICTURE_FIXES.getOrElse(pictureFix) { PICTURE_FIXES[0] }.name}" to { choosePictureFix() }
        else entries += "Screen filter: ${SCREEN_FILTERS.firstOrNull { it.first == screenFilter }?.second ?: "Original"}" to { chooseScreenFilter() }
        entries += "Back up saves" to { backUpToTelegram() }
        entries += "Restore saves" to { restoreFromTelegram() }
        entries += "Restart game" to {
            confirm("Restart the game?", "Progress since your last in-game save will be lost.") {
                saves.deleteAutoState()
                if (isDolphin) { closeMenu(); retroView?.reset(useEmulationThread = true) }
                else { retroView?.reset(useEmulationThread = false); closeMenu() }
            }
        }
        entries += (if (showSpeed) "Hide speed (fps)" else "Show speed (fps)") to {
            showSpeed = !showSpeed; speedView().visibility = if (showSpeed) View.VISIBLE else View.GONE; closeMenu()
        }
        entries += "Save problem report" to { saveProblemReport(); closeMenu() }
        entries += "Legal notices" to { showLegal() }
        entries += "Exit game" to { exitGame() }
        AlertDialog.Builder(this)
            .setTitle(config.title)
            .setItems(entries.map { tr(it.first) }.toTypedArray()) { _, which -> entries[which].second() }
            .setOnCancelListener { closeMenu() }
            .show()
    }

    /** Opens YouTube searching this game; you add the part you're stuck on (dungeon, boss, chapter). The game stays paused. */
    private fun walkthrough() {
        val name = gameTitle.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").trim().ifEmpty { gameTitle }
        val input = android.widget.EditText(this).apply {
            setSingleLine(); setText("$name walkthrough "); setSelection(text.length)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Walkthrough"))
            .setMessage(tr("Add what you're looking for (dungeon, boss, chapter…) and search on YouTube."))
            .setView(box)
            .setPositiveButton(tr("Search")) { _, _ -> openYouTube(input.text.toString().trim()) }
            .setNegativeButton(tr("Cancel"), null)
            .create()
        input.setOnEditorActionListener { _, _, _ -> dialog.dismiss(); openYouTube(input.text.toString().trim()); true }
        dialog.show()
        input.requestFocus()
    }

    private fun openYouTube(query: String) {
        if (query.isEmpty()) return
        val url = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        val tries = listOf(
            Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", query),
            Intent(Intent.ACTION_VIEW, url).setPackage("com.google.android.youtube.tv"),
            Intent(Intent.ACTION_VIEW, url).setPackage("com.amazon.firetv.youtube"),
            Intent(Intent.ACTION_VIEW, url),
        )
        for (i in tries) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) { }
        }
        toast("YouTube isn't installed on this device")
    }

    private fun showGameInfo() {
        val stored = intent.getStringExtra("meta")
        val info = runCatching { org.json.JSONObject(stored ?: "{}") }.getOrDefault(org.json.JSONObject())
        if (!info.has("title")) info.put("title", gameTitle)
        if (!info.has("console")) info.put("console", console.name.lowercase())
        if (info.optString("description").isBlank()) info.put("description", intent.getStringExtra("description") ?: "")
        val game = Game(intent.getStringExtra("gameid") ?: gameTitle, gameTitle, console, "", intent.getStringExtra("cover") ?: gameTitle, 0, 0, 0, 0, intent.getStringExtra("filename") ?: "", 0)
        Thread {
            val art = Artwork(applicationContext)
            val box = runCatching { art.cachedCover(game)?.let { android.graphics.BitmapFactory.decodeFile(it.path) } }.getOrNull()
            val shots = listOfNotNull(runCatching { art.snapshot(game, "Named_Snaps") }.getOrNull(), runCatching { art.snapshot(game, "Named_Titles") }.getOrNull())
            art.close()
            runOnUiThread { if (!isFinishing) GameInfo.show(this, info, box, shots) { closeMenu() } else closeMenu() }
        }.start()
    }

    private fun closeMenu() {
        menuOpen = false
        resumeEmulation()
    }

    private fun chooseSlot(save: Boolean) {
        val labels = (1..3).map { slot ->
            val info = saves.slotInfo(slot)
            "${tr("Slot")} $slot  —  ${info ?: tr("empty")}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (save) "Save to slot" else "Load from slot")
            .setItems(labels) { _, i ->
                val slot = i + 1
                val view = retroView ?: return@setItems closeMenu()
                if (save) {
                    val ok = runCatching {
                        val data = if (isDolphin) heldState ?: error("no state") else view.serializeState(useEmulationThread = false)
                        saves.writeSlot(slot, data)
                    }.isSuccess
                    toast(if (ok) "${tr("Saved to slot")} $slot" else "Saving failed")
                } else {
                    val data = saves.readSlot(slot)
                    if (data == null) toast("${tr("Slot")} $slot ${tr("is empty")}")
                    else if (isDolphin) {
                        // GameCube/Wii: load on the engine's own thread once it is running again.
                        closeMenu()
                        val ok = runCatching { view.unserializeState(data, useEmulationThread = true) }.getOrDefault(false)
                        toast(if (ok) "${tr("Loaded slot")} $slot" else "That save couldn't be loaded")
                        return@setItems
                    }
                    else toast(if (view.unserializeState(data, useEmulationThread = false)) "${tr("Loaded slot")} $slot" else "That save couldn't be loaded")
                }
                closeMenu()
            }
            .setOnCancelListener { closeMenu() }
            .show()
    }

    private fun confirm(title: String, body: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title).setMessage(body)
            .setPositiveButton(tr("Restart")) { _, _ -> onYes() }
            .setNegativeButton(tr("Cancel")) { _, _ -> closeMenu() }
            .setOnCancelListener { closeMenu() }
            .show()
    }

    private var speedText: TextView? = null
    private fun speedView(): TextView = speedText ?: TextView(this).apply {
        setTextColor(0xFFEDE6D6.toInt()); textSize = 13f
        setBackgroundColor(0x99000000.toInt()); setPadding(16, 8, 16, 8)
        text = "measuring…"
        this@GameActivity.findViewById<FrameLayout>(R.id.root).addView(this, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        speedText = this
    }

    private companion object {
        val PAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)
        val FALLBACK_DPAD = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private val crashFile by lazy { java.io.File(filesDir, "last-crash.txt") }

    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = java.io.StringWriter()
                error.printStackTrace(java.io.PrintWriter(sw))
                crashFile.writeText("App version ${packageManager.getPackageInfo(packageName, 0).versionName}, " +
                    "Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL}, engine $engineLabel " +
                    "(${if (is64) "64" else "32"}-bit)\n\n$sw")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun showLastCrashIfAny() {
        if (!crashFile.exists()) return
        val report = runCatching { crashFile.readText() }.getOrDefault("")
        crashFile.delete()
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics).toInt()
        val tv = TextView(this).apply {
            setText(report.take(6000)); setTextColor(0xFFC9CEDB.toInt()); textSize = 11f; setPadding(pad, pad / 2, pad, pad)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle(tr("The game closed unexpectedly last time"))
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(tr("Copy report")) { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Tirso crash report", report))
                toast("Report copied. Paste it to Claude.")
            }
            .setNegativeButton(tr("Close"), null)
            .show()
    }

    private fun showControllerHelp() {
        val ds = config.pad == "nds"
        val text = buildString {
            append("Menu: Back on the remote, or Select + Start together.\n")
            if (ds) {
                append("Full screen / both screens: L3 (press the left stick).\n")
                append("Swap screens: R3 (press the right stick).\n")
                append("D-pad: left stick or D-pad.\n")
                append("Touch screen: move the pencil with the right stick, touch with R2.\n")
            }
            when (config.pad) {
                "gc" -> append("GameCube: A B X Y as on a GameCube pad, L2/R2 = L/R, R1 = Z, right stick = C-stick.\n")
                "wii_sideways" -> append("Wii Remote (sideways): your A = 2, B = 1, X = A, Y = B, Start = +, Select = −, " +
                    "R2 = shake, R3 = Home.\n")
                "wii_nunchuk" -> append("Wii Remote + Nunchuk: left stick = Nunchuk stick, right stick = pointer, A, B, " +
                    "X = C, Y = Z, L1 = −, R1 = +, Start = 1, Select = 2, R2 = shake Remote, L2 = shake Nunchuk, R3 = Home.\n")
                "wii_classic" -> append("Classic Controller: buttons and sticks as on the Classic Controller, " +
                    "L1/R1 = ZL/ZR, L2/R2 = L/R, R3 = Home.\n")
            }
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Controller"))
            .setMessage(text)
            .setPositiveButton(tr("OK")) { _, _ -> closeMenu() }
            .setOnCancelListener { closeMenu() }
            .show()
    }

    /**
     * Saves this app's own log (the engine's messages included) to Downloads, so a problem can be
     * looked at without a computer connected. Android only lets an app read its own log lines.
     */
    private fun saveProblemReport() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val log = Runtime.getRuntime()
                        .exec(arrayOf("logcat", "-d", "-b", "main", "-b", "crash", "-v", "time", "-t", "8000"))
                        .inputStream.bufferedReader().readText()
                    val text = "Tirso problem report\n${config.title} (${packageName}, version " +
                        "${packageManager.getPackageInfo(packageName, 0).versionName}, build ${config.buildId})\n" +
                        "Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                        "engine $engineLabel (${if (is64) "64" else "32"}-bit)" +
                        (if (isDolphin) ", graphics: ${PICTURE_FIXES.getOrElse(pictureFix) { PICTURE_FIXES[0] }.name}" else "") +
                        "\n(includes this game's earlier runs, so a crash shows up too)\n\n$log"
                    val name = "Tirso report - ${safeName()} - ${System.currentTimeMillis() / 1000}.txt"
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: error("no Downloads")
                        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no stream")
                    } else {
                        java.io.File(getExternalFilesDir(null), name).writeText(text)
                    }
                }.isSuccess
            }
            Toast.makeText(this@GameActivity,
                if (ok) tr("Problem report saved in Downloads. Send it to Claude.") else tr("The report couldn't be saved."),
                Toast.LENGTH_LONG).show()
        }
    }

    private fun showLegal() {
        val text = runCatching { assets.open("licenses/NOTICE.txt").bufferedReader().use { it.readText() } }
            .getOrDefault("Legal notices are missing from this build.")
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics).toInt()
        val tv = TextView(this).apply {
            setText(text); setTextColor(0xFFC9CEDB.toInt()); textSize = 12f; setPadding(pad, pad / 2, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Legal notices"))
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(tr("Close")) { _, _ -> closeMenu() }
            .setOnCancelListener { closeMenu() }
            .show()
    }

    // ------------------------------------------------------------------ custom GPU driver (GameCube / Wii)
    /**
     * Unpacks the GPU driver pack Tirso Compiler put in this game (for example Mesa Turnip) into
     * the app's private files (Android only loads drivers from there) and tells the Vulkan bridge
     * where it is. On phones it can't run on, the bridge falls back to the phone's own driver.
     */
    private fun prepareGpuDriver(use: Boolean) {
        val vars = listOf("LIBRETRODROID_VK_DRIVER_DIR", "LIBRETRODROID_VK_DRIVER_LIB",
            "LIBRETRODROID_VK_HOOK_DIR", "LIBRETRODROID_VK_TMP_DIR")
        vars.forEach { runCatching { android.system.Os.unsetenv(it) } }
        val asset = config.gpuDriver ?: return
        if (!use) return
        try {
            val root = File(filesDir, "gpu_driver")
            val tag = config.gpuDriverSha1.ifBlank { "driver" }.take(12)
            val dir = File(root, tag)
            val done = File(dir, ".ready")
            if (!done.exists()) {
                root.deleteRecursively()
                dir.mkdirs()
                assets.open("tirso/$asset").use { input ->
                    java.util.zip.ZipInputStream(input).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val name = entry.name.substringAfterLast('/')
                            if (entry.isDirectory || name.isEmpty() || entry.name.contains("..")) continue
                            File(dir, name).outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
                done.writeText("ok")
            }
            val meta = File(dir, "meta.json")
            val library = runCatching { org.json.JSONObject(meta.readText()).optString("libraryName") }.getOrNull()
                ?.ifBlank { null }
                ?: dir.listFiles { f -> f.name.endsWith(".so") }?.firstOrNull()?.name
                ?: return
            File(dir, "redirect").mkdirs()
            val tmp = File(cacheDir, "gpu_tmp").apply { mkdirs() }
            fun withSlash(path: String) = if (path.endsWith("/")) path else "$path/"
            android.system.Os.setenv("LIBRETRODROID_VK_DRIVER_DIR", withSlash(dir.absolutePath), true)
            android.system.Os.setenv("LIBRETRODROID_VK_DRIVER_LIB", library, true)
            android.system.Os.setenv("LIBRETRODROID_VK_HOOK_DIR", withSlash(applicationInfo.nativeLibraryDir), true)
            android.system.Os.setenv("LIBRETRODROID_VK_TMP_DIR", withSlash(tmp.absolutePath), true)
            Log.i("Tirso", "GPU driver ready: ${config.gpuDriverName} ($library)")
        } catch (e: Exception) {
            Log.w("Tirso", "GPU driver couldn't be prepared; using the phone's driver", e)
            vars.forEach { runCatching { android.system.Os.unsetenv(it) } }
        }
    }

    // ------------------------------------------------------------------ picture fix (GameCube / Wii)
    private val isDolphin get() = config.core.contains("dolphin")
    /** True while the engine's thread is stopped (menu open or app in the background). */
    private var glPaused = false
    /** GameCube/Wii: the game's state at the moment it was last paused. */
    private var heldState: ByteArray? = null
    private fun snapshot(): ByteArray? =
        runCatching { retroView?.serializeState(useEmulationThread = true) }.getOrNull()?.takeIf { it.isNotEmpty() }
    private val prefs get() = getSharedPreferences("tirso_player", MODE_PRIVATE)
    private val pictureFix get() = prefs.getInt("picture_fix_v5", if (config.graphics == "opengl") SAFE_PICTURE else 0)

    /** The way the picture is scaled to the screen (the game's own resolution stays the same). */
    private val screenFilter get() = prefs.getString("screen_filter", null) ?: config.shader

    private fun shaderFor(id: String): ShaderConfig = when (id) {
        "sharp" -> ShaderConfig.Sharp
        "smooth" -> ShaderConfig.CUT()
        "smooth2" -> ShaderConfig.CUT2()
        "lcd" -> ShaderConfig.LCD
        "crt" -> ShaderConfig.CRT
        else -> ShaderConfig.Default
    }

    private fun chooseScreenFilter() {
        val ids = SCREEN_FILTERS.map { it.first }
        AlertDialog.Builder(this)
            .setTitle(tr("Screen filter"))
            .setSingleChoiceItems(SCREEN_FILTERS.map { tr(it.second) + "\n" + tr(it.third) }.toTypedArray(),
                ids.indexOf(screenFilter).coerceAtLeast(0)) { d, which ->
                prefs.edit().putString("screen_filter", ids[which]).apply()
                retroView?.shader = shaderFor(ids[which])
                d.dismiss(); closeMenu()
            }
            .setOnCancelListener { closeMenu() }
            .show()
    }

    private fun choosePictureFix() {
        val names = PICTURE_FIXES.map { it.name + "\n" + it.about }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(tr("Graphics"))
            .setSingleChoiceItems(names, pictureFix.coerceIn(0, PICTURE_FIXES.size - 1)) { d, which ->
                d.dismiss()
                if (which == pictureFix) { closeMenu(); return@setSingleChoiceItems }
                confirm("Use \"${PICTURE_FIXES[which].name}\"?",
                    "This changes how GameCube and Wii pictures are drawn. " +
                    "The game will close, and you open it again to use this setting." +
                    (if (config.autoResume) " It continues where you are now." else " Save in the game first.")) {
                    prefs.edit().putInt("picture_fix_v5", which).commit()
                    restartProcess()
                }
            }
            .setOnCancelListener { closeMenu() }
            .show()
    }

    /** Closes the game completely; the next time it is opened the engine starts with the new setting. */
    private fun restartProcess() {
        persist()
        menuOpen = false
        AlertDialog.Builder(this)
            .setTitle(tr("Graphics setting saved"))
            .setMessage(tr("The game will close now. Open it again from your home screen to use the new setting."))
            .setCancelable(false)
            .setPositiveButton(tr("Close game")) { _, _ ->
                finish()
                window.decorView.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 700)
            }
            .show()
    }

    private fun exitGame() {
        persist()
        autoBackup()
        menuOpen = false
        finish()
    }

    // ------------------------------------------------------------------ saving
    override fun onPause() {
        super.onPause()  // the emulator view pauses itself through the lifecycle
        persist()
    }

    override fun onStart() {
        super.onStart()
        if (!isolated) TirsoDrive.get(this).gameVisible(true)
    }

    override fun onStop() {
        super.onStop()
        if (!isolated) TirsoDrive.get(this).gameVisible(false)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
    }

    private fun entry() = SaveBackups.Entry(saves.root, romSha, gameTitle, console)

    /** Sends the saves to the Telegram backup channel on its own when they changed (when TirsoDrive is connected). */
    private fun autoBackup() {
        if (romSha.isEmpty() || isolated) return  // the app uploads changed saves itself when you come back to it
        val drive = TirsoDrive.get(this)
        if (!drive.ready() || !SaveBackups.changed(this, entry())) return
        val app = applicationContext
        Toast.makeText(app, tr("Backing up your save to Telegram…"), Toast.LENGTH_SHORT).show()
        SaveBackups.upload(this, drive, entry(), true) { ok, _ ->
            if (!ok) Log.w("Tirso", "Automatic save backup failed")
            Toast.makeText(app, tr(if (ok) "Save backed up to Telegram" else "Save backup failed"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun backUpToTelegram() {
        if (isolated) {
            runCatching { persist() }; closeMenu()
            toast("Saves will be backed up to Telegram when you leave the game")
            return
        }
        val drive = TirsoDrive.get(this)
        if (!drive.ready()) { toast("Connect TirsoDrive on the home screen to back up to Telegram"); closeMenu(); return }
        runCatching { persist() }
        closeMenu()
        toast("Backing up to Telegram…")
        SaveBackups.upload(this, drive, entry(), true) { _, msg -> toast(msg) }
    }

    private fun restoreFromTelegram() {
        if (isolated) {
            toast("To restore from Telegram, leave the game and use Settings > Save backups")
            closeMenu()
            return
        }
        val drive = TirsoDrive.get(this)
        if (!drive.ready()) { toast("Connect TirsoDrive on the home screen to restore from Telegram"); closeMenu(); return }
        toast("Looking in Telegram…")
        SaveBackups.fetch(drive, romSha) { list, err ->
            if (list == null) { toast(err ?: "Couldn't read the backup channel"); closeMenu(); return@fetch }
            if (list.isEmpty()) { toast("No backup of this game in Telegram yet"); closeMenu(); return@fetch }
            val labels = list.mapIndexed { i, b -> (if (i == 0) tr("Latest") + " · " else "") + SaveBackups.describe(this, b) }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(tr("Restore saves from Telegram"))
                .setItems(labels) { _, which ->
                    val b = list[which]
                    confirm("Restore this backup?", "Your current saves for $gameTitle will be replaced, and the game will close so you can reopen it.") {
                        toast("Restoring…")
                        SaveBackups.restore(this, drive, b) { ok, msg ->
                            toast(msg)
                            if (ok) { retroView = null; finish() } else closeMenu()
                        }
                    }
                }
                .setNegativeButton(tr("Cancel")) { _, _ -> closeMenu() }
                .setOnCancelListener { closeMenu() }
                .show()
        }
    }

    /** Called while the emulator is paused: native calls run on this thread. */
    private fun persist() {
        val view = retroView ?: return
        if (isDolphin) {
            // Dolphin must save from its own thread: use the copy taken when the game was paused.
            if (config.autoResume) heldState?.let {
                runCatching { saves.writeAutoState(it) }
                prefs.edit().putBoolean("dolphin_state_v2", true).apply()
            }
            return
        }
        runCatching { saves.writeSram(view.serializeSRAM(useEmulationThread = false)) }
        if (config.autoResume) runCatching { saves.writeAutoState(view.serializeState(useEmulationThread = false)) }
    }

    // ------------------------------------------------------------------ helpers
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun safeName() = gameTitle.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifBlank { "game" }

    private fun toast(text: String) = Toast.makeText(this, tr(text), Toast.LENGTH_SHORT).show()

    private fun fatal(text: String) {
        findViewById<View>(R.id.loading)?.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle(tr("RetroStream"))
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton(tr("Close")) { _, _ -> finish() }
            .show()
    }
}

/** One choice in the GameCube/Wii "Graphics" menu. */
class PictureFix(val name: String, val about: String, val glOff: String,
                 val variables: Map<String, String> = emptyMap(), val vulkan: Boolean = false,
                 val customDriver: Boolean = false)

/** Game-drawn textures (ground marks, effects) go through the console's memory, as on a real GameCube. */
private val COPIES_TO_RAM = mapOf("dolphin_efb_to_texture" to "disabled")

/** The choice used when another one stops the game from starting. */
const val SAFE_PICTURE = 1
const val RESTORE_ACTION = "com.tirso.action.RESTORE_SAVES"

/** id, name, what it does. Applied by the video library when the picture is scaled to the screen. */
val SCREEN_FILTERS = listOf(
    Triple("default", "Original", "Plain scaling, a little soft."),
    Triple("sharp", "Sharp pixels", "Crisp, evenly sized pixels."),
    Triple("smooth", "Smooth upscale", "Smooths jagged edges: pixel art looks closer to a high-resolution picture."),
    Triple("smooth2", "Smooth upscale, strong", "Stronger smoothing (uses more battery)."),
    Triple("lcd", "Handheld LCD", "Shows the pixel grid like the original handheld screen."),
    Triple("crt", "CRT TV", "Scanlines like an old TV."),
)

val PICTURE_FIXES = listOf(
    PictureFix("Vulkan", "Draws the way the Dolphin app does. Uses this game's GPU driver if it has one.", "",
        vulkan = true, customDriver = true),
    PictureFix("OpenGL ES", "The older way. Use it if Vulkan shows a black screen.", ""),
    PictureFix("OpenGL ES, exact textures", "OpenGL ES with game-drawn textures copied the exact way.", "", COPIES_TO_RAM),
    PictureFix("Vulkan, phone's own driver", "Vulkan without this game's GPU driver.", "", vulkan = true),
    PictureFix("Check for errors", "Vulkan, and records graphics errors for a problem report.", "",
        mapOf("dolphin_log_host_gpu_enabled" to "enabled", "dolphin_log_video_enabled" to "enabled",
            "dolphin_log_level" to "4"), vulkan = true, customDriver = true),
)
