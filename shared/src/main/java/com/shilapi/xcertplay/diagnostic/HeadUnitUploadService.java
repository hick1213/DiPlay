package com.shilapi.xcertplay.diagnostic;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * API17 alarm-triggered worker in a separate process. A native/Java crash in
 * the CarPlay process doesn't cancel the OS alarm; the crash/pending journal
 * can then be sent to the Mac without asking a driver to reopen the app.
 */
public final class HeadUnitUploadService extends Service {
    private static final int REQUEST_ID = 4217;

    public static void scheduleAfterConnectionAttempt(Context context) {
        if (!HeadUnitAutoUploader.isEnabled(context)) return;
        AlarmManager manager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (manager == null) return;
        PendingIntent pending = intent(context, PendingIntent.FLAG_UPDATE_CURRENT);
        manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 35000L, pending);
    }

    private static void retryLater(Context context) {
        int count = context.getSharedPreferences("headunit-log", MODE_PRIVATE)
            .getInt("retry_count", 0);
        if (count >= 6 || !HeadUnitAutoUploader.isEnabled(context)) return;
        context.getSharedPreferences("headunit-log", MODE_PRIVATE).edit()
            .putInt("retry_count", count + 1).commit();
        AlarmManager manager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (manager != null) {
            PendingIntent pending = intent(context, PendingIntent.FLAG_UPDATE_CURRENT);
            manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 5 * 60 * 1000L, pending);
        }
    }

    public static void cancel(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (manager == null) return;
        PendingIntent pending = intent(context, PendingIntent.FLAG_NO_CREATE);
        if (pending != null) {
            manager.cancel(pending);
            pending.cancel();
        }
    }

    private static PendingIntent intent(Context context, int flags) {
        Intent intent = new Intent(context, HeadUnitUploadService.class);
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(context, REQUEST_ID, intent, flags);
    }

    @Override public void onCreate() {
        super.onCreate();
        HeadUnitDiagnostics.install(getApplicationContext(), getPackageName() + " reporter");
    }

    @Override public int onStartCommand(Intent intent, int flags, final int startId) {
        new Thread(new Runnable() {
            @Override public void run() {
                PowerManager.WakeLock lock = null;
                try {
                    PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                    if (power != null) {
                        lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiPlay:AutoDiagnostic");
                        lock.acquire(25000L);
                    }
                    boolean sent = HeadUnitAutoUploader.uploadNow(
                        getApplicationContext(), "CarPlay connection interrupted");
                    if (!sent && HeadUnitAutoUploader.hasUnsentIncident(
                            getApplicationContext())) {
                        retryLater(getApplicationContext());
                    }
                } finally {
                    if (lock != null && lock.isHeld()) lock.release();
                    stopSelf(startId);
                }
            }
        }, "headunit-crash-reporter").start();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
