package com.tirso.tirsolador;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Collects what Android recorded about recent crashes so a failing game can be diagnosed. Reads only this app's own information. */
final class CrashReport {
    static String build(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("RetroStream ").append(BuildConfig.VERSION_NAME).append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n");
        out.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(") · ").append(String.join(",", Build.SUPPORTED_ABIS)).append("\n\n");
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 6);
                SimpleDateFormat when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                int shown = 0;
                for (ApplicationExitInfo info : list) {
                    int reason = info.getReason();
                    if (reason == ApplicationExitInfo.REASON_USER_REQUESTED || reason == ApplicationExitInfo.REASON_EXIT_SELF) continue;
                    out.append("== ").append(when.format(new Date(info.getTimestamp()))).append(" · ").append(reasonName(reason))
                        .append(" · ").append(info.getDescription() == null ? "" : info.getDescription()).append("\n");
                    out.append("status ").append(info.getStatus()).append(" · memory ").append(info.getRss() / 1024).append(" MB\n");
                    if (shown < 2 && (reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_ANR)) {
                        try (InputStream in = info.getTraceInputStream()) {
                            if (in != null) out.append(readable(in, 120 * 1024));
                        } catch (Exception ignored) { /* trace not available */ }
                    }
                    out.append("\n");
                    shown++;
                }
                if (shown == 0) out.append("No crashes recorded by Android yet.\n\n");
            } catch (Exception e) {
                out.append("Could not read Android's exit records: ").append(e.getMessage()).append("\n\n");
            }
        } else {
            out.append("This Android version does not keep crash records for apps.\n\n");
        }
        out.append("--- recent app log ---\n").append(log());
        return out.toString();
    }

    private static String reasonName(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_CRASH: return "JAVA CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "NATIVE CRASH";
            case ApplicationExitInfo.REASON_ANR: return "NOT RESPONDING";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "OUT OF MEMORY (killed)";
            case ApplicationExitInfo.REASON_SIGNALED: return "KILLED BY SIGNAL";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "START FAILURE";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY DIED";
            default: return "OTHER (" + r + ")";
        }
    }

    /** Keeps only the readable text of a (binary) crash trace: function names, library names, signals. */
    private static String readable(InputStream in, int max) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0 && raw.size() < max) raw.write(buffer, 0, n);
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (byte b : raw.toByteArray()) {
            if (b >= 32 && b < 127) run.append((char) b);
            else { if (run.length() >= 6) out.append(run).append('\n'); run.setLength(0); }
        }
        if (run.length() >= 6) out.append(run).append('\n');
        String text = out.toString();
        return text.length() > 12000 ? text.substring(0, 12000) + "\n…" : text;
    }

    private static String log() {
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-t", "600").redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String l = line.toLowerCase(Locale.ROOT);
                    if (l.contains("fatal") || l.contains("signal") || l.contains("backtrace") || l.contains("androidruntime") || l.contains("mupen")
                        || l.contains("egl") || l.contains("retro") || l.contains("tirso") || l.contains("libc") || l.contains(" #0") || l.contains(" pc "))
                        out.append(line).append('\n');
                    if (out.length() > 20000) break;
                }
            }
            return out.length() == 0 ? "(no relevant lines)\n" : out.toString();
        } catch (Exception e) {
            return "(log not available)\n";
        }
    }

    /** Saves the report in the Downloads folder; returns the file name or null. */
    static String save(Context context, String report) {
        try {
            String name = "RetroStream-report-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                try (OutputStream out = context.getContentResolver().openOutputStream(uri)) { out.write(report.getBytes("UTF-8")); }
                return "Downloads/" + name;
            }
            java.io.File dir = context.getExternalFilesDir(null);
            java.io.File file = new java.io.File(dir, name);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { out.write(report.getBytes("UTF-8")); }
            return file.getPath();
        } catch (Exception e) {
            return null;
        }
    }
}
