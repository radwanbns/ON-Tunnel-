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
            // ── payload handshake ────────────────────────────────────────
            // Each *complete* HTTP request (ends with a blank line) gets its own reply read.
            // Intermediate replies (decoy GETs such as /cdn-cgi/trace) are drained and ignored;
            // only the LAST reply decides success. A 101 at any point ends the handshake.
            // A part that does not end a request (an explicit [split] fragment) is simply written.
            List<PayloadTemplate.Part> parts = buildPayloadParts(wsKey);
            final boolean wsMode = cfg.mode == TransportConfig.MODE_WEBSOCKET;
            Reply last = null;
            boolean noReply = false;
            for (int i = 0; i < parts.size(); i++) {
                PayloadTemplate.Part part = parts.get(i);
                if (part.delayBeforeMs > 0) {
                    try { Thread.sleep(part.delayBeforeMs); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted");
                    }
                }
                upOut.write(part.data);
                upOut.flush();
                boolean isLast = i == parts.size() - 1;
                if (!isLast && !endsRequest(part.data)) continue; // mid-request fragment: no reply yet

                int attempt = i + 1;
                up.setSoTimeout(isLast ? (wsMode ? 20000 : 10000) : 10000);
                Reply r;
                try {
                    r = readReply(upIn);
                } catch (java.net.SocketTimeoutException ste) {
                    if (isLast && !wsMode) { noReply = true; break; } // injector-style: no answer, go raw
                    throw new IOException("No reply from the server to payload request #" + attempt);
                } catch (EOFException eof) {
                    throw new IOException("Server closed the connection after payload request #" + attempt);
                }
                last = r;
                if (r.code == 101) break;                    // upgraded: raw (or framed) stream starts now
                if (!isLast) drainBody(up, upIn, r);         // decoy reply: discard it
            }

            if (!noReply) {
                boolean ok = last != null && (last.code == 101 || (!wsMode && last.code >= 200 && last.code < 300));
                if (!ok) {
                    throw new IOException("Server rejected the " + (wsMode ? "WebSocket upgrade" : "payload")
                            + ": " + (last == null ? "no HTTP reply" : last.statusLine));
                }
            }

            // Clear read timeouts for the long-lived tunnel
            try { up.setSoTimeout(0); } catch (Exception ignored) { }
            try { client.setSoTimeout(0); } catch (Exception ignored) { }

            // Framing is applied ONLY in WebSocket mode with "WebSocket framing" on, after a real 101.
            // HTTP-payload mode is always raw: injector-style "Upgrade: websocket" payloads switch the
            // connection to a plain TCP pipe, and framing it would corrupt the OpenVPN stream.
            boolean useWs = wsMode && cfg.wsFraming && last != null && last.code == 101;
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

    /** One parsed HTTP reply header block. */
    private static final class Reply {
        final String statusLine;
        final String headers;
        final int code;
        Reply(String s, String h, int c) { statusLine = s; headers = h; code = c; }
    }

    /** True when the bytes end with a blank line, i.e. they complete an HTTP request. */
    private static boolean endsRequest(byte[] d) {
        int n = d.length;
        if (n >= 4 && d[n - 4] == '\r' && d[n - 3] == '\n' && d[n - 2] == '\r' && d[n - 1] == '\n') return true;
        return n >= 2 && d[n - 2] == '\n' && d[n - 1] == '\n';
    }

    /**
     * Reads the next HTTP reply. Leading junk (for example the tail of an earlier decoy body that had no
     * length information) is skipped: the reply starts at the first "HTTP/" in the block.
     */
    private static Reply readReply(InputStream in) throws IOException {
        for (int guard = 0; guard < 16; guard++) {
            HeaderBlock hb = readHeaderBlock(in);
            int idx = hb.headers.indexOf("HTTP/");
            if (idx < 0) continue;                         // pure junk block, keep reading
            String text = idx == 0 ? hb.headers : hb.headers.substring(idx);
            int eol = text.indexOf("\r\n");
            String status = eol > 0 ? text.substring(0, eol) : text.trim();
            return new Reply(status, text, statusCode(status));
        }
        throw new IOException("Server did not send a valid HTTP reply");
    }

    /** Discards the body of an intermediate reply so the next request starts on a clean stream. */
    private static void drainBody(Socket up, InputStream in, Reply r) throws IOException {
        if (r.code == 204 || r.code == 304 || (r.code >= 100 && r.code < 200)) return;
        String low = r.headers.toLowerCase(java.util.Locale.ROOT);
        int len = contentLength(r.headers);
        if (low.contains("transfer-encoding: chunked")) {
            skipChunked(in);
        } else if (len > 0) {
            long left = Math.min(len, 1024 * 1024);
            byte[] buf = new byte[4096];
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new EOFException("Server closed while sending the decoy reply");
                left -= n;
            }
        } else if (len < 0) {
            // No length information: whatever arrives shortly after the headers is body; swallow it.
            int old = up.getSoTimeout();
            try {
                up.setSoTimeout(350);
                byte[] buf = new byte[4096];
                while (in.read(buf) >= 0) { /* discard */ }
                throw new EOFException("Server closed while sending the decoy reply");
            } catch (java.net.SocketTimeoutException quiet) {
                // nothing more to read: body finished
            } finally {
                try { up.setSoTimeout(old); } catch (Exception ignored) { }
            }
        }
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
