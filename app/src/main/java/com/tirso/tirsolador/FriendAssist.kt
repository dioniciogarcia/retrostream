package com.tirso.tirsolador

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.util.Date

/**
 * Friend assist: "Ask a friend for help" sends your save (with where you left off) to the friends group.
 * A friend taps Help: the game opens on their device with YOUR save, in a separate folder (their own saves are never touched).
 * They tap Send back when done; you tap Continue: your current save is backed up first, then theirs is put in place.
 * Nothing to type anywhere.
 */
internal object FriendAssist {
    private val main = Handler(Looper.getMainLooper())
    private fun system(ctx: Context) = File(ctx.filesDir, "bios")
    @JvmStatic fun dir(ctx: Context, req: String) = File(ctx.filesDir, "assist/" + req.replace(Regex("[^A-Za-z0-9]"), "_"))
    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx.applicationContext, s, Toast.LENGTH_LONG).show()
    private fun meta(ctx: Context, title: String, console: Console, sha: String) =
        mapOf("package" to ctx.packageName, "title" to title, "console" to console.name, "sha" to sha)

    /** Shown in a game's menu when the friends group is set up and the game has been played here. */
    @JvmStatic fun canAsk(ctx: Context, drive: TirsoDrive, g: Game) =
        drive.friendsChat() != 0L && g.console != Console.MAME && SaveBackups.shaOf(ctx, g.id) != null

    @JvmStatic fun ask(a: Activity, drive: TirsoDrive, g: Game) {
        val sha = SaveBackups.shaOf(a, g.id) ?: run { toast(a, T.t("Juega el juego una vez antes de pedir ayuda.")); return }
        val app = a.applicationContext
        toast(a, T.t("Enviando tu partida a tus amigos…"))
        Thread {
            val f = File(app.cacheDir, "assist-${System.currentTimeMillis()}.zip")
            val ok = runCatching {
                f.outputStream().use { SaveManager(SaveBackups.rootFor(app, g.console, sha), system(app)).exportZip(it, meta(app, g.title, g.console, sha), withAutoState = g.console != Console.NDS) }
                f.length() > 0
            }.getOrDefault(false)
            main.post {
                if (!ok) { toast(app, T.t("No se pudo preparar tu partida.")); return@post }
                drive.askHelp(g, f) { sent, e ->
                    f.delete()
                    toast(app, if (sent == true) T.t("Pediste ayuda a tus amigos. Te avisaremos cuando terminen.") else e ?: T.t("No se pudo enviar"))
                }
            }
        }.start()
    }

    /** "Help" (or "Play again" while helping): opens their game with their save, in a separate folder. */
    @JvmStatic fun help(a: Activity, drive: TirsoDrive, lib: Library, x: TirsoDrive.Assist, play: (Game, String) -> Unit) {
        val g = lib.match(x.gameId, x.title, x.console)
            ?: run { toast(a, T.t("Para ayudar necesitas este juego en tu biblioteca: ") + x.title); return }
        val d = dir(a, x.req)
        if (x.takenBy == drive.accountId && (d.list()?.isNotEmpty() == true)) { play(g, d.path); return }
        val app = a.applicationContext
        toast(a, T.t("Descargando la partida de ") + x.fromName.ifEmpty { T.t("tu amigo") } + "…")
        drive.takeAssist(x) { file, err ->
            if (file == null) { toast(app, err ?: T.t("No se pudo descargar")); return@takeAssist }
            Thread {
                val ok = runCatching { d.mkdirs(); file.inputStream().use { SaveManager(d, system(app)).importZip(it) } }.getOrDefault(false)
                main.post { if (ok) play(g, d.path) else toast(app, T.t("El archivo no es una copia válida de partidas.")) }
            }.start()
        }
    }

    /** "Send back": their save, after you helped. */
    @JvmStatic fun sendBack(a: Activity, drive: TirsoDrive, x: TirsoDrive.Assist, done: () -> Unit) {
        val d = dir(a, x.req); val app = a.applicationContext
        val console = runCatching { Console.valueOf(x.console) }.getOrNull() ?: return
        if (d.list()?.isNotEmpty() != true) { toast(a, T.t("Primero juega para ayudar.")); return }
        toast(a, T.t("Enviando la partida…"))
        Thread {
            val f = File(app.cacheDir, "assist-done-${System.currentTimeMillis()}.zip")
            val ok = runCatching { f.outputStream().use { SaveManager(d, system(app)).exportZip(it, meta(app, x.title, console, ""), withAutoState = console != Console.NDS) }; f.length() > 0 }.getOrDefault(false)
            main.post {
                if (!ok) { toast(app, T.t("No se pudo preparar la partida.")); return@post }
                drive.sendBack(x, f) { sent, e -> f.delete(); toast(app, if (sent == true) T.t("Partida enviada a ") + x.fromName.ifEmpty { T.t("tu amigo") } else e ?: T.t("No se pudo enviar")); done() }
            }
        }.start()
    }

    /** "Continue": your save is backed up (Telegram and on this device), then the one your friend sent back is put in place. */
    @JvmStatic fun continueAssist(a: Activity, drive: TirsoDrive, lib: Library, x: TirsoDrive.Assist, play: (Game) -> Unit) {
        val g = lib.games.firstOrNull { it.id == x.gameId } ?: lib.match(x.gameId, x.title, x.console)
            ?: run { toast(a, T.t("Ese juego ya no está en tu biblioteca.")); return }
        val sha = SaveBackups.shaOf(a, g.id) ?: run { toast(a, T.t("Juega el juego una vez antes de pedir ayuda.")); return }
        val app = a.applicationContext; val root = SaveBackups.rootFor(app, g.console, sha)
        toast(a, T.t("Descargando la partida de ") + x.helperName.ifEmpty { T.t("tu amigo") } + "…")
        drive.downloadAssistResult(x) { file, err ->
            if (file == null) { toast(app, err ?: T.t("No se pudo descargar")); return@downloadAssistResult }
            val install = Runnable {
                Thread {
                    runCatching {
                        val keep = File(app.filesDir, "assist_backups"); keep.mkdirs()
                        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.US).format(Date())
                        File(keep, g.title.replace(Regex("[^A-Za-z0-9 ._()\\-]"), "_").take(60) + " - before help $stamp.zip").outputStream().use {
                            SaveManager(root, system(app)).exportZip(it, meta(app, g.title, g.console, sha), true)
                        }
                        keep.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
                    }
                    val ok = runCatching { file.inputStream().use { SaveManager(root, system(app)).importZip(it) } }.getOrDefault(false)
                    main.post {
                        if (!ok) { toast(app, T.t("El archivo no es una copia válida de partidas.")); return@post }
                        drive.closeAssist(x)
                        toast(app, T.t("Listo. Tu partida anterior quedó guardada como copia."))
                        play(g)
                    }
                }.start()
            }
            // Your save as it was goes to your backups channel first (you can bring it back in Settings > Save backups).
            if (drive.ready()) SaveBackups.upload(app, drive, SaveBackups.Entry(root, sha, g.title, g.console), false) { _, _ -> install.run() }
            else install.run()
        }
    }

    /** Folders of requests that ended, and any older than two weeks. */
    @JvmStatic fun cleanup(ctx: Context, drive: TirsoDrive) {
        for (req in drive.assistGone) dir(ctx, req).deleteRecursively()
        File(ctx.filesDir, "assist").listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 14L * 86_400_000L) it.deleteRecursively() }
    }
}
