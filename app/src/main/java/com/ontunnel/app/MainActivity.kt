package com.ontunnel.app

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal enum class VpnStatus { Disconnected, Connecting, Connected, Disconnecting }

class MainActivity : ComponentActivity() {
    private var pendingConfig: String? = null
    private var status by mutableStateOf(if (ONTunnelVpnService.running) VpnStatus.Connected else VpnStatus.Disconnected)
    private var errorMessage by mutableStateOf<String?>(null)
    private var availableAppUpdate by mutableStateOf<AppUpdateInfo?>(null)
    private var updateDownloadId: Long = -1L

    // ── Embedded OpenVPN engine ────────────────────────────────────────
    private var statusDetail by mutableStateOf<String?>(null)
    private var ovpnMissing by mutableStateOf(false)
    private var pendingOvpn: OvpnProfile? = null
    private var ovpnSawProgress = false
    /** "" | "v2ray" | "openvpn" – which engine currently owns the tunnel. */
    private var activeProto = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ovpnTimeout = Runnable {
        if (status == VpnStatus.Connecting && !ovpnSawProgress) {
            failOvpn("OpenVPN connection timed out. Check the server and try again.")
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) pendingConfig?.let(::startVpn)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (activeProto == "openvpn") return
            when (intent?.getStringExtra(ONTunnelVpnService.EXTRA_STATE)) {
                "connected" -> { status = VpnStatus.Connected; errorMessage = null }
                "disconnected" -> { status = VpnStatus.Disconnected; if (activeProto == "v2ray") activeProto = "" }
                "error" -> {
                    status = VpnStatus.Disconnected
                    errorMessage = intent.getStringExtra(ONTunnelVpnService.EXTRA_ERROR).orEmpty().take(300)
                }
            }
        }
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val completedId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (completedId != updateDownloadId) return
            getSharedPreferences("app_update", MODE_PRIVATE).edit().remove("download_id").apply()
            updateDownloadId = -1L
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(completedId)
            manager.query(query).use { cursor ->
                if (cursor.moveToFirst() &&
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                ) {
                    getSharedPreferences("app_update", MODE_PRIVATE).edit()
                        .putBoolean("pending_install", true).apply()
                    installDownloadedUpdate()
                } else {
                    Toast.makeText(this@MainActivity, "Update download failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installDownloadedUpdate() {
        val prefs = getSharedPreferences("app_update", MODE_PRIVATE)
        if (!prefs.getBoolean("pending_install", false)) return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"))
            )
            Toast.makeText(this, "Allow ON Tunnel to install updates, then return here", Toast.LENGTH_LONG).show()
            return
        }
        if (AppUpdater.installDownloaded(this)) {
            prefs.edit().putBoolean("pending_install", false).apply()
        }
    }

    private fun startAppUpdateDownload(info: AppUpdateInfo) {
        try {
            val request = DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("ON Tunnel update ${info.versionName}")
                .setDescription("Downloading application update")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "ON-Tunnel-update.apk")
            updateDownloadId = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            getSharedPreferences("app_update", MODE_PRIVATE).edit()
                .putLong("download_id", updateDownloadId).apply()
            availableAppUpdate = null
            Toast.makeText(this, "Downloading ON Tunnel update...", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start update: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private var tamperDetected by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        // Re-check the installed APK certificate whenever the activity returns.
        if (!AppIntegrity.isAppValid(this)) {
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }
        if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) {
            installDownloadedUpdate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SubscriptionSync.schedule(this)
        // Close the app immediately if its signing certificate does not match.
        if (!AppIntegrity.isAppValid(this)) {
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }
        tamperDetected = false
        val savedProto = getSharedPreferences("on_tunnel_core", MODE_PRIVATE).getString("active_proto", "").orEmpty()
        activeProto = if (ONTunnelVpnService.running) "v2ray" else savedProto
        if (activeProto == "openvpn") {
            IcsOpenVpn.observe(
                onDetail = { detail -> runOnUiThread { if (activeProto == "openvpn") statusDetail = detail } },
                onStatus = ::onIcsStatus
            )
        }
        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(ONTunnelVpnService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, updateReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateDownloadId = getSharedPreferences("app_update", MODE_PRIVATE)
            .getLong("download_id", -1L)
        Thread {
            val update = AppUpdater.checkForUpdate(this)
            if (update != null) runOnUiThread { availableAppUpdate = update }
        }.start()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            ONTunnelApp(
                status = status,
                statusDetail = statusDetail,
                error = errorMessage,
                tampered = tamperDetected,
                onDismissError = { errorMessage = null },
                onConnect = ::connect,
                onDisconnect = ::disconnect,
                ovpnMissing = ovpnMissing,
                onDismissOvpnMissing = { ovpnMissing = false },
                onOpenOvpnStore = ::openOvpnStore,
                appUpdate = availableAppUpdate,
                onDownloadAppUpdate = ::startAppUpdateDownload,
                onDismissAppUpdate = { availableAppUpdate = null }
            )
        }
    }

    private fun connect(entry: ServerEntry) {
        errorMessage = null
        if (entry.isOpenVpn) {
            val profile = OvpnProfile.fromJson(entry.config)
            if (profile == null) errorMessage = "Invalid OpenVPN profile" else connectOpenVpn(profile)
        } else {
            connectV2Ray(entry.config)
        }
    }

    private fun connectV2Ray(config: String) {
        if (activeProto == "openvpn") {
            stopOvpn()
        }
        activeProto = "v2ray"
        statusDetail = null
        pendingConfig = config
        val permission = VpnService.prepare(this)
        if (permission != null) vpnPermission.launch(permission) else startVpn(config)
    }

    private fun startVpn(config: String) {
        val intent = Intent(this, ONTunnelVpnService::class.java)
            .setAction(ONTunnelVpnService.ACTION_START)
            .putExtra(ONTunnelVpnService.EXTRA_CONFIG, config)
        ContextCompat.startForegroundService(this, intent)
        status = VpnStatus.Connecting
    }

    private fun disconnect() {
        if (activeProto == "openvpn") {
            stopOvpn()
            activeProto = ""
            status = VpnStatus.Disconnected
            statusDetail = null
            return
        }
        startService(Intent(this, ONTunnelVpnService::class.java).setAction(ONTunnelVpnService.ACTION_STOP))
        status = VpnStatus.Disconnecting
    }

    // ── OpenVPN flow ───────────────────────────────────────────────────

    private fun persistProto(value: String) {
        getSharedPreferences("on_tunnel_core", MODE_PRIVATE).edit().putString("active_proto", value).apply()
    }

    private fun connectOpenVpn(profile: OvpnProfile) {
        profile.validate()?.let { errorMessage = it; return }
        if (activeProto == "v2ray") {
            startService(Intent(this, ONTunnelVpnService::class.java).setAction(ONTunnelVpnService.ACTION_STOP))
        } else if (activeProto == "openvpn") {
            stopOvpn()
        }
        activeProto = "openvpn"
        persistProto("openvpn")
        pendingOvpn = profile
        ovpnSawProgress = false
        status = VpnStatus.Connecting
        statusDetail = "Starting embedded OpenVPN"
        mainHandler.removeCallbacks(ovpnTimeout)
        mainHandler.postDelayed(ovpnTimeout, 60_000L)
        IcsOpenVpn.observe(
            onDetail = { detail -> runOnUiThread { if (activeProto == "openvpn") statusDetail = detail } },
            onStatus = ::onIcsStatus
        )
        startOvpnProfile()
    }

    private fun startOvpnProfile() {
        val profile = pendingOvpn
        if (profile == null) { failOvpn("Connection was interrupted, please try again"); return }
        if (!profile.usesRelay) {
            launchOvpn(profile.buildConfig(-1, null))
            return
        }
        // Payload / WebSocket: bring the local relay up first, then point OpenVPN at it.
        OvpnTunnelService.onReady = { port, ip ->
            statusDetail = "Starting OpenVPN"
            launchOvpn(profile.buildConfig(port, ip))
        }
        OvpnTunnelService.onFailed = { msg -> failOvpn(msg) }
        OvpnTunnelService.onRelayError = { msg -> if (status != VpnStatus.Connected) errorMessage = msg.take(300) }
        statusDetail = "Starting transport relay"
        try {
            OvpnTunnelService.start(this, profile)
        } catch (e: Exception) {
            failOvpn(e.message ?: "Could not start the transport relay")
        }
    }

    private fun launchOvpn(config: String) {
        try {
            val profile = pendingOvpn ?: error("OpenVPN profile is missing")
            IcsOpenVpn.start(this, profile.name, config)
        } catch (e: Exception) {
            failOvpn("OpenVPN error: ${e.message}")
        }
    }

    private fun clearRelayHooks() {
        OvpnTunnelService.onReady = null
        OvpnTunnelService.onFailed = null
        OvpnTunnelService.onRelayError = null
    }

    private fun stopOvpn() {
        mainHandler.removeCallbacks(ovpnTimeout)
        clearRelayHooks()
        OvpnTunnelService.stop(this)
        IcsOpenVpn.stop(this)
        persistProto("")
    }

    private fun failOvpn(message: String) {
        stopOvpn()
        activeProto = ""
        status = VpnStatus.Disconnected
        statusDetail = null
        errorMessage = message.take(300)
    }

    private fun onIcsStatus(event: IcsOpenVpn.Status) {
        if (activeProto != "openvpn") return
        when (event) {
            IcsOpenVpn.Status.Connected -> {
                ovpnSawProgress = true
                mainHandler.removeCallbacks(ovpnTimeout)
                status = VpnStatus.Connected
                statusDetail = null
                errorMessage = null
            }
            IcsOpenVpn.Status.Connecting -> {
                ovpnSawProgress = true
                mainHandler.removeCallbacks(ovpnTimeout)
                if (status != VpnStatus.Disconnecting) status = VpnStatus.Connecting
                if (statusDetail.isNullOrBlank()) statusDetail = "Connecting to server"
            }
            IcsOpenVpn.Status.Waiting -> {
                ovpnSawProgress = true
                mainHandler.removeCallbacks(ovpnTimeout)
                status = VpnStatus.Connecting
                statusDetail = "Waiting for network; OpenVPN will retry automatically"
            }
            is IcsOpenVpn.Status.Failed -> failOvpn(event.message)
            IcsOpenVpn.Status.Stopped -> {
                if (status == VpnStatus.Connecting && !ovpnSawProgress) return
                mainHandler.removeCallbacks(ovpnTimeout)
                OvpnTunnelService.stop(this)
                activeProto = ""
                persistProto("")
                status = VpnStatus.Disconnected
                statusDetail = null
            }
        }
    }

    private fun openOvpnStore() {
        ovpnMissing = false
        Toast.makeText(this, "OpenVPN is built into ON Tunnel", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(ovpnTimeout)
        clearRelayHooks()
        IcsOpenVpn.removeObserver()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
