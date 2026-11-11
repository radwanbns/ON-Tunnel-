package com.ontunnel.app

import com.ontunnel.app.ovpn.OvpnConfigTools
import com.ontunnel.app.ovpn.TransportConfig
import org.json.JSONObject

object OvpnMode {
    const val DIRECT = "direct"
    const val PAYLOAD = "payload"
    const val WEBSOCKET = "websocket"
    /** Behaves like the old HTTP Custom (5.1.x) "payload + SSL/SNI" connection. */
    const val LEGACY = "httpcustom"

    val labels = listOf("Direct (standard)", "HTTP payload", "WebSocket", "HTTP Custom (legacy 5.1.26)")
    fun toLabel(mode: String) = when (mode) {
        PAYLOAD -> labels[1]
        WEBSOCKET -> labels[2]
        LEGACY -> labels[3]
        else -> labels[0]
    }
    fun fromLabel(label: String) = when (label) {
        labels[1] -> PAYLOAD
        labels[2] -> WEBSOCKET
        labels[3] -> LEGACY
        else -> DIRECT
    }

    /** WebSocket framing choices shown in the editor: label -> (wsFraming, wsFramingMode). */
    val framingLabels = listOf("Auto (recommended)", "Always framed (RFC 6455)", "Raw after 101 (no framing)")
    fun framingLabel(wsFraming: Boolean, mode: Int) = when {
        !wsFraming || mode == TransportConfig.FRAMING_NEVER -> framingLabels[2]
        mode == TransportConfig.FRAMING_ALWAYS -> framingLabels[1]
        else -> framingLabels[0]
    }

    /** WebSocket-style upgrade then raw OpenVPN (common injector template). */
    const val PAYLOAD_TEMPLATE =
        "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]"

    /** Decoy request first (its reply is read and ignored), then the upgrade request. Raw OpenVPN after the 101. */
    const val DECOY_TEMPLATE =
        "GET /cdn-cgi/trace HTTP/1.1[crlf]Host: [host][crlf][crlf]ACL / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: Websocket[crlf]Connection: Keep-Alive[crlf]User-Agent: [ua][crlf]Upgrade: websocket[crlf][crlf]"

    /** Classic HTTP CONNECT through a proxy – works with any target port. */
    const val CONNECT_TEMPLATE =
        "CONNECT [host_port] HTTP/1.1[crlf]Host: [host_port][crlf]Proxy-Connection: Keep-Alive[crlf]User-Agent: [ua][crlf][crlf]"
}

/**
 * An OpenVPN server entry. Stored as JSON inside [ServerEntry.config] (type = "openvpn").
 *
 * mode DIRECT    – the .ovpn is handed to OpenVPN for Android unchanged.
 * mode PAYLOAD   – local relay sends [payload] to the server, then carries raw OpenVPN (TCP) bytes.
 * mode WEBSOCKET – local relay performs an RFC 6455 handshake; OpenVPN travels in binary frames only when the
 *                  server proves it speaks RFC 6455 (Sec-WebSocket-Accept), otherwise as a raw pipe.
 * mode LEGACY    – HTTP Custom 5.1.x style: payload sent as-is, TLS never validated, any 101/2xx (or silence)
 *                  switches to a raw pipe.
 */
data class OvpnProfile(
    val name: String = "OpenVPN",
    val config: String = "",
    val username: String = "",
    val password: String = "",
    val mode: String = OvpnMode.DIRECT,
    /** Optional "host" or "host:port" to connect to instead of the .ovpn remote (CDN edge, front domain...). */
    val serverOverride: String = "",
    /** Optional HTTP proxy that receives the payload (payload mode). */
    val proxyHost: String = "",
    val proxyPort: Int = 8080,
    val payload: String = "",
    val wsPath: String = "/",
    val wsHost: String = "",
    val tls: Boolean = false,
    val sni: String = "",
    val allowInsecure: Boolean = false,
    /** WebSocket mode: wrap OpenVPN in RFC 6455 frames after the 101. Off = raw pipe (injector-style servers). */
    val wsFraming: Boolean = true,
    /** TransportConfig.FRAMING_AUTO / ALWAYS / NEVER (only used while [wsFraming] is true). */
    val wsFramingMode: Int = TransportConfig.FRAMING_AUTO
) {
    /** Payload / WebSocket always run over TCP, so they use the profile's first TCP remote when there is one. */
    val remote: OvpnConfigTools.Remote? get() = OvpnConfigTools.bestRemote(config, usesRelay)
    val usesRelay: Boolean get() = mode == OvpnMode.PAYLOAD || mode == OvpnMode.WEBSOCKET || mode == OvpnMode.LEGACY
    val needsAuth: Boolean get() = OvpnConfigTools.needsAuth(config)

    /** Returns an error message, or null when the profile can be used. */
    fun validate(): String? {
        if (config.isBlank()) return "The .ovpn configuration is empty"
        val r = remote ?: return "No 'remote' line found in the configuration"
        if (name.isBlank()) return "Name is required"
        if (usesRelay) {
            if (r.host.isBlank() && serverOverride.isBlank() && proxyHost.isBlank()) return "Server address is missing"
            if (proxyHost.isNotBlank() && proxyPort !in 1..65535) return "Invalid proxy port"
            // Empty payload is allowed: TransportProxy auto-builds CONNECT when a proxy is set,
            // or a WebSocket-style GET otherwise.
        }
        return null
    }

    fun toTransport(): TransportConfig {
        val r = remote
        val t = TransportConfig()
        t.mode = when (mode) {
            OvpnMode.WEBSOCKET -> TransportConfig.MODE_WEBSOCKET
            OvpnMode.LEGACY -> TransportConfig.MODE_LEGACY
            else -> TransportConfig.MODE_PAYLOAD
        }
        t.targetHost = r?.host ?: ""
        t.targetPort = r?.port ?: 1194
        if (proxyHost.isNotBlank()) {
            t.dialHost = proxyHost.trim()
            t.dialPort = proxyPort
        } else if (serverOverride.isNotBlank()) {
            val s = serverOverride.trim()
            val idx = s.lastIndexOf(':')
            if (idx > 0 && idx < s.length - 1 && s.substring(idx + 1).all { it.isDigit() } && s.count { it == ':' } == 1) {
                t.dialHost = s.substring(0, idx)
                t.dialPort = s.substring(idx + 1).toIntOrNull() ?: t.targetPort
            } else {
                t.dialHost = s
                t.dialPort = t.targetPort
            }
        } else {
            t.dialHost = t.targetHost
            t.dialPort = t.targetPort
        }
        t.tls = tls
        t.sni = sni.trim()
        t.allowInsecure = allowInsecure
        t.payload = payload
        t.wsPath = wsPath
        t.wsHost = wsHost.trim()
        t.wsFraming = wsFraming
        t.framingMode = wsFramingMode
        return t
    }

    /** Config text handed to OpenVPN for Android. localPort <= 0 means "no relay". */
    fun buildConfig(localPort: Int, excludeIp: String?): String =
        OvpnConfigTools.buildConfig(config, localPort, excludeIp, username, password)

    /** Short label shown on the server card. */
    fun badge(): String = when (mode) {
        OvpnMode.PAYLOAD -> "OpenVPN · Payload" + if (tls) " · TLS" else ""
        OvpnMode.WEBSOCKET -> "OpenVPN · WebSocket" + if (tls) " · TLS" else ""
        OvpnMode.LEGACY -> "OpenVPN · HTTP Custom" + if (tls) " · TLS" else ""
        else -> "OpenVPN" + (remote?.let { if (it.tcp) " · TCP" else " · UDP" } ?: "")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("config", config)
        .put("username", username).put("password", password)
        .put("mode", mode).put("serverOverride", serverOverride)
        .put("proxyHost", proxyHost).put("proxyPort", proxyPort)
        .put("payload", payload).put("wsPath", wsPath).put("wsHost", wsHost)
        .put("tls", tls).put("sni", sni).put("allowInsecure", allowInsecure)
        .put("wsFraming", wsFraming).put("wsFramingMode", wsFramingMode)

    companion object {
        fun fromJson(text: String): OvpnProfile? = try {
            val o = JSONObject(text)
            OvpnProfile(
                name = o.optString("name", "OpenVPN"),
                config = o.optString("config"),
                username = o.optString("username"),
                password = o.optString("password"),
                mode = o.optString("mode", OvpnMode.DIRECT),
                serverOverride = o.optString("serverOverride"),
                proxyHost = o.optString("proxyHost"),
                proxyPort = o.optInt("proxyPort", 8080),
                payload = o.optString("payload"),
                wsPath = o.optString("wsPath", "/"),
                wsHost = o.optString("wsHost"),
                tls = o.optBoolean("tls", false),
                sni = o.optString("sni"),
                allowInsecure = o.optBoolean("allowInsecure", false),
                wsFraming = o.optBoolean("wsFraming", true),
                wsFramingMode = o.optInt("wsFramingMode", TransportConfig.FRAMING_AUTO)
            )
        } catch (_: Exception) { null }

        /** Builds a fresh profile from raw .ovpn text (file or clipboard). */
        fun fromOvpnText(text: String, suggestedName: String? = null): OvpnProfile {
            val r = OvpnConfigTools.firstRemote(text)
                ?: throw IllegalArgumentException("No 'remote' line found – this does not look like an .ovpn file")
            val name = suggestedName?.takeIf { it.isNotBlank() } ?: "OpenVPN ${r.host}"
            return OvpnProfile(name = name, config = text.trim() + "\n")
        }
    }
}
