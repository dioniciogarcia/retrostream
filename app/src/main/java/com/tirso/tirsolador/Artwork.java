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
    Artwork(Context context) { appContext = context.getApplicationContext(); directory = new File(context.getCacheDir(), "artwork"); directory.mkdirs(); }
    private static String path(String s) { return android.net.Uri.encode(s); }
    static String url(Game g) {
        return "https://thumbnails.libretro.com/" + path(g.console.thumbnailSystem)
                + "/Named_Boxarts/" + path(g.coverName) + ".png";
    }
    /** Where the cover of a game is kept once it has been shown (null name = none). */
    static File cacheFile(Context context, Console console, String coverName) {
        try {
            String url = "https://thumbnails.libretro.com/" + path(console.thumbnailSystem) + "/Named_Boxarts/" + path(coverName) + ".png";
            StringBuilder hash = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8)))
                hash.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return new File(new File(context.getCacheDir(), "artwork"), hash + ".png");
        } catch (Exception e) { return null; }
    }
    private final Context appContext;
    private static String urlFor(Console console, String name) {
        return "https://thumbnails.libretro.com/" + path(console.thumbnailSystem) + "/Named_Boxarts/" + path(name) + ".png";
    }
    private static String hashOf(String url) throws Exception {
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8)))
            hash.append(String.format(java.util.Locale.ROOT, "%02x", b));
        return hash.toString();
    }
    /** The cached cover of a game (any of the names libretro may know it by), or null. */
    File cachedCover(Game game) {
        try {
            for (String name : GameMeta.coverNames(appContext, game)) {
                File f = new File(directory, hashOf(urlFor(game.console, name)) + ".png");
                if (f.isFile()) return f;
            }
        } catch (Exception ignored) { }
        return null;
    }
    void load(Game game, ImageView view) {
        final String tag = game.id + "|" + game.coverName; view.setTag(tag);
        Bitmap cached = memory.get(tag); if (cached != null) { view.setImageBitmap(cached); reveal(view); return; }
        pool.execute(() -> {
            try {
                byte[] bytes = null;
                for (String name : GameMeta.coverNames(appContext, game)) {
                    String url = urlFor(game.console, name);
                    String hash = hashOf(url);
                    File file = new File(directory, hash + ".png"), miss = new File(directory, hash + ".miss");
                    if (file.isFile()) { try (InputStream in = new java.io.FileInputStream(file)) { bytes = Library.readLimited(in, 4_000_000); } break; }
                    if (miss.exists()) continue;
                    HttpURLConnection connection = null;
                    try {
                        connection = (HttpURLConnection) new java.net.URL(url).openConnection();
                        connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
                        connection.setInstanceFollowRedirects(false);
                        int code = connection.getResponseCode();
                        if (code == 404) { miss.createNewFile(); continue; }
                        if (code != 200) return;
                        try (InputStream in = connection.getInputStream()) { bytes = Library.readLimited(in, 4_000_000); }
                    } finally { if (connection != null) connection.disconnect(); }
                    long size = 0; File[] files = directory.listFiles();
                    if (files != null) for (File f : files) size += f.length();
                    if (size < 64L * 1024 * 1024) try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
                    break;
                }
                if (bytes == null) return;
                BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return;
                int sample = 1; while (opts.outWidth / sample > 700 || opts.outHeight / sample > 700) sample *= 2;
                opts.inJustDecodeBounds = false; opts.inSampleSize = sample;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                if (bitmap == null) return;
                memory.put(tag, bitmap);
                ui.post(() -> { if (tag.equals(view.getTag())) { view.setImageBitmap(bitmap); reveal(view); } });
            } catch (Exception ignored) { /* Placeholder remains usable offline. */ }
        });
    }
    /** Screenshot / title screen of a game from libretro (null when there is none). */
    Bitmap snapshot(Game game, String kind) {
        try {
            for (String name : GameMeta.coverNames(appContext, game)) {
                String url = "https://thumbnails.libretro.com/" + path(game.console.thumbnailSystem) + "/" + kind + "/" + path(name) + ".png";
                String hash = hashOf(url);
                File file = new File(directory, hash + ".png"), miss = new File(directory, hash + ".miss");
                byte[] bytes = null;
                if (file.isFile()) { try (InputStream in = new java.io.FileInputStream(file)) { bytes = Library.readLimited(in, 4_000_000); } }
                else if (!miss.exists()) {
                    HttpURLConnection c = (HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setInstanceFollowRedirects(false);
                    try {
                        int code = c.getResponseCode();
                        if (code == 404) { miss.createNewFile(); continue; }
                        if (code != 200) return null;
                        try (InputStream in = c.getInputStream()) { bytes = Library.readLimited(in, 4_000_000); }
                        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
                    } finally { c.disconnect(); }
                }
                if (bytes != null) return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
        } catch (Exception ignored) { }
        return null;
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
