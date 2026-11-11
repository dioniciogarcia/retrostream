package com.tirso.tirsolador

import android.content.Context
import android.net.Uri
import java.io.File

/** BIOS files each person adds from their own console (Settings > BIOS files). */
internal object Bios {
    private fun ps2Dir(ctx: Context) = File(ctx.filesDir, "bios/pcsx2/bios").apply { mkdirs() }

    /** The PS2 BIOS added, or null. */
    @JvmStatic fun ps2(ctx: Context): File? = ps2Dir(ctx).listFiles()?.firstOrNull { it.isFile && it.length() > 0 }

    /** Copies a PS2 BIOS chosen on the phone; returns an error message, or null when it worked. */
    @JvmStatic fun addPs2(ctx: Context, uri: Uri): String? {
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "ps2-bios.bin"
        val safe = name.replace(Regex("[^A-Za-z0-9._()-]"), "_").ifBlank { "ps2-bios.bin" }
        val tmp = File(ctx.cacheDir, "bios.part")
        val size = ctx.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out ->
                val b = ByteArray(65536); var total = 0L
                while (true) { val n = input.read(b); if (n < 0) break; total += n; if (total > 16L * 1024 * 1024) break; out.write(b, 0, n) }
                total
            }
        } ?: return T.t("No se pudo leer el archivo.")
        // PS2 BIOS dumps are 4 MB (a few are a little bigger with extra ROM parts).
        if (size < 1024 * 1024 || size > 16L * 1024 * 1024) { tmp.delete(); return T.t("Eso no parece una BIOS de PS2 (suelen pesar 4 MB).") }
        ps2Dir(ctx).listFiles()?.forEach { it.delete() }
        if (!tmp.renameTo(File(ps2Dir(ctx), safe))) { tmp.copyTo(File(ps2Dir(ctx), safe), true); tmp.delete() }
        return null
    }

    @JvmStatic fun removePs2(ctx: Context) { ps2Dir(ctx).listFiles()?.forEach { it.delete() } }
}
