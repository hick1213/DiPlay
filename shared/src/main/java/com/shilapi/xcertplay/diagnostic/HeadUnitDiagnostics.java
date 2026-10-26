package com.shilapi.xcertplay.diagnostic;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Small synchronous, API-17-compatible journal for head units without ADB.
 *
 * No READ_LOGS permission: Android 4.2 applications cannot read another app's
 * logcat. Only explicit, sanitized application checkpoints are recorded here.
 * Write/close on each checkpoint so a hard crash does not discard the log.
 */
public final class HeadUnitDiagnostics {
    private static final String TAG = "HeadUnitDiagnostics";
    private static final int MAX_FILE_BYTES = 512 * 1024;
    private static final int MAX_MESSAGE_CHARS = 8192;
    private static final int MAX_PREVIEW_CHARS = 50 * 1024;
    private static final Object LOCK = new Object();
    private static final AtomicBoolean handlingFatal = new AtomicBoolean();
    private static volatile File directory;
    private static volatile String appLabel = "HeadUnit";
    private static volatile boolean installed;

    private static final Pattern URL = Pattern.compile("(?i)(?:https?|rtsp)://[^\\s\\\"'<>]+");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern MAC = Pattern.compile("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b");
    private static final Pattern SECRET = Pattern.compile(
        "(?i)\\b(password|passphrase|secret|token|authorization|private.?key|ssid|identity)\\s*[:=]\\s*[^,;\\s]+");
    private static final Pattern LONG_BLOB = Pattern.compile("\\b[A-Za-z0-9+/=_-]{100,}\\b");

    private HeadUnitDiagnostics() { }

    public static void install(Context context, String label) {
        synchronized (LOCK) {
            if (installed) return;
            directory = new File(context.getFilesDir(), "headunit-diagnostics");
            appLabel = label;
            if (!directory.isDirectory() && !directory.mkdirs()) {
                Log.e(TAG, "Unable to create app-local diagnostic directory");
                return;
            }
            installed = true;
            final Thread.UncaughtExceptionHandler original =
                Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread thread, Throwable cause) {
                    if (handlingFatal.compareAndSet(false, true)) {
                        try { fatal(thread.getName(), cause); }
                        catch (Throwable ignored) { }
                    }
                    if (original != null) {
                        original.uncaughtException(thread, cause);
                    } else {
                        // Do not swallow a fatal error: retaining a broken process
                        // would be more dangerous than allowing Android to exit it.
                        android.os.Process.killProcess(android.os.Process.myPid());
                        System.exit(10);
                    }
                }
            });
            if (new File(directory, "connection-pending.txt").exists()) {
                write("RECOVERY", "Previous CarPlay Connect did not finish; "
                    + "check crash.txt and last CONNECT_STEP. Native crashes/forced stops "
                    + "might not have a Java stack trace.");
            }
            write("PROCESS_START", "label=" + label + " SDK=" + Build.VERSION.SDK_INT +
                " android=" + Build.VERSION.RELEASE + " model=" + Build.MODEL);
        }
    }

    /** Keep this free of SSIDs, URLs, media metadata and raw protocol payloads. */
    public static void event(String step, String detail) {
        write(step, detail);
    }

    public static void recordException(String step, Throwable error) {
        synchronized (LOCK) {
            if (directory == null) return;
            StringWriter stack = new StringWriter();
            PrintWriter printer = new PrintWriter(stack);
            error.printStackTrace(printer);
            printer.flush();
            writePrivate(new File(directory, "crash.txt"),
                time() + " CAUGHT " + sanitize(step) + "\n" +
                    sanitize(stack.toString()), false);
            write("EXCEPTION", step + " type=" + error.getClass().getName());
        }
    }

    public static void connectPending(String step) {
        synchronized (LOCK) {
            if (directory == null) return;
            writePrivate(new File(directory, "connection-pending.txt"), sanitize(step), false);
            write("CONNECT_STEP", step);
        }
    }

    public static void connectFinished(String state) {
        synchronized (LOCK) {
            write("CONNECT_DONE", state);
            if (directory != null) new File(directory, "connection-pending.txt").delete();
        }
    }

    public static void flagIncident(String step) {
        synchronized (LOCK) {
            if (directory == null) return;
            writePrivate(new File(directory, "incident.txt"), time() + " " + sanitize(step), false);
            write("INCIDENT", step);
        }
    }

    public static long incidentTimestamp() {
        File root = directory;
        if (root == null) return 0;
        long found = 0;
        for (String name : new String[] {"crash.txt", "connection-pending.txt", "incident.txt"}) {
            found = Math.max(found, new File(root, name).lastModified());
        }
        return found;
    }

    public static boolean hasPreviousCrash() {
        File root = directory;
        return root != null && (new File(root, "crash.txt").length() > 0 ||
            new File(root, "connection-pending.txt").length() > 0 ||
            new File(root, "incident.txt").length() > 0);
    }

    public static String preview() {
        synchronized (LOCK) {
            if (directory == null) return "诊断日志尚未初始化";
            StringBuilder result = new StringBuilder();
            appendPreview(result, "crash.txt", 18000);
            appendPreview(result, "connection-pending.txt", 1500);
            appendPreview(result, "incident.txt", 1500);
            appendPreview(result, "events.log", 27000);
            if (result.length() == 0) return "尚无日志；先进行一次连接或投屏测试。";
            return result.length() <= MAX_PREVIEW_CHARS ? result.toString() :
                result.substring(result.length() - MAX_PREVIEW_CHARS);
        }
    }

    private static void appendPreview(StringBuilder out, String name, int maxBytes) {
        File file = new File(directory, name);
        if (!file.isFile()) return;
        out.append("\n--- ").append(name).append(" ---\n");
        try {
            byte[] bytes = readBounded(file, maxBytes);
            out.append(new String(bytes, "UTF-8")).append('\n');
        } catch (Exception e) {
            out.append("读取失败：").append(e.getClass().getSimpleName()).append('\n');
        }
    }

    public static File snapshotZip(Context context) throws Exception {
        synchronized (LOCK) {
            File root = directory;
            if (root == null) throw new IllegalStateException("Diagnostic journal unavailable");
            File output = new File(context.getCacheDir(), "headunit-diagnostic-" +
                System.currentTimeMillis() + ".zip");
            ZipOutputStream stream = new ZipOutputStream(new FileOutputStream(output));
            try {
                for (String name : new String[] {
                    "events.log", "events.previous.log", "crash.txt",
                    "connection-pending.txt", "incident.txt"
                }) {
                    File file = new File(root, name);
                    if (!file.isFile()) continue;
                    ZipEntry entry = new ZipEntry(name);
                    stream.putNextEntry(entry);
                    InputStream in = new FileInputStream(file);
                    try {
                        byte[] bytes = new byte[4096];
                        int n;
                        while ((n = in.read(bytes)) != -1) stream.write(bytes, 0, n);
                    } finally { in.close(); }
                    stream.closeEntry();
                }
                stream.putNextEntry(new ZipEntry("about.txt"));
                stream.write(("application=" + appLabel + "\nSDK=" + Build.VERSION.SDK_INT +
                    "\nAndroid=" + Build.VERSION.RELEASE +
                    "\ncollected=" + time() +
                    "\nNo logcat, microphone audio, screenshots or MFi secrets are included.\n")
                    .getBytes("UTF-8"));
                stream.closeEntry();
            } finally { stream.close(); }
            return output;
        }
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            if (directory == null) return;
            for (String name : new String[] {
                "events.log", "events.previous.log", "crash.txt", "connection-pending.txt",
                "incident.txt"
            }) {
                new File(directory, name).delete();
            }
            write("LOG_CLEARED", "User explicitly deleted the local diagnostics");
        }
    }

    private static void fatal(String thread, Throwable cause) {
        synchronized (LOCK) {
            if (directory == null) return;
            StringWriter stack = new StringWriter();
            PrintWriter printer = new PrintWriter(stack);
            cause.printStackTrace(printer);
            printer.flush();
            String report = "FATAL thread=" + thread + "\n" + stack;
            writePrivate(new File(directory, "crash.txt"), sanitize(report), false);
            write("FATAL", cause.getClass().getName() + " thread=" + thread);
        }
    }

    private static void write(String step, String detail) {
        synchronized (LOCK) {
            if (directory == null) return;
            File current = new File(directory, "events.log");
            if (current.length() > MAX_FILE_BYTES) {
                File previous = new File(directory, "events.previous.log");
                if (previous.exists()) previous.delete();
                if (!current.renameTo(previous)) current.delete();
            }
            String line = time() + " [" + sanitize(step) + "] " + sanitize(detail) + "\n";
            writePrivate(current, line, true);
        }
    }

    private static void writePrivate(File file, String message, boolean append) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file, append);
            out.write(message.getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
        } catch (Throwable error) {
            Log.w(TAG, "Diagnostic persistence failed: " + error.getClass().getSimpleName());
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
        }
    }

    private static String sanitize(String value) {
        if (value == null) return "(null)";
        String safe = value.length() <= MAX_MESSAGE_CHARS ? value : value.substring(0, MAX_MESSAGE_CHARS);
        safe = URL.matcher(safe).replaceAll("[URL_REDACTED]");
        safe = MAC.matcher(safe).replaceAll("[MAC_REDACTED]");
        safe = IPV4.matcher(safe).replaceAll("[IP_REDACTED]");
        safe = SECRET.matcher(safe).replaceAll("$1=[REDACTED]");
        safe = LONG_BLOB.matcher(safe).replaceAll("[DATA_REDACTED]");
        return safe;
    }

    private static String time() {
        SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
        return date.format(new Date());
    }

    private static byte[] readBounded(File file, int maxBytes) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] block = new byte[4096];
            int n;
            while ((n = in.read(block)) >= 0) {
                buffer.write(block, 0, n);
                if (buffer.size() > maxBytes) {
                    byte[] all = buffer.toByteArray();
                    buffer.reset();
                    buffer.write(all, all.length - maxBytes, maxBytes);
                }
            }
        } finally { in.close(); }
        return buffer.toByteArray();
    }
}
