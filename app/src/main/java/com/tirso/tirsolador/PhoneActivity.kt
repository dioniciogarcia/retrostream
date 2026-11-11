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
    }
    override fun onDestroy() {
        drive.detach(driveListener); artwork.close()
        if (isFinishing) { drive.releaseAll(); TemporaryRoms.purge(applicationContext) }
        super.onDestroy()
    }
    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); render() }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); playFromShortcut() }

    private fun onDrive(catalog: Boolean) {
        if (catalog) library = Library(this)
        if (!drive.skipped() && (drive.stage == TirsoDrive.Stage.QR || drive.stage == TirsoDrive.Stage.PHONE ||
                drive.stage == TirsoDrive.Stage.CODE || drive.stage == TirsoDrive.Stage.PASSWORD) && page != "welcome") page = "welcome"
        when (page) {
            "welcome" -> render()
            "loading" -> updateLoading()
            "home", "library" -> if (catalog) render() else updateBadge()
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
            "loading" -> loadingPage()
            else -> home()
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
    private fun go(target: String) { page = target; lastFocus = ""; render() }

    /** Header: title with search beside it on the left (clear of the camera); favorites, friends, settings on the right. */
    private fun header(title: String, back: Boolean, history: Boolean = false): View {
        val row = horizontal(); row.setPadding(dp(14), dp(10), dp(8), dp(6))
        if (back) row.addView(icon("back", T.t("Volver"), "back") { onBackPressed() })
        row.addView(tv(title, if (style == "tiles") 22f else 20f, p.text, bold = true).apply {
            setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setPadding(dp(6), 0, dp(4), 0)
            maxWidth = resources.displayMetrics.widthPixels - dp(if (back) 260 else 220) },
            LinearLayout.LayoutParams(-2, -2))
        row.addView(icon("search", T.t("Buscar juegos"), "h_search") { search() })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(icon("star", T.t("Favoritos"), "h_fav") { console = null; source = "fav"; go("library") })
        row.addView(icon("users", T.t("Amigos"), "h_friends", if (drive.friendsChat() != 0L) drive.friendsOnline() else 0) { openEmbedded("friends") })
        if (history) row.addView(icon("history", T.t("Historial de juego"), "h_history") { openEmbedded("history") })
        row.addView(icon("settings", T.t("Ajustes"), "h_settings") { openEmbedded("settings") })
        return row
    }
    private fun updateBadge() {
        (root.findViewWithTag<View>("h_friends") as? IconView)?.badge(if (drive.friendsChat() != 0L) drive.friendsOnline() else 0)
    }
    private fun openEmbedded(target: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("embed_page", target))
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
            row.addView(tv(if (s.chatId == 0L) s.issue else s.title, 12f, if (s.chatId == 0L) p.muted else p.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1.2f))
            control(row, "src_${c.name}", p.surface) { chooseSource(c) }
            col.addView(row, LinearLayout.LayoutParams(minOf(dp(520), resources.displayMetrics.widthPixels - dp(32)), -2).apply { topMargin = dp(6) })
        }
        col.addView(button(T.t("Ir a mi biblioteca"), "w_done", true) { drive.finishOnboarding(); library = Library(this); go("home") },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
    }
    private fun chooseSource(c: Console) {
        if (drive.syncing || drive.discovering) return
        val choices = drive.choices(c)
        val labels = arrayOf(T.t("Sin grupo asignado")) + choices.map { it.title }
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
        for (r in library.plays()) { val g = r[0] as Game; if (seen.add(g.id)) out += g to (r[1] as Long); if (out.size >= 12) break }
        return out
    }

    private fun home() {
        val last = recent().firstOrNull()?.first
        if (landscape) {
            val row = horizontal(); row.gravity = Gravity.TOP; row.setPadding(dp(12), dp(10), dp(12), dp(8))
            row.addView(continueCard(last, true), LinearLayout.LayoutParams(0, -1, 0.9f))
            val right = vertical(); right.setPadding(dp(12), 0, 0, 0)
            val top = horizontal()
            top.addView(tv(T.t("Inicio"), 18f, p.text, bold = true).apply { setPadding(dp(4), 0, dp(4), 0) })
            top.addView(icon("search", T.t("Buscar juegos"), "h_search") { search() })
            top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(icon("star", T.t("Favoritos"), "h_fav") { console = null; source = "fav"; go("library") })
            top.addView(icon("users", T.t("Amigos"), "h_friends", if (drive.friendsChat() != 0L) drive.friendsOnline() else 0) { openEmbedded("friends") })
            top.addView(icon("settings", T.t("Ajustes"), "h_settings") { openEmbedded("settings") })
            right.addView(top)
            right.addView(section(T.t("Consolas")))
            right.addView(consoleRow(dp(104), dp(70)))
            recentBlock(right, dp(78), dp(104))
            row.addView(scroll(right), LinearLayout.LayoutParams(0, -1, 1.25f))
            val outer = vertical(); outer.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
            if (hasController()) outer.addView(hints("A" to T.t("Abrir"), "B" to T.t("Volver"), "Y" to T.t("Buscar"), "X" to T.t("Favoritos")))
            root.addView(outer)
            return
        }
        val col = vertical(); col.setPadding(0, 0, 0, dp(18))
        col.addView(header(T.t("Inicio"), false))
        col.addView(continueCard(last, false), LinearLayout.LayoutParams(-1, dp(220)).apply { setMargins(dp(14), dp(4), dp(14), 0) })
        recentBlock(col, dp(104), dp(140))
        col.addView(section(T.t("Consolas")).apply { setPadding(dp(18), dp(18), 0, dp(6)) })
        col.addView(consoleRow(dp(120), dp(84)))
        friendsBlock(col)
        suggestedBlock(col)
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
            val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.CENTER_CROP; cover.addView(iv, FrameLayout.LayoutParams(-1, -1))
            artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles")
            cover.rotation = 6f
            card.addView(cover, FrameLayout.LayoutParams(dp(if (tall) 120 else 118), dp(if (tall) 160 else 158), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(-8) })
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
            card.addView(resume, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(16), 0, 0, dp(16)) })
        }
        return card
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
        head.addView(tv(T.t("Ver todo"), 12f, p.primary).also { control(it, "recent_all", Color.TRANSPARENT, 8f, false) { openEmbedded("history") } })
        parent.addView(head)
        val row = horizontal(); row.setPadding(dp(14), 0, dp(14), 0); row.gravity = Gravity.TOP
        for ((g, _) in list.drop(1)) {
            val cell = vertical()
            val art = FrameLayout(this); art.background = round(0xFFF2EEE4.toInt(), 10f); art.clipToOutline = true
            art.addView(tv(g.title, 11f, 0xFF2B2B2B.toInt(), bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }, FrameLayout.LayoutParams(-1, -1))
            val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.CENTER_CROP; art.addView(iv, FrameLayout.LayoutParams(-1, -1))
            artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles")
            cell.addView(art, LinearLayout.LayoutParams(w, h))
            cell.addView(tv(g.title, 11f, p.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2), dp(5), dp(2), 0) }, LinearLayout.LayoutParams(w, -2))
            val pt = PlayTime.label(this, g.id)
            if (pt.isNotEmpty()) cell.addView(tv(pt, 10f, p.muted).apply { setPadding(dp(2), dp(1), dp(2), 0) })
            control(cell, "recent_${g.id}", Color.TRANSPARENT, 12f, false) { play(g) }
            cell.setOnLongClickListener { selected = g; gameMore(g); true }
            row.addView(cell, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        }
        parent.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    /** Upright only: a few games to try, picked at random from the library (new picks each day, unplayed first). */
    private fun suggestedBlock(parent: LinearLayout) {
        val played = recent().map { it.first.id }.toSet()
        val pool = library.games.filter { library.visible(it.console) && it.id !in played }
        if (pool.isEmpty()) return
        val day = System.currentTimeMillis() / 86_400_000L
        val picks = pool.shuffled(java.util.Random(day)).take(9)
        parent.addView(section(T.t("Juegos sugeridos")).apply { setPadding(dp(18), dp(18), 0, dp(6)) })
        val grid = vertical(); grid.setPadding(dp(12), 0, dp(12), 0)
        var row: LinearLayout? = null
        picks.forEachIndexed { i, g ->
            if (i % 3 == 0) { row = horizontal(); row!!.gravity = Gravity.TOP; grid.addView(row, LinearLayout.LayoutParams(-1, -2)) }
            val cell = vertical()
            val art = FrameLayout(this); art.background = round(0xFFF2EEE4.toInt(), 10f); art.clipToOutline = true
            art.addView(tv(g.title, 11f, 0xFF2B2B2B.toInt(), bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }, FrameLayout.LayoutParams(-1, -1))
            val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.CENTER_CROP; art.addView(iv, FrameLayout.LayoutParams(-1, -1))
            artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles")
            val tag = tv(g.console.name, 9f, Color.WHITE, bold = true).apply { setPadding(dp(5), dp(2), dp(5), dp(2)); background = round(g.console.color, 6f) }
            art.addView(tag, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(5), dp(5), 0, 0) })
            cell.addView(art, LinearLayout.LayoutParams(-1, dp(140)))
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
            control(row, "friend_${f.userId}", Color.TRANSPARENT, 10f, false) { openEmbedded("friends") }
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
        val all = library.filtered(console, fav, query)
        return when (source) {
            "telegram" -> all.filter { it.telegramAccount != 0L }
            "phone" -> all.filter { it.id.startsWith("local:") }
            else -> all
        }
    }
    private fun libraryPage() {
        val c = console
        val title = when { source == "fav" -> T.t("Favoritos"); c == null -> T.t("Biblioteca"); else -> shortName(c) } + if (query.isNotEmpty()) " · \"$query\"" else ""
        val outer = vertical()
        outer.addView(header(title, true))
        val base = if (source == "fav") library.filtered(c, true, query) else library.filtered(c, false, query)
        if (source != "fav") {
            val chips = horizontal(); chips.setPadding(dp(16), 0, dp(16), dp(8))
            val tg = base.count { it.telegramAccount != 0L }; val ph = base.count { it.id.startsWith("local:") }
            chips.addView(chip(T.t("Todos") + " ${base.size}", source == "all", "chip_all") { source = "all"; render() })
            chips.addView(chip("Telegram $tg", source == "telegram", "chip_tg") { source = "telegram"; render() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            if (ph > 0 || source == "phone") chips.addView(chip(T.t("Teléfono") + " $ph", source == "phone", "chip_ph") { source = "phone"; render() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
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
    private fun sourceIcon(g: Game, kept: Boolean): Pair<String, Int> = when {
        g.id.startsWith("local:") -> "▣" to 0xFFF0B455.toInt()
        kept -> "✓" to 0xFF22C55E.toInt()
        else -> "✈" to 0xFF5AB0F0.toInt()
    }
    private fun gameTile(g: Game, kept: Boolean): View {
        val cell = vertical(); cell.setPadding(0, 0, 0, dp(2))
        val art = FrameLayout(this); art.background = round(0xFFF2EEE4.toInt(), 10f)
        art.clipToOutline = true
        art.addView(tv(g.title, 12f, 0xFF2B2B2B.toInt(), bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(6)); maxLines = 3 },
            FrameLayout.LayoutParams(-1, -1))
        val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.CENTER_CROP
        art.addView(iv, FrameLayout.LayoutParams(-1, -1))
        artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles")
        if (library.favorite(g)) art.addView(tv("★", 14f, 0xFFFFB400.toInt()).apply { setPadding(dp(6), dp(4), dp(6), 0) }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        cell.addView(art, LinearLayout.LayoutParams(-1, dp(if (landscape) 110 else 150)))
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
        col.addView(button("▶  " + T.t("Jugar"), "d_play", true) { play(g) }, LinearLayout.LayoutParams(-1, dp(52)))
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

    // ------------------------------------------------------------------ more: screenshots, shortcut, info
    private fun gameMore(g: Game) {
        val items = mutableListOf(T.t("Capturas de pantalla"), T.t("Añadir a la pantalla de inicio"),
            if (library.favorite(g)) T.t("Quitar de favoritos") else T.t("Añadir a favoritos"))
        AlertDialog.Builder(this).setTitle(g.title).setItems(items.toTypedArray()) { _, w ->
            when (w) { 0 -> showShots(g); 1 -> pinShortcut(g); else -> { library.toggleFavorite(g); render() } }
        }.show()
    }
    private fun pinShortcut(g: Game) {
        val sm = getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
        if (!sm.isRequestPinShortcutSupported) { toast(T.t("Tu pantalla de inicio no admite accesos directos.")); return }
        var icon: android.graphics.drawable.Icon? = null
        runCatching {
            artwork.cachedCover(g)?.let { f ->
                val b = android.graphics.BitmapFactory.decodeFile(f.path)
                if (b != null) { val s = minOf(b.width, b.height); val sq = android.graphics.Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
                    icon = android.graphics.drawable.Icon.createWithAdaptiveBitmap(android.graphics.Bitmap.createScaledBitmap(sq, 432, 432, true)) }
            }
        }
        val open = Intent(this, PhoneActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("play_game", g.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val info = android.content.pm.ShortcutInfo.Builder(this, "game_" + Integer.toHexString(g.id.hashCode()))
            .setShortLabel(g.title.take(24)).setLongLabel(g.title)
            .setIcon(icon ?: android.graphics.drawable.Icon.createWithResource(this, R.drawable.app_icon)).setIntent(open).build()
        sm.requestPinShortcut(info, null)
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
                        AlertDialog.Builder(this).setView(big).setPositiveButton(T.t("Cerrar"), null).show()
                    }
                }
            }
            if (row != null && row!!.childCount == 1) row!!.addView(View(this), LinearLayout.LayoutParams(0, dp(110), 1f))
        }
    }

    // ------------------------------------------------------------------ search, folders, keys
    private fun search() {
        val input = field(T.t("Nombre del juego"), InputType.TYPE_CLASS_TEXT); input.setText(query)
        AlertDialog.Builder(this).setTitle(T.t("Buscar juego")).setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton(T.t("Buscar")) { _, _ -> query = input.text.toString().trim(); if (page != "library") { console = null; source = "all" }; go("library") }
            .setNegativeButton(T.t("Cancelar"), null).show()
    }
    private var scanning = false
    private fun scanFolders() {
        if (scanning) return; scanning = true
        Thread {
            val r = runCatching { PhoneGames.scan(this) }.getOrNull()
            runOnUiThread {
                scanning = false
                if (r != null) { library.replaceLocal(r.games); library = Library(this); if (page == "home" || page == "library") render() }
            }
        }.start()
    }

    override fun onBackPressed() {
        when (page) {
            "welcome" -> { if (drive.stage != TirsoDrive.Stage.READY) drive.skip(); go("home") }
            "loading" -> cancelLoading()
            "detail" -> go("library")
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
            KeyEvent.KEYCODE_BUTTON_START -> { openEmbedded("settings"); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_BUTTON_B) return true
        return super.onKeyUp(keyCode, event)
    }
}
