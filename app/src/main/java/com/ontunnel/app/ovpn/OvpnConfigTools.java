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
        boolean hasRemote = Pattern.compile("(?m)^\\s*remote\\s+\\S+").matcher(t).find();
        boolean hasHint = Pattern.compile("(?m)^\\s*(client|dev\\s+tun|dev\\s+tap|<ca>|<connection>|auth-user-pass|remote-cert-tls)").matcher(t).find();
        return hasRemote && hasHint;
    }

    public static Remote firstRemote(String cfg) {
        if (cfg == null) return null;
        String globalProto = "udp";
        Matcher pm = Pattern.compile("(?m)^\\s*proto\\s+(\\S+)").matcher(cfg);
        if (pm.find()) globalProto = pm.group(1).toLowerCase(Locale.ROOT);
        Matcher m = Pattern.compile("(?m)^\\s*remote\\s+(\\S+)(?:\\s+(\\d+))?(?:\\s+(\\S+))?").matcher(cfg);
        if (!m.find()) return null;
        int port = 1194;
        if (m.group(2) != null) {
            try { port = Integer.parseInt(m.group(2)); } catch (NumberFormatException ignored) { }
        }
        String proto = m.group(3) != null ? m.group(3).toLowerCase(Locale.ROOT) : globalProto;
        return new Remote(m.group(1), port, proto.startsWith("tcp"));
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
