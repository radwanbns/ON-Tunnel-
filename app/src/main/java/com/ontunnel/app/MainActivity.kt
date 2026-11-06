package com.ontunnel.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64

// ─── Colors (light theme like Xray Tunnel screenshot) ───────────────────────
private val Bg = Color(0xFFFFF5F8)
private val CardBg = Color(0xFFF0F0F0)
private val ServerCard = Color(0xFFEDE7F6)
private val BlueBtn = Color(0xFF1976D2)
private val AccentPurple = Color(0xFF7E57C2)
private val TextDark = Color(0xFF212121)
private val TextMuted = Color(0xFF757575)
private val GreenDot = Color(0xFF43A047)
private val RedDelete = Color(0xFFE53935)

/**
 * Hardcoded subscription URL – NEVER shown in the UI.
 *
 * Expected content (GitHub raw or any URL):
 *   - One VLESS / VMess / Trojan link per line
 *   - OR the whole file base64-encoded (standard subscription format)
 *
 * Example raw GitHub:
 *   https://raw.githubusercontent.com/USER/REPO/main/servers.txt
 */
private const val SUBSCRIPTION_URL = "https://example.com/your-subscription-link"

/**
 * Server entry stored as: name | base64(configJson) | originalUri | locked
 * locked=1 → from subscription: show name only (no IP/port), lock icon
 */
private data class ServerEntry(
    val name: String,
    val configB64: String,
    val originalUri: String = "",
    val locked: Boolean = false
) {
    fun serialize(): String = "$name|$configB64|$originalUri|${if (locked) "1" else "0"}"
    companion object {
        fun parse(raw: String): ServerEntry? {
            val parts = raw.split("|", limit = 4)
            if (parts.size < 2 || parts[1].isBlank()) return null
            return ServerEntry(
                name = parts[0].ifBlank { "Server" },
                configB64 = parts[1],
                originalUri = parts.getOrElse(2) { "" },
                locked = parts.getOrElse(3) { "0" } == "1"
            )
        }
    }
}

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
            @Suppress("UnspecifiedRegisterReceiverFlag")
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
    val snackbarHostState = remember { SnackbarHostState() }

    var servers by remember {
        val loaded: List<ServerEntry> =
            (prefs.getStringSet("items", emptySet()) ?: emptySet())
                .mapNotNull { ServerEntry.parse(it) }
        mutableStateOf(loaded)
    }
    var selectedKey by remember { mutableStateOf(prefs.getString("selected", "") ?: "") }
    var isUpdating by remember { mutableStateOf(false) }
    var autoUpdateDone by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addInput by remember { mutableStateOf("") }
    var exportDialogEntry by remember { mutableStateOf<ServerEntry?>(null) }
    var editEntry by remember { mutableStateOf<ServerEntry?>(null) }
    var editInput by remember { mutableStateOf("") }

    fun saveServers(list: List<ServerEntry>) {
        servers = list
        prefs.edit().putStringSet("items", list.map { it.serialize() }.toSet()).apply()
    }

    fun selectServer(entry: ServerEntry) {
        selectedKey = entry.serialize()
        prefs.edit().putString("selected", selectedKey).apply()
    }

    val selectedEntry = servers.firstOrNull { it.serialize() == selectedKey }

    // Auto-update on launch
    LaunchedEffect(Unit) {
        if (!autoUpdateDone && isSubscriptionConfigured()) {
            autoUpdateDone = true
            isUpdating = true
            val result = withContext(Dispatchers.IO) { fetchAndParseSubscription(SUBSCRIPTION_URL) }
            isUpdating = false
            result.onSuccess { newEntries ->
                val oldNames = servers.map { it.name }.toSet()
                val merged = (servers + newEntries).distinctBy { it.name }
                val added = merged.count { it.name !in oldNames }
                if (added > 0) {
                    saveServers(merged)
                    snackbarHostState.showSnackbar("Updated successfully – $added new server(s) added")
                    Toast.makeText(context, "Updated successfully – $added new server(s)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun updateSubscription() {
        if (isUpdating) return
        if (!isSubscriptionConfigured()) {
            scope.launch { snackbarHostState.showSnackbar("Subscription URL not configured in code") }
            return
        }
        scope.launch {
            isUpdating = true
            val result = withContext(Dispatchers.IO) { fetchAndParseSubscription(SUBSCRIPTION_URL) }
            isUpdating = false
            result.onSuccess { newEntries ->
                val oldNames = servers.map { it.name }.toSet()
                val merged = (servers + newEntries).distinctBy { it.name }
                val added = merged.count { it.name !in oldNames }
                saveServers(merged)
                if (added > 0) {
                    snackbarHostState.showSnackbar("Updated successfully – $added new server(s) added")
                    Toast.makeText(context, "Updated successfully – $added new server(s)", Toast.LENGTH_SHORT).show()
                } else {
                    snackbarHostState.showSnackbar("Already up to date (${newEntries.size} servers in subscription)")
                    Toast.makeText(context, "Already up to date", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                snackbarHostState.showSnackbar("Update failed: ${e.message}")
                Toast.makeText(context, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun importFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("Clipboard is empty") }
            return
        }
        // Support multiple links (one per line) or a single link/JSON
        val lines = if (text.startsWith("{")) listOf(text) else text.lines().map { it.trim() }.filter { it.isNotBlank() }
        var added = 0
        val newList = servers.toMutableList()
        for (line in lines) {
            try {
                val parsed = V2RayConfigParser.parse(line)
                val b64 = Base64.encodeToString(parsed.config.toByteArray(), Base64.NO_WRAP)
                val uri = if (line.startsWith("{")) "" else line
                val entry = ServerEntry(parsed.name, b64, uri)
                if (newList.none { it.name == entry.name }) {
                    newList += entry
                    added++
                }
            } catch (_: Exception) { /* skip invalid */ }
        }
        if (added > 0) {
            saveServers(newList)
            if (selectedKey.isBlank() && newList.isNotEmpty()) selectServer(newList.last())
            scope.launch { snackbarHostState.showSnackbar("Imported $added server(s) from clipboard") }
            Toast.makeText(context, "Imported $added server(s)", Toast.LENGTH_SHORT).show()
        } else {
            scope.launch { snackbarHostState.showSnackbar("No valid servers found in clipboard") }
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
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
                            val entry = selectedEntry
                            if (entry == null) {
                                scope.launch { snackbarHostState.showSnackbar("Please select a server first") }
                            } else {
                                try {
                                    onConnect(String(Base64.decode(entry.configB64, Base64.DEFAULT)))
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
                // ── Top bar ───────────────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = TextDark)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Add server manually") },
                                onClick = {
                                    menuExpanded = false
                                    addInput = ""
                                    showAddDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Import from clipboard") },
                                onClick = {
                                    menuExpanded = false
                                    importFromClipboard()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete all servers") },
                                onClick = {
                                    menuExpanded = false
                                    saveServers(emptyList())
                                    selectedKey = ""
                                    prefs.edit().remove("selected").apply()
                                    scope.launch { snackbarHostState.showSnackbar("All servers deleted") }
                                }
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "ON Tunnel",
                        color = TextDark,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // ── Status card ───────────────────────────────────────────
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
                            Icon(Icons.Default.Refresh, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Update Servers", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Server list ───────────────────────────────────────────
                if (servers.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No servers yet.\nTap Update Servers or use the menu ⋮",
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
                        items(servers, key = { it.serialize() }) { entry ->
                            val active = selectedKey == entry.serialize()
                            // Locked (subscription) servers: name only, no IP/port
                            val detail = remember(entry.configB64, entry.locked) {
                                if (entry.locked) ""
                                else try {
                                    extractProtocolInfo(String(Base64.decode(entry.configB64, Base64.DEFAULT)))
                                } catch (_: Exception) { "" }
                            }

                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(ServerCard)
                                    .clickable { selectServer(entry) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            val newList = servers - entry
                                            saveServers(newList)
                                            if (selectedKey == entry.serialize()) {
                                                selectedKey = ""
                                                prefs.edit().remove("selected").apply()
                                            }
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, "Delete", tint = RedDelete, modifier = Modifier.size(20.dp))
                                    }

                                    Spacer(Modifier.width(4.dp))

                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (entry.locked) {
                                                Icon(
                                                    Icons.Default.Lock,
                                                    contentDescription = "Locked",
                                                    tint = TextMuted,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Text(
                                                entry.name,
                                                color = TextDark,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (detail.isNotBlank()) {
                                            Spacer(Modifier.height(2.dp))
                                            Text(detail, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }

                                    // Radio
                                    Box(
                                        Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .border(
                                                2.dp,
                                                if (active) AccentPurple else TextMuted.copy(alpha = 0.4f),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (active) {
                                            Box(Modifier.size(12.dp).clip(CircleShape).background(AccentPurple))
                                        }
                                    }
                                }

                                // Export / Edit – only for manually added (unlocked) servers
                                if (!entry.locked) {
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                editEntry = entry
                                                editInput = entry.originalUri.ifBlank {
                                                    try {
                                                        String(Base64.decode(entry.configB64, Base64.DEFAULT))
                                                    } catch (_: Exception) { "" }
                                                }
                                            },
                                            modifier = Modifier.weight(1f).height(34.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                                brush = androidx.compose.ui.graphics.SolidColor(AccentPurple.copy(alpha = 0.5f))
                                            )
                                        ) {
                                            Icon(Icons.Default.Edit, null, Modifier.size(14.dp), tint = AccentPurple)
                                            Spacer(Modifier.width(4.dp))
                                            Text("Edit", fontSize = 12.sp, color = AccentPurple)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                val uri = entry.originalUri
                                                if (uri.isNotBlank()) {
                                                    copyToClipboard("URI", uri)
                                                } else {
                                                    exportDialogEntry = entry
                                                }
                                            },
                                            modifier = Modifier.weight(1f).height(34.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                                brush = androidx.compose.ui.graphics.SolidColor(AccentPurple.copy(alpha = 0.5f))
                                            )
                                        ) {
                                            Icon(Icons.Default.ContentCopy, null, Modifier.size(14.dp), tint = AccentPurple)
                                            Spacer(Modifier.width(4.dp))
                                            Text("URI", fontSize = 12.sp, color = AccentPurple)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                try {
                                                    val json = String(Base64.decode(entry.configB64, Base64.DEFAULT))
                                                    copyToClipboard("JSON", json)
                                                } catch (_: Exception) {
                                                    Toast.makeText(context, "Failed to export JSON", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.weight(1f).height(34.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                                brush = androidx.compose.ui.graphics.SolidColor(AccentPurple.copy(alpha = 0.5f))
                                            )
                                        ) {
                                            Icon(Icons.Default.ContentCopy, null, Modifier.size(14.dp), tint = AccentPurple)
                                            Spacer(Modifier.width(4.dp))
                                            Text("JSON", fontSize = 12.sp, color = AccentPurple)
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        }

        // ── Add server dialog ─────────────────────────────────────────────
        if (showAddDialog) {
            Dialog(onDismissRequest = { showAddDialog = false }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Add server", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextDark, modifier = Modifier.weight(1f))
                        IconButton(onClick = { showAddDialog = false }) {
                            Icon(Icons.Default.Close, null, tint = TextMuted)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    BasicTextField(
                        value = addInput,
                        onValueChange = { addInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF5F5F5))
                            .padding(12.dp),
                        textStyle = TextStyle(color = TextDark, fontSize = 13.sp),
                        decorationBox = { inner ->
                            if (addInput.isEmpty()) {
                                Text("Paste VLESS / VMess / Trojan / JSON", color = TextMuted, fontSize = 13.sp)
                            }
                            inner()
                        }
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            try {
                                val parsed = V2RayConfigParser.parse(addInput.trim())
                                val b64 = Base64.encodeToString(parsed.config.toByteArray(), Base64.NO_WRAP)
                                val uri = if (addInput.trim().startsWith("{")) "" else addInput.trim()
                                val entry = ServerEntry(parsed.name, b64, uri)
                                val newList = (servers + entry).distinctBy { it.name }
                                saveServers(newList)
                                selectServer(entry)
                                showAddDialog = false
                                Toast.makeText(context, "Server added", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Invalid configuration", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BlueBtn, contentColor = Color.White)
                    ) {
                        Text("Add", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── Edit server dialog ────────────────────────────────────────────
        if (editEntry != null) {
            Dialog(onDismissRequest = { editEntry = null }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Edit server", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextDark, modifier = Modifier.weight(1f))
                        IconButton(onClick = { editEntry = null }) {
                            Icon(Icons.Default.Close, null, tint = TextMuted)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    BasicTextField(
                        value = editInput,
                        onValueChange = { editInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF5F5F5))
                            .padding(12.dp),
                        textStyle = TextStyle(color = TextDark, fontSize = 13.sp),
                        decorationBox = { inner ->
                            if (editInput.isEmpty()) {
                                Text("Paste VLESS / VMess / Trojan / JSON", color = TextMuted, fontSize = 13.sp)
                            }
                            inner()
                        }
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val current = editEntry ?: return@Button
                            try {
                                val parsed = V2RayConfigParser.parse(editInput.trim())
                                val b64 = Base64.encodeToString(parsed.config.toByteArray(), Base64.NO_WRAP)
                                val uri = if (editInput.trim().startsWith("{")) "" else editInput.trim()
                                val updated = ServerEntry(parsed.name, b64, uri, locked = false)
                                val newList = servers.map {
                                    if (it.serialize() == current.serialize()) updated else it
                                }
                                saveServers(newList)
                                if (selectedKey == current.serialize()) {
                                    selectServer(updated)
                                }
                                editEntry = null
                                Toast.makeText(context, "Server updated", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Invalid configuration", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BlueBtn, contentColor = Color.White)
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Export tip when no original URI
        if (exportDialogEntry != null) {
            AlertDialog(
                onDismissRequest = { exportDialogEntry = null },
                title = { Text("Export URI") },
                text = { Text("No original URI was stored for this server (it was imported as JSON only). Use the JSON button instead.") },
                confirmButton = {
                    TextButton(onClick = { exportDialogEntry = null }) { Text("OK") }
                }
            )
        }
    }
}

private fun isSubscriptionConfigured(): Boolean =
    SUBSCRIPTION_URL.isNotBlank() &&
        !SUBSCRIPTION_URL.contains("example.com") &&
        (SUBSCRIPTION_URL.startsWith("http://") || SUBSCRIPTION_URL.startsWith("https://"))

/**
 * Fetch subscription.
 * Supports:
 *  - Plain text: one vless/vmess/trojan link per line
 *  - Base64-encoded list of links (standard subscription format)
 *  - GitHub raw URLs
 */
private fun fetchAndParseSubscription(url: String): Result<List<ServerEntry>> = runCatching {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20000
        readTimeout = 20000
        requestMethod = "GET"
        instanceFollowRedirects = true
        // GitHub raw is friendlier with a browser-like UA
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 ONTunnel/1.1")
        setRequestProperty("Accept", "text/plain, */*")
    }
    val code = conn.responseCode
    if (code !in 200..299) {
        val errBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
        error("HTTP $code${if (!errBody.isNullOrBlank()) ": ${errBody.take(120)}" else ""}")
    }
    val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
    conn.disconnect()

    if (body.isBlank()) error("Subscription is empty")

    // Try base64 decode; if result looks like links, use it
    val content = tryDecodeSubscription(body)

    val lines = content.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
    val entries = mutableListOf<ServerEntry>()
    val errors = mutableListOf<String>()
    for (line in lines) {
        try {
            val parsed = V2RayConfigParser.parse(line)
            val b64 = Base64.encodeToString(parsed.config.toByteArray(), Base64.NO_WRAP)
            val uri = if (line.startsWith("{")) "" else line
            entries += ServerEntry(parsed.name, b64, originalUri = "", locked = true)
        } catch (e: Exception) {
            errors += (e.message ?: "parse error")
        }
    }
    if (entries.isEmpty()) {
        val hint = when {
            lines.isEmpty() -> "No lines found. File may be empty or wrong encoding."
            else -> "Parsed 0/${lines.size} lines. First error: ${errors.firstOrNull() ?: "unknown"}"
        }
        error(hint)
    }
    entries
}

/** If body is base64 of a link list, decode it; otherwise return as-is. */
private fun tryDecodeSubscription(body: String): String {
    // Already looks like links
    if (body.contains("://") || body.trimStart().startsWith("{")) return body

    // Try standard base64
    val attempts = listOf(
        Base64.DEFAULT,
        Base64.URL_SAFE or Base64.NO_WRAP,
        Base64.DEFAULT or Base64.NO_WRAP
    )
    for (flag in attempts) {
        try {
            val decoded = String(Base64.decode(body, flag), Charsets.UTF_8).trim()
            if (decoded.contains("://") || decoded.startsWith("{")) return decoded
        } catch (_: Exception) { }
    }
    return body
}

private fun extractProtocolInfo(json: String): String {
    return try {
        val obj = org.json.JSONObject(json)
        val outbounds = obj.optJSONArray("outbounds") ?: return ""
        val proxy = (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull {
                it.optString("tag") == "proxy" ||
                    it.optString("protocol") in listOf("vless", "vmess", "trojan")
            } ?: return ""
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
            else -> { address = ""; port = 0 }
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
    } catch (_: Exception) { "" }
}
