package com.ontunnel.app.ovpn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsing and rewriting of .ovpn text. Pure Java so it can be unit-tested off-device. */
public final class OvpnConfigTools {
    public static final class Remote {
        public final String host;
        public final int port;
        public final boolean tcp;
        Remote(String h, int p, boolean t) { host = h; port = p; tcp = t; }
    }

    private static final Pattern BLOCK_OPEN = Pattern.compile("^<([A-Za-z0-9_-]+)>$");
    private static final Set<String> DROP_WHEN_RELAYED = new HashSet<>(Arrays.asList(
            "remote", "remote-random", "remote-random-hostname", "proto", "proto-force",
            "http-proxy", "http-proxy-option", "http-proxy-retry", "http-proxy-timeout",
            "socks-proxy", "socks-proxy-retry",
            // UDP-only / conflicting with local TCP relay:
            "explicit-exit-notify", "fragment", "mssfix"));

    private OvpnConfigTools() {}

    public static boolean looksLikeOvpn(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.startsWith("{") || t.contains("://")) return false;
        boolean hasRemote = Pattern.compile("(?mi)^[ \\t]*remote[ \\t]+\\S+").matcher(t).find();
        boolean hasHint = Pattern.compile("(?m)^\\s*(client|dev\\s+tun|dev\\s+tap|<ca>|<connection>|auth-user-pass|remote-cert-tls)").matcher(t).find();
        return hasRemote && hasHint;
    }

    private static final Pattern REMOTE_LINE =
            Pattern.compile("^remote[ \\t]+(\\S+)(?:[ \\t]+(\\d+))?(?:[ \\t]+(\\S+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROTO_LINE =
            Pattern.compile("^proto[ \\t]+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROTO_WORD =
            Pattern.compile("^(udp|tcp)[46]?(-client|-server)?$", Pattern.CASE_INSENSITIVE);

    /**
     * Every "remote" of the profile in file order, with the protocol that applies to it:
     * the proto given on the remote line, else the proto of its enclosing &lt;connection&gt; block,
     * else the global "proto" (default udp). Lines inside certificate/key blocks are ignored.
     */
    public static List<Remote> allRemotes(String cfg) {
        List<Remote> out = new ArrayList<>();
        if (cfg == null) return out;
        String[] lines = cfg.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        String globalProto = null;
        // pass 1: global proto (outside any block)
        String open = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (open != null) { if (line.equalsIgnoreCase("</" + open + ">")) open = null; continue; }
            Matcher bm = BLOCK_OPEN.matcher(line);
            if (bm.matches()) { open = bm.group(1); continue; }
            Matcher pm = PROTO_LINE.matcher(line);
            if (globalProto == null && pm.find()) globalProto = pm.group(1).toLowerCase(Locale.ROOT);
        }
        if (globalProto == null) globalProto = "udp";

        // pass 2: remotes, tracking <connection> blocks
        open = null;
        List<String[]> pending = new ArrayList<>();   // host, port, explicitProto
        List<Integer> blockOf = new ArrayList<>();
        List<String> blockProto = new ArrayList<>();
        int block = -1;
        for (String raw : lines) {
            String line = raw.trim();
            if (open != null) {
                if (line.equalsIgnoreCase("</" + open + ">")) { open = null; block = -1; continue; }
                if (block >= 0) {
                    Matcher rm = REMOTE_LINE.matcher(line);
                    Matcher pm = PROTO_LINE.matcher(line);
                    if (rm.find()) { pending.add(new String[]{rm.group(1), rm.group(2), rm.group(3)}); blockOf.add(block); }
                    else if (pm.find()) blockProto.set(block, pm.group(1).toLowerCase(Locale.ROOT));
                }
                continue;
            }
            Matcher bm = BLOCK_OPEN.matcher(line);
            if (bm.matches()) {
                open = bm.group(1);
                if (open.equalsIgnoreCase("connection")) { block = blockProto.size(); blockProto.add(null); }
                continue;
            }
            Matcher rm = REMOTE_LINE.matcher(line);
            if (rm.find()) { pending.add(new String[]{rm.group(1), rm.group(2), rm.group(3)}); blockOf.add(-1); }
        }
        for (int i = 0; i < pending.size(); i++) {
            String[] r = pending.get(i);
            int port = 1194;
            if (r[1] != null) {
                try { port = Integer.parseInt(r[1]); } catch (NumberFormatException ignored) { }
            }
            String proto = null;
            if (r[2] != null && PROTO_WORD.matcher(r[2]).matches()) proto = r[2].toLowerCase(Locale.ROOT);
            int bi = blockOf.get(i);
            if (proto == null && bi >= 0 && blockProto.get(bi) != null) proto = blockProto.get(bi);
            if (proto == null) proto = globalProto;
            out.add(new Remote(r[0], port, proto.startsWith("tcp")));
        }
        return out;
    }

    /** The first remote of the profile, or null. */
    public static Remote firstRemote(String cfg) {
        List<Remote> all = allRemotes(cfg);
        return all.isEmpty() ? null : all.get(0);
    }

    /** With preferTcp, the first TCP remote if the profile has one (used by the payload/WebSocket relay). */
    public static Remote bestRemote(String cfg, boolean preferTcp) {
        List<Remote> all = allRemotes(cfg);
        if (all.isEmpty()) return null;
        if (preferTcp) {
            for (Remote r : all) if (r.tcp) return r;
        }
        return all.get(0);
    }

    /** True when the profile has an auth-user-pass directive. */
    public static boolean needsAuth(String cfg) {
        return cfg != null && Pattern.compile("(?m)^\\s*auth-user-pass\\b").matcher(cfg).find();
    }

    /**
     * @param localPort  > 0 to redirect the profile to 127.0.0.1:localPort over TCP (relay mode)
     * @param excludeIp  IPv4 literal that must bypass the tunnel (the relay's upstream), or null
     * @param user/pass  optional credentials, embedded as an inline auth-user-pass block
     */
    public static String buildConfig(String cfg, int localPort, String excludeIp, String user, String pass) {
        List<String> out = new ArrayList<>();
        String[] lines = cfg.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String openBlock = null;
        boolean dropBlock = false;
        boolean hasCreds = user != null && !user.isEmpty();
        boolean sawBareAuth = false;

        for (String raw : lines) {
            String line = raw.trim();
            if (openBlock != null) {
                if (line.equalsIgnoreCase("</" + openBlock + ">")) {
                    if (!dropBlock) out.add(raw);
                    openBlock = null;
                    dropBlock = false;
                } else if (!dropBlock) {
                    out.add(raw);
                }
                continue;
            }
            Matcher bm = BLOCK_OPEN.matcher(line);
            if (bm.matches()) {
                openBlock = bm.group(1);
                String name = openBlock.toLowerCase(Locale.ROOT);
                dropBlock = (localPort > 0 && name.equals("connection")) || (hasCreds && name.equals("auth-user-pass"));
                if (!dropBlock) out.add(raw);
                continue;
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) { out.add(raw); continue; }
            String key = line.split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (localPort > 0 && DROP_WHEN_RELAYED.contains(key)) continue;
            if (key.equals("auth-user-pass")) {
                if (hasCreds) {
                    if (!sawBareAuth) { out.add("auth-user-pass"); sawBareAuth = true; }
                    continue;
                }
            }
            out.add(raw);
        }
        if (openBlock != null) throw new IllegalArgumentException("Unclosed <" + openBlock + "> block in profile");

        StringBuilder sb = new StringBuilder();
        for (String l : out) sb.append(l).append('\n');
        if (localPort > 0) {
            sb.append("proto tcp-client\n");
            sb.append("remote 127.0.0.1 ").append(localPort).append('\n');
            sb.append("resolv-retry infinite\n");
            sb.append("connect-retry 2\n");
            sb.append("connect-retry-max 6\n");
            sb.append("connect-timeout 30\n");
            sb.append("nobind\n");
            sb.append("persist-key\n");
            sb.append("persist-tun\n");
            // Bypass tunnel for every upstream edge IP (proxy / CDN / real remote).
            // Same idea as HTTP Custom: route <ip> 255.255.255.255 net_gateway
            if (excludeIp != null && !excludeIp.isEmpty()) {
                java.util.LinkedHashSet<String> ips = new java.util.LinkedHashSet<>();
                for (String part : excludeIp.split("[,;\\s]+")) {
                    String ip = part.trim();
                    if (ip.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) ips.add(ip);
                }
                for (String ip : ips) {
                    sb.append("route ").append(ip).append(" 255.255.255.255 net_gateway\n");
                }
            }
        }
        if (hasCreds) {
            if (!sawBareAuth) sb.append("auth-user-pass\n");
            sb.append("<auth-user-pass>\n").append(clean(user)).append('\n')
              .append(clean(pass == null ? "" : pass)).append('\n')
              .append("</auth-user-pass>\n");
        }
        return sb.toString();
    }

    private static String clean(String s) { return s.replace("\r", "").replace("\n", ""); }
}
