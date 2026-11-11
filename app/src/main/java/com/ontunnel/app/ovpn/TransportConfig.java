package com.ontunnel.app.ovpn;

/** Plain description of how the local relay must reach the real OpenVPN server. */
public final class TransportConfig {
    public static final int MODE_PAYLOAD = 1;    // custom HTTP payload, then raw OpenVPN bytes
    public static final int MODE_WEBSOCKET = 2;  // RFC 6455 handshake + binary frames

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
}
