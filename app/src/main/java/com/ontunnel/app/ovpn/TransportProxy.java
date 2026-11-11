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
    private final InetAddress[] dialAddrs;
    private final Listener listener;
    private ServerSocket server;
    private volatile boolean running;
    private final Set<Socket> open = Collections.synchronizedSet(new HashSet<Socket>());

    public TransportProxy(TransportConfig cfg, InetAddress dialAddr, Listener listener) {
        this(cfg, new InetAddress[]{dialAddr}, listener);
    }

    /** All candidate addresses of the dial host are tried in order for every upstream connection. */
    public TransportProxy(TransportConfig cfg, InetAddress[] dialAddrs, Listener listener) {
        this.cfg = cfg;
        this.dialAddrs = dialAddrs;
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

    /** Handshake failure. {@code permanent} = retrying cannot help (the server answered 3xx/4xx). */
    private static final class Rejected extends IOException {
        private static final long serialVersionUID = 1L;
        final boolean permanent;
        Rejected(String message, boolean permanent) { super(message); this.permanent = permanent; }
    }

    /** An upstream connection whose payload / WebSocket handshake succeeded. */
    private static final class Session {
        final Socket up;
        final BufferedInputStream upIn;
        final OutputStream upOut;
        final boolean framed;
        Session(Socket up, BufferedInputStream in, OutputStream out, boolean framed) {
            this.up = up; this.upIn = in; this.upOut = out; this.framed = framed;
        }
    }

    private void handle(Socket client) {
        Session s = null;
        try {
            client.setTcpNoDelay(true);
            // The upstream handshake is retried here, BEFORE anything is read from OpenVPN. OpenVPN's own
            // bytes simply wait in the socket buffer, so a dropped dial / reset / 5xx during the handshake
            // never shows up as "Retrying connection" in OpenVPN.
            IOException lastErr = null;
            int attempts = Math.max(1, cfg.attempts);
            for (int i = 0; i < attempts && running; i++) {
                try {
                    s = connectAndHandshake();
                    lastErr = null;
                    break;
                } catch (IOException e) {
                    lastErr = e;
                    if (e instanceof Rejected && ((Rejected) e).permanent) break;
                    if (i + 1 < attempts && !pause(500L * (i + 1))) break;
                }
            }
            if (s == null) throw lastErr != null ? lastErr : new IOException("Relay stopped");

            // Clear read timeouts for the long-lived tunnel
            try { s.up.setSoTimeout(0); } catch (Exception ignored) { }
            try { client.setSoTimeout(0); } catch (Exception ignored) { }

            if (s.framed) {
                relayWebSocket(client, s.upIn, s.upOut, s.up);
            } else {
                relayRaw(client, s.upIn, s.upOut, s.up);
            }
        } catch (IOException e) {
            if (running && listener != null) listener.onError(e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            closeQuietly(client);
            if (s != null) { closeQuietly(s.up); open.remove(s.up); }
            open.remove(client);
        }
    }

    private static boolean pause(long ms) {
        try { Thread.sleep(ms); return true; } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    /** Dials the server and runs the payload / WebSocket handshake. Closes the socket itself on failure. */
    private Session connectAndHandshake() throws IOException {
        Socket up = openUpstream();
        open.add(up);
        boolean done = false;
        try {
            BufferedInputStream upIn = new BufferedInputStream(up.getInputStream(), 16 * 1024);
            OutputStream upOut = up.getOutputStream();

            final boolean wsMode = cfg.mode == TransportConfig.MODE_WEBSOCKET;
            // MODE_PAYLOAD and MODE_LEGACY are lenient: any 2xx is a valid "go raw" signal.
            final boolean lenient = !wsMode;
            String wsKey = null;
            if (wsMode) {
                byte[] k = new byte[16];
                RNG.nextBytes(k);
                wsKey = Base64.getEncoder().encodeToString(k);
            }
            // Match the working injector flow: send all payload fragments/requests first,
            // then consume HTTP replies. Some proxies require the complete multi-request
            // payload (for example GET /cdn-cgi/trace followed by ACL /) before replying.
            List<PayloadTemplate.Part> parts = buildPayloadParts(wsKey);
            for (PayloadTemplate.Part part : parts) {
                if (part.delayBeforeMs > 0 && !pause(part.delayBeforeMs)) throw new IOException("Interrupted");
                if (part.data.length == 0) continue;
                upOut.write(part.data);
                upOut.flush();
            }

            int expectedReplies = countHttpRequests(parts);
            Reply last = null;
            Reply pending = null;
            boolean noReply = false;
            for (int i = 0; i < expectedReplies; i++) {
                boolean isLast = i == expectedReplies - 1;
                up.setSoTimeout(wsMode ? 15000 : cfg.mode == TransportConfig.MODE_LEGACY ? 6000 : 10000);
                Reply r;
                try {
                    r = pending != null ? pending : readReply(upIn);
                    pending = null;
                } catch (java.net.SocketTimeoutException ste) {
                    if (lenient && (i == 0 || last != null)) { noReply = last == null; break; }
                    throw new IOException("No reply from the server to payload request #" + (i + 1));
                } catch (EOFException eof) {
                    throw new IOException("Server closed the connection after payload request #" + (i + 1));
                }
                last = r;
                if (r.code == 101) break;
                if (!isLast) {
                    try {
                        pending = drainBody(up, upIn, r);
                    } catch (java.net.SocketTimeoutException ste) {
                        if (!lenient) throw new IOException("No reply after payload request #" + (i + 1));
                        break;
                    }
                }
            }

            if (!noReply) {
                boolean ok = last != null && (last.code == 101 || (lenient && last.code >= 200 && last.code < 300));
                if (!ok) {
                    boolean permanent = last != null && last.code >= 300 && last.code < 500;
                    throw new Rejected("Server rejected the " + (wsMode ? "WebSocket upgrade" : "payload")
                            + ": " + (last == null ? "no HTTP reply" : last.statusLine), permanent);
                }
            }

            // Framing is applied ONLY in WebSocket mode after a real 101 reply:
            //  AUTO   - framed only when the server proves it speaks RFC 6455 (sends Sec-WebSocket-Accept);
            //           injector-style servers that turn into a plain TCP pipe after the 101 stay raw.
            //  ALWAYS - framed after every 101.   NEVER (or wsFraming=false) - always raw.
            // HTTP-payload and legacy modes are always raw: framing a plain pipe would corrupt OpenVPN's stream.
            boolean framed = false;
            if (wsMode && cfg.wsFraming && cfg.framingMode != TransportConfig.FRAMING_NEVER
                    && last != null && last.code == 101) {
                framed = cfg.framingMode == TransportConfig.FRAMING_ALWAYS
                        || headerValue(last.headers, "Sec-WebSocket-Accept") != null;
            }
            done = true;
            return new Session(up, upIn, upOut, framed);
        } finally {
            if (!done) { closeQuietly(up); open.remove(up); }
        }
    }

    /** Value of an HTTP header (first match, case-insensitive) in a header block, or null. */
    static String headerValue(String block, String name) {
        if (block == null) return null;
        for (String line : block.split("\r?\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) return line.substring(c + 1).trim();
        }
        return null;
    }

    // ── upstream connection ────────────────────────────────────────────

    /** Tries every candidate address of the dial host in order; the first that connects (and passes TLS) wins. */
    private Socket openUpstream() throws IOException {
        IOException last = null;
        for (InetAddress addr : dialAddrs) {
            if (!running) break;
            try {
                return openUpstream(addr);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("No server address to connect to");
    }

    private Socket openUpstream(InetAddress addr) throws IOException {
        Socket raw = new Socket();
        raw.setTcpNoDelay(true);
        try { raw.setKeepAlive(true); } catch (Exception ignored) { }
        raw.connect(new InetSocketAddress(addr, cfg.dialPort), 12000);
        // Handshake read timeout (cleared after successful payload response)
        try { raw.setSoTimeout(15000); } catch (Exception ignored) { }
        if (!cfg.tls) return raw;
        try {
            // Legacy (HTTP Custom style) never validates the certificate: SNI may be a front domain.
            boolean trustAll = cfg.allowInsecure || cfg.mode == TransportConfig.MODE_LEGACY;
            SSLContext ctx = SSLContext.getInstance("TLS");
            TrustManager[] tm = trustAll ? new TrustManager[]{new TrustAll()} : null;
            ctx.init(null, tm, RNG);
            String peer = cfg.sni != null && !cfg.sni.trim().isEmpty() ? cfg.sni.trim() : cfg.dialHost;
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, peer, cfg.dialPort, true);
            SSLParameters p = s.getSSLParameters();
            if (!isIpLiteral(peer)) {
                try { p.setServerNames(Collections.singletonList(new SNIHostName(peer))); }
                catch (IllegalArgumentException ignored) { }
            }
            if (!trustAll) p.setEndpointIdentificationAlgorithm("HTTPS");
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
        // Keep the payload intact. Only explicit split markers create fragments;
        // automatic CRLFCRLF splitting changes injector semantics.
        return PayloadTemplate.expand(tpl, cfg.targetHost, cfg.targetPort, wsKey);
    }

    /** Count complete HTTP requests in the expanded payload, regardless of explicit split fragments. */
    private static int countHttpRequests(List<PayloadTemplate.Part> parts) {
        StringBuilder all = new StringBuilder();
        for (PayloadTemplate.Part part : parts) {
            all.append(new String(part.data, StandardCharsets.ISO_8859_1));
        }
        String value = all.toString();
        int count = 0;
        for (int i = 0; i < value.length();) {
            if (value.startsWith("\r\n\r\n", i)) { count++; i += 4; }
            else if (value.startsWith("\n\n", i)) { count++; i += 2; }
            else i++;
        }
        return Math.max(1, count);
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
    private static Reply drainBody(Socket up, InputStream in, Reply r) throws IOException {
        if (r.code == 204 || r.code == 304 || (r.code >= 100 && r.code < 200)) return null;
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
            // No Content-Length: discard the decoy body until the next HTTP status line.
            return scanNextReply(in);
        }
        return null;
    }

    /** Find and parse the next HTTP response after an intermediate response with unknown body length. */
    private static Reply scanNextReply(InputStream in) throws IOException {
        final String marker = "HTTP/";
        int matched = 0;
        long scanned = 0;
        while (scanned++ < 65536) {
            int c = in.read();
            if (c < 0) throw new EOFException("Connection closed before the next HTTP reply");
            char ch = (char) c;
            matched = ch == marker.charAt(matched) ? matched + 1 : (ch == marker.charAt(0) ? 1 : 0);
            if (matched == marker.length()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                bytes.write(marker.getBytes(StandardCharsets.ISO_8859_1));
                int state = 0;
                while (bytes.size() < MAX_HEADER) {
                    int b = in.read();
                    if (b < 0) throw new EOFException("Connection closed during the next HTTP reply");
                    bytes.write(b);
                    if ((state == 0 || state == 2) && b == '\r') state++;
                    else if ((state == 1 || state == 3) && b == '\n') state++;
                    else state = b == '\r' ? 1 : 0;
                    if (state == 4) break;
                }
                String headers = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
                int eol = headers.indexOf("\r\n");
                String status = eol > 0 ? headers.substring(0, eol) : headers.trim();
                int code = statusCode(status);
                if (code >= 100) return new Reply(status, headers, code);
                matched = 0;
            }
        }
        throw new IOException("Could not find the next HTTP reply in the intermediate response body");
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
        try { pumpOpenVpnDown(upIn, client.getOutputStream()); } catch (IOException ignored) { }
    }

    /**
     * Injector-style gateways may prepend printable text after HTTP 101 (for example
     * "Content-Length: 104857600000\\r\\n\\r\\n") before the first OpenVPN TCP packet.
     * OpenVPN TCP packets start with a small binary length prefix, so discard only the
     * leading printable ASCII/CRLF bytes and then relay the stream byte-for-byte.
     */
    private static void pumpOpenVpnDown(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16 * 1024];
        boolean leading = true;
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n == 0) continue;
            int off = 0;
            if (leading) {
                while (off < n && isLeadingTextByte(buf[off])) off++;
                if (off < n) leading = false;
            }
            if (off < n) {
                out.write(buf, off, n - off);
                out.flush();
            }
        }
    }

    private static boolean isLeadingTextByte(byte value) {
        int b = value & 0xff;
        return (b >= 0x20 && b <= 0x7e) || b == 0x0d || b == 0x0a;
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
