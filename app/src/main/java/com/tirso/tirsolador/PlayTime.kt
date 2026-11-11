package com.tirso.tirsolador

import android.content.Context
import java.io.File

/** Time played per game (counted while the game is on screen). Files, so the player's own process can write it too. */
internal object PlayTime {
    private fun file(ctx: Context, id: String) = File(ctx.filesDir, "playtime/" + id.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    @JvmStatic fun get(ctx: Context, id: String): Long = runCatching { file(ctx, id).readText().trim().toLong() }.getOrDefault(0L)
    @JvmStatic fun add(ctx: Context, id: String, ms: Long) {
        if (id.isEmpty() || ms < 1000 || ms > 24L * 3600_000) return
        runCatching { val f = file(ctx, id); f.parentFile?.mkdirs(); f.writeText((get(ctx, id) + ms).toString()) }
    }
    /** "12 h 40 min", "35 min", or "" before the first minute. */
    @JvmStatic fun label(ctx: Context, id: String): String {
        val m = get(ctx, id) / 60000
        return when { m < 1 -> ""; m < 60 -> "$m min"; else -> "${m / 60} h ${m % 60} min" }
    }
}
