package com.ontunnel.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/** Editable representation of a single proxy server (same fields as V2rayNG's editor). */
data class ServerProfile(
    val protocol: String = "vless",      // vless | vmess | trojan
    val remarks: String = "",
    val address: String = "",
    val port: Int = 443,
    val id: String = "",                 // UUID, or password for trojan
    val alterId: Int = 0,
    val vmessSecurity: String = "auto",
    val flow: String = "",
    val encryption: String = "none",
    val network: String = "tcp",        // tcp | ws | grpc | httpupgrade | xhttp | h2
    val headerType: String = "none",     // tcp header: none | http
    val host: String = "",
    val path: String = "",               // path, or gRPC serviceName
    val grpcMode: String = "gun",        // gun | multi
    val xhttpMode: String = "auto",
    val security: String = "none",       // none | tls | reality
    val sni: String = "",
    val fingerprint: String = "",
    val alpn: String = "",
    val allowInsecure: Boolean = false,
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = ""
) {
    fun normalized() = copy(
        remarks = remarks.trim(), address = address.trim(), id = id.trim(),
        host = host.trim(), path = path.trim(), sni = sni.trim(), alpn = alpn.trim(),
        publicKey = publicKey.trim(), shortId = shortId.trim(), spiderX = spiderX.trim(),
        encryption = encryption.trim().ifBlank { "none" }
    )
}

object ServerOptions {
    val protocols = listOf("vless", "vmess", "trojan")
    val networks = listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp", "h2")
    val securities = listOf("none", "tls", "reality")
    val vmessCiphers = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")
    val flows = listOf("none", "xtls-rprx-vision", "xtls-rprx-vision-udp443")
    val fingerprints = listOf("none", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized")
    val xhttpModes = listOf("auto", "packet-up", "stream-up", "stream-one")
    val grpcModes = listOf("gun", "multi")
    val headerTypes = listOf("none", "http")
}

object V2RayConfigParser {
    data class Parsed(
        val name: String,
        val config: String,
        val profile: ServerProfile? = null,
        val uri: String = ""
    )

    fun parse(rawInput: String): Parsed {
        val raw = rawInput.trim()
        require(raw.isNotEmpty()) { "Enter a V2Ray link or JSON" }
        if (raw.startsWith("{")) {
            val json = JSONObject(raw)
            require(json.has("outbounds")) { "JSON does not contain outbounds" }
            val inbounds = json.optJSONArray("inbounds") ?: JSONArray().also { json.put("inbounds", it) }
            val hasTun = (0 until inbounds.length()).any { inbounds.optJSONObject(it)?.optString("protocol") == "tun" }
            if (!hasTun) inbounds.put(tunInbound())
            return Parsed(json.optString("remarks").ifBlank { "Custom JSON" }, json.toString())
        }
        val profile = parseUri(raw)
        return Parsed(displayName(profile), buildConfig(profile), profile, raw)
    }

    /** Builds a server entry from an edited profile (config + regenerated share link). */
    fun fromProfile(p: ServerProfile): Parsed {
        val n = p.normalized()
        return Parsed(displayName(n), buildConfig(n), n, toUri(n))
    }

    private fun displayName(p: ServerProfile) = p.remarks.ifBlank { p.address }

    // ───────────────────────── URI → profile ─────────────────────────

    fun parseUri(raw: String): ServerProfile {
        val s = raw.trim()
        return when {
            s.startsWith("vmess://", true) -> parseVmess(s)
            s.startsWith("vless://", true) -> parseStandard(s, "vless")
            s.startsWith("trojan://", true) -> parseStandard(s, "trojan")
            else -> error("Supported formats: VLESS, VMess, Trojan and JSON")
        }
    }

    private fun normalizeNetwork(n: String): String = when (n.lowercase().ifBlank { "tcp" }) {
        "raw" -> "tcp"
        "http", "h2" -> "h2"
        "splithttp" -> "xhttp"
        else -> n.lowercase()
    }

    private fun parseStandard(raw: String, proto: String): ServerProfile {
        var rest = raw.substringAfter("://")
        val fragment = if ('#' in rest) decode(rest.substringAfter('#')) else ""
        rest = rest.substringBefore('#')
        val query = if ('?' in rest) rest.substringAfter('?') else ""
        rest = rest.substringBefore('?')
        val at = rest.lastIndexOf('@')
        require(at > 0) { if (proto == "vless") "UUID missing" else "Trojan password missing" }
        val user = decode(rest.substring(0, at))
        val hostPort = rest.substring(at + 1).trimEnd('/')
        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            require(end > 0) { "Invalid server address" }
            host = hostPort.substring(1, end)
            port = hostPort.substring(end + 1).removePrefix(":").toIntOrNull() ?: 443
        } else {
            val idx = hostPort.lastIndexOf(':')
            if (idx > 0) {
                host = hostPort.substring(0, idx)
                port = hostPort.substring(idx + 1).toIntOrNull() ?: 443
            } else {
                host = hostPort
                port = 443
            }
        }
        require(host.isNotBlank()) { "Invalid server address" }

        val q = params(query)
        val net = normalizeNetwork(q["type"].orEmpty())
        val sec = q["security"].orEmpty().lowercase().ifBlank { if (proto == "trojan") "tls" else "none" }
        val mode = q["mode"].orEmpty()
        return ServerProfile(
            protocol = proto,
            remarks = fragment,
            address = host,
            port = port,
            id = user.substringBefore(':').takeIf { proto == "vless" } ?: user,
            flow = q["flow"].orEmpty(),
            encryption = q["encryption"].orEmpty().ifBlank { "none" },
            network = net,
            headerType = q["headerType"].orEmpty().ifBlank { "none" },
            host = q["host"].orEmpty(),
            path = if (net == "grpc") (q["serviceName"] ?: q["path"].orEmpty()) else q["path"].orEmpty(),
            grpcMode = if (mode == "multi") "multi" else "gun",
            xhttpMode = mode.ifBlank { "auto" },
            security = sec,
            sni = q["sni"] ?: q["serverName"].orEmpty(),
            fingerprint = q["fp"].orEmpty(),
            alpn = q["alpn"].orEmpty(),
            allowInsecure = q["allowInsecure"] == "1" || q["allowInsecure"] == "true" || q["insecure"] == "1",
            publicKey = q["pbk"].orEmpty(),
            shortId = q["sid"].orEmpty(),
            spiderX = q["spx"].orEmpty()
        )
    }

    private fun parseVmess(raw: String): ServerProfile {
        val body = raw.substringAfter("://").substringBefore('#').trim()
        val d = JSONObject(b64Decode(body))
        val net = normalizeNetwork(d.optString("net", "tcp"))
        val tls = d.optString("tls").equals("tls", true)
        return ServerProfile(
            protocol = "vmess",
            remarks = d.optString("ps"),
            address = d.getString("add"),
            port = d.optInt("port", 443),
            id = d.getString("id"),
            alterId = d.optInt("aid", 0),
            vmessSecurity = d.optString("scy").ifBlank { "auto" },
            network = net,
            headerType = d.optString("type").let { if (it == "http") "http" else "none" },
            host = d.optString("host"),
            path = d.optString("path"),
            grpcMode = if (d.optString("type") == "multi") "multi" else "gun",
            security = if (tls) "tls" else "none",
            sni = d.optString("sni"),
            fingerprint = d.optString("fp"),
            alpn = d.optString("alpn")
        )
    }

    // ───────────────────────── profile → xray JSON ─────────────────────────

    fun buildConfig(p: ServerProfile): String {
        require(p.address.isNotBlank()) { "Server address is required" }
        require(p.port in 1..65535) { "Port must be between 1 and 65535" }
        require(p.id.isNotBlank()) { if (p.protocol == "trojan") "Password is required" else "UUID is required" }
        if (p.security == "reality") require(p.publicKey.isNotBlank()) { "Reality public key is required" }

        val out = JSONObject().put("tag", "proxy").put("protocol", p.protocol)
        when (p.protocol) {
            "vmess" -> {
                val user = JSONObject().put("id", p.id).put("alterId", p.alterId)
                    .put("security", p.vmessSecurity.ifBlank { "auto" })
                out.put("settings", JSONObject().put("vnext", JSONArray().put(
                    JSONObject().put("address", p.address).put("port", p.port).put("users", JSONArray().put(user)))))
            }
            "vless" -> {
                val user = JSONObject().put("id", p.id).put("encryption", p.encryption.ifBlank { "none" })
                if (p.flow.isNotBlank()) user.put("flow", p.flow)
                out.put("settings", JSONObject().put("vnext", JSONArray().put(
                    JSONObject().put("address", p.address).put("port", p.port).put("users", JSONArray().put(user)))))
            }
            "trojan" -> out.put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", p.address).put("port", p.port).put("password", p.id))))
            else -> error("Unsupported protocol")
        }
        out.put("streamSettings", stream(p))
        return config(out)
    }

    private fun stream(p: ServerProfile): JSONObject {
        val net = p.network.ifBlank { "tcp" }
        val sec = p.security.ifBlank { "none" }
        val sni = p.sni.ifBlank { p.host.substringBefore(',') }.ifBlank { p.address }
        val hostHeader = p.host.ifBlank { sni }
        val s = JSONObject().put("network", net).put("security", sec)

        when (sec) {
            "tls" -> {
                val tls = JSONObject().put("serverName", sni)
                // Marker only: ONTunnelVpnService/TlsInsecureCompat converts it to pinnedPeerCertSha256
                // because the Xray core no longer accepts "allowInsecure".
                if (p.allowInsecure) tls.put("allowInsecure", true)
                if (p.alpn.isNotBlank()) tls.put("alpn", array(p.alpn))
                if (p.fingerprint.isNotBlank() && p.fingerprint != "none") tls.put("fingerprint", p.fingerprint)
                s.put("tlsSettings", tls)
            }
            "reality" -> s.put("realitySettings", JSONObject()
                .put("serverName", sni)
                .put("fingerprint", p.fingerprint.takeIf { it.isNotBlank() && it != "none" } ?: "chrome")
                .put("publicKey", p.publicKey)
                .put("shortId", p.shortId)
                .put("spiderX", p.spiderX.ifBlank { "/" }))
        }

        when (net) {
            "tcp" -> if (p.headerType == "http") {
                val headers = JSONObject()
                if (p.host.isNotBlank()) headers.put("Host", array(p.host))
                s.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http")
                    .put("request", JSONObject().put("version", "1.1").put("method", "GET")
                        .put("path", array(p.path.ifBlank { "/" })).put("headers", headers))))
            }
            "ws" -> s.put("wsSettings", JSONObject().put("path", p.path.ifBlank { "/" })
                .put("headers", JSONObject().put("Host", hostHeader)))
            "grpc" -> s.put("grpcSettings", JSONObject().put("serviceName", p.path)
                .put("multiMode", p.grpcMode == "multi"))
            "httpupgrade" -> s.put("httpupgradeSettings", JSONObject().put("path", p.path.ifBlank { "/" }).put("host", hostHeader))
            "xhttp" -> s.put("xhttpSettings", JSONObject().put("path", p.path.ifBlank { "/" })
                .put("host", hostHeader).put("mode", p.xhttpMode.ifBlank { "auto" }))
            "h2" -> s.put("httpSettings", JSONObject().put("path", p.path.ifBlank { "/" }).put("host", array(hostHeader)))
        }
        return s
    }

    private fun tunInbound(): JSONObject = JSONObject().put("tag", "tun").put("protocol", "tun")
        .put("settings", JSONObject().put("name", "ontun0").put("MTU", 1500).put("userLevel", 8))
        .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic")))

    private fun config(proxy: JSONObject): String =
        JSONObject().put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(tunInbound()))
            .put("outbounds", JSONArray().put(proxy)
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole")))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()))
            .put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
            .toString()

    // ───────────────────────── profile → share link ─────────────────────────

    fun toUri(p: ServerProfile): String {
        val name = enc(p.remarks.ifBlank { p.address })
        if (p.protocol == "vmess") {
            val j = JSONObject().put("v", "2").put("ps", p.remarks.ifBlank { p.address })
                .put("add", p.address).put("port", p.port.toString()).put("id", p.id)
                .put("aid", p.alterId.toString()).put("scy", p.vmessSecurity.ifBlank { "auto" })
                .put("net", p.network)
                .put("type", if (p.network == "grpc") p.grpcMode else p.headerType)
                .put("host", p.host).put("path", p.path)
                .put("tls", if (p.security == "tls") "tls" else "")
                .put("sni", p.sni).put("alpn", p.alpn)
                .put("fp", if (p.fingerprint == "none") "" else p.fingerprint)
            return "vmess://" + Base64.encodeToString(j.toString().toByteArray(), Base64.NO_WRAP)
        }
        val q = linkedMapOf<String, String>()
        if (p.protocol == "vless") {
            q["encryption"] = p.encryption.ifBlank { "none" }
            if (p.flow.isNotBlank()) q["flow"] = p.flow
        }
        q["security"] = p.security
        if (p.security == "tls" || p.security == "reality") {
            if (p.sni.isNotBlank()) q["sni"] = p.sni
            if (p.fingerprint.isNotBlank() && p.fingerprint != "none") q["fp"] = p.fingerprint
        }
        if (p.security == "tls") {
            if (p.alpn.isNotBlank()) q["alpn"] = p.alpn
            if (p.allowInsecure) q["allowInsecure"] = "1"
        }
        if (p.security == "reality") {
            q["pbk"] = p.publicKey
            if (p.shortId.isNotBlank()) q["sid"] = p.shortId
            if (p.spiderX.isNotBlank()) q["spx"] = p.spiderX
        }
        q["type"] = p.network
        when (p.network) {
            "tcp" -> if (p.headerType == "http") {
                q["headerType"] = "http"
                if (p.host.isNotBlank()) q["host"] = p.host
                if (p.path.isNotBlank()) q["path"] = p.path
            }
            "ws", "httpupgrade", "h2" -> {
                if (p.host.isNotBlank()) q["host"] = p.host
                if (p.path.isNotBlank()) q["path"] = p.path
            }
            "xhttp" -> {
                if (p.host.isNotBlank()) q["host"] = p.host
                if (p.path.isNotBlank()) q["path"] = p.path
                q["mode"] = p.xhttpMode
            }
            "grpc" -> {
                if (p.path.isNotBlank()) q["serviceName"] = p.path
                q["mode"] = p.grpcMode
            }
        }
        val query = q.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val hostPart = if (':' in p.address) "[${p.address}]" else p.address
        return "${p.protocol}://${enc(p.id)}@$hostPart:${p.port}?$query#$name"
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun b64Decode(s: String): String {
        val clean = s.trim().replace("\n", "").replace("\r", "")
        for (flag in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE)) {
            try { return String(Base64.decode(clean, flag), Charsets.UTF_8) } catch (_: Exception) {}
        }
        error("Invalid VMess link")
    }

    private fun params(raw: String?): Map<String, String> = raw.orEmpty().split("&").mapNotNull {
        if (it.isBlank()) return@mapNotNull null
        val p = it.split("=", limit = 2)
        decode(p[0]) to decode(p.getOrElse(1) { "" })
    }.toMap()

    private fun decode(s: String?): String = try {
        URLDecoder.decode(s.orEmpty().replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        s.orEmpty()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun array(s: String?): JSONArray = JSONArray().also { a ->
        s.orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach(a::put)
    }
}
