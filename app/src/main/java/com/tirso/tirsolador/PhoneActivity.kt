package com.tirso.tirsolador

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The phone app's own interface (RetroStream-phone.apk): made for touch and for a controller, upright or sideways.
 * The TV app keeps MainActivity; the phone app opens MainActivity only for a few settings pages.
 */
class PhoneActivity : Activity() {

    // ------------------------------------------------------------------ look (four themes, light or dark)
    private class Pal(
        val bg: Int, val surface: Int, val surface2: Int, val text: Int, val muted: Int,
        val primary: Int, val onPrimary: Int, val ring: Int, val outline: Int, val radius: Float, val outlined: Boolean,
    )

    private fun pal(): Pal {
        val theme = library.theme()
        val key = when (theme) { "GBA clásico" -> "gba"; "Mosaico" -> "tiles"; "Consola completa" -> "console"; else -> "midnight" }
        val dark = library.dark(key)
        style = key
        return when (key) {
            "gba" -> if (dark) Pal(0xFF14142A.toInt(), 0xFF1F1F3D.toInt(), 0xFF2A2A52.toInt(), 0xFFECEBFF.toInt(), 0xFFA9A6D6.toInt(),
                0xFFAFA9EC.toInt(), 0xFF14142A.toInt(), 0xFFECEBFF.toInt(), 0xFFAFA9EC.toInt(), 14f, true)
            else Pal(0xFFEDE8F7.toInt(), Color.WHITE, 0xFFF4F1FB.toInt(), 0xFF26215C.toInt(), 0xFF534AB7.toInt(),
                0xFF26215C.toInt(), Color.WHITE, 0xFF26215C.toInt(), 0xFF26215C.toInt(), 14f, true)
            "tiles" -> if (dark) Pal(0xFF101820.toInt(), 0xFF1B2733.toInt(), 0xFF243444.toInt(), Color.WHITE, 0xFF9FB3C8.toInt(),
                0xFFFFC857.toInt(), 0xFF1B1300.toInt(), Color.WHITE, 0x33FFFFFF, 18f, false)
            else Pal(0xFFF3F5F8.toInt(), Color.WHITE, 0xFFE7ECF2.toInt(), 0xFF101820.toInt(), 0xFF5B6B7C.toInt(),
                0xFFFF9A2E.toInt(), Color.WHITE, 0xFF101820.toInt(), 0x22101820, 18f, false)
            "console" -> if (dark) Pal(0xFF0E1018.toInt(), 0xFF1A1E2B.toInt(), 0xFF1C2130.toInt(), 0xFFE9ECF5.toInt(), 0xFF8C93A8.toInt(),
                0xFFE9ECF5.toInt(), 0xFF14161E.toInt(), 0xFFE9ECF5.toInt(), 0x22FFFFFF, 14f, false)
            else Pal(0xFFF2F3F7.toInt(), Color.WHITE, 0xFFE6E8EF.toInt(), 0xFF14161E.toInt(), 0xFF5D6478.toInt(),
                0xFF14161E.toInt(), Color.WHITE, 0xFF14161E.toInt(), 0x2214161E, 14f, false)
            else -> if (dark) Pal(0xFF07111F.toInt(), 0xFF0F1D30.toInt(), 0xFF15263D.toInt(), 0xFFE6F4FF.toInt(), 0xFF8AA4C2.toInt(),
                0xFF2DE2C8.toInt(), 0xFF04121F.toInt(), 0xFF2DE2C8.toInt(), 0x332DE2C8, 16f, false)
            else Pal(0xFFF1F7FB.toInt(), Color.WHITE, 0xFFE3EEF6.toInt(), 0xFF0B2236.toInt(), 0xFF4D6A86.toInt(),
                0xFF0E9F8C.toInt(), Color.WHITE, 0xFF0E9F8C.toInt(), 0x330E9F8C, 16f, false)
        }
    }

    private lateinit var p: Pal
    /** console | midnight | tiles | gba: how cards and tiles are drawn, not just their colors. */
    private var style = "console"
    private fun logoStyle(): String { val u = getSharedPreferences("ui", MODE_PRIVATE); return u.getString("logo_style", null) ?: if (u.getBoolean("logos", true)) "color" else "text" }
    private lateinit var library: Library
    private lateinit var artwork: Artwork
    private lateinit var drive: TirsoDrive
    private lateinit var root: FrameLayout
    private var page = "home"
    private var console: Console? = null
    private var source = "all"       // all | telegram | phone
    private var query = ""
    private var selected: Game? = null
    private var freshStart = false        // "Play from the start": don't load where you left off
    private var downloadedOnly = false
    private var collectionName = ""     // source "col": the library shows this collection
    private var lastFocus = ""
    private val driveListener = TirsoDrive.Listener { catalog -> onDrive(catalog) }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Int) = dp(v.toFloat())
    private val landscape get() = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        T.init(this)
        TemporaryRoms.sweep(this)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        library = Library(this); artwork = Artwork(this); drive = TirsoDrive.get(this)
        root = FrameLayout(this); setContentView(root)
        state?.let {
            page = it.getString("page", "home"); source = it.getString("source", "all"); query = it.getString("query", "")
            console = runCatching { Console.valueOf(it.getString("console", "")) }.getOrNull()
            selected = library.games.firstOrNull { g -> g.id == it.getString("game") }
            if (page == "loading" || page == "detail" && selected == null) page = if (console != null) "library" else "home"
        }
        if (state == null && !drive.onboarded() && !drive.skipped()) page = "welcome"
        drive.attach(driveListener); drive.start()
        scrape()
        if (PhoneGames.scanOn(this) && PhoneGames.folders(this).isNotEmpty() && PhoneGames.canReadFolders()) scanFolders()
        render()
        if (intent?.hasExtra("play_game") == true) root.post { playFromShortcut() }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("page", page); out.putString("source", source); out.putString("query", query)
        out.putString("console", console?.name ?: ""); out.putString("game", selected?.id ?: "")
    }

    override fun onStart() { super.onStart(); drive.appVisible(true) }
    override fun onStop() { drive.appVisible(false); super.onStop() }
    override fun onResume() {
        super.onResume()
        drive.attach(driveListener); drive.inMenu()
        val lockLand = PhoneGames.lockLandscape(this)
        val want = if (lockLand) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        if (requestedOrientation != want) requestedOrientation = want
        library = Library(this)
        if (drive.ready()) SaveBackups.uploadAll(this, drive, { }, { _, _ -> })
        Screenshots.uploadPending(this, drive)
        render()
        startPolling()
    }
    override fun onPause() { stopPolling(); super.onPause() }
    override fun onDestroy() {
        drive.detach(driveListener); artwork.close()
        if (isFinishing) { drive.releaseAll(); TemporaryRoms.purge(applicationContext) }
        super.onDestroy()
    }
    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); render() }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); playFromShortcut() }

    private fun onDrive(catalog: Boolean) {
        if (catalog) { library = Library(this); scrape() }
        if (!drive.skipped() && (drive.stage == TirsoDrive.Stage.QR || drive.stage == TirsoDrive.Stage.PHONE ||
                drive.stage == TirsoDrive.Stage.CODE || drive.stage == TirsoDrive.Stage.PASSWORD) && page != "welcome") page = "welcome"
        when (page) {
            "welcome" -> render()
            "loading" -> updateLoading()
            "home", "library", "consoles" -> if (catalog) render() else updateBadge()
            "friends" -> if (!friendsQueued) { friendsQueued = true; root.postDelayed({ friendsQueued = false; if (page == "friends") render() }, 600) }
            "settings" -> render()
        }
    }

    // ------------------------------------------------------------------ building blocks
    private fun tv(s: CharSequence, sp: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); includeFontPadding = false
        typeface = if (mono) Typeface.MONOSPACE else if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
    }
    private fun round(color: Int, r: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke > 0) setStroke(stroke, strokeColor)
    }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /** Makes a view a focusable, clickable control with the theme's focus ring (for controllers). */
    private fun control(v: View, tag: String, bg: Int, r: Float = p.radius, outline: Boolean = p.outlined, click: () -> Unit): View {
        v.tag = tag; v.isFocusable = true; v.isClickable = true
        fun paint(f: Boolean) {
            v.background = round(bg, r, if (f) dp(3) else if (outline) dp(1.5f) else 0, if (f) p.ring else p.outline)
        }
        paint(false)
        v.setOnFocusChangeListener { _, has -> paint(has); if (has) lastFocus = tag }
        v.setOnClickListener { click() }
        return v
    }
    private fun button(label: String, tag: String, primary: Boolean = false, click: () -> Unit): TextView {
        val t = tv(label, 15f, if (primary) p.onPrimary else p.text, bold = true)
        t.gravity = Gravity.CENTER; t.setPadding(dp(16), dp(12), dp(16), dp(12))
        control(t, tag, if (primary) p.primary else p.surface2, 12f, false, click)
        return t
    }
    private fun chip(label: String, on: Boolean, tag: String, click: () -> Unit): TextView {
        val t = tv(label, 13f, if (on) p.onPrimary else p.text)
        t.setPadding(dp(12), dp(7), dp(12), dp(7))
        control(t, tag, if (on) p.primary else p.surface2, 20f, false, click)
        return t
    }
    private fun icon(name: String, label: String, tag: String, badge: Int = 0, click: () -> Unit): View {
        val v = IconView(this, name, label, p.text); if (badge > 0) v.badge(badge)
        control(v, tag, Color.TRANSPARENT, 22f, false, click)
        v.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(2) }
        return v
    }
    private fun section(title: String) = tv(title.uppercase(), 11f, p.muted, mono = true).apply { letterSpacing = .1f; setPadding(dp(4), dp(14), 0, dp(6)) }
    /** The console's logo in the style chosen in Settings (color, mono outline); null for "text only". */
    private fun logo(c: Console, onColor: Boolean): ImageView? {
        val st = logoStyle(); if (st == "text") return null
        val pick = if (st == "mono" || (onColor && st != "color")) "logom_" else "logoc_"
        val id = resources.getIdentifier(pick + c.logo, "drawable", packageName).takeIf { it != 0 }
            ?: resources.getIdentifier("logo_${c.logo}", "drawable", packageName)
        if (id == 0) return null
        return ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageResource(id); contentDescription = c.title
            if (st == "mono" && !onColor) setColorFilter(p.text) }
    }
    private fun hasController(): Boolean = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id); d != null && (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD)
    }
    private fun hints(vararg items: Pair<String, String>): View {
        val row = horizontal(); row.setPadding(dp(16), dp(8), dp(16), dp(8))
        for ((k, v) in items) {
            val key = tv(k, 10f, p.text, bold = true); key.gravity = Gravity.CENTER
            key.background = round(p.surface2, 9f); row.addView(key, LinearLayout.LayoutParams(dp(18), dp(18)))
            row.addView(tv(v, 12f, p.muted).apply { setPadding(dp(5), 0, dp(14), 0) })
        }
        return row
    }
    /** Box art shape per console (width / height), so each cover shows whole: DVD cases are tall, N64/SNES boxes wide. */
    private fun coverRatio(c: Console): Float = when (c) {
        Console.PS2, Console.GC, Console.WII -> 0.71f; Console.PSP -> 0.58f
        Console.NES, Console.MD, Console.MAME -> 0.72f; Console.NDS -> 0.89f
        Console.GB, Console.GBC, Console.GBA -> 1.0f; Console.N64, Console.SNES -> 1.38f
    }
    /** A frame whose height follows its width at the console's cover shape. */
    private inner class CoverFrame(private val ratio: Float) : FrameLayout(this@PhoneActivity) {
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((width / ratio).toInt(), MeasureSpec.EXACTLY))
        }
    }
    /** Cover picture shown whole (never stretched); hides the title placeholder and the frame color once it arrives. */
    private inner class CoverImage(private val frame: View, private val placeholder: View?) : ImageView(this@PhoneActivity) {
        init { scaleType = ScaleType.FIT_CENTER }
        override fun setImageBitmap(bm: android.graphics.Bitmap?) { super.setImageBitmap(bm); arrived(bm != null) }
        override fun setImageDrawable(d: android.graphics.drawable.Drawable?) { super.setImageDrawable(d); arrived(d != null) }
        private fun arrived(on: Boolean) {
            if (!on) return
            placeholder?.visibility = View.INVISIBLE
            frame.background = round(Color.TRANSPARENT, 10f)
        }
    }
    private fun scroll(content: View) = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; addView(content) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ pages
    private fun render() {
        p = pal()
        root.removeAllViews(); root.setBackgroundColor(p.bg)
        window.statusBarColor = p.bg
        when (page) {
            "welcome" -> welcome()
            "library" -> libraryPage()
            "detail" -> detailPage()
            "consoles" -> consolesPage()
            "settings" -> settingsPage()
            "set_consoles" -> consolesSettingsPage()
            "set_home" -> homeSettingsPage()
            "set_storage" -> storagePage()
            "set_resolution" -> resolutionPage()
            "friends" -> friendsPage()
            "history" -> historyPage()
            "loading" -> loadingPage()
            "remote" -> remotePage()
            "media" -> mediaPage()
            else -> home()
        }
        if (page == "home" || page == "library" || page == "detail" || page == "consoles") miniPlayer()?.let { bar ->
            (root.getChildAt(0) as? ViewGroup)?.let { it.clipToPadding = false; it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, maxOf(it.paddingBottom, dp(84))) }
            root.addView(bar, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels - dp(20), dp(560)), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12) })
        }
        root.post {
            val target = if (lastFocus.isEmpty()) null else root.findViewWithTag<View>(lastFocus)
            if (target != null && target.isFocusable) target.requestFocus() else if (hasController()) firstFocusable(root)?.requestFocus()
        }
    }
    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun go(target: String) { if (target == "media" && page != "media") { mediaShots = null; mediaShown = 60 }; page = target; lastFocus = ""; render() }

    /** Header: title with search beside it on the left (clear of the camera); favorites, friends, settings on the right. */
    private fun header(title: String, back: Boolean, history: Boolean = false): View {
        val row = horizontal(); row.setPadding(dp(14), dp(26), dp(8), dp(8))   // lower, clear of the camera and the top edge
        if (back) row.addView(icon("back", T.t("Volver"), "back") { onBackPressed() })
        row.addView(tv(title, if (style == "tiles") 22f else 20f, p.text, bold = true).apply {
            setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setPadding(dp(6), 0, dp(4), 0)
            maxWidth = resources.displayMetrics.widthPixels - dp(if (back) 304 else 264) },
            LinearLayout.LayoutParams(-2, -2))
        row.addView(icon("search", T.t("Buscar juegos"), "h_search") { search() })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(castIcon())
        row.addView(icon("photos", T.t("Capturas"), "h_media") { go("media") })
        row.addView(icon("star", T.t("Favoritos"), "h_fav") { console = null; source = "fav"; go("library") })
        row.addView(icon("users", T.t("Amigos"), "h_friends", if (drive.friendsChat() != 0L) drive.friendsOnline() else 0) { go("friends") })
        if (history) row.addView(icon("history", T.t("Historial de juego"), "h_history") { go("history") })
        row.addView(icon("settings", T.t("Ajustes"), "h_settings") { go("settings") })
        return row
    }
    /** The Cast button: blue while a TV or PC is playing something. */
    private fun castIcon(): View {
        val on = castNow() != null
        val v = IconView(this, "cast", T.t("Jugar en TV o PC"), if (on) p.onPrimary else p.text)
        control(v, "h_cast", if (on) p.primary else Color.TRANSPARENT, 22f, false) { if (on) { remoteDevice = castNow()?.first; go("remote") } else castSheet(null) }
        v.setOnLongClickListener { castSheet(null); true }
        v.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(2) }
        return v
    }
    private fun updateBadge() {
        (root.findViewWithTag<View>("h_friends") as? IconView)?.badge(if (drive.friendsChat() != 0L) drive.friendsOnline() else 0)
    }
    private fun openEmbedded(target: String, action: String? = null) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("embed_page", target).apply { if (action != null) putExtra("embed_action", action) })
    }

    // ------------------------------------------------------------------ sign in (phone number or QR)
    private var phoneTab = true
    private fun welcome() {
        val col = vertical(); col.gravity = Gravity.CENTER_HORIZONTAL; col.setPadding(dp(24), dp(28), dp(24), dp(24))
        val logoBox = FrameLayout(this); logoBox.background = round(0xFF7A4DFF.toInt(), 18f)
        logoBox.addView(ImageView(this).apply { setImageResource(R.drawable.app_icon); scaleType = ImageView.ScaleType.FIT_CENTER },
            FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        col.addView(logoBox, LinearLayout.LayoutParams(dp(72), dp(72)))
        col.addView(tv("RetroStream", 24f, p.text, bold = true).apply { setPadding(0, dp(12), 0, 0) })
        val stage = drive.stage
        if (stage == TirsoDrive.Stage.READY) { connected(col); root.addView(scroll(col)); return }
        col.addView(tv(T.t("Inicia sesión con Telegram"), 15f, p.muted).apply { setPadding(0, dp(4), 0, dp(18)) })
        val box = vertical(); box.layoutParams = LinearLayout.LayoutParams(-1, -2)
        col.addView(box)
        val limit = LinearLayout.LayoutParams(minOf(dp(420), resources.displayMetrics.widthPixels - dp(48)), -2)
        box.layoutParams = limit
        when (stage) {
            TirsoDrive.Stage.CODE -> {
                box.addView(tv(T.t("Escribe el código que Telegram envió a tu app de Telegram."), 14f, p.muted))
                val code = field(T.t("Código"), InputType.TYPE_CLASS_NUMBER)
                box.addView(code, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                box.addView(button(T.t("Continuar"), "w_code", true) { drive.code(code.text.toString().trim()) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }
            TirsoDrive.Stage.PASSWORD -> {
                box.addView(tv(T.t("Tu cuenta tiene verificación en dos pasos. Escribe tu contraseña."), 14f, p.muted))
                val pass = field(T.t("Contraseña"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                box.addView(pass, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                box.addView(button(T.t("Continuar"), "w_pass", true) { drive.password(pass.text.toString()) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }
            else -> {
                if (stage == TirsoDrive.Stage.QR) phoneTab = false
                val seg = horizontal(); seg.background = round(p.surface2, 12f); seg.setPadding(dp(3), dp(3), dp(3), dp(3))
                seg.addView(segment(T.t("Número de teléfono"), phoneTab, "w_tab_phone") { phoneTab = true; render() }, LinearLayout.LayoutParams(0, -2, 1f))
                seg.addView(segment(T.t("Código QR"), !phoneTab, "w_tab_qr") { phoneTab = false; if (drive.stage != TirsoDrive.Stage.QR) drive.useQr(); render() }, LinearLayout.LayoutParams(0, -2, 1f))
                box.addView(seg)
                if (phoneTab) {
                    val ui = getSharedPreferences("ui", MODE_PRIVATE)
                    val row = horizontal(); row.setPadding(0, dp(12), 0, 0)
                    val cc = field("+1", InputType.TYPE_CLASS_PHONE).apply { setText(ui.getString("phone_cc", "+1")) }
                    val num = field("809 555 0123", InputType.TYPE_CLASS_PHONE)
                    row.addView(cc, LinearLayout.LayoutParams(dp(72), -2)); row.addView(num, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                    box.addView(row)
                    box.addView(button(T.t("Enviar código"), "w_send", true) {
                        val c = cc.text.toString().replace(Regex("[^0-9]"), "").ifEmpty { "1" }
                        val n = num.text.toString().replace(Regex("[^0-9]"), "")
                        if (n.isNotEmpty()) { ui.edit().putString("phone_cc", "+$c").apply(); drive.phoneNumber("+$c$n") }
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                    box.addView(tv(T.t("Telegram envía un código a tu app de Telegram y, si la tienes, te pide la contraseña de verificación en dos pasos."), 12f, p.muted).apply {
                        gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), 0) })
                } else {
                    if (drive.qrLink.isNotEmpty()) runCatching {
                        val qr = ImageView(this); qr.setImageBitmap(QrCode.encode(drive.qrLink, 512)); qr.setBackgroundColor(Color.WHITE); qr.setPadding(dp(10), dp(10), dp(10), dp(10))
                        box.addView(qr, LinearLayout.LayoutParams(dp(220), dp(220)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16) })
                    } else box.addView(ProgressBar(this), LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(20) })
                    box.addView(tv(T.t("En otro teléfono o tablet: Telegram > Ajustes > Dispositivos > Vincular dispositivo, y escanea este código."), 13f, p.muted).apply {
                        gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), 0) })
                }
            }
        }
        if (drive.error.isNotEmpty()) box.addView(tv(drive.error, 13f, 0xFFFF8A9A.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
        else if (drive.status.isNotEmpty()) box.addView(tv(drive.status, 12f, p.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        col.addView(button(T.t("Omitir y usar solo juegos del teléfono"), "w_skip") { drive.skip(); go("home") },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) })
        root.addView(scroll(col))
    }
    private fun segment(label: String, on: Boolean, tag: String, click: () -> Unit): TextView {
        val t = tv(label, 14f, if (on) p.onPrimary else p.text, bold = on); t.gravity = Gravity.CENTER; t.setPadding(0, dp(9), 0, dp(9))
        control(t, tag, if (on) p.primary else Color.TRANSPARENT, 10f, false, click); return t
    }
    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint; inputType = type; setSingleLine(); setTextColor(p.text); setHintTextColor(p.muted)
        background = round(p.surface2, 10f); setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    /** Signed in: the console groups found, then the library. */
    private fun connected(col: LinearLayout) {
        col.addView(tv(T.t("Sesión conectada"), 15f, p.muted).apply { setPadding(0, dp(4), 0, dp(14)) })
        col.addView(tv(if (drive.discovering || drive.syncing) T.t("Buscando tus grupos TD - ROMs…") else drive.status, 13f, p.muted).apply { gravity = Gravity.CENTER })
        for (c in Console.all()) {
            val s = drive.sources[c] ?: continue
            val row = horizontal(); row.setPadding(dp(14), dp(10), dp(14), dp(10))
            row.addView(tv(c.title, 15f, p.text, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            val n = if (s.chatId != 0L) drive.fileCounts[s.chatId] else null
            row.addView(tv(if (s.chatId == 0L) s.issue else s.title + (if (n != null) " · $n" else ""), 12f, if (s.chatId == 0L) p.muted else p.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1.2f))
            control(row, "src_${c.name}", p.surface) { chooseSource(c) }
            col.addView(row, LinearLayout.LayoutParams(minOf(dp(520), resources.displayMetrics.widthPixels - dp(32)), -2).apply { topMargin = dp(6) })
        }
        if (drive.error.isNotEmpty()) col.addView(tv(drive.error, 13f, 0xFFFF8A95.toInt()).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), 0) })
        val w = minOf(dp(520), resources.displayMetrics.widthPixels - dp(32))
        if (drive.discovering || drive.syncing) {
            col.addView(button(T.t("Cancelar actualización"), "w_cancel") { drive.cancelSync() }, LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(18) })
        } else {
            col.addView(button(T.t("Escanear todos los canales"), "w_scan") { drive.scanAll() }, LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(18) })
            col.addView(button(T.t("Actualizar catálogo"), "w_sync") { drive.sync() }, LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(8) })
            col.addView(button(T.t("Borrar todas las asignaciones"), "w_clear") {
                AlertDialog.Builder(this).setTitle(T.t("Borrar todas las asignaciones"))
                    .setMessage(T.t("Se quitarán los canales de todas las consolas y la lista de juegos de Telegram. Tus partidas guardadas y los juegos del teléfono no se tocan."))
                    .setPositiveButton(T.t("Borrar")) { _, _ -> drive.clearAssignments() }.setNegativeButton(T.t("Cancelar"), null).show()
            }, LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(8) })
        }
        col.addView(button(T.t("Ir a mi biblioteca"), "w_done", true) { drive.finishOnboarding(); library = Library(this); go("home") },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
    }
    private fun chooseSource(c: Console) {
        if (drive.syncing || drive.discovering) return
        val choices = drive.choices(c)
        val labels = arrayOf(T.t("Sin grupo asignado")) + choices.map { it.title + "  ·  " + drive.describe(it) }
        AlertDialog.Builder(this).setTitle(c.title).setItems(labels) { _, w -> if (w == 0) drive.unbind(c) else drive.bind(c, choices[w - 1].id) }.show()
    }

    // ------------------------------------------------------------------ home (option A: continue first)
    private fun visibleConsoles() = Console.all().filter { library.visible(it) }
    private fun heroConsole(): Console {
        val list = visibleConsoles()
        return list.firstOrNull { it.name == library.homeConsole() } ?: list.firstOrNull { library.count(it) > 0 } ?: list.firstOrNull() ?: Console.GBA
    }
    private fun recent(): List<Pair<Game, Long>> {
        val seen = HashSet<String>(); val out = mutableListOf<Pair<Game, Long>>()
        for (r in library.recentPlays()) { val g = r[0] as Game; if (seen.add(g.id)) out += g to (r[1] as Long); if (out.size >= 12) break }
        return out
    }

    private fun home() {
        val last = recent().firstOrNull()?.first
        if (landscape) {
            val row = horizontal(); row.gravity = Gravity.TOP; row.setPadding(dp(12), dp(18), dp(12), dp(8))
            if (HomeLayout.shown(this, "continue")) row.addView(continueCard(last, true), LinearLayout.LayoutParams(0, -1, 0.9f))
            val right = vertical(); right.setPadding(dp(12), 0, 0, 0)
            val top = horizontal()
            top.addView(tv(T.t("Inicio"), 18f, p.text, bold = true).apply { setPadding(dp(4), 0, dp(4), 0) })
            top.addView(icon("search", T.t("Buscar juegos"), "h_search") { search() })
            top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(tv("🎲", 18f, p.text).also { it.setPadding(dp(8), dp(4), dp(8), dp(4)); it.contentDescription = T.t("Sorpréndeme"); control(it, "surprise", Color.TRANSPARENT, 8f, false) { GameMenu.surprise(this, menuHost) } })
            top.addView(castIcon())
            top.addView(icon("photos", T.t("Capturas"), "h_media") { go("media") })
            top.addView(icon("star", T.t("Favoritos"), "h_fav") { console = null; source = "fav"; go("library") })
            top.addView(icon("users", T.t("Amigos"), "h_friends", if (drive.friendsChat() != 0L) drive.friendsOnline() else 0) { go("friends") })
            top.addView(icon("settings", T.t("Ajustes"), "h_settings") { go("settings") })
            right.addView(top)
            if (HomeLayout.shown(this, "recent")) recentBlock(right, dp(78), dp(104))
            if (HomeLayout.shown(this, "new")) newBlock(right, dp(78), dp(104))
            if (HomeLayout.shown(this, "consoles")) { right.addView(section(T.t("Consolas"))); right.addView(consoleRow(dp(104), dp(70))) }
            if (HomeLayout.shown(this, "friends")) friendsBlock(right)
            row.addView(scroll(right), LinearLayout.LayoutParams(0, -1, 1.25f))
            val outer = vertical(); outer.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
            if (hasController()) outer.addView(hints("A" to T.t("Abrir"), "B" to T.t("Volver"), "Y" to T.t("Buscar"), "X" to T.t("Favoritos")))
            root.addView(outer)
            return
        }
        val col = vertical(); col.setPadding(0, 0, 0, dp(18))
        col.addView(header(T.t("Inicio"), false))
        if (HomeLayout.shown(this, "continue")) col.addView(continueCard(last, false), LinearLayout.LayoutParams(-1, dp(220)).apply { setMargins(dp(14), dp(4), dp(14), 0) })
        if (HomeLayout.shown(this, "recent")) recentBlock(col, dp(104), dp(140))
        if (HomeLayout.shown(this, "new")) newBlock(col, dp(104), dp(140))
        if (HomeLayout.shown(this, "consoles")) {
            if (HomeLayout.consoleGrid(this)) {
                val head = horizontal(); head.setPadding(dp(18), dp(18), dp(14), dp(6))
                head.addView(tv(T.t("Consolas").uppercase(), 11f, p.muted, mono = true).apply { letterSpacing = .1f }, LinearLayout.LayoutParams(0, -2, 1f))
                head.addView(tv("🎲 " + T.t("Sorpréndeme"), 12f, p.primary).also { control(it, "surprise", Color.TRANSPARENT, 8f, false) { GameMenu.surprise(this, menuHost) } },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
                if (visibleConsoles().size > 9) head.addView(tv(T.t("Ver todo"), 12f, p.primary).also { control(it, "consoles_all", Color.TRANSPARENT, 8f, false) { go("consoles") } })
                col.addView(head)
                col.addView(consoleGrid(visibleConsoles().take(9)))
            } else {
                col.addView(section(T.t("Consolas")).apply { setPadding(dp(18), dp(18), 0, dp(6)) })
                col.addView(consoleRow(dp(120), dp(84)))
            }
        }
        if (HomeLayout.shown(this, "friends")) friendsBlock(col)
        if (HomeLayout.shown(this, "suggested")) suggestedBlock(col)
        root.addView(scroll(col))
    }

    /** The big card: the last game played, its console's color, cover, time played and Resume. */
    private fun continueCard(g: Game?, tall: Boolean): View {
        val c = g?.console ?: heroConsole()
        val filled = style == "console" || style == "tiles"
        val card = FrameLayout(this); card.clipToOutline = true
        val radius = if (style == "tiles") 26f else 18f
        card.background = if (filled) round(c.color, radius) else round(p.surface, radius, dp(if (style == "gba") 2 else 1), if (style == "gba") p.outline else c.color)
        val ink = if (filled) Color.WHITE else p.text
        val soft = if (filled) 0xD9FFFFFF.toInt() else p.muted
        if (g != null) {
            val cover = FrameLayout(this); cover.background = round(0xFFF2EEE4.toInt(), 10f); cover.clipToOutline = true
            val iv = CoverImage(cover, null); cover.addView(iv, FrameLayout.LayoutParams(-1, -1))
            artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles")
            cover.rotation = 6f
            val ch = dp(160); val cw = minOf((ch * coverRatio(g.console)).toInt(), dp(190))
            card.addView(cover, FrameLayout.LayoutParams(cw, (cw / coverRatio(g.console)).toInt(), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(-8) })
        }
        val texts = vertical(); texts.setPadding(dp(16), dp(16), dp(16), 0)
        texts.addView(tv(if (g != null) (T.t("CONTINUAR") + " · " + c.title.uppercase()) else T.t("EMPIEZA A JUGAR"), 11f, soft, mono = true).apply { letterSpacing = .08f })
        texts.addView(tv(g?.title ?: T.t("Elige una consola abajo"), 22f, ink, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0) },
            LinearLayout.LayoutParams(-1, -2).apply { marginEnd = dp(118) })
        if (g != null) {
            val played = PlayTime.label(this, g.id)
            if (played.isNotEmpty()) texts.addView(tv(played + T.t(" jugadas"), 12f, ink).apply {
                setPadding(dp(9), dp(4), dp(9), dp(4)); background = round(if (filled) 0x2EFFFFFF else p.surface2, 10f) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        }
        card.addView(texts, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        if (g != null) {
            val resume = tv("▶  " + T.t("Reanudar"), 15f, if (filled) 0xFF14161E.toInt() else p.onPrimary, bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(9), dp(16), dp(9)) }
            control(resume, "resume", if (filled) Color.WHITE else p.primary, 12f, false) { play(g) }
            resume.setOnLongClickListener { selected = g; gameMore(g, true); true }
            card.setOnLongClickListener { selected = g; gameMore(g, true); true }
            card.addView(resume, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(16), 0, 0, dp(16)) })
        }
        return card
    }

    /** Consoles as a 3-column grid of tiles. */
    private fun consoleGrid(list: List<Console>): View {
        val grid = vertical(); grid.setPadding(dp(12), 0, dp(12), 0)
        var row: LinearLayout? = null
        list.forEachIndexed { i, c ->
            if (i % 3 == 0) { row = horizontal(); grid.addView(row, LinearLayout.LayoutParams(-1, -2)) }
            row!!.addView(consoleTile(c), LinearLayout.LayoutParams(0, dp(84), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        val rest = list.size % 3
        if (rest != 0) repeat(3 - rest) { row!!.addView(View(this), LinearLayout.LayoutParams(0, dp(10), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        return grid
    }
    private fun consolesPage() {
        val outer = vertical()
        outer.addView(header(T.t("Consolas"), true))
        val list = visibleConsoles()
        outer.addView(scroll(vertical().apply { setPadding(0, 0, 0, dp(16)); addView(if (landscape) consoleGridCols(list, 5) else consoleGrid(list)) }), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(outer)
    }
    private fun consoleGridCols(list: List<Console>, cols: Int): View {
        val grid = vertical(); grid.setPadding(dp(12), 0, dp(12), 0)
        var row: LinearLayout? = null
        list.forEachIndexed { i, c ->
            if (i % cols == 0) { row = horizontal(); grid.addView(row, LinearLayout.LayoutParams(-1, -2)) }
            row!!.addView(consoleTile(c), LinearLayout.LayoutParams(0, dp(80), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        val rest = list.size % cols
        if (rest != 0) repeat(cols - rest) { row!!.addView(View(this), LinearLayout.LayoutParams(0, dp(10), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        return grid
    }
    private fun consoleTile(c: Console): View {
        val tile = FrameLayout(this); tile.setPadding(dp(8), dp(6), dp(8), dp(6))
        val filled = style == "console" || style == "tiles"
        val img = logo(c, filled)
        if (img != null) tile.addView(img, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(2), dp(2), dp(2), dp(16)) })
        else tile.addView(tv(c.title, 13f, if (filled) Color.WHITE else p.text, bold = true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(12) })
        tile.addView(tv(c.name, 11f, if (filled) Color.WHITE else p.text, bold = true), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        val n = library.count(c)
        if (n > 0) tile.addView(tv(n.toString(), 10f, if (filled) 0xCCFFFFFF.toInt() else p.muted), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        if (style == "midnight") tile.addView(View(this).apply { setBackgroundColor(c.color) }, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))
        control(tile, "tile_${c.name}", if (filled) c.color else p.surface, if (style == "tiles") 18f else 12f, style == "gba") { openConsole(c) }
        return tile
    }

    /** Consoles as one swipeable row of tiles. */
    private fun consoleRow(w: Int, h: Int): View {
        val row = horizontal(); row.setPadding(dp(14), 0, dp(14), 0)
        for (c in visibleConsoles()) {
            val tile = FrameLayout(this); tile.setPadding(dp(8), dp(6), dp(8), dp(6))
            val filled = style == "console" || style == "tiles"
            val bg = if (filled) c.color else p.surface
            val img = logo(c, filled)
            if (img != null) tile.addView(img, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(2), dp(2), dp(2), dp(16)) })
            else tile.addView(tv(c.title, 13f, if (filled) Color.WHITE else p.text, bold = true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(12) })
            tile.addView(tv(c.name, 11f, if (filled) Color.WHITE else p.text, bold = true), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            val n = library.count(c)
            if (n > 0) tile.addView(tv(n.toString(), 10f, if (filled) 0xCCFFFFFF.toInt() else p.muted), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
            if (style == "midnight") tile.addView(View(this).apply { setBackgroundColor(c.color) }, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))
            control(tile, "tile_${c.name}", bg, if (style == "tiles") 18f else 12f, style == "gba") { openConsole(c) }
            row.addView(tile, LinearLayout.LayoutParams(w, h).apply { marginEnd = dp(8) })
        }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(row) }
    }

    private fun recentBlock(parent: LinearLayout, w: Int, h: Int) {
        val list = recent(); if (list.size < 2) return
        val head = horizontal(); head.setPadding(dp(18), dp(16), dp(14), dp(6))
        head.addView(tv(T.t("Jugados recientemente").uppercase(), 11f, p.muted, mono = true).apply { letterSpacing = .1f }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(tv(T.t("Ver todo"), 12f, p.primary).also { control(it, "recent_all", Color.TRANSPARENT, 8f, false) { go("history") } })
        parent.addView(head)
        val row = horizontal(); row.setPadding(dp(14), 0, dp(14), 0); row.gravity = Gravity.TOP
        for ((g, _) in list.drop(1)) {
            val cell = vertical()
            val art = FrameLayout(this); art.background = round(0xFFF2EEE4.toInt(), 10f); art.clipToOutline = true
            val ph = tv(g.title, 11f, 0xFF2B2B2B.toInt(), bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }
            art.addView(ph, FrameLayout.LayoutParams(-1, -1))
            val iv = CoverImage(art, ph); art.addView(iv, FrameLayout.LayoutParams(-1, -1))
            artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles")
            val cw = (h * coverRatio(g.console)).toInt()   // same height for all, width follows the console's box shape
            cell.addView(art, LinearLayout.LayoutParams(cw, h))
            cell.addView(tv(g.title, 11f, p.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), dp(5), dp(2), 0) }, LinearLayout.LayoutParams(maxOf(cw, w), -2))
            val pt = PlayTime.label(this, g.id)
            if (pt.isNotEmpty()) cell.addView(tv(pt, 10f, p.muted).apply { setPadding(dp(2), dp(1), dp(2), 0) })
            control(cell, "recent_${g.id}", Color.TRANSPARENT, 12f, false) { play(g) }
            cell.setOnLongClickListener { selected = g; gameMore(g, true); true }
            row.addView(cell, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        }
        parent.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    /** Games that showed up in your channels in the last two weeks. */
    private fun newBlock(parent: LinearLayout, w: Int, h: Int) {
        val list = library.newGames(14).take(15); if (list.isEmpty()) return
        parent.addView(section(T.t("Nuevos en tus canales") + " · ${list.size}").apply { setPadding(dp(18), dp(16), 0, dp(6)) })
        val row = horizontal(); row.setPadding(dp(14), 0, dp(14), 0); row.gravity = Gravity.TOP
        for (g in list) {
            val cell = vertical()
            val art = FrameLayout(this); art.background = round(0xFFF2EEE4.toInt(), 10f); art.clipToOutline = true
            val ph = tv(g.title, 11f, 0xFF2B2B2B.toInt(), bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }
            art.addView(ph, FrameLayout.LayoutParams(-1, -1))
            val iv = CoverImage(art, ph); art.addView(iv, FrameLayout.LayoutParams(-1, -1))
            artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles")
            art.addView(tv(T.t("NUEVO"), 9f, Color.WHITE, bold = true).apply { setPadding(dp(5), dp(2), dp(5), dp(2)); background = round(0xFFE5484D.toInt(), 6f) },
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(4), dp(4), 0, 0) })
            val cw = (h * coverRatio(g.console)).toInt()
            cell.addView(art, LinearLayout.LayoutParams(cw, h))
            cell.addView(tv(g.title, 11f, p.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), dp(5), dp(2), 0) }, LinearLayout.LayoutParams(maxOf(cw, w), -2))
            cell.addView(tv(g.console.title, 10f, p.muted).apply { setPadding(dp(2), dp(1), dp(2), 0) })
            control(cell, "new_${g.id}", Color.TRANSPARENT, 12f, false) { menuHost.open(g) }
            cell.setOnLongClickListener { selected = g; gameMore(g); true }
            row.addView(cell, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        }
        parent.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    /** Upright only: a few games to try, picked at random from the library (new picks each day, unplayed first). */
    private fun suggestedBlock(parent: LinearLayout) {
        val played = recent().map { it.first.id }.toSet()
        val hidden = library.hidden()
        val pool = library.games.filter { library.visible(it.console) && it.id !in played && it.id !in hidden }
        if (pool.isEmpty()) return
        val day = System.currentTimeMillis() / 86_400_000L
        val picks = pool.shuffled(java.util.Random(day)).take(9)
        parent.addView(section(T.t("Juegos sugeridos")).apply { setPadding(dp(18), dp(18), 0, dp(6)) })
        val grid = vertical(); grid.setPadding(dp(12), 0, dp(12), 0)
        var row: LinearLayout? = null
        picks.forEachIndexed { i, g ->
            if (i % 3 == 0) { row = horizontal(); row!!.gravity = Gravity.TOP; grid.addView(row, LinearLayout.LayoutParams(-1, -2)) }
            val cell = vertical()
            val art = CoverFrame(coverRatio(g.console)); art.background = round(0xFFF2EEE4.toInt(), 10f); art.clipToOutline = true
            val ph = tv(g.title, 11f, 0xFF2B2B2B.toInt(), bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }
            art.addView(ph, FrameLayout.LayoutParams(-1, -1))
            val iv = CoverImage(art, ph); art.addView(iv, FrameLayout.LayoutParams(-1, -1))
            artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles")
            val tag = tv(g.console.name, 9f, Color.WHITE, bold = true).apply { setPadding(dp(5), dp(2), dp(5), dp(2)); background = round(g.console.color, 6f) }
            art.addView(tag, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(5), dp(5), 0, 0) })
            cell.addView(art, LinearLayout.LayoutParams(-1, -2))
            cell.addView(tv(g.title, 11f, p.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), dp(5), dp(2), 0) })
            control(cell, "sugg_${g.id}", Color.TRANSPARENT, 12f, false) { selected = g; console = g.console; go("detail") }
            cell.setOnLongClickListener { selected = g; gameMore(g); true }
            row!!.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(8)) })
        }
        val rest = picks.size % 3
        if (rest != 0) repeat(3 - rest) { row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        parent.addView(grid)
    }

    /** Friends who are online or playing (only when the friends group is set up). */
    private fun friendsBlock(parent: LinearLayout) {
        if (drive.friendsChat() == 0L) return
        parent.addView(section(T.t("Amigos")).apply { setPadding(dp(18), dp(16), 0, dp(6)) })
        val on = drive.friends.values.filter { it.userId != drive.accountId && it.online() }.sortedBy { if (it.playing()) 0 else 1 }
        if (on.isEmpty()) { parent.addView(tv(T.t("Nadie conectado ahora"), 13f, p.muted).apply { setPadding(dp(18), 0, 0, 0) }); return }
        for (f in on.take(4)) {
            val row = horizontal(); row.setPadding(dp(18), dp(4), dp(18), dp(4))
            val name = f.name.ifEmpty { "…" }
            val av = tv(name.take(1).uppercase(), 13f, 0xFF06210F.toInt(), bold = true).apply { gravity = Gravity.CENTER; background = round(if (f.playing()) 0xFF22C55E.toInt() else 0xFF3B82F6.toInt(), 14f) }
            row.addView(av, LinearLayout.LayoutParams(dp(28), dp(28)))
            row.addView(tv(name + " · " + if (f.playing()) f.game else T.t("En el menú"), 14f, p.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(10), 0, 0, 0) })
            control(row, "friend_${f.userId}", Color.TRANSPARENT, 10f, false) { go("friends") }
            parent.addView(row)
        }
    }
    private fun openConsole(c: Console) { console = c; source = "all"; library.homeConsole(c); go("library") }
    private fun shiftConsole(step: Int) {
        val list = visibleConsoles(); if (list.isEmpty()) return
        val i = (list.indexOf(heroConsole()) + step + list.size) % list.size
        library.homeConsole(list[i]); lastFocus = "tile_${list[i].name}"; render()
    }

    // ------------------------------------------------------------------ library
    /** Short console names for the narrow header (upright). */
    private fun shortName(c: Console) = if (landscape) c.title else when (c) {
        Console.GBA -> "GBA"; Console.GBC -> "GBC"; Console.MD -> "Mega Drive"; Console.GC -> "GameCube"; Console.NDS -> "DS"
        Console.PSP -> "PSP"; Console.PS2 -> "PS2"; Console.N64 -> "N64"; Console.SNES -> "SNES"; Console.NES -> "NES"; else -> c.title
    }
    private fun gamesShown(): List<Game> {
        val fav = source == "fav"
        val all = if (source == "col") library.collection(collectionName).let { ids -> library.filtered(null, false, query).filter { it.id in ids } } else library.filtered(console, fav, query)
        val shown = when (source) {
            "telegram" -> all.filter { it.telegramAccount != 0L }
            "phone" -> all.filter { it.id.startsWith("local:") }
            else -> all
        }
        if (!downloadedOnly) return shown
        val kept = PhoneGames.kept(this).map { it[0] as String }.toSet()
        return shown.filter { it.id.startsWith("local:") || it.id in kept || drive.file(it)?.local?.isDownloadingCompleted == true }
    }
    private fun libraryPage() {
        val c = console
        val title = when { source == "fav" -> T.t("Favoritos"); source == "col" -> collectionName; c == null -> T.t("Biblioteca"); else -> shortName(c) } + if (query.isNotEmpty()) " · \"$query\"" else ""
        val outer = vertical()
        outer.addView(header(title, true))
        val base = if (source == "fav") library.filtered(c, true, query) else library.filtered(c, false, query)
        if ((source == "fav" || source == "col") && library.collections().isNotEmpty()) {
            // Favorites and your collections, one tap apart.
            val chips = horizontal(); chips.setPadding(dp(16), 0, dp(16), dp(8))
            chips.addView(chip("★ " + T.t("Favoritos"), source == "fav", "chip_fav") { source = "fav"; render() })
            for (n in library.collections()) chips.addView(chip(n, source == "col" && collectionName == n, "chip_col_${n.hashCode()}") { source = "col"; collectionName = n; render() },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            outer.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        }
        if (source != "fav" && source != "col") {
            val chips = horizontal(); chips.setPadding(dp(16), 0, dp(16), dp(8))
            val tg = base.count { it.telegramAccount != 0L }; val ph = base.count { it.id.startsWith("local:") }
            chips.addView(chip(T.t("Todos") + " ${base.size}", source == "all", "chip_all") { source = "all"; render() })
            chips.addView(chip("Telegram $tg", source == "telegram", "chip_tg") { source = "telegram"; render() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            if (ph > 0 || source == "phone") chips.addView(chip(T.t("Teléfono") + " $ph", source == "phone", "chip_ph") { source = "phone"; render() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            chips.addView(chip("✓ " + T.t("Descargados"), downloadedOnly, "chip_dl") { downloadedOnly = !downloadedOnly; render() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            chips.addView(chip("⇅ " + sortLabel(), false, "chip_sort") { chooseSort() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            outer.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        }
        val games = gamesShown()
        val grid = vertical(); grid.setPadding(dp(12), 0, dp(12), dp(16))
        if (games.isEmpty()) grid.addView(tv(if (drive.syncing) T.t("Actualizando colección…") else T.t("No hay juegos aquí todavía."), 15f, p.muted).apply { setPadding(dp(8), dp(24), 0, 0) })
        val cols = if (landscape) 4 else 2
        var row: LinearLayout? = null
        val kept = PhoneGames.kept(this).map { it[0] as String }.toSet()
        games.forEachIndexed { i, g ->
            if (i % cols == 0) { row = horizontal(); row!!.gravity = Gravity.TOP; grid.addView(row, LinearLayout.LayoutParams(-1, -2)) }
            row!!.addView(gameTile(g, g.id in kept), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        val rest = games.size % cols
        if (rest != 0) repeat(cols - rest) { row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        val list = scroll(grid)
        if (landscape) {
            val body = horizontal(); body.gravity = Gravity.TOP
            body.addView(list, LinearLayout.LayoutParams(0, -1, 1f))
            val focus = selected?.takeIf { it in games } ?: games.firstOrNull()
            if (focus != null) body.addView(detailsPanel(focus), LinearLayout.LayoutParams(dp(250), -1).apply { setMargins(dp(4), 0, dp(14), dp(10)) })
            outer.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            if (hasController()) outer.addView(hints("A" to T.t("Jugar"), "B" to T.t("Volver"), "X" to T.t("Favorito"), "Y" to T.t("Buscar")))
        } else outer.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(outer)
    }
    private val sorts = listOf("name" to "Nombre A-Z", "name_desc" to "Nombre Z-A", "added" to "Añadidos recientemente", "played" to "Más jugados", "recent" to "Jugados recientemente")
    private fun sortKey() = if (library.sort() == "name" && library.descending()) "name_desc" else library.sort()
    private fun sortLabel() = T.t(sorts.firstOrNull { it.first == sortKey() }?.second ?: "Nombre A-Z")
    private fun chooseSort() {
        AlertDialog.Builder(this).setTitle(T.t("Ordenar por")).setSingleChoiceItems(sorts.map { T.t(it.second) }.toTypedArray(), sorts.indexOfFirst { it.first == sortKey() }) { d, w ->
            val k = sorts[w].first
            if (k.startsWith("name")) { library.sort("name"); library.descending(k == "name_desc") } else library.sort(k)
            d.dismiss(); render()
        }.show()
    }
    private fun sourceIcon(g: Game, kept: Boolean): Pair<String, Int> = when {
        g.id.startsWith("local:") -> "▣" to 0xFFF0B455.toInt()
        kept -> "✓" to 0xFF22C55E.toInt()
        else -> "✈" to 0xFF5AB0F0.toInt()
    }
    private fun gameTile(g: Game, kept: Boolean): View {
        val cell = vertical(); cell.setPadding(0, 0, 0, dp(2))
        val art = CoverFrame(coverRatio(g.console)); art.background = round(0xFFF2EEE4.toInt(), 10f)
        art.clipToOutline = true
        val ph = tv(g.title, 12f, 0xFF2B2B2B.toInt(), bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(6)); maxLines = 3 }
        art.addView(ph, FrameLayout.LayoutParams(-1, -1))
        val iv = CoverImage(art, ph)
        art.addView(iv, FrameLayout.LayoutParams(-1, -1))
        artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles")
        if (library.favorite(g)) art.addView(tv("★", 14f, 0xFFFFB400.toInt()).apply { setPadding(dp(6), dp(4), dp(6), 0) }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        if (library.completed(g)) art.addView(tv("🏆", 13f, Color.WHITE).apply { setPadding(dp(5), dp(2), dp(5), dp(2)); background = round(0xCC14161E.toInt(), 8f); contentDescription = T.t("Completado") },
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(5), dp(5)) })
        if (kept || g.id.startsWith("local:")) art.addView(tv("✓", 11f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER; background = round(0xFF22C55E.toInt(), 20f); contentDescription = T.t("En el teléfono") },
            FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.START).apply { setMargins(dp(5), dp(5), 0, 0) })
        cell.addView(art, LinearLayout.LayoutParams(-1, -2))
        val info = horizontal(); info.setPadding(dp(4), dp(6), dp(4), dp(2))
        info.addView(tv(g.title, 12f, p.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        val (sym, col) = sourceIcon(g, kept)
        info.addView(tv(sym, 12f, col))
        cell.addView(info)
        control(cell, "game_${g.id}", Color.TRANSPARENT, 12f, false) {
            selected = g
            if (landscape) play(g) else { lastFocus = ""; go("detail") }
        }
        cell.setOnFocusChangeListener { v, has ->
            v.background = round(Color.TRANSPARENT, 12f, if (has) dp(3) else 0, p.ring)
            if (has) { lastFocus = "game_${g.id}"; if (landscape && selected != g) { selected = g; refreshPanel(g) } }
        }
        cell.setOnLongClickListener { selected = g; gameMore(g); true }
        return cell
    }
    private var panel: LinearLayout? = null
    private fun detailsPanel(g: Game): View {
        val box = vertical(); box.background = round(p.surface, 14f); box.setPadding(dp(12), dp(12), dp(12), dp(12))
        panel = box; fillPanel(box, g); return box
    }
    private fun refreshPanel(g: Game) { panel?.let { it.removeAllViews(); fillPanel(it, g) } }
    private fun fillPanel(box: LinearLayout, g: Game) {
        val art = FrameLayout(this); art.background = round(0xFFF2EEE4.toInt(), 10f); art.clipToOutline = true
        val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.FIT_CENTER; art.addView(iv, FrameLayout.LayoutParams(-1, -1))
        artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles", "Named_Logos")
        box.addView(art, LinearLayout.LayoutParams(-1, 0, 1f))
        box.addView(tv(g.title, 15f, p.text, bold = true).apply { maxLines = 2; setPadding(0, dp(8), 0, 0) })
        box.addView(tv(meta(g), 12f, p.muted).apply { setPadding(0, dp(3), 0, dp(8)) })
        val row = horizontal()
        row.addView(button("▶  " + T.t("Jugar"), "panel_play", true) { play(g) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (g.telegramAccount != 0L) row.addView(IconView(this, "cast", T.t("Jugar en TV o PC"), p.text).also { control(it, "panel_cast", p.surface2, 12f, false) { castSheet(g) } }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
        row.addView(button(if (library.favorite(g)) "★" else "☆", "panel_fav") { library.toggleFavorite(g); lastFocus = "panel_fav"; render() }, LinearLayout.LayoutParams(dp(44), -2).apply { marginStart = dp(6) })
        row.addView(button("⋯", "panel_more") { gameMore(g) }, LinearLayout.LayoutParams(dp(44), -2).apply { marginStart = dp(6) })
        box.addView(row)
    }
    private fun meta(g: Game): String {
        val src = when { g.id.startsWith("local:") -> T.t("en tu teléfono"); g.id in PhoneGames.kept(this).map { it[0] as String } -> T.t("guardado en el teléfono"); else -> "Telegram" }
        val size = if (g.fileSize > 0) " · " + android.text.format.Formatter.formatShortFileSize(this, g.fileSize) else ""
        return g.console.title + " · " + src + size
    }

    // ------------------------------------------------------------------ game detail (upright)
    private fun detailPage() {
        val g = selected ?: return go("library")
        val col = vertical(); col.setPadding(dp(18), 0, dp(18), dp(18))
        val top = horizontal(); top.setPadding(0, dp(10), 0, dp(6))
        top.addView(icon("back", T.t("Volver"), "back") { onBackPressed() })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(icon("star", T.t("Favorito"), "d_fav") { library.toggleFavorite(g); lastFocus = "d_fav"; render() })
        col.addView(top)
        val art = FrameLayout(this); art.background = round(0xFFF2EEE4.toInt(), 14f); art.clipToOutline = true
        art.addView(tv(g.title, 18f, 0xFF2B2B2B.toInt(), bold = true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.FIT_CENTER; art.addView(iv, FrameLayout.LayoutParams(-1, -1))
        artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles")
        col.addView(art, LinearLayout.LayoutParams(-1, dp(300)))
        col.addView(tv(g.title + if (library.favorite(g)) "  ★" else "", 22f, p.text, bold = true).apply { setPadding(0, dp(14), 0, 0) })
        col.addView(tv(meta(g), 13f, p.muted).apply { setPadding(0, dp(4), 0, dp(16)) })
        val plays = horizontal()
        plays.addView(button("▶  " + T.t("Jugar aquí"), "d_play", true) { play(g) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        if (g.telegramAccount != 0L) plays.addView(button(T.t("Jugar en TV o PC"), "d_cast") { castSheet(g) }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        col.addView(plays)
        val row = horizontal(); row.setPadding(0, dp(8), 0, 0)
        row.addView(button(T.t("Capturas"), "d_shots") { showShots(g) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button(T.t("Más"), "d_more") { gameMore(g) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        col.addView(row)
        if (g.description.isNotBlank()) col.addView(tv(g.description, 13f, p.muted).apply { setPadding(0, dp(16), 0, 0) })
        root.addView(scroll(col))
        lastFocus = lastFocus.ifEmpty { "d_play" }
    }

    // ------------------------------------------------------------------ playing: download, BIN/CUE tracks, newest save, launch
    private var pending: Game? = null
    private var disc: List<Pair<String, Int>>? = null   // (track name, file id)
    private var discJson: String? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var lastBytes = 0L; private var lastAt = 0L; private var speed = 0.0

    private fun play(g: Game) {
        if (TemporaryRoms.busy()) { root.postDelayed({ play(g) }, 150); return }
        selected = g; disc = null; discJson = null
        drive.inspectFile(g)
        val f = drive.file(g)
        if (f != null && f.local.isDownloadingCompleted) { launch(g, f.local.path); return }
        if (g.id.startsWith("local:")) {
            val path = PhoneGames.path(this, g.id)
            if (path != null && File(path).isFile) launch(g, path) else toast(T.t("Ese archivo ya no está en tu teléfono. Busca juegos de nuevo en Ajustes."))
            return
        }
        if (!drive.owns(g)) { toast(T.t("Conecta TirsoDrive y su grupo para jugar este juego.")); go("welcome"); return }
        if (PhoneGames.wifiOnly(this) && !PhoneGames.onWifi(this)) { toast(T.t("Solo Wi-Fi está activado: conéctate a una red Wi-Fi para descargar este juego.")); return }
        pending = g; lastBytes = 0; lastAt = 0; speed = 0.0
        go("loading"); drive.download(g); updateLoading()
    }
    private fun loadingPage() {
        val g = selected ?: return go("home")
        val col = vertical(); col.gravity = Gravity.CENTER_HORIZONTAL; col.setPadding(dp(24), dp(24), dp(24), dp(24))
        val art = FrameLayout(this); art.background = round(0xFFF2EEE4.toInt(), 14f); art.clipToOutline = true
        art.addView(tv(g.title, 16f, 0xFF2B2B2B.toInt(), bold = true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.FIT_CENTER; art.addView(iv, FrameLayout.LayoutParams(-1, -1))
        artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles")
        col.addView(art, LinearLayout.LayoutParams(dp(if (landscape) 150 else 200), dp(if (landscape) 150 else 200)))
        col.addView(tv(g.title, 18f, p.text, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) })
        col.addView(tv(g.console.title + " · Telegram", 13f, p.muted).apply { setPadding(0, dp(4), 0, dp(18)) })
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.max = 1000
        bar.progressDrawable?.setTint(p.primary)
        col.addView(bar, LinearLayout.LayoutParams(minOf(dp(420), resources.displayMetrics.widthPixels - dp(48)), dp(8)))
        val status = tv(T.t("Preparando tu juego…"), 13f, p.text).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        col.addView(status)
        progressBar = bar; progressText = status
        if (PhoneGames.keep(this)) col.addView(tv(T.t("«Guardar juegos descargados» está activado: este juego se quedará en el teléfono."), 12f, p.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) })
        col.addView(button(T.t("Cancelar"), "cancel_load") { cancelLoading() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        root.addView(scroll(col))
        lastFocus = "cancel_load"
    }
    private fun cancelLoading() {
        pending?.let { g -> drive.file(g)?.let { f -> if (f.local.isDownloadingActive) drive.cancelDownload(g) } }
        pending = null; disc = null; discJson = null
        go(if (console != null || source == "fav") "library" else "home")
    }
    private fun updateLoading() {
        val g = pending ?: return
        val bar = progressBar ?: return; val text = progressText ?: return
        disc?.let { parts ->
            var total = 0L; var done = 0L; var all = true; val arr = JSONArray()
            for ((name, id) in parts) {
                val f = drive.part(id); if (f == null) { all = false; continue }
                val size = maxOf(1L, maxOf(f.size, f.expectedSize)); total += size
                done += if (f.local.isDownloadingCompleted) size else f.local.downloadedSize
                if (!f.local.isDownloadingCompleted || !File(f.local.path).isFile) all = false else arr.put(JSONObject().put("name", name).put("path", f.local.path))
            }
            if (all) { discJson = arr.toString(); val f = drive.file(g); if (f != null) launch(g, f.local.path); return }
            bar.progress = (done * 1000 / maxOf(1, total)).toInt()
            text.text = T.t("Pistas del disco: ") + fmt(done) + T.t(" de ") + fmt(total) + " · " + (done * 100 / maxOf(1, total)) + "%"
            return
        }
        val f = drive.file(g)
        if (f != null && f.local.isDownloadingCompleted) { bar.progress = 1000; text.text = T.t("Listo"); launch(g, f.local.path); return }
        if (f != null && f.local.isDownloadingActive) {
            val total = maxOf(1L, maxOf(f.size, f.expectedSize)); val done = f.local.downloadedSize
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastAt != 0L && now > lastAt && done >= lastBytes) { val inst = (done - lastBytes) * 1000.0 / (now - lastAt); speed = if (speed == 0.0) inst else speed * .7 + inst * .3 }
            lastBytes = done; lastAt = now
            bar.progress = (done * 1000 / total).toInt()
            text.text = fmt(done) + T.t(" de ") + fmt(total) + (if (speed > 0) " · " + fmt(speed.toLong()) + "/s" else "") + " · " + (done * 100 / total) + "%"
        } else text.text = drive.error.ifEmpty { T.t("Preparando tu juego…") }
    }
    private fun fmt(b: Long) = android.text.format.Formatter.formatShortFileSize(this, b)

    /** Starts the player (after the BIN/CUE tracks, and after pulling a newer save from Telegram). */
    private fun launch(g: Game, path: String) {
        if (path.endsWith(".cue", true) && discJson == null) { prepareDisc(g, path); return }
        val parts = if (path.endsWith(".cue", true)) discJson else null
        pending = null
        if (PhoneGames.keep(this) && g.telegramAccount != 0L) {
            PhoneGames.remember(this, g, path)
            parts?.let { runCatching { val a = JSONArray(it); for (i in 0 until a.length()) PhoneGames.remember(this, Game(g.id + "#" + i, g.title, g.console, "", ""), a.getJSONObject(i).getString("path")) } }
        }
        library.addRecent(g)
        val cls = if (Console.ownProcess(g.console)) GameActivityN64::class.java else GameActivity::class.java
        val intent = Intent(this, cls).putExtra("rom", path).putExtra("filename", g.fileName).putExtra("title", g.title)
            .putExtra("description", g.description).putExtra("console", g.console.name).putExtra("cover", g.coverName)
            .putExtra("gameid", g.id).putExtra("meta", GameMeta.info(this, g).toString()).putExtra("fileId", g.fileId).putExtra("account", g.telegramAccount)
        if (parts != null) intent.putExtra("parts", parts)
        if (freshStart) { intent.putExtra("fresh", true); freshStart = false }
        drive.playing(g)
        page = if (landscape || console != null) "library" else "detail"
        lastFocus = "game_${g.id}"; render()
        val sha = SaveBackups.shaOf(this, g.id)
        if (sha == null || !drive.ready()) { startActivity(intent); return }
        val state = booleanArrayOf(false, false)
        val go = Runnable { if (!state[0]) { state[0] = true; startActivity(intent) } }
        root.postDelayed({ if (!state[1]) go.run() }, 7000)
        Toast.makeText(this, T.t("Buscando tu partida más reciente…"), Toast.LENGTH_SHORT).show()
        SaveBackups.pullNewest(this, drive, g.console, sha, { if (state[0]) false else { state[1] = true; true } }) { msg ->
            if (msg != null) toast(msg); go.run()
        }
    }
    private fun prepareDisc(g: Game, cue: String) {
        val names = mutableListOf<String>()
        runCatching {
            File(cue).readLines().forEach { l ->
                Regex("""^\s*FILE\s+"?(.+?)"?\s+\S+\s*$""", RegexOption.IGNORE_CASE).find(l)?.let { val n = it.groupValues[1].replace('\\', '/').substringAfterLast('/'); if (n !in names) names += n }
            }
        }
        if (names.isEmpty()) { toast(T.t("El archivo .cue no lista ninguna pista")); return }
        pending = g; if (page != "loading") go("loading")
        progressText?.text = T.t("Buscando las pistas del disco…")
        drive.findParts(g, names) { list, error ->
            if (pending != g) return@findParts
            if (list == null) { toast(error ?: T.t("No se encontraron las pistas del disco")); cancelLoading(); return@findParts }
            disc = names.indices.map { names[it] to list[it].id }
            list.forEach { if (!it.local.isDownloadingCompleted) drive.downloadPart(it.id) }
            updateLoading()
        }
    }
    private fun playFromShortcut() {
        val id = intent?.getStringExtra("play_game") ?: return
        intent.removeExtra("play_game")
        val g = library.games.firstOrNull { it.id == id } ?: run { toast(T.t("Ese juego ya no está en tu biblioteca.")); return }
        console = g.console; selected = g; page = "library"; render()
        startWhenReady(g, 0)
    }
    private fun startWhenReady(g: Game, tries: Int) {
        if (g.telegramAccount == 0L || drive.ready() || tries > 40) { play(g); return }
        root.postDelayed({ startWhenReady(g, tries + 1) }, 500)
    }

    // ------------------------------------------------------------------ play on TV or PC (phone remote over the Wi-Fi)
    private var castStatus = mutableMapOf<String, JSONObject>()
    private var remoteDevice: RemoteClient.Device? = null
    private var remoteTimer: TextView? = null
    private var polling = false
    private val poller = object : Runnable {
        override fun run() {
            if (!polling) return
            pollDevices()
            root.postDelayed(this, if (page == "remote") 2500 else 6000)
        }
    }
    private fun startPolling() {
        RemoteClient.init(this); misses = 0; lastSearch = 0
        if (!polling) { polling = true; root.post(poller) }
        // Coming back to the app: look for paired devices again right away (their address may have changed).
        if (RemoteClient.saved(this).any { it.paired }) findAgain()
        watchWifi(true)
    }
    private fun stopPolling() { polling = false; root.removeCallbacks(poller); watchWifi(false) }
    /** When the Wi-Fi comes back (after the screen was off, for example), reconnect at once. */
    private var wifiWatch: android.net.ConnectivityManager.NetworkCallback? = null
    private fun watchWifi(on: Boolean) {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        wifiWatch?.let { runCatching { cm.unregisterNetworkCallback(it) } }; wifiWatch = null
        if (!on) return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                root.postDelayed({ if (polling) { lastSearch = 0; findAgain(); pollDevices() } }, 1200)
            }
        }
        runCatching { cm.registerNetworkCallback(android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), cb); wifiWatch = cb }
    }
    private var misses = 0
    private var lastSearch = 0L
    /** If a paired TV / PC stops answering (phone asleep, Wi-Fi back, its address changed), look for it again on the Wi-Fi. */
    private fun findAgain() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSearch < 10000) return
        lastSearch = now
        RemoteClient.discover(this) { if (!isFinishing && !isDestroyed) { misses = 0; pollDevices() } }
    }
    /** The saved copy of a device (its address may have been updated). */
    private fun fresh(d: RemoteClient.Device) = RemoteClient.saved(this).firstOrNull { it.id == d.id } ?: d
    private fun pollDevices() {
        val list = RemoteClient.saved(this).filter { it.paired }
        if (list.isEmpty()) { if (castStatus.isNotEmpty()) { castStatus.clear(); castChanged() }; return }
        for (d in list) RemoteClient.call(d, JSONObject().put("cmd", "status"), 3500) { r ->
            if (isFinishing || isDestroyed) return@call
            val before = castStatus[d.id]
            if (r.optString("error") == "offline") { if (++misses >= 2) findAgain() } else misses = 0
            // One missed answer doesn't hide what is playing; two do.
            if (r.optBoolean("ok")) castStatus[d.id] = r else if (r.optString("error") != "offline" || misses >= 2) castStatus.remove(d.id)
            val key = { o: JSONObject? -> if (o == null) "" else o.optString("state") + o.optJSONObject("game")?.optString("id") + o.optBoolean("paused") + o.optBoolean("ff") +
                o.optJSONObject("settings")?.let { it.optString("res") + it.optString("filter") } }
            if (key(before) != key(castStatus[d.id])) castChanged()
        }
    }
    private fun castChanged() {
        if (page == "remote" || page == "home" || page == "library" || page == "detail" || page == "consoles") { lastFocus = lastFocus.ifEmpty { "" }; render() }
    }
    /** The device something is playing on (the one the remote page shows first). */
    private fun castNow(): Pair<RemoteClient.Device, JSONObject>? {
        val saved = RemoteClient.saved(this)
        remoteDevice?.let { d -> castStatus[d.id]?.let { s -> if (s.optString("state") != "menu") return (saved.firstOrNull { it.id == d.id } ?: d) to s } }
        for (d in saved) castStatus[d.id]?.let { s -> if (s.optString("state") != "menu") return d to s }
        return null
    }
    private fun castGame(s: JSONObject): Game? {
        val j = s.optJSONObject("game") ?: return null
        return library.games.firstOrNull { it.id == j.optString("id") }
            ?: library.games.firstOrNull { j.optLong("chatId") != 0L && it.chatId == j.optLong("chatId") && it.messageId == j.optLong("messageId") }
    }
    private fun ago(since: Long): String {
        val m = ((System.currentTimeMillis() - since) / 60000).coerceAtLeast(0)
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }
    private fun deviceIcon(d: RemoteClient.Device, color: Int, bg: Int, size: Int): View {
        val box = FrameLayout(this); box.background = round(bg, size / 2f)
        box.addView(IconView(this, if (d.kind == "pc") "pc" else "tv", d.name, color), FrameLayout.LayoutParams(dp(size), dp(size)))
        return box
    }

    /** Bottom bar while a TV or PC is playing: tap for the remote, button to pause / resume. */
    private fun miniPlayer(): View? {
        val (d, s) = castNow() ?: return null
        val j = s.optJSONObject("game") ?: return null
        val bar = horizontal(); bar.setPadding(dp(10), dp(9), dp(12), dp(9))
        val art = FrameLayout(this); art.background = round(p.primary, 10f); art.clipToOutline = true
        castGame(s)?.let { g -> val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.CENTER_CROP; art.addView(iv, FrameLayout.LayoutParams(-1, -1)); artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles") }
        bar.addView(art, LinearLayout.LayoutParams(dp(42), dp(42)))
        val txt = vertical(); txt.setPadding(dp(10), 0, dp(6), 0)
        txt.addView(tv(j.optString("title"), 14f, p.text, bold = true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
        val line = if (s.optString("state") == "loading") T.t("Preparando en ") + d.name + "…" else d.name + " · " + ago(s.optLong("since")) + if (s.optBoolean("paused")) " · " + T.t("en pausa") else ""
        txt.addView(tv(line, 12f, p.muted).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) })
        bar.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
        if (s.optString("state") == "playing") {
            val paused = s.optBoolean("paused")
            bar.addView(tv(if (paused) "▶" else "❚❚", 18f, p.text, bold = true).apply { gravity = Gravity.CENTER; contentDescription = T.t(if (paused) "Continuar" else "Pausa")
                control(this, "mini_pause", p.surface2, 22f, false) { castControl(d, if (paused) "resume" else "pause") } }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        control(bar, "mini_open", p.surface2, 18f, false) { remoteDevice = d; go("remote") }
        return bar
    }

    /** "Play on TV or PC" (with a game) or the list of devices (from the Cast button). */
    internal fun castSheet(game: Game?) {
        if (game != null && game.telegramAccount == 0L) { toast(T.t("Este juego está solo en tu teléfono: los otros dispositivos no pueden abrirlo.")); return }
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val col = vertical(); col.setPadding(dp(18), dp(10), dp(18), dp(20))
        col.background = GradientDrawable().apply { setColor(p.surface); cornerRadii = floatArrayOf(dp(24f).toFloat(), dp(24f).toFloat(), dp(24f).toFloat(), dp(24f).toFloat(), 0f, 0f, 0f, 0f) }
        col.addView(View(this).apply { background = round(p.muted, 2f); alpha = .5f }, LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        col.addView(tv(if (game != null) T.t("Jugar en") else T.t("Tus TV y PC"), 18f, p.text, bold = true))
        col.addView(tv(if (game != null) game.title else T.t("Dispositivos con RetroStream en tu red Wi-Fi"), 13f, p.muted).apply { setPadding(0, dp(3), 0, dp(10)) })
        val list = vertical(); col.addView(list)
        val status = tv(T.t("Buscando en tu Wi-Fi…"), 13f, p.muted).apply { setPadding(dp(4), dp(10), 0, dp(10)) }
        list.addView(status)
        val foot = horizontal(); foot.setPadding(0, dp(12), 0, 0)
        foot.addView(tv(T.t("Buscar de nuevo"), 13f, p.primary).also { control(it, "cast_again", Color.TRANSPARENT, 8f, false) { dialog.dismiss(); castSheet(game) } }.apply { setPadding(dp(4), dp(8), dp(12), dp(8)) })
        foot.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        foot.addView(tv(T.t("Escribir dirección"), 13f, p.primary).also { control(it, "cast_ip", Color.TRANSPARENT, 8f, false) { dialog.dismiss(); addByAddress(game) } }.apply { setPadding(dp(12), dp(8), dp(4), dp(8)) })
        col.addView(foot)
        dialog.setContentView(col)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(minOf(resources.displayMetrics.widthPixels, dp(560)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        RemoteClient.discover(this) { devices ->
            if (!dialog.isShowing) return@discover
            list.removeAllViews()
            if (devices.isEmpty()) {
                list.addView(tv(T.t("No se encontró ningún dispositivo. Abre RetroStream en la TV o el PC, conéctalos a la misma red Wi-Fi que este teléfono y vuelve a buscar."), 13f, p.muted).apply { setPadding(dp(4), dp(8), 0, dp(8)) })
                return@discover
            }
            for (d in devices) {
                pollOne(d)
                val s = castStatus[d.id]
                val row = horizontal(); row.setPadding(dp(12), dp(12), dp(12), dp(12))
                val playingHere = s != null && s.optString("state") == "playing"
                row.addView(deviceIcon(d, if (playingHere) p.onPrimary else p.text, if (playingHere) p.primary else p.surface2, 44), LinearLayout.LayoutParams(dp(44), dp(44)))
                val txt = vertical(); txt.setPadding(dp(12), 0, dp(8), 0)
                txt.addView(tv(d.name, 15f, p.text, bold = true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
                val line = when {
                    !d.paired -> T.t("Nuevo · vincúlalo con un código")
                    s == null -> if (d.kind == "pc") "PC" else "TV"
                    playingHere -> "● " + T.t("Jugando ") + s.optJSONObject("game")?.optString("title")
                    s.optString("state") == "loading" -> T.t("Preparando un juego…")
                    else -> "● " + T.t("En el menú")
                }
                txt.addView(tv(line, 12f, if (playingHere) 0xFF22C55E.toInt() else p.muted).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) })
                row.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
                if (!d.paired) row.addView(tv(T.t("Vincular"), 13f, p.onPrimary, bold = true).apply { setPadding(dp(12), dp(6), dp(12), dp(6)); background = round(p.primary, 14f) })
                control(row, "dev_" + d.id, p.surface2, 16f, false) {
                    dialog.dismiss()
                    val go = {
                        if (game != null) castPlay(d, game, false)
                        else { remoteDevice = d; go("remote") }
                    }
                    if (d.paired) go() else pair(d) { go() }
                }
                if (d.paired) row.setOnLongClickListener {
                    AlertDialog.Builder(this).setTitle(d.name).setItems(arrayOf(T.t("Olvidar este dispositivo"))) { _, _ -> RemoteClient.forget(this, d.id); castStatus.remove(d.id); dialog.dismiss(); castSheet(game) }.show(); true
                }
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }
    }
    private fun pollOne(d: RemoteClient.Device) {
        if (!d.paired) return
        RemoteClient.call(d, JSONObject().put("cmd", "status"), 3000) { r -> if (r.optBoolean("ok")) castStatus[d.id] = r else if (r.optString("error") == "unpaired") { d.token = ""; RemoteClient.remember(this, d) } }
    }
    private fun addByAddress(game: Game?) {
        val input = field("192.168.1.20", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        AlertDialog.Builder(this).setTitle(T.t("Dirección de la TV o el PC"))
            .setMessage(T.t("En el PC: Ajustes › Control desde el teléfono muestra su dirección. En la TV: Ajustes de Android › Red."))
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(4), dp(20), 0); addView(input) })
            .setPositiveButton(T.t("Conectar")) { _, _ ->
                val host = input.text.toString().trim().substringBefore(':'); if (host.isEmpty()) return@setPositiveButton
                RemoteClient.call(host, Remote.PORT, JSONObject().put("cmd", "hello")) { r ->
                    if (!r.optBoolean("ok")) { toast(RemoteClient.problem(r) ?: T.t("No responde.")); return@call }
                    val known = RemoteClient.saved(this).firstOrNull { it.id == r.optString("id") }
                    val d = known?.also { it.host = host } ?: RemoteClient.Device(r.optString("id"), r.optString("name"), r.optString("kind", "tv"), host, Remote.PORT, "")
                    val go = { if (game != null) castPlay(d, game, false) else { remoteDevice = d; go("remote") } }
                    if (d.paired) { RemoteClient.remember(this, d); go() } else pair(d) { go() }
                }
            }.setNegativeButton(T.t("Cancelar"), null).show()
    }
    /** Asks the TV / PC for a code, shows four boxes to type it, keeps the key it gives back. */
    private fun pair(d: RemoteClient.Device, then: () -> Unit) {
        RemoteClient.call(d.host, d.port, JSONObject().put("cmd", "pair").put("phone", RemoteClient.phoneName())) { r ->
            if (!r.optBoolean("ok")) { toast(RemoteClient.problem(r) ?: T.t("No responde.")); return@call }
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(android.text.InputFilter.LengthFilter(4)); gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f); letterSpacing = .6f; hint = "• • • •"; setTextColor(p.text)
            }
            val box = vertical(); box.setPadding(dp(22), dp(6), dp(22), 0)
            box.addView(tv(T.t("Escribe el código que aparece en ") + d.name + ".", 14f, p.muted))
            box.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            val dialog = AlertDialog.Builder(this).setTitle(T.t("Vincular con ") + d.name).setView(box).setNegativeButton(T.t("Cancelar"), null).create()
            fun send() {
                val code = input.text.toString(); if (code.length != 4) return
                RemoteClient.call(d.host, d.port, JSONObject().put("cmd", "pair").put("code", code).put("phone", RemoteClient.phoneName())) { a ->
                    val token = a.optString("token")
                    if (token.isEmpty()) { input.setText(""); toast(RemoteClient.problem(a) ?: T.t("No se pudo vincular.")); if (a.optString("error") == "expired") dialog.dismiss(); return@call }
                    d.token = token; d.name = a.optString("name", d.name); RemoteClient.remember(this, d)
                    dialog.dismiss(); toast(T.t("Vinculado con ") + d.name); then()
                }
            }
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { if ((s?.length ?: 0) == 4) send() }
            })
            dialog.show()
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
    }
    private fun briefOf(g: Game) = JSONObject().put("id", g.id).put("title", g.title).put("console", g.console.name)
        .put("chatId", g.chatId).put("messageId", g.messageId).put("fileName", g.fileName ?: "")
    private fun castPlay(d0: RemoteClient.Device, g: Game, fromStart: Boolean) {
        val d = fresh(d0)
        toast(T.t("Abriendo en ") + d.name + "…")
        RemoteClient.call(d, JSONObject().put("cmd", "play").put("game", briefOf(g)).put("fresh", fromStart), 30000) { r ->
            RemoteClient.problem(r)?.let { toast(it); if (r.optString("error") == "unpaired") { d.token = ""; RemoteClient.remember(this, d) }; return@call }
            remoteDevice = d
            castStatus[d.id] = JSONObject().put("ok", true).put("state", "loading").put("game", briefOf(g)).put("since", System.currentTimeMillis())
            go("remote"); root.postDelayed({ pollDevices() }, 1500)
        }
    }
    private fun castControl(d0: RemoteClient.Device, action: String, slot: Int = 1, value: String = "") {
        val d = fresh(d0)
        RemoteClient.call(d, JSONObject().put("cmd", "control").put("action", action).put("slot", slot).put("value", value), if (action == "backup") 30000 else 10000) { r ->
            val bad = RemoteClient.problem(r)
            if (bad != null) toast(bad)
            else when (action) {
                "save" -> toast(T.t("Guardado en el espacio ") + slot)
                "load" -> toast(T.t("Cargado el espacio ") + slot)
                "shot" -> toast(T.t("Captura tomada"))
                "backup" -> toast(T.t("Partida copiada a Telegram"))
                "exit" -> toast(T.t("Juego cerrado"))
                "res" -> toast(T.t("Cambiando la resolución: el juego se cierra un momento y sigue donde estaba."))
            }
            // The answer arrives a moment before the TV / PC updates its state.
            root.postDelayed({ pollDevices() }, 400)
        }
    }
    // ------------------------------------------------------------------ updates, shown over the phone's own screens
    private fun checkUpdates() {
        Toast.makeText(this, T.t("Buscando actualizaciones…"), Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val info = Updater.check()
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (info.build <= BuildConfig.VERSION_CODE) {
                        AlertDialog.Builder(this).setTitle(T.t("Actualizaciones")).setMessage(T.t("Ya tienes la última versión (build ") + BuildConfig.VERSION_CODE + ").").setPositiveButton(T.t("Cerrar"), null).show()
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(this).setTitle(T.t("Nueva versión disponible"))
                        .setMessage("Build " + info.build + " · " + (info.size / (1024 * 1024)) + " MB\n\n" + info.notes + T.t("\n\nTus partidas y la sesión de Telegram se conservan."))
                        .setPositiveButton(T.t("Actualizar ahora")) { _, _ -> startUpdate(info) }.setNegativeButton(T.t("Más tarde"), null).show()
                }
            } catch (e: Exception) {
                runOnUiThread { AlertDialog.Builder(this).setTitle(T.t("Actualizaciones")).setMessage(T.t("No se pudo comprobar: ") + e.message).setPositiveButton(T.t("Cerrar"), null).show() }
            }
        }.start()
    }
    private fun startUpdate(info: Updater.Info) {
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this).setTitle(T.t("Permiso necesario"))
                .setMessage(T.t("Android pide permiso una sola vez para que RetroStream pueda instalar su propia actualización. Activa «Permitir de esta fuente» y vuelve a pulsar Actualizar."))
                .setPositiveButton(T.t("Abrir ajustes")) { _, _ -> runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName"))) } }
                .setNegativeButton(T.t("Cancelar"), null).show()
            return
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.max = 100; bar.progressDrawable?.setTint(p.primary)
        val box = vertical(); box.setPadding(dp(24), dp(16), dp(24), dp(8)); box.addView(bar)
        val dialog = AlertDialog.Builder(this).setTitle(T.t("Descargando actualización…")).setView(box).setCancelable(false).show()
        Thread {
            try {
                val apk = Updater.download(this, info) { done, total -> val pct = (done * 100 / maxOf(1L, total)).toInt(); runOnUiThread { bar.progress = pct } }
                runOnUiThread { dialog.dismiss(); Toast.makeText(this, T.t("Instalando…"), Toast.LENGTH_LONG).show() }
                Updater.install(this, apk)
            } catch (e: Exception) {
                runOnUiThread { dialog.dismiss(); AlertDialog.Builder(this).setTitle(T.t("Actualizaciones")).setMessage(e.message).setPositiveButton(T.t("Cerrar"), null).show() }
            }
        }.start()
    }

    // ------------------------------------------------------------------ media: every screenshot (phone, TV and PC), newest first
    private var mediaShots: List<TirsoDrive.Shot>? = null
    private var mediaShown = 60
    private fun mediaPage() {
        val col = vertical(); col.setPadding(0, 0, 0, dp(18))
        col.addView(header(T.t("Capturas"), true))
        val status = tv(T.t("Cargando capturas…"), 13f, p.muted).apply { setPadding(dp(18), dp(4), dp(18), dp(8)) }
        col.addView(status)
        val grid = vertical(); grid.setPadding(dp(12), 0, dp(12), 0); col.addView(grid)
        root.addView(scroll(col))
        fun fill(list: List<TirsoDrive.Shot>) {
            status.text = list.size.toString() + T.t(" capturas") + " · " + T.t("toca una para verla y compartirla")
            val cols = if (landscape) 5 else 3
            var row: LinearLayout? = null
            list.take(mediaShown).forEachIndexed { i, s ->
                if (i % cols == 0) { row = horizontal(); grid.addView(row) }
                val cell = vertical(); cell.setPadding(dp(4), dp(4), dp(4), dp(6))
                val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.background = round(p.surface2, 10f); iv.clipToOutline = true
                cell.addView(iv, LinearLayout.LayoutParams(-1, dp(if (landscape) 90 else 96)))
                cell.addView(tv(s.title, 11f, p.text).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), dp(4), 0, 0) })
                drive.downloadPicture(s.thumbId) { path, _ -> if (path != null) iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path)) }
                control(cell, "media_$i", Color.TRANSPARENT, 12f, false) { openShot(s) }
                row!!.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            row?.let { r -> while (r.childCount < cols) r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            if (list.size > mediaShown) grid.addView(button(T.t("Ver más"), "media_more") { mediaShown += 60; lastFocus = "media_more"; render() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        mediaShots?.let { fill(it); return }
        if (!drive.ready()) { status.text = T.t("Conecta TirsoDrive para ver las capturas."); return }
        drive.listAllShots { list, err ->
            if (page != "media") return@listAllShots
            if (list.isNullOrEmpty()) { status.text = err ?: T.t("Aún no hay capturas. En un juego: menú > Captura de pantalla, o Select + R1."); return@listAllShots }
            mediaShots = list; fill(list)
        }
    }
    private fun openShot(s: TirsoDrive.Shot) {
        drive.downloadPicture(s.fullId) { path, _ ->
            if (path == null) { toast(T.t("No se pudo abrir la captura.")); return@downloadPicture }
            val big = ImageView(this); big.adjustViewBounds = true; big.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path))
            val b = AlertDialog.Builder(this).setTitle(s.title).setView(big).setPositiveButton(T.t("Cerrar"), null)
                .setNeutralButton(T.t("Compartir")) { _, _ -> Screenshots.shareToApps(this, path, s.title) }
            if (drive.friendsChat() != 0L) b.setNegativeButton(T.t("A amigos")) { _, _ -> Screenshots.shareToFriends(this, drive, path, s.title) }
            b.show()
        }
    }

    /** The game's cheats on the TV / PC: tick to turn on or off, or all off. */
    private fun cheatsDialog(d0: RemoteClient.Device) {
        val d = fresh(d0)
        toast(T.t("Cargando trucos…"))
        RemoteClient.call(d, JSONObject().put("cmd", "control").put("action", "cheats"), 30000) { r ->
            RemoteClient.problem(r)?.let { toast(it); return@call }
            val a = r.optJSONArray("list") ?: JSONArray()
            if (a.length() == 0) { toast(T.t("No hay trucos para este juego.")); return@call }
            val names = Array(a.length()) { a.optJSONObject(it)?.optString("name") ?: "" }
            val on = BooleanArray(a.length()) { a.optJSONObject(it)?.optBoolean("on") == true }
            val count = { on.count { it } }
            val dialog = AlertDialog.Builder(this).setTitle(T.t("Trucos") + " (" + count() + " " + T.t("activos") + ")")
                .setMultiChoiceItems(names, on) { dlg, i, checked ->
                    on[i] = checked
                    RemoteClient.call(d, JSONObject().put("cmd", "control").put("action", "cheat").put("slot", i).put("value", if (checked) "on" else "off")) { x -> RemoteClient.problem(x)?.let { toast(it) } }
                    (dlg as? AlertDialog)?.setTitle(T.t("Trucos") + " (" + count() + " " + T.t("activos") + ")")
                }
                .setPositiveButton(T.t("Listo"), null)
                .setNeutralButton(T.t("Todo apagado")) { _, _ ->
                    RemoteClient.call(d, JSONObject().put("cmd", "control").put("action", "cheatsoff")) { x -> toast(RemoteClient.problem(x) ?: T.t("Trucos apagados")) }
                }.create()
            dialog.show()
            toast(T.t("Los trucos marcados con (M) o Master deben estar activos para que funcionen los demás. Guarda antes: un truco puede romper el juego."))
        }
    }
    private fun chooseSlot(d: RemoteClient.Device, save: Boolean) {
        AlertDialog.Builder(this).setTitle(T.t(if (save) "Guardar en" else "Cargar desde"))
            .setItems(arrayOf(T.t("Espacio 1"), T.t("Espacio 2"), T.t("Espacio 3"))) { _, i -> castControl(d, if (save) "save" else "load", i + 1) }
            .setNegativeButton(T.t("Cancelar"), null).show()
    }

    /** The remote: what is playing on a TV / PC and its controls. */
    private fun remotePage() {
        val d = (remoteDevice ?: castNow()?.first ?: RemoteClient.saved(this).firstOrNull { it.paired } ?: run { page = "home"; home(); return }).let { fresh(it) }
        remoteDevice = d
        val s = castStatus[d.id]
        val state = s?.optString("state") ?: "offline"
        val j = s?.optJSONObject("game")
        val g = s?.let { castGame(it) }
        val playing = state == "playing" && s != null
        val can = if (playing) s!!.optJSONArray("controls")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: setOf("exit") else emptySet()
        val paused = s?.optBoolean("paused") == true
        val consoleTitle = Console.values().firstOrNull { it.name == j?.optString("console") }?.title ?: j?.optString("console") ?: ""

        // Upright, everything fits on one screen: the cover takes whatever the controls leave. Sideways it scrolls.
        val col = vertical(); col.setPadding(dp(16), 0, dp(16), dp(14))
        val top = horizontal(); top.setPadding(0, dp(18), 0, dp(4))
        top.addView(icon("back", T.t("Volver"), "back") { onBackPressed() })
        val head = vertical(); head.setPadding(dp(8), 0, dp(8), 0)
        head.addView(tv(T.t("JUGANDO EN"), 10f, p.muted, mono = true).apply { letterSpacing = .14f })
        head.addView(tv(d.name, 16f, p.text, bold = true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0) })
        top.addView(head, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(icon("cast", T.t("Cambiar de dispositivo"), "r_switch") { castSheet(null) })
        col.addView(top)

        val art = FrameLayout(this); art.background = round(p.surface2, 22f); art.clipToOutline = true
        art.addView(tv(j?.optString("title") ?: d.name, 18f, p.muted, bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(20), dp(20), dp(20)) }, FrameLayout.LayoutParams(-1, -1))
        if (g != null) { val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.FIT_CENTER; art.addView(iv, FrameLayout.LayoutParams(-1, -1)); artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Snaps", "Named_Titles") }
        fun badge(text: String, bg: Int, where: Int) = art.addView(tv(text, 12f, Color.WHITE, bold = true).apply { setPadding(dp(9), dp(4), dp(9), dp(4)); background = round(bg, 9f) },
            FrameLayout.LayoutParams(-2, -2, where).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        if (playing) {
            badge(ago(s!!.optLong("since")), 0xCC000000.toInt(), Gravity.BOTTOM or Gravity.END)
            if (paused) badge(T.t("En pausa"), 0xE6F59E0B.toInt(), Gravity.TOP or Gravity.START)
            if (s.optBoolean("ff")) badge(T.t("Avance rápido"), 0xE62F6DF6.toInt(), Gravity.TOP or Gravity.END)
        }
        col.addView(art, if (landscape) LinearLayout.LayoutParams(-1, dp(150)).apply { topMargin = dp(4) } else LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })

        col.addView(tv(j?.optString("title") ?: when (state) { "menu" -> T.t("Nada en juego"); "offline" -> T.t("Buscando el dispositivo…"); else -> "" }, 19f, p.text, bold = true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), dp(12), 0, 0) })
        val sub = when (state) {
            "playing" -> consoleTitle
            "loading" -> T.t("Descargando o preparando el juego en ") + d.name + "…"
            "menu" -> T.t("Elige un juego y toca «Jugar en TV o PC».")
            else -> T.t("Si no aparece: abre RetroStream en la TV o el PC y revisa que esté en la misma red Wi-Fi.")
        }
        col.addView(tv(sub, 13f, p.muted).apply { maxLines = 2; setPadding(dp(2), dp(2), 0, dp(10)) })

        if (playing) {
            val transport = horizontal(); transport.gravity = Gravity.CENTER
            fun roundButton(iconName: String, label: String, tag: String, size: Int, filled: Boolean, f: () -> Unit) {
                val cell = vertical(); cell.gravity = Gravity.CENTER_HORIZONTAL
                val b = FrameLayout(this)
                b.addView(IconView(this, iconName, label, if (filled) p.onPrimary else p.text), FrameLayout.LayoutParams(dp(size * 0.6f), dp(size * 0.6f), Gravity.CENTER))
                control(b, tag, if (filled) p.primary else p.surface2, size / 2f, false, f)
                cell.addView(b, LinearLayout.LayoutParams(dp(size), dp(size)))
                cell.addView(tv(label, 11f, p.muted).apply { gravity = Gravity.CENTER; setSingleLine(); setPadding(0, dp(4), 0, 0) })
                transport.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            if ("ff" in can) roundButton("ff", T.t("Avance rápido"), "r_ff", 56, s!!.optBoolean("ff")) { castControl(d, "ff") }
            if ("pause" in can) roundButton(if (paused) "play" else "pause", T.t(if (paused) "Continuar" else "Pausa"), "r_pause", 72, true) { castControl(d, if (paused) "resume" else "pause") }
            if ("shot" in can) roundButton("camera", T.t("Captura"), "r_shot", 56, false) { castControl(d, "shot") }
            if (transport.childCount > 0) col.addView(transport)

            // One row of tiles: icon over a short name.
            val tiles = mutableListOf<Triple<String, String, () -> Unit>>()
            if ("save" in can) tiles += Triple("save", T.t("Guardar"), { chooseSlot(d, true) })
            if ("load" in can) tiles += Triple("load", T.t("Cargar"), { chooseSlot(d, false) })
            if ("backup" in can) tiles += Triple("cloud", T.t("Copia"), { castControl(d, "backup") })
            if ("reset" in can) tiles += Triple("restart", T.t("Reiniciar"), {
                AlertDialog.Builder(this).setTitle(T.t("¿Reiniciar el juego?")).setMessage(T.t("Se pierde lo que no hayas guardado."))
                    .setPositiveButton(T.t("Reiniciar")) { _, _ -> castControl(d, "reset") }.setNegativeButton(T.t("Cancelar"), null).show()
                Unit
            })
            if ("cheats" in can) tiles += Triple("cheat", T.t("Trucos"), { cheatsDialog(d) })
            if (tiles.isNotEmpty()) {
                val row = horizontal(); row.setPadding(0, dp(12), 0, 0)
                for ((k, t) in tiles.withIndex()) {
                    val good = t.first == "cloud"; val fg = if (good) 0xFF22C55E.toInt() else p.text
                    val tile = vertical(); tile.gravity = Gravity.CENTER_HORIZONTAL; tile.setPadding(dp(4), dp(11), dp(4), dp(10))
                    tile.addView(IconView(this, t.first, t.second, fg), LinearLayout.LayoutParams(dp(26), dp(26)))
                    tile.addView(tv(t.second, if (tiles.size > 4) 11f else 12f, fg, bold = true).apply { gravity = Gravity.CENTER; setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0) })
                    control(tile, "r_tile_" + t.first, if (good) 0x1A22C55E else p.surface2, 16f, false, t.third)
                    row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (k > 0) marginStart = dp(if (tiles.size > 4) 6 else 8) })
                }
                col.addView(row)
            }

            val st = s!!.optJSONObject("settings") ?: JSONObject()
            val resOptions = st.optJSONArray("resOptions")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            val filters = st.optJSONArray("filters")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            if (("res" in can && resOptions.isNotEmpty()) || ("filter" in can && filters.isNotEmpty())) {
                val card = vertical(); card.setPadding(dp(12), dp(10), dp(12), dp(10)); card.background = round(p.surface2, 16f)
                if ("res" in can && resOptions.isNotEmpty()) {
                    val line = horizontal()
                    line.addView(tv(T.t("Resolución"), 12f, p.muted), LinearLayout.LayoutParams(dp(92), -2))
                    val seg = horizontal(); seg.background = round(p.bg, 12f); seg.setPadding(dp(3), dp(3), dp(3), dp(3))
                    val names = mapOf("low" to T.t("Baja"), "medium" to T.t("Media"), "high" to T.t("Alta"))
                    for (v in resOptions) {
                        val on = st.optString("res") == v
                        val b = tv(names[v] ?: v, 13f, if (on) p.onPrimary else p.text, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(7), 0, dp(7)) }
                        control(b, "r_res_$v", if (on) p.primary else Color.TRANSPARENT, 10f, false) {
                            if (!on) AlertDialog.Builder(this).setTitle(T.t("Resolución: ") + (names[v] ?: v))
                                .setMessage(T.t("El juego se cierra un momento y vuelve a abrir donde estaba (se guarda solo)."))
                                .setPositiveButton(T.t("Cambiar")) { _, _ -> castControl(d, "res", value = v) }.setNegativeButton(T.t("Cancelar"), null).show()
                        }
                        seg.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    line.addView(seg, LinearLayout.LayoutParams(0, -2, 1f))
                    card.addView(line)
                }
                if ("filter" in can && filters.isNotEmpty()) {
                    val line = horizontal(); if (card.childCount > 0) line.setPadding(0, dp(8), 0, 0)
                    line.addView(tv(T.t("Filtro"), 12f, p.muted), LinearLayout.LayoutParams(dp(92), -2))
                    val names = mapOf("default" to T.t("Original"), "sharp" to T.t("Píxeles nítidos"), "smooth" to T.t("Suavizado"), "smooth2" to T.t("Suavizado fuerte"), "lcd" to T.t("LCD portátil"), "crt" to T.t("TV CRT"))
                    val row = horizontal()
                    for ((i, v) in filters.withIndex()) {
                        val c = chip(names[v] ?: v, st.optString("filter") == v, "r_filter_$v") {
                            castControl(d, "filter", value = v)
                            // Show the choice now, on the newest copy of the TV / PC's state (not the one this screen was drawn from).
                            castStatus[d.id]?.optJSONObject("settings")?.put("filter", v); lastFocus = "r_filter_$v"; render()
                        }
                        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); c.setPadding(dp(11), dp(6), dp(11), dp(6))
                        row.addView(c, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(6) })
                    }
                    line.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(0, -2, 1f))
                    card.addView(line)
                }
                col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }
            if (!can.contains("pause")) col.addView(tv(T.t("Esta consola usa su propio programa en el PC: desde el teléfono puedes ver qué se juega y cerrarlo."), 12f, p.muted).apply { setPadding(0, dp(10), 0, 0) })
        }

        val bottom = horizontal(); bottom.setPadding(0, dp(10), 0, 0)
        fun wide(iconName: String, label: String, tag: String, fg: Int, bg: Int, f: () -> Unit) {
            val b = horizontal(); b.gravity = Gravity.CENTER; b.setPadding(dp(4), dp(11), dp(4), dp(11))
            b.addView(IconView(this, iconName, label, fg), LinearLayout.LayoutParams(dp(18), dp(18)))
            b.addView(tv(label, 13f, fg, bold = true).apply { setPadding(dp(6), 0, 0, 0); setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
            control(b, tag, bg, 16f, false, f)
            bottom.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (bottom.childCount > 0) marginStart = dp(8) })
        }
        if (j != null) {
            wide("youtube", "YouTube", "r_yt", p.text, p.surface2) {
                val q = (j.optString("title") + " " + consoleTitle + " walkthrough").trim()
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(q)))) }
            }
            if (g != null) wide("photos", T.t("Capturas"), "r_shots", p.text, p.surface2) { showShots(g) }
        }
        if (playing) wide("exit", T.t("Salir"), "r_exit", 0xFFFCA5A5.toInt(), 0xFF3B1219.toInt()) {
            AlertDialog.Builder(this).setTitle(T.t("¿Salir del juego?")).setMessage(T.t("Se guarda dónde lo dejaste, como al salir con el mando."))
                .setPositiveButton(T.t("Salir")) { _, _ -> castControl(d, "exit") }.setNegativeButton(T.t("Cancelar"), null).show()
        }
        if (state == "offline") {
            col.addView(tv(d.host + " · " + RemoteClient.lastError.ifEmpty { T.t("sin respuesta") }, 11f, p.muted, mono = true).apply { maxLines = 2; setPadding(dp(2), 0, 0, dp(8)) })
            col.addView(button(T.t("Reconectar"), "r_reconnect", true) {
                toast(T.t("Buscando en tu Wi-Fi…")); lastSearch = 0; findAgain(); pollDevices()
            }, LinearLayout.LayoutParams(-1, dp(50)))
        }
        if (bottom.childCount > 0) col.addView(bottom)
        if (landscape) root.addView(scroll(col)) else root.addView(col, FrameLayout.LayoutParams(-1, -1))
        lastFocus = lastFocus.ifEmpty { "r_pause" }
        pollDevices()
    }

    // ------------------------------------------------------------------ more: screenshots, shortcut, info
    private val menuHost = object : GameMenu.Host {
        override fun refresh() { library = Library(this@PhoneActivity); if (selected != null) selected = library.games.firstOrNull { it.id == selected!!.id } ?: selected; render() }
        override fun play(g: Game, fresh: Boolean) { freshStart = fresh; this@PhoneActivity.play(g) }
        override fun shots(g: Game) = showShots(g)
        override fun pin(g: Game) = pinShortcut(g)
        override fun openCollection(name: String) { console = null; source = "col"; collectionName = name; query = ""; go("library") }
        override fun open(g: Game) { selected = g; console = g.console; source = "all"; if (landscape) go("library") else { lastFocus = ""; go("detail") } }
    }
    private fun gameMore(g: Game, recent: Boolean = false) = GameMenu.show(this, g, library, drive, recent, menuHost)
    /** Looks up names, covers and descriptions for games that don't have them yet. */
    private fun scrape() = GameMeta.refresh(this, library.games) { if (!isFinishing && !isDestroyed) { library = Library(this); if (page == "home" || page == "library" || page == "detail") render() } }
    /** Home screen shortcut: the game's logo on white (box cover if there is no logo); opens straight into the game. */
    private fun pinShortcut(g: Game) {
        val sm = getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
        if (!sm.isRequestPinShortcutSupported) { toast(T.t("Tu pantalla de inicio no admite accesos directos.")); return }
        Toast.makeText(this, T.t("Preparando el icono…"), Toast.LENGTH_SHORT).show()
        Thread {
            val bmp = runCatching { shortcutIcon(g) }.getOrNull()
            runOnUiThread {
                val open = Intent(this, PhoneActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("play_game", g.id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val icon = if (bmp != null) android.graphics.drawable.Icon.createWithAdaptiveBitmap(bmp) else android.graphics.drawable.Icon.createWithResource(this, R.drawable.app_icon)
                val info = android.content.pm.ShortcutInfo.Builder(this, "game_" + Integer.toHexString(g.id.hashCode()))
                    .setShortLabel(g.title.take(24)).setLongLabel(g.title).setIcon(icon).setIntent(open).build()
                sm.requestPinShortcut(info, null)
            }
        }.start()
    }
    /** 432 px adaptive icon: logo centered on white inside the safe area, or the cover filling it. */
    private fun shortcutIcon(g: Game): android.graphics.Bitmap? {
        val size = 432
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val logo = Artwork(applicationContext).snapshot(g, "Named_Logos")
        if (logo != null) {
            canvas.drawColor(Color.WHITE)
            val box = size * 0.58f   // adaptive icons keep the middle ~66% visible
            val scale = minOf(box / logo.width, box / logo.height)
            val w = logo.width * scale; val h = logo.height * scale
            canvas.drawBitmap(logo, null, android.graphics.RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), paint)
            return out
        }
        val f = artwork.cachedCover(g) ?: return null
        val b = android.graphics.BitmapFactory.decodeFile(f.path) ?: return null
        val s = minOf(b.width, b.height)
        canvas.drawBitmap(b, android.graphics.Rect((b.width - s) / 2, (b.height - s) / 2, (b.width + s) / 2, (b.height + s) / 2), android.graphics.Rect(0, 0, size, size), paint)
        return out
    }
    private fun showShots(g: Game) {
        val grid = vertical(); grid.setPadding(dp(8), dp(8), dp(8), dp(8))
        val status = tv(T.t("Cargando capturas…"), 14f, 0xFF8C93A8.toInt()); grid.addView(status)
        val dialog = AlertDialog.Builder(this).setTitle(T.t("Capturas · ") + g.title).setView(ScrollView(this).apply { addView(grid) })
            .setNegativeButton(T.t("Cerrar"), null).create()
        dialog.show()
        if (!drive.ready()) { status.text = T.t("Conecta TirsoDrive para ver las capturas."); return }
        drive.listShots(g.id) { list, err ->
            if (!dialog.isShowing) return@listShots
            if (list.isNullOrEmpty()) { status.text = err ?: T.t("Aún no hay capturas de este juego. En el juego: menú > Captura de pantalla, o Select + R1."); return@listShots }
            status.text = list.size.toString() + T.t(" capturas")
            var row: LinearLayout? = null
            list.forEachIndexed { i, s ->
                if (i % 2 == 0) { row = horizontal(); grid.addView(row) }
                val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.CENTER_CROP; iv.setBackgroundColor(0xFF1A1E2B.toInt())
                iv.isFocusable = true; iv.isClickable = true
                row!!.addView(iv, LinearLayout.LayoutParams(0, dp(110), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                drive.downloadPicture(s.thumbId) { path, _ -> if (path != null) iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path)) }
                iv.setOnClickListener {
                    drive.downloadPicture(s.fullId) { path, _ ->
                        if (path == null) return@downloadPicture
                        val big = ImageView(this); big.adjustViewBounds = true; big.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path))
                        val b = AlertDialog.Builder(this).setView(big).setPositiveButton(T.t("Cerrar"), null)
                            .setNeutralButton(T.t("Compartir")) { _, _ -> Screenshots.shareToApps(this, path, g.title) }
                        if (drive.friendsChat() != 0L) b.setNegativeButton(T.t("A amigos")) { _, _ -> Screenshots.shareToFriends(this, drive, path, g.title) }
                        b.show()
                    }
                }
            }
            if (row != null && row!!.childCount == 1) row!!.addView(View(this), LinearLayout.LayoutParams(0, dp(110), 1f))
        }
    }


    // ================================================================== settings (phone)
    private var friendsQueued = false
    private fun page(title: String, body: (LinearLayout) -> Unit) {
        val outer = vertical()
        val top = horizontal(); top.setPadding(dp(10), dp(26), dp(10), dp(8))
        top.addView(icon("back", T.t("Volver"), "back") { onBackPressed() })
        top.addView(tv(title, 20f, p.text, bold = true).apply { setPadding(dp(6), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        outer.addView(top)
        val col = vertical(); col.setPadding(dp(14), 0, dp(14), dp(24))
        val limit = if (landscape) minOf(resources.displayMetrics.widthPixels - dp(28), dp(760)) else -1
        val wrap = FrameLayout(this); wrap.addView(col, FrameLayout.LayoutParams(limit, -2, Gravity.CENTER_HORIZONTAL))
        body(col)
        outer.addView(scroll(wrap), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(outer)
    }
    private fun group(col: LinearLayout, title: String): LinearLayout {
        col.addView(section(title))
        val g = vertical(); g.background = round(p.surface, 14f, if (p.outlined) dp(1.5f) else 0, p.outline); g.setPadding(0, dp(2), 0, dp(2))
        col.addView(g, LinearLayout.LayoutParams(-1, -2))
        return g
    }
    /** One settings row: icon dot, title, value under it, chevron or switch on the right. */
    private fun row(g: LinearLayout, title: String, value: String?, tag: String, color: Int, toggle: Boolean? = null, click: () -> Unit) {
        val r = horizontal(); r.setPadding(dp(14), dp(12), dp(14), dp(12))
        r.addView(View(this).apply { background = round(color, 8f) }, LinearLayout.LayoutParams(dp(26), dp(26)))
        val texts = vertical(); texts.setPadding(dp(12), 0, dp(8), 0)
        texts.addView(tv(title, 15f, p.text))
        if (!value.isNullOrEmpty()) texts.addView(tv(value, 12f, p.muted).apply { setPadding(0, dp(2), 0, 0) })
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (toggle != null) {
            val track = FrameLayout(this); track.background = round(if (toggle) 0xFF22C55E.toInt() else p.surface2, 12f)
            track.addView(View(this).apply { background = round(Color.WHITE, 9f) }, FrameLayout.LayoutParams(dp(18), dp(18), (if (toggle) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL).apply { setMargins(dp(3), 0, dp(3), 0) })
            r.addView(track, LinearLayout.LayoutParams(dp(44), dp(24)))
        } else r.addView(tv("›", 20f, p.muted))
        control(r, tag, Color.TRANSPARENT, 12f, false, click)
        if (g.childCount > 0) g.addView(View(this).apply { setBackgroundColor(p.outline) }, LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(52) })
        g.addView(r)
    }
    private fun themeName(): String = library.theme().let { if (it == "GBA clásico" || it == "Mosaico" || it == "Consola completa") it else "Moderno" }
    private fun themeKey(): String = when (themeName()) { "GBA clásico" -> "gba"; "Mosaico" -> "tiles"; "Consola completa" -> "console"; else -> "midnight" }

    private fun settingsPage() = page(T.t("Ajustes")) { col ->
        val a = group(col, T.t("Apariencia"))
        row(a, T.t("Tema"), T.t(themeName()), "s_theme", 0xFF7A4DFF.toInt()) {
            val themes = arrayOf("Consola completa", "Moderno", "Mosaico", "GBA clásico")
            AlertDialog.Builder(this).setTitle(T.t("Tema visual")).setSingleChoiceItems(themes.map { T.t(it) }.toTypedArray(), themes.indexOf(themeName())) { d, w -> library.theme(themes[w]); d.dismiss(); render() }.show()
        }
        val dark = library.dark(themeKey())
        row(a, T.t("Modo oscuro"), null, "s_dark", 0xFF4DB36B.toInt(), dark) { library.dark(themeKey(), !dark); render() }
        val ls = logoStyle()
        row(a, T.t("Logotipos"), if (ls == "color") T.t("Logos a color") else if (ls == "mono") T.t("Logos monocromo") else T.t("Texto"), "s_logos", 0xFFFF9A2E.toInt()) {
            val n = if (ls == "color") "mono" else if (ls == "mono") "text" else "color"; getSharedPreferences("ui", MODE_PRIVATE).edit().putString("logo_style", n).apply(); render()
        }
        row(a, "Idioma / Language", if (T.english()) "English" else "Español", "s_lang", 0xFF2AA8FF.toInt()) {
            AlertDialog.Builder(this).setTitle("Idioma / Language").setSingleChoiceItems(arrayOf("Español", "English"), if (T.english()) 1 else 0) { d, w -> T.set(this, if (w == 1) "en" else "es"); d.dismiss(); render() }.show()
        }
        row(a, T.t("Pantalla de inicio"), T.t("Qué secciones se muestran"), "s_home", 0xFF14C9AA.toInt()) { go("set_home") }

        val l = group(col, T.t("Biblioteca"))
        row(l, "TirsoDrive", if (drive.stage == TirsoDrive.Stage.READY) T.t("Conectado · gestionar grupos") else T.t("Vincular colección"), "s_td", 0xFF14C9AA.toInt()) { page = "welcome"; render() }
        val shown = Console.all().count { library.visible(it) }
        row(l, T.t("Consolas"), "$shown" + T.t(" de ") + Console.all().size + T.t(" visibles"), "s_consoles", 0xFF2F6DF6.toInt()) { go("set_consoles") }
        row(l, T.t("Descargar imágenes"), T.t("Logos y carátulas"), "s_art", 0xFFFF9A2E.toInt()) { openEmbedded("artwork") }
        row(l, T.t("Limpiar grupos vacíos"), T.t("Borra grupos TD duplicados sin archivos"), "s_clean", 0xFFFF4D73.toInt()) { cleanupGroups() }
        row(l, T.t("Colecciones"), library.collections().size.toString() + T.t(" listas"), "s_cols", 0xFF7A4DFF.toInt()) { GameMenu.collections(this, menuHost) }
        row(l, T.t("Estadísticas de juego"), T.t("Horas por consola y más jugados"), "s_stats", 0xFF2AA8FF.toInt()) { GameMenu.stats(this) }
        row(l, T.t("Juegos sin información"), T.t("Busca a mano la información o portada que falta"), "s_missing", 0xFFF0B455.toInt()) { GameMenu.missing(this, menuHost) }
        row(l, T.t("Juegos ocultos"), library.hidden().size.toString() + T.t(" ocultos"), "s_hidden", 0xFF8C93A8.toInt()) { GameMenu.hidden(this, menuHost) }

        val gm = group(col, T.t("Juegos"))
        row(gm, T.t("Resolución"), T.t("Por consola"), "s_res", 0xFF2F6DF6.toInt()) { go("set_resolution") }
        row(gm, T.t("Archivos BIOS"), if (Bios.ps2(this) != null) T.t("BIOS de PS2 lista") else T.t("Añade tu BIOS de PS2"), "s_bios", 0xFF4DB36B.toInt()) { biosMenu() }
        row(gm, T.t("Controlador de GPU"), PhoneGames.driverName(this).ifEmpty { T.t("El del teléfono") }, "s_gpu", 0xFFA06BFF.toInt()) { gpuMenu() }
        row(gm, T.t("Mandos"), T.t("Perfiles independientes"), "s_pads", 0xFFFF4D73.toInt()) { openEmbedded("settings", "controllers") }
        row(gm, T.t("Copias de partidas"), T.t("Guardadas en tu canal de Telegram"), "s_backups", 0xFF2DE2C8.toInt()) { openEmbedded("backups") }

        val st = group(col, T.t("Teléfono"))
        row(st, T.t("Teléfono y almacenamiento"), T.t("Carpetas, juegos guardados, Wi-Fi"), "s_storage", 0xFF38BDF8.toInt()) { go("set_storage") }
        row(st, T.t("Cola de descargas"), DownloadQueue.status().ifEmpty { T.t("Descarga juegos para jugar sin conexión") }, "s_queue", 0xFF22C55E.toInt()) { GameMenu.queue(this, drive, menuHost) }
        row(st, T.t("Espacio usado"), T.t("Descargas, portadas, partidas · liberar espacio"), "s_space", 0xFF22C55E.toInt()) { GameMenu.storage(this, drive, menuHost) }

        val ap = group(col, T.t("Aplicación"))
        row(ap, T.t("Actualizaciones"), T.t("Buscar e instalar la última versión"), "s_update", 0xFF7A4DFF.toInt()) { checkUpdates() }
        row(ap, T.t("Informe de problemas"), T.t("Qué registró Android si un juego falla"), "s_report", 0xFFFF9A2E.toInt()) { openEmbedded("settings", "report") }
        row(ap, T.t("Licencias y créditos"), T.t("Motores, fuentes y bases de datos"), "s_lic", 0xFF4DB36B.toInt()) { openEmbedded("settings", "licenses") }
        row(ap, "RetroStream", BuildConfig.VERSION_NAME + " · build " + BuildConfig.VERSION_CODE, "s_about", 0xFF2AA8FF.toInt()) { }
    }

    private fun consolesSettingsPage() = page(T.t("Consolas")) { col ->
        val g = group(col, T.t("Consolas visibles"))
        for (c in Console.all()) {
            val on = library.visible(c); val n = library.count(c)
            row(g, c.title, if (n == 1) "1 " + T.t("juego") else "$n " + T.t("juegos"), "vis_${c.name}", if (on) c.color else p.surface2, on) { library.visible(c, !on); render() }
        }
        col.addView(button(T.t("Mostrar todas"), "vis_all") { Console.all().forEach { library.visible(it, true) }; render() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
    }

    private fun homeSettingsPage() = page(T.t("Pantalla de inicio")) { col ->
        val c = group(col, T.t("Consolas"))
        val grid = HomeLayout.consoleGrid(this)
        row(c, T.t("Consolas en cuadrícula"), if (grid) T.t("Las primeras 9 y «Ver todo»") else T.t("Una fila que se desliza (como antes)"), "home_grid", 0xFF2F6DF6.toInt(), grid) { HomeLayout.consoleGrid(this, !grid); render() }
        val s = group(col, T.t("Secciones"))
        val names = listOf("continue" to T.t("Continuar"), "recent" to T.t("Jugados recientemente"), "new" to T.t("Nuevos en tus canales"), "consoles" to T.t("Consolas"), "friends" to T.t("Amigos"), "suggested" to T.t("Juegos sugeridos"))
        for ((key, name) in names) {
            val on = HomeLayout.shown(this, key)
            row(s, name, if (key == "suggested") T.t("Solo con el teléfono en vertical") else null, "home_$key", 0xFF14C9AA.toInt(), on) { HomeLayout.shown(this, key, !on); render() }
        }
    }

    private fun resolutionPage() = page(T.t("Resolución")) { col ->
        col.addView(tv(T.t("Más alta se ve mejor pero exige más al teléfono."), 13f, p.muted).apply { setPadding(dp(4), dp(4), 0, 0) })
        val g = group(col, T.t("Por consola"))
        val labels = mapOf("low" to T.t("Baja"), "medium" to T.t("Media"), "high" to T.t("Alta"))
        for (c in Console.all()) {
            if (!library.visible(c) || c == Console.MAME) continue
            val v = Presets.resolution(this, c)
            row(g, c.title, labels[v] ?: v, "res_${c.name}", c.color) {
                val keys = arrayOf("low", "medium", "high")
                AlertDialog.Builder(this).setTitle(c.title).setSingleChoiceItems(keys.map { labels[it] }.toTypedArray(), keys.indexOf(v)) { d, w -> Presets.setResolution(this, c, keys[w]); d.dismiss(); render() }.show()
            }
        }
    }

    private fun storagePage() = page(T.t("Teléfono y almacenamiento")) { col ->
        val f = group(col, T.t("Carpetas del teléfono"))
        if (!PhoneGames.canReadFolders()) row(f, T.t("Permitir acceso a los archivos"), T.t("Necesario para encontrar juegos en tus carpetas"), "allow_files", 0xFFFF9A2E.toInt()) {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                .onFailure { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
        val scanOn = PhoneGames.scanOn(this)
        row(f, T.t("Buscar juegos en carpetas"), T.t("Al abrir la app y con «Buscar ahora»"), "scan_on", 0xFF38BDF8.toInt(), scanOn) { PhoneGames.scanOn(this, !scanOn); render() }
        for (folder in PhoneGames.folders(this)) row(f, folder.substringAfterLast('/'), folder, "folder_${folder.hashCode()}", 0xFF8C93A8.toInt()) {
            AlertDialog.Builder(this).setTitle(T.t("Quitar carpeta")).setMessage(folder).setPositiveButton(T.t("Quitar")) { _, _ -> PhoneGames.removeFolder(this, folder); scanFolders(); render() }.setNegativeButton(T.t("Cancelar"), null).show()
        }
        row(f, T.t("Añadir carpeta"), T.t("Elige una carpeta con juegos (se buscan también sus subcarpetas)"), "add_folder", 0xFF22C55E.toInt()) {
            runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 33) }.onFailure { toast(T.t("No hay un selector de archivos en este teléfono.")) }
        }
        val local = library.games.count { it.id.startsWith("local:") }
        row(f, if (scanning) T.t("Buscando…") else T.t("Buscar ahora"), "$local" + T.t(" juegos del teléfono en la biblioteca"), "scan_now", 0xFF2F6DF6.toInt()) {
            if (!PhoneGames.canReadFolders()) toast(T.t("Primero permite el acceso a los archivos.")) else { scanFolders(true); render() }
        }
        for (u in unsorted) row(f, File(u).name, T.t("Sin clasificar · toca para elegir la consola"), "uns_${u.hashCode()}", 0xFFF0B455.toInt()) {
            val all = Console.all()
            AlertDialog.Builder(this).setTitle(File(u).name).setItems(all.map { it.title }.toTypedArray()) { _, w -> PhoneGames.choose(this, u, all[w]); scanFolders(true) }.show()
        }
        val t = group(col, T.t("Juegos de Telegram"))
        val keep = PhoneGames.keep(this); val wifi = PhoneGames.wifiOnly(this)
        row(t, T.t("Guardar juegos descargados"), T.t("Se quedan en el teléfono para jugar sin volver a descargarlos"), "keep", 0xFF22C55E.toInt(), keep) { PhoneGames.keep(this, !keep); render() }
        row(t, T.t("Descargar solo con Wi-Fi"), T.t("No usa datos móviles para descargar juegos"), "wifi", 0xFF2AA8FF.toInt(), wifi) { PhoneGames.wifiOnly(this, !wifi); render() }
        val kept = PhoneGames.kept(this)
        if (kept.isNotEmpty()) {
            val total = kept.sumOf { it[4] as Long }
            val k = group(col, T.t("Juegos guardados") + " · " + fmt(total))
            for (e in kept) {
                val id = e[0] as String; val g = library.games.firstOrNull { it.id == id }
                val name = g?.let { it.title + " · " + it.console.name } ?: File(e[3] as String).name
                row(k, name, fmt(e[4] as Long) + T.t(" · toca para borrar"), "kept_${id.hashCode()}", g?.console?.color ?: 0xFF8C93A8.toInt()) {
                    AlertDialog.Builder(this).setTitle(name).setMessage(T.t("Se borra del teléfono; sigue en Telegram.")).setPositiveButton(T.t("Borrar")) { _, _ -> deleteKept(e); render() }.setNegativeButton(T.t("Cancelar"), null).show()
                }
            }
            row(k, T.t("Borrar todos los juegos guardados"), null, "kept_all", 0xFFFF4D73.toInt()) {
                AlertDialog.Builder(this).setTitle(T.t("Borrar juegos guardados")).setMessage(T.t("Se borran del teléfono; siguen en Telegram."))
                    .setPositiveButton(T.t("Borrar")) { _, _ -> PhoneGames.kept(this).forEach { deleteKept(it) }; render() }.setNegativeButton(T.t("Cancelar"), null).show()
            }
        }
        val d = group(col, T.t("Pantalla y rendimiento"))
        val lock = PhoneGames.lockLandscape(this); val max = PhoneGames.perfMax(this)
        row(d, T.t("Bloquear en horizontal"), T.t("La app no gira al inclinar el teléfono"), "lock", 0xFF7A4DFF.toInt(), lock) { PhoneGames.lockLandscape(this, !lock); requestedOrientation = if (!lock) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER; render() }
        row(d, T.t("Rendimiento máximo"), if (max) T.t("Toda la potencia del teléfono") else T.t("Equilibrado: más frío y estable en sesiones largas"), "perf", 0xFFFF9A2E.toInt(), max) { PhoneGames.perfMax(this, !max); render() }
    }
    private fun deleteKept(e: Array<Any>) {
        PhoneGames.forget(this, e[0] as String); drive.releaseRom(e[1] as Int, e[2] as Long); File(e[3] as String).delete()
    }
    private var unsorted: List<String> = emptyList()

    private fun biosMenu() {
        val ps2 = Bios.ps2(this)
        val items = arrayOf(T.t("BIOS de PS2: ") + (ps2?.name ?: T.t("no añadida")), T.t("Quitar la BIOS de PS2"), T.t("Otros archivos (Famicom Disk System, MAME)"))
        AlertDialog.Builder(this).setTitle(T.t("Archivos BIOS")).setItems(items) { _, w ->
            when (w) {
                0 -> runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 32) }
                1 -> { Bios.removePs2(this); render() }
                else -> openEmbedded("settings", "system_files")
            }
        }.setNegativeButton(T.t("Cancelar"), null).show()
    }
    private fun gpuMenu() {
        AlertDialog.Builder(this).setTitle(T.t("Controlador de GPU"))
            .setMessage(T.t("Para PS2, GameCube y Wii (Vulkan). Elige un ZIP de controlador como Turnip de Mr. Purple."))
            .setPositiveButton(T.t("Elegir controlador (.zip)")) { _, _ -> runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 34) } }
            .setNeutralButton(T.t("Usar el del teléfono")) { _, _ -> PhoneGames.removeDriver(this); render() }
            .setNegativeButton(T.t("Cancelar"), null).show()
    }
    private fun cleanupGroups() {
        if (!drive.ready()) { toast(T.t("Conecta TirsoDrive primero.")); return }
        Toast.makeText(this, T.t("Buscando grupos vacíos…"), Toast.LENGTH_SHORT).show()
        drive.emptyGroups { list, err ->
            if (err != null) { toast(err); return@emptyGroups }
            if (list.isNullOrEmpty()) { toast(T.t("No hay grupos vacíos que borrar.")); return@emptyGroups }
            AlertDialog.Builder(this).setTitle(T.t("Borrar ") + list.size + T.t(" grupos vacíos"))
                .setMessage(T.t("Estos grupos los creaste tú y no tienen juegos, partidas ni capturas. Se borrarán de Telegram:\n\n") + list.joinToString("\n") { "• " + it.title })
                .setPositiveButton(T.t("Borrar")) { _, _ -> drive.deleteGroups(list) { n, e2 -> toast(e2 ?: (n.toString() + T.t(" grupos borrados."))) } }
                .setNegativeButton(T.t("Cancelar"), null).show()
        }
    }
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        val uri = data?.data
        if (result != RESULT_OK || uri == null) return
        when (request) {
            32 -> Thread { val err = runCatching { Bios.addPs2(this, uri) }.getOrElse { it.message }; runOnUiThread { toast(err ?: T.t("BIOS de PS2 añadida")); render() } }.start()
            33 -> { val path = PhoneGames.pathOf(uri); if (path == null) toast(T.t("No se pudo usar esa carpeta.")) else { PhoneGames.addFolder(this, path); if (PhoneGames.canReadFolders()) scanFolders(true); render() } }
            34 -> {
                var name = "driver"
                runCatching { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) } }
                Thread { val err = runCatching { PhoneGames.addDriver(this, uri, name) }.getOrElse { it.message }; runOnUiThread { toast(err ?: T.t("Controlador de GPU instalado")); render() } }.start()
            }
        }
    }

    // ================================================================== friends (phone)
    private fun avatar(name: String, status: Int, size: Int): View {
        val box = FrameLayout(this)
        val base = (0xFF000000.toInt()) or (Math.abs(name.hashCode()) and 0x7F7F7F) or 0x404040
        val av = tv(name.ifEmpty { "?" }.take(1).uppercase(), size * 0.42f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(base); setStroke(dp(2), status) } }
        box.addView(av, FrameLayout.LayoutParams(dp(size), dp(size)))
        box.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(status); setStroke(dp(2), p.bg) } },
            FrameLayout.LayoutParams(dp(size / 3), dp(size / 3), Gravity.BOTTOM or Gravity.END))
        return box
    }
    private fun friendsPage() = page(T.t("Amigos")) { col ->
        drive.loadFriends()
        if (!drive.ready()) { col.addView(tv(T.t("Conecta TirsoDrive para ver a tus amigos."), 14f, p.muted)); return@page }
        if (drive.friendsChat() == 0L) {
            val hero = vertical(); hero.gravity = Gravity.CENTER_HORIZONTAL; hero.setPadding(dp(20), dp(28), dp(20), dp(20))
            hero.background = round(p.surface, 20f, if (p.outlined) dp(1.5f) else 0, p.outline)
            hero.addView(IconView(this, "users", T.t("Amigos"), p.text), LinearLayout.LayoutParams(dp(64), dp(64)))
            hero.addView(tv(T.t("Juega con tus amigos"), 22f, p.text, bold = true).apply { setPadding(0, dp(12), 0, 0) })
            hero.addView(tv(T.t("Mira quién está conectado y a qué juega. Todos entran al mismo grupo privado de Telegram."), 14f, p.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(16)) })
            hero.addView(button(T.t("Pegar enlace de invitación"), "f_join", true) { joinDialog() }, LinearLayout.LayoutParams(-1, -2))
            hero.addView(button(T.t("Crear grupo de amigos"), "f_create") {
                Toast.makeText(this, T.t("Creando grupo…"), Toast.LENGTH_SHORT).show()
                drive.friendsCreate { _, error -> if (error != null) toast(error) else { toast(T.t("Grupo creado. Comparte el enlace con tus amigos.")); render() } }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            col.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            return@page
        }
        val all = drive.friends.values.toList()
        val playing = all.filter { it.playing() }.sortedBy { it.name.lowercase() }
        val online = all.filter { it.online() && !it.playing() }.sortedBy { it.name.lowercase() }
        val offline = all.filter { !it.online() }.sortedBy { it.name.lowercase() }
        val pills = horizontal(); pills.setPadding(0, dp(8), 0, 0)
        fun pill(t: String, fg: Int, bg: Int) = tv(t, 12f, fg, bold = true).apply { setPadding(dp(10), dp(5), dp(10), dp(5)); background = round(bg, 12f) }
        pills.addView(pill("${playing.size} " + T.t("jugando"), 0xFF06210F.toInt(), 0xFF22C55E.toInt()))
        pills.addView(pill("${online.size} " + T.t("en el menú"), Color.WHITE, 0xFF3B82F6.toInt()), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        pills.addView(pill("${offline.size} " + T.t("desconectados"), p.text, p.surface2), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pills) })
        if (all.size <= 1) col.addView(tv(T.t("Aún no hay amigos aquí. Comparte el enlace de invitación."), 14f, p.muted).apply { setPadding(dp(4), dp(14), 0, 0) })
        if (playing.isNotEmpty()) {
            col.addView(section(T.t("Jugando ahora")))
            for (f in playing) {
                val c = runCatching { Console.valueOf(f.console) }.getOrNull()
                val g = library.games.firstOrNull { it.title.equals(f.game, true) }
                val card = horizontal(); card.setPadding(dp(12), dp(12), dp(14), dp(12))
                val ratio = c?.let { coverRatio(it) } ?: 0.72f
                val art = FrameLayout(this); art.background = round(c?.color ?: 0xFF475569.toInt(), 8f); art.clipToOutline = true
                val ph = tv(c?.name ?: f.console, 11f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
                art.addView(ph, FrameLayout.LayoutParams(-1, -1))
                if (g != null) { val iv = CoverImage(art, ph); art.addView(iv, FrameLayout.LayoutParams(-1, -1)); artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles") }
                card.addView(art, LinearLayout.LayoutParams((dp(84) * ratio).toInt(), dp(84)))
                val tx = vertical(); tx.setPadding(dp(12), 0, 0, 0)
                val who = horizontal(); who.addView(avatar(f.name, 0xFF22C55E.toInt(), 26))
                who.addView(tv(f.name.ifEmpty { "…" } + if (f.userId == drive.accountId) T.t("  (tú)") else "", 14f, p.text, bold = true).apply { setPadding(dp(8), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                tx.addView(who)
                tx.addView(tv(f.game, 16f, p.text, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0) })
                val mins = maxOf(0L, (System.currentTimeMillis() - f.since) / 60000)
                tx.addView(tv((c?.title ?: f.console) + " · " + (if (mins < 60) "$mins min" else "${mins / 60} h ${mins % 60} min"), 12f, 0xFF22C55E.toInt()).apply { setPadding(0, dp(3), 0, 0) })
                card.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
                control(card, "fr_${f.userId}", p.surface, 16f) { if (g != null) { selected = g; console = g.console; go("detail") } }
                col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        val rest = online + offline
        if (rest.isNotEmpty()) {
            col.addView(section(T.t("Amigos")))
            val cols = if (landscape) 5 else 3
            var row: LinearLayout? = null
            rest.forEachIndexed { i, f ->
                if (i % cols == 0) { row = horizontal(); col.addView(row, LinearLayout.LayoutParams(-1, -2)) }
                val on = f.online()
                val t = vertical(); t.gravity = Gravity.CENTER_HORIZONTAL; t.setPadding(dp(6), dp(12), dp(6), dp(10))
                t.addView(avatar(f.name, if (on) 0xFF3B82F6.toInt() else 0xFF6B7280.toInt(), 52))
                t.addView(tv(f.name.ifEmpty { "…" }, 13f, if (on) p.text else p.muted, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
                t.addView(tv(if (on) T.t("En el menú") else T.t("Desconectado"), 11f, if (on) 0xFF3B82F6.toInt() else p.muted).apply { gravity = Gravity.CENTER })
                control(t, "fr_${f.userId}", p.surface, 16f) { }
                row!!.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            val left = rest.size % cols
            if (left != 0) repeat(cols - left) { row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }) }
        }
        val a = group(col, T.t("Opciones"))
        val share = drive.shareActivity()
        row(a, T.t("Mostrar mi actividad"), T.t("Tus amigos ven si estás conectado y a qué juegas"), "f_share", 0xFF22C55E.toInt(), share) { drive.shareActivity(!share); render() }
        if (drive.friendsLink().isNotEmpty()) row(a, T.t("Copiar enlace de invitación"), drive.friendsLink(), "f_copy", 0xFF2AA8FF.toInt()) {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("RetroStream", drive.friendsLink()))
            toast(T.t("Enlace copiado: ") + drive.friendsLink())
        }
        row(a, T.t("Actualizar"), null, "f_refresh", 0xFF7A4DFF.toInt()) { drive.loadFriends(); Toast.makeText(this, T.t("Actualizando…"), Toast.LENGTH_SHORT).show() }
        row(a, T.t("Salir del grupo"), null, "f_leave", 0xFFFF4D73.toInt()) {
            AlertDialog.Builder(this).setTitle(T.t("Salir de amigos")).setMessage(T.t("Dejarás de ver y de mostrar actividad. Puedes volver a unirte con el enlace."))
                .setPositiveButton(T.t("Salir")) { _, _ -> drive.friendsLeave(); render() }.setNegativeButton(T.t("Cancelar"), null).show()
        }
    }
    private fun joinDialog() {
        val input = field("https://t.me/+…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        AlertDialog.Builder(this).setTitle(T.t("Enlace de invitación")).setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton(T.t("Unirme")) { _, _ -> drive.friendsJoin(input.text.toString()) { _, error -> if (error != null) toast(error) else render() } }
            .setNegativeButton(T.t("Cancelar"), null).show()
    }

    // ================================================================== played history (phone)
    private fun historyPage() = page(T.t("Historial de juego")) { col ->
        val plays = library.plays()
        if (plays.isEmpty()) { col.addView(tv(T.t("Aún no has jugado nada."), 14f, p.muted).apply { setPadding(dp(4), dp(8), 0, 0) }); return@page }
        var day = ""
        var g: LinearLayout? = null
        for (r in plays.take(200)) {
            val game = r[0] as Game; val t = r[1] as Long
            val d = dayLabel(t)
            if (d != day) { day = d; col.addView(section(d)); g = vertical().apply { background = round(p.surface, 14f) }; col.addView(g) }
            val row = horizontal(); row.setPadding(dp(10), dp(8), dp(14), dp(8))
            val art = FrameLayout(this); art.background = round(0xFFF2EEE4.toInt(), 6f); art.clipToOutline = true
            val iv = CoverImage(art, null); art.addView(iv, FrameLayout.LayoutParams(-1, -1)); artwork.loadFirst(game, iv, "Named_Boxarts", "Named_Titles")
            row.addView(art, LinearLayout.LayoutParams((dp(56) * coverRatio(game.console)).toInt(), dp(56)))
            val tx = vertical(); tx.setPadding(dp(12), 0, 0, 0)
            tx.addView(tv(game.title, 15f, p.text, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val pt = PlayTime.label(this, game.id)
            tx.addView(tv(game.console.title + " · " + android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(t)) + if (pt.isNotEmpty()) " · $pt" else "", 12f, p.muted))
            row.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(tv("▶", 16f, p.primary))
            control(row, "hist_${game.id}_$t", Color.TRANSPARENT, 12f, false) { play(game) }
            g!!.addView(row)
        }
    }
    private fun dayLabel(t: Long): String {
        val cal = java.util.Calendar.getInstance(); val today = cal.get(java.util.Calendar.DAY_OF_YEAR) + cal.get(java.util.Calendar.YEAR) * 400
        cal.timeInMillis = t; val d = cal.get(java.util.Calendar.DAY_OF_YEAR) + cal.get(java.util.Calendar.YEAR) * 400
        return when (today - d) { 0 -> T.t("Hoy"); 1 -> T.t("Ayer"); else -> android.text.format.DateFormat.getMediumDateFormat(this).format(java.util.Date(t)) }
    }

    // ------------------------------------------------------------------ search, folders, keys
    private fun search() {
        val input = field(T.t("Nombre del juego"), InputType.TYPE_CLASS_TEXT); input.setText(query)
        AlertDialog.Builder(this).setTitle(T.t("Buscar juego")).setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton(T.t("Buscar")) { _, _ -> query = input.text.toString().trim(); if (page != "library") { console = null; source = "all" }; go("library") }
            .setNegativeButton(T.t("Cancelar"), null).show()
    }
    private var scanning = false
    private fun scanFolders(tell: Boolean = false) {
        if (scanning) return; scanning = true
        Thread {
            val r = runCatching { PhoneGames.scan(this) }.getOrNull()
            runOnUiThread {
                scanning = false
                if (r != null) {
                    library.replaceLocal(r.games); library = Library(this); unsorted = r.unsorted
                    if (tell) toast(r.games.size.toString() + T.t(" juegos encontrados en tu teléfono") + if (unsorted.isEmpty()) "" else " · " + unsorted.size + T.t(" sin clasificar"))
                    if (page == "home" || page == "library" || page == "set_storage") render()
                }
            }
        }.start()
    }

    override fun onBackPressed() {
        when (page) {
            "welcome" -> { if (drive.stage != TirsoDrive.Stage.READY) drive.skip(); go("home") }
            "loading" -> cancelLoading()
            "detail" -> go("library")
            "consoles", "settings", "friends", "history", "remote", "media" -> go("home")
            "set_consoles", "set_home", "set_storage", "set_resolution" -> go("settings")
            "library" -> { query = ""; source = "all"; console = null; go("home") }
            else -> AlertDialog.Builder(this).setTitle(T.t("Salir de RetroStream"))
                .setPositiveButton(T.t("Salir")) { _, _ -> finish() }.setNegativeButton(T.t("Cancelar"), null).show()
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_B -> { onBackPressed(); return true }
            KeyEvent.KEYCODE_BUTTON_A -> { if (event.repeatCount == 0) currentFocus?.performClick(); return true }
            KeyEvent.KEYCODE_BUTTON_Y -> { if (page == "home" || page == "library") { search(); return true } }
            KeyEvent.KEYCODE_BUTTON_X -> {
                val g = selected
                if (page == "library" && g != null) { library.toggleFavorite(g); lastFocus = "game_${g.id}"; render(); return true }
                if (page == "home") { console = null; source = "fav"; go("library"); return true }
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> if (page == "home") { shiftConsole(-1); return true }
            KeyEvent.KEYCODE_BUTTON_R1 -> if (page == "home") { shiftConsole(1); return true }
            KeyEvent.KEYCODE_BUTTON_START -> { go("settings"); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_BUTTON_B) return true
        return super.onKeyUp(keyCode, event)
    }
}
