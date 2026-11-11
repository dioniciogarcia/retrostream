package com.tirso.tirsolador;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
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
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private final ExecutorService disk = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(Math.max(24, (int) (Runtime.getRuntime().maxMemory() / (1024 * 1024) / 6)) * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private final File directory;
    Artwork(Context context) { appContext = context.getApplicationContext(); directory = new File(context.getFilesDir(), "artwork"); directory.mkdirs();
        // Pictures used to live in the cache folder, which Android may empty at any time: move them to permanent storage.
        File old = new File(context.getCacheDir(), "artwork"); File[] found = old.listFiles();
        if (found != null) new Thread(() -> { for (File f : found) { File to = new File(directory, f.getName()); if (!to.exists()) f.renameTo(to); else f.delete(); } old.delete(); }, "art-migrate").start(); }
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
            return new File(new File(context.getFilesDir(), "artwork"), hash + ".png");
        } catch (Exception e) { return null; }
    }
    private final Context appContext;
    private static String urlFor(Console console, String name) { return urlFor(console, name, "Named_Boxarts"); }
    private static String urlFor(Console console, String name, String kind) {
        return "https://thumbnails.libretro.com/" + path(console.thumbnailSystem) + "/" + kind + "/" + path(name) + ".png";
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
    void load(Game game, ImageView view) { load(game, view, "Named_Boxarts"); }
    void load(Game game, ImageView view, String kind) { loadFirst(game, view, kind); }
    /** Tries the picture kinds in order (e.g. logo, then title screen, then box art) and shows the first one libretro has. */
    void loadFirst(Game game, ImageView view, String... kinds) {
        final String tag = game.id + "|" + game.coverName + "|" + String.join(",", kinds); view.setTag(tag);
        final int maxDim = kinds.length > 1 ? 360 : 700;
        Bitmap cached = memory.get(tag); if (cached != null) { view.setImageBitmap(cached); reveal(view); return; }
        // 1) pictures already saved on the device appear straight away, never waiting behind downloads
        disk.execute(() -> {
            try {
                byte[] bytes = null; boolean allKnown = true;
                search:
                for (String kind : kinds) {
                    if (skipKind(game, kind)) continue;
                    for (String name : GameMeta.coverNames(appContext, game)) {
                        String hash = hashOf(urlFor(game.console, name, kind));
                        File file = new File(directory, hash + ".png");
                        if (file.isFile()) { try (InputStream in = new java.io.FileInputStream(file)) { bytes = Library.readLimited(in, 4_000_000); } break search; }
                        if (!new File(directory, hash + ".miss").exists()) allKnown = false;
                    }
                }
                if (bytes != null) { show(tag, view, bytes, maxDim); return; }
                if (allKnown) return; // everything was already tried and libretro has nothing
            } catch (Exception ignored) { }
            // 2) otherwise download, but only for tiles that are actually on screen
            ui.post(() -> whenVisible(view, tag, () -> pool.execute(() -> download(game, view, tag, maxDim, kinds))));
        });
    }
    private static boolean skipKind(Game game, String kind) {
        return kind.equals("Named_Logos") && (game.console == Console.GBC || game.console == Console.NDS); // libretro has no logo folder there
    }
    /** Runs the task once the view is visible; tiles scrolled away wait until they come into view. */
    private void whenVisible(View view, String tag, Runnable task) {
        if (!tag.equals(view.getTag())) return;
        android.graphics.Rect r = new android.graphics.Rect();
        if (view.isAttachedToWindow() && view.getGlobalVisibleRect(r)) { task.run(); return; }
        if (!view.isAttachedToWindow()) return;
        final android.view.ViewTreeObserver.OnScrollChangedListener[] holder = new android.view.ViewTreeObserver.OnScrollChangedListener[1];
        holder[0] = () -> {
            if (!tag.equals(view.getTag()) || !view.isAttachedToWindow()) { remove(view, holder[0]); return; }
            if (view.getGlobalVisibleRect(new android.graphics.Rect())) { remove(view, holder[0]); task.run(); }
        };
        view.getViewTreeObserver().addOnScrollChangedListener(holder[0]);
    }
    private static void remove(View view, android.view.ViewTreeObserver.OnScrollChangedListener l) { try { view.getViewTreeObserver().removeOnScrollChangedListener(l); } catch (Exception ignored) { } }
    private void download(Game game, ImageView view, String tag, int maxDim, String... kinds) {
        try {
            if (!tag.equals(view.getTag())) return;
            byte[] bytes = null;
            search:
            for (String kind : kinds) {
                if (skipKind(game, kind)) continue;
                for (String name : GameMeta.coverNames(appContext, game)) {
                    String url = urlFor(game.console, name, kind);
                    String hash = hashOf(url);
                    File file = new File(directory, hash + ".png"), miss = new File(directory, hash + ".miss");
                    if (file.isFile()) { try (InputStream in = new java.io.FileInputStream(file)) { bytes = Library.readLimited(in, 4_000_000); } break search; }
                    if (miss.exists()) continue;
                    HttpURLConnection connection = null;
                    try {
                        connection = (HttpURLConnection) new java.net.URL(url).openConnection();
                        connection.setConnectTimeout(6000); connection.setReadTimeout(6000);
                        connection.setInstanceFollowRedirects(false);
                        int code = connection.getResponseCode();
                        if (code == 404) { miss.createNewFile(); continue; }
                        if (code != 200) return;
                        try (InputStream in = connection.getInputStream()) { bytes = Library.readLimited(in, 4_000_000); }
                    } finally { if (connection != null) connection.disconnect(); }
                    try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
                    break search;
                }
            }
            if (bytes != null) show(tag, view, bytes, maxDim);
        } catch (Exception ignored) { /* Placeholder remains usable offline. */ }
    }
    private void show(String tag, ImageView view, byte[] bytes, int maxDim) {
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return;
        int sample = 1; while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2;
        opts.inJustDecodeBounds = false; opts.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (bitmap == null) return;
        memory.put(tag, bitmap);
        ui.post(() -> { if (tag.equals(view.getTag())) { view.setImageBitmap(bitmap); reveal(view); } });
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
    void close() { pool.shutdownNow(); disk.shutdownNow(); ui.removeCallbacksAndMessages(null); memory.evictAll(); }
}
