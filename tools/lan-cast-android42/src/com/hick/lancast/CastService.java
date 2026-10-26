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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

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

    static CastService getInstance() { return instance; }

    public String getSummary() {
        if (!running) return "已停止";
        return ip.length() == 0 || port == 0 ? "正在启动（" + lastError + "）" :
            transport + " · http://" + ip + ":" + port + "/device.xml" +
            (lastError.length() == 0 ? "" : " · " + lastError);
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        running = true;
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
        transport = state;
        if (instance != null) instance.announce();
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
                    while (running) {
                        final Socket accepted = webSocket.accept();
                        new Thread(new Runnable() {
                            @Override public void run() { handleHttp(accepted); }
                        }, "cast-http-peer").start();
                    }
                } catch (Exception e) {
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
                    while (running) {
                        if (port > 0 && ip.length() > 0 && System.currentTimeMillis() - lastAlive > 18000) {
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
        String[] all = {"upnp:rootdevice", "uuid:" + uuid, DEV, AV, CM, RC};
        for (String type : all) {
            if (!("ssdp:all".equalsIgnoreCase(st) || type.equalsIgnoreCase(st))) continue;
            String usn = "uuid:" + uuid + (type.startsWith("uuid:") ? "" : "::" + type);
            String response = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n" +
                "LOCATION: http://" + ip + ":" + port + "/device.xml\r\n" +
                "SERVER: Android/4.2 UPnP/1.0 CarLanCast/1.0\r\n" +
                "ST: " + type + "\r\nUSN: " + usn + "\r\n\r\n";
            byte[] bytes = response.getBytes("UTF-8");
            multicast.send(new DatagramPacket(bytes, bytes.length, remote, remotePort));
        }
    }

    private void notifyAlive() throws Exception {
        String msg = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
            "CACHE-CONTROL: max-age=1800\r\nLOCATION: http://" + ip + ":" + port + "/device.xml\r\n" +
            "NT: " + DEV + "\r\nNTS: ssdp:alive\r\nSERVER: Android/4.2 UPnP/1.0 CarLanCast/1.0\r\n" +
            "USN: uuid:" + uuid + "::" + DEV + "\r\n\r\n";
        byte[] bytes = msg.getBytes("UTF-8");
        multicast.send(new DatagramPacket(bytes, bytes.length, multicastAddress, 1900));
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
            if ("GET".equalsIgnoreCase(start[0])) {
                String path = start[1].split("\\?")[0];
                String body = getPage(path);
                sendHttp(out, body == null ? 404 : 200, "text/xml; charset=utf-8", body == null ? "Not Found" : body);
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
                String response = handleSoap(start[1], headers.get("soapaction"), new String(bodyBytes, "UTF-8"));
                sendHttp(out, 200, "text/xml; charset=utf-8", response);
            } else if ("SUBSCRIBE".equalsIgnoreCase(start[0])) {
                // Some DLNA controllers require a GENA subscription before SOAP commands.
                // This minimal renderer is polling-only and does not dispatch event callbacks.
                String sid = headers.get("sid");
                if (sid == null || !sid.startsWith("uuid:")) sid = "uuid:" + uuid;
                out.write(("HTTP/1.1 200 OK\r\nSID: " + sid +
                    "\r\nTIMEOUT: Second-1800\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                out.flush();
            } else if ("UNSUBSCRIBE".equalsIgnoreCase(start[0])) {
                sendHttp(out, 200, "text/plain", "");
            } else {
                sendHttp(out, 405, "text/plain", "Method not allowed");
            }
        } catch (Exception e) { Log.w(TAG, "HTTP peer failed: " + e.toString()); }
        finally { try { socket.close(); } catch (Exception ignored) { } }
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
        byte[] data = body.getBytes("UTF-8");
        String header = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\n" +
            "Content-Type: " + type + "\r\nContent-Length: " + data.length + "\r\n" +
            "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        out.write(data);
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
            "GetCurrentTransportActions", "Play", "Pause", "Stop", "Seek" });
        if ("/connectionmanager.xml".equals(path)) return serviceScpd(CM, new String[] {
            "GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo" });
        if ("/renderingcontrol.xml".equals(path)) return serviceScpd(RC,
            new String[] { "GetVolume", "SetVolume", "GetMute", "SetMute" });
        return null;
    }

    private static String serviceDesc(String type, String path) {
        return "<service><serviceType>" + type + "</serviceType>" +
            "<serviceId>urn:upnp-org:serviceId:" + path + "</serviceId>" +
            "<SCPDURL>/" + path + ".xml</SCPDURL><controlURL>/control/" + path +
            "</controlURL><eventSubURL>/event/" + path + "</eventSubURL></service>";
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
                usedStates.add(pair[0]);
                b.append("<argument><name>").append(pair[0]).append("</name><direction>")
                    .append(pair[1]).append("</direction><relatedStateVariable>A_ARG_TYPE_")
                    .append(pair[0]).append("</relatedStateVariable></argument>");
            }
            b.append("</argumentList></action>");
        }
        b.append("</actionList><serviceStateTable>");
        for (String name : usedStates) {
            boolean numeric = name.equals("InstanceID") || name.equals("ConnectionID") ||
                name.equals("DesiredVolume") || name.equals("CurrentVolume") ||
                name.equals("CurrentMute") || name.equals("DesiredMute");
            b.append("<stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_").append(name)
                .append("</name><dataType>").append(numeric ? "ui4" : "string")
                .append("</dataType></stateVariable>");
        }
        b.append("</serviceStateTable></scpd>");
        return b.toString();
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
                    Log.i(TAG, "Media URI received (length=" + url.length() + ")");
                }
            } else if ("Play".equals(action)) {
                if (mediaUri.length() > 0) {
                    if ("PAUSED_PLAYBACK".equals(transport)) playbackCommand("play", 0);
                    else {
                        Intent play = new Intent(this, CastPlayerActivity.class);
                        play.putExtra("mediaUri", mediaUri);
                        play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(play);
                    }
                    updateTransport("PLAYING");
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
            }
        } else if (CM.equals(type)) {
            if ("GetProtocolInfo".equals(action)) {
                args = "<Source></Source><Sink>http-get:*:video/mp4:*,http-get:*:video/mpeg:*," +
                    "http-get:*:application/vnd.apple.mpegurl:*,http-get:*:application/x-mpegURL:*," +
                    "http-get:*:audio/mpeg:*</Sink>";
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
            } else if ("GetMute".equals(action)) args = "<CurrentMute>" + (muted ? "1" : "0") + "</CurrentMute>";
            else if ("SetMute".equals(action)) {
                muted = "1".equals(xmlValue(body, "DesiredMute"));
                AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (audio != null) {
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, muted ? 0 : max * volume / 100, 0);
                }
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
