package com.ontunnel.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback

/**
 * Thin client for the remote (AIDL) API of the "OpenVPN for Android" app (package de.blinkt.openvpn).
 * That app owns the OpenVPN engine and the Android VpnService; ON Tunnel supplies the profile.
 */
class OpenVpnController(
    private val context: Context,
    private val listener: (state: String, message: String, level: String) -> Unit
) {
    companion object {
        const val PKG = "de.blinkt.openvpn"
        private const val API_ACTION = "de.blinkt.openvpn.api.IOpenVPNAPIService"

        fun isInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(PKG, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var service: IOpenVPNAPIService? = null
    private var binding = false
    private var registered = false
    private val waiters = ArrayList<(String?) -> Unit>()

    private val callback = object : IOpenVPNStatusCallback.Stub() {
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            main.post { listener(state.orEmpty(), message.orEmpty(), level.orEmpty()) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IOpenVPNAPIService.Stub.asInterface(binder)
            binding = false
            flush(null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            registered = false
        }
    }

    private fun flush(error: String?) {
        val copy = ArrayList(waiters)
        waiters.clear()
        copy.forEach { it(error) }
    }

    /** Binds to OpenVPN for Android. [done] receives null on success or an error message. Main thread only. */
    fun bind(done: (String?) -> Unit) {
        if (service != null) { done(null); return }
        waiters.add(done)
        if (binding) return
        binding = true
        val ok = try {
            context.bindService(Intent(API_ACTION).setPackage(PKG), connection, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) { false }
        if (!ok) {
            binding = false
            flush("Could not connect to OpenVPN for Android")
        }
    }

    /** Null = this app may already use the API; otherwise launch the returned Intent and wait for RESULT_OK. */
    fun prepareApi(): Intent? = service?.prepare(context.packageName)

    /** Null = Android VPN permission already granted to OpenVPN for Android. */
    fun prepareVpn(): Intent? = service?.prepareVPNService()

    /** Status callbacks (also delivers the current state right after registering). */
    fun register() {
        if (registered) return
        try {
            service?.registerStatusCallback(callback)
            registered = true
        } catch (_: Exception) { }
    }

    fun start(config: String) {
        val s = service ?: error("Not connected to OpenVPN for Android")
        s.startVPN(config)
    }

    fun disconnect() {
        try { service?.disconnect() } catch (_: Exception) { }
    }

    fun release() {
        try { if (registered) service?.unregisterStatusCallback(callback) } catch (_: Exception) { }
        registered = false
        try { if (service != null || binding) context.unbindService(connection) } catch (_: Exception) { }
        service = null
        binding = false
        waiters.clear()
    }
}
