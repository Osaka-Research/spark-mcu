package com.osakaresearch.spark.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osakaresearch.spark.net.Ota
import com.osakaresearch.spark.net.OtaResult
import com.osakaresearch.spark.net.SparkClient
import com.osakaresearch.spark.net.SparkConnState
import kotlinx.coroutines.launch

@Composable
fun FirmwareScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connState by SparkClient.state.collectAsState()

    var hostOverride by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("80") }
    var selectedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedName by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var log by remember { mutableStateOf(listOf("firmware upload ready.")) }
    var uploading by remember { mutableStateOf(false) }

    LaunchedEffect(connState) {
        if (hostOverride.isBlank() && connState is SparkConnState.Connected) {
            val url = (connState as SparkConnState.Connected).url
            // ws://host:81/  -> host
            val host = url.removePrefix("ws://").substringBefore(":").substringBefore("/")
            hostOverride = host
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedName = uri.lastPathSegment?.substringAfterLast('/') ?: "firmware.bin"
            log = log + "picked: $selectedName"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Firmware",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Push a .bin over WiFi. The chip reboots into it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Target", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hostOverride,
                        onValueChange = { hostOverride = it },
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
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Binary", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (selectedUri == null) "no file selected"
                    else selectedName,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Pick .bin") }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            val uri = selectedUri ?: return@Button
                            val host = hostOverride.trim()
                            val p = port.toIntOrNull() ?: 80
                            if (host.isBlank()) {
                                log = log + "need a host"
                                return@Button
                            }
                            uploading = true
                            progress = 0f
                            log = log + "uploading to $host:$p ..."
                            scope.launch {
                                val result = Ota.upload(context, host, p, uri) { done, total ->
                                    progress = if (total > 0) done.toFloat() / total.toFloat() else 0f
                                }
                                uploading = false
                                log = log + when (result) {
                                    is OtaResult.Ok -> "ok: ${result.bytes} bytes · ${result.response}"
                                    is OtaResult.Fail -> "failed: ${result.reason}"
                                }
                            }
                        },
                        enabled = !uploading && selectedUri != null && hostOverride.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (uploading) "Uploading" else "Flash") }
                }
                if (uploading || progress > 0f) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                log.takeLast(50).forEach { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
