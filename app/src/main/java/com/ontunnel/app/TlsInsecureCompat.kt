package com.ontunnel.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Recent Xray-core versions REMOVED `tlsSettings.allowInsecure` and fail with
 * "The feature allowInsecure has been removed and migrated ...".
 *
 * This helper keeps the "Allow insecure" switch working:
 *  1. `allowInsecure` is always stripped from the config (so the core never sees it).
 *  2. If it was `true`, we open a TLS connection to the server (before the VPN is up),
 *     read the certificate it presents and pin its SHA-256 in `pinnedPeerCertSha256`.
 *     Xray then accepts exactly that certificate even if the name does not match
 *     (custom SNI / bug host / self-signed), which is what allowInsecure used to do.
 */
object TlsInsecureCompat {

    fun prepare(config: String): String {
        val root = try { JSONObject(config) } catch (_: Exception) { return config }
        val outbounds = root.optJSONArray("outbounds") ?: return config
        var changed = false
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            val ss = ob.optJSONObject("streamSettings") ?: continue
            val tls = ss.optJSONObject("tlsSettings") ?: continue
            // Xray expects pinnedPeerCertSha256 to be a STRING, not a JSON array.
            // Normalize older/custom configs that may already contain an array.
            if (tls.has("pinnedPeerCertSha256") && !tls.isNull("pinnedPeerCertSha256")) {
                val pin = tls.opt("pinnedPeerCertSha256")
                if (pin is JSONArray) {
                    val firstPin = pin.optString(0).trim()
                    if (firstPin.isNotEmpty()) tls.put("pinnedPeerCertSha256", firstPin)
                    else tls.remove("pinnedPeerCertSha256")
                    changed = true
                }
            }

            val insecure = tls.optBoolean("allowInsecure", false)
            if (tls.has("allowInsecure")) {
                tls.remove("allowInsecure")
                changed = true
            }
            if (!insecure || tls.has("pinnedPeerCertSha256")) continue

            val (host, port) = endpoint(ob) ?: continue
            val sni = tls.optString("serverName").ifBlank { host }
            val hash = fetchLeafSha256(host, port, sni)
                ?: throw IllegalStateException("Allow insecure: could not reach $host:$port to read its certificate")
            tls.put("pinnedPeerCertSha256", hash)
        }
        return if (changed) root.toString() else config
    }

    private fun endpoint(ob: JSONObject): Pair<String, Int>? {
        val settings = ob.optJSONObject("settings") ?: return null
        val first = (settings.optJSONArray("vnext") ?: settings.optJSONArray("servers"))?.optJSONObject(0)
            ?: return null
        val address = first.optString("address")
        val port = first.optInt("port", 443)
        return if (address.isBlank()) null else address to port
    }

    private fun fetchLeafSha256(host: String, port: Int, sni: String): String? {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        repeat(2) {
            try {
                val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
                val plain = java.net.Socket()
                plain.connect(InetSocketAddress(host, port), 8000)
                plain.soTimeout = 8000
                val socket = ctx.socketFactory.createSocket(plain, sni, port, true) as SSLSocket
                try {
                    val params = socket.sslParameters
                    params.serverNames = listOf<SNIServerName>(SNIHostName(sni))
                    socket.sslParameters = params
                    socket.startHandshake()
                    val cert = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                    if (cert != null) {
                        return MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                            .joinToString("") { "%02x".format(it) }
                    }
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            }
        }
        return null
    }
}
