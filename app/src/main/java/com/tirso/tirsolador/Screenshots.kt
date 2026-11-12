package com.tirso.tirsolador

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import org.json.JSONObject
import java.io.File

/**
 * Screenshots: saved to the phone's gallery (Pictures/RetroStream) and queued for the Telegram channel
 * "TD - RetroStream - Screenshots". The queue is sent from the app's menus (the player may run in its own process).
 */
internal object Screenshots {
    private val main = Handler(Looper.getMainLooper())
    private fun queue(ctx: Context) = File(ctx.filesDir, "screenshots/pending").apply { mkdirs() }

    fun capture(activity: Activity, view: SurfaceView, gameId: String, title: String, console: Console, done: (Boolean) -> Unit) {
        val w = view.width; val h = view.height
        if (w <= 0 || h <= 0) { done(false); return }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(view, bmp, { result ->
                if (result != PixelCopy.SUCCESS) { done(false); return@request }
                Thread {
                    val ok = runCatching { save(activity.applicationContext, bmp, gameId, title, console) }.isSuccess
                    main.post { done(ok) }
                }.start()
            }, main)
        } catch (e: Exception) { done(false) }
    }

    private fun save(ctx: Context, bmp: Bitmap, gameId: String, title: String, console: Console) {
        val stamp = System.currentTimeMillis()
        val safe = title.replace(Regex("[^A-Za-z0-9 ._()-]"), "_").take(60)
        // Gallery copy.
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$safe $stamp.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RetroStream")
            }
            ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        // Telegram queue (JPEG keeps uploads quick; Telegram recompresses photos anyway).
        val dir = queue(ctx)
        File(dir, "$stamp.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        File(dir, "$stamp.json").writeText(JSONObject().put("id", gameId).put("title", title).put("console", console.name)
            .put("w", bmp.width).put("h", bmp.height).toString())
    }

    /** Sends the queued screenshots to the screenshots channel, one at a time. */
    @JvmStatic fun uploadPending(ctx: Context, drive: TirsoDrive) {
        if (busy) { again = true; return }   // a screenshot taken while sending goes right after
        if (!drive.ready()) return
        val items = queue(ctx).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: return
        if (items.isEmpty()) return
        busy = true
        drive.shotsChannel { chat, _ ->
            if (chat == null || chat == 0L) { busy = false; return@shotsChannel }
            fun next(i: Int) {
                if (i >= items.size) { busy = false; if (again) { again = false; uploadPending(ctx, drive) }; return }
                val meta = items[i]; val pic = File(meta.path.removeSuffix(".json") + ".jpg")
                val j = runCatching { JSONObject(meta.readText()) }.getOrNull()
                if (j == null || !pic.isFile) { meta.delete(); pic.delete(); next(i + 1); return }
                val caption = "#RSSHOT\nid:${j.optString("id")}\ntitle:${j.optString("title")}\nconsole:${j.optString("console")}\nv:1"
                drive.uploadShot(chat, pic, j.optInt("w"), j.optInt("h"), caption) { _, err ->
                    if (err == null) { meta.delete(); pic.delete() }
                    next(i + 1)
                }
            }
            next(0)
        }
    }
    private var busy = false
    private var again = false

    /** Shares a screenshot with another app (WhatsApp, Messages…) through Android's share sheet. */
    @JvmStatic fun shareToApps(activity: Activity, picture: String, title: String) {
        runCatching {
            val dir = File(activity.cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = File(dir, title.replace(Regex("[^A-Za-z0-9 ._()-]"), "_").take(50).ifBlank { "RetroStream" } + ".jpg")
            File(picture).copyTo(out, overwrite = true)
            val uri = androidx.core.content.FileProvider.getUriForFile(activity, activity.packageName + ".share", out)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/jpeg")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri).putExtra(android.content.Intent.EXTRA_TEXT, title + " · RetroStream")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(android.content.Intent.createChooser(send, T.t("Compartir captura")))
        }.onFailure { android.widget.Toast.makeText(activity, T.t("No se pudo compartir la captura."), android.widget.Toast.LENGTH_LONG).show() }
    }
    /** Sends a screenshot to the friends group. */
    @JvmStatic fun shareToFriends(activity: Activity, drive: TirsoDrive, picture: String, title: String) {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }; android.graphics.BitmapFactory.decodeFile(picture, o)
        drive.sendPhotoToFriends(picture, o.outWidth, o.outHeight, "📸 " + title) { ok, err ->
            android.widget.Toast.makeText(activity, if (ok == true) T.t("Enviado al grupo de amigos") else (err ?: T.t("No se pudo enviar")), android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
