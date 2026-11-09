package com.ontunnel.app

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

/**
 * Lightweight reachability check for V2Ray/Xray endpoints.
 *
 * This measures the TCP handshake to the configured server address/port.
 * It does not authenticate a V2Ray user or prove that the complete proxy
 * transport (TLS/Reality/WebSocket/gRPC) is usable.
 */
internal object V2RayPingTester {
    private data class Endpoint(val host: String, val port: Int)

    fun isSupported(config: String): Boolean = runCatching { endpoint(config) }.isSuccess

    fun test(config: String): String {
        val target = runCatching { endpoint(config) }.getOrElse { return "INVALID" }
        return try {
            var elapsed = 0L
            Socket().use { socket ->
                elapsed = measureTimeMillis {
                    socket.connect(InetSocketAddress(target.host, target.port), TIMEOUT_MS)
                }
            }
            "${elapsed.coerceAtLeast(1)} ms"
        } catch (_: java.net.SocketTimeoutException) {
            "TIMEOUT"
        } catch (_: java.net.UnknownHostException) {
            "DNS ERROR"
        } catch (_: java.net.ConnectException) {
            "OFFLINE"
        } catch (_: Exception) {
            "FAILED"
        }
    }

    private fun endpoint(config: String): Endpoint {
        val root = JSONObject(config)
        val outbounds = root.optJSONArray("outbounds")
            ?: error("Missing outbounds")
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            val protocol = outbound.optString("protocol").lowercase()
            if (protocol !in setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http")) continue
            val settings = outbound.optJSONObject("settings") ?: continue

            val vnext = settings.optJSONArray("vnext")
            if (vnext != null && vnext.length() > 0) {
                val server = vnext.optJSONObject(0) ?: continue
                return validated(server.optString("address"), server.optInt("port"))
            }

            val servers = settings.optJSONArray("servers")
            if (servers != null && servers.length() > 0) {
                val server = servers.optJSONObject(0) ?: continue
                return validated(server.optString("address"), server.optInt("port"))
            }
        }
        error("No supported proxy endpoint")
    }

    private fun validated(host: String, port: Int): Endpoint {
        val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
        require(cleanHost.isNotBlank() && port in 1..65535)
        return Endpoint(cleanHost, port)
    }

    private const val TIMEOUT_MS = 2500
}
