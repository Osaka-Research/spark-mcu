package com.osakaresearch.spark.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osakaresearch.spark.net.SparkClient
import com.osakaresearch.spark.net.SparkConnState
import com.osakaresearch.spark.net.SparkDiscovery

@Composable
fun ScanScreen() {
    var host by remember { mutableStateOf("spark-mcu.local") }
    var port by remember { mutableStateOf("81") }
    val state by SparkClient.state.collectAsState()
    val context = LocalContext.current
    val discovery = remember(context) { SparkDiscovery(context) }
    val devices by discovery.devices.collectAsState()
    val discoveryStatus by discovery.status.collectAsState()
    DisposableEffect(discovery) {
        discovery.start()
        onDispose { discovery.stop() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "Scan",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Find the chip or type where it lives.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Manual", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host / IP") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() } },
                        label = { Text("Port") },
                        modifier = Modifier.width(100.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(
                        onClick = {
                            val p = port.toIntOrNull() ?: 81
                            SparkClient.connect(host.trim(), p)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Connect") }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = { SparkClient.disconnect() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Disconnect") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        StatusLine(state)

        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("mDNS discovery", fontWeight = FontWeight.SemiBold)
                Text(
                    "service: _spark._tcp",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Text(discoveryStatus, fontSize = 12.sp)
                devices.forEach { device ->
                    OutlinedButton(onClick = {
                        host = device.host
                        port = device.port.toString()
                        SparkClient.connect(device.host, device.port)
                    }) { Text("${device.name} · ${device.host}:${device.port}") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        McpStatusCard()
    }
}

@Composable
private fun StatusLine(state: SparkConnState) {
    val (text, color) = when (state) {
        is SparkConnState.Connected -> "connected: ${state.url}" to MaterialTheme.colorScheme.primary
        is SparkConnState.Connecting -> "connecting: ${state.url}" to MaterialTheme.colorScheme.secondary
        is SparkConnState.Failed -> "failed: ${state.reason}" to MaterialTheme.colorScheme.error
        SparkConnState.Disconnected -> "disconnected" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    }
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = color)
}
