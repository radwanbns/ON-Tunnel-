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
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

class ONTunnelVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var core: CoreController? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_START -> {
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
            val builder = Builder().setSession("ON Tunnel").setMtu(1500)
                .addAddress("10.111.0.1", 32).addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }
            tun = builder.establish() ?: error("Failed to create VPN interface")
            Seq.setContext(applicationContext)

            // Xray expects BaseKey as Base64 encoding of exactly 32 raw bytes.
            // Generate it here to avoid copied or line-wrapped Base64 literals.
            val xudpKeyBytes = "ON-Tunnel-XUDP-Key-32-Bytes-Only".toByteArray(Charsets.UTF_8)
            check(xudpKeyBytes.size == 32) {
                "Internal XUDP key must be exactly 32 bytes (was ${xudpKeyBytes.size})"
            }
            val xudpBaseKey = Base64.encodeToString(xudpKeyBytes, Base64.NO_WRAP)
            val decodedKey = Base64.decode(xudpBaseKey, Base64.DEFAULT)
            check(decodedKey.size == 32) {
                "Internal XUDP BaseKey must decode to 32 bytes (was ${decodedKey.size})"
            }
            Libv2ray.initCoreEnv(filesDir.absolutePath, xudpBaseKey)

            val callback = object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(code: Long, message: String?): Long = 0
            }
            core = Libv2ray.newCoreController(callback)
            core!!.startLoop(config, tun!!.fd.toLong().toInt())
            sendBroadcast(
                Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_STATE, "connected")
            )
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

    private fun stopCore() {
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
            .setSmallIcon(android.R.drawable.stat_sys_warning)
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
    }
}
