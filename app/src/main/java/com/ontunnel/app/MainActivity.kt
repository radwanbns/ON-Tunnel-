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

/** OpenVPN: how many times a failed / stuck attempt is restarted automatically before giving up. */
private const val OVPN_MAX_AUTO_RETRIES = 8
/** OpenVPN: an attempt that is still not CONNECTED after this long is restarted. */
private const val OVPN_STUCK_MS = 30_000L

class MainActivity : ComponentActivity() {
    private var pendingConfig: String? = null
    private var status by mutableStateOf(if (ONTunnelVpnService.running) VpnStatus.Connected else VpnStatus.Disconnected)
    private var errorMessage by mutableStateOf<String?>(null)
    private var availableAppUpdate by mutableStateOf<AppUpdateInfo?>(null)
    private var updateDownloadId: Long = -1L

    // ── OpenVPN (through the "OpenVPN for Android" remote API) ──────────
    private var statusDetail by mutableStateOf<String?>(null)
    private var ovpnMissing by mutableStateOf(false)
    private var pendingOvpn: OvpnProfile? = null
    private var ovpnSawProgress = false
    /** "" | "v2ray" | "openvpn" – which engine currently owns the tunnel. */
    private var activeProto = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ovpn = OpenVpnController(this) { state, message, level -> onOvpnStatus(state, message, level) }
    /** True from "Connect" until the user (or a fatal error) stops the OpenVPN tunnel. Drives auto-reconnect. */
    private var ovpnWanted = false
    private var ovpnAutoCount = 0
    private var ovpnWatchArmed = false
    /** Fires when OpenVPN has been connecting / retrying for too long without reaching CONNECTED. */
    private val ovpnStuckWatch = Runnable {
        ovpnWatchArmed = false
        if (ovpnWanted && activeProto == "openvpn" && status != VpnStatus.Connected) {
            autoReconnectOvpn("Connection did not complete in time")
        }
    }
    private val ovpnTimeout = Runnable {
        if (status == VpnStatus.Connecting && !ovpnSawProgress) {
            failOvpn("OpenVPN for Android did not respond. Open it once, then try again.")
        }
    }

    private val ovpnApiPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) proceedOvpnVpnPermission()
        else failOvpn("Permission to control OpenVPN for Android was denied")
    }

    private val ovpnVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startOvpnProfile()
        else failOvpn("VPN permission was denied")
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
                "connected" -> { AppLogs.add(this@MainActivity, "V2Ray tunnel connected"); status = VpnStatus.Connected; errorMessage = null }
                "disconnected" -> { AppLogs.add(this@MainActivity, "V2Ray tunnel disconnected"); status = VpnStatus.Disconnected; if (activeProto == "v2ray") activeProto = "" }
                "error" -> {
                    val detail = intent.getStringExtra(ONTunnelVpnService.EXTRA_ERROR).orEmpty().take(300)
                    AppLogs.add(this@MainActivity, "V2Ray error: $detail")
                    status = VpnStatus.Disconnected
                    errorMessage = detail
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
            // The tunnel may still be up in OpenVPN for Android: re-attach to get its current state.
            if (OpenVpnController.isInstalled(this)) {
                ovpn.bind { err ->
                    if (err == null) {
                        try { if (ovpn.prepareApi() == null) ovpn.register() } catch (_: Exception) { }
                    }
                }
            } else {
                activeProto = ""
                persistProto("")
            }
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
        AppLogs.add(this, "User requested VPN disconnect")
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
        AppLogs.add(this, "OpenVPN connection requested: ${profile.name}")
        profile.validate()?.let { AppLogs.add(this, "OpenVPN profile error: $it"); errorMessage = it; return }
        if (!OpenVpnController.isInstalled(this)) { ovpnMissing = true; return }
        if (activeProto == "v2ray") {
            startService(Intent(this, ONTunnelVpnService::class.java).setAction(ONTunnelVpnService.ACTION_STOP))
        } else if (activeProto == "openvpn") {
            stopOvpn()
        }
        activeProto = "openvpn"
        persistProto("openvpn")
        pendingOvpn = profile
        ovpnSawProgress = false
        ovpnWanted = true
        ovpnAutoCount = 0
        ovpnWatchArmed = false
        mainHandler.removeCallbacks(ovpnStuckWatch)
        status = VpnStatus.Connecting
        statusDetail = "Contacting OpenVPN for Android"
        mainHandler.removeCallbacks(ovpnTimeout)
        mainHandler.postDelayed(ovpnTimeout, 60_000L)
        ovpn.bind { err ->
            if (err != null) failOvpn(err) else proceedOvpnApiPermission()
        }
    }

    private fun proceedOvpnApiPermission() {
        val intent = try { ovpn.prepareApi() } catch (e: Exception) {
            failOvpn("OpenVPN for Android refused access: ${e.message}")
            return
        }
        if (intent != null) ovpnApiPermission.launch(intent) else proceedOvpnVpnPermission()
    }

    private fun proceedOvpnVpnPermission() {
        ovpn.register()
        val intent = try { ovpn.prepareVpn() } catch (e: Exception) {
            failOvpn("OpenVPN for Android refused access: ${e.message}")
            return
        }
        if (intent != null) ovpnVpnPermission.launch(intent) else startOvpnProfile()
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
        OvpnTunnelService.onFailed = { msg ->
            if (msg.startsWith("App integrity") || msg.startsWith("Invalid") || msg.contains("is missing")) {
                failOvpn(msg)
            } else {
                errorMessage = msg.take(300)
                autoReconnectOvpn(msg)
            }
        }
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
            ovpn.start(config)
        } catch (e: Exception) {
            failOvpn("OpenVPN for Android error: ${e.message}")
        }
    }

    private fun clearRelayHooks() {
        OvpnTunnelService.onReady = null
        OvpnTunnelService.onFailed = null
        OvpnTunnelService.onRelayError = null
    }

    private fun stopOvpn() {
        ovpnWanted = false
        ovpnWatchArmed = false
        mainHandler.removeCallbacks(ovpnStuckWatch)
        mainHandler.removeCallbacks(ovpnTimeout)
        clearRelayHooks()
        OvpnTunnelService.stop(this)
        ovpn.bind { err -> if (err == null) ovpn.disconnect() }
        persistProto("")
    }

    private fun failOvpn(message: String) {
        AppLogs.add(this, "OpenVPN failed: ${message.take(300)}")
        stopOvpn()
        activeProto = ""
        status = VpnStatus.Disconnected
        statusDetail = null
        errorMessage = message.take(300)
    }

    private fun onOvpnStatus(state: String, message: String, level: String) {
        if (activeProto != "openvpn") return
        if (state.isNotBlank()) AppLogs.add(this, "OpenVPN [$level] $state: ${message.take(180)}")
        when {
            state == "CONNECTED" -> {
                ovpnSawProgress = true
                ovpnAutoCount = 0
                ovpnWatchArmed = false
                mainHandler.removeCallbacks(ovpnStuckWatch)
                mainHandler.removeCallbacks(ovpnTimeout)
                status = VpnStatus.Connected
                statusDetail = null
                errorMessage = null
            }
            state == "AUTH_FAILED" || level == "LEVEL_AUTH_FAILED" -> {
                failOvpn("Authentication failed – check the username and password")
            }
            state == "NOPROCESS" || state == "EXITING" -> {
                // OpenVPN reports NOPROCESS right after we register, before our profile starts: ignore that one.
                if (status == VpnStatus.Connecting && !ovpnSawProgress) return
                // The engine quit while we were still trying to connect (not after a user disconnect): try again.
                if (ovpnWanted && status == VpnStatus.Connecting) {
                    autoReconnectOvpn("OpenVPN stopped before the tunnel was up")
                    return
                }
                mainHandler.removeCallbacks(ovpnTimeout)
                OvpnTunnelService.stop(this)
                activeProto = ""
                persistProto("")
                status = VpnStatus.Disconnected
                statusDetail = null
            }
            else -> {
                ovpnSawProgress = true
                mainHandler.removeCallbacks(ovpnTimeout)
                if (status != VpnStatus.Disconnecting) status = VpnStatus.Connecting
                // Not connected yet: make sure a stuck attempt is restarted automatically.
                if (ovpnWanted && !ovpnWatchArmed) {
                    ovpnWatchArmed = true
                    mainHandler.postDelayed(ovpnStuckWatch, OVPN_STUCK_MS)
                }
                statusDetail = when (state) {
                    "CONNECTING", "TCP_CONNECT" -> "Connecting to server"
                    "WAIT" -> "Waiting for server"
                    "RESOLVE" -> "Resolving server address"
                    // The engine's own message is often just a counter such as "-2": hide plain numbers.
                    "CONNECTRETRY" -> "Retrying connection" +
                        (message.takeIf { it.isNotBlank() && !it.matches(Regex("-?\\d+")) }?.let { " – ${it.take(60)}" } ?: "")
                    "AUTH" -> "Authenticating"
                    "GET_CONFIG" -> "Getting configuration"
                    "ASSIGN_IP" -> "Assigning IP address"
                    "ADD_ROUTES" -> "Adding routes"
                    "RECONNECTING" -> "Reconnecting"
                    "NONETWORK" -> "Waiting for network"
                    else -> state.lowercase().replaceFirstChar { it.uppercase() }
                }
            }
        }
    }

    /**
     * Restarts the whole OpenVPN attempt (engine + local relay) without user interaction. Used when the
     * relay cannot start, OpenVPN quits while connecting, or an attempt is stuck in CONNECTRETRY / WAIT.
     */
    private fun autoReconnectOvpn(reason: String) {
        val profile = pendingOvpn
        if (!ovpnWanted || profile == null || activeProto != "openvpn") return
        mainHandler.removeCallbacks(ovpnStuckWatch)
        mainHandler.removeCallbacks(ovpnTimeout)
        ovpnWatchArmed = false
        if (ovpnAutoCount >= OVPN_MAX_AUTO_RETRIES) {
            failOvpn("Could not connect after $OVPN_MAX_AUTO_RETRIES automatic attempts. ${errorMessage ?: reason}")
            return
        }
        ovpnAutoCount++
        ovpnSawProgress = false   // also makes the engine's NOPROCESS/EXITING from this teardown be ignored
        status = VpnStatus.Connecting
        statusDetail = "Reconnecting automatically ($ovpnAutoCount/$OVPN_MAX_AUTO_RETRIES)"
        AppLogs.add(this, "OpenVPN automatic retry $ovpnAutoCount/$OVPN_MAX_AUTO_RETRIES: ${reason.take(180)}")
        clearRelayHooks()
        // The relay service is deliberately NOT stopped: it is already a foreground service, and Android 12+
        // refuses to start a new one from the background. startOvpnProfile() re-sends ACTION_START and the
        // running service just replaces its proxy.
        ovpn.bind { err -> if (err == null) ovpn.disconnect() }
        mainHandler.postDelayed({
            if (ovpnWanted && activeProto == "openvpn") {
                mainHandler.postDelayed(ovpnTimeout, 60_000L)
                startOvpnProfile()
            }
        }, 2500L)
    }

    private fun openOvpnStore() {
        ovpnMissing = false
        val uris = listOf("market://details?id=${OpenVpnController.PKG}", "https://play.google.com/store/apps/details?id=${OpenVpnController.PKG}")
        for (u in uris) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) { }
        }
        Toast.makeText(this, "Install \"OpenVPN for Android\" from Google Play or F-Droid", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(ovpnTimeout)
        clearRelayHooks()
        ovpn.release()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
