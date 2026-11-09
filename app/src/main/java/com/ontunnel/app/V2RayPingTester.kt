package com.ontunnel.app

import android.util.Base64
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.system.measureTimeMillis

/**
 * Checks the actual WebSocket HTTP upgrade handshake for V2Ray/Xray WS servers.
 * Only WS endpoints on ports 80 and 443 are eligible.
 * This verifies the WS transport handshake, not V2Ray user authentication.
 */
internal object V2RayPingTester {
    private data class Endpoint(
        val host: String,
        val port: Int,
        val path: String,
        val wsHost: String,
        val useTls: Boolean,
        val extraHeaders: Map<String, String>
    )

    fun isSupported(config: String): Boolean = runCatching { endpoint(config) }.isSuccess

    fun test(config: String): String {
        val target = runCatching { endpoint(config) }.getOrElse { return "WS ONLY (80/443)" }
        return try {
            val response = measureTimeMillis {
                Socket().use { raw ->
                    raw.connect(InetSocketAddress(target.host, target.port), TIMEOUT_MS)
                    raw.soTimeout = TIMEOUT_MS

                    val transport: Socket = if (target.useTls) {
                        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                            .createSocket(raw, target.host, target.port, true) as SSLSocket
                        ssl.soTimeout = TIMEOUT_MS
                        ssl.startHandshake()
                        ssl
                    } else raw

                    transport.use { socket ->
                        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
                        val key = Base64.encodeToString(nonce, Base64.NO_WRAP)
                        val request = buildString {
                            append("GET ${target.path} HTTP/1.1\\r\\n")
                            append("Host: ${target.wsHost}\\r\\n")
                            append("Upgrade: websocket\\r\\n")
                            append("Connection: Upgrade\\r\\n")
                            append("Sec-WebSocket-Key: $key\\r\\n")
                            append("Sec-WebSocket-Version: 13\\r\\n")
                            target.extraHeaders.forEach { (name, value) ->
                                if (!name.equals("Host", true) &&
                                    !name.equals("Upgrade", true) &&
                                    !name.equals("Connection", true) &&
                                    !name.equals("Sec-WebSocket-Key", true) &&
                                    !name.equals("Sec-WebSocket-Version", true)
                                ) append("$name: $value\\r\\n")
                            }
                            append("\\r\\n")
                        }
                        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                        socket.getOutputStream().flush()

                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val statusLine = reader.readLine() ?: error("Empty WS response")
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        if (!statusLine.contains(" 101 ")) {
                            error("WS handshake rejected: ${statusLine.take(80)}")
                        }
                    }
                }
            }
            "${target.port} • ${response.coerceAtLeast(1)} ms"
        } catch (_: java.net.SocketTimeoutException) {
            "${target.port} • TIMEOUT"
        } catch (_: java.net.UnknownHostException) {
            "${target.port} • DNS ERROR"
        } catch (_: java.net.ConnectException) {
            "${target.port} • OFFLINE"
        } catch (e: Exception) {
            "${target.port} • WS FAILED"
        }
    }

    private fun endpoint(config: String): Endpoint {
        val root = JSONObject(config)
        val outbounds = root.optJSONArray("outbounds") ?: error("Missing outbounds")
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            val protocol = outbound.optString("protocol").lowercase()
            if (protocol !in setOf("vless", "vmess", "trojan")) continue

            val settings = outbound.optJSONObject("settings") ?: continue
            val stream = outbound.optJSONObject("streamSettings") ?: continue
            if (!stream.optString("network").equals("ws", ignoreCase = true)) continue

            val ws = stream.optJSONObject("wsSettings") ?: continue
            val vnext = settings.optJSONArray("vnext")
            val servers = settings.optJSONArray("servers")
            val server = when {
                vnext != null && vnext.length() > 0 -> vnext.optJSONObject(0)
                servers != null && servers.length() > 0 -> servers.optJSONObject(0)
                else -> null
            } ?: continue

            val host = server.optString("address").trim()
                .removePrefix("[").removeSuffix("]")
            val port = server.optInt("port")
            require(host.isNotBlank() && port in ALLOWED_PORTS) {
                "Only WebSocket on ports 80 and 443 is supported"
            }

            val headers = ws.optJSONObject("headers")
            val wsHost = headers?.optString("Host")?.takeIf { it.isNotBlank() } ?: host
            val rawPath = ws.optString("path", "/").substringBefore('?')
            val path = when {
                rawPath.isBlank() -> "/"
                rawPath.startsWith("/") -> rawPath
                else -> "/$rawPath"
            }
            val extraHeaders = linkedMapOf<String, String>()
            if (headers != null) {
                val keys = headers.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val value = headers.optString(name)
                    if (value.isNotBlank() && !name.contains('\\r') && !name.contains('\\n') &&
                        !value.contains('\\r') && !value.contains('\\n')
                    ) extraHeaders[name] = value
                }
            }
            val security = stream.optString("security").lowercase()
            return Endpoint(
                host = host,
                port = port,
                path = path,
                wsHost = wsHost,
                useTls = port == 443 || security == "tls",
                extraHeaders = extraHeaders
            )
        }
        error("No WS server on port 80 or 443")
    }

    private val ALLOWED_PORTS = setOf(80, 443)
    private const val TIMEOUT_MS = 3500
}
