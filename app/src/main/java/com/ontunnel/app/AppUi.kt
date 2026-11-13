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
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.input.pointer.pointerInput
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
private class SshEditorTarget(val entry: ServerEntry?, val profile: SshProfile)

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
    updateDownloading: Boolean,
    updateProgress: Int,
    onDismissAppUpdate: () -> Unit,
    incomingServerFileUri: Uri?,
    onIncomingFileHandled: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { ServerStore(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var servers by remember { mutableStateOf(store.load()) }
    var selectedId by remember { mutableStateOf(store.selectedId) }
    var isUpdating by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var currentTab by rememberSaveable { mutableStateOf("HOME") }
    var showAddSheet by remember { mutableStateOf(false) }
    // Shown on EVERY app launch (rememberSaveable only survives rotation, not a new launch)
    var showTelegram by rememberSaveable { mutableStateOf(true) }
    var showPaste by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditorTarget?>(null) }
    var ovpnEditing by remember { mutableStateOf<OvpnEditorTarget?>(null) }
    var sshEditing by remember { mutableStateOf<SshEditorTarget?>(null) }
    var jsonEditing by remember { mutableStateOf<ServerEntry?>(null) }
    var passwordPurpose by remember { mutableStateOf<String?>(null) }
    var passwordInput by remember { mutableStateOf("") }
    var pendingImportText by remember { mutableStateOf<String?>(null) }
    var pendingShareEntry by remember { mutableStateOf<ServerEntry?>(null) }
    var pendingCotExportEntry by remember { mutableStateOf<ServerEntry?>(null) }
    var cotExportEntry by remember { mutableStateOf<ServerEntry?>(null) }
    var cotNoteInput by remember { mutableStateOf("") }
    var cotLockEnabled by remember { mutableStateOf(false) }
    var cotExportText by remember { mutableStateOf<String?>(null) }

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
        Toast.makeText(context, "$label – copied", Toast.LENGTH_SHORT).show()
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
                // Deduplicate subscription entries by actual profile, not display name.
                // The same V2Ray URI can appear more than once with different labels.
                fun profileKey(e: ServerEntry) = "${e.type}|${e.config.trim()}"
                val uniqueFresh = fresh.distinctBy { profileKey(it) }

                val previousSubscription = servers.filter { it.subscription }
                // Older app versions saved Raw subscription entries as locked=true but
                // without the subscription marker. Match those against the downloaded
                // profiles and migrate them instead of keeping a second copy as manual.
                val legacySubscription = servers.filter { old ->
                    !old.subscription && old.locked &&
                        uniqueFresh.any { profileKey(it) == profileKey(old) }
                }
                val oldByProfile = (previousSubscription + legacySubscription)
                    .associateBy { profileKey(it) }

                val newLocked = uniqueFresh.map { e ->
                    (oldByProfile[profileKey(e)]?.let { e.copy(id = it.id) } ?: e)
                        .copy(locked = true, subscription = true)
                }
                val previousKeys = (previousSubscription + legacySubscription)
                    .map { profileKey(it) }.toSet()
                val added = newLocked.count { profileKey(it) !in previousKeys }
                val manual = servers.filter { !it.subscription && it !in legacySubscription }
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

    fun addImported(imported: List<ServerEntry>) {
        // Locally imported locked servers are still user-owned and deletable.
        // Only built-in subscription entries are excluded from duplicate checks.
        val localServers = servers.filter { !it.subscription }
        val fresh = imported.filterNot { candidate ->
            localServers.any { it.type == candidate.type && it.config == candidate.config }
        }
        if (fresh.isEmpty()) { message("These servers are already imported"); return }
        saveServers(servers + fresh)
        select(fresh.first().id)
        message("Imported ${fresh.size} server(s)")
    }

    fun beginCotImport(text: String) {
        if (ServerTransfer.isLockedCotFile(text)) {
            pendingImportText = text
            passwordInput = ""
            passwordPurpose = "import_cot"
            return
        }
        try { addImported(listOf(ServerTransfer.importCot(text))) }
        catch (e: Exception) { message(e.message ?: "Could not import .cot server") }
    }

    fun shareServerUri(entry: ServerEntry, locked: Boolean) {
        if (locked) {
            pendingShareEntry = entry
            passwordInput = ""
            passwordPurpose = "export_uri"
            return
        }
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("URI", ServerTransfer.shareUri(entry, false)))
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { message(e.message ?: "This server cannot be copied as URI") }
    }

    fun shareServerJson(entry: ServerEntry) {
        try { val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; cm.setPrimaryClip(ClipData.newPlainText("JSON", ServerTransfer.export(listOf(entry)))); Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show() }
        catch (e: Exception) { message(e.message ?: "Could not copy JSON") }
    }

    fun importFromClipboard() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
            if (text.isBlank()) {
                message("Clipboard is empty")
                return
            }
            when {
                ServerTransfer.isCotFile(text) -> {
                    beginCotImport(text)
                }
                OvpnConfigTools.looksLikeOvpn(text) -> {
                    openOvpnEditorFromText(text)
                }
                ServerTransfer.isLockedUri(text) -> {
                    pendingImportText = text
                    passwordInput = ""
                    passwordPurpose = "import_uri"
                }
                ServerTransfer.isLockedFile(text) -> {
                    pendingImportText = text
                    passwordInput = ""
                    passwordPurpose = "import_file"
                }
                else -> {
                    val imported = ServerTransfer.importText(text)
                    if (imported.isEmpty()) {
                        message("No supported servers found in clipboard")
                    } else {
                        addImported(imported)
                    }
                }
            }
        } catch (e: Exception) {
            message(e.message?.takeIf { it.isNotBlank() } ?: "No supported servers found in clipboard")
        }
    }

    LaunchedEffect(incomingServerFileUri) {
        val uri = incomingServerFileUri
        if (uri != null) {
            try {
                val text = if (uri.scheme.equals("ontunnel", ignoreCase = true)) {
                    uri.toString()
                } else {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read file")
                    if (bytes.size > 5_000_000) error("Server file is too large")
                    String(bytes, Charsets.UTF_8)
                }
                if (ServerTransfer.isCotFile(text)) {
                    beginCotImport(text)
                } else if (OvpnConfigTools.looksLikeOvpn(text)) {
                    openOvpnEditorFromText(text, displayName(context, uri)?.substringBeforeLast('.'))
                } else if (ServerTransfer.isLockedUri(text)) {
                    pendingImportText = text
                    passwordInput = ""
                    passwordPurpose = "import_uri"
                } else if (ServerTransfer.isLockedFile(text)) {
                    pendingImportText = text
                    passwordInput = ""
                    passwordPurpose = "import_file"
                } else addImported(ServerTransfer.importText(text))
            } catch (e: Exception) { message(e.message ?: "Could not open server file") }
            finally { onIncomingFileHandled() }
        }
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

    val serverFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read file")
            if (bytes.size > 5_000_000) error("Server file is too large")
            val text = String(bytes, Charsets.UTF_8)
            if (ServerTransfer.isCotFile(text)) {
                beginCotImport(text)
            } else if (OvpnConfigTools.looksLikeOvpn(text)) {
                openOvpnEditorFromText(text, displayName(context, uri)?.substringBeforeLast('.'))
            } else if (ServerTransfer.isLockedUri(text)) {
                pendingImportText = text
                passwordInput = ""
                passwordPurpose = "import_uri"
            } else if (ServerTransfer.isLockedFile(text)) {
                pendingImportText = text
                passwordInput = ""
                passwordPurpose = "import_file"
            } else addImported(ServerTransfer.importText(text))
        } catch (e: Exception) { message(e.message ?: "Could not import server file") }
    }
    val cotExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.ontunnel.cot")) { uri: Uri? ->
        if (uri != null) try {
            val data = cotExportText ?: error("Nothing to export")
            context.contentResolver.openOutputStream(uri)?.use { it.write(data.toByteArray(Charsets.UTF_8)) } ?: error("Cannot create file")
            message("ON Tunnel server file exported")
        } catch (e: Exception) { message(e.message ?: "Export failed") }
        cotExportText = null
    }
    fun openEditor(e: ServerEntry) {
        if (e.isSsh) {
            val profile = SshProfile.fromJson(e.config)
            if (profile != null) sshEditing = SshEditorTarget(e, profile) else message("Invalid SSH profile")
            return
        }
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
        Surface(
            color = Bg,
            modifier = Modifier.fillMaxSize().pointerInput(currentTab) {
                var horizontalDrag = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        horizontalDrag += dragAmount
                    },
                    onDragEnd = {
                        // RTL navigation: swipe right to move HOME → SERVERS → LOGS → MORE.
                        // Swipe left moves back through the same pages.
                        if (horizontalDrag > 60f) {
                            currentTab = when (currentTab) {
                                "HOME" -> "CONFIG"
                                "CONFIG" -> "LOGS"
                                "LOGS" -> "MORE"
                                else -> "MORE"
                            }
                        } else if (horizontalDrag < -60f) {
                            currentTab = when (currentTab) {
                                "MORE" -> "LOGS"
                                "LOGS" -> "CONFIG"
                                "CONFIG" -> "HOME"
                                else -> "HOME"
                            }
                        }
                        horizontalDrag = 0f
                    },
                    onDragCancel = { horizontalDrag = 0f }
                )
            }
        ) {
            val v2Target = editing
            val ovTarget = ovpnEditing
            val sshTarget = sshEditing
            if (sshTarget != null) {
                BackHandler { sshEditing = null }
                SshEditorScreen(
                    title = if (sshTarget.entry == null) "New SSH server" else "Edit SSH server",
                    initial = sshTarget.profile,
                    onBack = { sshEditing = null },
                    onSave = { profile ->
                        val old = sshTarget.entry
                        if (old == null) {
                            val entry = ServerEntry.ssh(profile)
                            saveServers(servers + entry)
                            select(entry.id)
                        } else {
                            val updated = old.copy(name = profile.name, config = profile.toJson().toString())
                            saveServers(servers.map { if (it.id == old.id) updated else it })
                        }
                        sshEditing = null
                        Toast.makeText(context, "Server saved", Toast.LENGTH_SHORT).show()
                    }
                )
            } else if (ovTarget != null) {
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
            } else if (currentTab == "LOGS") {
                BackHandler { currentTab = "HOME" }
                LogsScreen(onBack = { currentTab = "HOME" }, onConfig = { currentTab = "CONFIG" }, onMore = { currentTab = "MORE" })
            } else if (currentTab == "MORE") {
                BackHandler { currentTab = "HOME" }
                MoreScreen(
                    themeMode = themeMode,
                    onThemeChange = {
                        themeMode = it
                        store.themeMode = it
                    },
                    onHome = { currentTab = "HOME" },
                    onServers = { currentTab = "CONFIG" },
                    onLogs = { currentTab = "LOGS" }
                )
            } else {
                val selected = servers.firstOrNull { it.id == selectedId }

                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Accent.copy(alpha = if (dark) 0.16f else 0.12f),
                            0.55f to Bg,
                            1f to Bg
                        )
                    )
                ) {
                    Scaffold(
                        containerColor = Color.Transparent,
                        snackbarHost = { SnackbarHost(snackbar) },
                        bottomBar = {
                            HomeConfigLogsBottomBar(
                                selectedTab = currentTab,
                                onHome = { currentTab = "HOME" },
                                onConfig = { currentTab = "CONFIG" },
                                onLogs = { currentTab = "LOGS" },
                                onMore = { currentTab = "MORE" }
                            )
                        },
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = {
                                    Text("ON Tunnel", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                                },
                                navigationIcon = {
                                    Box {
                                        IconButton(onClick = { menuOpen = true }) {
                                            Icon(Icons.Default.MoreVert, "Menu")
                                        }
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
                                                    themeMode = when (themeMode) {
                                                        "system" -> "light"
                                                        "light" -> "dark"
                                                        else -> "system"
                                                    }
                                                    store.themeMode = themeMode
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Add server") },
                                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                                onClick = { menuOpen = false; showAddSheet = true }
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
                                                text = { Text("Import server file") },
                                                leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                                onClick = { menuOpen = false; serverFilePicker.launch(arrayOf("*/*")) }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Export a server (.cot)") },
                                                leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                                                enabled = servers.any { !it.locked && !it.subscription },
                                                onClick = {
                                                    menuOpen = false
                                                    message("Open a server's three-dot menu and choose Export server (.cot)")
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Delete added servers") },
                                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = RedErr) },
                                                onClick = { menuOpen = false; confirmDeleteAll = true }
                                            )
                                        }
                                    }
                                },
                                actions = {
                                    if (currentTab == "HOME") {
                                        IconButton(onClick = { openTelegram(context) }) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Send,
                                                contentDescription = "Telegram channel",
                                                tint = Accent
                                            )
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
                        }
                    ) { padding ->
                        if (currentTab == "HOME") {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().padding(padding),
                                contentPadding = PaddingValues(bottom = 28.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                item(key = "connection_hero") {
                                    ConnectHero(
                                        status = status,
                                        detail = statusDetail,
                                        serverName = selected?.name,
                                        serverBadge = null,
                                        busyLocked = status == VpnStatus.Disconnecting,
                                        onClick = ::toggleConnect
                                    )
                                }
                                // The imported .cot HTML note belongs in HOME's free content area,
                                // not inside the server row in CONFIG.
                                if (!selected?.noteHtml.isNullOrBlank()) {
                                    item(key = "selected_server_note") {
                                        Box(
                                            Modifier.fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                        ) {
                                            HtmlServerNote(selected!!.noteHtml)
                                        }
                                    }
                                }
                                if (tampered) {
                                    item(key = "integrity_error") {
                                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                            ErrorCard("App signature does not match the official build – connection disabled for your safety.", {})
                                        }
                                    }
                                }
                                if (error != null) {
                                    item(key = "connection_error") {
                                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                            ErrorCard(error, onDismissError)
                                        }
                                    }
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(padding)
                            ) {
                                // Fixed header: only the server list below is scrollable.
                                Column(
                                    Modifier.fillMaxWidth()
                                        .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Servers", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            Modifier.clip(RoundedCornerShape(10.dp))
                                                .background(SurfaceHigh)
                                                .padding(horizontal = 9.dp, vertical = 3.dp)
                                        ) {
                                            Text("${servers.size}", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Text("Choose a server", color = TextMuted, fontSize = 11.sp)
                                    }
                                }
                                if (tampered) {
                                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                        ErrorCard(
                                            "App signature does not match the official build – connection disabled for your safety.",
                                            {}
                                        )
                                    }
                                }
                                if (error != null) {
                                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                        ErrorCard(error, onDismissError)
                                    }
                                }
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
                                    verticalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    if (servers.isEmpty()) {
                                        item(key = "empty_servers") {
                                            Column(
                                                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                                    .clip(RoundedCornerShape(22.dp))
                                                    .background(SurfaceC)
                                                    .padding(28.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Icon(Icons.Default.Dns, null, tint = Accent, modifier = Modifier.size(38.dp))
                                                Spacer(Modifier.height(10.dp))
                                                Text("No servers yet", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    "Use the menu to add a server or update your list.",
                                                    color = TextMuted,
                                                    fontSize = 13.sp,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    } else {
                                    items(servers, key = { it.id }) { entry ->
                                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                            ServerCard(
                                                entry = entry,
                                                badge = "",
                                                selected = entry.id == selectedId,
                                                onSelect = {
                                                    if (entry.id != selectedId &&
                                                        (status == VpnStatus.Connected || status == VpnStatus.Connecting)
                                                    ) {
                                                        onDisconnect()
                                                    }
                                                    select(entry.id)
                                                    currentTab = "HOME"
                                                    message("Selected: ${entry.name}")
                                                },
                                                onEdit = { openEditor(entry) },
                                                onShareUri = { locked -> shareServerUri(entry, locked) },
                                                onShareJson = { shareServerJson(entry) },
                                                onExportCot = {
                                                    cotExportEntry = entry
                                                    cotNoteInput = entry.noteHtml
                                                    cotLockEnabled = false
                                                },
                                                onDuplicate = {
                                                    val copy = entry.copy(
                                                        id = java.util.UUID.randomUUID().toString(),
                                                        name = entry.name + " copy"
                                                    )
                                                    saveServers(servers + copy)
                                                },
                                                onDelete = {
                                                    if (!entry.subscription) {
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
                }


            } // end else (no editor)

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
                        SheetRow(Icons.Default.Terminal, "New SSH server", "Direct, proxy, payload, TLS and more") {
                            showAddSheet = false
                            sshEditing = SshEditorTarget(null, SshProfile())
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

            if (updateDownloading) {\n                AlertDialog(\n                    onDismissRequest = { },\n                    containerColor = SurfaceC,\n                    title = { Text("Downloading ON Tunnel", fontWeight = FontWeight.Bold) },\n                    text = {\n                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {\n                            LinearProgressIndicator(progress = { updateProgress / 100f }, modifier = Modifier.fillMaxWidth())\n                            Text("$updateProgress%", color = TextMuted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)\n                            Text("Please keep the app open until the download finishes.", color = TextMuted)\n                        }\n                    },\n                    confirmButton = {}\n                )\n            }\n\n            appUpdate?.takeIf { !showTelegram && !updateDownloading }?.let { update ->
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
                        } else if (ServerTransfer.isCotFile(text)) {
                            try {
                                beginCotImport(text)
                                showPaste = false
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Invalid .cot file", Toast.LENGTH_LONG).show()
                            }
                        } else if (ServerTransfer.isLockedUri(text)) {
                            pendingImportText = text
                            passwordInput = ""
                            passwordPurpose = "import_uri"
                            showPaste = false
                        } else if (ServerTransfer.isLockedFile(text)) {
                            pendingImportText = text
                            passwordInput = ""
                            passwordPurpose = "import_file"
                            showPaste = false
                        } else {
                            try {
                                addImported(ServerTransfer.importText(text))
                                showPaste = false
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

            if (cotExportEntry != null) {
                AlertDialog(
                    onDismissRequest = { cotExportEntry = null },
                    containerColor = SurfaceC,
                    title = { Text("Export server as .cot", color = TextMain, fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Add an optional HTML note. It will appear with this server after import. Links in the note can be opened.",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                            OutlinedTextField(
                                value = cotNoteInput,
                                onValueChange = { cotNoteInput = it },
                                label = { Text("HTML note") },
                                placeholder = { Text("<p>Server note: <a href=\"https://example.com\">Open link</a></p>") },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 220.dp)
                            )
                            Text("File protection", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (cotLockEnabled) {
                                    OutlinedButton(onClick = { cotLockEnabled = false }, modifier = Modifier.weight(1f)) {
                                        Text("Unlocked")
                                    }
                                    Button(onClick = { cotLockEnabled = true }, modifier = Modifier.weight(1f)) {
                                        Text("Locked")
                                    }
                                } else {
                                    Button(onClick = { cotLockEnabled = false }, modifier = Modifier.weight(1f)) {
                                        Text("Unlocked")
                                    }
                                    OutlinedButton(onClick = { cotLockEnabled = true }, modifier = Modifier.weight(1f)) {
                                        Text("Locked")
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            try {
                                val entry = cotExportEntry ?: error("No server selected")
                                if (cotLockEnabled) {
                                    pendingCotExportEntry = entry
                                    passwordInput = ""
                                    passwordPurpose = "export_cot"
                                    cotExportEntry = null
                                } else {
                                    cotExportText = ServerTransfer.exportCot(entry, cotNoteInput, false)
                                    val safeName = entry.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                                        .trim('_').ifBlank { "ON-Tunnel-server" }
                                    cotExportPicker.launch("$safeName.cot")
                                    cotExportEntry = null
                                }
                            } catch (e: Exception) {
                                message(e.message ?: "Export failed")
                            }
                        }) { Text("Export .cot", color = Accent) }
                    },
                    dismissButton = {
                        TextButton(onClick = { cotExportEntry = null }) { Text("Cancel", color = TextMuted) }
                    }
                )
            }

            if (passwordPurpose != null) {
                AlertDialog(
                    onDismissRequest = {
                        passwordPurpose = null
                        pendingImportText = null
                        pendingShareEntry = null
                        pendingCotExportEntry = null
                        passwordInput = ""
                    },
                    containerColor = SurfaceC,
                    title = {
                        Text(
                            when (passwordPurpose) {
                                "export_uri" -> "Set password for locked URI"
                                "export_cot" -> "Set password for locked .cot"
                                else -> "Unlock server"
                            },
                            color = TextMain
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                if (passwordPurpose == "export_uri" || passwordPurpose == "export_cot")
                                    "Choose a password with at least 6 characters. Share it separately with the recipient."
                                else "Enter the password chosen by the server owner.",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            try {
                                when (passwordPurpose) {
                                    "export_uri" -> {
                                        val entry = pendingShareEntry ?: error("No server selected")
                                        val encryptedUri = ServerTransfer.shareUri(entry, true, passwordInput)
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("URI", encryptedUri))
                                        Toast.makeText(context, "Encrypted locked URI copied", Toast.LENGTH_SHORT).show()
                                        pendingShareEntry = null
                                    }
                                    "export_cot" -> {
                                        val entry = pendingCotExportEntry ?: error("No server selected")
                                        cotExportText = ServerTransfer.exportCot(entry, cotNoteInput, true, passwordInput)
                                        val safeName = entry.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                                            .trim('_').ifBlank { "ON-Tunnel-server" }
                                        cotExportPicker.launch("$safeName.cot")
                                        pendingCotExportEntry = null
                                    }
                                    else -> {
                                        val pending = pendingImportText ?: error("Missing file")
                                        val imported = when (passwordPurpose) {
                                            "import_uri" -> ServerTransfer.importText(pending, passwordInput)
                                            "import_cot" -> listOf(ServerTransfer.importCot(pending, passwordInput))
                                            else -> ServerTransfer.importServers(pending, passwordInput, preserveLock = true)
                                        }
                                        addImported(imported)
                                        pendingImportText = null
                                    }
                                }
                                passwordPurpose = null
                                passwordInput = ""
                            } catch (e: Exception) { message(e.message ?: "Transfer failed") }
                        }) {
                            Text(
                                if (passwordPurpose == "export_uri" || passwordPurpose == "export_cot")
                                    "Encrypt and continue" else "Unlock and import",
                                color = Accent
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            passwordPurpose = null
                            pendingImportText = null
                        }) { Text("Cancel", color = TextMuted) }
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
                            val keep = servers.filter { it.subscription }
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



@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreScreen(
    themeMode: String,
    onThemeChange: (String) -> Unit,
    onHome: () -> Unit,
    onServers: () -> Unit,
    onLogs: () -> Unit
) {
    val context = LocalContext.current
    val version = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            "${info.versionName ?: "Unknown"} ($code)"
        } catch (_: Exception) { "Unknown" }
    }
    val socials = listOf(
        Triple("Facebook", "https://www.facebook.com/radouanetech1", Icons.Default.Public),
        Triple("YouTube", "https://youtube.com/@radouane_tech", Icons.Default.PlayCircle),
        Triple("Telegram", "https://t.me/radouane_tech", Icons.AutoMirrored.Filled.Send),
        Triple("WhatsApp", "https://whatsapp.com/channel/0029VbEFISc1iUxQigpG0t17", Icons.Default.Chat)
    )
    val proxyPrefs = remember { context.getSharedPreferences(ProxyShareService.PREFS, Context.MODE_PRIVATE) }
    var httpEnabled by remember { mutableStateOf(proxyPrefs.getBoolean("http_running", false)) }
    var socksEnabled by remember { mutableStateOf(proxyPrefs.getBoolean("socks_running", false)) }
    var proxyIp by remember { mutableStateOf(proxyPrefs.getString("local_ip", "").orEmpty()) }
    var httpPortText by remember { mutableStateOf(proxyPrefs.getInt("http_port", ProxyShareService.DEFAULT_HTTP_PORT).toString()) }
    var socksPortText by remember { mutableStateOf(proxyPrefs.getInt("socks_port", ProxyShareService.DEFAULT_SOCKS_PORT).toString()) }
    LaunchedEffect(Unit) {
        while (true) {
            httpEnabled = proxyPrefs.getBoolean("http_running", false)
            socksEnabled = proxyPrefs.getBoolean("socks_running", false)
            proxyIp = proxyPrefs.getString("local_ip", "").orEmpty()
            delay(1000)
        }
    }
    fun copyProxy(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }
    Scaffold(
        containerColor = Bg,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("ON Tunnel", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = TextMain
                )
            )
        },
        bottomBar = {
            HomeConfigLogsBottomBar(
                selectedTab = "MORE",
                onHome = onHome,
                onConfig = onServers,
                onLogs = onLogs,
                onMore = {}
            )
        }
    ) { innerPadding ->
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("More", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        item {
            SettingsCard(title = "Appearance") {
                listOf("system" to "System default", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { onThemeChange(value) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = themeMode == value,
                            onClick = { onThemeChange(value) },
                            colors = RadioButtonDefaults.colors(selectedColor = Accent)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, color = TextMain, fontSize = 15.sp)
                    }
                }
            }
        }
        item {
            SettingsCard(title = "Internet sharing") {
                Text(
                    "Share this phone's connection with devices on the same Wi-Fi network or hotspot. Keep sharing off on public networks.",
                    color = TextMuted, fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = httpPortText,
                    onValueChange = { httpPortText = it.filter(Char::isDigit).take(5) },
                    label = { Text("HTTP proxy port") },
                    singleLine = true,
                    enabled = !httpEnabled,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("HTTP Proxy", color = TextMain, fontWeight = FontWeight.SemiBold)
                        Text(if (httpEnabled) "Running at $proxyIp:$httpPortText" else "Stopped", color = TextMuted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = httpEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                val port = httpPortText.toIntOrNull()
                                if (port == null || port !in 1024..65535) {
                                    Toast.makeText(context, "Enter a port from 1024 to 65535", Toast.LENGTH_SHORT).show()
                                } else ProxyShareService.start(context, "http", port)
                            } else ProxyShareService.stop(context, "http")
                        }
                    )
                }
                if (httpEnabled && proxyIp.isNotBlank()) {
                    TextButton(onClick = { copyProxy("HTTP proxy", proxyIp + ":" + httpPortText) }) {
                        Icon(Icons.Default.ContentCopy, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Copy HTTP address")
                    }
                }
                Divider(color = TextMuted.copy(alpha = 0.2f))
                OutlinedTextField(
                    value = socksPortText,
                    onValueChange = { socksPortText = it.filter(Char::isDigit).take(5) },
                    label = { Text("SOCKS5 proxy port") },
                    singleLine = true,
                    enabled = !socksEnabled,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SOCKS5 Proxy", color = TextMain, fontWeight = FontWeight.SemiBold)
                        Text(if (socksEnabled) "Running at $proxyIp:$socksPortText" else "Stopped", color = TextMuted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = socksEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                val port = socksPortText.toIntOrNull()
                                if (port == null || port !in 1024..65535) {
                                    Toast.makeText(context, "Enter a port from 1024 to 65535", Toast.LENGTH_SHORT).show()
                                } else ProxyShareService.start(context, "socks", port)
                            } else ProxyShareService.stop(context, "socks")
                        }
                    )
                }
                if (socksEnabled && proxyIp.isNotBlank()) {
                    TextButton(onClick = { copyProxy("SOCKS5 proxy", "socks5://" + proxyIp + ":" + socksPortText) }) {
                        Icon(Icons.Default.ContentCopy, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Copy SOCKS5 address")
                    }
                }
                Text(
                    "The proxy listens on the phone's private network address. Other devices on this network can use it; no username or password is required.",
                    color = TextMuted, fontSize = 11.sp
                )
            }
        }
        item {
            SettingsCard(title = "Social channels") {
                socials.forEach { (label, url, icon) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Could not open $label", Toast.LENGTH_SHORT).show()
                                }
                            }.padding(vertical = 11.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = label, tint = Accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(label, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.OpenInNew, contentDescription = "Open $label", tint = TextMuted, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    "Follow ON Tunnel on Facebook, YouTube, Telegram and WhatsApp.",
                    color = TextMuted, fontSize = 11.sp
                )
            }
        }
        item {
            SettingsCard(title = "Version information") {
                Text("ON Tunnel", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text("Version $version", color = TextMuted, fontSize = 13.sp)
                Text("Android VPN client", color = TextMuted, fontSize = 12.sp)
            }
        }
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
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(236.dp), contentAlignment = Alignment.Center) {
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
                Modifier.size(128.dp)
                    .shadow(18.dp, CircleShape, ambientColor = color, spotColor = color)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.72f))))
                    .clickable(enabled = !busyLocked, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = "Connect", tint = OnHero, modifier = Modifier.size(56.dp))
            }
            if (status == VpnStatus.Connecting || status == VpnStatus.Disconnecting) {
                CircularProgressIndicator(Modifier.size(150.dp), color = color, strokeWidth = 3.dp)
            }
        }
        Text(label, color = color, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
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
private fun HtmlServerNote(html: String) {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            TextView(context).apply {
                textSize = 11f
                setTextColor(android.graphics.Color.GRAY)
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { view ->
            view.text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
            view.movementMethod = LinkMovementMethod.getInstance()
        }
    )
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
    if (entry.isSsh) SshProfile.fromJson(entry.config)?.badge() ?: "SSH"
    else if (entry.isOpenVpn) OvpnProfile.fromJson(entry.config)?.badge() ?: "OpenVPN"
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

@Composable
private fun ServerCard(
    entry: ServerEntry,
    badge: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onShareUri: (Boolean) -> Unit,
    onShareJson: () -> Unit,
    onExportCot: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onSelect,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) SurfaceHigh else SurfaceC),
        border = if (selected) BorderStroke(1.5.dp, Accent) else BorderStroke(0.dp, Color.Transparent),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Radio indicator
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) Accent else Color.Transparent)
                    .then(
                        if (!selected) Modifier.border(2.dp, TextMuted.copy(alpha = 0.45f), CircleShape)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(OnHero))
                }
            }
            Spacer(Modifier.width(10.dp))
            if (entry.locked) {
                Icon(Icons.Default.Lock, "Locked", tint = TextMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    entry.name,
                    color = TextMain,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!entry.subscription) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, "Options", tint = TextMuted, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (!entry.locked) DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onEdit() })
                        if (!entry.locked) DropdownMenuItem(text = { Text("Share URI (locked)") }, leadingIcon = { Icon(Icons.Default.Lock, null) }, onClick = { menu = false; onShareUri(true) })
                        if (!entry.locked) DropdownMenuItem(text = { Text("Share URI (unlocked)") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu = false; onShareUri(false) })
                        if (!entry.locked) DropdownMenuItem(text = { Text("Share JSON") }, leadingIcon = { Icon(Icons.Default.Code, null) }, onClick = { menu = false; onShareJson() })
                        if (!entry.locked) DropdownMenuItem(text = { Text("Export server (.cot)") }, leadingIcon = { Icon(Icons.Default.FileDownload, null) }, onClick = { menu = false; onExportCot() })
                        if (!entry.locked) DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("Delete", color = RedErr) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = RedErr) }, onClick = { menu = false; onDelete() })
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
            if (
                line.startsWith("ontunnel://server/", ignoreCase = true) ||
                line.startsWith("ovpn://", ignoreCase = true) ||
                line.startsWith("ssh://", ignoreCase = true) ||
                line.startsWith("v2ray://", ignoreCase = true)
            ) {
                // ON Tunnel protocol-specific URIs carry complete SSH/OpenVPN/V2Ray profiles.
                // Every entry fetched from the built-in subscription is locked and marked
                // as a subscription entry, regardless of flags embedded in the URI.
                val imported = ServerTransfer.importText(line)
                entries += imported.map { it.copy(locked = true, subscription = true) }
            } else {
                // Standard V2Ray subscription links (VLESS / VMess / Trojan).
                val parsed = V2RayConfigParser.parse(line)
                entries += ServerEntry(
                    name = parsed.name,
                    config = parsed.config,
                    uri = parsed.uri,
                    locked = true,
                    subscription = true
                )
            }
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


@Composable
private fun HomeConfigLogsBottomBar(
    selectedTab: String,
    onHome: () -> Unit,
    onConfig: () -> Unit,
    onLogs: () -> Unit,
    onMore: () -> Unit = {}
) {
    NavigationBar(containerColor = SurfaceC) {
        NavigationBarItem(
            selected = selectedTab == "HOME",
            onClick = onHome,
            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
            label = { Text("HOME") }
        )
        NavigationBarItem(
            selected = selectedTab == "CONFIG",
            onClick = onConfig,
            icon = { Icon(Icons.Default.Dns, contentDescription = "Configurations") },
            label = { Text("SERVERS") }
        )
        NavigationBarItem(
            selected = selectedTab == "LOGS",
            onClick = onLogs,
            icon = { Icon(Icons.Default.ReceiptLong, contentDescription = "Logs") },
            label = { Text("LOGS") }
        )
        NavigationBarItem(
            selected = selectedTab == "MORE",
            onClick = onMore,
            icon = { Icon(Icons.Default.MoreHoriz, contentDescription = "More") },
            label = { Text("MORE") }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsScreen(onBack: () -> Unit, onConfig: () -> Unit, onMore: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(AppLogs.read(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            entries = AppLogs.read(context)
            delay(900)
        }
    }
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            HomeConfigLogsBottomBar(
                selectedTab = "LOGS",
                onHome = onBack,
                onConfig = onConfig,
                onLogs = {},
                onMore = onMore
            )
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Logs", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        val content = entries.joinToString("\n") { "${it.time}  ${it.message}" }
                        if (content.isBlank()) {
                            Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show()
                        } else {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("ON Tunnel Logs", content))
                            Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs") }
                    IconButton(onClick = { AppLogs.clear(context); entries = emptyList() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear logs", tint = RedErr)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = TextMain
                )
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.ReceiptLong, null, tint = Accent, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("No logs yet", color = TextMain, fontWeight = FontWeight.SemiBold)
                Text("Connection events and errors will appear here.", color = TextMuted, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries.reversed(), key = { it.id }) { entry ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SurfaceC).padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(entry.time, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(3.dp))
                        Text(entry.message, color = TextMain, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}