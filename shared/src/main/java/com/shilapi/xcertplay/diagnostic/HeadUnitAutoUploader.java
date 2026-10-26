package com.shilapi.xcertplay.diagnostic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automatic crash/black-screen reporting with dynamic private-LAN Mac discovery.
 * User-requested test builds default to enabled; a visible switch disables it.
 * The payload is the app's sanitized journal, never logcat/media/MFi secrets.
 */
public final class HeadUnitAutoUploader {
    private static final String TAG = "HeadUnitAutoUploader";
    private static final String PREFS = "headunit-log";
    private static final int DISCOVERY_PORT = 18817;
    private static final int REPORT_PORT = 18816;
    private static final String QUERY = "DIPLAY_HEADUNIT_LOG_DISCOVER_V1:";
    private static final String RESPONSE = "DIPLAY_HEADUNIT_LOG_RECEIVER_V1:";
    private static final AtomicBoolean inFlight = new AtomicBoolean(false);
    private static final SecureRandom random = new SecureRandom();

    public interface UploadCallback {
        void onFinished(boolean success, String status);
    }

    private HeadUnitAutoUploader() { }

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("auto_upload", true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("auto_upload", enabled).commit();
        HeadUnitDiagnostics.event("AUTO_UPLOAD_SWITCH",
            enabled ? "Enabled by driver" : "Disabled by driver");
        if (!enabled) HeadUnitUploadService.cancel(context);
    }

    public static String lastStatus(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("auto_upload_status", "等待需要上报的故障");
    }

    public static boolean hasUnsentIncident(Context context) {
        if (!isEnabled(context)) return false;
        long newest = HeadUnitDiagnostics.incidentTimestamp();
        return newest > 0 && newest > context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("sent_incident_timestamp", 0L);
    }

    public static void schedule(final Context context, final String reason) {
        if (!isEnabled(context) || !inFlight.compareAndSet(false, true)) return;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    uploadNow(app, reason);
                } finally { inFlight.set(false); }
            }
        }, "headunit-automatic-log-upload").start();
    }

    /**
     * User-triggered upload deliberately bypasses the auto-reporting switch,
     * incident de-duplication and retry interval. An apparently normal or black
     * video should always be reportable, even when watchdogs do not fire.
     */
    public static void uploadManual(final Context context, final String reason,
                                    final UploadCallback callback) {
        final Context app = context.getApplicationContext();
        if (!inFlight.compareAndSet(false, true)) {
            notifyUi(callback, false, "另一项日志上传正在进行，请稍后再按一次");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                boolean result = false;
                try {
                    HeadUnitDiagnostics.flagIncident("User-triggered report: " + reason);
                    result = uploadNow(app, reason, true);
                } finally {
                    inFlight.set(false);
                    notifyUi(callback, result, lastStatus(app));
                }
            }
        }, "headunit-manual-log-upload").start();
    }

    private static void notifyUi(final UploadCallback callback, final boolean success,
                                 final String status) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { callback.onFinished(success, status); }
        });
    }

    /** Synchronous method for an alarm-triggered upload service. Never call on UI. */
    public static boolean uploadNow(Context context, String reason) {
        return uploadNow(context, reason, false);
    }

    private static boolean uploadNow(Context context, String reason, boolean manual) {
        if (!manual && !isEnabled(context)) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long incident = HeadUnitDiagnostics.incidentTimestamp();
        long previouslySent = prefs.getLong("sent_incident_timestamp", 0L);
        if (!manual && (incident == 0 || incident <= previouslySent)) return false;
        long now = System.currentTimeMillis();
        if (!manual && now - prefs.getLong("last_attempt", 0) < 12000L) return false;
        prefs.edit().putLong("last_attempt", now).commit();
        HeadUnitDiagnostics.event("AUTO_UPLOAD_START", reason);
        File zip = null;
        HttpURLConnection http = null;
        try {
            String server = discover(context);
            if (server == null) throw new IllegalStateException("Mac receiver not discovered on LAN");
            zip = HeadUnitDiagnostics.snapshotZip(context);
            if (zip.length() <= 0 || zip.length() > 2 * 1024 * 1024) {
                throw new IllegalStateException("Unexpected diagnostic ZIP size");
            }
            http = (HttpURLConnection) new URL(
                "http://" + server + ":" + REPORT_PORT + "/api/diagnostic-dump")
                .openConnection(Proxy.NO_PROXY);
            http.setRequestMethod("POST");
            http.setConnectTimeout(3500);
            http.setReadTimeout(15000);
            http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/zip");
            http.setFixedLengthStreamingMode((int) zip.length());
            FileInputStream input = new FileInputStream(zip);
            OutputStream output = http.getOutputStream();
            try {
                byte[] bytes = new byte[4096];
                int count;
                while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
                output.flush();
            } finally {
                input.close();
                output.close();
            }
            int response = http.getResponseCode();
            if (response != 200) {
                throw new IllegalStateException("Mac receiver HTTP " + response);
            }
            prefs.edit().putLong("sent_incident_timestamp", incident)
                .putString("auto_upload_status", "上报成功：Mac " + server +
                    "，HTTP 200，故障 " + reason)
                .putInt("retry_count", 0).commit();
            HeadUnitDiagnostics.event("AUTO_UPLOAD_SUCCESS", "HTTP 200");
            Log.i(TAG, "Sanitized diagnostic uploaded (HTTP 200) to trusted LAN receiver");
            return true;
        } catch (Exception error) {
            String status = "暂未上传：Mac 未发现或接收失败（" +
                error.getClass().getSimpleName() + "）。日志已本地保存，可自动重试。";
            prefs.edit().putString("auto_upload_status", status).commit();
            HeadUnitDiagnostics.event("AUTO_UPLOAD_RETRY", error.getClass().getSimpleName());
            Log.w(TAG, "Diagnostic auto-upload deferred: " + error.getClass().getSimpleName());
            return false;
        } finally {
            if (http != null) http.disconnect();
            if (zip != null) zip.delete();
        }
    }

    /**
     * The Mac answers UDP 18817 with a versioned, nonce-echoed response.
     * The response's source address is the Mac's *current* LAN IP, even after
     * switching from 192.168.x.x to an iPhone hotspot (172.20.10.x).
     */
    public static String discover(Context context) throws Exception {
        byte[] nonceBytes = new byte[8];
        random.nextBytes(nonceBytes);
        StringBuilder hex = new StringBuilder(16);
        for (byte b : nonceBytes) hex.append(String.format("%02x", b & 0xff));
        final String nonce = hex.toString();
        final byte[] query = (QUERY + nonce).getBytes("US-ASCII");
        final String expected = RESPONSE + nonce;
        final Set<String> destinations = new HashSet<String>();
        destinations.add("255.255.255.255");
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface adapter = interfaces.nextElement();
            if (!adapter.isUp() || adapter.isLoopback()) continue;
            for (InterfaceAddress address : adapter.getInterfaceAddresses()) {
                InetAddress broadcast = address.getBroadcast();
                if (broadcast != null) destinations.add(broadcast.getHostAddress());
            }
        }
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setBroadcast(true);
            socket.setSoTimeout(1000);
            for (String destination : destinations) {
                try {
                    DatagramPacket request = new DatagramPacket(query, query.length,
                        InetAddress.getByName(destination), DISCOVERY_PORT);
                    socket.send(request);
                } catch (Exception ignored) { }
            }
            // Use a cached address only as a fallback; never bake an IP into the APK.
            String cached = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("last_mac_ip", "");
            if (isPrivateIpv4(cached)) {
                DatagramPacket request = new DatagramPacket(query, query.length,
                    InetAddress.getByName(cached), DISCOVERY_PORT);
                socket.send(request);
            }
            long deadline = System.currentTimeMillis() + 3200L;
            while (System.currentTimeMillis() < deadline) {
                byte[] response = new byte[160];
                DatagramPacket answer = new DatagramPacket(response, response.length);
                try {
                    socket.receive(answer);
                } catch (java.net.SocketTimeoutException timeout) { continue; }
                String payload = new String(answer.getData(), answer.getOffset(),
                    answer.getLength(), "US-ASCII");
                String peer = answer.getAddress().getHostAddress();
                if (expected.equals(payload) && isPrivateIpv4(peer)) {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString("last_mac_ip", peer).commit();
                    return peer;
                }
            }
        } finally { socket.close(); }
        return null;
    }

    public static boolean isPrivateIpv4(String ip) {
        if (ip == null || !ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
        String[] octets = ip.split("\\.");
        int[] numbers = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                numbers[i] = Integer.parseInt(octets[i]);
                if (numbers[i] > 255) return false;
            } catch (NumberFormatException error) { return false; }
        }
        return numbers[0] == 10 || numbers[0] == 192 && numbers[1] == 168 ||
            numbers[0] == 172 && numbers[1] >= 16 && numbers[1] <= 31;
    }
}
