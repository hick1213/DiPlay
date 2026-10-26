package com.hick.carprobe;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small persistent ring of this app's own events, including its previous crash. */
public final class DiagnosticLog {
    private static final String PREF = "diagnostic_event_log";
    private static final int MAX_CHARS = 14000;
    private static boolean handlerInstalled;

    private DiagnosticLog() { }

    public static synchronized void append(Context context, String kind, String message) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String current = prefs.getString("events", "");
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String safe = message == null ? "" : message.replace('\r', ' ').replace('\n', ' ');
        if (safe.length() > 1200) safe = safe.substring(0, 1200) + "…";
        String updated = current + time + " [" + kind + "] " + safe + "\n";
        if (updated.length() > MAX_CHARS) {
            int start = updated.indexOf('\n', updated.length() - MAX_CHARS);
            updated = updated.substring(start < 0 ? updated.length() - MAX_CHARS : start + 1);
        }
        // commit() is important when this method is invoked from an uncaught-exception handler.
        prefs.edit().putString("events", updated).commit();
    }

    public static synchronized String read(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString("events", "(尚无本应用运行日志)\n");
    }

    public static synchronized void installCrashHandler(final Context context) {
        if (handlerInstalled) return;
        handlerInstalled = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                try {
                    StringWriter buffer = new StringWriter();
                    error.printStackTrace(new PrintWriter(buffer));
                    append(app, "CRASH", "线程=" + thread.getName() + " " + buffer.toString());
                } catch (Throwable ignored) { }
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }
}
