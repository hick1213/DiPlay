package com.hick.carprobe;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** HTTPS-only report upload. Never disables certificate or hostname validation. */
public final class CloudReporter {
    private CloudReporter() { }

    public static JSONObject post(String endpoint, String route, JSONObject request,
                                  String bearerToken) throws Exception {
        String base = endpoint.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URL url = new URL(base + route);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null) {
            throw new IOException("只能上传到经过证书验证的 HTTPS 地址");
        }

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            if (connection instanceof HttpsURLConnection) {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, null, null);
                ((HttpsURLConnection) connection).setSSLSocketFactory(
                        new Tls12SocketFactory(sslContext.getSocketFactory()));
            }
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            if (bearerToken != null && bearerToken.length() > 0) {
                connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            byte[] payload = request.toString().getBytes("UTF-8");
            if (payload.length > 180 * 1024) throw new IOException("报告过大");
            connection.setFixedLengthStreamingMode(payload.length);
            connection.setDoOutput(true);
            OutputStream stream = connection.getOutputStream();
            try {
                stream.write(payload);
            } finally {
                stream.close();
            }

            int status = connection.getResponseCode();
            InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (body == null) throw new IOException("服务器未返回响应");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try {
                byte[] block = new byte[1024];
                int count;
                while ((count = body.read(block)) != -1) {
                    if (buffer.size() + count > 4096) throw new IOException("响应过大");
                    buffer.write(block, 0, count);
                }
            } finally {
                body.close();
            }
            JSONObject response = new JSONObject(new String(buffer.toByteArray(), "UTF-8"));
            if (status != 200 || !response.optBoolean("ok", false)) {
                throw new IOException("HTTP " + status + " " + response.optString("error", "请求失败"));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    /** TLS 1.2 exists on API 17 but is not enabled by default on client sockets. */
    private static final class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        Tls12SocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private Socket enableTls12(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                for (String supported : ssl.getSupportedProtocols()) {
                    if ("TLSv1.2".equals(supported)) {
                        ssl.setEnabledProtocols(new String[] {"TLSv1.2"});
                        break;
                    }
                }
            }
            return socket;
        }

        @Override public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override public Socket createSocket(Socket socket, String host, int port,
                                              boolean autoClose) throws IOException {
            return enableTls12(delegate.createSocket(socket, host, port, autoClose));
        }

        @Override public Socket createSocket(String host, int port) throws IOException {
            return enableTls12(delegate.createSocket(host, port));
        }

        @Override public Socket createSocket(String host, int port,
                                              InetAddress localHost, int localPort)
                throws IOException {
            return enableTls12(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override public Socket createSocket(InetAddress address, int port) throws IOException {
            return enableTls12(delegate.createSocket(address, port));
        }

        @Override public Socket createSocket(InetAddress address, int port,
                                              InetAddress localAddress, int localPort)
                throws IOException {
            return enableTls12(delegate.createSocket(address, port, localAddress, localPort));
        }
    }
}
