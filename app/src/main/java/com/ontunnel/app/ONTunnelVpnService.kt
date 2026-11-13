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
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ONTunnelVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var core: CoreController? = null
    @Volatile private var protocolLabel: String = "V2Ray"

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
                protocolLabel = intent.getStringExtra(EXTRA_PROTOCOL)
                    ?.uppercase()
                    ?.takeIf { it == "SSH" } ?: "V2Ray"
                startForeground(NOTIFICATION_ID, notification("$protocolLabel Connecting"))
                Thread { startTunnel(config) }.start()
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(config: String) {
        try {
            stopCore()
            // New Xray removed allowInsecure: convert it to certificate pinning (done before the VPN is up).
            val finalConfig = TlsInsecureCompat.prepare(config)
            // Initialize Xray and test this server ONCE before creating the VPN.
            // This mirrors V2RayNG's outbound-delay check: the probe is performed by
            // a temporary core instance and does not depend on the phone's VPN route.
            Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(filesDir.absolutePath, xudpBaseKey())
            // Run the outbound check with a hard timeout so a silent/expired server
            // cannot leave the UI stuck in Connecting forever.
            val probe = FutureTask<Long> {
                Libv2ray.measureOutboundDelay(finalConfig, SERVER_TEST_URL)
            }
            Thread(probe, "ON-Tunnel-server-check").apply {
                isDaemon = true
                start()
            }
            val serverDelay = try {
                probe.get(SERVER_TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                probe.cancel(true)
                error("Failed connect to internet")
            }
            if (serverDelay < 0L) {
                error("Failed connect to internet")
            }

            val builder = Builder().setSession("ON Tunnel").setMtu(1500)
                .addAddress("10.111.0.1", 32).addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }
            tun = builder.establish() ?: error("Failed to create VPN interface")
            val callback = object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(code: Long, message: String?): Long = 0
            }
            core = Libv2ray.newCoreController(callback)
            core!!.startLoop(finalConfig, tun!!.fd.toLong().toInt())

            // Do not probe the remote server to decide whether to keep the VPN alive.
            // Reachability probes can fail because of temporary filtering, blocked test
            // endpoints, or server-side policy and must never tear down an active TUN.
            // A loaded Xray core stays running until the user explicitly disconnects
            // or Android revokes/destroys the VPN service.
            running = true
            updateNotification("$protocolLabel Connected")
            if (protocolLabel == "SSH") SshTunnelService.hideNotification(this)
            sendBroadcast(
                Intent(ACTION_STATE).setPackage(packageName)
                    .putExtra(EXTRA_STATE, "connected")
                    .putExtra(EXTRA_PROTOCOL, protocolLabel)
            )
        } catch (e: Exception) {
            stopCore()
            sendBroadcast(
                Intent(ACTION_STATE).setPackage(packageName)
                    .putExtra(EXTRA_STATE, "error")
                    .putExtra(EXTRA_ERROR, safeConnectionError(e))
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
    private fun safeConnectionError(error: Exception): String {
        val detail = error.message.orEmpty()
        // Keep the internal generate_204 probe private; never expose its URL/EOF text.
        return if (
            detail.contains("generate_204", ignoreCase = true) ||
            detail.contains("google.com", ignoreCase = true) ||
            detail.contains("EOF", ignoreCase = true)
        ) "Failed connect to internet"
        else detail.takeIf { it.isNotBlank() } ?: "Failed to start connection"
    }

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

    private fun stopCore() {
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

    private fun updateNotification(status: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(status))
        } catch (_: Exception) {
        }
    }

    private fun notification(status: String): Notification {
        val channel = "on_tunnel_vpn"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "ON Tunnel VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("ON Tunnel")
            .setContentText(status)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.ontunnel.app.START"
        const val ACTION_STOP = "com.ontunnel.app.STOP"
        const val ACTION_STATE = "com.ontunnel.app.STATE"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_STATE = "state"
        const val EXTRA_ERROR = "error"
        const val NOTIFICATION_ID = 7001
        private const val SERVER_TEST_URL = "https://www.google.com/generate_204"
        private const val SERVER_TEST_TIMEOUT_MS = 20_000L

        @Volatile
        var running: Boolean = false
    }
}
