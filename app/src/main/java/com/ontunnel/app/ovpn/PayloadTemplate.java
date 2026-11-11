package com.ontunnel.app.ovpn;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands an HTTP-injector style payload template.
 * Tags: [crlf] [cr] [lf] [crlf*N] [cr*N] [lf*N] [host] [port] [host_port] [protocol] [ua] [ws_key]
 *       [netData] [rotate=a;b;c] [random=a;b;c] [split] [delay_split] [instant_split]
 * Literal "\r" and "\n" escape sequences are accepted too.
 * (The N-repeat, [netData], [rotate=..] and [instant_split] tags follow the HTTP Custom payload syntax.)
 */
public final class PayloadTemplate {
    public static final String DEFAULT_UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36";

    public static final class Part {
        public final byte[] data;
        /** Pause before sending this part (ms). */
        public final int delayBeforeMs;
        Part(byte[] d, int delay) { data = d; delayBeforeMs = delay; }
    }

    private static final Pattern SPLIT = Pattern.compile("\\[(split|delay_split|instant_split)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPEAT = Pattern.compile("\\[(crlf|cr|lf)\\*(\\d{1,3})\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern PICK = Pattern.compile("\\[(rotate|random)=([^\\]]*)\\]", Pattern.CASE_INSENSITIVE);
    private static final AtomicInteger ROTATE = new AtomicInteger();
    private static final java.security.SecureRandom RND = new java.security.SecureRandom();

    private PayloadTemplate() {}

    public static String replaceTags(String tpl, String host, int port, String wsKey) {
        String s = tpl == null ? "" : tpl;
        s = s.replace("\\r", "\r").replace("\\n", "\n");
        s = expandPicks(s);
        s = expandRepeats(s);
        s = ci(s, "[netData]", "CONNECT [host_port] [protocol]");
        s = ci(s, "[crlf]", "\r\n");
        s = ci(s, "[cr]", "\r");
        s = ci(s, "[lf]", "\n");
        s = ci(s, "[host_port]", host + ":" + port);
        s = ci(s, "[host]", host);
        s = ci(s, "[port]", String.valueOf(port));
        s = ci(s, "[protocol]", "HTTP/1.1");
        s = ci(s, "[ua]", DEFAULT_UA);
        s = ci(s, "[ws_key]", wsKey == null ? "" : wsKey);
        return s;
    }

    public static List<Part> expand(String tpl, String host, int port, String wsKey) {
        String s = replaceTags(tpl, host, port, wsKey);
        List<Part> out = new ArrayList<>();
        Matcher m = SPLIT.matcher(s);
        int last = 0;
        int pendingDelay = 0;
        while (m.find()) {
            if (m.start() > last) out.add(new Part(bytes(s.substring(last, m.start())), pendingDelay));
            String kind = m.group(1);
            pendingDelay = kind.equalsIgnoreCase("delay_split") ? 1500 : kind.equalsIgnoreCase("instant_split") ? 0 : 150;
            last = m.end();
        }
        if (last < s.length()) out.add(new Part(bytes(s.substring(last)), pendingDelay));
        return out;
    }

    /** [crlf*2] -> two CRLFs, [lf*3] -> three LFs ... (capped at 64). */
    private static String expandRepeats(String src) {
        Matcher m = REPEAT.matcher(src);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String unit = m.group(1).equalsIgnoreCase("crlf") ? "\r\n" : m.group(1).equalsIgnoreCase("cr") ? "\r" : "\n";
            int n = Math.min(64, Integer.parseInt(m.group(2)));
            StringBuilder rep = new StringBuilder();
            for (int i = 0; i < n; i++) rep.append(unit);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** [rotate=a;b;c] cycles per connection, [random=a;b;c] picks one at random. */
    private static String expandPicks(String src) {
        Matcher m = PICK.matcher(src);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String[] items = m.group(2).split(";");
            String pick = "";
            if (items.length > 0) {
                int idx = m.group(1).equalsIgnoreCase("rotate")
                        ? Math.floorMod(ROTATE.getAndIncrement(), items.length) : RND.nextInt(items.length);
                pick = items[idx].trim();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(pick));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }

    private static String ci(String src, String tag, String value) {
        return Pattern.compile(Pattern.quote(tag), Pattern.CASE_INSENSITIVE)
                .matcher(src).replaceAll(Matcher.quoteReplacement(value));
    }
}
