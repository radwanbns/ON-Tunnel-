package com.ontunnel.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Local-network-only HTTP CONNECT and SOCKS5 proxy listeners. */
class ProxyShareService : Service() {
    private val clients = Executors.newFixedThreadPool(24)
    private var http: ServerSocket? = null
    private var socks: ServerSocket? = null
    private var ip = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground()
        when (intent?.action) {
            START_HTTP -> startHttp(intent.getIntExtra(PORT, DEFAULT_HTTP_PORT))
            STOP_HTTP -> stopHttp()
            START_SOCKS -> startSocks(intent.getIntExtra(PORT, DEFAULT_SOCKS_PORT))
            STOP_SOCKS -> stopSocks()
        }
        if (http == null && socks == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Proxy sharing", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("ON Tunnel proxy sharing")
            .setContentText("Local HTTP / SOCKS proxy service")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION, notification)
    }

    private fun startHttp(port: Int) {
        if (http != null) return
        startListener(port, "http") { socket -> handleHttp(socket) }
    }

    private fun startSocks(port: Int) {
        if (socks != null) return
        startListener(port, "socks") { socket -> handleSocks(socket) }
    }

    private fun startListener(port: Int, kind: String, handler: (Socket) -> Unit) {
        if (port !in 1024..65535) {
            AppLogs.add(this, "Proxy port must be between 1024 and 65535")
            return
        }
        val address = findLanAddress()
        if (address == null) {
            AppLogs.add(this, "Proxy sharing requires Wi-Fi or a phone hotspot")
            return
        }
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(address, port), 32)
            ip = address.hostAddress ?: ""
            if (kind == "http") http = server else socks = server
            prefs().edit().putString("local_ip", ip)
                .putBoolean(kind + "_running", true).putInt(kind + "_port", port).apply()
            AppLogs.add(this, kind.uppercase() + " proxy started at " + ip + ":" + port)
            thread(name = "ONProxy-" + kind, isDaemon = true) {
                while (!server.isClosed) {
                    try {
                        val client = server.accept()
                        clients.execute {
                            try { client.soTimeout = 30000; handler(client) }
                            catch (_: Exception) { try { client.close() } catch (_: Exception) { } }
                        }
                    } catch (_: Exception) {
                        if (!server.isClosed) AppLogs.add(this, kind + " proxy listener stopped")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogs.add(this, kind + " proxy failed: " + (e.message ?: "unknown error"))
            if (kind == "http") http = null else socks = null
        }
    }

    private fun handleHttp(client: Socket) {
        client.use { incoming ->
            val input = BufferedInputStream(incoming.getInputStream())
            val first = line(input) ?: return
            val parts = first.split(" ", limit = 3)
            if (parts.size != 3) return
            val method = parts[0].uppercase()
            val target = parts[1]
            val headers = StringBuilder()
            var bytes = 0
            while (true) {
                val h = line(input) ?: return
                bytes += h.length
                if (bytes > 32768) return
                if (h.isEmpty()) break
                headers.append(h).append("\r\n")
            }
            val uri = try { if (method == "CONNECT") null else URI(target) } catch (_: Exception) { null }
            val hostHeader = headers.toString().lineSequence()
                .firstOrNull { it.startsWith("Host:", true) }?.substringAfter(":")?.trim()
            val authority = if (method == "CONNECT") target else
                (uri?.host?.let { it + ":" + if (uri.port > 0) uri.port else 80 } ?: hostHeader)
            if (authority.isNullOrBlank()) return
            val host = authority.substringBeforeLast(':', authority).trim('[', ']')
            val port = authority.substringAfterLast(':', if (method == "CONNECT") "443" else "80").toIntOrNull() ?: return
            Socket().use { remote ->
                remote.connect(InetSocketAddress(host, port), 12000)
                if (method == "CONNECT") {
                    incoming.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                } else {
                    val path = (uri?.rawPath?.ifBlank { "/" } ?: target) +
                        (uri?.rawQuery?.let { "?" + it } ?: "")
                    val out = remote.getOutputStream()
                    out.write((method + " " + path + " HTTP/1.1\r\n").toByteArray())
                    out.write(headers.toString().toByteArray())
                    out.write("\r\n".toByteArray())
                    out.flush()
                }
                relay(incoming, remote)
            }
        }
    }

    private fun handleSocks(client: Socket) {
        client.use { incoming ->
            val input = BufferedInputStream(incoming.getInputStream())
            val out = incoming.getOutputStream()
            if (input.read() != 5) return
            val n = input.read()
            if (n !in 1..16) return
            repeat(n) { input.read() }
            out.write(byteArrayOf(5, 0)); out.flush()
            if (input.read() != 5) return
            val command = input.read()
            input.read()
            val atyp = input.read()
            if (command != 1) { out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); return }
            val host = when (atyp) {
                1 -> ByteArray(4).also { readFully(input, it) }.joinToString(".") { (it.toInt() and 255).toString() }
                3 -> {
                    val length = input.read()
                    if (length !in 1..255) return
                    ByteArray(length).also { readFully(input, it) }.toString(Charsets.UTF_8)
                }
                4 -> ByteArray(16).also { readFully(input, it) }.let { java.net.InetAddress.getByAddress(it).hostAddress ?: return }
                else -> return
            }
            val p = ByteArray(2); readFully(input, p)
            val port = ((p[0].toInt() and 255) shl 8) or (p[1].toInt() and 255)
            Socket().use { remote ->
                try { remote.connect(InetSocketAddress(host, port), 12000) }
                catch (_: Exception) { out.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); return }
                out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                relay(incoming, remote)
            }
        }
    }

    private fun relay(a: Socket, b: Socket) {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { pipe(a, b) }
            val second = executor.submit { pipe(b, a) }
            try { first.get() } catch (_: Exception) { }
            try { second.get() } catch (_: Exception) { }
        } finally {
            try { a.close() } catch (_: Exception) { }
            try { b.close() } catch (_: Exception) { }
            executor.shutdownNow()
        }
    }

    private fun pipe(from: Socket, to: Socket) {
        val buffer = ByteArray(16384)
        val input = from.getInputStream()
        val output = to.getOutputStream()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            output.flush()
        }
    }

    private fun line(input: BufferedInputStream): String? {
        val result = StringBuilder()
        while (result.length < 8192) {
            val value = input.read()
            if (value < 0) return if (result.isEmpty()) null else result.toString()
            if (value == 10) break
            if (value != 13) result.append(value.toChar())
        }
        return result.toString()
    }

    private fun readFully(input: BufferedInputStream, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val count = input.read(data, offset, data.size - offset)
            if (count < 0) throw IllegalStateException("Incomplete SOCKS request")
            offset += count
        }
    }

    private fun findLanAddress(): Inet4Address? {
        val all = try { NetworkInterface.getNetworkInterfaces().toList() } catch (_: Exception) { emptyList() }
        val ordered = all.filter {
            try { it.isUp && !it.isLoopback && !it.isVirtual } catch (_: Exception) { false }
        }.sortedBy {
            val name = it.name.lowercase()
            if (name.startsWith("wlan") || name.startsWith("ap") || name.contains("wifi")) 0 else 1
        }
        for (nic in ordered) for (address in nic.inetAddresses) {
            if (address is Inet4Address && address.isSiteLocalAddress && !address.isLoopbackAddress) return address
        }
        return null
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun stopHttp() {
        try { http?.close() } catch (_: Exception) { }
        http = null
        prefs().edit().putBoolean("http_running", false).apply()
    }

    private fun stopSocks() {
        try { socks?.close() } catch (_: Exception) { }
        socks = null
        prefs().edit().putBoolean("socks_running", false).apply()
    }

    override fun onDestroy() {
        stopHttp(); stopSocks(); clients.shutdownNow()
        prefs().edit().putBoolean("http_running", false).putBoolean("socks_running", false).apply()
        super.onDestroy()
    }

    companion object {
        const val PREFS = "on_tunnel_proxy"
        const val DEFAULT_HTTP_PORT = 8080
        const val DEFAULT_SOCKS_PORT = 1080
        private const val CHANNEL = "on_tunnel_proxy"
        private const val NOTIFICATION = 7218
        private const val PORT = "port"
        private const val START_HTTP = "com.ontunnel.app.proxy.START_HTTP"
        private const val STOP_HTTP = "com.ontunnel.app.proxy.STOP_HTTP"
        private const val START_SOCKS = "com.ontunnel.app.proxy.START_SOCKS"
        private const val STOP_SOCKS = "com.ontunnel.app.proxy.STOP_SOCKS"

        fun start(context: Context, protocol: String, port: Int) {
            val action = if (protocol == "http") START_HTTP else START_SOCKS
            val intent = Intent(context, ProxyShareService::class.java).setAction(action).putExtra(PORT, port)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context, protocol: String) {
            val action = if (protocol == "http") STOP_HTTP else STOP_SOCKS
            context.startService(Intent(context, ProxyShareService::class.java).setAction(action))
        }
    }
}
