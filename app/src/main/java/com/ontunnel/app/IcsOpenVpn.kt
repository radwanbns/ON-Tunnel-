package com.ontunnel.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader

/** Embedded ICS OpenVPN engine. This app owns the VPN service; no companion app is required. */
object IcsOpenVpn {
    sealed class Status {
        object Connecting : Status()
        object Connected : Status()
        object Waiting : Status()
        object NeedsUserAction : Status()
        data class Failed(val message: String) : Status()
        object Stopped : Status()
    }

    private var listener: VpnStatus.StateListener? = null
    private var sawActive = false

    private fun quietNotifications(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                listOf("openvpn_bg", "openvpn_newstat", "openvpn_status", "vpn", "background", "openvpn")
                    .forEach { id ->
                        nm.createNotificationChannel(
                            NotificationChannel(id, "OpenVPN background", NotificationManager.IMPORTANCE_MIN).apply {
                                setShowBadge(false)
                                setSound(null, null)
                                enableVibration(false)
                                enableLights(false)
                            }
                        )
                    }
            }
        } catch (_: Throwable) { }
    }

    /** Parses a complete .ovpn config (including any locally rewritten relay remote) and starts ICS. */
    fun start(ctx: Context, profileName: String, configText: String) {
        val parser = ConfigParser()
        try {
            parser.parseConfig(StringReader(configText))
        } catch (e: Exception) {
            throw IllegalStateException("Invalid .ovpn configuration: ${e.message ?: e.javaClass.simpleName}")
        }
        val profile = try {
            parser.convertProfile()
        } catch (e: Exception) {
            throw IllegalStateException(
                "Could not load .ovpn configuration: ${e.message ?: e.javaClass.simpleName}. " +
                    "Embed certificates and keys inside the file (<ca>, <cert>, <key>, <tls-auth>) if required."
            )
        }

        profile.mName = profileName.ifBlank { "OpenVPN" }
        // Keep ON Tunnel's own update/subscription requests outside its VPN tunnel.
        profile.mAllowedAppsVpn.add(ctx.packageName)
        profile.mAllowedAppsVpnAreDisallowed = true

        quietNotifications(ctx)
        ProfileManager.setTemporaryProfile(ctx, profile)
        VPNLaunchHelper.startOpenVpn(profile, ctx, "ONTunnel", true)
    }

    @Synchronized
    fun observe(onDetail: (String) -> Unit = {}, onStatus: (Status) -> Unit) {
        removeObserver()
        sawActive = false
        val stateListener = object : VpnStatus.StateListener {
            override fun updateState(
                state: String?,
                logmessage: String?,
                localizedResId: Int,
                level: ConnectionStatus?,
                intent: Intent?
            ) {
                if (!state.isNullOrBlank()) onDetail(state)
                val mapped = when (level) {
                    ConnectionStatus.LEVEL_CONNECTED -> {
                        sawActive = true
                        Status.Connected
                    }
                    ConnectionStatus.LEVEL_START,
                    ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                    ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED -> {
                        sawActive = true
                        Status.Connecting
                    }
                    ConnectionStatus.LEVEL_NONETWORK,
                    ConnectionStatus.LEVEL_VPNPAUSED -> if (sawActive) Status.Waiting else null
                    ConnectionStatus.LEVEL_AUTH_FAILED ->
                        Status.Failed("Authentication failed: check your username and password.")
                    ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> Status.NeedsUserAction
                    ConnectionStatus.LEVEL_NOTCONNECTED -> if (sawActive) Status.Stopped else null
                    else -> null
                }
                if (mapped != null) onStatus(mapped)
            }

            override fun setConnectedVPN(uuid: String?) {}
        }
        listener = stateListener
        VpnStatus.addStateListener(stateListener)
    }

    @Synchronized
    fun removeObserver() {
        listener?.let { VpnStatus.removeStateListener(it) }
        listener = null
    }

    /** Stops the embedded OpenVPN service if it is running. */
    fun stop(ctx: Context) {
        removeObserver()
        try { ProfileManager.setConnectedVpnProfileDisconnected(ctx) } catch (_: Throwable) { }
        val app = ctx.applicationContext
        val intent = Intent(app, OpenVPNService::class.java).setAction(OpenVPNService.START_SERVICE)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    IOpenVPNServiceInternal.Stub.asInterface(binder)?.stopVPN(false)
                } catch (_: Throwable) {
                } finally {
                    try { app.unbindService(this) } catch (_: Throwable) { }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        try {
            app.bindService(intent, connection, 0)
        } catch (_: Throwable) { }
    }
}
