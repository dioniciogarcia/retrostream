package com.tirso.tirsolador

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Time played per game (counted while the game is on screen). Files, so the player's own process can write it too.
 *  Time played on your other devices (phone, TV, PC) comes from the library sync and is added to the total. */
internal object PlayTime {
    private fun name(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
    private fun file(ctx: Context, id: String) = File(ctx.filesDir, "playtime/" + name(id))
    private fun othersFile(ctx: Context) = File(ctx.filesDir, "playtime_others.json")
    private var othersCache: JSONObject? = null
    private var othersStamp = -1L
    private fun othersOf(ctx: Context, id: String): Long {
        val f = othersFile(ctx); val stamp = if (f.exists()) f.lastModified() else 0L
        if (othersCache == null || stamp != othersStamp) { othersCache = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject()); othersStamp = stamp }
        return othersCache?.optLong(name(id)) ?: 0L
    }
    /** Time on this device only. */
    @JvmStatic fun own(ctx: Context, id: String): Long = runCatching { file(ctx, id).readText().trim().toLong() }.getOrDefault(0L)
    /** Total time, all your devices together. */
    @JvmStatic fun get(ctx: Context, id: String): Long = own(ctx, id) + othersOf(ctx, id)
    @JvmStatic fun add(ctx: Context, id: String, ms: Long) {
        if (id.isEmpty() || ms < 1000 || ms > 24L * 3600_000) return
        runCatching { val f = file(ctx, id); f.parentFile?.mkdirs(); f.writeText((own(ctx, id) + ms).toString()) }
    }
    /** Saved by the library sync: play time per game on the other devices. */
    @JvmStatic fun others(ctx: Context, map: JSONObject) {
        runCatching { val f = othersFile(ctx); val tmp = File(f.path + ".tmp"); tmp.writeText(map.toString()); tmp.renameTo(f); othersCache = null }
    }
    /** "12 h 40 min", "35 min", or "" before the first minute. */
    @JvmStatic fun label(ctx: Context, id: String): String {
        val m = get(ctx, id) / 60000
        return when { m < 1 -> ""; m < 60 -> "$m min"; else -> "${m / 60} h ${m % 60} min" }
    }
}
