package com.ontunnel.app

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
private fun OvpnSection(text: String) {
    Text(
        text.uppercase(), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
    )
}

@Composable
private fun Hint(text: String, color: androidx.compose.ui.graphics.Color = TextMuted) {
    Text(text, color = color, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMain, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Editor for OpenVPN entries, including the HTTP payload / WebSocket transport options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OvpnEditorScreen(
    title: String,
    initial: OvpnProfile,
    onSave: (OvpnProfile) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var p by remember { mutableStateOf(initial) }
    var portText by remember { mutableStateOf(initial.proxyPort.toString()) }

    fun save() {
        val final = p.copy(
            name = p.name.trim(),
            proxyPort = portText.trim().toIntOrNull() ?: 8080,
            wsPath = p.wsPath.ifBlank { "/" }
        )
        val err = final.validate()
        if (err != null) Toast.makeText(context, err, Toast.LENGTH_LONG).show() else onSave(final)
    }

    val remote = p.remote

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
            OvpnSection("Profile")
            Field("Name", p.name, { p = p.copy(name = it) })
            OutlinedTextField(
                value = p.config, onValueChange = { p = p.copy(config = it) },
                label = { Text(".ovpn configuration") },
                minLines = 6, maxLines = 12,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth()
            )
            if (remote != null) {
                Hint("Server: ${remote.host}:${remote.port} (${if (remote.tcp) "TCP" else "UDP"})")
            } else {
                Hint("No 'remote' line found yet.", RedErr)
            }

            OvpnSection("Account")
            Field("Username (optional)", p.username, { p = p.copy(username = it) })
            OutlinedTextField(
                value = p.password, onValueChange = { p = p.copy(password = it) },
                label = { Text("Password (optional)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            if (p.needsAuth && p.username.isBlank()) {
                Hint("This profile uses auth-user-pass. If you leave this empty, OpenVPN for Android will ask for the login.")
            }

            OvpnSection("Transport")
            Choice("Connection type", OvpnMode.toLabel(p.mode), OvpnMode.labels) { label ->
                val mode = OvpnMode.fromLabel(label)
                val autoPayload = when {
                    mode != OvpnMode.PAYLOAD -> p.payload
                    p.payload.isNotBlank() -> p.payload
                    p.proxyHost.isNotBlank() -> OvpnMode.CONNECT_TEMPLATE
                    else -> OvpnMode.PAYLOAD_TEMPLATE
                }
                p = p.copy(mode = mode, payload = autoPayload)
            }

            if (p.usesRelay) {
                Hint(
                    "ON Tunnel opens the connection to your server itself, sends the " +
                        (if (p.mode == OvpnMode.WEBSOCKET) "WebSocket upgrade" else "payload") +
                        ", then carries OpenVPN through it. The server must expose OpenVPN over TCP behind this endpoint."
                )
                if (remote != null && !remote.tcp) {
                    Hint("Your profile says UDP – it will be forced to TCP in this mode.", Amber)
                }
                Field("Connect to (optional)  host[:port]", p.serverOverride, { p = p.copy(serverOverride = it) })

                if (p.mode == OvpnMode.PAYLOAD) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { Field("HTTP proxy host (optional)", p.proxyHost, { p = p.copy(proxyHost = it) }) }
                        Box(Modifier.width(100.dp)) {
                            Field("Port", portText, { portText = it.filter(Char::isDigit).take(5) }, KeyboardType.Number)
                        }
                    }
                    OutlinedTextField(
                        value = p.payload, onValueChange = { p = p.copy(payload = it) },
                        label = { Text("Payload") }, minLines = 3, maxLines = 8,
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { p = p.copy(payload = OvpnMode.CONNECT_TEMPLATE) }) {
                            Text("CONNECT")
                        }
                        TextButton(onClick = { p = p.copy(payload = OvpnMode.PAYLOAD_TEMPLATE) }) {
                            Text("WS-style")
                        }
                        TextButton(onClick = { p = p.copy(payload = OvpnMode.DECOY_TEMPLATE) }) {
                            Text("Decoy + ACL")
                        }
                    }
                    Hint("HTTP payload mode always carries OpenVPN as a raw stream after the server's reply (also after “101 Switching Protocols”). With several requests in one payload, every reply except the last is read and ignored; the last one must be 101 or 2xx.")
                    Hint("For HTTP proxy on any port: set Proxy host/port and use CONNECT template (or leave payload empty – auto CONNECT).")
                    Hint("Tags: [crlf] [cr] [lf] [host] [port] [host_port] [ua] [split] [delay_split]")
                } else {
                    Field("WebSocket path", p.wsPath, { p = p.copy(wsPath = it) })
                    Field("Host header (optional)", p.wsHost, { p = p.copy(wsHost = it) })
                    OutlinedTextField(
                        value = p.payload, onValueChange = { p = p.copy(payload = it) },
                        label = { Text("Custom request (optional)") }, minLines = 2, maxLines = 8,
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Hint("Leave the custom request empty to use a standard RFC 6455 upgrade. If you write one, include [ws_key] in the Sec-WebSocket-Key header.")
                    SwitchRow("WebSocket framing (RFC 6455)", p.wsFraming) { p = p.copy(wsFraming = it) }
                    Hint("Turn this OFF when the server switches to a plain TCP pipe after “101 Switching Protocols” (typical for injector payloads such as decoy GET + ACL). OFF behaves like HTTP payload mode.")
                }

                SwitchRow("TLS (HTTPS)", p.tls) { p = p.copy(tls = it) }
                if (p.tls) {
                    Field("SNI", p.sni, { p = p.copy(sni = it) })
                    SwitchRow("Allow insecure certificate", p.allowInsecure) { p = p.copy(allowInsecure = it) }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { save() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp)
            ) { Text("Save", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
