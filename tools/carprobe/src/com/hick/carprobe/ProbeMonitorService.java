package com.hick.carprobe;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.UUID;

/** Explicitly opted-in foreground service. Works while CarPlay is on screen. */
public final class ProbeMonitorService extends Service {
    public static final String ACTION_START = "com.hick.carprobe.MONITOR_START";
    public static final String ACTION_STOP = "com.hick.carprobe.MONITOR_STOP";
    private static final int NOTIFICATION_ID = 8192;
    private static final long INTERVAL_MS = 5 * 60 * 1000L;
    private static final String PREFS = "cloud_pairing";

    private Handler handler;
    private volatile boolean uploading;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!enabled()) return;
            sendSnapshot();
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        handler = new Handler();
    }

    private boolean enabled() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("auto_upload_logs", false);
    }

    @Override public int onStartCommand(Intent request, int flags, int startId) {
        if (request != null && ACTION_STOP.equals(request.getAction())) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("auto_upload_logs", false).apply();
            DiagnosticLog.append(this, "MONITOR", "用户停止后台监测");
            handler.removeCallbacks(tick);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!enabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Intent screen = new Intent(this, WhitelistProbeActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, screen,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, ProbeMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("车机诊断正在后台监测")
                .setContentText("每 5 分钟上报硬件状态及诊断应用日志；可随时停止")
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_media_pause, "停止监测", stopAction)
                .setOngoing(true)
                .getNotification();
        startForeground(NOTIFICATION_ID, notification);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 4000);
        DiagnosticLog.append(this, "MONITOR", "用户允许后台监测启动");
        return START_STICKY;
    }

    private void sendSnapshot() {
        if (uploading) return;
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String token = prefs.getString("token", "");
        final String endpoint = prefs.getString("endpoint", "");
        if (token.length() == 0 || endpoint.length() == 0) return;
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo network = manager == null ? null : manager.getActiveNetworkInfo();
            if (network == null || !network.isConnected()) return;
        } catch (Exception ignored) { return; }
        uploading = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (!enabled()) return;
                    String id = prefs.getString("device_id", "");
                    if (id.length() == 0) {
                        id = UUID.randomUUID().toString();
                        prefs.edit().putString("device_id", id).apply();
                    }
                    String snapshot = "时间戳(ms monotonic): " + SystemClock.elapsedRealtime()
                            + "\n" + AdvancedDiagnostics.collect(ProbeMonitorService.this)
                            + "\n=== 本诊断应用日志（非其他应用的 logcat）===\n"
                            + DiagnosticLog.read(ProbeMonitorService.this);
                    JSONObject payload = new JSONObject();
                    payload.put("device_id", id);
                    payload.put("app_version", "title.navi diagnostic v4 monitor");
                    payload.put("report", snapshot);
                    if (!enabled()) return;
                    CloudReporter.post(endpoint, "/api/report", payload, token);
                    prefs.edit().putLong("monitor_last_ok", System.currentTimeMillis()).apply();
                } catch (Exception error) {
                    String reason = error.getMessage();
                    if (reason == null) reason = error.getClass().getSimpleName();
                    DiagnosticLog.append(ProbeMonitorService.this, "MONITOR_ERROR", reason);
                } finally {
                    uploading = false;
                }
            }
        }, "carprobe-background-monitor").start();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
