package com.ontunnel.app.ovpn;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Loopback TCP relay. OpenVPN (TCP) connects to 127.0.0.1:port; for every connection this relay
 * dials the real server, performs the payload / WebSocket handshake, then forwards the bytes.
 */
public final class TransportProxy {
    public interface Listener { void onError(String message); }

    private static final SecureRandom RNG = new SecureRandom();
    private static final int MAX_HEADER = 32 * 1024;
    private static final int MAX_FRAME = 4 * 1024 * 1024;

    private final TransportConfig cfg;
    private final InetAddress dialAddr;
    private final Listener listener;
    private ServerSocket server;
    private volatile boolean running;
    private final Set<Socket> open = Collections.synchronizedSet(new HashSet<Socket>());

    public TransportProxy(TransportConfig cfg, InetAddress dialAddr, Listener listener) {
        this.cfg = cfg;
        this.dialAddr = dialAddr;
        this.listener = listener;
    }

    /** Starts listening on a random loopback port and returns it. */
    public int start() throws IOException {
        server = new ServerSocket(0, 16, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "ovpn-relay-accept");
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        synchronized (open) {
            for (Socket s : open) closeQuietly(s);
            open.clear();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket c = server.accept();
                open.add(c);
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { handle(c); }
                }, "ovpn-relay-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running && listener != null) listener.onError("Relay accept failed: " + e.getMessage());
                return;
            }
        }
    }

    private void handle(Socket client) {
        Socket up = null;
        try {
            client.setTcpNoDelay(true);
            up = openUpstream();
            open.add(up);
            BufferedInputStream upIn = new BufferedInputStream(up.getInputStream(), 16 * 1024);
            OutputStream upOut = up.getOutputStream();

            String wsKey = null;
            if (cfg.mode == TransportConfig.MODE_WEBSOCKET) {
                byte[] k = new byte[16];
                RNG.nextBytes(k);
                wsKey = Base64.getEncoder().encodeToString(k);
            }
            // Multi-request payloads (bug-style): send each request separately, drain
            // intermediate HTTP replies, and only judge success on the *last* response.
            // This prevents accepting "200 OK" from a decoy GET (e.g. /cdn-cgi/trace) and
            // then stalling OpenVPN in CONNECTRETRY.
            List<PayloadTemplate.Part> parts = buildPayloadParts(wsKey);
            for (int i = 0; i < parts.size(); i++) {
                PayloadTemplate.Part p = parts.get(i);
                if (p.delayBeforeMs > 0) {
                    try { Thread.sleep(p.delayBeforeMs); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted");
                    }
                }
                upOut.write(p.data);
                upOut.flush();
                if (i < parts.size() - 1) {
                    try {
                        // Intermediate replies (decoy GETs) – ignore failures
                        up.setSoTimeout(8000);
                        drainOneHttpExchange(upIn);
                    } catch (Exception ignored) { }
                }
            }

            // Final handshake response. Many free / bug hosts never send a clean HTTP
            // status (or hang). HTTP Injector still pipes OpenVPN in that case.
            // Strict WebSocket mode still requires 101.
            String status = null;
            int code = -1;
            boolean gotResponse = false;
            try {
                up.setSoTimeout(cfg.mode == TransportConfig.MODE_WEBSOCKET ? 20000 : 10000);
                status = readHeaders(upIn);
                code = statusCode(status);
                gotResponse = true;
            } catch (java.net.SocketTimeoutException ste) {
                if (cfg.mode == TransportConfig.MODE_WEBSOCKET) {
                    throw new IOException("WebSocket handshake timed out (no 101 response)");
                }
                // PAYLOAD: no response – continue with raw tunnel (injector-style)
            } catch (EOFException eof) {
                if (cfg.mode == TransportConfig.MODE_WEBSOCKET) {
                    throw new IOException("Server closed during WebSocket handshake");
                }
            }

            boolean ok = isHandshakeOk(code, status);
            if (cfg.mode == TransportConfig.MODE_WEBSOCKET) {
                if (!ok) throw new IOException("Server rejected the WebSocket upgrade: " + status + " (code=" + code + ")");
            } else if (gotResponse && !ok) {
                // Got a clear HTTP error (4xx/5xx). Still try raw – some edges reply
                // with an error page but keep the TCP session for the tunnel.
                // Only abort on hard close (handled above).
            }

            // Clear read timeout for the long-lived tunnel
            try { up.setSoTimeout(0); } catch (Exception ignored) { }
            try { client.setSoTimeout(0); } catch (Exception ignored) { }

            // Real RFC6455 WebSocket only when we actually received 101.
            // Fake "Upgrade: websocket" injector payloads almost always need raw TCP.
            boolean useWs = (cfg.mode == TransportConfig.MODE_WEBSOCKET && ok)
                    || (code == 101);
            if (useWs) {
                relayWebSocket(client, upIn, upOut, up);
            } else {
                relayRaw(client, upIn, upOut, up);
            }
        } catch (IOException e) {
            if (running && listener != null) listener.onError(e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            closeQuietly(client);
            closeQuietly(up);
            open.remove(client);
            if (up != null) open.remove(up);
        }
    }

    // ── upstream connection ────────────────────────────────────────────

    private Socket openUpstream() throws IOException {
        Socket raw = new Socket();
        raw.setTcpNoDelay(true);
        try { raw.setKeepAlive(true); } catch (Exception ignored) { }
        raw.connect(new InetSocketAddress(dialAddr, cfg.dialPort), 20000);
        // Handshake read timeout (cleared after successful payload response)
        try { raw.setSoTimeout(25000); } catch (Exception ignored) { }
        if (!cfg.tls) return raw;
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            TrustManager[] tm = cfg.allowInsecure ? new TrustManager[]{new TrustAll()} : null;
            ctx.init(null, tm, RNG);
            String peer = cfg.sni != null && !cfg.sni.trim().isEmpty() ? cfg.sni.trim() : cfg.dialHost;
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, peer, cfg.dialPort, true);
            SSLParameters p = s.getSSLParameters();
            if (!isIpLiteral(peer)) {
                try { p.setServerNames(Collections.singletonList(new SNIHostName(peer))); }
                catch (IllegalArgumentException ignored) { }
            }
            if (!cfg.allowInsecure) p.setEndpointIdentificationAlgorithm("HTTPS");
            s.setSSLParameters(p);
            s.startHandshake();
            return s;
        } catch (IOException e) {
            closeQuietly(raw);
            throw e;
        } catch (Exception e) {
            closeQuietly(raw);
            throw new IOException("TLS setup failed: " + e.getMessage(), e);
        }
    }

    private static final class TrustAll implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
        @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private static boolean isIpLiteral(String h) {
        return h.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") || h.indexOf(':') >= 0;
    }

    // ── handshake ──────────────────────────────────────────────────────

    private List<PayloadTemplate.Part> buildPayloadParts(String wsKey) {
        String tpl = cfg.payload == null ? "" : cfg.payload;
        if (tpl.trim().isEmpty()) {
            if (cfg.mode == TransportConfig.MODE_WEBSOCKET) {
                String host = !blank(cfg.wsHost) ? cfg.wsHost : (!blank(cfg.sni) ? cfg.sni : cfg.targetHost);
                String path = blank(cfg.wsPath) ? "/" : (cfg.wsPath.startsWith("/") ? cfg.wsPath : "/" + cfg.wsPath);
                tpl = "GET " + path + " HTTP/1.1[crlf]Host: " + host + "[crlf]User-Agent: [ua][crlf]"
                        + "Upgrade: websocket[crlf]Connection: Upgrade[crlf]"
                        + "Sec-WebSocket-Key: [ws_key][crlf]Sec-WebSocket-Version: 13[crlf][crlf]";
            } else if (isHttpProxyDial()) {
                tpl = "CONNECT [host_port] HTTP/1.1[crlf]Host: [host_port][crlf]"
                        + "Proxy-Connection: Keep-Alive[crlf]Connection: Keep-Alive[crlf]"
                        + "User-Agent: [ua][crlf][crlf]";
            } else {
                tpl = "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]";
            }
        }
        List<PayloadTemplate.Part> parts = PayloadTemplate.expand(tpl, cfg.targetHost, cfg.targetPort, wsKey);
        // If the user pasted a multi-request bug payload without [split], break it at
        // intermediate CRLFCRLF so each HTTP request is its own write + response drain.
        if (parts.size() == 1) {
            parts = splitHttpRequests(parts.get(0));
        }
        return parts;
    }

    /** Split one blob into HTTP requests at each intermediate \\r\\n\\r\\n (keep final empty line with last req). */
    private static List<PayloadTemplate.Part> splitHttpRequests(PayloadTemplate.Part single) {
        String s = new String(single.data, StandardCharsets.ISO_8859_1);
        List<PayloadTemplate.Part> out = new ArrayList<PayloadTemplate.Part>();
        int from = 0;
        while (from < s.length()) {
            int idx = s.indexOf("\r\n\r\n", from);
            if (idx < 0) {
                String tail = s.substring(from);
                if (!tail.isEmpty()) out.add(new PayloadTemplate.Part(tail.getBytes(StandardCharsets.ISO_8859_1), out.isEmpty() ? single.delayBeforeMs : 80));
                break;
            }
            String chunk = s.substring(from, idx + 4); // include the blank line
            out.add(new PayloadTemplate.Part(chunk.getBytes(StandardCharsets.ISO_8859_1), out.isEmpty() ? single.delayBeforeMs : 80));
            from = idx + 4;
        }
        if (out.isEmpty()) out.add(single);
        return out;
    }

    /** True when we are dialing a different host/port than the real OpenVPN target (HTTP proxy path). */
    private boolean isHttpProxyDial() {
        if (blank(cfg.dialHost) || blank(cfg.targetHost)) return false;
        if (!cfg.dialHost.equalsIgnoreCase(cfg.targetHost)) return true;
        return cfg.dialPort != cfg.targetPort;
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    private static boolean isHandshakeOk(int code, String status) {
        if (code == 101) return true;
        if (code >= 200 && code < 300) return true;
        if (status != null) {
            String low = status.toLowerCase();
            if (low.contains("connection established")) return true;
            if (low.contains("101") && low.contains("switch")) return true;
            // Some injectors / CDN edges return odd lines but leave the socket usable
            if (low.contains("200") && (low.contains("ok") || low.contains("connection"))) return true;
        }
        return false;
    }

    /**
     * Drain one intermediate HTTP response (status + headers + small body) so the next
     * request in a multi-request payload can proceed on the same socket.
     */
    private static void drainOneHttpExchange(InputStream in) throws IOException {
        HeaderBlock hb = readHeaderBlock(in);
        int len = contentLength(hb.headers);
        if (len > 0 && len < 256 * 1024) {
            long left = len;
            byte[] buf = new byte[4096];
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                left -= n;
            }
        } else if (len < 0 && hb.headers.toLowerCase().contains("transfer-encoding: chunked")) {
            // Best-effort: skip one chunked body until 0\r\n\r\n
            skipChunked(in);
        }
        // len == 0 or unknown: headers only (typical CONNECT / upgrade) – stop here
    }

    private static void skipChunked(InputStream in) throws IOException {
        while (true) {
            String line = readLine(in);
            if (line == null) return;
            int sc = line.indexOf(';');
            String hex = (sc >= 0 ? line.substring(0, sc) : line).trim();
            int size;
            try { size = Integer.parseInt(hex, 16); } catch (Exception e) { return; }
            if (size == 0) {
                // trailing headers
                while (true) {
                    String t = readLine(in);
                    if (t == null || t.isEmpty()) return;
                }
            }
            long left = size + 2; // data + CRLF
            byte[] buf = new byte[4096];
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) return;
                left -= n;
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int prev = 0;
        while (b.size() < MAX_HEADER) {
            int c = in.read();
            if (c < 0) return b.size() == 0 ? null : b.toString(StandardCharsets.ISO_8859_1.name());
            if (prev == '\r' && c == '\n') {
                byte[] arr = b.toByteArray();
                return new String(arr, 0, Math.max(0, arr.length - 1), StandardCharsets.ISO_8859_1);
            }
            b.write(c);
            prev = c;
        }
        return b.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static final class HeaderBlock {
        final String statusLine;
        final String headers;
        HeaderBlock(String s, String h) { statusLine = s; headers = h; }
    }

    private static HeaderBlock readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int m = 0;
        while (buf.size() < MAX_HEADER) {
            int b = in.read();
            if (b < 0) throw new EOFException("Server closed the connection during handshake");
            buf.write(b);
            if ((m == 0 || m == 2) && b == '\r') m++;
            else if ((m == 1 || m == 3) && b == '\n') m++;
            else m = (b == '\r') ? 1 : 0;
            if (m == 4) {
                String all = new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
                int eol = all.indexOf("\r\n");
                String status = eol > 0 ? all.substring(0, eol) : all.trim();
                return new HeaderBlock(status, all);
            }
        }
        throw new IOException("Handshake response too large");
    }

    /** Reads up to and including the blank line that ends an HTTP header block; returns the status line. */
    private static String readHeaders(InputStream in) throws IOException {
        return readHeaderBlock(in).statusLine;
    }

    private static int contentLength(String headers) {
        for (String line : headers.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                try { return Integer.parseInt(line.substring(c + 1).trim()); } catch (Exception ignored) { }
            }
        }
        return -1;
    }

    private static int statusCode(String statusLine) {
        if (statusLine == null) return -1;
        String[] p = statusLine.split(" ", 3);
        if (p.length >= 2 && p[0].startsWith("HTTP/")) {
            try { return Integer.parseInt(p[1].trim()); } catch (NumberFormatException ignored) { }
        }
        return -1;
    }

    // ── relays ─────────────────────────────────────────────────────────

    private void relayRaw(final Socket client, final InputStream upIn, final OutputStream upOut, final Socket up) throws IOException {
        Thread c2u = new Thread(new Runnable() {
            @Override public void run() {
                try { pump(client.getInputStream(), upOut); } catch (IOException ignored) { }
                finally { closeQuietly(client); closeQuietly(up); }
            }
        }, "ovpn-relay-c2u");
        c2u.setDaemon(true);
        c2u.start();
        try { pump(upIn, client.getOutputStream()); } catch (IOException ignored) { }
    }

    private static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    private void relayWebSocket(final Socket client, final InputStream upIn, final OutputStream upOut, final Socket up) throws IOException {
        final Object writeLock = new Object();
        Thread c2u = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream cin = client.getInputStream();
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = cin.read(buf)) >= 0) {
                        if (n == 0) continue;
                        synchronized (writeLock) { writeFrame(upOut, 0x2, buf, 0, n); }
                    }
                } catch (IOException ignored) {
                } finally { closeQuietly(client); closeQuietly(up); }
            }
        }, "ovpn-relay-c2u");
        c2u.setDaemon(true);
        c2u.start();

        DataInputStream din = new DataInputStream(upIn);
        OutputStream cout = client.getOutputStream();
        try {
            while (true) {
                int b0 = din.read();
                if (b0 < 0) return;
                int b1 = din.read();
                if (b1 < 0) return;
                int op = b0 & 0x0f;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7f;
                if (len == 126) len = din.readUnsignedShort();
                else if (len == 127) len = din.readLong();
                if (len < 0 || len > MAX_FRAME) throw new IOException("WebSocket frame too large");
                byte[] mask = null;
                if (masked) { mask = new byte[4]; din.readFully(mask); }
                byte[] data = new byte[(int) len];
                din.readFully(data);
                if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
                if (op == 0x8) return;                       // close
                if (op == 0x9) {                             // ping -> pong
                    synchronized (writeLock) { writeFrame(upOut, 0xA, data, 0, data.length); }
                } else if (op == 0xA) {
                    // pong: ignore
                } else {                                      // 0 continuation, 1 text, 2 binary
                    cout.write(data);
                    cout.flush();
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** Writes one masked (client->server) FIN frame. */
    static void writeFrame(OutputStream out, int opcode, byte[] data, int off, int len) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(len + 14);
        f.write(0x80 | opcode);
        if (len < 126) {
            f.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            f.write(0x80 | 126);
            f.write((len >> 8) & 0xFF);
            f.write(len & 0xFF);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) (((long) len >> (8 * i)) & 0xFF));
        }
        byte[] mask = new byte[4];
        RNG.nextBytes(mask);
        f.write(mask);
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) masked[i] = (byte) (data[off + i] ^ mask[i & 3]);
        f.write(masked);
        out.write(f.toByteArray());
        out.flush();
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) { }
    }
}
