package com.ontunnel.app

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun SshSection(text: String) {
    Text(
        text.uppercase(), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
    )
}

@Composable
private fun SshHint(text: String, color: androidx.compose.ui.graphics.Color = TextMuted) {
    Text(text, color = color, fontSize = 12.sp, lineHeight = 17.sp)
}

/** Editor for SSH entries: connection type, server, proxy, payload, TLS and credentials. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SshEditorScreen(
    title: String,
    initial: SshProfile,
    onSave: (SshProfile) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var p by remember { mutableStateOf(initial) }
    var portText by remember { mutableStateOf(initial.port.toString()) }
    var proxyPortText by remember { mutableStateOf(initial.proxyPort.toString()) }

    fun save() {
        val final = p.copy(
            name = p.name.trim(),
            host = p.host.trim(),
            username = p.username.trim(),
            port = portText.trim().toIntOrNull() ?: 22,
            proxyHost = p.proxyHost.trim(),
            proxyPort = proxyPortText.trim().toIntOrNull() ?: 8080
        )
        val err = final.validate()
        if (err != null) Toast.makeText(context, err, Toast.LENGTH_LONG).show() else onSave(final)
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { save() }) { Icon(Icons.Default.Check, "Save", tint = Accent) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Bg, titleContentColor = TextMain, navigationIconContentColor = TextMain
                )
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SshSection("Profile")
            Field("Name", p.name, { p = p.copy(name = it) })
            Choice("Protocol", p.mode, SshMode.all) { mode ->
                val next = p.copy(mode = mode)
                // Pre-fill a sensible payload the first time a payload mode is chosen.
                p = if (next.usesPayload && next.payload.isBlank()) {
                    next.copy(payload = if (next.usesProxy) OvpnMode.CONNECT_TEMPLATE else SshMode.WS_PAYLOAD)
                } else next
            }

            if (p.isDnstt) {
                SshHint("DNSTT is now started as the transport for SSH. Enter a UDP DNS resolver (IP or hostname, optionally :port), the tunnel NS domain, and the server public key.", Accent)
                Field("DNS resolver (UDP / IP or hostname)", p.dnsServer, { p = p.copy(dnsServer = it) })
                Field("Tunnel domain (NS)", p.dnsDomain, { p = p.copy(dnsDomain = it) })
                OutlinedTextField(
                    value = p.dnsKey, onValueChange = { p = p.copy(dnsKey = it) },
                    label = { Text("DNSTT public key") }, minLines = 2, maxLines = 4,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (p.isDnstt) {
                SshHint("The SSH destination host and port are configured on the DNSTT server. This app connects to the local DNSTT client endpoint.", TextMuted)
            } else {
                SshSection("SSH server")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { Field("SSH host", p.host, { p = p.copy(host = it) }) }
                    Box(Modifier.width(100.dp)) {
                        Field("Port", portText, { portText = it.filter(Char::isDigit).take(5) }, KeyboardType.Number)
                    }
                }
            }

            if (p.usesProxy) {
                SshSection("Proxy")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { Field("Proxy host", p.proxyHost, { p = p.copy(proxyHost = it) }) }
                    Box(Modifier.width(100.dp)) {
                        Field("Port", proxyPortText, { proxyPortText = it.filter(Char::isDigit).take(5) }, KeyboardType.Number)
                    }
                }
                if (!p.usesPayload) {
                    SshHint("ON Tunnel sends an HTTP CONNECT request to the proxy for the SSH server's address, then carries SSH through it.")
                }
            }

            if (p.usesPayload) {
                SshSection("Payload")
                OutlinedTextField(
                    value = p.payload, onValueChange = { p = p.copy(payload = it) },
                    label = { Text("Payload") }, minLines = 3, maxLines = 8,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { p = p.copy(payload = OvpnMode.CONNECT_TEMPLATE) }) { Text("CONNECT") }
                    TextButton(onClick = { p = p.copy(payload = SshMode.WS_PAYLOAD) }) { Text("WS-style") }
                    TextButton(onClick = { p = p.copy(payload = OvpnMode.DECOY_TEMPLATE) }) { Text("Decoy + ACL") }
                }
                SshHint("The payload is sent first${if (p.usesProxy) " to the proxy" else " to the server"}; after the HTTP reply (101 or 2xx) the connection becomes a raw SSH stream. Any text before the SSH banner is ignored.")
                SshHint("Tags: [crlf] [cr] [lf] [crlf*N] [host] [port] [host_port] [ua] [rotate=a;b] [split] [delay_split] [instant_split]")
            }

            if (p.usesTls) {
                SshSection("TLS")
                Field("SNI", p.sni, { p = p.copy(sni = it) })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow insecure certificate", color = TextMain, modifier = Modifier.weight(1f))
                    Switch(checked = p.allowInsecure, onCheckedChange = { p = p.copy(allowInsecure = it) })
                }
                SshHint("The TLS layer ${if (p.usesProxy) "is opened to the proxy" else "is opened to the SSH host"}. SSH still authenticates the real server, so a front-domain SNI with an unmatched certificate is fine.")
            }

            SshSection("Account")
            Field("SSH Username", p.username, { p = p.copy(username = it) })
            Choice("Authentication", p.authType, SshMode.authTypes) { p = p.copy(authType = it) }
            if (p.usesKey) {
                OutlinedTextField(
                    value = p.privateKey, onValueChange = { p = p.copy(privateKey = it) },
                    label = { Text("Private key (PEM / OpenSSH)") }, minLines = 5, maxLines = 12,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = p.passphrase, onValueChange = { p = p.copy(passphrase = it) },
                    label = { Text("Key passphrase (optional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                OutlinedTextField(
                    value = p.password, onValueChange = { p = p.copy(password = it) },
                    label = { Text("SSH Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
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
