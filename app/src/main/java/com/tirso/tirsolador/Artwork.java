package com.tirso.tirsolador;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Artwork {
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private final File directory;
    Artwork(Context context) { directory = new File(context.getCacheDir(), "artwork"); directory.mkdirs(); }
    private static String path(String s) { return android.net.Uri.encode(s); }
    static String url(Game g) {
        return "https://thumbnails.libretro.com/" + path(g.console.thumbnailSystem)
                + "/Named_Boxarts/" + path(g.coverName) + ".png";
    }
    void load(Game game, ImageView view) {
        String url = url(game); view.setTag(url);
        Bitmap cached = memory.get(url); if (cached != null) { view.setImageBitmap(cached); reveal(view); return; }
        pool.execute(() -> {
            HttpURLConnection connection = null;
            try {
                StringBuilder hash = new StringBuilder();
                for (byte b : MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8)))
                    hash.append(String.format(java.util.Locale.ROOT, "%02x", b));
                File file = new File(directory, hash + ".png");
                byte[] bytes;
                if (file.isFile()) { try (InputStream in = new java.io.FileInputStream(file)) { bytes = Library.readLimited(in, 4_000_000); } }
                else {
                    connection = (HttpURLConnection) new java.net.URL(url).openConnection();
                    connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
                    connection.setInstanceFollowRedirects(false);
                    if (connection.getResponseCode() != 200) return;
                    try (InputStream in = connection.getInputStream()) { bytes = Library.readLimited(in, 4_000_000); }
                }
                BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return;
                int sample = 1; while (opts.outWidth / sample > 700 || opts.outHeight / sample > 700) sample *= 2;
                opts.inJustDecodeBounds = false; opts.inSampleSize = sample;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                if (bitmap == null) return;
                memory.put(url, bitmap);
                if (!file.exists()) {
                    long size = 0; File[] files = directory.listFiles();
                    if (files != null) for (File f : files) size += f.length();
                    if (size < 64L * 1024 * 1024) try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
                }
                ui.post(() -> { if (url.equals(view.getTag())) { view.setImageBitmap(bitmap); reveal(view); } });
            } catch (Exception ignored) { /* Placeholder remains usable offline. */ }
            finally { if (connection != null) connection.disconnect(); }
        });
    }
    /** Once the cover is showing, hide the title placeholder behind it so the text can't show through. */
    private static void reveal(ImageView view) {
        if (view.getParent() instanceof android.widget.FrameLayout) {
            android.widget.FrameLayout frame = (android.widget.FrameLayout) view.getParent();
            for (int i = 0; i < frame.getChildCount(); i++) if (frame.getChildAt(i) != view) frame.getChildAt(i).setVisibility(android.view.View.INVISIBLE);
        }
    }
    void close() { pool.shutdownNow(); ui.removeCallbacksAndMessages(null); memory.evictAll(); }
}
