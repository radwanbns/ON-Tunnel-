import com.ontunnel.app.ovpn.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Fake "Cloudflare + raw OpenVPN backend": req1 GET /cdn-cgi/trace -> 200 + body, req2 ACL / -> 101, then raw echo (UPPERCASE). */
public class PayloadScenarios {
    static int fails = 0;
    static void check(String n, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + n); if (!ok) fails++; }

    static String hdr(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(); int c;
        while ((c = in.read()) >= 0) { sb.append((char) c); if (sb.length() >= 4 && sb.substring(sb.length() - 4).equals("\r\n\r\n")) break; }
        return sb.toString();
    }

    /** bodyStyle: 0 = Content-Length, 1 = chunked, 2 = no length (close-delimited style, no CRLFCRLF in body) */
    static ServerSocket server(final int bodyStyle, final List<String> log) throws Exception {
        final ServerSocket ss = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> { try { while (true) { final Socket s = ss.accept(); new Thread(() -> {
            try {
                InputStream in = new BufferedInputStream(s.getInputStream()); OutputStream out = s.getOutputStream();
                String r1 = hdr(in); log.add(r1.split("\r\n")[0]);
                // Read request #2 before replying to #1: validates send-all-first injector behavior.
                String r2 = hdr(in); log.add(r2.split("\r\n")[0]);
                String body = "fl=1f1\nh=example.com\nip=1.2.3.4\nts=1.0\nvisit_scheme=https\nuag=x\ncolo=CDG\nhttp=http/1.1\nloc=MA\ntls=TLSv1.3\nsni=plaintext\nwarp=off\ngateway=off\nrbi=off\nkex=X25519\n";
                if (bodyStyle == 0) out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + body.length() + "\r\n\r\n" + body).getBytes());
                else if (bodyStyle == 1) out.write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + Integer.toHexString(body.length()) + "\r\n" + body + "\r\n0\r\n\r\n").getBytes());
                else out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n" + body).getBytes());
                out.flush();
                out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".getBytes()); out.flush();
                byte[] b = new byte[4096]; int n;   // raw backend: echo upper-case
                while ((n = in.read(b)) >= 0) { out.write(new String(b, 0, n).toUpperCase().getBytes()); out.flush(); }
            } catch (Exception e) { } }).start(); } } catch (Exception e) { } });
        t.setDaemon(true); t.start(); return ss;
    }

    static String roundTrip(int port, String data, int expect) throws Exception {
        Socket c = new Socket("127.0.0.1", port); c.setSoTimeout(6000);
        c.getOutputStream().write(data.getBytes()); c.getOutputStream().flush();
        byte[] buf = new byte[expect]; new DataInputStream(c.getInputStream()).readFully(buf); c.close(); return new String(buf);
    }

    static final String PAYLOAD = "GET /cdn-cgi/trace HTTP/1.1[crlf]Host: [host][crlf][crlf]ACL / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: Websocket[crlf]Connection: Keep-Alive[crlf]User-Agent: [ua][crlf]Upgrade: websocket[crlf][crlf]";

    /** Server variant: raw-after-101 backend whose first reply is a given status (for rejection tests). */
    static ServerSocket simple(final String reply, final boolean thenEcho) throws Exception {
        final ServerSocket ss = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> { try { while (true) { final Socket s = ss.accept(); new Thread(() -> {
            try {
                InputStream in = new BufferedInputStream(s.getInputStream()); OutputStream out = s.getOutputStream();
                hdr(in);
                if (reply != null) { out.write(reply.getBytes()); out.flush(); }
                if (!thenEcho) { Thread.sleep(300); s.close(); return; }
                byte[] b = new byte[4096]; int n;
                while ((n = in.read(b)) >= 0) { out.write(new String(b, 0, n).toUpperCase().getBytes()); out.flush(); }
            } catch (Exception e) { } }).start(); } } catch (Exception e) { } });
        t.setDaemon(true); t.start(); return ss;
    }

    static TransportProxy proxy(TransportConfig c, List<String> errs) throws Exception {
        return new TransportProxy(c, InetAddress.getByName("127.0.0.1"), errs::add);
    }

    static TransportConfig base(int port, int mode, String payload) {
        TransportConfig c = new TransportConfig();
        c.mode = mode; c.dialHost = "127.0.0.1"; c.dialPort = port; c.targetHost = "vpn.example.com"; c.targetPort = 443; c.payload = payload;
        return c;
    }

    public static void main(String[] a) throws Exception {
        // 1) the reported payload, three kinds of decoy reply body -> must end up RAW after the 101
        for (int style = 0; style <= 2; style++) {
            List<String> log = Collections.synchronizedList(new ArrayList<String>());
            ServerSocket srv = server(style, log);
            TransportProxy p = proxy(base(srv.getLocalPort(), TransportConfig.MODE_PAYLOAD, PAYLOAD), new ArrayList<String>());
            int port = p.start();
            String got;
            try { got = roundTrip(port, "openvpn-hello", 13); } catch (Exception e) { got = "<" + e.getClass().getSimpleName() + ">"; }
            check("HTTP-payload mode, decoy body style " + style + ": raw stream after 101", got.equals("OPENVPN-HELLO"));
            check("  both requests reached the server in order", log.size() == 2 && log.get(0).startsWith("GET /cdn-cgi/trace") && log.get(1).startsWith("ACL /"));
            p.stop();
        }

        // 2) WebSocket mode + custom request + framing OFF (injector-style server) -> raw
        {
            List<String> log = Collections.synchronizedList(new ArrayList<String>());
            ServerSocket srv = server(0, log);
            TransportConfig c = base(srv.getLocalPort(), TransportConfig.MODE_WEBSOCKET, PAYLOAD);
            c.wsFraming = false;
            TransportProxy p = proxy(c, new ArrayList<String>());
            String got; try { got = roundTrip(p.start(), "openvpn-hello", 13); } catch (Exception e) { got = "<" + e.getClass().getSimpleName() + ">"; }
            check("WebSocket mode, framing OFF, custom request: raw stream", got.equals("OPENVPN-HELLO"));
            p.stop();
        }

        // 3) explicit [split] fragments of ONE request must not wait for a reply between fragments
        {
            ServerSocket srv = simple("HTTP/1.1 101 Switching Protocols\r\n\r\n", true);
            TransportConfig c = base(srv.getLocalPort(), TransportConfig.MODE_PAYLOAD, "GET / HTTP/1.1[crlf][split]Host: [host][crlf]Upgrade: websocket[crlf][split][crlf]");
            TransportProxy p = proxy(c, new ArrayList<String>());
            long t0 = System.currentTimeMillis();
            String got; try { got = roundTrip(p.start(), "abc", 3); } catch (Exception e) { got = "<" + e.getClass().getSimpleName() + ">"; }
            long ms = System.currentTimeMillis() - t0;
            check("[split] fragments: works and is fast (" + ms + " ms)", got.equals("ABC") && ms < 3000);
            p.stop();
        }

        // 4) clear HTTP error on the final reply -> connection closed + readable error (no silent garbage)
        {
            ServerSocket srv = simple("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n", false);
            List<String> errs = Collections.synchronizedList(new ArrayList<String>());
            TransportProxy p = proxy(base(srv.getLocalPort(), TransportConfig.MODE_PAYLOAD, "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]"), errs);
            Socket c = new Socket("127.0.0.1", p.start()); c.setSoTimeout(4000);
            int r = c.getInputStream().read(); Thread.sleep(300);
            check("403 on final reply: closed with a clear error " + errs, r == -1 && !errs.isEmpty() && errs.get(0).contains("403"));
            p.stop();
        }

        // 5) classic CONNECT: 200 Connection established then raw
        {
            ServerSocket srv = simple("HTTP/1.1 200 Connection established\r\n\r\n", true);
            TransportConfig c = base(srv.getLocalPort(), TransportConfig.MODE_PAYLOAD, "CONNECT [host_port] HTTP/1.1[crlf]Host: [host_port][crlf][crlf]");
            TransportProxy p = proxy(c, new ArrayList<String>());
            String got; try { got = roundTrip(p.start(), "xyz", 3); } catch (Exception e) { got = "<" + e.getClass().getSimpleName() + ">"; }
            check("CONNECT 200 then raw", got.equals("XYZ"));
            p.stop();
        }

        // 6) the decoy GET is answered but the ACL request gets a 400 -> must be reported, not tunnelled
        {
            final ServerSocket ss = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(() -> { try { while (true) { final Socket s = ss.accept(); new Thread(() -> { try {
                InputStream in = new BufferedInputStream(s.getInputStream()); OutputStream out = s.getOutputStream();
                hdr(in); out.write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nfl=1".getBytes()); out.flush();
                hdr(in); out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes()); out.flush(); Thread.sleep(300); s.close();
            } catch (Exception e) { } }).start(); } } catch (Exception e) { } });
            t.setDaemon(true); t.start();
            List<String> errs = Collections.synchronizedList(new ArrayList<String>());
            TransportProxy p = proxy(base(ss.getLocalPort(), TransportConfig.MODE_PAYLOAD, PAYLOAD), errs);
            Socket c = new Socket("127.0.0.1", p.start()); c.setSoTimeout(4000);
            int r = c.getInputStream().read(); Thread.sleep(300);
            check("decoy 200 then 400: reported as 400 " + errs, r == -1 && !errs.isEmpty() && errs.get(0).contains("400"));
            p.stop();
        }

        // 7) no reply at all to the final request -> injector-style, goes raw after the wait
        {
            ServerSocket srv = simple(null, true);
            TransportProxy p = proxy(base(srv.getLocalPort(), TransportConfig.MODE_PAYLOAD, "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]"), new ArrayList<String>());
            int port = p.start();
            Socket c = new Socket("127.0.0.1", port); c.setSoTimeout(16000);
            c.getOutputStream().write("late".getBytes()); c.getOutputStream().flush();
            byte[] buf = new byte[4]; String got;
            try { new DataInputStream(c.getInputStream()).readFully(buf); got = new String(buf); } catch (Exception e) { got = "<" + e.getClass().getSimpleName() + ">"; }
            check("no reply: falls back to raw after the wait", got.equals("LATE"));
            p.stop();
        }

        System.out.println(fails == 0 ? "ALL PAYLOAD SCENARIOS PASSED" : fails + " SCENARIO(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
