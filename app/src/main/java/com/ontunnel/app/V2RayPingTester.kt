package com.ontunnel.app

import android.util.Base64
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.system.measureTimeMillis

internal object V2RayPingTester {
    private data class Endpoint(
        val host: String,
        val port: Int,
        val path: String,
        val wsHost: String,
        val serverName: String,
        val useTls: Boolean,
        val extraHeaders: Map<String, String>
    )

    fun isSupported(config: String): Boolean = runCatching { endpoint(config) }.isSuccess

    fun test(config: String): String {
        val first = testOnce(config)
        if (first.endsWith("ms") || first.contains("HTTP ")) return first
        // Mobile networks and Cloudflare edges can occasionally drop the first handshake.
        Thread.sleep(RETRY_DELAY_MS)
        val second = testOnce(config)
        if (second.endsWith("ms")) return second
        return second
    }

    private fun testOnce(config: String): String {
        val target = runCatching { endpoint(config) }.getOrElse { return "WS ONLY (80/443)" }
        return try {
            val elapsed = measureTimeMillis {
                Socket().use { raw ->
                    raw.connect(InetSocketAddress(target.host, target.port), TIMEOUT_MS)
                    raw.soTimeout = TIMEOUT_MS

                    val socket: Socket = if (target.useTls) {
                        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                            .createSocket(raw, target.host, target.port, true) as SSLSocket
                        ssl.soTimeout = TIMEOUT_MS
                        if (!target.serverName.matches(Regex("[0-9.]+"))) {
                            val params = ssl.sslParameters
                            params.serverNames = listOf(SNIHostName(target.serverName))
                            ssl.sslParameters = params
                        }
                        ssl.startHandshake()
                        ssl
                    } else raw

                    socket.use { connection ->
                        val key = Base64.encodeToString(
                            ByteArray(16).also { SecureRandom().nextBytes(it) },
                            Base64.NO_WRAP
                        )
                        val request = buildString {
                            append("GET ${target.path} HTTP/1.1\r\n")
                            append("Host: ${target.wsHost}\r\n")
                            append("Upgrade: websocket\r\n")
                            append("Connection: Upgrade\r\n")
                            append("Sec-WebSocket-Key: $key\r\n")
                            append("Sec-WebSocket-Version: 13\r\n")
                            target.extraHeaders.forEach { (name, value) ->
                                if (!name.equals("Host", true) &&
                                    !name.equals("Upgrade", true) &&
                                    !name.equals("Connection", true) &&
                                    !name.equals("Sec-WebSocket-Key", true) &&
                                    !name.equals("Sec-WebSocket-Version", true)
                                ) append("$name: $value\r\n")
                            }
                            append("\r\n")
                        }
                        connection.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                        connection.getOutputStream().flush()

                        val reader = connection.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val status = reader.readLine() ?: throw WsHttpException("EMPTY RESPONSE")
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        if (!status.contains(" 101 ")) {
                            throw WsHttpException(status.substringAfter(" ").take(55))
                        }
                    }
                }
            }
            "${target.port} • ${elapsed.coerceAtLeast(1)} ms"
        } catch (_: java.net.SocketTimeoutException) {
            "${target.port} • TIMEOUT"
        } catch (_: java.net.UnknownHostException) {
            "${target.port} • DNS ERROR"
        } catch (_: java.net.ConnectException) {
            "${target.port} • OFFLINE"
        } catch (_: javax.net.ssl.SSLException) {
            "${target.port} • TLS ERROR"
        } catch (e: WsHttpException) {
            "${target.port} • HTTP ${e.message}"
        } catch (_: Exception) {
            "${target.port} • WS ERROR"
        }
    }

    private fun endpoint(config: String): Endpoint {
        val root = JSONObject(config)
        val outbounds = root.optJSONArray("outbounds") ?: error("Missing outbounds")
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            if (outbound.optString("protocol").lowercase() !in setOf("vless", "vmess", "trojan")) continue

            val settings = outbound.optJSONObject("settings") ?: continue
            val stream = outbound.optJSONObject("streamSettings") ?: continue
            if (!stream.optString("network").equals("ws", true)) continue

            val ws = stream.optJSONObject("wsSettings") ?: continue
            val vnext = settings.optJSONArray("vnext")
            val servers = settings.optJSONArray("servers")
            val server = when {
                vnext != null && vnext.length() > 0 -> vnext.optJSONObject(0)
                servers != null && servers.length() > 0 -> servers.optJSONObject(0)
                else -> null
            } ?: continue

            val host = server.optString("address").trim().removePrefix("[").removeSuffix("]")
            val port = server.optInt("port")
            require(host.isNotBlank() && port in ALLOWED_PORTS)

            val headers = ws.optJSONObject("headers")
            val wsHost = headers?.optString("Host")?.takeIf { it.isNotBlank() } ?: host
            val rawPath = ws.optString("path", "/")
            val path = when {
                rawPath.isBlank() -> "/"
                rawPath.startsWith("/") -> rawPath
                else -> "/$rawPath"
            }
            val extras = linkedMapOf<String, String>()
            if (headers != null) {
                val keys = headers.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val value = headers.optString(name)
                    if (value.isNotBlank() && !name.contains('\r') && !name.contains('\n') &&
                        !value.contains('\r') && !value.contains('\n')
                    ) extras[name] = value
                }
            }
            val tls = stream.optJSONObject("tlsSettings")
            val serverName = tls?.optString("serverName")?.takeIf { it.isNotBlank() } ?: wsHost
            val security = stream.optString("security").lowercase()
            return Endpoint(
                host = host,
                port = port,
                path = path,
                wsHost = wsHost,
                serverName = serverName,
                useTls = port == 443 || security == "tls",
                extraHeaders = extras
            )
        }
        error("No WS server on port 80 or 443")
    }

    private class WsHttpException(message: String) : Exception(message)
    private val ALLOWED_PORTS = setOf(80, 443)
    private const val TIMEOUT_MS = 8000
    private const val RETRY_DELAY_MS = 500
}
