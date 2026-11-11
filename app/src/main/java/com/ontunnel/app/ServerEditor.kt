package com.ontunnel.app

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = Accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

/** Full server editor – same fields V2rayNG exposes for VLESS / VMess / Trojan. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerEditorScreen(
    title: String,
    initial: ServerProfile,
    onSave: (ServerProfile) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var p by remember { mutableStateOf(initial) }
    var portText by remember { mutableStateOf(initial.port.toString()) }
    var aidText by remember { mutableStateOf(initial.alterId.toString()) }

    fun save() {
        val port = portText.trim().toIntOrNull()
        if (port == null) {
            Toast.makeText(context, "Invalid port", Toast.LENGTH_SHORT).show()
            return
        }
        val final = p.copy(port = port, alterId = aidText.trim().toIntOrNull() ?: 0).normalized()
        try {
            V2RayConfigParser.buildConfig(final)
            onSave(final)
        } catch (e: Exception) {
            Toast.makeText(context, e.message ?: "Invalid configuration", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { save() }) { Icon(Icons.Default.Check, "Save", tint = Accent) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextMain, navigationIconContentColor = TextMain)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionTitle("Server")
            Field("Remarks", p.remarks, { p = p.copy(remarks = it) })
            Choice("Protocol", p.protocol, ServerOptions.protocols) { v ->
                p = p.copy(
                    protocol = v,
                    security = when {
                        v == "trojan" && p.security == "none" -> "tls"
                        v == "vmess" && p.security == "reality" -> "tls"
                        else -> p.security
                    }
                )
            }
            Field("Address", p.address, { p = p.copy(address = it) })
            Field("Port", portText, { portText = it.filter(Char::isDigit).take(5) }, KeyboardType.Number)
            Field(if (p.protocol == "trojan") "Password" else "UUID", p.id, { p = p.copy(id = it) })

            when (p.protocol) {
                "vmess" -> {
                    Field("Alter ID", aidText, { aidText = it.filter(Char::isDigit).take(4) }, KeyboardType.Number)
                    Choice("Security", p.vmessSecurity, ServerOptions.vmessCiphers) { p = p.copy(vmessSecurity = it) }
                }
                "vless" -> {
                    Choice("Flow", p.flow.ifBlank { "none" }, ServerOptions.flows) {
                        p = p.copy(flow = if (it == "none") "" else it)
                    }
                    Field("Encryption", p.encryption, { p = p.copy(encryption = it) })
                }
            }

            SectionTitle("Transport")
            Choice("Network", p.network, ServerOptions.networks) { p = p.copy(network = it) }
            when (p.network) {
                "tcp" -> {
                    Choice("Header type", p.headerType, ServerOptions.headerTypes) { p = p.copy(headerType = it) }
                    if (p.headerType == "http") {
                        Field("Host (comma separated)", p.host, { p = p.copy(host = it) })
                        Field("Path", p.path, { p = p.copy(path = it) })
                    }
                }
                "ws", "httpupgrade", "h2" -> {
                    Field("Host", p.host, { p = p.copy(host = it) })
                    Field("Path", p.path, { p = p.copy(path = it) })
                }
                "xhttp" -> {
                    Field("Host", p.host, { p = p.copy(host = it) })
                    Field("Path", p.path, { p = p.copy(path = it) })
                    Choice("Mode", p.xhttpMode, ServerOptions.xhttpModes) { p = p.copy(xhttpMode = it) }
                }
                "grpc" -> {
                    Field("Service name", p.path, { p = p.copy(path = it) })
                    Choice("Mode", p.grpcMode, ServerOptions.grpcModes) { p = p.copy(grpcMode = it) }
                }
            }

            SectionTitle("Security")
            val securities = if (p.protocol == "vmess") listOf("none", "tls") else ServerOptions.securities
            Choice("TLS", p.security, securities) { p = p.copy(security = it) }
            if (p.security == "tls" || p.security == "reality") {
                Field("SNI", p.sni, { p = p.copy(sni = it) })
                Choice("Fingerprint", p.fingerprint.ifBlank { "none" }, ServerOptions.fingerprints) {
                    p = p.copy(fingerprint = if (it == "none") "" else it)
                }
            }
            if (p.security == "tls") {
                Field("ALPN (comma separated)", p.alpn, { p = p.copy(alpn = it) })
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Allow insecure", color = TextMain, modifier = Modifier.weight(1f))
                    Switch(checked = p.allowInsecure, onCheckedChange = { p = p.copy(allowInsecure = it) })
                }
            }
            if (p.security == "reality") {
                Field("Public key", p.publicKey, { p = p.copy(publicKey = it) })
                Field("Short ID", p.shortId, { p = p.copy(shortId = it) })
                Field("SpiderX", p.spiderX, { p = p.copy(spiderX = it) })
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { save() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp)
            ) { Text("Save", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
