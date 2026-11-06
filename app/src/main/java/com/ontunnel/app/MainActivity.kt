package com.ontunnel.app

import android.Manifest
import android.app.Activity
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

/**
 * Hardcoded subscription URL – NEVER shown in the UI.
 * Content: one VLESS / VMess / Trojan link per line, or the whole file base64-encoded.
 */
// Real values are XOR-encrypted in SecureStrings.kt – never stored as plain strings.
private val SUBSCRIPTION_URL: String get() = SecureStrings.subscriptionUrl
private val TELEGRAM_URL: String get() = SecureStrings.telegramUrl

internal enum class VpnStatus { Disconnected, Connecting, Connected, Disconnecting }

class MainActivity : ComponentActivity() {
    private var pendingConfig: String? = null
    private var status by mutableStateOf(if (ONTunnelVpnService.running) VpnStatus.Connected else VpnStatus.Disconnected)
    private var errorMessage by mutableStateOf<String?>(null)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) pendingConfig?.let(::startVpn)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(ONTunnelVpnService.EXTRA_STATE)) {
                "connected" -> { status = VpnStatus.Connected; errorMessage = null }
                "disconnected" -> status = VpnStatus.Disconnected
                "error" -> {
                    status = VpnStatus.Disconnected
                    errorMessage = intent.getStringExtra(ONTunnelVpnService.EXTRA_ERROR).orEmpty().take(300)
                }
            }
        }
    }

    private var tamperDetected by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tamperDetected = IntegrityGuard.status(this) == IntegrityGuard.Status.MISMATCH
        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(ONTunnelVpnService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            ONTunnelApp(
                status = status,
                error = errorMessage,
                tampered = tamperDetected,
                onDismissError = { errorMessage = null },
                onConnect = ::connect,
                onDisconnect = ::disconnect
            )
        }
    }

    private fun connect(config: String) {
        errorMessage = null
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
        startService(Intent(this, ONTunnelVpnService::class.java).setAction(ONTunnelVpnService.ACTION_STOP))
        status = VpnStatus.Disconnecting
    }

    override fun onDestroy() {
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}

private fun openTelegram(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, TELEGRAM_URL, Toast.LENGTH_LONG).show()
    }
}

private class EditorTarget(val entry: ServerEntry?, val profile: ServerProfile)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ONTunnelApp(
    status: VpnStatus,
    error: String?,
    tampered: Boolean,
    onDismissError: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { ServerStore(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var servers by remember { mutableStateOf(store.load()) }
    var selectedId by remember { mutableStateOf(store.selectedId) }
    var isUpdating by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // Shown on EVERY app launch (rememberSaveable only survives rotation, not a new launch)
    var showTelegram by rememberSaveable { mutableStateOf(true) }
    var showPaste by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditorTarget?>(null) }
    var jsonEditing by remember { mutableStateOf<ServerEntry?>(null) }

    var themeMode by remember { mutableStateOf(store.themeMode) }
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) { "light" -> false; "dark" -> true; else -> systemDark }
    Pal.dark = dark
    SideEffect {
        (context as? Activity)?.window?.let { w ->
            val c = WindowCompat.getInsetsController(w, w.decorView)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }

    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }

    fun saveServers(list: List<ServerEntry>) {
        servers = list
        store.save(list)
    }

    fun select(id: String) {
        selectedId = id
        store.selectedId = id
    }

    fun copyText(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun runUpdate(auto: Boolean) {
        if (isUpdating) return
        if (!isSubscriptionConfigured()) {
            if (!auto) message("Subscription URL not configured")
            return
        }
        scope.launch {
            isUpdating = true
            val result = withContext(Dispatchers.IO) { fetchAndParseSubscription(SUBSCRIPTION_URL) }
            isUpdating = false
            result.onSuccess { fresh ->
                val oldLocked = servers.filter { it.locked }.associateBy { it.name }
                val manual = servers.filter { !it.locked }
                val newLocked = fresh.distinctBy { it.name }.map { e -> oldLocked[e.name]?.let { e.copy(id = it.id) } ?: e }
                val added = newLocked.count { it.name !in oldLocked }
                val merged = newLocked + manual
                saveServers(merged)
                if (merged.none { it.id == selectedId }) select(merged.firstOrNull()?.id.orEmpty())
                if (added > 0) message("Updated successfully – $added new server(s) added")
                else if (!auto) message("Already up to date (${newLocked.size} servers)")
            }.onFailure { e ->
                if (!auto) message("Update failed: ${e.message}")
            }
        }
    }

    fun addParsed(parsed: V2RayConfigParser.Parsed): ServerEntry {
        val entry = ServerEntry(name = parsed.name, config = parsed.config, uri = parsed.uri)
        saveServers(servers + entry)
        select(entry.id)
        return entry
    }

    fun importFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isBlank()) { message("Clipboard is empty"); return }
        val lines = if (text.startsWith("{")) listOf(text) else text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val list = servers.toMutableList()
        var added = 0
        for (line in lines) {
            try {
                val parsed = V2RayConfigParser.parse(line)
                if (list.none { it.config == parsed.config }) {
                    list += ServerEntry(name = parsed.name, config = parsed.config, uri = parsed.uri)
                    added++
                }
            } catch (_: Exception) { }
        }
        if (added > 0) {
            saveServers(list)
            if (selectedId.isBlank()) select(list.last().id)
            message("Imported $added server(s)")
        } else message("No valid servers found in clipboard")
    }

    fun openEditor(e: ServerEntry) {
        val profile = try { if (e.uri.isNotBlank()) V2RayConfigParser.parseUri(e.uri) else null } catch (_: Exception) { null }
        if (profile != null) editing = EditorTarget(e, profile) else jsonEditing = e
    }

    fun toggleConnect() {
        if (tampered) { message("App integrity check failed – connection disabled"); return }
        if (status == VpnStatus.Connected) { onDisconnect(); return }
        val sel = servers.firstOrNull { it.id == selectedId }
        if (sel == null) message("Please select a server first") else onConnect(sel.config)
    }

    LaunchedEffect(Unit) {
        runUpdate(auto = true)
    }

    MaterialTheme(colorScheme = appColorScheme(dark)) {
        Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
            val target = editing
            if (target != null) {
                BackHandler { editing = null }
                ServerEditorScreen(
                    title = if (target.entry == null) "New server" else "Edit server",
                    initial = target.profile,
                    onBack = { editing = null },
                    onSave = { profile ->
                        val parsed = V2RayConfigParser.fromProfile(profile)
                        val old = target.entry
                        if (old == null) {
                            addParsed(parsed)
                        } else {
                            val updated = old.copy(name = parsed.name, config = parsed.config, uri = parsed.uri)
                            saveServers(servers.map { if (it.id == old.id) updated else it })
                        }
                        editing = null
                        Toast.makeText(context, "Server saved", Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                Scaffold(
                    containerColor = Bg,
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text("ON Tunnel", fontWeight = FontWeight.Bold, fontSize = 22.sp) },
                            navigationIcon = {
                                Box {
                                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text(if (isUpdating) "Checking..." else "Check update") },
                                            leadingIcon = {
                                                if (isUpdating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                                else Icon(Icons.Default.Refresh, null)
                                            },
                                            enabled = !isUpdating,
                                            onClick = { menuOpen = false; runUpdate(auto = false) }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    when (themeMode) {
                                                        "light" -> "Theme: Light"
                                                        "dark" -> "Theme: Dark"
                                                        else -> "Theme: Auto"
                                                    }
                                                )
                                            },
                                            leadingIcon = { Icon(Icons.Default.BrightnessMedium, null) },
                                            onClick = {
                                                themeMode = when (themeMode) { "system" -> "light"; "light" -> "dark"; else -> "system" }
                                                store.themeMode = themeMode
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Add server") },
                                            leadingIcon = { Icon(Icons.Default.Add, null) },
                                            onClick = { menuOpen = false; editing = EditorTarget(null, ServerProfile()) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Paste link / JSON") },
                                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                                            onClick = { menuOpen = false; showPaste = true }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Import from clipboard") },
                                            leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                                            onClick = { menuOpen = false; importFromClipboard() }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete all servers") },
                                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = RedErr) },
                                            onClick = { menuOpen = false; confirmDeleteAll = true }
                                        )
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = { openTelegram(context) }) {
                                    Icon(Icons.AutoMirrored.Filled.Send, "Telegram", tint = Accent)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Bg, titleContentColor = TextMain,
                                navigationIconContentColor = TextMain
                            )
                        )
                    }
                ) { padding ->
                    LazyColumn(
                        Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            ConnectHero(
                                status = status,
                                serverName = servers.firstOrNull { it.id == selectedId }?.name,
                                onClick = ::toggleConnect
                            )
                        }
                        if (tampered) {
                            item { ErrorCard("App signature does not match the official build – connection disabled for your safety.", {}) }
                        }
                        if (error != null) {
                            item { ErrorCard(error, onDismissError) }
                        }
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Servers", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier.clip(RoundedCornerShape(10.dp)).background(SurfaceHigh)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) { Text("${servers.size}", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                        if (servers.isEmpty()) {
                            item {
                                Box(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceC).padding(28.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No servers yet.\nUse the menu ⋮ → Check update, or add one manually.",
                                        color = TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            items(servers, key = { it.id }) { entry ->
                                ServerCard(
                                    entry = entry,
                                    selected = entry.id == selectedId,
                                    onSelect = { select(entry.id) },
                                    onEdit = { openEditor(entry) },
                                    onCopyUri = {
                                        if (entry.uri.isNotBlank()) copyText("URI", entry.uri)
                                        else message("No link stored for this server – use Copy JSON")
                                    },
                                    onCopyJson = { copyText("JSON", entry.config) },
                                    onDuplicate = {
                                        val copy = entry.copy(id = java.util.UUID.randomUUID().toString(), name = entry.name + " copy")
                                        saveServers(servers + copy)
                                    },
                                    onDelete = {
                                        saveServers(servers.filter { it.id != entry.id })
                                        if (selectedId == entry.id) select("")
                                    }
                                )
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }

            // ── Dialogs ────────────────────────────────────────────────
            if (showTelegram) {
                AlertDialog(
                    onDismissRequest = { showTelegram = false },
                    containerColor = SurfaceC,
                    icon = {
                        Box(
                            Modifier.size(56.dp).clip(CircleShape).background(Accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.AutoMirrored.Filled.Send, null, tint = Accent, modifier = Modifier.size(28.dp)) }
                    },
                    title = { Text("Join our Telegram channel", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            "Get the latest servers, updates and support on @radouane_tech.",
                            color = TextMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(onClick = { openTelegram(context); showTelegram = false }) { Text("Join Channel") }
                    },
                    dismissButton = { TextButton(onClick = { showTelegram = false }) { Text("Later") } }
                )
            }

            if (showPaste) {
                PasteDialog(
                    title = "Add server",
                    initial = "",
                    confirmText = "Add",
                    onDismiss = { showPaste = false },
                    onConfirm = { text ->
                        try {
                            addParsed(V2RayConfigParser.parse(text))
                            showPaste = false
                            Toast.makeText(context, "Server added", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Invalid configuration", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }

            jsonEditing?.let { entry ->
                PasteDialog(
                    title = "Edit JSON",
                    initial = entry.config,
                    confirmText = "Save",
                    onDismiss = { jsonEditing = null },
                    onConfirm = { text ->
                        try {
                            val parsed = V2RayConfigParser.parse(text)
                            val updated = entry.copy(name = parsed.name, config = parsed.config, uri = parsed.uri)
                            saveServers(servers.map { if (it.id == entry.id) updated else it })
                            jsonEditing = null
                            Toast.makeText(context, "Server saved", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Invalid configuration", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }

            if (confirmDeleteAll) {
                AlertDialog(
                    onDismissRequest = { confirmDeleteAll = false },
                    containerColor = SurfaceC,
                    title = { Text("Delete all servers?") },
                    text = { Text("This removes every server from the list. You can restore subscription servers from the menu ⋮ → Check update.", color = TextMuted) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDeleteAll = false
                            saveServers(emptyList()); select("")
                        }) { Text("Delete", color = RedErr) }
                    },
                    dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } }
                )
            }
        }
    }
}

@Composable
private fun ConnectHero(status: VpnStatus, serverName: String?, onClick: () -> Unit) {
    val busy = status == VpnStatus.Connecting || status == VpnStatus.Disconnecting
    val target = when (status) {
        VpnStatus.Connected -> GreenOk
        VpnStatus.Disconnected -> Accent
        else -> Amber
    }
    val color by animateColorAsState(target, label = "hero")
    val label = when (status) {
        VpnStatus.Connected -> "Connected"
        VpnStatus.Connecting -> "Connecting..."
        VpnStatus.Disconnecting -> "Disconnecting..."
        VpnStatus.Disconnected -> "Tap to connect"
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(color.copy(alpha = 0.08f)))
            Box(Modifier.size(164.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)))
            Box(
                Modifier.size(126.dp).clip(CircleShape).background(color)
                    .clickable(enabled = !busy, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = "Connect", tint = OnHero, modifier = Modifier.size(56.dp))
            }
            if (busy) CircularProgressIndicator(Modifier.size(150.dp), color = color, strokeWidth = 3.dp)
        }
        Spacer(Modifier.height(10.dp))
        Text(label, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            serverName ?: "No server selected",
            color = TextMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ErrorCard(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RedErr.copy(alpha = 0.12f))
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = RedErr, fontSize = 13.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss", tint = RedErr) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerCard(
    entry: ServerEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onCopyUri: () -> Unit,
    onCopyJson: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onSelect,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) SurfaceHigh else SurfaceC),
        border = if (selected) BorderStroke(1.dp, Accent) else BorderStroke(1.dp, TextMuted.copy(alpha = 0.18f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(18.dp).clip(CircleShape)
                    .border(2.dp, if (selected) Accent else TextMuted.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(Accent))
            }
            Spacer(Modifier.width(12.dp))
            if (entry.locked) {
                Icon(Icons.Default.Lock, "Locked", tint = TextMuted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                entry.name, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, "Options", tint = TextMuted, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!entry.locked) {
                        DropdownMenuItem(
                            text = { Text("Edit") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menu = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy link") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { menu = false; onCopyUri() }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy JSON") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { menu = false; onCopyJson() }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Default.Add, null) },
                            onClick = { menu = false; onDuplicate() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = RedErr) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = RedErr) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun PasteDialog(
    title: String,
    initial: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceC,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Paste VLESS / VMess / Trojan / JSON") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 320.dp)
            )
        },
        confirmButton = { Button(onClick = { onConfirm(text) }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun isSubscriptionConfigured(): Boolean =
    SUBSCRIPTION_URL.isNotBlank() &&
        !SUBSCRIPTION_URL.contains("example.com") &&
        (SUBSCRIPTION_URL.startsWith("http://") || SUBSCRIPTION_URL.startsWith("https://"))

/** Supports plain text (one link per line) and base64-encoded subscription bodies. */
private fun fetchAndParseSubscription(url: String): Result<List<ServerEntry>> = runCatching {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20000
        readTimeout = 20000
        requestMethod = "GET"
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 ONTunnel/1.2")
        setRequestProperty("Accept", "text/plain, */*")
    }
    val code = conn.responseCode
    if (code !in 200..299) {
        conn.disconnect()
        error("HTTP $code")
    }
    val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
    conn.disconnect()
    if (body.isBlank()) error("Subscription is empty")

    val lines = tryDecodeSubscription(body).lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
    val entries = mutableListOf<ServerEntry>()
    val errors = mutableListOf<String>()
    for (line in lines) {
        try {
            val parsed = V2RayConfigParser.parse(line)
            entries += ServerEntry(name = parsed.name, config = parsed.config, uri = "", locked = true)
        } catch (e: Exception) {
            errors += (e.message ?: "parse error")
        }
    }
    if (entries.isEmpty()) {
        error(
            if (lines.isEmpty()) "No servers found in subscription"
            else "Parsed 0/${lines.size} lines. First error: ${errors.firstOrNull() ?: "unknown"}"
        )
    }
    entries
}

private fun tryDecodeSubscription(body: String): String {
    if (body.contains("://") || body.trimStart().startsWith("{")) return body
    for (flag in listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP, Base64.DEFAULT or Base64.NO_WRAP)) {
        try {
            val decoded = String(Base64.decode(body, flag), Charsets.UTF_8).trim()
            if (decoded.contains("://") || decoded.startsWith("{")) return decoded
        } catch (_: Exception) { }
    }
    return body
}
