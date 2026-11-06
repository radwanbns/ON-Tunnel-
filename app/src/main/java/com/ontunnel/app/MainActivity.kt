package com.ontunnel.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64

// ─── Colors matching the Xray Tunnel screenshot (light theme) ───────────────
private val Bg = Color(0xFFFFF5F8)           // soft pinkish white
private val CardBg = Color(0xFFF0F0F0)       // status card gray
private val ServerCard = Color(0xFFEDE7F6)   // soft purple server card
private val BlueBtn = Color(0xFF1976D2)      // update button blue
private val AccentPurple = Color(0xFF7E57C2)
private val TextDark = Color(0xFF212121)
private val TextMuted = Color(0xFF757575)
private val GreenDot = Color(0xFF43A047)
private val RedDelete = Color(0xFFE53935)

/**
 * Hardcoded subscription URL – NEVER shown in the UI.
 * Replace this with your real subscription link.
 */
private const val SUBSCRIPTION_URL = "https://github.com/radwanbns/ON-Tunnel-/blob/main/servers.txt"

class MainActivity : ComponentActivity() {
    private var pendingConfig: String? = null
    private var stateText by mutableStateOf("Disconnected")
    private var isConnected by mutableStateOf(false)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) pendingConfig?.let(::startVpn)
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(ONTunnelVpnService.EXTRA_STATE)) {
                "connected" -> { stateText = "Connected"; isConnected = true }
                "disconnected" -> { stateText = "Disconnected"; isConnected = false }
                "error" -> {
                    stateText = "Error: " + intent.getStringExtra(ONTunnelVpnService.EXTRA_ERROR).orEmpty()
                    isConnected = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(ONTunnelVpnService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        setContent {
            ONTunnelScreen(
                state = stateText,
                connected = isConnected,
                onConnect = ::connect,
                onDisconnect = ::disconnect
            )
        }
    }

    private fun connect(config: String) {
        pendingConfig = config
        val permission = VpnService.prepare(this)
        if (permission != null) vpnPermission.launch(permission) else startVpn(config)
    }

    private fun startVpn(config: String) {
        val intent = Intent(this, ONTunnelVpnService::class.java)
            .setAction(ONTunnelVpnService.ACTION_START)
            .putExtra(ONTunnelVpnService.EXTRA_CONFIG, config)
        ContextCompat.startForegroundService(this, intent)
        stateText = "Connecting..."
    }

    private fun disconnect() {
        startService(Intent(this, ONTunnelVpnService::class.java).setAction(ONTunnelVpnService.ACTION_STOP))
        stateText = "Disconnecting..."
    }

    override fun onDestroy() {
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}

@Composable
private fun ONTunnelScreen(
    state: String,
    connected: Boolean,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("on_tunnel_servers", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var servers by remember {
        mutableStateOf(prefs.getStringSet("items", emptySet())?.toList() ?: emptyList())
    }
    var selected by remember { mutableStateOf(prefs.getString("selected", "") ?: "") }
    var isUpdating by remember { mutableStateOf(false) }
    var autoUpdateDone by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Silent auto-update on launch
    LaunchedEffect(Unit) {
        if (!autoUpdateDone && SUBSCRIPTION_URL.isNotBlank() && !SUBSCRIPTION_URL.contains("example.com")) {
            autoUpdateDone = true
            isUpdating = true
            val result = withContext(Dispatchers.IO) { fetchAndParseSubscription(SUBSCRIPTION_URL) }
            isUpdating = false
            result.onSuccess { newEntries ->
                val oldCount = servers.size
                val merged = (servers + newEntries).distinctBy { it.substringBefore("|") }
                val added = merged.size - oldCount
                if (added > 0) {
                    servers = merged
                    prefs.edit().putStringSet("items", merged.toSet()).apply()
                    snackbarHostState.showSnackbar("Updated successfully – $added new server(s) added")
                    Toast.makeText(context, "Updated successfully – $added new server(s)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun updateSubscription() {
        if (isUpdating) return
        if (SUBSCRIPTION_URL.isBlank() || SUBSCRIPTION_URL.contains("example.com")) {
            scope.launch { snackbarHostState.showSnackbar("Subscription URL not configured") }
            return
        }
        scope.launch {
            isUpdating = true
            val result = withContext(Dispatchers.IO) { fetchAndParseSubscription(SUBSCRIPTION_URL) }
            isUpdating = false
            result.onSuccess { newEntries ->
                val oldCount = servers.size
                val merged = (servers + newEntries).distinctBy { it.substringBefore("|") }
                val added = merged.size - oldCount
                servers = merged
                prefs.edit().putStringSet("items", merged.toSet()).apply()
                if (added > 0) {
                    snackbarHostState.showSnackbar("Updated successfully – $added new server(s) added")
                    Toast.makeText(context, "Updated successfully – $added new server(s)", Toast.LENGTH_SHORT).show()
                } else {
                    snackbarHostState.showSnackbar("Already up to date")
                    Toast.makeText(context, "Already up to date", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                snackbarHostState.showSnackbar("Update failed: ${e.message}")
            }
        }
    }

    MaterialTheme {
        Scaffold(
            containerColor = Bg,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (connected) {
                            onDisconnect()
                        } else {
                            val encoded = selected.substringAfter("|", "")
                            if (encoded.isBlank()) {
                                scope.launch { snackbarHostState.showSnackbar("Please select a server first") }
                            } else {
                                try {
                                    onConnect(String(Base64.decode(encoded, Base64.DEFAULT)))
                                } catch (_: Exception) {
                                    scope.launch { snackbarHostState.showSnackbar("Failed to read server config") }
                                }
                            }
                        }
                    },
                    containerColor = if (connected) Color(0xFFE53935) else Color(0xFFE1BEE7),
                    contentColor = if (connected) Color.White else TextDark,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(
                        if (connected) "Disconnect" else "Connect",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (connected) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                }
            },
            floatingActionButtonPosition = FabPosition.Start
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                // ── Top App Bar ───────────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { /* menu placeholder */ }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = TextDark)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "ON Tunnel",
                        color = TextDark,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // ── Status Card ───────────────────────────────────────────
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardBg)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        when {
                            connected -> "Connected"
                            state.contains("Connecting") -> "Connecting..."
                            state.contains("Disconnecting") -> "Disconnecting..."
                            state.startsWith("Error") -> state
                            else -> "Disconnected"
                        },
                        color = if (connected) GreenDot else TextDark,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(16.dp))

                    // Update Servers button (same position as screenshot)
                    Button(
                        onClick = { updateSubscription() },
                        enabled = !isUpdating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BlueBtn,
                            contentColor = Color.White,
                            disabledContainerColor = BlueBtn.copy(alpha = 0.6f)
                        )
                    ) {
                        if (isUpdating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Updating...", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Update Servers", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Server list ───────────────────────────────────────────
                if (servers.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No servers yet.\nTap Update Servers to fetch them.",
                            color = TextMuted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(servers, key = { it }) { item ->
                            val name = item.substringBefore("|")
                            val active = selected == item
                            val detail = remember(item) {
                                try {
                                    val cfg = String(Base64.decode(item.substringAfter("|"), Base64.DEFAULT))
                                    extractProtocolInfo(cfg)
                                } catch (_: Exception) {
                                    ""
                                }
                            }

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(ServerCard)
                                    .clickable {
                                        selected = item
                                        prefs.edit().putString("selected", item).apply()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Delete button
                                IconButton(
                                    onClick = {
                                        servers = servers - item
                                        prefs.edit().putStringSet("items", servers.toSet()).apply()
                                        if (selected == item) {
                                            selected = ""
                                            prefs.edit().remove("selected").apply()
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = RedDelete,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(Modifier.width(4.dp))

                                // Name + details
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        name,
                                        color = TextDark,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (detail.isNotBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            detail,
                                            color = TextMuted,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Radio indicator
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .border(
                                            width = 2.dp,
                                            color = if (active) AccentPurple else TextMuted.copy(alpha = 0.4f),
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (active) {
                                        Box(
                                            Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(AccentPurple)
                                        )
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(80.dp)) } // space for FAB
                    }
                }
            }
        }
    }
}

/** Extract a short protocol summary from the Xray JSON config. */
private fun extractProtocolInfo(json: String): String {
    return try {
        val obj = org.json.JSONObject(json)
        val outbounds = obj.optJSONArray("outbounds") ?: return ""
        val proxy = (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { it.optString("tag") == "proxy" || it.optString("protocol") in listOf("vless", "vmess", "trojan") }
            ?: return ""
        val protocol = proxy.optString("protocol").uppercase()
        val stream = proxy.optJSONObject("streamSettings")
        val network = stream?.optString("network") ?: "tcp"
        val security = stream?.optString("security") ?: "none"
        val address: String
        val port: Int
        when (protocol.lowercase()) {
            "vless", "vmess" -> {
                val vnext = proxy.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)
                address = vnext?.optString("address") ?: ""
                port = vnext?.optInt("port") ?: 0
            }
            "trojan" -> {
                val server = proxy.optJSONObject("settings")?.optJSONArray("servers")?.optJSONObject(0)
                address = server?.optString("address") ?: ""
                port = server?.optInt("port") ?: 0
            }
            else -> {
                address = ""
                port = 0
            }
        }
        buildString {
            append(protocol)
            append(" • ")
            append(network)
            if (security != "none") {
                append(" • ")
                append(security)
            }
            if (address.isNotBlank()) {
                append(" • ")
                append(address)
                if (port > 0) append(":$port")
            }
        }
    } catch (_: Exception) {
        ""
    }
}

/**
 * Fetches the subscription URL, decodes base64 if needed, splits into lines,
 * and parses each valid V2Ray link.
 */
private fun fetchAndParseSubscription(url: String): Result<List<String>> = runCatching {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 15000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "ONTunnel/1.1")
    }
    val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
    conn.disconnect()

    val decoded = try {
        String(Base64.decode(body, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (_: Exception) {
        body
    }

    val lines = decoded.lines().map { it.trim() }.filter { it.isNotBlank() }
    val entries = mutableListOf<String>()
    for (line in lines) {
        try {
            val parsed = V2RayConfigParser.parse(line)
            val encoded = Base64.encodeToString(parsed.config.toByteArray(), Base64.NO_WRAP)
            entries += parsed.name + "|" + encoded
        } catch (_: Exception) {
            // skip invalid lines
        }
    }
    if (entries.isEmpty()) error("No valid servers found in subscription")
    entries
}
