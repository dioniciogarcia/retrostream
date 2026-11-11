package com.tirso.tirsolador;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Self-update from this project's own GitHub release ("latest"). Downloads only from the repository's release address and checks size and SHA-256 before installing. */
final class Updater {
    static final String REPO = "dioniciogarcia/retrostream";
    static final String API = "https://api.github.com/repos/" + REPO + "/releases/tags/latest";
    static final String ALLOWED_PREFIX = "https://github.com/" + REPO + "/releases/download/";
    static final String ASSET = "RetroStream.apk";
    static final String ACTION = "com.tirso.tirsolador.UPDATE_STATUS";

    interface Progress { void update(long done, long total); }

    static final class Info {
        int build;
        String notes = "", url = "", sha = "";
        long size;
    }

    /** Asks GitHub which build is the newest. */
    static Info check() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "RetroStream/" + BuildConfig.VERSION_NAME);
        try {
            int code = c.getResponseCode();
            if (code == 404) throw new IOException(T.t("Todavía no hay una versión publicada en GitHub"));
            if (code == 403 || code == 429) throw new IOException(T.t("GitHub limita las consultas por ahora; inténtalo en unos minutos"));
            if (code != 200) throw new IOException(T.t("GitHub respondió con el error ") + code);
            String body;
            try (InputStream in = c.getInputStream()) { body = new String(readAll(in, 4 * 1024 * 1024), StandardCharsets.UTF_8); }
            JSONObject release = new JSONObject(body);
            String notes = release.optString("body", "");
            Info info = new Info();
            Matcher b = Pattern.compile("build:(\\d+)").matcher(notes);
            if (!b.find()) throw new IOException(T.t("La versión publicada no indica su número de build"));
            info.build = Integer.parseInt(b.group(1));
            Matcher s = Pattern.compile("([0-9a-fA-F]{64})\\s+\\*?" + Pattern.quote(ASSET)).matcher(notes);
            if (s.find()) info.sha = s.group(1).toLowerCase(java.util.Locale.ROOT);
            info.notes = notes.split("<!-- checksums -->")[0].replaceAll("build:\\d+", "").trim();
            JSONArray assets = release.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (ASSET.equals(a.optString("name"))) { info.url = a.optString("browser_download_url"); info.size = a.optLong("size"); }
            }
            if (info.url.isEmpty()) throw new IOException(T.t("La versión publicada no incluye el APK"));
            if (info.sha.isEmpty()) throw new IOException(T.t("La versión publicada no incluye su suma de verificación"));
            return info;
        } catch (org.json.JSONException e) {
            throw new IOException(T.t("Respuesta de GitHub no válida"));
        } finally { c.disconnect(); }
    }

    /** Downloads the APK and verifies it. Returns the verified file. */
    static File download(Context context, Info info, Progress progress) throws IOException {
        if (!info.url.startsWith(ALLOWED_PREFIX)) throw new IOException(T.t("Dirección de descarga no permitida"));
        File dir = new File(context.getCacheDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException(T.t("No se pudo crear la carpeta temporal"));
        File target = new File(dir, ASSET);
        File temp = new File(dir, ASSET + ".part");
        if (dir.getUsableSpace() < info.size * 2 + 50L * 1024 * 1024) throw new IOException(T.t("No hay espacio libre suficiente para la actualización"));
        HttpURLConnection c = (HttpURLConnection) new URL(info.url).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "RetroStream/" + BuildConfig.VERSION_NAME);
        try {
            if (c.getResponseCode() != 200) throw new IOException(T.t("La descarga falló (error ") + c.getResponseCode() + ")");
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IOException("SHA-256 no disponible"); }
            long done = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    done += n;
                    if (done > info.size + 1024) throw new IOException(T.t("El archivo es más grande de lo esperado"));
                    if (progress != null) progress.update(done, info.size);
                }
            }
            if (done != info.size) throw new IOException(T.t("Descarga incompleta; vuelve a intentarlo"));
            StringBuilder hex = new StringBuilder();
            for (byte x : digest.digest()) hex.append(String.format("%02x", x));
            if (!hex.toString().equals(info.sha)) throw new IOException(T.t("La suma de verificación no coincide; no se instalará"));
            if (target.exists() && !target.delete()) throw new IOException(T.t("No se pudo reemplazar la descarga anterior"));
            if (!temp.renameTo(target)) throw new IOException(T.t("No se pudo guardar la descarga"));
            return target;
        } catch (IOException e) {
            temp.delete();
            throw e;
        } finally { c.disconnect(); }
    }

    /** Hands the verified APK to Android's installer; the result arrives in UpdateReceiver. */
    static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("RetroStream.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                session.fsync(out);
            }
            Intent intent = new Intent(context, UpdateReceiver.class).setAction(ACTION);
            PendingIntent pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > max) throw new IOException(T.t("Respuesta demasiado grande"));
        }
        return out.toByteArray();
    }
}
