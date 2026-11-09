package com.ontunnel.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.core.app.NotificationCompat
import java.security.SecureRandom
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

class ONTunnelVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var core: CoreController? = null
    @Volatile private var monitorActive = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_START -> {
                // Verify again at the VPN-service boundary; a patched UI alone cannot start a tunnel.
                if (!AppIntegrity.isAppValid(this)) {
                    sendBroadcast(
                        Intent(ACTION_STATE).setPackage(packageName)
                            .putExtra(EXTRA_STATE, "error")
                            .putExtra(EXTRA_ERROR, "App integrity check failed")
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, notification())
                Thread { startTunnel(config) }.start()
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(config: String) {
        try {
            stopCore()
            // Test the actual proxy host/port, not Android's network flags alone.
            val endpoint = proxyEndpoint(config)
                ?: error("Cannot find the server IP/host and port in this configuration")
            if (!probeEndpoint(endpoint.first, endpoint.second)) {
                error("Server is unreachable. Check your data/Wi-Fi connection or server address.")
            }
            // New Xray removed allowInsecure: convert it to certificate pinning (done before the VPN is up).
            val finalConfig = TlsInsecureCompat.prepare(config)
            val builder = Builder().setSession("ON Tunnel").setMtu(1500)
                .addAddress("10.111.0.1", 32).addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }
            tun = builder.establish() ?: error("Failed to create VPN interface")
            Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(filesDir.absolutePath, xudpBaseKey())
            val callback = object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(code: Long, message: String?): Long = 0
            }
            core = Libv2ray.newCoreController(callback)
            core!!.startLoop(finalConfig, tun!!.fd.toLong().toInt())
            running = true
            sendBroadcast(
                Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_STATE, "connected")
            )
            // Recheck every five seconds; clear Connected when the network or host goes down.
            monitorActive = true
            Thread {
                while (monitorActive && running) {
                    try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                    if (!monitorActive || !running) break
                    if (!probeEndpoint(endpoint.first, endpoint.second)) {
                        stopCore()
                        sendBroadcast(
                            Intent(ACTION_STATE).setPackage(packageName)
                                .putExtra(EXTRA_STATE, "error")
                                .putExtra(EXTRA_ERROR, "Internet connection lost or server is unreachable")
                        )
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                }
            }.start()
        } catch (e: Exception) {
            stopCore()
            sendBroadcast(
                Intent(ACTION_STATE).setPackage(packageName)
                    .putExtra(EXTRA_STATE, "error")
                    .putExtra(EXTRA_ERROR, e.message ?: "Failed to start connection")
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Xray requires the XUDP base key to be a 32-byte value encoded with
     * URL-safe Base64 WITHOUT padding (43 characters). Anything else makes the core
     * fail with "BaseKey must be 32 bytes". The key is generated once and persisted.
     */
    private fun xudpBaseKey(): String {
        val prefs = getSharedPreferences("on_tunnel_core", MODE_PRIVATE)
        val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        prefs.getString("xudp_key", null)?.let { saved ->
            try {
                if (Base64.decode(saved, flags).size == 32 && !saved.contains("=")) return saved
            } catch (_: Exception) {
            }
        }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val key = Base64.encodeToString(bytes, flags)
        prefs.edit().putString("xudp_key", key).apply()
        return key
    }

    /** Extract the VLESS/VMess/Trojan server host and port from Xray JSON. */
    private fun proxyEndpoint(config: String): Pair<String, Int>? {
        return try {
            val outbounds = JSONObject(config).optJSONArray("outbounds") ?: return null
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i) ?: continue
                if (outbound.optString("protocol") !in setOf("vless", "vmess", "trojan")) continue
                val settings = outbound.optJSONObject("settings") ?: continue
                val servers = settings.optJSONArray("vnext") ?: settings.optJSONArray("servers") ?: continue
                val server = servers.optJSONObject(0) ?: continue
                val host = server.optString("address").trim()
                val port = server.optInt("port", 0)
                if (host.isNotBlank() && port in 1..65535) return host to port
            }
            null
        } catch (_: Exception) { null }
    }

    /** Test the endpoint using the underlying network, outside the VPN tunnel. */
    private fun probeEndpoint(host: String, port: Int): Boolean {
        val socket = Socket()
        return try {
            if (!protect(socket)) return false
            socket.connect(InetSocketAddress(host, port), 7000)
            true
        } catch (_: Exception) {
            false
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun stopCore() {
        monitorActive = false
        running = false
        try {
            core?.stopLoop()
        } catch (_: Exception) {
        }
        core = null
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
    }

    private fun stopTunnel() {
        stopCore()
        sendBroadcast(
            Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_STATE, "disconnected")
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopCore()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val channel = "on_tunnel_vpn"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "ON Tunnel VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("ON Tunnel")
            .setContentText("VPN connection is active")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.ontunnel.app.START"
        const val ACTION_STOP = "com.ontunnel.app.STOP"
        const val ACTION_STATE = "com.ontunnel.app.STATE"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_STATE = "state"
        const val EXTRA_ERROR = "error"
        const val NOTIFICATION_ID = 7001

        @Volatile
        var running: Boolean = false
    }
}
