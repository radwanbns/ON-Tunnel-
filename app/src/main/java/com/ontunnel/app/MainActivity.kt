package com.ontunnel.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF071321)
private val Panel = Color(0xFF102338)
private val Cyan = Color(0xFF00C2E8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ONTunnelScreen() }
    }
}

@Composable
private fun ONTunnelScreen() {
    val connected = remember { mutableStateOf(false) }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Navy) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("OT", color = Cyan, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text("  ON Tunnel", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(34.dp))
                Text("الوقت المتبقي", color = Color.LightGray, fontSize = 15.sp)
                Text("00:05:00", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(30.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(18.dp)
                ) {
                    Text("الخادم", color = Color.LightGray)
                    Spacer(Modifier.height(8.dp))
                    Text("لم يتم اختيار خادم", color = Color.White, fontSize = 17.sp)
                }
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = { connected.value = !connected.value },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy)
                ) {
                    Text(if (connected.value) "DISCONNECT" else "CONNECT", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.height(16.dp))
                Text(if (connected.value) "واجهة تجريبية — لا يوجد اتصال VPN فعلي بعد" else "غير متصل", color = Color.LightGray)
                Spacer(Modifier.weight(1f))
                Text("ON Tunnel • Version 1.0", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}
