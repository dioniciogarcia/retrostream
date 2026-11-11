package com.tirso.tirsolador

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile

/**
 * Phone app: games found in folders on the phone, and the phone-only options
 * (keep downloaded games, Wi-Fi only, lock sideways, GPU driver).
 */
internal object PhoneGames {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("phone_games", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ options
    @JvmStatic fun keep(ctx: Context) = BuildConfig.PHONE && prefs(ctx).getBoolean("keep", false)
    @JvmStatic fun keep(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("keep", on).apply()
    @JvmStatic fun wifiOnly(ctx: Context) = BuildConfig.PHONE && prefs(ctx).getBoolean("wifi_only", false)
    @JvmStatic fun wifiOnly(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("wifi_only", on).apply()
    @JvmStatic fun lockLandscape(ctx: Context) = BuildConfig.PHONE && prefs(ctx).getBoolean("lock_landscape", false)
    @JvmStatic fun lockLandscape(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("lock_landscape", on).apply()
    @JvmStatic fun scanOn(ctx: Context) = prefs(ctx).getBoolean("scan", true)
    @JvmStatic fun scanOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("scan", on).apply()

    /** True when the phone is on Wi-Fi (or another network that doesn't use mobile data). */
    @JvmStatic fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    // ------------------------------------------------------------------ kept Telegram games
    /** Remembers a Telegram game kept on the phone: id -> "fileId|account|path". */
    @JvmStatic fun remember(ctx: Context, game: Game, path: String) {
        ctx.getSharedPreferences("kept_games", Context.MODE_PRIVATE).edit().putString(game.id, "${game.fileId}|${game.telegramAccount}|$path").apply()
    }
    /** {game id, file id, account, path, size} for every kept game whose file is still there. */
    @JvmStatic fun kept(ctx: Context): List<Array<Any>> {
        val p = ctx.getSharedPreferences("kept_games", Context.MODE_PRIVATE)
        val out = mutableListOf<Array<Any>>()
        for ((id, v) in p.all) {
            val parts = (v as? String)?.split('|', limit = 3) ?: continue
            if (parts.size < 3) continue
            val f = File(parts[2]); if (!f.isFile) { p.edit().remove(id).apply(); continue }
            out += arrayOf(id, parts[0].toIntOrNull() ?: 0, parts[1].toLongOrNull() ?: 0L, parts[2], f.length())
        }
        return out.sortedByDescending { it[4] as Long }
    }
    @JvmStatic fun forget(ctx: Context, id: String) = ctx.getSharedPreferences("kept_games", Context.MODE_PRIVATE).edit().remove(id).apply()
    @JvmStatic fun isKept(ctx: Context, path: String): Boolean =
        ctx.getSharedPreferences("kept_games", Context.MODE_PRIVATE).all.values.any { (it as? String)?.endsWith("|$path") == true }

    // ------------------------------------------------------------------ phone folders
    @JvmStatic fun folders(ctx: Context): List<String> = prefs(ctx).getStringSet("folders", emptySet())!!.sorted()
    @JvmStatic fun addFolder(ctx: Context, path: String) = prefs(ctx).edit().putStringSet("folders", folders(ctx).toMutableSet().apply { add(path) }).apply()
    @JvmStatic fun removeFolder(ctx: Context, path: String) = prefs(ctx).edit().putStringSet("folders", folders(ctx).toMutableSet().apply { remove(path) }).apply()

    /** The folder path behind a folder chosen in Android's picker (internal storage or SD card). */
    @JvmStatic fun pathOf(tree: Uri): String? {
        val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val volume = id.substringBefore(':'); val rel = id.substringAfter(':', "")
        val base = if (volume.equals("primary", true)) android.os.Environment.getExternalStorageDirectory().absolutePath else "/storage/$volume"
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    @JvmStatic fun canReadFolders() = android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()

    /** A local game's file, or null. */
    @JvmStatic fun path(ctx: Context, id: String): String? = ctx.getSharedPreferences("local_roms", Context.MODE_PRIVATE).getString(id, null)

    /** The console picked by hand for a file the scan couldn't sort. */
    @JvmStatic fun choose(ctx: Context, path: String, console: Console) = prefs(ctx).edit().putString("choice_$path", console.name).apply()

    class Result(val games: List<Game>, val unsorted: List<String>)

    /** Scans the folders (several levels deep) and sorts each game file by console. */
    @JvmStatic fun scan(ctx: Context): Result {
        val games = mutableListOf<Game>(); val unsorted = mutableListOf<String>(); val paths = mutableMapOf<String, String>()
        val p = prefs(ctx); val seen = HashSet<String>()
        // Tracks of BIN/CUE discs are listed by their .cue, not on their own.
        fun walk(dir: File, depth: Int) {
            val list = dir.listFiles() ?: return
            val cueTracks = HashSet<String>()
            list.filter { it.isFile && it.name.endsWith(".cue", true) }.forEach { cue ->
                runCatching { cue.readLines().forEach { l -> Regex("""^\s*FILE\s+"?(.+?)"?\s+\S+\s*$""", RegexOption.IGNORE_CASE).find(l)?.let { cueTracks += it.groupValues[1].substringAfterLast('/').lowercase() } } }
            }
            for (f in list.sortedBy { it.name.lowercase() }) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) { if (depth < 6) walk(f, depth + 1); continue }
                if (f.name.lowercase() in cueTracks) continue
                if (!seen.add(f.absolutePath)) continue
                val chosen = p.getString("choice_${f.absolutePath}", null)?.let { runCatching { Console.valueOf(it) }.getOrNull() }
                val console = chosen ?: detect(f)
                if (console == null) { if (looksLikeGame(f)) unsorted += f.absolutePath; continue }
                if (!Console.all().contains(console)) continue
                val stem = f.name.substringBeforeLast('.').trim()
                val id = "local:" + GameStorage.hash(f.absolutePath.toByteArray()).take(24)
                games += Game(id, stem, console, T.t("En tu teléfono · ") + f.name, stem, 0, 0, 0, 0, f.name, f.length())
                paths[id] = f.absolutePath
            }
        }
        for (folder in folders(ctx)) runCatching { walk(File(folder), 0) }
        val store = ctx.getSharedPreferences("local_roms", Context.MODE_PRIVATE).edit().clear()
        paths.forEach { (k, v) -> store.putString(k, v) }
        store.apply()
        return Result(games, unsorted)
    }

    private val GAME_EXT = setOf("nes", "fds", "sfc", "smc", "md", "gen", "smd", "gb", "gbc", "gba", "n64", "z64", "v64", "nds",
        "iso", "cso", "chd", "zso", "pbp", "cue", "gcm", "gcz", "rvz", "wia", "wbfs", "ciso", "zip", "7z")
    private fun looksLikeGame(f: File) = f.extension.lowercase() in GAME_EXT && f.length() > 16 * 1024

    /** Console from the extension, the file's own header, or the folder's name. */
    @JvmStatic fun detect(f: File): Console? {
        val ext = f.extension.lowercase()
        val byExt = when (ext) {
            "nes", "fds", "unf", "unif" -> Console.NES; "sfc", "smc" -> Console.SNES; "md", "gen", "smd" -> Console.MD
            "gb" -> Console.GB; "gbc" -> Console.GBC; "gba" -> Console.GBA; "n64", "z64", "v64" -> Console.N64
            "nds" -> Console.NDS; "wbfs" -> Console.WII; "gcm" -> Console.GC; "pbp" -> Console.PSP; "cue" -> Console.PS2
            else -> null
        }
        if (byExt != null) return byExt
        val byHeader = when (ext) {
            "iso", "rvz", "wia", "gcz" -> runCatching { header(f) }.getOrNull()
            "zip" -> runCatching { insideZip(f) }.getOrNull()
            else -> null
        }
        return byHeader ?: byFolder(f)
    }

    private fun byFolder(f: File): Console? {
        val path = f.parentFile?.absolutePath?.lowercase()?.replace(Regex("[^a-z0-9/]"), "") ?: return null
        val ext = f.extension.lowercase()
        for (part in path.split('/').reversed()) {
            when (part) {
                "psp", "playstationportable" -> return Console.PSP
                "ps2", "playstation2" -> return Console.PS2
                "gc", "gamecube", "ngc" -> return Console.GC
                "wii" -> return Console.WII
                "gba" -> if (ext == "zip") return Console.GBA
                "snes" -> if (ext == "zip") return Console.SNES
                "nes" -> if (ext == "zip") return Console.NES
                "n64" -> if (ext == "zip") return Console.N64
                "nds", "ds" -> if (ext == "zip") return Console.NDS
            }
        }
        return null
    }

    private fun header(f: File): Console? {
        RandomAccessFile(f, "r").use { r ->
            val b = ByteArray(0x8100); r.readFully(b, 0, minOf(b.size.toLong(), r.length()).toInt())
            fun u32(o: Int) = ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)
            val magic = String(b, 0, 4, Charsets.ISO_8859_1)
            if (magic == "RVZ\u0001" || magic == "WIA\u0001") return when (u32(0x48)) { 1 -> Console.GC; 2 -> Console.WII; else -> null }
            if (u32(0) == 0x01C00BB1) return if (b[4].toInt() == 1) Console.WII else Console.GC   // GCZ (little-endian header)
            if (u32(0x1C) == 0xC2339F3D.toInt()) return Console.GC
            if (u32(0x18) == 0x5D1C9EA3) return Console.WII
            if (String(b, 0x8001, 5, Charsets.ISO_8859_1) == "CD001") {
                val system = String(b, 0x8008, 32, Charsets.ISO_8859_1)
                if (system.contains("PSP GAME")) return Console.PSP
                if (system.contains("PLAYSTATION")) return Console.PS2
            }
        }
        return null
    }

    private fun insideZip(f: File): Console? {
        java.util.zip.ZipFile(f).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                val ext = e.name.substringAfterLast('.', "").lowercase()
                when (ext) {
                    "nes", "fds" -> return Console.NES; "sfc", "smc" -> return Console.SNES; "md", "gen", "smd" -> return Console.MD
                    "gb" -> return Console.GB; "gbc" -> return Console.GBC; "gba" -> return Console.GBA
                    "n64", "z64", "v64" -> return Console.N64; "nds" -> return Console.NDS
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ GPU driver (Turnip and others)
    @JvmStatic fun driverDir(ctx: Context) = File(ctx.filesDir, "gpu_user")
    /** The driver chosen in Settings ("" when none). */
    @JvmStatic fun driverName(ctx: Context): String = prefs(ctx).getString("driver_name", "") ?: ""
    /** Unpacks a driver zip (an adrenotools package: meta.json + a .so); returns an error message or null. */
    @JvmStatic fun addDriver(ctx: Context, uri: Uri, name: String): String? {
        val dir = driverDir(ctx); dir.deleteRecursively(); dir.mkdirs()
        var so = false
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            java.util.zip.ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val n = e.name.substringAfterLast('/')
                    if (e.isDirectory || n.isEmpty() || e.name.contains("..")) continue
                    File(dir, n).outputStream().use { zip.copyTo(it) }
                    if (n.endsWith(".so")) so = true
                }
            }
        } ?: return T.t("No se pudo leer el archivo.")
        if (!so) { dir.deleteRecursively(); return T.t("Ese ZIP no contiene un controlador (.so).") }
        File(dir, ".ready").writeText("ok")
        prefs(ctx).edit().putString("driver_name", name.removeSuffix(".zip")).apply()
        return null
    }
    @JvmStatic fun removeDriver(ctx: Context) { driverDir(ctx).deleteRecursively(); prefs(ctx).edit().remove("driver_name").apply() }
}
