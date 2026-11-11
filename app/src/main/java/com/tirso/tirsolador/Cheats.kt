package com.tirso.tirsolador

import android.app.Activity
import android.app.Dialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.LibretroDroid
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cheats: the list packed by Tirso Compiler (assets/tirso/cheats.json) or, for GameCube/Wii, the one
 * Dolphin builds from its game settings at start (files/cheats/dolphin-emu/<game>.cht).
 * Every cheat starts off; what you turn on is remembered and applied each time the game starts.
 */
class Cheats(private val activity: Activity, private val prefs: SharedPreferences, private val dolphin: Boolean,
             private val gameKey: String = "", private val title: String = "", private val console: Console? = null) {
    data class Cheat(val name: String, val code: String)

    private var cached: List<Cheat>? = null

    fun list(): List<Cheat> {
        cached?.let { if (it.isNotEmpty() || !dolphin) return it }
        val out = mutableListOf<Cheat>()
        if (dolphin) {
            listOf(File(activity.filesDir, "cheats/dolphin-emu"), File(activity.filesDir, "bios/cheats/dolphin-emu"))
                .flatMap { d -> d.listFiles { f -> f.name.endsWith(".cht") }?.toList().orEmpty() }
                .maxByOrNull { it.lastModified() }?.let { out += parseCht(it.readText()) }
        } else {
            runCatching {
                val text = activity.assets.open("tirso/cheats.json").use { it.reader().readText() }
                val arr = JSONObject(text).getJSONArray("cheats")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out += Cheat(o.optString("name"), o.optString("code"))
                }
            }
            val f = file()
            if (out.isEmpty() && f != null && f.isFile) out += parseCht(f.readText())
        }
        cached = out
        return out
    }

    fun available() = if (dolphin) true
        else runCatching { activity.assets.open("tirso/cheats.json").close(); true }.getOrDefault(false) ||
            (console != null && console != Console.MAME && gameKey.isNotEmpty() && !recentMiss())

    // ------------------------------------------------------------------ download (libretro cheat database)
    private fun dir() = File(activity.filesDir, "cheats/db").apply { mkdirs() }
    private fun file() = if (gameKey.isEmpty()) null else File(dir(), "$gameKey.cht")
    private fun miss() = File(dir(), "$gameKey.none")
    private fun recentMiss() = miss().let { it.isFile && System.currentTimeMillis() - it.lastModified() < 7L * 24 * 3600_000 }
    private fun have() = file()?.isFile == true

    /** Makes sure this game's cheat list has been downloaded (once), then calls [done] on the main thread. */
    fun ensure(done: () -> Unit) {
        if (dolphin || have() || console == null || console == Console.MAME || recentMiss()) { done(); return }
        Thread {
            runCatching { download() }
            cached = null
            activity.runOnUiThread { done() }
        }.start()
    }

    private fun norm(name: String) = name.substringBefore(" (").lowercase().replace(Regex("[^a-z0-9]"), "")
    private fun region(name: String): String {
        val tag = Regex("""\(([^)]*)\)""").find(name)?.groupValues?.get(1).orEmpty()
        return when {
            tag.contains("USA") -> "usa"; tag.contains("Europe") -> "eur"; tag.contains("World") -> "world"
            tag.contains("Japan") -> "jpn"; tag.isEmpty() -> ""; else -> tag.lowercase()
        }
    }
    private fun http(url: String): String? {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000; c.setRequestProperty("User-Agent", "RetroStream")
        return try { if (c.responseCode == 200) c.inputStream.use { it.reader().readText() } else null } finally { c.disconnect() }
    }
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun index(system: String): List<String> {
        val cache = File(dir(), "index-${norm(system)}.txt")
        if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < 30L * 24 * 3600_000) return cache.readLines()
        val json = http("https://api.github.com/repos/libretro/libretro-database/git/trees/master:cht%2F${enc(system)}")
            ?: return if (cache.isFile) cache.readLines() else emptyList()
        val arr = JSONObject(json).getJSONArray("tree")
        val names = (0 until arr.length()).map { arr.getJSONObject(it).getString("path") }.filter { it.endsWith(".cht") }
        cache.writeText(names.joinToString("\n"))
        return names
    }

    private fun download() {
        val c = console ?: return
        val names = index(c.thumbnailSystem)
        if (names.isEmpty()) return                      // no internet: try again next time (no miss marker)
        val want = norm(title)
        val same = names.filter { norm(it.removeSuffix(".cht")) == want }
        if (same.isEmpty()) { miss().writeText(title); return }
        val mine = region(title)
        val groups = same.groupBy { region(it.removeSuffix(".cht")) }
        val pick = groups[mine] ?: groups["usa"] ?: groups["world"] ?: groups["eur"] ?: groups[""] ?: groups.values.first()
        val sb = StringBuilder(); var n = 0
        for (name in pick.sortedBy { it.count { ch -> ch == '(' } }.take(4)) {
            val text = http("https://raw.githubusercontent.com/libretro/libretro-database/master/cht/${enc(c.thumbnailSystem)}/${enc(name)}") ?: continue
            val device = Regex("""\((Game Genie|GameShark|Game Shark|Action Replay|Code Breaker|Rumbles|Pro Action Replay)\)""").find(name)?.groupValues?.get(1)
            for (ch in parseCht(text)) {
                sb.append("cheat${n}_desc = \"").append(if (device != null) "${ch.name} [$device]" else ch.name).append("\"\n")
                sb.append("cheat${n}_code = \"").append(ch.code).append("\"\n"); n++
            }
        }
        if (n == 0) miss().writeText(title) else { file()!!.writeText(sb.toString()); miss().delete() }
    }

    private fun parseCht(text: String): List<Cheat> {
        val fields = sortedMapOf<Int, MutableMap<String, String>>()
        val re = Regex("""^\s*cheat(\d+)_(desc|code)\s*=\s*"?(.*?)"?\s*$""")
        text.lineSequence().forEach { line ->
            re.find(line)?.let { m -> fields.getOrPut(m.groupValues[1].toInt()) { mutableMapOf() }[m.groupValues[2]] = m.groupValues[3] }
        }
        return fields.values.mapNotNull { f ->
            val n = f["desc"].orEmpty(); val c = f["code"].orEmpty()
            if (n.isBlank() || c.isBlank()) null else Cheat(n, c)
        }
    }

    /** Codes of the cheats that are on. */
    var enabled: Set<String>
        get() = runCatching {
            val a = JSONArray(prefs.getString("cheats_on$gameKey", "[]")); (0 until a.length()).map { a.getString(it) }.toSet()
        }.getOrDefault(emptySet())
        set(v) { prefs.edit().putString("cheats_on$gameKey", JSONArray(v.toList()).toString()).apply() }

    /** Sends the cheats that are on to the engine, numbered from 0 (some engines need that). */
    fun apply(view: GLRetroView) {
        val on = enabled
        val codes = list().filter { it.code in on }.map { it.code }
        view.queueEvent {
            runCatching {
                LibretroDroid.resetCheat()
                codes.forEachIndexed { i, code -> LibretroDroid.setCheat(i, true, code) }
            }
        }
    }

    fun show(view: GLRetroView?, onClose: () -> Unit) {
        val all = list()
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics).toInt() }
        val on = enabled.toMutableSet()
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#161A24")); cornerRadius = dp(18f).toFloat(); setStroke(dp(1f), Color.parseColor("#323A50")) }
            setPadding(dp(18f), dp(16f), dp(18f), dp(12f))
        }
        val title = TextView(activity).apply { setTextColor(Color.parseColor("#E9ECF5")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f) }
        card.addView(title)
        val note = TextView(activity).apply {
            setTextColor(Color.parseColor("#8C93A8")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = if (all.isEmpty()) {
                if (dolphin) "No cheats are known for this game yet. Dolphin lists them a few seconds after the game starts; try again then."
                else tr("No cheats were found for this game (or there is no internet connection).")
            } else "Tick the cheats you want. Codes marked (M) or \"Master\" must be on for the others to work. " +
                "Cheats can crash or corrupt a game: save first. Turned-on cheats stay on next time."
        }
        card.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) })
        val search = EditText(activity).apply {
            hint = "Search cheats"; setSingleLine(); setTextColor(Color.parseColor("#E9ECF5")); setHintTextColor(Color.parseColor("#5D6478"))
            visibility = if (all.size > 12) View.VISIBLE else View.GONE
        }
        card.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })

        var shown = all
        val list = ListView(activity).apply { choiceMode = AbsListView.CHOICE_MODE_MULTIPLE; divider = ColorDrawable(Color.parseColor("#262C3D")); dividerHeight = 1 }
        val adapter = object : ArrayAdapter<Cheat>(activity, android.R.layout.simple_list_item_multiple_choice, shown.toMutableList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as CheckedTextView
                v.text = getItem(position)?.name; v.setTextColor(Color.parseColor("#E9ECF5")); v.minHeight = dp(48f)
                return v
            }
        }
        list.adapter = adapter
        fun refreshChecks() {
            for (i in shown.indices) list.setItemChecked(i, shown[i].code in on)
            title.text = "Cheats" + if (on.isNotEmpty()) " (${on.size} on)" else ""
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            val c = shown[pos]
            if (c.code in on) on.remove(c.code) else on.add(c.code)
            refreshChecks()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                shown = if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) }
                adapter.clear(); adapter.addAll(shown); refreshChecks()
            }
        })
        card.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6f) })

        val bar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        fun button(text: String, primary: Boolean, click: () -> Unit) = Button(activity).apply {
            this.text = text; isAllCaps = false
            setTextColor(Color.parseColor(if (primary) "#1A1712" else "#E9ECF5"))
            background = GradientDrawable().apply { setColor(Color.parseColor(if (primary) "#EDE6D6" else "#1C2130")); cornerRadius = dp(10f).toFloat() }
            setPadding(dp(16f), 0, dp(16f), 0); setOnClickListener { click() }
        }
        if (all.isNotEmpty()) bar.addView(button("All off", false) { on.clear(); refreshChecks() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)).apply { marginEnd = dp(10f) })
        val done = button("Done", true) { dialog.dismiss() }
        bar.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)))
        card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })

        val scrim = FrameLayout(activity).apply { setBackgroundColor(Color.argb(230, 8, 10, 16)) }
        scrim.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            val m = dp(16f); setMargins(m, m, m, m)
        })
        dialog.setContentView(scrim)
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_BUTTON_B
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_B -> { dialog.dismiss(); true }
                KeyEvent.KEYCODE_BUTTON_A -> {   // A ticks the highlighted cheat (or presses the highlighted button)
                    val f = dialog.currentFocus
                    if (f is Button) f.performClick()
                    else if (list.selectedItemPosition >= 0) list.performItemClick(list.selectedView, list.selectedItemPosition, 0)
                    true
                }
                else -> false
            }
        }
        dialog.setOnDismissListener {
            val changed = on != enabled
            enabled = on.toSet()
            if (changed && view != null) apply(view)
            onClose()
        }
        refreshChecks()
        dialog.show()
        if (all.isNotEmpty()) list.requestFocus() else done.requestFocus()
    }
}
