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

/**
 * Foreground service that keeps the loopback relay alive while OpenVPN for Android
 * tunnels through it (HTTP payload / WebSocket transports).
 */
class OvpnTunnelService : Service() {
    private var proxy: TransportProxy? = null
    private val main = Handler(Looper.getMainLooper())

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
        startForeground(NOTIFICATION_ID, notification())
        Thread { begin(profile) }.start()
        return START_NOT_STICKY
    }

    private fun begin(profile: OvpnProfile) {
        try {
            proxy?.stop()
            val t = profile.toTransport()
            // Resolve once, BEFORE the tunnel is up; the same IP is then excluded from the tunnel.
            val all = InetAddress.getAllByName(t.dialHost)
            val addr = all.firstOrNull { it is Inet4Address } ?: all.first()
            val p = TransportProxy(t, addr) { msg -> report { onRelayError?.invoke(msg) } }
            val port = p.start()
            proxy = p
            report { onReady?.invoke(port, if (addr is Inet4Address) addr.hostAddress else null) }
        } catch (e: Exception) {
            report { onFailed?.invoke(e.message ?: "Could not start the transport relay") }
            stopSelf()
        }
    }

    private fun report(block: () -> Unit) { main.post(block) }

    override fun onDestroy() {
        proxy?.stop()
        proxy = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val channel = "on_tunnel_relay"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "ON Tunnel transport", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("ON Tunnel")
            .setContentText("OpenVPN transport relay is running")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.ontunnel.app.OVPN_RELAY_START"
        const val EXTRA_PROFILE = "profile"
        const val NOTIFICATION_ID = 7002

        /** (localPort, upstreamIpv4OrNull) – relay is listening. Delivered on the main thread. */
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
