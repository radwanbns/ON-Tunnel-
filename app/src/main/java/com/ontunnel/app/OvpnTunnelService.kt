package com.ontunnel.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ontunnel.app.ovpn.TransportProxy
import java.net.Inet4Address
import java.net.InetAddress
import java.util.LinkedHashSet

/**
 * Foreground service that keeps the loopback relay alive while OpenVPN for Android
 * tunnels through it (HTTP payload / WebSocket transports).
 */
class OvpnTunnelService : Service() {
    private var proxy: TransportProxy? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastAt = 0L
    private val ticker = object : Runnable {
        override fun run() {
            val p = proxy
            if (p != null) {
                val now = System.nanoTime()
                val rx = p.rxBytes(); val tx = p.txBytes()
                val dt = if (lastAt == 0L) 1.0 else (now - lastAt) / 1e9
                val down = if (dt > 0) ((rx - lastRx) / dt).toLong() else 0L
                val up = if (dt > 0) ((tx - lastTx) / dt).toLong() else 0L
                lastRx = rx; lastTx = tx; lastAt = now
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("↓ ${speed(down)}   ↑ ${speed(up)}"))
                main.postDelayed(this, 1000L)
            }
        }
    }

    private fun speed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / 1048576.0)
        else -> String.format(java.util.Locale.US, "%d KB/s", bytesPerSec / 1024L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        if (!AppIntegrity.isAppValid(this)) {
            report { onFailed?.invoke("App integrity check failed") }
            stopSelf()
            return START_NOT_STICKY
        }
        val profile = intent.getStringExtra(EXTRA_PROFILE)?.let { OvpnProfile.fromJson(it) }
        if (profile == null || profile.validate() != null) {
            report { onFailed?.invoke(profile?.validate() ?: "Invalid OpenVPN profile") }
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("↓ 0 KB/s   ↑ 0 KB/s"))
        Thread { begin(profile) }.start()
        return START_NOT_STICKY
    }

    private fun begin(profile: OvpnProfile) {
        try {
            proxy?.stop()
            val t = profile.toTransport()
            // Every resolved address is a candidate (IPv4 first): the relay tries them in order.
            val all = InetAddress.getAllByName(t.dialHost)
            val ordered = all.sortedBy { if (it is Inet4Address) 0 else 1 }.toTypedArray()
            val p = TransportProxy(t, ordered) { msg -> report { onRelayError?.invoke(msg) } }
            val port = p.start()
            proxy = p
            lastRx = 0L; lastTx = 0L; lastAt = 0L
            main.removeCallbacks(ticker)
            main.post(ticker)
            AppLogs.add(this, "OpenVPN relay listening on 127.0.0.1:$port")
            // Complete the upstream TCP/TLS + payload/WebSocket handshake first.
            // OpenVPN is launched only after a ready transport session is waiting in the relay.
            if (t.deferPreflight) {
                AppLogs.add(this, "HTTP PAYLOAD handshake deferred until OpenVPN opens the relay")
            } else {
                AppLogs.add(this, "Preparing proxy and payload handshake")
                p.prepare()
                AppLogs.add(this, "Proxy and payload handshake completed")
            }

            val excludeIps = LinkedHashSet<String>()
            fun addResolved(host: String?) {
                if (host.isNullOrBlank()) return
                val h = host.trim()
                if (h.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) {
                    excludeIps.add(h)
                    return
                }
                try {
                    InetAddress.getAllByName(h).forEach { a ->
                        if (a is Inet4Address) a.hostAddress?.let { excludeIps.add(it) }
                    }
                } catch (_: Exception) { }
            }
            ordered.forEach { a -> if (a is Inet4Address) a.hostAddress?.let { excludeIps.add(it) } }
            addResolved(t.dialHost)
            addResolved(t.targetHost)
            addResolved(profile.proxyHost)
            if (profile.serverOverride.isNotBlank()) {
                addResolved(profile.serverOverride.substringBefore(":"))
            }
            HOST_IN_PAYLOAD.findAll(profile.payload).forEach { m ->
                addResolved(m.groupValues[1].trim())
            }

            val csv = excludeIps.joinToString(",")
            report { AppLogs.add(this, "OpenVPN relay ready; launching engine"); onReady?.invoke(port, csv.ifBlank { null }) }
        } catch (e: Exception) {
            // Keep the service alive: the app may retry (auto-reconnect) and a background app cannot start a new
            // foreground service. If it gives up, failOvpn() stops this service.
            report { AppLogs.add(this, "OpenVPN relay error: ${e.message ?: e.javaClass.simpleName}"); onFailed?.invoke(e.message ?: "Could not start the transport relay") }
        }
    }

    private fun report(block: () -> Unit) { main.post(block) }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        proxy?.stop()
        proxy = null
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val channel = "on_tunnel_relay_speed"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "ON Tunnel", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("ON Tunnel")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_START = "com.ontunnel.app.OVPN_RELAY_START"
        const val EXTRA_PROFILE = "profile"
        const val NOTIFICATION_ID = 7002

        private val HOST_IN_PAYLOAD =
            Regex("""(?i)Host:\s*([^\r\n\s,:]+)""")

        /** (localPort, comma-separated upstream IPv4s for net_gateway routes). */
        @Volatile var onReady: ((Int, String?) -> Unit)? = null
        @Volatile var onFailed: ((String) -> Unit)? = null
        @Volatile var onRelayError: ((String) -> Unit)? = null

        fun start(context: Context, profile: OvpnProfile) {
            val i = Intent(context, OvpnTunnelService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROFILE, profile.toJson().toString())
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OvpnTunnelService::class.java))
        }
    }
}
