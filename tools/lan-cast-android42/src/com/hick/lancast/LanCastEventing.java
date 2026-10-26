package com.hick.lancast;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** UPnP/GENA subscriptions with an initial NOTIFY and ordered LastChange events. API 17. */
final class LanCastEventing {
    interface BodyProvider { String propertySet(String service); }

    static final class Reply {
        final int status;
        final String sid;
        final int timeoutSeconds;
        Reply(int status, String sid, int timeoutSeconds) {
            this.status = status;
            this.sid = sid;
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    private static final String TAG = "CarLanCast";
    private static final int MAX_SUBSCRIPTIONS = 32;
    private static final int MAX_SECONDS = 1800;
    private final Map<String, Subscription> subscribers = new HashMap<String, Subscription>();
    private final ExecutorService events = Executors.newSingleThreadExecutor();
    private final BodyProvider provider;
    private volatile boolean closed;

    private static final class Subscription {
        final String sid;
        final String service;
        final String host;
        final int port;
        final String path;
        long expiresAt;
        long nextSequence;

        Subscription(String sid, String service, String host, int port, String path, long expiresAt) {
            this.sid = sid;
            this.service = service;
            this.host = host;
            this.port = port;
            this.path = path;
            this.expiresAt = expiresAt;
        }
    }

    LanCastEventing(BodyProvider provider) { this.provider = provider; }

    synchronized Reply subscribe(String service, String callback, String sid, String nt,
                                  String timeout, InetAddress sender) {
        if (closed || service == null || sender == null) return new Reply(412, null, 0);
        prune();
        int seconds = parseTimeout(timeout);
        if (sid != null && sid.length() > 0) {
            Subscription current = subscribers.get(sid);
            if (current == null || !service.equals(current.service) ||
                    callback != null && callback.length() > 0) return new Reply(412, null, 0);
            current.expiresAt = System.currentTimeMillis() + seconds * 1000L;
            Log.i(TAG, "GENA RENEW service=" + service + " sid=existing timeout=" + seconds);
            return new Reply(200, current.sid, seconds);
        }
        if (subscribers.size() >= MAX_SUBSCRIPTIONS || !"upnp:event".equalsIgnoreCase(nt)) {
            return new Reply(412, null, 0);
        }
        URI target = parseCallback(callback, sender);
        if (target == null) {
            Log.w(TAG, "GENA reject unsafe/invalid callback service=" + service);
            return new Reply(412, null, 0);
        }
        Subscription subscription = new Subscription("uuid:" + UUID.randomUUID(), service,
            target.getHost(), target.getPort() == -1 ? 80 : target.getPort(),
            (target.getRawPath() == null || target.getRawPath().length() == 0 ? "/" : target.getRawPath()) +
            (target.getRawQuery() == null ? "" : "?" + target.getRawQuery()),
            System.currentTimeMillis() + seconds * 1000L);
        subscribers.put(subscription.sid, subscription);
        // The initial event must follow the SUBSCRIBE HTTP response, not precede it.
        // The caller invokes initialEvent() only after flushing that response.
        Log.i(TAG, "GENA SUBSCRIBE accepted service=" + service + " sid=new timeout=" + seconds);
        return new Reply(200, subscription.sid, seconds);
    }

    synchronized boolean unsubscribe(String service, String sid) {
        Subscription found = subscribers.get(sid);
        if (found == null || !found.service.equals(service)) return false;
        subscribers.remove(sid);
        Log.i(TAG, "GENA UNSUBSCRIBE service=" + service);
        return true;
    }

    void initialEvent(String sid) {
        final Subscription sub;
        synchronized (this) { sub = subscribers.get(sid); }
        if (sub != null && !closed) {
            events.execute(new Runnable() {
                @Override public void run() { notifyOne(sub); }
            });
        }
    }

    void changed(String service) {
        final ArrayList<Subscription> targets = new ArrayList<Subscription>();
        synchronized (this) {
            if (closed) return;
            prune();
            for (Subscription sub : subscribers.values()) {
                if (service.equals(sub.service)) targets.add(sub);
            }
        }
        for (final Subscription sub : targets) {
            events.execute(new Runnable() {
                @Override public void run() { notifyOne(sub); }
            });
        }
    }

    private void notifyOne(Subscription sub) {
        long seq;
        synchronized (this) {
            if (closed || !subscribers.containsKey(sub.sid) ||
                    System.currentTimeMillis() >= sub.expiresAt) return;
            seq = sub.nextSequence;
            sub.nextSequence = seq == 0xffffffffL ? 1L : seq + 1L;
        }
        final byte[] body;
        try {
            body = provider.propertySet(sub.service).getBytes("UTF-8");
        } catch (Exception error) {
            Log.w(TAG, "GENA failed to construct event", error);
            return;
        }
        try {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(sub.host, sub.port), 1500);
                socket.setSoTimeout(1800);
                OutputStream output = socket.getOutputStream();
                String headers = "NOTIFY " + sub.path + " HTTP/1.1\r\n" +
                    "HOST: " + sub.host + ":" + sub.port + "\r\n" +
                    "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n" +
                    "CONTENT-LENGTH: " + body.length + "\r\n" +
                    "NT: upnp:event\r\nNTS: upnp:propchange\r\n" +
                    "SID: " + sub.sid + "\r\nSEQ: " + seq +
                    "\r\nConnection: close\r\n\r\n";
                output.write(headers.getBytes("UTF-8"));
                output.write(body);
                output.flush();
                InputStream input = socket.getInputStream();
                byte[] response = new byte[100];
                int count = input.read(response);
                String first = count > 0 ? new String(response, 0, count, "UTF-8").split("\\r?\\n")[0] : "empty";
                String safeFirst = first.replaceAll("[^A-Za-z0-9 ./-]", "");
                Log.i(TAG, "GENA NOTIFY service=" + sub.service + " seq=" + seq + " response=" +
                    safeFirst.substring(0, Math.min(55, safeFirst.length())));
            } finally { socket.close(); }
        } catch (Exception error) {
            // Event delivery is best effort. Do not damage Bilibili's ordinary SOAP playback.
            Log.w(TAG, "GENA NOTIFY delivery failed service=" + sub.service +
                " seq=" + seq + " cause=" + error.getClass().getSimpleName());
        }
    }

    synchronized void shutdown() {
        closed = true;
        subscribers.clear();
        events.shutdownNow();
    }

    private void prune() {
        ArrayList<String> expired = new ArrayList<String>();
        for (Subscription sub : subscribers.values()) {
            if (sub.expiresAt <= System.currentTimeMillis()) expired.add(sub.sid);
        }
        for (String sid : expired) subscribers.remove(sid);
    }

    private static URI parseCallback(String input, InetAddress sender) {
        if (input == null || input.length() > 1024 || !input.startsWith("<") || !input.endsWith(">")) return null;
        try {
            URI uri = new URI(input.substring(1, input.length() - 1));
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                    uri.getUserInfo() != null || uri.getFragment() != null ||
                    !uri.getHost().matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return null;
            // A controller can only make the receiver connect back to that same LAN peer.
            if (!InetAddress.getByName(uri.getHost()).equals(sender)) return null;
            int port = uri.getPort();
            if (port < -1 || port == 0 || port > 65535) return null;
            return uri;
        } catch (Exception ignored) { return null; }
    }

    private static int parseTimeout(String value) {
        if (value != null && value.toLowerCase().startsWith("second-")) {
            try { return Math.max(60, Math.min(MAX_SECONDS, Integer.parseInt(value.substring(7)))); }
            catch (NumberFormatException ignored) { }
        }
        return MAX_SECONDS;
    }
}
