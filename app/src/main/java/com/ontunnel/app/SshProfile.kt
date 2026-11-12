package com.ontunnel.app

import com.ontunnel.app.ovpn.TransportConfig
import org.json.JSONArray
import org.json.JSONObject

/** The connection types offered by the SSH editor (same names as the other tunnel apps). */
object SshMode {
    const val DIRECT = "SSH-Direct"
    const val PROXY = "SSH-Proxy"
    const val PAYLOAD = "SSH-Payload"
    const val PROXY_PAYLOAD = "SSH-Proxy-Payload"
    const val TLS = "SSH-TLS"
    const val TLS_PROXY = "SSH-TLS-Proxy"
    const val TLS_PAYLOAD = "SSH-TLS-Payload"
    const val TLS_PROXY_PAYLOAD = "SSH-TLS-Proxy-Payload"
    const val DNSTT = "SSH-DNSTT"

    val all = listOf(DIRECT, PROXY, PAYLOAD, PROXY_PAYLOAD, TLS, TLS_PROXY, TLS_PAYLOAD, TLS_PROXY_PAYLOAD, DNSTT)

    const val AUTH_PASSWORD = "Password"
    const val AUTH_KEY = "Private key"
    val authTypes = listOf(AUTH_PASSWORD, AUTH_KEY)

    /** Used when the payload field is empty and the mode needs one. */
    const val WS_PAYLOAD =
        "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]"
}

/**
 * An SSH server entry (stored as JSON inside [ServerEntry.config], type = "ssh").
 *
 * How it runs: the loopback relay ([com.ontunnel.app.ovpn.TransportProxy]) dials the server (optionally through an
 * HTTP proxy, over TLS, with a custom payload), JSch speaks SSH through it, and a local SOCKS5 server turns every
 * connection into an SSH "direct-tcpip" channel. Xray's TUN inbound then sends the whole device through that SOCKS port.
 */
data class SshProfile(
    val name: String = "SSH",
    val mode: String = SshMode.DIRECT,
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authType: String = SshMode.AUTH_PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val proxyHost: String = "",
    val proxyPort: Int = 8080,
    val payload: String = "",
    val sni: String = "",
    val allowInsecure: Boolean = true,
    /** SSH-DNSTT fields (kept so the entry is not lost; the DNSTT core is not bundled yet). */
    val dnsServer: String = "",
    val dnsDomain: String = "",
    val dnsKey: String = ""
) {
    val usesTls: Boolean get() = mode.contains("TLS")
    val usesProxy: Boolean get() = mode.contains("Proxy")
    val usesPayload: Boolean get() = mode.contains("Payload")
    val isDnstt: Boolean get() = mode == SshMode.DNSTT
    val usesKey: Boolean get() = authType == SshMode.AUTH_KEY

    /** Returns an error message, or null when the profile can be used. */
    fun validate(): String? {
        if (name.isBlank()) return "Name is required"
        if (isDnstt) {
            if (dnsServer.isBlank()) return "DNS resolver is missing"
            val resolver = dnsServer.trim()
            val isDoh = resolver.startsWith("https://", true)
            val isDot = resolver.startsWith("tls://", true) || resolver.startsWith("dot://", true)
            if (resolver.startsWith("http://", true)) return "DoH requires HTTPS"
            if (!isDoh && !isDot && (resolver.contains("://") || resolver.contains("/") || resolver.contains("?")))
                return "Use UDP host[:port], DoH https://.../dns-query, or DoT tls://host[:853]"
            if (isDoh && !resolver.substringAfter("://").contains("/"))
                return "DoH URL must include /dns-query"
            if (isDot && resolver.substringAfter("://").isBlank()) return "DoT hostname is missing"
            if (dnsDomain.isBlank()) return "DNSTT tunnel domain is missing"
            if (dnsKey.isBlank()) return "DNSTT public key is missing"
            if (username.isBlank()) return "SSH username is missing"
            if (usesKey && privateKey.isBlank()) return "Private key is missing"
            if (!usesKey && password.isEmpty()) return "SSH password is missing"
            return null
        }
        if (host.isBlank()) return "SSH server address is missing"
        if (port !in 1..65535) return "Invalid SSH port"
        if (username.isBlank()) return "SSH username is missing"
        if (usesKey) {
            if (privateKey.isBlank()) return "Private key is missing"
        } else if (password.isEmpty()) {
            return "SSH password is missing"
        }
        if (usesProxy) {
            if (proxyHost.isBlank()) return "Proxy address is missing"
            if (proxyPort !in 1..65535) return "Invalid proxy port"
        }
        return null
    }

    /** Relay description, or null for SSH-Direct (JSch then connects to the server itself). */
    fun toTransport(): TransportConfig? {
        if (mode == SshMode.DIRECT) return null
        val t = TransportConfig()
        t.targetHost = host.trim()
        t.targetPort = port
        if (usesProxy) {
            t.dialHost = proxyHost.trim()
            t.dialPort = proxyPort
        } else {
            t.dialHost = host.trim()
            t.dialPort = port
        }
        t.tls = usesTls
        t.sni = sni.trim()
        t.allowInsecure = allowInsecure
        if (usesProxy || usesPayload) {
            t.mode = TransportConfig.MODE_PAYLOAD
            // Empty payload + proxy => the relay builds a CONNECT request on its own.
            t.payload = if (usesPayload) payload else ""
            t.sshBanner = true
        } else {
            t.mode = TransportConfig.MODE_RAW
        }
        t.deferPreflight = false
        return t
    }

    /** Xray config: TUN inbound -> SOCKS outbound on 127.0.0.1:[socksPort] (the local SSH forwarder). */
    fun buildXrayConfig(socksPort: Int): String {
        val tun = JSONObject().put("tag", "tun").put("protocol", "tun")
            .put("settings", JSONObject().put("name", "ontun0").put("MTU", 1500).put("userLevel", 8))
            .put("sniffing", JSONObject().put("enabled", true)
                .put("destOverride", JSONArray().put("http").put("tls")))
        val proxy = JSONObject().put("tag", "proxy").put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", "127.0.0.1").put("port", socksPort))))
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", JSONArray().put(proxy)
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
                .put(JSONObject().put("tag", "dns-out").put("protocol", "dns")))
            // An SSH tunnel carries TCP only: DNS (UDP/53) is answered by Xray's own resolver over TCP through the
            // tunnel, every other UDP flow (QUIC...) is dropped so applications fall back to TCP.
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()
                .put(JSONObject().put("type", "field").put("network", "udp").put("port", "53").put("outboundTag", "dns-out"))
                .put(JSONObject().put("type", "field").put("network", "udp").put("outboundTag", "block"))))
            .put("dns", JSONObject().put("servers", JSONArray().put("tcp://1.1.1.1").put("tcp://8.8.8.8")))
            .toString()
    }

    /** Short label shown on the server card. */
    fun badge(): String = mode + if (usesKey) " · Key" else ""

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("mode", mode).put("host", host).put("port", port)
        .put("username", username).put("authType", authType)
        .put("password", password).put("privateKey", privateKey).put("passphrase", passphrase)
        .put("proxyHost", proxyHost).put("proxyPort", proxyPort)
        .put("payload", payload).put("sni", sni).put("allowInsecure", allowInsecure)
        .put("dnsServer", dnsServer).put("dnsDomain", dnsDomain).put("dnsKey", dnsKey)

    companion object {
        fun fromJson(text: String): SshProfile? = try {
            val o = JSONObject(text)
            SshProfile(
                name = o.optString("name", "SSH"),
                mode = o.optString("mode", SshMode.DIRECT).takeIf { it in SshMode.all } ?: SshMode.DIRECT,
                host = o.optString("host"),
                port = o.optInt("port", 22),
                username = o.optString("username"),
                authType = o.optString("authType", SshMode.AUTH_PASSWORD),
                password = o.optString("password"),
                privateKey = o.optString("privateKey"),
                passphrase = o.optString("passphrase"),
                proxyHost = o.optString("proxyHost"),
                proxyPort = o.optInt("proxyPort", 8080),
                payload = o.optString("payload"),
                sni = o.optString("sni"),
                allowInsecure = o.optBoolean("allowInsecure", true),
                dnsServer = o.optString("dnsServer"),
                dnsDomain = o.optString("dnsDomain"),
                dnsKey = o.optString("dnsKey")
            )
        } catch (_: Exception) { null }
    }
}
