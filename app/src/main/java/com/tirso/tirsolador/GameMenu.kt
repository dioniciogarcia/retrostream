package com.tirso.tirsolador

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * The long-press menu for a game (same everywhere, on the phone and on the TV), and the windows behind it:
 * search the game's information by hand, rename, choose a cover, games missing information, hidden games, space used.
 */
internal object GameMenu {
    /** What the screen showing the menu does for it. */
    interface Host {
        fun refresh()                      // reload the library and redraw
        fun play(g: Game, fresh: Boolean)  // fresh = from the start, without "continue where you left off"
        fun shots(g: Game)
        fun pin(g: Game)
        fun openCollection(name: String)   // the library showing only that collection
        fun open(g: Game)                  // the game's page (phone) / the game in its console's library (TV)
    }

    private val main = Handler(Looper.getMainLooper())
    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()
    private fun toast(a: Activity, s: String) = Toast.makeText(a, s, Toast.LENGTH_LONG).show()
    private fun tv(a: Activity) = a.packageManager.hasSystemFeature("android.software.leanback") && !BuildConfig.PHONE

    /** Game file already on the device (kept on the phone, or still in Telegram's downloads). */
    @JvmStatic fun downloaded(a: Activity, drive: TirsoDrive, g: Game): Boolean {
        if (g.id.startsWith("local:")) return false
        if (PhoneGames.kept(a).any { it[0] == g.id }) return true
        val f = drive.file(g); return f != null && f.local.isDownloadingCompleted
    }

    @JvmStatic fun show(a: Activity, g: Game, lib: Library, drive: TirsoDrive, recent: Boolean, host: Host) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        fun add(label: String, f: () -> Unit) { items += label to f }
        if (recent) {
            add(T.t("Continuar")) { host.play(g, false) }
            add(T.t("Jugar desde el principio")) {
                AlertDialog.Builder(a).setTitle(g.title).setMessage(T.t("El juego empezará sin cargar dónde lo dejaste. Tus partidas guardadas no se borran."))
                    .setPositiveButton(T.t("Jugar")) { _, _ -> host.play(g, true) }.setNegativeButton(T.t("Cancelar"), null).show()
            }
        }
        add(T.t("Ver información del juego")) { info(a, g) }
        add(T.t("Buscar información del juego")) { search(a, g, false, host) }
        add(T.t("Editar información")) { editInfo(a, g, host) }
        add((if (lib.favorite(g)) T.t("Quitar de favoritos") else T.t("Añadir a favoritos"))) { lib.toggleFavorite(g); host.refresh() }
        add(T.t("Añadir a una colección")) { collectionPicker(a, lib, g, host) }
        add(if (lib.completed(g)) T.t("Quitar «Completado»") else T.t("Marcar como completado")) { lib.completed(g, !lib.completed(g)); host.refresh() }
        if (g.console != Console.MAME) add(T.t("Ajustes de este juego")) { gameSettings(a, g) }
        if (g.telegramAccount != 0L) add(T.t("Compartir con un amigo")) { share(a, drive, g) }
        if (BuildConfig.PHONE && g.telegramAccount != 0L && !downloaded(a, drive, g) && !DownloadQueue.contains(g))
            add(T.t("Descargar para jugar sin conexión")) { val n = DownloadQueue.add(a, drive, listOf(g)); if (n > 0) toast(a, T.t("Añadido a la cola de descargas")) }
        add(T.t("Capturas de pantalla")) { host.shots(g) }
        val sm = a.getSystemService(android.content.pm.ShortcutManager::class.java)
        if (sm != null && sm.isRequestPinShortcutSupported) add(T.t("Añadir a la pantalla de inicio")) { host.pin(g) }
        val sha = SaveBackups.shaOf(a, g.id)
        if (sha != null) add(T.t("Hacer copia de la partida ahora")) { backupNow(a, drive, sha) }
        if (downloaded(a, drive, g)) add(T.t("Borrar archivo descargado")) {
            AlertDialog.Builder(a).setTitle(g.title).setMessage(T.t("Se borra del dispositivo para liberar espacio. El juego sigue en Telegram y en tu biblioteca."))
                .setPositiveButton(T.t("Borrar")) { _, _ -> deleteDownload(a, drive, g); host.refresh(); toast(a, T.t("Archivo borrado")) }.setNegativeButton(T.t("Cancelar"), null).show()
        }
        add(T.t("Ocultar juego")) {
            AlertDialog.Builder(a).setTitle(g.title).setMessage(T.t("No se mostrará en tu biblioteca. Puedes volver a mostrarlo en Ajustes > Juegos ocultos."))
                .setPositiveButton(T.t("Ocultar")) { _, _ -> lib.hide(g, true); host.refresh() }.setNegativeButton(T.t("Cancelar"), null).show()
        }
        if (recent) {
            add(T.t("Quitar de recientes")) { lib.removeRecent(g); host.refresh() }
            add(T.t("Borrar todos los recientes")) {
                AlertDialog.Builder(a).setTitle(T.t("Borrar todos los recientes")).setMessage(T.t("La fila «Jugados recientemente» quedará vacía. El historial de juego y las partidas no se tocan."))
                    .setPositiveButton(T.t("Borrar")) { _, _ -> lib.clearRecents(); host.refresh() }.setNegativeButton(T.t("Cancelar"), null).show()
            }
        }
        AlertDialog.Builder(a).setTitle(g.title).setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .setNegativeButton(T.t("Cerrar"), null).show()
    }

    private fun deleteDownload(a: Activity, drive: TirsoDrive, g: Game) {
        PhoneGames.kept(a).firstOrNull { it[0] == g.id }?.let { e -> PhoneGames.forget(a, g.id); runCatching { File(e[3] as String).delete() } }
        drive.releaseRom(g.fileId, g.telegramAccount)
    }

    private fun backupNow(a: Activity, drive: TirsoDrive, sha: String) {
        val e = SaveBackups.entries(a).firstOrNull { it.sha == sha }
        if (e == null) { toast(a, T.t("Este juego aún no tiene partidas guardadas.")); return }
        Toast.makeText(a, T.t("Haciendo copia…"), Toast.LENGTH_SHORT).show()
        SaveBackups.upload(a, drive, e, true) { _, msg -> toast(a, msg) }
    }

    // ------------------------------------------------------------------ information window
    @JvmStatic fun info(a: Activity, g: Game) {
        Toast.makeText(a, T.t("Cargando información…"), Toast.LENGTH_SHORT).show()
        Thread {
            val art = Artwork(a.applicationContext)
            val box = runCatching { art.cachedCover(g)?.let { android.graphics.BitmapFactory.decodeFile(it.path) } }.getOrNull()
            val shots = mutableListOf<android.graphics.Bitmap>()
            runCatching { art.snapshot(g, "Named_Snaps")?.let { shots += it }; art.snapshot(g, "Named_Titles")?.let { shots += it } }
            art.close()
            main.post { if (!a.isFinishing) GameInfo.show(a, GameMeta.info(a, g), box, shots) {} }
        }.start()
    }

    // ------------------------------------------------------------------ fixing a game's information
    private fun editInfo(a: Activity, g: Game, host: Host) {
        val opts = arrayOf(T.t("Cambiar nombre"), T.t("Elegir portada"), T.t("Restablecer información"))
        AlertDialog.Builder(a).setTitle(g.title).setItems(opts) { _, w ->
            when (w) {
                0 -> {
                    val input = EditText(a).apply { setText(g.title); setSingleLine(); setSelection(text.length) }
                    val box = FrameLayout(a).apply { setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0); addView(input) }
                    AlertDialog.Builder(a).setTitle(T.t("Cambiar nombre")).setMessage(T.t("Solo cambia el nombre que ves en la app; el archivo en Telegram no se toca.")).setView(box)
                        .setPositiveButton(T.t("Guardar")) { _, _ -> val t = input.text.toString().trim(); if (t.isNotEmpty()) { GameMeta.rename(a, g, t); host.refresh() } }
                        .setNegativeButton(T.t("Cancelar"), null).show()
                }
                1 -> search(a, g, true, host)
                else -> AlertDialog.Builder(a).setTitle(T.t("Restablecer información")).setMessage(T.t("Se borran el nombre, la portada y la descripción que elegiste, y la app vuelve a buscarlos sola."))
                    .setPositiveButton(T.t("Restablecer")) { _, _ ->
                        GameMeta.reset(a, g); host.refresh()
                        GameMeta.refresh(a, Library(a).games) { host.refresh() }
                    }.setNegativeButton(T.t("Cancelar"), null).show()
            }
        }.show()
    }

    /** Search window: type a name, pick the right game. [coverOnly] changes only the picture. */
    @JvmStatic fun search(a: Activity, g: Game, coverOnly: Boolean, host: Host) {
        val dark = 0xFF161A24.toInt(); val text = 0xFFE9ECF5.toInt(); val muted = 0xFF9AA3B8.toInt()
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 4)) }
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(a).apply {
            setText(GameMeta.clean(GameMeta.stemOf(g))); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH; setTextColor(text); setHintTextColor(muted); hint = T.t("Nombre del juego")
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val go = TextView(a).apply {
            this.text = T.t("Buscar"); setTextColor(Color.WHITE); textSize = 15f; setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 10))
            background = GradientDrawable().apply { cornerRadius = dp(a, 10).toFloat(); setColor(0xFF2F6DF6.toInt()) }; isFocusable = true; isClickable = true
        }
        bar.addView(go, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(a, 8) })
        col.addView(bar)
        val status = TextView(a).apply { setTextColor(muted); textSize = 13f; setPadding(0, dp(a, 8), 0, dp(a, 6)) }
        col.addView(status)
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(a).apply { addView(list) }
        col.addView(scroll, LinearLayout.LayoutParams(-1, (a.resources.displayMetrics.heightPixels * 0.55f).toInt()))
        val art = Artwork(a.applicationContext)
        val dialog = AlertDialog.Builder(a).setTitle(if (coverOnly) T.t("Elegir portada") else T.t("Buscar información del juego")).setView(col)
            .setNegativeButton(T.t("Cerrar"), null).create()
        dialog.setOnDismissListener { art.close() }
        var run = 0
        fun pick(r: org.json.JSONObject) {
            status.text = T.t("Guardando…")
            Thread {
                runCatching { if (coverOnly) GameMeta.setCover(a, g, r.optString("c")) else GameMeta.choose(a, g, r) }
                main.post { dialog.dismiss(); host.refresh(); toast(a, if (coverOnly) T.t("Portada cambiada") else T.t("Información actualizada")) }
            }.start()
        }
        fun doSearch() {
            val q = input.text.toString().trim(); if (q.isEmpty()) return
            val token = ++run
            list.removeAllViews(); status.text = T.t("Buscando…")
            (a.getSystemService(Activity.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
            Thread {
                val found = runCatching { GameMeta.search(a, g.console, q) }.getOrDefault(emptyList())
                main.post {
                    if (token != run || !dialog.isShowing) return@post
                    status.text = if (found.isEmpty()) T.t("No se encontró nada. Prueba con menos palabras o con el nombre en inglés.") else found.size.toString() + T.t(" resultados")
                    for (r in found) {
                        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(a, 8), dp(a, 8), dp(a, 8), dp(a, 8)); isFocusable = true; isClickable = true }
                        row.background = GradientDrawable().apply { cornerRadius = dp(a, 10).toFloat(); setColor(Color.TRANSPARENT) }
                        row.setOnFocusChangeListener { v, f -> (v.background as GradientDrawable).setStroke(if (f) dp(a, 3) else 0, 0xFF2F6DF6.toInt()) }
                        val cover = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER; background = GradientDrawable().apply { cornerRadius = dp(a, 6).toFloat(); setColor(dark) } }
                        val holder = FrameLayout(a).apply { addView(cover, FrameLayout.LayoutParams(-1, -1)) }
                        row.addView(holder, LinearLayout.LayoutParams(dp(a, 56), dp(a, 72)))
                        val name = r.optString("c")
                        art.load(Game("pick:$name", name, g.console, "", name), cover)
                        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 12), 0, 0, 0) }
                        texts.addView(TextView(a).apply { this.text = r.optString("t"); textSize = 15f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                        val sub = listOfNotNull(r.optInt("y").takeIf { it > 0 }?.toString(), r.optString("r").takeIf { it.isNotBlank() },
                            name.takeIf { it != r.optString("t") }, if (r.optString("d").isNotBlank()) T.t("con descripción") else null).joinToString(" · ")
                        texts.addView(TextView(a).apply { this.text = sub; textSize = 12f; setTextColor(muted); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                        row.setOnClickListener { pick(r) }
                        list.addView(row, LinearLayout.LayoutParams(-1, -2))
                    }
                    if (tv(a) && list.childCount > 0) list.getChildAt(0).requestFocus()
                }
            }.start()
        }
        go.setOnClickListener { doSearch() }
        input.setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { doSearch(); true } else false }
        dialog.show()
        doSearch()
    }

    // ------------------------------------------------------------------ collections, per-game settings, sharing
    private fun ask(a: Activity, title: String, start: String, done: (String) -> Unit) {
        val input = EditText(a).apply { setText(start); setSingleLine(); setSelection(text.length) }
        val box = FrameLayout(a).apply { setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0); addView(input) }
        AlertDialog.Builder(a).setTitle(title).setView(box)
            .setPositiveButton(T.t("Guardar")) { _, _ -> val t = input.text.toString().trim(); if (t.isNotEmpty()) done(t) }.setNegativeButton(T.t("Cancelar"), null).show()
    }
    private fun collectionPicker(a: Activity, lib: Library, g: Game, host: Host) {
        val names = lib.collections()
        val checked = names.map { g.id in lib.collection(it) }.toBooleanArray()
        val b = AlertDialog.Builder(a).setTitle(T.t("Colecciones · ") + g.title)
        if (names.isEmpty()) b.setMessage(T.t("Aún no tienes colecciones. Crea la primera."))
        else b.setMultiChoiceItems(names.toTypedArray(), checked) { _, w, on -> lib.collect(names[w], g, on) }
        b.setPositiveButton(T.t("Listo")) { _, _ -> host.refresh() }
            .setNeutralButton(T.t("Nueva colección")) { _, _ -> ask(a, T.t("Nueva colección"), "") { name -> lib.createCollection(name); lib.collect(name, g, true); toast(a, g.title + T.t(" añadido a ") + name); host.refresh() } }
            .show()
    }
    /** Your collections: open one, rename or delete it. */
    @JvmStatic fun collections(a: Activity, host: Host) {
        val lib = Library(a); val names = lib.collections()
        if (names.isEmpty()) {
            AlertDialog.Builder(a).setTitle(T.t("Colecciones")).setMessage(T.t("Crea listas propias como «Para jugar en pareja» o «Terminados». Mantén pulsado un juego y elige «Añadir a una colección»."))
                .setPositiveButton(T.t("Nueva colección")) { _, _ -> ask(a, T.t("Nueva colección"), "") { lib.createCollection(it); host.refresh() } }.setNegativeButton(T.t("Cerrar"), null).show()
            return
        }
        val labels = names.map { n -> n + "  ·  " + lib.collection(n).count { id -> lib.games.any { it.id == id } } + T.t(" juegos") }.toTypedArray()
        AlertDialog.Builder(a).setTitle(T.t("Colecciones")).setItems(labels) { _, w ->
            val n = names[w]
            AlertDialog.Builder(a).setTitle(n).setItems(arrayOf(T.t("Abrir"), T.t("Cambiar nombre"), T.t("Borrar colección"))) { _, x ->
                when (x) {
                    0 -> host.openCollection(n)
                    1 -> ask(a, T.t("Cambiar nombre"), n) { lib.renameCollection(n, it); host.refresh() }
                    else -> AlertDialog.Builder(a).setTitle(T.t("Borrar colección")).setMessage(n + T.t(": los juegos no se borran, solo la lista."))
                        .setPositiveButton(T.t("Borrar")) { _, _ -> lib.deleteCollection(n); host.refresh() }.setNegativeButton(T.t("Cancelar"), null).show()
                }
            }.show()
        }.setPositiveButton(T.t("Nueva colección")) { _, _ -> ask(a, T.t("Nueva colección"), "") { lib.createCollection(it); host.refresh() } }
            .setNegativeButton(T.t("Cerrar"), null).show()
    }

    /** Resolution for this game only (the rest of its settings are in the game's menu > "Settings for this game only"). */
    private fun gameSettings(a: Activity, g: Game) {
        val keys = arrayOf<String?>(null, "low", "medium", "high")
        val labels = arrayOf(T.t("Igual que la consola") + " (" + resName(Presets.resolution(a, g.console)) + ")", T.t("Baja"), T.t("Media"), T.t("Alta"))
        val now = keys.indexOf(Presets.gameResolution(a, g.id)).coerceAtLeast(0)
        AlertDialog.Builder(a).setTitle(T.t("Resolución de este juego")).setSingleChoiceItems(labels, now) { d, w ->
            Presets.setGameResolution(a, g.id, keys[w]); d.dismiss()
            toast(a, T.t("Se usa la próxima vez que abras el juego. Dentro del juego, «Ajustes solo para este juego» guarda también los controles táctiles, el filtro y el avance rápido."))
        }.setNegativeButton(T.t("Cancelar"), null).show()
    }
    private fun resName(k: String) = when (k) { "low" -> T.t("Baja"); "high" -> T.t("Alta"); else -> T.t("Media") }

    private fun share(a: Activity, drive: TirsoDrive, g: Game) {
        drive.messageLink(g) { link, err ->
            if (link == null) { toast(a, err ?: T.t("No se pudo crear el enlace.")); return@messageLink }
            val text = g.title + " (" + g.console.title + ")\n" + link
            val opts = mutableListOf<String>()
            if (drive.friendsChat() != 0L) opts += T.t("Enviar al grupo de amigos")
            opts += T.t("Compartir con otra app")
            AlertDialog.Builder(a).setTitle(T.t("Compartir con un amigo")).setItems(opts.toTypedArray()) { _, w ->
                if (opts[w] == T.t("Enviar al grupo de amigos"))
                    drive.tellFriends("🎮 " + T.t("Te recomiendo: ") + text) { ok, e -> toast(a, if (ok == true) T.t("Enviado al grupo de amigos") else (e ?: T.t("No se pudo enviar"))) }
                else runCatching {
                    a.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), T.t("Compartir con un amigo")))
                }.onFailure { toast(a, T.t("No hay apps para compartir en este dispositivo.")) }
            }.setNegativeButton(T.t("Cancelar"), null).show()
        }
    }

    /** A random game from your library: play it, try another, or close. */
    @JvmStatic fun surprise(a: Activity, host: Host) {
        val lib = Library(a)
        val pool = lib.filtered(null, false, "")
        if (pool.isEmpty()) { toast(a, T.t("Tu biblioteca está vacía.")); return }
        val played = lib.recent().toSet()
        val fresh = pool.filter { it.id !in played && !lib.completed(it) }
        val g = (if (fresh.isNotEmpty() && Math.random() < 0.8) fresh else pool).random()
        val more = listOfNotNull(g.console.title, PlayTime.label(a, g.id).takeIf { it.isNotEmpty() }?.let { it + T.t(" jugadas") } ?: T.t("Nunca jugado"),
            GameMeta.info(a, g).optInt("year").takeIf { it > 0 }?.toString()).joinToString(" · ")
        AlertDialog.Builder(a).setTitle("🎲 " + g.title).setMessage(more)
            .setPositiveButton(T.t("Jugar")) { _, _ -> host.play(g, false) }
            .setNeutralButton(T.t("Otro")) { _, _ -> surprise(a, host) }
            .setNegativeButton(T.t("Ver juego")) { _, _ -> host.open(g) }.show()
    }

    /** Time played: total, per console and your most played games. */
    @JvmStatic fun stats(a: Activity) {
        val lib = Library(a)
        val per = lib.games.map { it to PlayTime.get(a, it.id) }.filter { it.second >= 60_000 }
        if (per.isEmpty()) { toast(a, T.t("Aún no hay tiempo de juego registrado.")); return }
        fun h(ms: Long): String { val m = ms / 60000; return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min" }
        val sb = StringBuilder()
        sb.append(T.t("Total: ")).append(h(per.sumOf { it.second })).append(" · ").append(per.size).append(T.t(" juegos")).append("\n")
        sb.append(T.t("Completados: ")).append(lib.games.count { lib.completed(it) }).append("\n\n")
        sb.append(T.t("POR CONSOLA")).append("\n")
        for ((c, list) in per.groupBy { it.first.console }.toList().sortedByDescending { e -> e.second.sumOf { it.second } })
            sb.append("• ").append(c.title).append(": ").append(h(list.sumOf { it.second })).append("\n")
        sb.append("\n").append(T.t("MÁS JUGADOS")).append("\n")
        per.sortedByDescending { it.second }.take(10).forEachIndexed { i, (g, t) -> sb.append(i + 1).append(". ").append(g.title).append(" — ").append(h(t)).append("\n") }
        val text = TextView(a).apply { this.text = sb.toString().trim(); textSize = 15f; setPadding(dp(a, 22), dp(a, 12), dp(a, 22), dp(a, 8)); setLineSpacing(0f, 1.25f); isFocusable = true }
        AlertDialog.Builder(a).setTitle(T.t("Estadísticas de juego")).setView(ScrollView(a).apply { addView(text) }).setPositiveButton(T.t("Cerrar"), null).show()
    }

    /** Phone: what's downloading for offline play, with a way to stop it. */
    @JvmStatic fun queue(a: Activity, drive: TirsoDrive, host: Host) {
        val list = DownloadQueue.list()
        val b = AlertDialog.Builder(a).setTitle(T.t("Cola de descargas"))
        if (list.isEmpty()) b.setMessage(T.t("No hay descargas. Mantén pulsado un juego y elige «Descargar para jugar sin conexión», o descarga una consola entera aquí."))
        else b.setMessage(DownloadQueue.status() + "\n\n" + list.joinToString("\n") { "• " + it.title })
        b.setPositiveButton(T.t("Descargar una consola")) { _, _ ->
            val lib = Library(a); val consoles = Console.all().filter { c -> lib.games.any { it.console == c && it.telegramAccount != 0L } }
            AlertDialog.Builder(a).setTitle(T.t("Descargar una consola")).setItems(consoles.map { c -> c.title + "  ·  " + lib.filtered(c, false, "").count { it.telegramAccount != 0L } + T.t(" juegos") }.toTypedArray()) { _, w ->
                val games = lib.filtered(consoles[w], false, "").filter { it.telegramAccount != 0L }
                val bytes = games.sumOf { it.fileSize }
                AlertDialog.Builder(a).setTitle(consoles[w].title).setMessage(games.size.toString() + T.t(" juegos · ") + fmt(a, bytes) + T.t(". Se guardan en el teléfono para jugar sin conexión."))
                    .setPositiveButton(T.t("Descargar")) { _, _ -> toast(a, DownloadQueue.add(a, drive, games).toString() + T.t(" juegos añadidos a la cola")); host.refresh() }
                    .setNegativeButton(T.t("Cancelar"), null).show()
            }.show()
        }
        if (list.isNotEmpty()) b.setNeutralButton(T.t("Detener todo")) { _, _ -> DownloadQueue.cancelAll(); toast(a, T.t("Descargas detenidas")); host.refresh() }
        b.setNegativeButton(T.t("Cerrar"), null).show()
    }

    // ------------------------------------------------------------------ settings windows
    /** Games with no description or no cover: tap one to search its information. */
    @JvmStatic fun missing(a: Activity, host: Host) {
        Toast.makeText(a, T.t("Revisando tu biblioteca…"), Toast.LENGTH_SHORT).show()
        Thread {
            val lib = Library(a); val art = Artwork(a.applicationContext)
            val list = ArrayList<Game>(); val labels = ArrayList<String>()
            for (g in lib.filtered(null, false, "")) {
                val noText = !GameMeta.described(a, g); val noCover = art.cachedCover(g) == null
                if (!noText && !noCover) continue
                list += g
                labels += "${g.title}  ·  ${g.console.title}  ·  " + listOfNotNull(if (noText) T.t("sin descripción") else null, if (noCover) T.t("sin portada") else null).joinToString(", ")
            }
            art.close()
            main.post {
                if (a.isFinishing) return@post
                if (list.isEmpty()) { toast(a, T.t("Todos tus juegos tienen información y portada.")); return@post }
                AlertDialog.Builder(a).setTitle(T.t("Juegos sin información") + " (${list.size})").setItems(labels.toTypedArray()) { _, w -> search(a, list[w], false, host) }
                    .setNegativeButton(T.t("Cerrar"), null).show()
            }
        }.start()
    }

    /** Games you hid: tap one to show it again. */
    @JvmStatic fun hidden(a: Activity, host: Host) {
        val lib = Library(a); val list = lib.hiddenGames()
        if (list.isEmpty()) { toast(a, T.t("No tienes juegos ocultos.")); return }
        AlertDialog.Builder(a).setTitle(T.t("Juegos ocultos")).setItems(list.map { "${it.title}  ·  ${it.console.title}" }.toTypedArray()) { _, w ->
            lib.hide(list[w], false); host.refresh(); toast(a, T.t("Se muestra de nuevo: ") + list[w].title)
        }.setNegativeButton(T.t("Cerrar"), null).show()
    }

    private fun size(f: File): Long = if (!f.exists()) 0L else if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private fun fmt(a: Activity, b: Long) = android.text.format.Formatter.formatShortFileSize(a, b)

    /** Space used by the app, with a clean-up for what can be downloaded again. */
    @JvmStatic fun storage(a: Activity, drive: TirsoDrive, host: Host) {
        Toast.makeText(a, T.t("Calculando…"), Toast.LENGTH_SHORT).show()
        Thread {
            val dir = a.filesDir
            val kept = PhoneGames.kept(a)
            val keptBytes = kept.sumOf { it[4] as Long }
            val downloads = (size(File(dir, "telegram/files")) - kept.filter { (it[3] as String).startsWith(File(dir, "telegram/files").path) }.sumOf { it[4] as Long }).coerceAtLeast(0)
            val saves = size(File(dir, "saves"))
            val shots = size(File(dir, "screenshots"))
            val art = size(File(dir, "artwork")) + size(File(dir, "thumbs"))
            val data = size(File(dir, "openvgdb.sqlite")) + size(File(dir, "gamemeta.json")) + size(File(dir, "cheats"))
            val telegram = size(File(dir, "telegram/database"))
            main.post {
                if (a.isFinishing) return@post
                val rows = mutableListOf<Pair<String, (() -> Unit)?>>()
                fun row(label: String, f: (() -> Unit)? = null) { rows += label to f }
                row((T.t("Descargas de Telegram") + ": " + fmt(a, downloads) + "  ·  " + T.t("toca para borrar"))) {
                    confirm(a, T.t("Borrar descargas"), T.t("Se borran los juegos descargados que no guardaste. Siguen en Telegram.")) {
                        drive.releaseAll(); toast(a, T.t("Descargas borradas")); host.refresh()
                    }
                }
                if (keptBytes > 0) row((T.t("Juegos guardados en el teléfono") + ": " + fmt(a, keptBytes) + "  ·  " + T.t("toca para borrar"))) {
                    confirm(a, T.t("Borrar juegos guardados"), T.t("Se borran del teléfono; siguen en Telegram.")) {
                        for (e in PhoneGames.kept(a)) { PhoneGames.forget(a, e[0] as String); drive.releaseRom(e[1] as Int, e[2] as Long); runCatching { File(e[3] as String).delete() } }
                        toast(a, T.t("Juegos guardados borrados")); host.refresh()
                    }
                }
                row((T.t("Portadas e imágenes") + ": " + fmt(a, art) + "  ·  " + T.t("toca para borrar"))) {
                    confirm(a, T.t("Borrar portadas"), T.t("Se vuelven a descargar cuando las veas.")) {
                        File(dir, "artwork").listFiles()?.forEach { it.delete() }; File(dir, "thumbs").deleteRecursively(); toast(a, T.t("Portadas borradas")); host.refresh()
                    }
                }
                row((T.t("Partidas guardadas") + ": " + fmt(a, saves)))
                row((T.t("Capturas pendientes") + ": " + fmt(a, shots)))
                row((T.t("Datos de juegos y trucos") + ": " + fmt(a, data)))
                row((T.t("Sesión de Telegram") + ": " + fmt(a, telegram)))
                val total = downloads + keptBytes + saves + shots + art + data + telegram
                AlertDialog.Builder(a).setTitle(T.t("Espacio usado") + " · " + fmt(a, total)).setItems(rows.map { it.first }.toTypedArray()) { _, w -> rows[w].second?.invoke() }
                    .setNegativeButton(T.t("Cerrar"), null).show()
            }
        }.start()
    }
    private fun confirm(a: Activity, title: String, msg: String, ok: () -> Unit) {
        AlertDialog.Builder(a).setTitle(title).setMessage(msg).setPositiveButton(T.t("Borrar")) { _, _ -> ok() }.setNegativeButton(T.t("Cancelar"), null).show()
    }
}
