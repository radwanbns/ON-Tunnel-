import com.ontunnel.app.ovpn.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.*;
import java.security.*;

public class OvpnCoreTest {
    static int fails = 0;
    static final String KS;
    static { try { KS = java.nio.file.Files.createTempDirectory("ovpncore").resolve("ks.p12").toString(); } catch (Exception e) { throw new RuntimeException(e); } }
    static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

    static String readHdr(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(); int c;
        while ((c = in.read()) >= 0) { sb.append((char) c); if (sb.toString().endsWith("\r\n\r\n")) break; }
        return sb.toString();
    }

    // fake server: plain/TLS, mode raw or ws; echoes upper-case
    static ServerSocket startServer(final boolean ws, final boolean tls, final String[] seenHeaders) throws Exception {
        final ServerSocket ss;
        if (tls) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new FileInputStream(KS), "changeit".toCharArray());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, "changeit".toCharArray());
            SSLContext c = SSLContext.getInstance("TLS"); c.init(kmf.getKeyManagers(), null, null);
            ss = c.getServerSocketFactory().createServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        } else ss = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    final Socket s = ss.accept();
                    new Thread(() -> {
                        try {
                            InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream();
                            String h = readHdr(in); seenHeaders[0] = h;
                            out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n".getBytes()); out.flush();
                            if (!ws) {
                                byte[] b = new byte[4096]; int n;
                                while ((n = in.read(b)) >= 0) { out.write(new String(b, 0, n).toUpperCase().getBytes()); out.flush(); }
                            } else {
                                DataInputStream d = new DataInputStream(in);
                                // send a ping first
                                out.write(new byte[]{(byte)0x89, 0x02, 'h', 'i'}); out.flush();
                                while (true) {
                                    int b0 = d.read(); if (b0 < 0) break; int b1 = d.read();
                                    boolean masked = (b1 & 0x80) != 0; long len = b1 & 0x7f;
                                    if (len == 126) len = d.readUnsignedShort(); else if (len == 127) len = d.readLong();
                                    byte[] m = new byte[4]; if (masked) d.readFully(m);
                                    byte[] p = new byte[(int) len]; d.readFully(p);
                                    if (masked) for (int i = 0; i < p.length; i++) p[i] ^= m[i & 3];
                                    int op = b0 & 0xf;
                                    if (op == 0xA) { seenHeaders[0] += "[PONG:" + new String(p) + "]"; continue; }
                                    if (!masked) { seenHeaders[0] += "[UNMASKED]"; }
                                    byte[] up = new String(p).toUpperCase().getBytes();
                                    ByteArrayOutputStream f = new ByteArrayOutputStream();
                                    f.write(0x82);
                                    if (up.length < 126) f.write(up.length);
                                    else if (up.length <= 0xFFFF) { f.write(126); f.write(up.length >> 8); f.write(up.length & 0xff); }
                                    else { f.write(127); for (int i = 7; i >= 0; i--) f.write((int)(((long) up.length >> (8*i)) & 0xff)); }
                                    f.write(up); out.write(f.toByteArray()); out.flush();
                                }
                            }
                        } catch (Exception e) { }
                    }).start();
                }
            } catch (Exception e) { }
        });
        t.setDaemon(true); t.start();
        return ss;
    }

    static String roundTrip(int port, byte[] data, int expect) throws Exception {
        Socket c = new Socket("127.0.0.1", port); c.setSoTimeout(5000);
        c.getOutputStream().write(data); c.getOutputStream().flush();
        byte[] buf = new byte[expect]; DataInputStream d = new DataInputStream(c.getInputStream()); d.readFully(buf); c.close();
        return new String(buf);
    }

    public static void main(String[] a) throws Exception {
        // ---- payload template
        String s = PayloadTemplate.replaceTags("GET / HTTP/1.1[crlf]Host: [HOST][crlf]X: [host_port][lf]", "ex.com", 443, null);
        check("template tags", s.equals("GET / HTTP/1.1\r\nHost: ex.com\r\nX: ex.com:443\n"));
        check("template escapes", PayloadTemplate.replaceTags("A\\r\\nB", "h", 1, null).equals("A\r\nB"));
        List<PayloadTemplate.Part> parts = PayloadTemplate.expand("AAA[split]BBB[delay_split]CCC", "h", 1, null);
        check("split parts", parts.size() == 3 && parts.get(1).delayBeforeMs == 150 && parts.get(2).delayBeforeMs == 1500);

        // ---- payload mode, plain
        String[] seen = new String[]{""};
        ServerSocket srv = startServer(false, false, seen);
        TransportConfig c = new TransportConfig();
        c.mode = TransportConfig.MODE_PAYLOAD; c.dialHost = "127.0.0.1"; c.dialPort = srv.getLocalPort();
        c.targetHost = "vpn.example.com"; c.targetPort = 1194;
        c.payload = "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]";
        final List<String> errs = Collections.synchronizedList(new ArrayList<String>());
        TransportProxy p = new TransportProxy(c, InetAddress.getByName("127.0.0.1"), errs::add);
        int port = p.start();
        check("payload relay echo", roundTrip(port, "hello openvpn".getBytes(), 13).equals("HELLO OPENVPN"));
        check("payload header sent w/ host tag", seen[0].contains("Host: vpn.example.com") && seen[0].contains("Upgrade: websocket"));
        byte[] big = new byte[200000]; Arrays.fill(big, (byte) 'a');
        Socket bc = new Socket("127.0.0.1", port); bc.setSoTimeout(8000);
        new Thread(() -> { try { bc.getOutputStream().write(big); bc.getOutputStream().flush(); } catch (Exception e) {} }).start();
        DataInputStream bd = new DataInputStream(bc.getInputStream()); byte[] bb = new byte[200000]; bd.readFully(bb); bc.close();
        check("payload relay 200KB", bb[0] == 'A' && bb[199999] == 'A');
        p.stop();

        // ---- payload rejected
        final ServerSocket rej = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        new Thread(() -> { try { Socket x = rej.accept(); readHdr(x.getInputStream()); x.getOutputStream().write("HTTP/1.1 403 Forbidden\r\n\r\n".getBytes()); x.close(); } catch (Exception e) {} }).start();
        TransportConfig cr = new TransportConfig(); cr.dialHost = "127.0.0.1"; cr.dialPort = rej.getLocalPort(); cr.targetHost = "h"; cr.payload = "GET / HTTP/1.1[crlf][crlf]";
        errs.clear();
        TransportProxy pr = new TransportProxy(cr, InetAddress.getByName("127.0.0.1"), errs::add);
        int prt = pr.start();
        Socket rc = new Socket("127.0.0.1", prt); rc.setSoTimeout(3000);
        int r = rc.getInputStream().read();
        Thread.sleep(300);
        check("rejected payload closes + reports", r == -1 && !errs.isEmpty() && errs.get(0).contains("403"));
        pr.stop();

        // ---- WebSocket mode, plain
        String[] seen2 = new String[]{""};
        ServerSocket wss = startServer(true, false, seen2);
        TransportConfig w = new TransportConfig();
        w.mode = TransportConfig.MODE_WEBSOCKET; w.dialHost = "127.0.0.1"; w.dialPort = wss.getLocalPort();
        w.targetHost = "vpn.example.com"; w.wsPath = "/vpn"; w.wsHost = "cdn.example.com";
        TransportProxy wp = new TransportProxy(w, InetAddress.getByName("127.0.0.1"), errs::add);
        int wport = wp.start();
        check("ws echo small", roundTrip(wport, "ping-data".getBytes(), 9).equals("PING-DATA"));
        check("ws handshake fields", seen2[0].contains("GET /vpn HTTP/1.1") && seen2[0].contains("Host: cdn.example.com")
                && seen2[0].contains("Sec-WebSocket-Key: ") && seen2[0].contains("Upgrade: websocket"));
        check("ws frames are masked", !seen2[0].contains("[UNMASKED]"));
        check("ws ping answered with pong", seen2[0].contains("[PONG:hi]"));
        byte[] mid = new byte[3000]; Arrays.fill(mid, (byte) 'b');
        check("ws 16-bit length frame", roundTrip(wport, mid, 3000).equals(new String(mid).toUpperCase()));
        byte[] huge = new byte[16 * 1024]; Arrays.fill(huge, (byte) 'c');
        check("ws large chunk", roundTrip(wport, huge, huge.length).charAt(0) == 'C');
        wp.stop();

        // ---- WebSocket over TLS with SNI + allowInsecure
        new File(KS).delete();
        Process kt = new ProcessBuilder("keytool", "-genkeypair", "-keystore", KS, "-storetype", "PKCS12", "-storepass", "changeit",
                "-keypass", "changeit", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost", "-validity", "2").inheritIO().start();
        kt.waitFor();
        String[] seen3 = new String[]{""};
        ServerSocket tss = startServer(true, true, seen3);
        TransportConfig t = new TransportConfig();
        t.mode = TransportConfig.MODE_WEBSOCKET; t.dialHost = "127.0.0.1"; t.dialPort = tss.getLocalPort(); t.targetHost = "x";
        t.tls = true; t.sni = "front.example.com"; t.allowInsecure = true; t.wsHost = "front.example.com";
        TransportProxy tp = new TransportProxy(t, InetAddress.getByName("127.0.0.1"), errs::add);
        int tport = tp.start();
        check("ws over TLS (insecure) echo", roundTrip(tport, "secure".getBytes(), 6).equals("SECURE"));
        tp.stop();
        // strict mode must refuse the self-signed cert
        TransportConfig t2 = new TransportConfig();
        t2.mode = TransportConfig.MODE_WEBSOCKET; t2.dialHost = "127.0.0.1"; t2.dialPort = tss.getLocalPort(); t2.targetHost = "x";
        t2.tls = true; t2.sni = "front.example.com"; t2.allowInsecure = false;
        errs.clear();
        TransportProxy tp2 = new TransportProxy(t2, InetAddress.getByName("127.0.0.1"), errs::add);
        int tport2 = tp2.start();
        Socket sc = new Socket("127.0.0.1", tport2); sc.setSoTimeout(4000);
        int sr = sc.getInputStream().read(); Thread.sleep(300);
        check("strict TLS rejects self-signed", sr == -1 && !errs.isEmpty());
        tp2.stop();

        // ---- config tools
        String ovpn = "client\ndev tun\nproto udp\nremote vpn.example.com 1194\nremote-random\nhttp-proxy 1.2.3.4 8080\nauth-user-pass\n<ca>\n-----BEGIN CERTIFICATE-----\nremote fake 1\n-----END CERTIFICATE-----\n</ca>\n<connection>\nremote other.example.com 443 tcp\n</connection>\nverb 3\n";
        OvpnConfigTools.Remote rm = OvpnConfigTools.firstRemote(ovpn);
        check("firstRemote", rm != null && rm.host.equals("vpn.example.com") && rm.port == 1194 && !rm.tcp);
        check("looksLikeOvpn", OvpnConfigTools.looksLikeOvpn(ovpn) && !OvpnConfigTools.looksLikeOvpn("vless://abc@h:1") && !OvpnConfigTools.looksLikeOvpn("{\"a\":1}"));
        check("needsAuth", OvpnConfigTools.needsAuth(ovpn));
        String built = OvpnConfigTools.buildConfig(ovpn, 41234, "104.18.37.127", "bob", "p@ss");
        System.out.println("---- built config ----\n" + built + "----------------------");
        check("relay: remote rewritten", built.contains("remote 127.0.0.1 41234") && built.contains("proto tcp-client"));
        check("relay: old remote/proxy/connection dropped", !built.contains("vpn.example.com") && !built.contains("other.example.com") && !built.contains("http-proxy") && !built.contains("remote-random"));
        check("relay: cert block preserved intact", built.contains("remote fake 1") && built.contains("<ca>") && built.contains("</ca>"));
        check("relay: excluded route", built.contains("route 104.18.37.127 255.255.255.255 net_gateway"));
        check("creds inline", built.contains("<auth-user-pass>\nbob\np@ss\n</auth-user-pass>") && built.contains("\nauth-user-pass\n"));
        String direct = OvpnConfigTools.buildConfig(ovpn, -1, null, "", "");
        check("direct: untouched remotes", direct.contains("remote vpn.example.com 1194") && direct.contains("<connection>"));
        System.out.println(fails == 0 ? "ALL TESTS PASSED" : (fails + " TEST(S) FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
