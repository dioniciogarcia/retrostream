package com.tirso.tirsolador

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Save backups live in a private Telegram channel ("TD - RetroStream - Save file back-ups") as ZIP files.
 * Every zip carries a caption (#RSSAVE / sha / title / console) so the app knows which game it belongs to;
 * the sha is the game file's SHA-256, so the same game matches on any device or account.
 */
object SaveBackups {
    class Entry(val root: File, val sha: String, val title: String, val console: Console)

    private val main = Handler(Looper.getMainLooper())
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("save_backups", Context.MODE_PRIVATE)
    private fun system(ctx: Context) = File(ctx.filesDir, "bios")

    @JvmStatic fun rootFor(ctx: Context, console: Console, sha: String) = File(ctx.filesDir, "saves/${Presets.key(console)}/$sha")

    @JvmStatic fun writeMeta(root: File, title: String, console: Console) {
        runCatching {
            root.mkdirs()
            File(root, "meta.json").writeText(org.json.JSONObject().put("title", title).put("console", console.name).toString())
        }
    }

    /** Every game that has a save folder and a known name. */
    @JvmStatic fun entries(ctx: Context): List<Entry> {
        val out = mutableListOf<Entry>()
        File(ctx.filesDir, "saves").listFiles()?.forEach { keyDir ->
            keyDir.listFiles()?.forEach { root ->
                val meta = File(root, "meta.json")
                if (!meta.isFile) return@forEach
                runCatching {
                    val j = org.json.JSONObject(meta.readText())
                    out += Entry(root, root.name, j.getString("title"), Console.valueOf(j.getString("console")))
                }
            }
        }
        return out
    }

    private fun manager(ctx: Context, e: Entry) = SaveManager(e.root, system(ctx))
    @JvmStatic fun changed(ctx: Context, e: Entry): Boolean {
        val last = manager(ctx, e).lastChange()
        return last > 0 && last > prefs(ctx).getLong("done_${e.sha}", 0L)
    }

    @JvmStatic fun caption(e: Entry) = "#RSSAVE\nsha:${e.sha}\ntitle:${e.title}\nconsole:${e.console.name}\nv:1"

    private fun zipFile(ctx: Context, e: Entry): File? {
        val sm = manager(ctx, e)
        if (sm.lastChange() == 0L) return null
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.US).format(Date())
        val safe = e.title.replace(Regex("[^A-Za-z0-9 ._()\\-]"), "_").take(80)
        val f = File(ctx.cacheDir, "$safe (${e.console.name}) - saves $stamp.zip")
        f.outputStream().use { sm.exportZip(it, mapOf("package" to ctx.packageName, "title" to e.title, "console" to e.console.name, "sha" to e.sha), withAutoState = false) }
        return f
    }

    private fun post(cb: (Boolean, String) -> Unit, ok: Boolean, msg: String) { main.post { cb(ok, msg) } }

    /** Zips one game's saves and sends the zip to the channel. [cb] runs on the main thread. */
    @JvmStatic fun upload(ctx: Context, drive: TirsoDrive, e: Entry, prune: Boolean, cb: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        if (!drive.ready()) { cb(false, T.t("Conecta TirsoDrive para usar las copias de partidas en Telegram.")); return }
        Thread {
            val zip = runCatching { zipFile(app, e) }.getOrNull()
            val stamp = manager(app, e).lastChange()
            main.post {
                if (zip == null) { cb(false, T.t("Este juego aún no tiene partidas guardadas.")); return@post }
                drive.backupChannel { chat, err ->
                    if (err != null) { zip.delete(); cb(false, err); return@backupChannel }
                    drive.uploadBackup(chat, zip, caption(e)) { _, err2 ->
                        zip.delete()
                        if (err2 != null) { cb(false, err2); return@uploadBackup }
                        prefs(app).edit().putLong("done_${e.sha}", stamp).apply()
                        if (prune) drive.listBackups(chat) { list, _ -> if (list != null) drive.pruneBackups(chat, list) }
                        cb(true, T.t("Copia guardada en Telegram"))
                    }
                }
            }
        }.start()
    }

    /** Sends every game with new saves; one summary callback at the end. */
    @JvmStatic fun uploadAll(ctx: Context, drive: TirsoDrive, progress: (String) -> Unit, cb: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        val todo = entries(app).filter { changed(app, it) }
        if (!drive.ready()) { cb(false, T.t("Conecta TirsoDrive para usar las copias de partidas en Telegram.")); return }
        if (todo.isEmpty()) { cb(true, T.t("Todo está al día: no hay partidas nuevas que copiar.")); return }
        var okCount = 0; var failed = ""
        fun next(i: Int) {
            if (i >= todo.size) {
                drive.backupChannel { chat, _ -> if (chat != 0L) drive.listBackups(chat) { l, _ -> if (l != null) drive.pruneBackups(chat, l) } }
                cb(failed.isEmpty(), if (failed.isEmpty()) "$okCount ${T.t("copias guardadas en Telegram")}" else "$okCount ${T.t("copias guardadas")} · $failed")
                return
            }
            progress("${i + 1}/${todo.size} · ${todo[i].title}")
            upload(app, drive, todo[i], false) { ok, msg -> if (ok) okCount++ else failed = msg; next(i + 1) }
        }
        next(0)
    }

    /** Newest backups first. [sha] limits the list to one game. */
    @JvmStatic fun fetch(drive: TirsoDrive, sha: String?, cb: (List<TirsoDrive.Backup>?, String?) -> Unit) {
        if (!drive.ready()) { cb(null, T.t("Conecta TirsoDrive para usar las copias de partidas en Telegram.")); return }
        drive.backupChannel { chat, err ->
            if (err != null) { cb(null, err); return@backupChannel }
            drive.listBackups(chat) { list, err2 -> cb(list?.filter { sha == null || it.sha == sha }, err2) }
        }
    }

    /** Newest backup of every game. */
    @JvmStatic fun latestPerGame(all: List<TirsoDrive.Backup>): List<TirsoDrive.Backup> =
        all.groupBy { it.sha }.map { (_, l) -> l.maxByOrNull { it.date }!! }.sortedBy { it.title.lowercase() }

    /** Downloads one backup zip, reads it and puts the files back where the game keeps its saves. */
    @JvmStatic fun restore(ctx: Context, drive: TirsoDrive, b: TirsoDrive.Backup, cb: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        val console = runCatching { Console.valueOf(b.console) }.getOrNull()
        if (console == null) { cb(false, T.t("Esta copia es de una consola que no conozco.")); return }
        drive.downloadBackup(b) { file, err ->
            if (file == null) { cb(false, err ?: T.t("No se pudo descargar la copia.")); return@downloadBackup }
            Thread {
                val ok = runCatching {
                    val root = rootFor(app, console, b.sha)
                    file.inputStream().use { SaveManager(root, system(app)).importZip(it) }.also { if (it) writeMeta(root, b.title, console) }
                }.getOrDefault(false)
                main.post {
                    drive.releaseBackup(b)
                    if (ok) prefs(app).edit().putLong("done_${b.sha}", manager(app, Entry(rootFor(app, console, b.sha), b.sha, b.title, console)).lastChange()).apply()
                    cb(ok, if (ok) T.t("Partidas restauradas") else T.t("El archivo no es una copia válida de partidas."))
                }
            }.start()
        }
    }

    @JvmStatic fun describe(ctx: Context, b: TirsoDrive.Backup): String {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(b.date * 1000L))
        return if (b.size > 0) date + " · " + android.text.format.Formatter.formatShortFileSize(ctx, b.size) else date
    }
}
