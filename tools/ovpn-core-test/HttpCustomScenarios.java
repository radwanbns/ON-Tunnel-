import com.ontunnel.app.ovpn.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;

/**
 * Scenarios for: WebSocket payload against raw-pipe servers (HTTP Custom style), transparent in-relay retry,
 * the "HTTP Custom (legacy)" mode, multi-address dialing and the extra payload tags.
 */
public class HttpCustomScenarios {
    static int fails = 0;
    static void check(String n, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + n); if (!ok) fails++; }

    static String hdr(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(); int c;
        while ((c = in.read()) >= 0) { sb.append((char) c); if (sb.length() >= 4 && sb.substring(sb.length() - 4).equals("\r\n\r\n")) break; }
        return sb.toString();
    }

    interface Handler { void run(int connectionNo, Socket s, InputStream in, OutputStream out) throws Exception; }

    static ServerSocket server(final Handler h) throws Exception {
        final ServerSocket ss = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        final AtomicInteger n = new AtomicInteger();
        Thread t = new Thread(() -> { try { while (true) { final Socket s = ss.accept(); final int no = n.incrementAndGet();
            new Thread(() -> { try { h.run(no, s, new BufferedInputStream(s.getInputStream()), s.getOutputStream()); } catch (Exception e) { } }).start(); } } catch (Exception e) { } });
        t.setDaemon(true); t.start(); return ss;
    }

    static void echoUpper(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[4096]; int n;
        while ((n = in.read(b)) >= 0) { out.write(new String(b, 0, n).toUpperCase().getBytes()); out.flush(); }
    }

    static TransportConfig base(int port, int mode, String payload) {
        TransportConfig c = new TransportConfig();
        c.mode = mode; c.dialHost = "127.0.0.1"; c.dialPort = port; c.targetHost = "vpn.example.com"; c.targetPort = 443; c.payload = payload;
        return c;
    }

    static String roundTrip(int port, String data, int expect) {
        try {
            Socket c = new Socket("127.0.0.1", port); c.setSoTimeout(8000);
            c.getOutputStream().write(data.getBytes()); c.getOutputStream().flush();
            byte[] buf = new byte[expect]; new DataInputStream(c.getInputStream()).readFully(buf); c.close(); return new String(buf);
        } catch (Exception e) { return "<" + e.getClass().getSimpleName() + ">"; }
    }

    static TransportProxy proxy(TransportConfig c, List<String> errs) throws Exception {
        return new TransportProxy(c, InetAddress.getByName("127.0.0.1"), errs::add);
    }

    public static void main(String[] a) throws Exception {
        final String WS_PAYLOAD = "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]Sec-WebSocket-Key: [ws_key][crlf]Sec-WebSocket-Version: 13[crlf][crlf]";

        // 1) THE reported case: WebSocket mode (default framing = AUTO), injector-style server that answers
        //    101 WITHOUT Sec-WebSocket-Accept and then is a plain TCP pipe. Must be raw, not framed.
        {
            ServerSocket srv = server((no, s, in, out) -> {
                hdr(in);
                out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".getBytes()); out.flush();
                echoUpper(in, out);
            });
            TransportProxy p = proxy(base(srv.getLocalPort(), TransportConfig.MODE_WEBSOCKET, WS_PAYLOAD), new ArrayList<String>());
            check("WS mode + AUTO framing + raw-pipe server (101 w/o Accept): raw stream", roundTrip(p.start(), "openvpn-hello", 13).equals("OPENVPN-HELLO"));
            p.stop();

            // default (empty) custom request must work too
            TransportProxy p2 = proxy(base(srv.getLocalPort(), TransportConfig.MODE_WEBSOCKET, ""), new ArrayList<String>());
            check("WS mode, empty custom request, raw-pipe server", roundTrip(p2.start(), "abc", 3).equals("ABC"));
            p2.stop();
        }

        // 2) framing policy NEVER on a server that does send Accept -> stays raw (server here is a raw pipe that also sends Accept)
        {
            ServerSocket srv = server((no, s, in, out) -> {
                hdr(in);
                out.write("HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: x\r\n\r\n".getBytes()); out.flush();
                echoUpper(in, out);
            });
            TransportConfig c = base(srv.getLocalPort(), TransportConfig.MODE_WEBSOCKET, WS_PAYLOAD);
            c.framingMode = TransportConfig.FRAMING_NEVER;
            TransportProxy p = proxy(c, new ArrayList<String>());
            check("framing NEVER: raw even if Accept is present", roundTrip(p.start(), "xyz", 3).equals("XYZ"));
            p.stop();
            TransportConfig c2 = base(srv.getLocalPort(), TransportConfig.MODE_WEBSOCKET, WS_PAYLOAD);
            c2.framingMode = TransportConfig.FRAMING_AUTO;
            TransportProxy p2 = proxy(c2, new ArrayList<String>());
            // AUTO + Accept present -> framed -> the raw-echo server answers with raw bytes that are NOT a valid frame
            String got = roundTrip(p2.start(), "xyz", 3);
            check("framing AUTO: Accept header present => framed (raw echo no longer matches)", !got.equals("XYZ"));
            p2.stop();
        }

        // 3) transparent retry: server drops the first 2 connections, OpenVPN's connection must still succeed
        {
            final AtomicInteger seen = new AtomicInteger();
            ServerSocket srv = server((no, s, in, out) -> {
                seen.incrementAndGet();
                hdr(in);
                if (no <= 2) { s.close(); return; }                       // reset / EOF during handshake
                out.write("HTTP/1.1 101 Switching Protocols\r\n\r\n".getBytes()); out.flush();
                echoUpper(in, out);
            });
            List<String> errs = Collections.synchronizedList(new ArrayList<String>());
            TransportProxy p = proxy(base(srv.getLocalPort(), TransportConfig.MODE_WEBSOCKET, WS_PAYLOAD), errs);
            String got = roundTrip(p.start(), "retry-me", 8);
            check("server drops 2 handshakes: relay retries, client never sees it (" + seen.get() + " dials, errs=" + errs + ")",
                    got.equals("RETRY-ME") && seen.get() == 3 && errs.isEmpty());
            p.stop();
        }

        // 4) transparent retry on a 502 from a CDN edge, but NO retry on a 403 (definitive)
        {
            final AtomicInteger dials = new AtomicInteger();
            ServerSocket srv = server((no, s, in, out) -> {
                dials.incrementAndGet(); hdr(in);
                if (no == 1) { out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes()); out.flush(); s.close(); return; }
                out.write("HTTP/1.1 101 Switching Protocols\r\n\r\n".getBytes()); out.flush();
                echoUpper(in, out);
            });
            TransportProxy p = proxy(base(srv.getLocalPort(), TransportConfig.MODE_PAYLOAD, "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]"), new ArrayList<String>());
            check("502 once then 101: relay recovers silently", roundTrip(p.start(), "ok", 2).equals("OK"));
            p.stop();

            final AtomicInteger d403 = new AtomicInteger();
            ServerSocket s403 = server((no, s, in, out) -> {
                d403.incrementAndGet(); hdr(in);
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes()); out.flush(); s.close();
            });
            List<String> errs = Collections.synchronizedList(new ArrayList<String>());
            TransportProxy p2 = proxy(base(s403.getLocalPort(), TransportConfig.MODE_PAYLOAD, "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]"), errs);
            Socket c = new Socket("127.0.0.1", p2.start()); c.setSoTimeout(5000);
            int r = c.getInputStream().read(); Thread.sleep(200);
            check("403 is definitive: one dial only, clear error [" + errs + "]", r == -1 && d403.get() == 1 && !errs.isEmpty() && errs.get(0).contains("403"));
            p2.stop();
        }

        // 4b) HTTP proxy: do not consume a disposable CONNECT during preflight.
        // The first CONNECT must be the real OpenVPN relay session.
        {
            final AtomicInteger connects = new AtomicInteger();
            ServerSocket proxyServer = server((no, s, in, out) -> {
                connects.incrementAndGet();
                String request = hdr(in);
                if (!request.startsWith("CONNECT vpn.example.com:443")) throw new IOException("Expected CONNECT, got: " + request);
                out.write("HTTP/1.1 200 Connection established\\r\\nProxy-Agent: test\\r\\n\\r\\n".getBytes()); out.flush();
                echoUpper(in, out);
            });
            TransportConfig c = base(proxyServer.getLocalPort(), TransportConfig.MODE_PAYLOAD,
                    "CONNECT [host_port] HTTP/1.1[crlf]Host: [host_port][crlf][crlf]");
            c.deferPreflight = true;
            List<String> errs = Collections.synchronizedList(new ArrayList<String>());
            TransportProxy p = proxy(c, errs);
            int localPort = p.start();
            p.prepare(); // must not open a disposable CONNECT tunnel
            Socket client = new Socket("127.0.0.1", localPort);
            client.setSoTimeout(5000);
            client.getOutputStream().write("http-proxy".getBytes()); client.getOutputStream().flush();
            byte[] answer = new byte[10]; new DataInputStream(client.getInputStream()).readFully(answer);
            client.close();
            check("HTTP proxy: deferred CONNECT uses one real tunnel (dials=" + connects.get() + ", errs=" + errs + ")",
                    new String(answer).equals("HTTP-PROXY") && connects.get() == 1 && errs.isEmpty());
            p.stop();
        }

        // 5) legacy mode: lenient 200 (not 101) then raw; and silence then raw
        {
            ServerSocket s200 = server((no, s, in, out) -> {
                hdr(in); out.write("HTTP/1.1 200 OK\r\nServer: x\r\n\r\n".getBytes()); out.flush(); echoUpper(in, out);
            });
            TransportProxy p = proxy(base(s200.getLocalPort(), TransportConfig.MODE_LEGACY, "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]"), new ArrayList<String>());
            check("legacy: 200 OK reply then raw", roundTrip(p.start(), "legacy", 6).equals("LEGACY"));
            p.stop();

            ServerSocket silent = server((no, s, in, out) -> { hdr(in); echoUpper(in, out); });
            TransportProxy p2 = proxy(base(silent.getLocalPort(), TransportConfig.MODE_LEGACY, "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]"), new ArrayList<String>());
            check("legacy: server never answers => goes raw", roundTrip(p2.start(), "quiet", 5).equals("QUIET"));
            p2.stop();
        }

        // 6) legacy mode over TLS: self-signed cert is accepted (HTTP Custom never validates), PAYLOAD mode is strict
        {
            String ks = java.nio.file.Files.createTempDirectory("hc").resolve("ks.p12").toString();
            Process kt = new ProcessBuilder("keytool", "-genkeypair", "-keystore", ks, "-storetype", "PKCS12", "-storepass", "changeit",
                    "-keypass", "changeit", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=front.example.com", "-validity", "2").redirectErrorStream(true).start();
            kt.getInputStream().readAllBytes(); kt.waitFor();
            KeyStore store = KeyStore.getInstance("PKCS12"); store.load(new FileInputStream(ks), "changeit".toCharArray());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509"); kmf.init(store, "changeit".toCharArray());
            SSLContext sc = SSLContext.getInstance("TLS"); sc.init(kmf.getKeyManagers(), null, null);
            final ServerSocket tls = sc.getServerSocketFactory().createServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
            final List<String> seenSni = Collections.synchronizedList(new ArrayList<String>());
            Thread t = new Thread(() -> { try { while (true) { final Socket s = tls.accept(); new Thread(() -> { try {
                InputStream in = new BufferedInputStream(s.getInputStream()); OutputStream out = s.getOutputStream();
                hdr(in); out.write("HTTP/1.1 101 Switching Protocols\r\n\r\n".getBytes()); out.flush(); echoUpper(in, out);
            } catch (Exception e) { } }).start(); } } catch (Exception e) { } });
            t.setDaemon(true); t.start();

            TransportConfig c = base(tls.getLocalPort(), TransportConfig.MODE_LEGACY, "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]");
            c.tls = true; c.sni = "other-front.example.net"; c.allowInsecure = false;
            TransportProxy p = proxy(c, new ArrayList<String>());
            check("legacy + TLS + mismatching SNI + self-signed cert: connects", roundTrip(p.start(), "tls", 3).equals("TLS"));
            p.stop();

            TransportConfig c2 = base(tls.getLocalPort(), TransportConfig.MODE_PAYLOAD, "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]");
            c2.tls = true; c2.sni = "other-front.example.net"; c2.allowInsecure = false; c2.attempts = 1;
            TransportProxy p2 = proxy(c2, new ArrayList<String>());
            check("payload mode keeps strict TLS (rejects the same server)", !roundTrip(p2.start(), "tls", 3).equals("TLS"));
            p2.stop();
        }

        // 7) several addresses: first one refuses the connection, second one works
        {
            ServerSocket good = server((no, s, in, out) -> { hdr(in); out.write("HTTP/1.1 101 Switching Protocols\r\n\r\n".getBytes()); out.flush(); echoUpper(in, out); });
            ServerSocket dead = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); int deadPort = dead.getLocalPort(); dead.close();
            // same port for both candidates is not possible, so emulate with two dial candidates on the good port:
            // 127.0.0.2 (nothing listens on that address:port) first, then 127.0.0.1.
            TransportConfig c = base(good.getLocalPort(), TransportConfig.MODE_PAYLOAD, "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]");
            TransportProxy p = new TransportProxy(c, new InetAddress[]{InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1")}, m -> { });
            check("multi-address: dead first IP is skipped (dead port " + deadPort + ")", roundTrip(p.start(), "ip", 2).equals("IP"));
            p.stop();
        }

        // 8) payload tags
        {
            String s = new String(PayloadTemplate.expand("A[crlf*2]B[lf*3]C[cr*2]D", "h", 1, "k").get(0).data, "ISO-8859-1");
            check("tags: [crlf*N] [lf*N] [cr*N]", s.equals("A\r\n\r\nB\n\n\nC\r\rD"));
            String n = new String(PayloadTemplate.expand("[netData][crlf]", "vpn.example.com", 443, "").get(0).data, "ISO-8859-1");
            check("tags: [netData] -> CONNECT host:port HTTP/1.1", n.equals("CONNECT vpn.example.com:443 HTTP/1.1\r\n"));
            Set<String> picks = new HashSet<String>();
            for (int i = 0; i < 4; i++) picks.add(PayloadTemplate.replaceTags("Host: [rotate=a.com;b.com]", "h", 1, ""));
            check("tags: [rotate=a;b] cycles " + picks, picks.size() == 2 && picks.contains("Host: a.com") && picks.contains("Host: b.com"));
            List<PayloadTemplate.Part> parts = PayloadTemplate.expand("GET / HTTP/1.1[crlf][instant_split]Host: x[crlf][crlf]", "h", 1, "");
            check("tags: [instant_split] has no delay", parts.size() == 2 && parts.get(1).delayBeforeMs == 0);
        }

        // 9) generated OpenVPN profile never gives up
        {
            String cfg = OvpnConfigTools.buildConfig("client\nremote vpn.example.com 443 tcp\n", 40000, "1.2.3.4", null, null);
            check("relay profile: no connect-retry-max (unlimited retry)", !cfg.contains("connect-retry-max") && cfg.contains("connect-retry 2"));
        }

        System.out.println(fails == 0 ? "ALL HTTP CUSTOM SCENARIOS PASSED" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
