package com.tirso.tirsolador

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Phone remote, TV side: the RetroStream phone app finds this TV on the Wi-Fi, pairs with a 4-digit code and then can
 * start games, see what is playing and pause / save / load / take screenshots / back up / exit. Nothing goes through the internet.
 *
 * Same protocol as the PC app (windows/lib/remote.js):
 *  - Finding: "RSDISCOVER1" by UDP broadcast to port 47111; the TV answers with one JSON line {rs, id, name, kind, port}.
 *  - Talking: TCP port 47110, one JSON line in, one JSON line out, then the connection closes.
 *
 * The game screen (GameActivity, sometimes in its own process) is reached with broadcasts inside the app:
 * ACTION_CMD carries the phone's command to it, ACTION_STATE carries what it is doing back here.
 */
object Remote {
    const val PORT = 47110
    const val FIND_PORT = 47111
    const val ACTION_CMD = "com.tirso.retrostream.REMOTE_CMD"
    const val ACTION_STATE = "com.tirso.retrostream.REMOTE_STATE"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var started = false
    @Volatile var error = ""

    // What is playing (from GameActivity's broadcasts).
    @Volatile private var now: JSONObject? = null
    @Volatile private var since = 0L
    @Volatile private var paused = false
    @Volatile private var ff = false
    @Volatile private var controls = JSONArray()
    @Volatile private var settings = JSONObject()
    @Volatile private var relaunch: String? = null
    @Volatile private var pending: JSONObject? = null
    @Volatile private var pendingAt = 0L
    @Volatile private var answer: JSONObject? = null
    @Volatile private var answerLatch: CountDownLatch? = null

    // Our own screens in this process (to show the pairing code and to know the app is open).
    private var resumed: WeakReference<Activity>? = null
    @Volatile private var visible = 0

    // Pairing
    @Volatile private var code: String? = null
    @Volatile private var codeUntil = 0L
    @Volatile private var tries = 0
    private var codeDialog: AlertDialog? = null
    private val random = SecureRandom()

    private fun prefs() = app.getSharedPreferences("remote", Context.MODE_PRIVATE)
    @JvmStatic fun enabled(ctx: Context) = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE).getBoolean("on", true)
    @JvmStatic fun setEnabled(ctx: Context, on: Boolean) = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
    @JvmStatic fun phones(ctx: Context): List<String> {
        val j = runCatching { JSONObject(ctx.getSharedPreferences("remote", Context.MODE_PRIVATE).getString("phones", "{}")!!) }.getOrDefault(JSONObject())
        return j.keys().asSequence().map { j.optJSONObject(it)?.optString("name") ?: "" }.toList()
    }
    @JvmStatic fun forget(ctx: Context) = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE).edit().putString("phones", "{}").apply()
    private fun id(): String {
        var id = prefs().getString("id", null)
        if (id == null) { id = (1..16).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString(""); prefs().edit().putString("id", id).apply() }
        return id
    }
    fun name(ctx: Context): String = runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).trim()

    /** TV app only: starts listening (once per app process). */
    @JvmStatic fun start(ctx: Context) {
        if (started || BuildConfig.PHONE) return
        started = true
        app = ctx.applicationContext
        (app as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) { visible++ }
            override fun onActivityResumed(a: Activity) { resumed = WeakReference(a) }
            override fun onActivityPaused(a: Activity) { if (resumed?.get() === a) resumed = null }
            override fun onActivityStopped(a: Activity) { visible = maxOf(0, visible - 1) }
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onState(i)
        }, IntentFilter(ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        pool.execute { serve() }
        pool.execute { answerFinders() }
    }

    // ------------------------------------------------------------------ the game screen tells us what it is doing
    private fun onState(i: Intent) {
        when (i.getStringExtra("event")) {
            "started" -> {
                val gid = i.getStringExtra("gameid") ?: ""
                val parts = gid.split(":")
                now = JSONObject().put("id", gid).put("title", i.getStringExtra("title") ?: "").put("console", i.getStringExtra("console") ?: "")
                    .put("fileName", i.getStringExtra("filename") ?: "")
                    .put("chatId", if (parts.size == 4) parts[2].toLongOrNull() ?: 0 else 0).put("messageId", if (parts.size == 4) parts[3].toLongOrNull() ?: 0 else 0)
                since = System.currentTimeMillis(); paused = false; ff = false; pending = null
                controls = JSONArray(i.getStringArrayExtra("controls")?.toList() ?: listOf("pause", "resume", "save", "load", "shot", "ff", "reset", "backup", "exit"))
                settings = JSONObject().put("res", i.getStringExtra("res") ?: "").put("filter", i.getStringExtra("filter") ?: "")
                    .put("resOptions", JSONArray(if (controls.toString().contains("\"res\"")) listOf("low", "medium", "high") else emptyList<String>()))
                    .put("filters", JSONArray(i.getStringArrayExtra("filters")?.toList() ?: emptyList<String>()))
            }
            "state" -> { paused = i.getBooleanExtra("paused", paused); ff = i.getBooleanExtra("ff", ff); i.getStringExtra("filter")?.let { if (it.isNotEmpty()) settings.put("filter", it) } }
            "exited" -> {
                val was = now; now = null; paused = false; ff = false
                // Resolution changed from the phone: open the game again (it continues where it was).
                val again = relaunch; relaunch = null
                if (again != null && was?.optString("id") == again) { pending = was; pendingAt = System.currentTimeMillis() }
                if (again != null && was?.optString("id") == again) main.postDelayed({
                    runCatching { app.startActivity(Intent(app, MainActivity::class.java).putExtra("play_game", again)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                }, 900)
            }
            "done" -> {
                answer = JSONObject().put("ok", i.getBooleanExtra("ok", false)).put("error", i.getStringExtra("error") ?: "")
                    .also { a -> i.getByteArrayExtra("jpg")?.let { a.put("jpg", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)) } }
                    .also { a -> i.getStringExtra("list")?.let { a.put("list", runCatching { JSONArray(it) }.getOrDefault(JSONArray())) } }
                answerLatch?.countDown()
            }
        }
    }
    /** The TV's own screen is about to download / start this game (asked from the phone or not). */
    @JvmStatic fun loading(g: Game) { pending = brief(g); pendingAt = System.currentTimeMillis() }

    private fun brief(g: Game) = JSONObject().put("id", g.id).put("title", g.title).put("console", g.console.name)
        .put("chatId", g.chatId).put("messageId", g.messageId).put("fileName", g.fileName ?: "")

    // ------------------------------------------------------------------ network
    /** Listens for the phone forever: if the TV's Wi-Fi drops and the socket breaks, it starts listening again. */
    private fun serve() {
        while (true) {
            try {
                ServerSocket().use { server ->
                    server.reuseAddress = true; server.bind(InetSocketAddress(PORT)); error = ""
                    while (true) { val s = server.accept(); pool.execute { talk(s) } }
                }
            } catch (e: Exception) { error = e.toString(); Log.w("Remote", "server", e) }
            Thread.sleep(3000)
        }
    }
    private fun talk(s: Socket) {
        runCatching {
            s.soTimeout = 15000
            val line = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine() ?: return@runCatching
            if (line.length > 65536) return@runCatching
            val res = try { handle(JSONObject(line)) } catch (e: Exception) { JSONObject().put("error", e.message ?: "error") }
            s.getOutputStream().write((res.toString() + "\n").toByteArray(Charsets.UTF_8)); s.getOutputStream().flush()
        }
        runCatching { s.close() }
    }
    private fun answerFinders() {
        while (true) { findersOnce(); Thread.sleep(3000) }   // starts again after any network problem
    }
    private fun findersOnce() {
        val lock = runCatching {
            (app.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).createMulticastLock("retrostream-remote").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        try {
            DatagramSocket(null).use { u ->
                u.reuseAddress = true; u.broadcast = true; u.bind(InetSocketAddress(FIND_PORT))
                val buf = ByteArray(512)
                while (true) {
                    val p = DatagramPacket(buf, buf.size)
                    u.receive(p)
                    if (!enabled(app) || String(p.data, 0, p.length).trim() != "RSDISCOVER1") continue
                    val reply = (JSONObject().put("rs", 1).put("id", id()).put("name", name(app)).put("kind", "tv").put("port", PORT).toString() + "\n").toByteArray()
                    runCatching { u.send(DatagramPacket(reply, reply.size, p.address, p.port)) }
                }
            }
        } catch (e: Exception) { Log.w("Remote", "finders", e) } finally { runCatching { lock?.release() } }
    }

    private fun base() = JSONObject().put("id", id()).put("name", name(app)).put("kind", "tv").put("version", BuildConfig.VERSION_NAME)

    private fun handle(req: JSONObject): JSONObject {
        if (!enabled(app)) return JSONObject().put("error", "off")
        val cmd = req.optString("cmd")
        val phones = runCatching { JSONObject(prefs().getString("phones", "{}")!!) }.getOrDefault(JSONObject())
        val token = req.optString("token")
        if (cmd == "hello") return base().put("ok", true).put("paired", token.isNotEmpty() && phones.has(token))
        if (cmd == "pair") {
            val given = req.optString("code")
            val phone = req.optString("phone").take(40).ifBlank { "Phone" }
            if (given.isEmpty()) {
                val c = String.format(java.util.Locale.ROOT, "%04d", random.nextInt(10000))
                code = c; codeUntil = System.currentTimeMillis() + 120000; tries = 0
                main.post { showCode(c, phone) }
                return base().put("ok", true).put("needCode", true)
            }
            val c = code
            if (c == null || System.currentTimeMillis() > codeUntil) return JSONObject().put("error", "expired")
            if (++tries > 5) { code = null; main.post { hideCode() }; return JSONObject().put("error", "expired") }
            if (given != c) return JSONObject().put("error", "wrong code")
            code = null; main.post { hideCode(); Toast.makeText(app, T.t("Teléfono vinculado: ") + phone, Toast.LENGTH_LONG).show() }
            val t = (1..32).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")
            phones.put(t, JSONObject().put("name", phone).put("at", System.currentTimeMillis()))
            prefs().edit().putString("phones", phones.toString()).apply()
            return base().put("ok", true).put("token", t)
        }
        if (token.isEmpty() || !phones.has(token)) return JSONObject().put("error", "unpaired")
        return when (cmd) {
            "status" -> base().put("ok", true).also { status(it) }
            "play" -> play(req)
            "control" -> control(req)
            else -> JSONObject().put("error", "unknown command")
        }
    }
    private fun status(o: JSONObject) {
        val n = now
        if (pending != null && System.currentTimeMillis() - pendingAt > 15 * 60000) pending = null
        when {
            n != null -> o.put("state", "playing").put("game", n).put("since", since).put("paused", paused).put("ff", ff).put("controls", controls).put("settings", settings)
            pending != null -> o.put("state", "loading").put("game", pending).put("since", pendingAt)
            else -> o.put("state", "menu")
        }
    }
    private fun play(req: JSONObject): JSONObject {
        val want = req.optJSONObject("game") ?: return JSONObject().put("error", "no game")
        val games = Library(app).games
        val id = want.optString("id"); val chat = want.optLong("chatId"); val msg = want.optLong("messageId")
        val g = games.firstOrNull { it.id == id }
            ?: games.firstOrNull { chat != 0L && it.chatId == chat && it.messageId == msg }
            ?: games.firstOrNull { it.console.name == want.optString("console") && it.fileName != null && it.fileName == want.optString("fileName") }
            ?: return JSONObject().put("error", "not found")
        // Android only lets an app open a screen while it is on the TV: RetroStream must be open (any of its screens).
        if (visible == 0 && now == null) return JSONObject().put("error", "tv_closed")
        pending = brief(g); pendingAt = System.currentTimeMillis()
        main.post {
            runCatching {
                app.startActivity(Intent(app, MainActivity::class.java).putExtra("play_game", g.id).putExtra("play_fresh", req.optBoolean("fresh"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }.onFailure { Log.w("Remote", "play", it) }
        }
        return JSONObject().put("ok", true)
    }
    @Synchronized private fun control(req: JSONObject): JSONObject {
        if (now == null) return JSONObject().put("error", "not playing")
        val action = req.optString("action")
        val waits = action == "save" || action == "load" || action == "backup" || action == "frame" || action == "res" || action == "cheats" || action == "cheat" || action == "cheatsoff"
        val latch = CountDownLatch(1)
        if (waits) { answer = null; answerLatch = latch }
        val value = req.optString("value")
        if (action == "res") { relaunch = now?.optString("id"); settings.put("res", value) }
        if (action == "filter") settings.put("filter", value)
        app.sendBroadcast(Intent(ACTION_CMD).setPackage(app.packageName).putExtra("action", action).putExtra("slot", req.optInt("slot", 1)).putExtra("value", value))
        if (!waits) return JSONObject().put("ok", true)
        latch.await(if (action == "backup" || action == "cheats") 25 else 8, TimeUnit.SECONDS)
        val a = answer ?: return JSONObject().put("error", "timeout")
        if (action == "res" && !a.optBoolean("ok")) relaunch = null
        return if (a.optBoolean("ok")) JSONObject().put("ok", true).also { r -> if (a.has("jpg")) r.put("jpg", a.getString("jpg")); if (a.has("list")) r.put("list", a.getJSONArray("list")) } else JSONObject().put("error", a.optString("error").ifEmpty { "failed" })
    }

    // ------------------------------------------------------------------ the pairing code on the TV
    private fun showCode(c: String, phone: String) {
        hideCode()
        val a = resumed?.get()
        if (a == null || a.isFinishing) {
            // A game in its own process is in front: a long message instead of a window.
            Toast.makeText(app, T.t("Código para el teléfono: ") + c, Toast.LENGTH_LONG).show()
            main.postDelayed({ if (code == c) Toast.makeText(app, T.t("Código para el teléfono: ") + c, Toast.LENGTH_LONG).show() }, 3600)
            return
        }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(40, 30, 40, 10) }
        col.addView(TextView(a).apply { text = phone + T.t(" quiere controlar RetroStream en esta TV. Escribe este código en el teléfono:"); textSize = 18f; gravity = Gravity.CENTER })
        col.addView(TextView(a).apply { text = c.toCharArray().joinToString("  "); textSize = 56f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, 24, 0, 18) })
        col.addView(TextView(a).apply { text = T.t("El código sirve durante 2 minutos."); textSize = 14f; gravity = Gravity.CENTER; alpha = .7f })
        codeDialog = AlertDialog.Builder(a).setTitle(T.t("Vincular teléfono")).setView(col).setNegativeButton(T.t("Cerrar"), null).show()
        main.postDelayed({ if (code == c) hideCode() }, 120000)
    }
    private fun hideCode() { runCatching { codeDialog?.dismiss() }; codeDialog = null }
}
