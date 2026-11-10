package com.ontunnel.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.ontunnel.app.ovpn.OvpnConfigTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hardcoded subscription URL – NEVER shown in the UI.
 * Content: one VLESS / VMess / Trojan link per line, or the whole file base64-encoded.
 */
// Real values are XOR-encrypted in SecureStrings.kt – never stored as plain strings.
private val SUBSCRIPTION_URL: String get() = SecureStrings.subscriptionUrl
private val TELEGRAM_URL: String get() = SecureStrings.telegramUrl

private fun openTelegram(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, TELEGRAM_URL, Toast.LENGTH_LONG).show()
    }
}

private class EditorTarget(val entry: ServerEntry?, val profile: ServerProfile)
private class OvpnEditorTarget(val entry: ServerEntry?, val profile: OvpnProfile)

// ═══════════════════════════════════════════════════════════════════════
//  Root screen
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ONTunnelApp(
    status: VpnStatus,
    statusDetail: String?,
    error: String?,
    tampered: Boolean,
    onDismissError: () -> Unit,
    onConnect: (ServerEntry) -> Unit,
    onDisconnect: () -> Unit,
    ovpnMissing: Boolean,
    onDismissOvpnMissing: () -> Unit,
    onOpenOvpnStore: () -> Unit,
    appUpdate: AppUpdateInfo?,
    onDownloadAppUpdate: (AppUpdateInfo) -> Unit,
    onDismissAppUpdate: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { ServerStore(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var servers by remember { mutableStateOf(store.load()) }
    var selectedId by remember { mutableStateOf(store.selectedId) }
    var isUpdating by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    // Shown on EVERY app launch (rememberSaveable only survives rotation, not a new launch)
    var showTelegram by rememberSaveable { mutableStateOf(true) }
    var showPaste by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditorTarget?>(null) }
    var ovpnEditing by remember { mutableStateOf<OvpnEditorTarget?>(null) }
    var jsonEditing by remember { mutableStateOf<ServerEntry?>(null) }

    // 0 = Home (Connect), 1 = Servers, 2 = Settings
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showAutoSelectSheet by remember { mutableStateOf(false) }
    var showServerList by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var dnsMode by remember { mutableStateOf(store.dnsMode) }
    var customDns by remember { mutableStateOf(store.customDns) }

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

    fun selectRandom() {
        if (servers.isEmpty()) { message("No servers available"); return }
        val pick = servers.random()
        select(pick.id)
        message("Random: ${pick.name}")
    }

    fun selectAuto() {
        if (servers.isEmpty()) { message("No servers available"); return }
        // Prefer locked (subscription) servers, otherwise any
        val pool = servers.filter { it.locked }.ifEmpty { servers }
        val pick = pool.random()
        select(pick.id)
        message("Auto selected: ${pick.name}")
    }

    fun copyText(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label – copied (Unlocked)", Toast.LENGTH_SHORT).show()
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

    fun openOvpnEditorFromText(text: String, suggestedName: String? = null) {
        try {
            ovpnEditing = OvpnEditorTarget(null, OvpnProfile.fromOvpnText(text, suggestedName))
        } catch (e: Exception) {
            message(e.message ?: "Invalid .ovpn file")
        }
    }

    fun importFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isBlank()) { message("Clipboard is empty"); return }
        if (OvpnConfigTools.looksLikeOvpn(text)) { openOvpnEditorFromText(text); return }
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

    val ovpnFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read the file")
                if (bytes.size > 1_000_000) error("File is too large")
                val name = displayName(context, uri)?.substringBeforeLast('.')
                openOvpnEditorFromText(String(bytes, Charsets.UTF_8), name)
            } catch (e: Exception) {
                message(e.message ?: "Could not import the file")
            }
        }
    }

    fun openEditor(e: ServerEntry) {
        if (e.isOpenVpn) {
            val profile = OvpnProfile.fromJson(e.config)
            if (profile != null) ovpnEditing = OvpnEditorTarget(e, profile) else message("Invalid OpenVPN profile")
            return
        }
        val profile = try { if (e.uri.isNotBlank()) V2RayConfigParser.parseUri(e.uri) else null } catch (_: Exception) { null }
        if (profile != null) editing = EditorTarget(e, profile) else jsonEditing = e
    }

    fun toggleConnect() {
        if (tampered) { message("App integrity check failed – connection disabled"); return }
        if (status == VpnStatus.Connected || (status == VpnStatus.Connecting && statusDetail != null)) { onDisconnect(); return }
        val sel = servers.firstOrNull { it.id == selectedId }
        if (sel == null) message("Please select a server first") else onConnect(sel)
    }

    LaunchedEffect(Unit) {
        runUpdate(auto = true)
    }

    MaterialTheme(colorScheme = appColorScheme(dark)) {
        Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
            val v2Target = editing
            val ovTarget = ovpnEditing
            if (ovTarget != null) {
                BackHandler { ovpnEditing = null }
                OvpnEditorScreen(
                    title = if (ovTarget.entry == null) "New OpenVPN server" else "Edit OpenVPN server",
                    initial = ovTarget.profile,
                    onBack = { ovpnEditing = null },
                    onSave = { profile ->
                        val old = ovTarget.entry
                        if (old == null) {
                            val entry = ServerEntry.openVpn(profile)
                            saveServers(servers + entry)
                            select(entry.id)
                        } else {
                            val updated = old.copy(name = profile.name, config = profile.toJson().toString())
                            saveServers(servers.map { if (it.id == old.id) updated else it })
                        }
                        ovpnEditing = null
                        Toast.makeText(context, "Server saved", Toast.LENGTH_SHORT).show()
                    }
                )
            } else if (v2Target != null) {
                BackHandler { editing = null }
                ServerEditorScreen(
                    title = if (v2Target.entry == null) "New server" else "Edit server",
                    initial = v2Target.profile,
                    onBack = { editing = null },
                    onSave = { profile ->
                        val parsed = V2RayConfigParser.fromProfile(profile)
                        val old = v2Target.entry
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
                val selected = servers.firstOrNull { it.id == selectedId }
                val connected = status == VpnStatus.Connected
                val connecting = status == VpnStatus.Connecting || status == VpnStatus.Disconnecting

                val drawerState = rememberDrawerState(DrawerValue.Closed)
                LaunchedEffect(drawerOpen) {
                    if (drawerOpen) drawerState.open() else drawerState.close()
                }
                LaunchedEffect(drawerState.currentValue) {
                    if (drawerState.currentValue == DrawerValue.Closed) drawerOpen = false
                }
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = true,
                    drawerContent = {
                        ModalDrawerSheet(drawerContainerColor = SurfaceC) {
                            Column(
                                Modifier
                                    .fillMaxHeight()
                                    .width(280.dp)
                                    .padding(vertical = 12.dp)
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Shield, null, tint = Accent, modifier = Modifier.size(32.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text("ON Tunnel", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                                }
                                HorizontalDivider(color = TextMuted.copy(alpha = 0.2f))
                                Spacer(Modifier.height(8.dp))
                                DrawerItem(Icons.Default.Settings, "Settings") {
                                    drawerOpen = false
                                    selectedTab = 1
                                }
                            }
                        }
                    }
                ) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Accent.copy(alpha = if (dark) 0.10f else 0.08f),
                                0.4f to Bg,
                                1f to Bg
                            )
                        )
                    ) {
                        Scaffold(
                            containerColor = Color.Transparent,
                            snackbarHost = { SnackbarHost(snackbar) },
                            topBar = {
                                CenterAlignedTopAppBar(
                                    title = {
                                        Text(
                                            if (selectedTab == 1) "Settings" else "ON Tunnel",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                    },
                                    navigationIcon = {
                                        IconButton(onClick = {
                                            if (selectedTab == 1) selectedTab = 0
                                            else drawerOpen = true
                                        }) {
                                            Icon(
                                                if (selectedTab == 1) Icons.Default.ArrowBack else Icons.Default.Menu,
                                                contentDescription = "Menu"
                                            )
                                        }
                                    },
                                    actions = {
                                        if (selectedTab == 0) {
                                            IconButton(onClick = { showAddSheet = true }) {
                                                Icon(Icons.Default.Add, contentDescription = "Add")
                                            }
                                            Box {
                                                IconButton(onClick = { menuOpen = true }) {
                                                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                                                }
                                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                                    DropdownMenuItem(
                                                        text = { Text("Add server") },
                                                        leadingIcon = { Icon(Icons.Default.Add, null) },
                                                        onClick = { menuOpen = false; showAddSheet = true }
                                                    )
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
                                                        text = { Text("Delete added servers") },
                                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = RedErr) },
                                                        onClick = { menuOpen = false; confirmDeleteAll = true }
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                        containerColor = Color.Transparent,
                                        titleContentColor = TextMain,
                                        navigationIconContentColor = TextMain,
                                        actionIconContentColor = TextMain
                                    )
                                )
                            },
                            floatingActionButton = { },
                        ) { padding ->
                            Column(Modifier.fillMaxSize().padding(padding)) {
                                if (selectedTab == 1) {
                                    SettingsPage(
                                        themeMode = themeMode,
                                        onThemeChange = { themeMode = it; store.themeMode = it },
                                        dnsMode = dnsMode,
                                        onDnsModeChange = { dnsMode = it; store.dnsMode = it },
                                        customDns = customDns,
                                        onCustomDnsChange = { customDns = it; store.customDns = it }
                                    )
                                } else {
                                    // Big circular connect button (as before)
                                    ConnectHero(
                                        status = status,
                                        detail = statusDetail,
                                        serverName = selected?.name,
                                        serverBadge = selected?.let { badgeFor(it) },
                                        busyLocked = status == VpnStatus.Disconnecting,
                                        onClick = ::toggleConnect
                                    )
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Servers", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(SurfaceHigh)
                                                .padding(horizontal = 7.dp, vertical = 1.dp)
                                        ) {
                                            Text("${servers.size}", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    if (tampered) {
                                        ErrorCard(
                                            "App signature does not match the official build – connection disabled for your safety.",
                                            {}
                                        )
                                    }
                                    if (error != null) {
                                        ErrorCard(error, onDismissError)
                                    }
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 300.dp),
                                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        if (servers.isEmpty()) {
                                            item {
                                                Column(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(16.dp))
                                                        .background(SurfaceC)
                                                        .padding(24.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Icon(Icons.Default.Dns, null, tint = Accent, modifier = Modifier.size(32.dp))
                                                    Spacer(Modifier.height(8.dp))
                                                    Text(
                                                        "No servers yet.\nTap + to add, or Check update from the menu.",
                                                        color = TextMuted,
                                                        fontSize = 13.sp,
                                                        textAlign = TextAlign.Center
                                                    )
                                                }
                                            }
                                        } else {
                                            items(servers, key = { it.id }) { entry ->
                                                ServerCard(
                                                    entry = entry,
                                                    badge = remember(entry.id, entry.config) { badgeFor(entry) },
                                                    selected = entry.id == selectedId,
                                                    onSelect = {
                                                        select(entry.id)
                                                        if (status != VpnStatus.Connecting && status != VpnStatus.Disconnecting) {
                                                            onConnect(entry)
                                                        }
                                                    },
                                                    onEdit = { openEditor(entry) },
                                                    onCopyUri = {
                                                        if (entry.isOpenVpn) {
                                                            OvpnProfile.fromJson(entry.config)?.let { copyText(".ovpn", it.config) }
                                                        } else if (entry.uri.isNotBlank()) copyText("URI", entry.uri)
                                                        else message("No link stored for this server – use Copy JSON")
                                                    },
                                                    onCopyJson = { copyText("JSON", entry.config) },
                                                    onDuplicate = {
                                                        val copy = entry.copy(
                                                            id = java.util.UUID.randomUUID().toString(),
                                                            name = entry.name + " copy"
                                                        )
                                                        saveServers(servers + copy)
                                                    },
                                                    onDelete = {
                                                        if (!entry.locked) {
                                                            saveServers(servers.filter { it.id != entry.id })
                                                            if (selectedId == entry.id) select("")
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } // end Box/Drawer

            } // end else (no editor)

            // ── Auto-select sheet ──────────────────────────
            if (showAutoSelectSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showAutoSelectSheet = false },
                    containerColor = SurfaceC,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                        Text(
                            "Select a server",
                            color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                        if (servers.isEmpty()) {
                            Text("No servers available. Update subscription or add one.", color = TextMuted, modifier = Modifier.padding(20.dp))
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.heightIn(max = 420.dp)
                            ) {
                                items(servers, key = { it.id }) { entry ->
                                    ServerCard(
                                        entry = entry,
                                        badge = remember(entry.id, entry.config) { badgeFor(entry) },
                                        selected = entry.id == selectedId,
                                        onSelect = {
                                            select(entry.id)
                                            showAutoSelectSheet = false
                                            message("Selected: ${entry.name}")
                                        },
                                        onEdit = { showAutoSelectSheet = false; openEditor(entry) },
                                        onCopyUri = {},
                                        onCopyJson = {},
                                        onDuplicate = {},
                                        onDelete = {}
                                    )
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { selectAuto(); showAutoSelectSheet = false },
                                modifier = Modifier.weight(1f)
                            ) { Text("Auto pick") }
                            Button(
                                onClick = { selectRandom(); showAutoSelectSheet = false },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) { Text("Random") }
                        }
                    }
                }
            }

            // ── Add-server sheet ───────────────────────────────────────
            if (showAddSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showAddSheet = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = SurfaceC
                ) {
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                        Text("Add server", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                        SheetRow(Icons.Default.FolderOpen, "Import OpenVPN file", "Pick an .ovpn profile from your device") {
                            showAddSheet = false
                            ovpnFilePicker.launch(arrayOf("*/*"))
                        }
                        SheetRow(Icons.Default.VpnKey, "New OpenVPN profile", "Paste a config and set payload / WebSocket") {
                            showAddSheet = false
                            ovpnEditing = OvpnEditorTarget(null, OvpnProfile())
                        }
                        SheetRow(Icons.Default.Edit, "Paste link / config", "VLESS, VMess, Trojan, JSON or .ovpn text") {
                            showAddSheet = false
                            showPaste = true
                        }
                        SheetRow(Icons.Default.ContentPaste, "Import from clipboard", "Add everything copied to the clipboard") {
                            showAddSheet = false
                            importFromClipboard()
                        }
                        SheetRow(Icons.Default.Add, "Add V2Ray server manually", "Full editor: transport, TLS, Reality…") {
                            showAddSheet = false
                            editing = EditorTarget(null, ServerProfile())
                        }
                    }
                }
            }

            appUpdate?.takeIf { !showTelegram }?.let { update ->
                AlertDialog(
                    onDismissRequest = onDismissAppUpdate,
                    containerColor = SurfaceC,
                    title = { Text("ON Tunnel update available", fontWeight = FontWeight.Bold) },
                    text = {
                        Text("Version ${update.versionName} is available. Download and install it now?", color = TextMuted)
                    },
                    confirmButton = {
                        Button(onClick = { onDownloadAppUpdate(update) }) { Text("Download and install") }
                    },
                    dismissButton = { TextButton(onClick = onDismissAppUpdate) { Text("Later") } }
                )
            }

            if (ovpnMissing) {
                AlertDialog(
                    onDismissRequest = onDismissOvpnMissing,
                    containerColor = SurfaceC,
                    icon = { Icon(Icons.Default.VpnKey, null, tint = Accent, modifier = Modifier.size(30.dp)) },
                    title = { Text("OpenVPN for Android required", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
                    text = {
                        Text(
                            "OpenVPN connections run on the free “OpenVPN for Android” app (package de.blinkt.openvpn). " +
                                "Install it from Google Play or F-Droid, then connect again. ON Tunnel handles the profile, payload and WebSocket transport.",
                            color = TextMuted
                        )
                    },
                    confirmButton = { Button(onClick = onOpenOvpnStore) { Text("Open Google Play") } },
                    dismissButton = { TextButton(onClick = onDismissOvpnMissing) { Text("Later") } }
                )
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
                        if (OvpnConfigTools.looksLikeOvpn(text)) {
                            showPaste = false
                            openOvpnEditorFromText(text)
                        } else {
                            try {
                                addParsed(V2RayConfigParser.parse(text))
                                showPaste = false
                                Toast.makeText(context, "Server added", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Invalid configuration", Toast.LENGTH_LONG).show()
                            }
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
                    title = { Text("Delete added servers?") },
                    text = { Text("This removes only the servers you added (from clipboard, links, files or manually). Locked subscription servers are kept.", color = TextMuted) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDeleteAll = false
                            val keep = servers.filter { it.locked }
                            saveServers(keep)
                            if (keep.none { it.id == selectedId }) select("")
                        }) { Text("Delete", color = RedErr) }
                    },
                    dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Components
// ═══════════════════════════════════════════════════════════════════════


@Composable
private fun DrawerItem(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (danger) RedErr else Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = if (danger) RedErr else TextMain, fontSize = 15.sp)
    }
}

@Composable
private fun SettingsPage(
    themeMode: String,
    onThemeChange: (String) -> Unit,
    dnsMode: String,
    onDnsModeChange: (String) -> Unit,
    customDns: String,
    onCustomDnsChange: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Settings",
                color = TextMain,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(bottom = 4.dp, top = 4.dp)
            )
        }

        item {
            SettingsCard(title = "Appearance") {
                val labels = listOf("Auto (System)", "Light", "Dark")
                val values = listOf("system", "light", "dark")
                values.forEachIndexed { i, v ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onThemeChange(v) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = themeMode == v,
                            onClick = { onThemeChange(v) },
                            colors = RadioButtonDefaults.colors(selectedColor = Accent)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(labels[i], color = TextMain, fontSize = 15.sp)
                    }
                }
            }
        }

        item {
            SettingsCard(title = "DNS") {
                val options = listOf(
                    "system" to "System default",
                    "cloudflare" to "Cloudflare (1.1.1.1)",
                    "google" to "Google (8.8.8.8)",
                    "adguard" to "AdGuard (94.140.14.14)",
                    "custom" to "Custom"
                )
                options.forEach { (v, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onDnsModeChange(v) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = dnsMode == v,
                            onClick = { onDnsModeChange(v) },
                            colors = RadioButtonDefaults.colors(selectedColor = Accent)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, color = TextMain, fontSize = 15.sp)
                    }
                }
                if (dnsMode == "custom") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customDns,
                        onValueChange = onCustomDnsChange,
                        label = { Text("Custom DNS (IP)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            Text(
                "ON Tunnel",
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SurfaceC).padding(16.dp)
    ) {
        Text(
            title,
            color = TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}

private fun formatElapsed(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

@Composable
private fun ConnectHero(
    status: VpnStatus,
    detail: String?,
    serverName: String?,
    serverBadge: String?,
    busyLocked: Boolean,
    onClick: () -> Unit
) {
    val active = status == VpnStatus.Connected || status == VpnStatus.Connecting
    val target = when (status) {
        VpnStatus.Connected -> GreenOk
        VpnStatus.Disconnected -> Accent
        else -> Amber
    }
    val color by animateColorAsState(target, label = "hero")
    val label = when (status) {
        VpnStatus.Connected -> "Connected"
        VpnStatus.Connecting -> "Connecting…"
        VpnStatus.Disconnecting -> "Disconnecting…"
        VpnStatus.Disconnected -> "Not connected"
    }

    val elapsed by produceState(0L, status) {
        if (status == VpnStatus.Connected) {
            val t0 = System.currentTimeMillis()
            while (true) {
                value = (System.currentTimeMillis() - t0) / 1000
                delay(1000)
            }
        } else {
            value = 0L
        }
    }

    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "pulseValue"
    )

    Column(
        Modifier.fillMaxWidth().padding(top = 0.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val maxR = size.minDimension / 2f
                // static halo
                drawCircle(color.copy(alpha = 0.07f), radius = maxR * 0.86f)
                drawCircle(color.copy(alpha = 0.13f), radius = maxR * 0.66f)
                if (active) {
                    for (i in 0..1) {
                        val f = (pulse + i * 0.5f) % 1f
                        drawCircle(
                            color.copy(alpha = (1f - f) * 0.35f),
                            radius = maxR * (0.56f + 0.44f * f),
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                    }
                }
            }
            Box(
                Modifier.size(88.dp)
                    .shadow(12.dp, CircleShape, ambientColor = color, spotColor = color)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.72f))))
                    .clickable(enabled = !busyLocked, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = "Connect", tint = OnHero, modifier = Modifier.size(40.dp))
            }
            if (status == VpnStatus.Connecting || status == VpnStatus.Disconnecting) {
                CircularProgressIndicator(Modifier.size(104.dp), color = color, strokeWidth = 2.5.dp)
            }
        }
        Text(label, color = color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(2.dp))
        val sub = when {
            status == VpnStatus.Connected -> formatElapsed(elapsed)
            !detail.isNullOrBlank() -> detail
            status == VpnStatus.Disconnected -> "Tap the button to connect"
            else -> ""
        }
        Text(sub, color = TextMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.clip(RoundedCornerShape(24.dp)).background(SurfaceC)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Public, null, tint = Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                serverName ?: "No server selected",
                color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)
            )
            if (serverBadge != null) {
                Spacer(Modifier.width(8.dp))
                BadgeChip(serverBadge)
            }
        }
    }
}

@Composable
private fun BadgeChip(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Accent.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(text, color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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

@Composable
private fun SheetRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = Accent, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
    }
}

private fun badgeFor(entry: ServerEntry): String =
    if (entry.isOpenVpn) OvpnProfile.fromJson(entry.config)?.badge() ?: "OpenVPN"
    else v2rayBadge(entry.config)

/** Reads the first outbound of the generated Xray config, e.g. "VLESS · WS · TLS". */
private fun v2rayBadge(config: String): String = try {
    val outbound = JSONObject(config).getJSONArray("outbounds").getJSONObject(0)
    val protocol = outbound.optString("protocol").uppercase()
    val stream = outbound.optJSONObject("streamSettings")
    val network = stream?.optString("network").orEmpty().uppercase()
    val security = stream?.optString("security").orEmpty().uppercase()
    listOf(protocol, network, security.takeIf { it != "NONE" }.orEmpty())
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { "V2Ray" }
} catch (_: Exception) {
    "V2Ray"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerCard(
    entry: ServerEntry,
    badge: String,
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
        border = if (selected) BorderStroke(1.dp, Accent) else BorderStroke(1.dp, TextMuted.copy(alpha = 0.14f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .background(
                        if (entry.locked) TextMuted.copy(alpha = 0.18f)
                        else if (selected) Accent
                        else Accent.copy(alpha = 0.14f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (entry.locked) Icons.Default.Lock
                    else if (entry.isOpenVpn) Icons.Default.VpnKey
                    else Icons.Default.Bolt,
                    contentDescription = if (entry.locked) "Locked" else null,
                    tint = if (entry.locked) TextMuted
                    else if (selected) OnHero
                    else Accent,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    color = TextMain,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Locked servers: name + lock only (no badge / protocol details)
                if (!entry.locked) {
                    Text(badge, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selected) {
                Icon(Icons.Default.CheckCircle, null, tint = Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
            }
            // Locked: no share / edit menu at all
            if (!entry.locked) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreVert, "Options", tint = TextMuted, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menu = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (entry.isOpenVpn) "Copy .ovpn (Unlocked)" else "Copy link (Unlocked)") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { menu = false; onCopyUri() }
                        )
                        if (!entry.isOpenVpn) {
                            DropdownMenuItem(
                                text = { Text("Copy JSON (Unlocked)") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                onClick = { menu = false; onCopyJson() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            leadingIcon = { Icon(Icons.Default.Add, null) },
                            onClick = { menu = false; onDuplicate() }
                        )
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
                placeholder = { Text("Paste VLESS / VMess / Trojan / JSON / .ovpn") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 320.dp)
            )
        },
        confirmButton = { Button(onClick = { onConfirm(text) }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (_: Exception) {
    null
}

// ═══════════════════════════════════════════════════════════════════════
//  Subscription (unchanged behaviour)
// ═══════════════════════════════════════════════════════════════════════

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
