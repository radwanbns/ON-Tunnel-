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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val AppBg = Color(0xFF101820)
private val Panel = Color(0xFF1B2732)
private val PanelSelected = Color(0xFF17364A)
private val Blue = Color(0xFF29B6F6)
private val Green = Color(0xFF27C98A)
private val MainText = Color(0xFFF1F5F8)
private val Muted = Color(0xFF9EADB9)
private val Red = Color(0xFFFF7777)

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
private fun ONTunnelScreen(state: String, connected: Boolean, onConnect: (String) -> Unit, onDisconnect: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("on_tunnel_servers", Context.MODE_PRIVATE) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf(prefs.getStringSet("items", emptySet())?.toList() ?: emptyList()) }
    var selected by remember { mutableStateOf(prefs.getString("selected", "") ?: "") }
    val selectedName = selected.substringBefore("|").ifBlank { "لم يتم اختيار سيرفر" }
    val statusColor = if (connected) Green else if (state.contains("جارٍ")) Blue else Muted

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = AppBg) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ON Tunnel", color = MainText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("V2Ray • Secure connection", color = Muted, fontSize = 11.sp)
                    }
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Panel).padding(horizontal = 13.dp, vertical = 8.dp)) {
                        Text("V2RAY", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(22.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("حالة الاتصال", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        when {
                            connected -> "CONNECTED"
                            state.contains("جارٍ الاتصال") -> "CONNECTING..."
                            state.contains("جارٍ الفصل") -> "DISCONNECTING..."
                            else -> "DISCONNECTED"
                        },
                        color = statusColor, fontSize = 19.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(18.dp))
                    Box(
                        Modifier.size(158.dp).clip(CircleShape)
                            .background(if (connected) Color(0xFF123B35) else Color(0xFF12344A))
                            .clickable {
                                if (connected) onDisconnect()
                                else {
                                    val encoded = selected.substringAfter("|", "")
                                    if (encoded.isBlank()) message = "أضف سيرفراً واختره أولاً"
                                    else try {
                                        onConnect(String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)))
                                        message = ""
                                    } catch (_: Exception) { message = "تعذر قراءة إعداد السيرفر" }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier.size(128.dp).clip(CircleShape).background(if (connected) Green else Blue),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (connected) "STOP" else "CONNECT", color = Color(0xFF07131D), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    Spacer(Modifier.height(13.dp))
                    Text(if (connected) "اضغط للفصل عن الشبكة الآمنة" else "اضغط للاتصال بالسيرفر المحدد", color = Muted, fontSize = 11.sp)
                    if (state.startsWith("فشل")) {
                        Spacer(Modifier.height(8.dp))
                        Text(state, color = Red, fontSize = 11.sp)
                    }
                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(message, color = Red, fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(15.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(14.dp)) {
                    Text("السيرفر الحالي", color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (connected) Green else Muted))
                        Spacer(Modifier.width(9.dp))
                        Text(selectedName, color = MainText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${servers.size} servers", color = Muted, fontSize = 10.sp)
                    }
                }

                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("CONFIGURATIONS", color = MainText, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${servers.size} items", color = Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(9.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(12.dp)) {
                    Text("إضافة إعداد جديد", color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.height(9.dp))
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it; message = "" },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(AppBg).padding(12.dp),
                        textStyle = TextStyle(color = MainText, fontSize = 13.sp),
                        decorationBox = { inner ->
                            if (input.isEmpty()) Text("الصق VLESS / VMess / Trojan / JSON", color = Muted, fontSize = 12.sp)
                            inner()
                        }
                    )
                    Spacer(Modifier.height(9.dp))
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
                                message = "تمت إضافة السيرفر بنجاح"
                            } catch (e: Exception) { message = e.message ?: "الإعداد غير صالح" }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color(0xFF07131D))
                    ) { Text("＋  إضافة السيرفر", fontWeight = FontWeight.Bold) }
                }

                Spacer(Modifier.height(14.dp))
                servers.forEach { item ->
                    val name = item.substringBefore("|")
                    val active = selected == item
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 7.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (active) PanelSelected else Panel)
                            .clickable {
                                selected = item
                                prefs.edit().putString("selected", item).apply()
                            }.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (active) Blue else Muted))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = MainText, fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (active) "SELECTED" else "Tap to select", color = if (active) Blue else Muted, fontSize = 9.sp)
                        }
                        TextButton(onClick = {
                            servers = servers - item
                            prefs.edit().putStringSet("items", servers.toSet()).apply()
                            if (selected == item) {
                                selected = ""
                                prefs.edit().remove("selected").apply()
                            }
                        }) { Text("حذف", color = Red, fontSize = 11.sp) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("ON Tunnel • VLESS • VMess • Trojan", color = Muted, fontSize = 10.sp)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
