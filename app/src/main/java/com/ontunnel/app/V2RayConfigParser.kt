package com.ontunnel.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

object V2RayConfigParser {
    data class Parsed(val name: String, val config: String)

    fun parse(rawInput: String): Parsed {
        val raw = rawInput.trim()
        require(raw.isNotEmpty()) { "أدخل رابط V2Ray أو JSON" }
        if (raw.startsWith("{")) {
            val json = JSONObject(raw)
            require(json.has("outbounds")) { "ملف JSON لا يحتوي outbounds" }
            val inbounds = json.optJSONArray("inbounds") ?: JSONArray().also { json.put("inbounds", it) }
            val hasTun = (0 until inbounds.length()).any { inbounds.optJSONObject(it)?.optString("protocol") == "tun" }
            if (!hasTun) {
                val tun = JSONObject().put("tag", "tun").put("protocol", "tun")
                    .put("settings", JSONObject().put("name", "ontun0").put("MTU", 1500).put("userLevel", 8))
                    .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic")))
                inbounds.put(tun)
            }
            return Parsed(json.optString("remarks").ifBlank { "Custom JSON" }, json.toString())
        }
        if (raw.startsWith("vmess://", true)) return vmess(raw)
        val uri = URI(raw)
        return when (uri.scheme?.lowercase()) {
            "vless" -> vless(uri)
            "trojan" -> trojan(uri)
            else -> error("الصيغ المدعومة: VLESS وVMess وTrojan وJSON")
        }
    }

    private fun vmess(raw: String): Parsed {
        val encoded = raw.substringAfter("://").substringBefore('#')
        val d = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
        val host = d.getString("add")
        val port = d.optInt("port", 443)
        val net = d.optString("net", "tcp").lowercase()
        val tls = d.optString("tls").equals("tls", true)
        val stream = JSONObject().put("network", net).put("security", if (tls) "tls" else "none")
        if (tls) stream.put("tlsSettings", JSONObject().put("serverName", d.optString("sni").ifBlank { d.optString("host") }).put("allowInsecure", false))
        if (net == "ws") stream.put("wsSettings", JSONObject().put("path", d.optString("path", "/")).put("headers", JSONObject().put("Host", d.optString("host", host))))
        if (net == "grpc") stream.put("grpcSettings", JSONObject().put("serviceName", d.optString("path")))
        val user = JSONObject().put("id", d.getString("id")).put("alterId", d.optInt("aid", 0)).put("security", d.optString("scy", "auto"))
        val out = JSONObject().put("tag", "proxy").put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user)))))
            .put("streamSettings", stream)
        return Parsed(d.optString("ps").ifBlank { host }, config(out))
    }

    private fun vless(u: URI): Parsed {
        val host = u.host ?: error("عنوان الخادم غير صحيح")
        val q = params(u.rawQuery)
        val net = q["type"].orEmpty().ifBlank { "tcp" }.lowercase()
        val sec = q["security"].orEmpty().ifBlank { "none" }.lowercase()
        val stream = JSONObject().put("network", net).put("security", sec)
        val sni = q["sni"].orEmpty().ifBlank { q["serverName"].orEmpty() }.ifBlank { host }
        if (sec == "tls") stream.put("tlsSettings", JSONObject().put("serverName", sni).put("allowInsecure", false).put("alpn", array(q["alpn"])))
        if (sec == "reality") stream.put("realitySettings", JSONObject().put("serverName", sni).put("fingerprint", q["fp"] ?: "chrome").put("publicKey", q["pbk"] ?: "").put("shortId", q["sid"] ?: "").put("spiderX", q["spx"] ?: "/"))
        if (net == "ws") stream.put("wsSettings", JSONObject().put("path", q["path"] ?: "/").put("headers", JSONObject().put("Host", q["host"] ?: host)))
        if (net == "grpc") stream.put("grpcSettings", JSONObject().put("serviceName", q["serviceName"] ?: q["path"] ?: "").put("multiMode", q["mode"] == "multi"))
        if (net == "httpupgrade") stream.put("httpupgradeSettings", JSONObject().put("path", q["path"] ?: "/").put("host", q["host"] ?: host))
        val user = JSONObject().put("id", u.userInfo?.substringBefore(':') ?: error("UUID غير موجود")).put("encryption", q["encryption"] ?: "none")
        q["flow"]?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }
        val out = JSONObject().put("tag", "proxy").put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", host).put("port", if (u.port > 0) u.port else 443).put("users", JSONArray().put(user)))))
            .put("streamSettings", stream)
        return Parsed(decode(u.rawFragment).ifBlank { host }, config(out))
    }

    private fun trojan(u: URI): Parsed {
        val host = u.host ?: error("عنوان الخادم غير صحيح")
        val q = params(u.rawQuery)
        val net = q["type"].orEmpty().ifBlank { "tcp" }.lowercase()
        val stream = JSONObject().put("network", net).put("security", "tls")
            .put("tlsSettings", JSONObject().put("serverName", q["sni"] ?: q["host"] ?: host).put("allowInsecure", false))
        if (net == "ws") stream.put("wsSettings", JSONObject().put("path", q["path"] ?: "/").put("headers", JSONObject().put("Host", q["host"] ?: host)))
        if (net == "grpc") stream.put("grpcSettings", JSONObject().put("serviceName", q["serviceName"] ?: q["path"] ?: ""))
        val password = u.userInfo ?: error("كلمة مرور Trojan غير موجودة")
        val out = JSONObject().put("tag", "proxy").put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", host).put("port", if (u.port > 0) u.port else 443).put("password", password))))
            .put("streamSettings", stream)
        return Parsed(decode(u.rawFragment).ifBlank { host }, config(out))
    }

    private fun config(proxy: JSONObject): String {
        val tun = JSONObject().put("tag", "tun").put("protocol", "tun")
            .put("settings", JSONObject().put("name", "ontun0").put("MTU", 1500).put("userLevel", 8))
            .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic")))
        return JSONObject().put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", JSONArray().put(proxy).put(JSONObject().put("tag", "direct").put("protocol", "freedom")).put(JSONObject().put("tag", "block").put("protocol", "blackhole")))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()))
            .put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8"))).toString()
    }

    private fun params(raw: String?): Map<String, String> = raw.orEmpty().split("&").mapNotNull {
        val p = it.split("=", limit = 2)
        if (p.isEmpty()) null else decode(p[0]) to decode(p.getOrElse(1) { "" })
    }.toMap()
    private fun decode(s: String?): String = try { URLDecoder.decode(s.orEmpty(), "UTF-8") } catch (_: Exception) { s.orEmpty() }
    private fun array(s: String?): JSONArray = JSONArray().also { a -> s.orEmpty().split(",").filter { it.isNotBlank() }.forEach(a::put) }
}
