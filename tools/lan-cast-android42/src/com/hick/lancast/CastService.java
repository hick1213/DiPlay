package com.hick.lancast;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import com.shilapi.xcertplay.diagnostic.HeadUnitDiagnostics;
import com.shilapi.xcertplay.diagnostic.HeadUnitAutoUploader;

/**
 * Small UPnP/DLNA Digital Media Renderer for Android API 17. Only the common
 * HTTP/AVTransport subset is implemented; this is not Chromecast or AirPlay.
 */
public final class CastService extends Service {
    static final String STATUS_ACTION = "com.hick.lancast.STATUS";
    static final String PLAYER_ACTION = "com.hick.lancast.PLAYER";
    private static final String TAG = "CarLanCast";
    private static final String AV = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String CM = "urn:schemas-upnp-org:service:ConnectionManager:1";
    private static final String RC = "urn:schemas-upnp-org:service:RenderingControl:1";
    private static final String DEV = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String SINK_PROTOCOLS = "http-get:*:video/mp4:*,http-get:*:video/mpeg:*," +
        "http-get:*:application/vnd.apple.mpegurl:*,http-get:*:application/x-mpegURL:*," +
        "http-get:*:audio/mpeg:*";
    private static volatile CastService instance;
    private static volatile String mediaUri = "";
    private static volatile String transport = "STOPPED";

    private volatile boolean running;
    private volatile String ip = "";
    private volatile int port = 0;
    private volatile String lastError = "";
    private volatile int volume = 50;
    private volatile boolean muted;
    private ServerSocket webSocket;
    private MulticastSocket multicast;
    private WifiManager.MulticastLock multicastLock;
    private InetAddress multicastAddress;
    private String uuid;
    private LanCastEventing eventing;
    private volatile long lastSsdpDiagnosticTime;

    static CastService getInstance() { return instance; }

    public String getSummary() {
        if (!running) return "已停止";
        return ip.length() == 0 || port == 0 ? "正在启动（" + lastError + "）" :
            ("STOPPED".equals(transport) ? "等待投屏（DLNA STOPPED，接收服务仍在线）" :
                "播放状态：" + transport) + " · http://" + ip + ":" + port + "/device.xml" +
            (lastError.length() == 0 ? "" : " · " + lastError);
    }

    @Override public void onCreate() {
        super.onCreate();
        HeadUnitDiagnostics.event("CAST_SERVICE_START", "DLNA receiver service onCreate");
        instance = this;
        running = true;
        eventing = new LanCastEventing(new LanCastEventing.BodyProvider() {
            @Override public String propertySet(String service) { return eventProperties(service); }
        });
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        uuid = UUID.nameUUIDFromBytes(("car-lan-cast:" + (androidId == null ? "unknown" : androidId)).getBytes()).toString();
        startHttp();
        startSsdp();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        announce();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (eventing != null) eventing.shutdown();
        // Explicitly invalidate cached UPnP descriptions when the receiver exits.
        // Otherwise controllers may keep the older SCPD for up to 30 minutes.
        try { notifyAvailability(false); } catch (Exception ignored) { }
        try { if (multicast != null) multicast.close(); } catch (Exception ignored) { }
        try { if (webSocket != null) webSocket.close(); } catch (Exception ignored) { }
        try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); } catch (Exception ignored) { }
        port = 0;
        ip = "";
        instance = null;
        announce();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void announce() { sendBroadcast(new Intent(STATUS_ACTION)); }

    static void updateTransport(String state) {
        if (state.equals(transport)) return;
        transport = state;
        HeadUnitDiagnostics.event("DLNA_STATE", "transport=" + state);
        if (instance != null) {
            instance.announce();
            instance.publishEvent(AV);
        }
    }

    private void publishEvent(String service) {
        LanCastEventing current = eventing;
        if (current != null) current.changed(service);
    }

    /** GENA initial/changed variables, with XML-escaped AVTransport/RenderingControl LastChange. */
    private String eventProperties(String service) {
        String properties;
        if (AV.equals(service)) {
            // Broadcast LastChange without media URLs: they can contain signed playback
            // tokens, and multiple LAN controllers may subscribe to this renderer.
            String last = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\">" +
                "<InstanceID val=\"0\"><TransportState val=\"" + escape(transport) +
                "\"/><NumberOfTracks val=\"" + (mediaUri.length() == 0 ? 0 : 1) +
                "\"/><CurrentTransportActions val=\"Play,Pause,Stop,Seek\"/>" +
                "</InstanceID></Event>";
            properties = "<e:property><LastChange>" + escape(last) + "</LastChange></e:property>";
        } else if (RC.equals(service)) {
            String last = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\">" +
                "<InstanceID val=\"0\"><Volume channel=\"Master\" val=\"" + volume +
                "\"/><Mute channel=\"Master\" val=\"" + (muted ? 1 : 0) +
                "\"/></InstanceID></Event>";
            properties = "<e:property><LastChange>" + escape(last) + "</LastChange></e:property>";
        } else {
            properties = "<e:property><SourceProtocolInfo></SourceProtocolInfo></e:property>" +
                "<e:property><SinkProtocolInfo>" + escape(SINK_PROTOCOLS) + "</SinkProtocolInfo></e:property>" +
                "<e:property><CurrentConnectionIDs>0</CurrentConnectionIDs></e:property>";
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" + properties + "</e:propertyset>";
    }

    private void startHttp() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ip = findIpv4();
                    webSocket = new ServerSocket();
                    webSocket.setReuseAddress(true);
                    try {
                        webSocket.bind(new InetSocketAddress(8200));
                    } catch (Exception occupied) {
                        webSocket.bind(new InetSocketAddress(0));
                    }
                    port = webSocket.getLocalPort();
                    announce();
                    Log.i(TAG, "DLNA HTTP listening at " + ip + ":" + port);
                    HeadUnitDiagnostics.event("DLNA_READY", "DLNA port=" + port);
                    while (running) {
                        final Socket accepted = webSocket.accept();
                        new Thread(new Runnable() {
                            @Override public void run() { handleHttp(accepted); }
                        }, "cast-http-peer").start();
                    }
                } catch (Exception e) {
                    HeadUnitDiagnostics.recordException("DLNA receiver HTTP", e);
                    HeadUnitAutoUploader.schedule(CastService.this, "DLNA server startup/HTTP failure");
                    if (running) { lastError = "HTTP: " + e.getMessage(); announce(); Log.e(TAG, lastError, e); }
                }
            }
        }, "cast-http").start();
    }

    private void startSsdp() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wifi != null) {
                        multicastLock = wifi.createMulticastLock("car-lan-cast-ssdp");
                        multicastLock.setReferenceCounted(false);
                        multicastLock.acquire();
                    }
                    multicastAddress = InetAddress.getByName("239.255.255.250");
                    multicast = new MulticastSocket(null);
                    multicast.setReuseAddress(true);
                    multicast.bind(new InetSocketAddress(1900));
                    multicast.setSoTimeout(2000);
                    multicast.joinGroup(multicastAddress);
                    byte[] buffer = new byte[8192];
                    long lastAlive = 0;
                    boolean initialNotice = false;
                    while (running) {
                        if (port > 0 && ip.length() > 0 && System.currentTimeMillis() - lastAlive > 18000) {
                            if (!initialNotice) {
                                // UPnP 1.0 clients may cache the previous description even
                                // after ssdp:alive. byebye followed by alive forces re-fetch.
                                notifyAvailability(false);
                                initialNotice = true;
                            }
                            notifyAlive();
                            lastAlive = System.currentTimeMillis();
                        }
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        try {
                            multicast.receive(packet);
                            String request = new String(packet.getData(), packet.getOffset(), packet.getLength(), "UTF-8");
                            if (request.startsWith("M-SEARCH") && request.toLowerCase().contains("ssdp:discover")) {
                                respondSearch(request, packet.getAddress(), packet.getPort());
                            }
                        } catch (java.net.SocketTimeoutException ignored) { }
                    }
                } catch (Exception e) {
                    if (running) { lastError = "SSDP: " + e.getMessage(); announce(); Log.e(TAG, lastError, e); }
                }
            }
        }, "cast-ssdp").start();
    }

    private void respondSearch(String req, InetAddress remote, int remotePort) throws Exception {
        if (port == 0 || ip.length() == 0) return;
        String st = "";
        for (String line : req.split("\\r?\\n")) {
            if (line.toLowerCase().startsWith("st:")) st = line.substring(3).trim();
        }
        long now = System.currentTimeMillis();
        if (now - lastSsdpDiagnosticTime > 1500) {
            lastSsdpDiagnosticTime = now;
            Log.i(TAG, "DLNA M-SEARCH st=" + st + " peer=" + remote.getHostAddress());
        }
        String[] all = {"upnp:rootdevice", "uuid:" + uuid, DEV, AV, CM, RC};
        for (String type : all) {
            if (!("ssdp:all".equalsIgnoreCase(st) || type.equalsIgnoreCase(st))) continue;
            String usn = "uuid:" + uuid + (type.startsWith("uuid:") ? "" : "::" + type);
            String response = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n" +
                "LOCATION: http://" + ip + ":" + port + "/device.xml\r\n" +
                "SERVER: Android/4.2 UPnP/1.0 CarLanCast/1.1\r\n" +
                "ST: " + type + "\r\nUSN: " + usn + "\r\n\r\n";
            byte[] bytes = response.getBytes("UTF-8");
            multicast.send(new DatagramPacket(bytes, bytes.length, remote, remotePort));
        }
    }

    private void notifyAlive() throws Exception {
        notifyAvailability(true);
    }

    private void notifyAvailability(boolean available) throws Exception {
        if (multicast == null || multicast.isClosed() || multicastAddress == null ||
                ip.length() == 0 || port == 0 || uuid == null) return;
        String[] types = {"upnp:rootdevice", "uuid:" + uuid, DEV, AV, CM, RC};
        for (String type : types) {
            String usn = "uuid:" + uuid + (type.startsWith("uuid:") ? "" : "::" + type);
            String msg = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                (available ? "CACHE-CONTROL: max-age=1800\r\nLOCATION: http://" + ip +
                    ":" + port + "/device.xml\r\n" : "") +
                "NT: " + type + "\r\nNTS: ssdp:" + (available ? "alive" : "byebye") +
                "\r\nSERVER: Android/4.2 UPnP/1.0 CarLanCast/1.4\r\n" +
                "USN: " + usn + "\r\n\r\n";
            byte[] bytes = msg.getBytes("UTF-8");
            multicast.send(new DatagramPacket(bytes, bytes.length, multicastAddress, 1900));
        }
        if (!available) Log.i(TAG, "DLNA SSDP byebye sent; old description cache invalidated");
    }

    private void handleHttp(Socket socket) {
        try {
            socket.setSoTimeout(8000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String first = readLine(in);
            if (first == null || first.length() > 2000) return;
            String[] start = first.split(" ");
            if (start.length < 2) return;
            Map<String, String> headers = new HashMap<String, String>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
            String method = start[0].toUpperCase(java.util.Locale.US);
            String rawPath = start[1].split("\\?")[0];
            // Some DLNA controllers incorrectly concatenate the URLBase ending in '/'
            // with service URLs already starting in '/', then request //renderingcontrol.xml.
            // Accept both spellings; advertise correct relative service URLs below.
            String path = rawPath.replaceFirst("^/{2,}", "/");
            if (!path.equals(rawPath)) Log.i(TAG, "DLNA normalized duplicated URL slashes path=" + path);
            Log.i(TAG, "DLNA HTTP method=" + method + " path=" + path +
                " peer=" + socket.getInetAddress().getHostAddress());
            if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                String body = getPage(path);
                sendHttp(out, body == null ? 404 : 200, "text/xml; charset=utf-8",
                    body == null ? "Not Found" : body, "GET".equalsIgnoreCase(method));
            } else if ("POST".equalsIgnoreCase(start[0])) {
                int len = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0");
                if (len < 0 || len > 131072) { sendHttp(out, 413, "text/plain", "Too large"); return; }
                byte[] bodyBytes = new byte[len];
                int offset = 0;
                while (offset < len) {
                    int n = in.read(bodyBytes, offset, len - offset);
                    if (n < 0) return;
                    offset += n;
                }
                Log.i(TAG, "DLNA SOAP action=" + soapAction(headers.get("soapaction"), new String(bodyBytes, "UTF-8")) +
                    " path=" + path);
                String response = handleSoap(path, headers.get("soapaction"), new String(bodyBytes, "UTF-8"));
                sendHttp(out, 200, "text/xml; charset=utf-8", response);
            } else if ("SUBSCRIBE".equalsIgnoreCase(start[0])) {
                String service = eventService(path);
                LanCastEventing.Reply reply = eventing.subscribe(service, headers.get("callback"),
                    headers.get("sid"), headers.get("nt"), headers.get("timeout"), socket.getInetAddress());
                sendSubscriptionReply(out, reply);
                if (reply.status == 200 && headers.get("sid") == null) eventing.initialEvent(reply.sid);
            } else if ("UNSUBSCRIBE".equalsIgnoreCase(start[0])) {
                boolean removed = eventing.unsubscribe(eventService(path), headers.get("sid"));
                sendHttp(out, removed ? 200 : 412, "text/plain", "");
            } else {
                sendHttp(out, 405, "text/plain", "Method not allowed");
            }
        } catch (Exception e) { Log.w(TAG, "HTTP peer failed: " + e.toString()); }
        finally { try { socket.close(); } catch (Exception ignored) { } }
    }

    private static String soapAction(String actionHeader, String body) {
        String action = "";
        if (actionHeader != null) {
            int hash = actionHeader.indexOf('#');
            if (hash >= 0) action = actionHeader.substring(hash + 1).replace("\"", "").trim();
        }
        if (action.length() == 0) action = xmlAction(body);
        return action.matches("[A-Za-z][A-Za-z0-9]*") ? action : "invalid";
    }

    private static String eventService(String path) {
        if ("/event/avtransport".equalsIgnoreCase(path)) return AV;
        if ("/event/connectionmanager".equalsIgnoreCase(path)) return CM;
        if ("/event/renderingcontrol".equalsIgnoreCase(path)) return RC;
        return null;
    }

    private static void sendSubscriptionReply(OutputStream out, LanCastEventing.Reply reply) throws Exception {
        String header = "HTTP/1.1 " + reply.status + (reply.status == 200 ? " OK" : " Precondition Failed") + "\r\n" +
            (reply.status == 200 ? "SID: " + reply.sid + "\r\nTIMEOUT: Second-" + reply.timeoutSeconds + "\r\n" : "") +
            "Content-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        out.flush();
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') buffer.write(c);
            if (buffer.size() > 8192) throw new Exception("HTTP header too large");
        }
        if (c < 0 && buffer.size() == 0) return null;
        return buffer.toString("UTF-8");
    }

    private static void sendHttp(OutputStream out, int status, String type, String body) throws Exception {
        sendHttp(out, status, type, body, true);
    }

    private static void sendHttp(OutputStream out, int status, String type, String body, boolean sendBody) throws Exception {
        byte[] data = body.getBytes("UTF-8");
        String header = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\n" +
            "Content-Type: " + type + "\r\nContent-Length: " + data.length + "\r\n" +
            "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        if (sendBody) out.write(data);
        out.flush();
    }

    private String getPage(String path) {
        if ("/device.xml".equals(path) || "/desc.xml".equals(path) || "/".equals(path)) {
            return "<?xml version=\"1.0\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\">" +
                "<specVersion><major>1</major><minor>0</minor></specVersion>" +
                "<URLBase>http://" + ip + ":" + port + "/</URLBase>" +
                "<device><deviceType>" + DEV + "</deviceType><friendlyName>Car LAN Cast</friendlyName>" +
                "<manufacturer>Local Android</manufacturer><modelName>Android 4.2 DLNA Renderer</modelName>" +
                "<UDN>uuid:" + uuid + "</UDN><serviceList>" + serviceDesc(AV, "avtransport") +
                serviceDesc(CM, "connectionmanager") + serviceDesc(RC, "renderingcontrol") +
                "</serviceList></device></root>";
        }
        if ("/avtransport.xml".equals(path)) return serviceScpd(AV, new String[] {
            "SetAVTransportURI", "GetMediaInfo", "GetTransportInfo", "GetPositionInfo",
            "GetDeviceCapabilities", "GetTransportSettings", "GetCurrentTransportActions",
            "Play", "Pause", "Stop", "Seek" });
        if ("/connectionmanager.xml".equals(path)) return serviceScpd(CM, new String[] {
            "GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo" });
        if ("/renderingcontrol.xml".equals(path)) return serviceScpd(RC,
            new String[] { "GetVolume", "SetVolume", "GetMute", "SetMute" });
        return null;
    }

    private static String serviceDesc(String type, String path) {
        // UPnP service IDs are case-sensitive, independent of our lower-case URL
        // routes. MediaRenderer:1 prescribes these exact identifiers.
        String serviceId = AV.equals(type) ? "AVTransport" :
            CM.equals(type) ? "ConnectionManager" : "RenderingControl";
        return "<service><serviceType>" + type + "</serviceType>" +
            "<serviceId>urn:upnp-org:serviceId:" + serviceId + "</serviceId>" +
            // URLBase ends with '/', so relative URLs must NOT start with '/'.
            // Otherwise naive controllers concatenate two slashes and fail capability checks.
            "<SCPDURL>" + path + ".xml</SCPDURL><controlURL>control/" + path +
            "</controlURL><eventSubURL>event/" + path + "</eventSubURL></service>";
    }

    private static String serviceScpd(String type, String[] actions) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion><actionList>");
        LinkedHashSet<String> usedStates = new LinkedHashSet<String>();
        for (String a : actions) {
            b.append("<action><name>").append(a).append("</name><argumentList>");
            String specs = actionArguments(type, a);
            if (specs.length() > 0) for (String arg : specs.split(",")) {
                String[] pair = arg.split(":");
                String state = stateForArgument(type, pair[0]);
                usedStates.add(state);
                b.append("<argument><name>").append(pair[0]).append("</name><direction>")
                    .append(pair[1]).append("</direction><relatedStateVariable>")
                    .append(state).append("</relatedStateVariable></argument>");
            }
            b.append("</argumentList></action>");
        }
        b.append("</actionList><serviceStateTable>");
        if (AV.equals(type) || RC.equals(type)) {
            usedStates.add("LastChange");
        } else if (CM.equals(type)) {
            usedStates.add("SourceProtocolInfo");
            usedStates.add("SinkProtocolInfo");
            usedStates.add("CurrentConnectionIDs");
        }
        for (String state : usedStates) {
            boolean evented = state.equals("LastChange") ||
                CM.equals(type) && (state.equals("SourceProtocolInfo") ||
                    state.equals("SinkProtocolInfo") || state.equals("CurrentConnectionIDs"));
            b.append("<stateVariable sendEvents=\"").append(evented ? "yes" : "no")
                .append("\"><name>").append(state).append("</name><dataType>")
                .append(stateType(state)).append("</dataType>");
            if (state.equals("Volume")) {
                b.append("<allowedValueRange><minimum>0</minimum><maximum>100</maximum>")
                    .append("<step>1</step></allowedValueRange>");
            } else if (state.equals("A_ARG_TYPE_Channel")) {
                b.append("<allowedValueList><allowedValue>Master</allowedValue></allowedValueList>");
            }
            b.append("</stateVariable>");
        }
        b.append("</serviceStateTable></scpd>");
        return b.toString();
    }

    /**
     * UPnP's relatedStateVariable must be the actual shared state variable, NOT
     * a synthetic A_ARG_TYPE_<argument> for every input/output. A strict DLNA
     * controller validates this mapping when reading RenderingControl.xml.
     */
    private static String stateForArgument(String service, String arg) {
        if ("InstanceID".equals(arg)) return "A_ARG_TYPE_InstanceID";
        if (RC.equals(service)) {
            if ("Channel".equals(arg)) return "A_ARG_TYPE_Channel";
            if ("CurrentVolume".equals(arg) || "DesiredVolume".equals(arg)) return "Volume";
            if ("CurrentMute".equals(arg) || "DesiredMute".equals(arg)) return "Mute";
        }
        if (CM.equals(service)) {
            if ("Source".equals(arg)) return "SourceProtocolInfo";
            if ("Sink".equals(arg)) return "SinkProtocolInfo";
            if ("ConnectionIDs".equals(arg)) return "CurrentConnectionIDs";
        }
        if (AV.equals(service)) {
            if ("CurrentURI".equals(arg)) return "AVTransportURI";
            if ("CurrentURIMetaData".equals(arg)) return "AVTransportURIMetaData";
            if ("NextURI".equals(arg)) return "NextAVTransportURI";
            if ("NextURIMetaData".equals(arg)) return "NextAVTransportURIMetaData";
            if ("Speed".equals(arg) || "CurrentSpeed".equals(arg)) return "TransportPlaySpeed";
            if ("Unit".equals(arg)) return "A_ARG_TYPE_SeekMode";
            if ("Target".equals(arg)) return "A_ARG_TYPE_SeekTarget";
            if ("CurrentTransportState".equals(arg)) return "TransportState";
            if ("CurrentTransportStatus".equals(arg)) return "TransportStatus";
            if ("Track".equals(arg)) return "CurrentTrack";
            if ("TrackDuration".equals(arg)) return "CurrentTrackDuration";
            if ("TrackMetaData".equals(arg)) return "CurrentTrackMetaData";
            if ("TrackURI".equals(arg)) return "CurrentTrackURI";
            if ("RelTime".equals(arg)) return "RelativeTimePosition";
            if ("AbsTime".equals(arg)) return "AbsoluteTimePosition";
            if ("RelCount".equals(arg)) return "RelativeCounterPosition";
            if ("AbsCount".equals(arg)) return "AbsoluteCounterPosition";
            if ("NrTracks".equals(arg)) return "NumberOfTracks";
            if ("MediaDuration".equals(arg)) return "CurrentMediaDuration";
            if ("PlayMedium".equals(arg)) return "PlaybackStorageMedium";
            if ("RecordMedium".equals(arg)) return "RecordStorageMedium";
            if ("WriteStatus".equals(arg)) return "RecordMediumWriteStatus";
            if ("Actions".equals(arg)) return "CurrentTransportActions";
            if ("PlayMedia".equals(arg)) return "PossiblePlaybackStorageMedia";
            if ("RecMedia".equals(arg)) return "PossibleRecordStorageMedia";
            if ("RecQualityModes".equals(arg)) return "PossibleRecordQualityModes";
            if ("PlayMode".equals(arg)) return "CurrentPlayMode";
            if ("RecQualityMode".equals(arg)) return "CurrentRecordQualityMode";
        }
        return "A_ARG_TYPE_" + arg;
    }

    private static String stateType(String state) {
        if ("Volume".equals(state)) return "ui2";
        if ("Mute".equals(state)) return "boolean";
        if ("NumberOfTracks".equals(state) || "CurrentTrack".equals(state) ||
                "A_ARG_TYPE_InstanceID".equals(state) ||
                "A_ARG_TYPE_AVTransportID".equals(state) ||
                "A_ARG_TYPE_RcsID".equals(state)) return "ui4";
        if ("A_ARG_TYPE_ConnectionID".equals(state) ||
                "A_ARG_TYPE_PeerConnectionID".equals(state) ||
                state.endsWith("CounterPosition")) return "i4";
        return "string";
    }

    private static String actionArguments(String type, String action) {
        if (AV.equals(type)) {
            if ("SetAVTransportURI".equals(action)) return "InstanceID:in,CurrentURI:in,CurrentURIMetaData:in";
            if ("Play".equals(action)) return "InstanceID:in,Speed:in";
            if ("Pause".equals(action) || "Stop".equals(action)) return "InstanceID:in";
            if ("Seek".equals(action)) return "InstanceID:in,Unit:in,Target:in";
            if ("GetTransportInfo".equals(action)) return "InstanceID:in,CurrentTransportState:out,CurrentTransportStatus:out,CurrentSpeed:out";
            if ("GetPositionInfo".equals(action)) return "InstanceID:in,Track:out,TrackDuration:out,TrackMetaData:out,TrackURI:out,RelTime:out,AbsTime:out,RelCount:out,AbsCount:out";
            if ("GetMediaInfo".equals(action)) return "InstanceID:in,NrTracks:out,MediaDuration:out,CurrentURI:out,CurrentURIMetaData:out,NextURI:out,NextURIMetaData:out,PlayMedium:out,RecordMedium:out,WriteStatus:out";
            if ("GetDeviceCapabilities".equals(action)) return "InstanceID:in,PlayMedia:out,RecMedia:out,RecQualityModes:out";
            if ("GetTransportSettings".equals(action)) return "InstanceID:in,PlayMode:out,RecQualityMode:out";
            if ("GetCurrentTransportActions".equals(action)) return "InstanceID:in,Actions:out";
        } else if (CM.equals(type)) {
            if ("GetProtocolInfo".equals(action)) return "Source:out,Sink:out";
            if ("GetCurrentConnectionIDs".equals(action)) return "ConnectionIDs:out";
            if ("GetCurrentConnectionInfo".equals(action)) return "ConnectionID:in,RcsID:out,AVTransportID:out,ProtocolInfo:out,PeerConnectionManager:out,PeerConnectionID:out,Direction:out,Status:out";
        } else if (RC.equals(type)) {
            if ("GetVolume".equals(action)) return "InstanceID:in,Channel:in,CurrentVolume:out";
            if ("SetVolume".equals(action)) return "InstanceID:in,Channel:in,DesiredVolume:in";
            if ("GetMute".equals(action)) return "InstanceID:in,Channel:in,CurrentMute:out";
            if ("SetMute".equals(action)) return "InstanceID:in,Channel:in,DesiredMute:in";
        }
        return "";
    }

    private String handleSoap(String path, String actionHeader, String body) throws Exception {
        String type = path.toLowerCase().contains("connection") ? CM :
            path.toLowerCase().contains("rendering") ? RC : AV;
        String action = "";
        if (actionHeader != null) {
            int hash = actionHeader.indexOf('#');
            if (hash >= 0) action = actionHeader.substring(hash + 1).replace("\"", "").trim();
        }
        if (action.length() == 0) action = xmlAction(body);
        if (!action.matches("[A-Za-z][A-Za-z0-9]*")) action = "GetTransportInfo";
        String args = "";
        if (AV.equals(type)) {
            if ("SetAVTransportURI".equals(action)) {
                String url = xmlValue(body, "CurrentURI");
                if (url.length() == 0) url = xmlValue(body, "NewURI");
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("rtsp://")) {
                    mediaUri = url;
                    updateTransport("STOPPED");
                    publishEvent(AV);
                    String host = "unknown";
                    try { host = new URI(url).getHost(); } catch (Exception ignored) { }
                    Log.i(TAG, "DLNA SetAVTransportURI accepted scheme=" +
                        url.substring(0, url.indexOf(':')) + " host=" + host + " length=" + url.length());
                    HeadUnitDiagnostics.event("DLNA_URI", "scheme=" +
                        url.substring(0, url.indexOf(':')) + " bytes=" + url.length());
                } else {
                    Log.w(TAG, "DLNA SetAVTransportURI rejected scheme=unsupported/empty length=" + url.length());
                }
            } else if ("Play".equals(action)) {
                if (mediaUri.length() > 0) {
                    Log.i(TAG, "DLNA Play accepted currentUri=true");
                    HeadUnitDiagnostics.event("DLNA_PLAY", "media URI received, launching Android decoder");
                    if ("PAUSED_PLAYBACK".equals(transport)) playbackCommand("play", 0);
                    else {
                        Intent play = new Intent(this, CastPlayerActivity.class);
                        play.putExtra("mediaUri", mediaUri);
                        play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(play);
                    }
                    updateTransport("PLAYING");
                } else {
                    Log.w(TAG, "DLNA Play ignored: no media URI previously received");
                }
            } else if ("Pause".equals(action)) {
                playbackCommand("pause", 0);
                updateTransport("PAUSED_PLAYBACK");
            } else if ("Stop".equals(action)) {
                playbackCommand("stop", 0);
                updateTransport("STOPPED");
            } else if ("Seek".equals(action)) {
                playbackCommand("seek", parseTime(xmlValue(body, "Target")));
            } else if ("GetTransportInfo".equals(action)) {
                args = "<CurrentTransportState>" + transport + "</CurrentTransportState>" +
                    "<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>";
            } else if ("GetPositionInfo".equals(action)) {
                String duration = timeString(CastPlayerActivity.durationMs);
                String position = timeString(CastPlayerActivity.positionMs);
                args = "<Track>1</Track><TrackDuration>" + duration + "</TrackDuration><TrackMetaData></TrackMetaData>" +
                    "<TrackURI>" + escape(mediaUri) + "</TrackURI><RelTime>" + position + "</RelTime>" +
                    "<AbsTime>" + position + "</AbsTime><RelCount>0</RelCount><AbsCount>0</AbsCount>";
            } else if ("GetMediaInfo".equals(action)) {
                args = "<NrTracks>1</NrTracks><MediaDuration>" +
                    timeString(CastPlayerActivity.durationMs) + "</MediaDuration>" +
                    "<CurrentURI>" + escape(mediaUri) + "</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>" +
                    "<NextURI></NextURI><NextURIMetaData></NextURIMetaData>" +
                    "<PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium><WriteStatus>NOT_IMPLEMENTED</WriteStatus>";
            } else if ("GetCurrentTransportActions".equals(action)) {
                args = "<Actions>Play,Pause,Stop,Seek</Actions>";
            } else if ("GetDeviceCapabilities".equals(action)) {
                args = "<PlayMedia>NETWORK</PlayMedia><RecMedia>NOT_IMPLEMENTED</RecMedia>" +
                    "<RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>";
            } else if ("GetTransportSettings".equals(action)) {
                args = "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>";
            }
        } else if (CM.equals(type)) {
            if ("GetProtocolInfo".equals(action)) {
                args = "<Source></Source><Sink>" + escape(SINK_PROTOCOLS) + "</Sink>";
            } else if ("GetCurrentConnectionIDs".equals(action)) args = "<ConnectionIDs>0</ConnectionIDs>";
            else if ("GetCurrentConnectionInfo".equals(action)) {
                args = "<RcsID>0</RcsID><AVTransportID>0</AVTransportID><ProtocolInfo></ProtocolInfo>" +
                    "<PeerConnectionManager></PeerConnectionManager><PeerConnectionID>-1</PeerConnectionID>" +
                    "<Direction>Input</Direction><Status>OK</Status>";
            }
        } else if (RC.equals(type)) {
            if ("GetVolume".equals(action)) args = "<CurrentVolume>" + volume + "</CurrentVolume>";
            else if ("SetVolume".equals(action)) {
                try { volume = Math.max(0, Math.min(100, Integer.parseInt(xmlValue(body, "DesiredVolume")))); }
                catch (Exception ignored) { }
                AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (audio != null) {
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, muted ? 0 : max * volume / 100, 0);
                }
                publishEvent(RC);
            } else if ("GetMute".equals(action)) args = "<CurrentMute>" + (muted ? "1" : "0") + "</CurrentMute>";
            else if ("SetMute".equals(action)) {
                muted = "1".equals(xmlValue(body, "DesiredMute"));
                AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (audio != null) {
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, muted ? 0 : max * volume / 100, 0);
                }
                publishEvent(RC);
            }
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:" + action + "Response xmlns:u=\"" + type + "\">" + args +
            "</u:" + action + "Response></s:Body></s:Envelope>";
    }

    private void playbackCommand(String cmd, int positionMs) {
        Intent i = new Intent(PLAYER_ACTION);
        i.putExtra("command", cmd);
        i.putExtra("positionMs", positionMs);
        sendBroadcast(i);
    }

    private static int parseTime(String time) {
        try {
            String[] pieces = time.split(":");
            int seconds = 0;
            for (String piece : pieces) seconds = seconds * 60 + Integer.parseInt(piece);
            return seconds * 1000;
        } catch (Exception ignored) { return 0; }
    }

    private static String timeString(int milliseconds) {
        int seconds = Math.max(0, milliseconds / 1000);
        return String.format(java.util.Locale.US, "%02d:%02d:%02d",
            seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }

    private static String xmlValue(String body, String tag) {
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(body));
            int event;
            while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && tag.equalsIgnoreCase(p.getName())) return p.nextText();
            }
        } catch (Exception ignored) { }
        return "";
    }

    private static String xmlAction(String body) {
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(body));
            boolean bodyStarted = false;
            int event;
            while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if ("Body".equalsIgnoreCase(p.getName())) bodyStarted = true;
                    else if (bodyStarted) return p.getName();
                }
            }
        } catch (Exception ignored) { }
        return "GetTransportInfo";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private String findIpv4() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifi.getConnectionInfo() != null) {
                int v = wifi.getConnectionInfo().getIpAddress();
                if (v != 0) return (v & 255) + "." + ((v >> 8) & 255) + "." +
                    ((v >> 16) & 255) + "." + ((v >> 24) & 255);
            }
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements();) {
                NetworkInterface net = e.nextElement();
                if (!net.isUp() || net.isLoopback()) continue;
                for (Enumeration<InetAddress> addrs = net.getInetAddresses(); addrs.hasMoreElements();) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) return addr.getHostAddress();
                }
            }
        } catch (Exception e) { Log.w(TAG, "Unable to read IPv4: " + e); }
        return "";
    }
}
