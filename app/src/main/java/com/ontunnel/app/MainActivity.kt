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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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

// ─── Colors (v2rayNG-inspired dark theme) ───────────────────────────────────
private val AppBg = Color(0xFF0D1117)
private val Panel = Color(0xFF161B22)
private val PanelSelected = Color(0xFF1F6FEB)
private val Accent = Color(0xFF58A6FF)
private val Green = Color(0xFF3FB950)
private val MainText = Color(0xFFE6EDF3)
private val Muted = Color(0xFF8B949E)
private val Red = Color(0xFFF85149)
private val Border = Color(0xFF30363D)

/**
 * Hardcoded subscription URL – NEVER shown in the UI.
 * Replace this with your real subscription link.
 * The app fetches it silently on launch and via the refresh button.
 */
private const val SUBSCRIPTION_URL = "https://example.com/your-subscription-link"

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
                "connected" -> {
                    stateText = "Connected"
                    isConnected = true
                }
                "disconnected" -> {
                    stateText = "Disconnected"
                    isConnected = false
                }
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

    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var messageIsSuccess by remember { mutableStateOf(false) }
    var servers by remember {
        mutableStateOf(prefs.getStringSet("items", emptySet())?.toList() ?: emptyList())
    }
    var selected by remember { mutableStateOf(prefs.getString("selected", "") ?: "") }
    var isUpdating by remember { mutableStateOf(false) }
    var autoUpdateDone by remember { mutableStateOf(false) }

    val selectedName = selected.substringBefore("|").ifBlank { "No server selected" }
    val statusColor = when {
        connected -> Green
        state.contains("Connecting") || state.contains("Disconnecting") -> Accent
        else -> Muted
    }

    // Silent auto-update on first composition (app launch)
    LaunchedEffect(Unit) {
        if (!autoUpdateDone && SUBSCRIPTION_URL.isNotBlank() && !SUBSCRIPTION_URL.contains("example.com")) {
            autoUpdateDone = true
            isUpdating = true
            val result = withContext(Dispatchers.IO) {
                fetchAndParseSubscription(SUBSCRIPTION_URL)
            }
            isUpdating = false
            result.onSuccess { newEntries ->
                val oldCount = servers.size
                val merged = (servers + newEntries).distinctBy { it.substringBefore("|") }
                val added = merged.size - oldCount
                if (added > 0) {
                    servers = merged
                    prefs.edit().putStringSet("items", merged.toSet()).apply()
                    message = "Updated successfully – $added new server(s) added"
                    messageIsSuccess = true
                    Toast.makeText(context, "Updated successfully – $added new server(s)", Toast.LENGTH_SHORT).show()
                }
            }
            // Failures on auto-update are silent (no message)
        }
    }

    fun updateSubscription(showToast: Boolean = true) {
        if (isUpdating) return
        if (SUBSCRIPTION_URL.isBlank() || SUBSCRIPTION_URL.contains("example.com")) {
            message = "Subscription URL not configured"
            messageIsSuccess = false
            return
        }
        scope.launch {
            isUpdating = true
            message = ""
            val result = withContext(Dispatchers.IO) {
                fetchAndParseSubscription(SUBSCRIPTION_URL)
            }
            isUpdating = false
            result.onSuccess { newEntries ->
                val oldCount = servers.size
                val merged = (servers + newEntries).distinctBy { it.substringBefore("|") }
                val added = merged.size - oldCount
                servers = merged
                prefs.edit().putStringSet("items", merged.toSet()).apply()
                if (added > 0) {
                    message = "Updated successfully – $added new server(s) added"
                    messageIsSuccess = true
                    if (showToast) {
                        Toast.makeText(context, "Updated successfully – $added new server(s)", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    message = "Already up to date"
                    messageIsSuccess = true
                    if (showToast) {
                        Toast.makeText(context, "Already up to date", Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure { e ->
                message = "Update failed: ${e.message}"
                messageIsSuccess = false
            }
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = AppBg) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Header ────────────────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "ON Tunnel",
                            color = MainText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "V2Ray / Xray Client",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                    // Refresh subscription button (URL is never shown)
                    IconButton(
                        onClick = { updateSubscription() },
                        enabled = !isUpdating
                    ) {
                        if (isUpdating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Accent,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Update servers",
                                tint = Accent
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Connection panel ──────────────────────────────────────
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Panel)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("CONNECTION STATUS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            connected -> "CONNECTED"
                            state.contains("Connecting") -> "CONNECTING..."
                            state.contains("Disconnecting") -> "DISCONNECTING..."
                            else -> "DISCONNECTED"
                        },
                        color = statusColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(20.dp))

                    // Big circular connect / stop button (v2rayNG style)
                    Box(
                        Modifier
                            .size(150.dp)
                            .clip(CircleShape)
                            .background(if (connected) Color(0xFF0D2A1F) else Color(0xFF0D1B2A))
                            .clickable {
                                if (connected) {
                                    onDisconnect()
                                } else {
                                    val encoded = selected.substringAfter("|", "")
                                    if (encoded.isBlank()) {
                                        message = "Please add and select a server first"
                                        messageIsSuccess = false
                                    } else {
                                        try {
                                            onConnect(String(Base64.decode(encoded, Base64.DEFAULT)))
                                            message = ""
                                        } catch (_: Exception) {
                                            message = "Failed to read server config"
                                            messageIsSuccess = false
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .size(120.dp)
                                .clip(CircleShape)
                                .background(if (connected) Green else Accent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (connected) "STOP" else "CONNECT",
                                color = Color(0xFF0D1117),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (connected) "Tap to disconnect" else "Tap to connect to selected server",
                        color = Muted,
                        fontSize = 12.sp
                    )

                    if (state.startsWith("Error")) {
                        Spacer(Modifier.height(8.dp))
                        Text(state, color = Red, fontSize = 12.sp)
                    }
                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            message,
                            color = if (messageIsSuccess) Green else Red,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── Current server card ───────────────────────────────────
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel)
                        .padding(14.dp)
                ) {
                    Text("CURRENT SERVER", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (connected) Green else Muted)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            selectedName,
                            color = MainText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${servers.size} servers",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                // ── Configurations header ─────────────────────────────────
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "CONFIGURATIONS",
                        color = MainText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${servers.size} items", color = Muted, fontSize = 12.sp)
                }

                Spacer(Modifier.height(10.dp))

                // ── Add config panel ──────────────────────────────────────
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel)
                        .padding(14.dp)
                ) {
                    Text("Add configuration", color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it; message = "" },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppBg)
                            .padding(12.dp),
                        textStyle = TextStyle(color = MainText, fontSize = 13.sp),
                        decorationBox = { inner ->
                            if (input.isEmpty()) {
                                Text(
                                    "Paste VLESS / VMess / Trojan / JSON",
                                    color = Muted,
                                    fontSize = 12.sp
                                )
                            }
                            inner()
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            try {
                                val parsed = V2RayConfigParser.parse(input)
                                val encoded = Base64.encodeToString(
                                    parsed.config.toByteArray(),
                                    Base64.NO_WRAP
                                )
                                val entry = parsed.name + "|" + encoded
                                servers = (servers + entry).distinct()
                                prefs.edit().putStringSet("items", servers.toSet()).apply()
                                selected = entry
                                prefs.edit().putString("selected", entry).apply()
                                input = ""
                                message = "Server added successfully"
                                messageIsSuccess = true
                            } catch (e: Exception) {
                                message = e.message ?: "Invalid configuration"
                                messageIsSuccess = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color(0xFF0D1117)
                        )
                    ) {
                        Text("+  Add Server", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── Server list ───────────────────────────────────────────
                servers.forEach { item ->
                    val name = item.substringBefore("|")
                    val active = selected == item
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) PanelSelected.copy(alpha = 0.25f) else Panel)
                            .clickable {
                                selected = item
                                prefs.edit().putString("selected", item).apply()
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (active) Accent else Muted)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                name,
                                color = MainText,
                                fontSize = 14.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (active) "SELECTED" else "Tap to select",
                                color = if (active) Accent else Muted,
                                fontSize = 10.sp
                            )
                        }
                        IconButton(
                            onClick = {
                                servers = servers - item
                                prefs.edit().putStringSet("items", servers.toSet()).apply()
                                if (selected == item) {
                                    selected = ""
                                    prefs.edit().remove("selected").apply()
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = Red,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "ON Tunnel  •  VLESS  •  VMess  •  Trojan",
                    color = Muted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/**
 * Fetches the subscription URL, decodes base64 if needed, splits into lines,
 * and parses each valid V2Ray link into the same "name|base64config" format
 * used by the local server list.
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

    // Subscription responses are usually base64-encoded lists of links
    val decoded = try {
        String(Base64.decode(body, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (_: Exception) {
        body // already plain text
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
