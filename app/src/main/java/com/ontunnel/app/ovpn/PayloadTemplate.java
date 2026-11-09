package com.ontunnel.app.ovpn;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands an HTTP-injector style payload template.
 * Tags: [crlf] [cr] [lf] [host] [port] [host_port] [protocol] [ua] [ws_key] [split] [delay_split]
 * Literal "\r" and "\n" escape sequences are accepted too.
 */
public final class PayloadTemplate {
    public static final String DEFAULT_UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36";

    public static final class Part {
        public final byte[] data;
        /** Pause before sending this part (ms). */
        public final int delayBeforeMs;
        Part(byte[] d, int delay) { data = d; delayBeforeMs = delay; }
    }

    private static final Pattern SPLIT = Pattern.compile("\\[(split|delay_split)\\]", Pattern.CASE_INSENSITIVE);

    private PayloadTemplate() {}

    public static String replaceTags(String tpl, String host, int port, String wsKey) {
        String s = tpl == null ? "" : tpl;
        s = s.replace("\\r", "\r").replace("\\n", "\n");
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
            pendingDelay = m.group(1).equalsIgnoreCase("delay_split") ? 1500 : 150;
            last = m.end();
        }
        if (last < s.length()) out.add(new Part(bytes(s.substring(last)), pendingDelay));
        return out;
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }

    private static String ci(String src, String tag, String value) {
        return Pattern.compile(Pattern.quote(tag), Pattern.CASE_INSENSITIVE)
                .matcher(src).replaceAll(Matcher.quoteReplacement(value));
    }
}
