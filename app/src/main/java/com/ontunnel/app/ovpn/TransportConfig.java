package com.ontunnel.app.ovpn;

/** Plain description of how the local relay must reach the real OpenVPN server. */
public final class TransportConfig {
    public static final int MODE_PAYLOAD = 1;    // custom HTTP payload, then raw OpenVPN bytes
    public static final int MODE_WEBSOCKET = 2;  // RFC 6455 handshake + binary frames
    /**
     * "HTTP Custom (legacy 5.1.x)" behaviour: payload is sent as-is, TLS never validates the certificate,
     * any 101 / 2xx reply (or no reply at all) switches to a raw pipe, no WebSocket framing ever.
     */
    public static final int MODE_LEGACY = 3;

    /** WebSocket framing policy (MODE_WEBSOCKET only, after a 101 reply). */
    public static final int FRAMING_AUTO = 0;    // framed only if the server proves it is RFC 6455 (Sec-WebSocket-Accept)
    public static final int FRAMING_ALWAYS = 1;  // always framed after 101
    public static final int FRAMING_NEVER = 2;   // always raw after 101

    public int mode = MODE_PAYLOAD;
    /** Host/port we actually open a TCP connection to (server, CDN edge, or HTTP proxy). */
    public String dialHost = "";
    public int dialPort = 443;
    /** The real OpenVPN server, used for [host] / [port] tags. */
    public String targetHost = "";
    public int targetPort = 1194;
    public boolean tls = false;
    public String sni = "";
    public boolean allowInsecure = false;
    /** HTTP payload template (see PayloadTemplate). May be blank in WebSocket mode. */
    public String payload = "";
    public String wsPath = "/";
    public String wsHost = "";
    /**
     * WebSocket mode only: after the 101 reply, wrap OpenVPN bytes in RFC 6455 binary frames (true).
     * Turn off for injector-style servers that switch to a plain TCP pipe after the 101 (false).
     */
    public boolean wsFraming = true;
    /** Only used while {@link #wsFraming} is true. */
    public int framingMode = FRAMING_AUTO;
    /**
     * How many times the relay re-dials + repeats the handshake for ONE local connection before it
     * gives up and closes it (OpenVPN then sees a reset). Keeps short server hiccups invisible to OpenVPN.
     */
    public int attempts = 3;
}
