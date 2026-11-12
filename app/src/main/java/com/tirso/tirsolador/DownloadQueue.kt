package com.tirso.tirsolador

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Phone: games downloaded one after another and kept on the phone, to play later without a connection.
 * Lives as long as the app is open.
 */
internal object DownloadQueue {
    private val main = Handler(Looper.getMainLooper())
    private val waiting = ArrayList<Game>()
    @Volatile var current: Game? = null; private set
    var done = 0; private set
    private var app: Context? = null
    private var drive: TirsoDrive? = null
    private var ticks = 0

    @JvmStatic fun list(): List<Game> = listOfNotNull(current) + waiting
    @JvmStatic fun contains(g: Game) = current?.id == g.id || waiting.any { it.id == g.id }

    @JvmStatic fun add(ctx: Context, d: TirsoDrive, games: List<Game>): Int {
        app = ctx.applicationContext; drive = d
        var n = 0
        for (g in games) if (g.telegramAccount != 0L && !contains(g)) { waiting += g; n++ }
        if (current == null) next()
        return n
    }
    @JvmStatic fun cancelAll() {
        waiting.clear()
        current?.let { g -> drive?.cancelDownload(g) }
        current = null
    }
    /** "2 of 5 · Mario Kart 64 · 40%" or "" when nothing is downloading. */
    @JvmStatic fun status(): String {
        val g = current ?: return ""
        val f = drive?.file(g)
        val pct = if (f != null && f.expectedSize > 0) " · " + (f.local.downloadedSize * 100 / f.expectedSize) + "%" else ""
        return T.t("Descargando ") + g.title + pct + if (waiting.isNotEmpty()) " · " + waiting.size + T.t(" en espera") else ""
    }

    private fun next() {
        val ctx = app ?: return; val d = drive ?: return
        if (waiting.isEmpty()) {
            current = null
            if (done > 0) Toast.makeText(ctx, T.t("Descargas terminadas: ") + done + T.t(" juegos listos sin conexión"), Toast.LENGTH_LONG).show()
            done = 0; return
        }
        val g = waiting.removeAt(0)
        if (PhoneGames.kept(ctx).any { it[0] == g.id }) { done++; next(); return }
        if (PhoneGames.wifiOnly(ctx) && !PhoneGames.onWifi(ctx)) {
            Toast.makeText(ctx, T.t("Solo Wi-Fi está activado: las descargas esperan a una red Wi-Fi."), Toast.LENGTH_LONG).show()
            waiting.add(0, g); current = null; return
        }
        current = g; ticks = 0
        d.inspectFile(g); d.download(g)
        main.postDelayed(::check, 1500)
    }
    private fun check() {
        val g = current ?: return; val ctx = app ?: return; val d = drive ?: return
        val f = d.file(g)
        if (f != null && f.local.isDownloadingCompleted && java.io.File(f.local.path).isFile) {
            PhoneGames.remember(ctx, g, f.local.path); done++; next(); return
        }
        // No progress for ~10 minutes (lost connection, Telegram refused): skip it.
        val moving = f != null && (f.local.isDownloadingActive || f.local.downloadedSize > 0)
        if (!moving && ++ticks > 400) { Toast.makeText(ctx, T.t("No se pudo descargar: ") + g.title, Toast.LENGTH_LONG).show(); next(); return }
        main.postDelayed(::check, 1500)
    }
}
