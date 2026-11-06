package com.ontunnel.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Navy = Color(0xFF071321)
private val Panel = Color(0xFF102338)
private val Cyan = Color(0xFF00C2E8)

class MainActivity : ComponentActivity() {
    private var pendingConfig: String? = null
    private var stateText by mutableStateOf("غير متصل")
    private var isConnected by mutableStateOf(false)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) pendingConfig?.let(::startVpn)
    }
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(ONTunnelVpnService.EXTRA_STATE)) {
                "connected" -> { stateText = "متصل"; isConnected = true }
                "disconnected" -> { stateText = "غير متصل"; isConnected = false }
                "error" -> { stateText = "فشل الاتصال: " + intent.getStringExtra(ONTunnelVpnService.EXTRA_ERROR).orEmpty(); isConnected = false }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(ONTunnelVpnService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(stateReceiver, filter)
        setContent { ONTunnelScreen(stateText, isConnected, ::connect, ::disconnect) }
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
        stateText = "جارٍ الاتصال..."
    }

    private fun disconnect() {
        startService(Intent(this, ONTunnelVpnService::class.java).setAction(ONTunnelVpnService.ACTION_STOP))
        stateText = "جارٍ الفصل..."
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("on_tunnel_servers", Context.MODE_PRIVATE) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf(prefs.getStringSet("items", emptySet())?.toList() ?: emptyList()) }
    var selected by remember { mutableStateOf(prefs.getString("selected", "") ?: "") }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Navy) {
            Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ON Tunnel", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                Text("V2Ray VPN", color = Cyan, fontSize = 14.sp)
                Text(state, color = if (connected) Cyan else Color.LightGray, fontSize = 17.sp)
                Spacer(Modifier.height(18.dp))

                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(14.dp)) {
                    Text("رابط V2Ray أو إعداد JSON", color = Color.LightGray)
                    Spacer(Modifier.height(10.dp))
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it; message = "" },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        decorationBox = { inner ->
                            if (input.isEmpty()) Text("vless:// أو vmess:// أو trojan://", color = Color.Gray)
                            inner()
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            try {
                                val parsed = V2RayConfigParser.parse(input)
                                val encoded = android.util.Base64.encodeToString(parsed.config.toByteArray(), android.util.Base64.NO_WRAP)
                                val entry = parsed.name + "|" + encoded
                                servers = (servers + entry).distinct()
                                prefs.edit().putStringSet("items", servers.toSet()).apply()
                                selected = entry
                                prefs.edit().putString("selected", entry).apply()
                                input = ""
                                message = "تمت إضافة السيرفر"
                            } catch (e: Exception) {
                                message = e.message ?: "الإعداد غير صالح"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy)
                    ) { Text("إضافة السيرفر") }
                    if (message.isNotBlank()) Text(message, color = if (message.startsWith("تم")) Cyan else Color(0xFFFF8A80), fontSize = 12.sp)
                }

                Spacer(Modifier.height(14.dp))
                Text("السيرفرات (" + servers.size + ")", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(servers) { item ->
                        val name = item.substringBefore("|")
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .background(Panel, RoundedCornerShape(10.dp)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected == item, onClick = {
                                selected = item
                                prefs.edit().putString("selected", item).apply()
                            })
                            Text(name, color = Color.White, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                servers = servers - item
                                prefs.edit().putStringSet("items", servers.toSet()).apply()
                                if (selected == item) {
                                    selected = ""
                                    prefs.edit().remove("selected").apply()
                                }
                            }) { Text("حذف", color = Color(0xFFFF8A80)) }
                        }
                    }
                }

                Button(
                    onClick = {
                        if (connected) onDisconnect()
                        else {
                            val encoded = selected.substringAfter("|", "")
                            if (encoded.isBlank()) message = "أضف سيرفراً واختره أولاً"
                            else try {
                                onConnect(String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)))
                            } catch (_: Exception) {
                                message = "تعذر قراءة إعداد السيرفر"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy)
                ) {
                    Text(if (connected) "DISCONNECT" else "CONNECT", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text("VLESS • VMess • Trojan • JSON", color = Color.Gray, fontSize = 11.sp)
            }
        }
    }
}
