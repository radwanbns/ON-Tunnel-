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
            sendPayload(upOut, wsKey);
            String status = readHeaders(upIn);
            int code = statusCode(status);
            boolean ok = cfg.mode == TransportConfig.MODE_WEBSOCKET ? code == 101 : (code == 101 || (code >= 200 && code < 300));
            if (!ok) throw new IOException("Server rejected the " + (cfg.mode == TransportConfig.MODE_WEBSOCKET ? "WebSocket upgrade" : "payload") + ": " + status);

            if (cfg.mode == TransportConfig.MODE_WEBSOCKET) {
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
        raw.connect(new InetSocketAddress(dialAddr, cfg.dialPort), 15000);
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

    private void sendPayload(OutputStream out, String wsKey) throws IOException {
        String tpl = cfg.payload == null ? "" : cfg.payload;
        if (tpl.trim().isEmpty()) {
            if (cfg.mode != TransportConfig.MODE_WEBSOCKET) {
                tpl = "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]";
            } else {
                String host = !blank(cfg.wsHost) ? cfg.wsHost : (!blank(cfg.sni) ? cfg.sni : cfg.targetHost);
                String path = blank(cfg.wsPath) ? "/" : (cfg.wsPath.startsWith("/") ? cfg.wsPath : "/" + cfg.wsPath);
                tpl = "GET " + path + " HTTP/1.1[crlf]Host: " + host + "[crlf]User-Agent: [ua][crlf]"
                        + "Upgrade: websocket[crlf]Connection: Upgrade[crlf]"
                        + "Sec-WebSocket-Key: [ws_key][crlf]Sec-WebSocket-Version: 13[crlf][crlf]";
            }
        }
        List<PayloadTemplate.Part> parts = PayloadTemplate.expand(tpl, cfg.targetHost, cfg.targetPort, wsKey);
        for (PayloadTemplate.Part p : parts) {
            if (p.delayBeforeMs > 0) {
                try { Thread.sleep(p.delayBeforeMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
            }
            out.write(p.data);
            out.flush();
        }
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    /** Reads up to and including the blank line that ends an HTTP header block; returns the status line. */
    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int m = 0; // progress through \r\n\r\n
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
                return eol > 0 ? all.substring(0, eol) : all.trim();
            }
        }
        throw new IOException("Handshake response too large");
    }

    private static int statusCode(String statusLine) {
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
