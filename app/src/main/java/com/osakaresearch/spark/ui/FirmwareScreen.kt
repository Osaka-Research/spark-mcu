package com.osakaresearch.spark.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.osakaresearch.spark.compile.SketchBuilder
import com.osakaresearch.spark.net.FlashResult
import com.osakaresearch.spark.net.Ota
import com.osakaresearch.spark.net.OtaResult
import com.osakaresearch.spark.net.SparkClient
import com.osakaresearch.spark.net.SparkConnState
import com.osakaresearch.spark.net.UsbDeviceInfo
import com.osakaresearch.spark.net.UsbFlasher
import kotlinx.coroutines.launch
import java.io.File

private enum class FlashMode(val label: String) { WIFI("WiFi OTA"), USB("USB") }

@Composable
fun FirmwareScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val connState by SparkClient.state.collectAsState()

    var mode by remember { mutableStateOf(FlashMode.WIFI) }

    var hostOverride by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("80") }
    var offsetHex by remember { mutableStateOf("0x10000") }
    var selectedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedName by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var log by remember { mutableStateOf(listOf("firmware upload ready.")) }
    var uploading by remember { mutableStateOf(false) }
    var usbDevices by remember { mutableStateOf<List<UsbDeviceInfo>>(emptyList()) }
    var usbRefreshTick by remember { mutableIntStateOf(0) }
    var building by remember { mutableStateOf(false) }

    LaunchedEffect(connState) {
        if (hostOverride.isBlank() && connState is SparkConnState.Connected) {
            val url = (connState as SparkConnState.Connected).url
            val host = url.removePrefix("ws://").substringBefore(":").substringBefore("/")
            hostOverride = host
        }
    }

    LaunchedEffect(mode, usbRefreshTick) {
        if (mode == FlashMode.USB) {
            usbDevices = UsbFlasher.listDevices(context)
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
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Firmware",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            when (mode) {
                FlashMode.WIFI -> "Push a .bin over WiFi. The chip reboots into it."
                FlashMode.USB -> "Flash a .bin over USB-OTG. Chip must be in bootloader mode (auto-reset via DTR/RTS)."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            FlashMode.values().forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { if (!uploading) mode = m },
                    shape = SegmentedButtonDefaults.itemShape(i, FlashMode.values().size),
                ) { Text(m.label) }
            }
        }

        when (mode) {
            FlashMode.WIFI -> WifiTarget(
                host = hostOverride, onHost = { hostOverride = it },
                port = port, onPort = { port = it.filter { c -> c.isDigit() } },
            )
            FlashMode.USB -> UsbTarget(
                devices = usbDevices,
                offsetHex = offsetHex,
                onOffset = { offsetHex = it },
                onRefresh = { usbRefreshTick++ },
            )
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
                    if (selectedUri == null) "no file selected" else selectedName,
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
                            uploading = true
                            progress = 0f
                            when (mode) {
                                FlashMode.WIFI -> {
                                    val host = hostOverride.trim()
                                    val p = port.toIntOrNull() ?: 80
                                    if (host.isBlank()) {
                                        log = log + "need a host"
                                        uploading = false
                                        return@Button
                                    }
                                    log = log + "OTA uploading to $host:$p ..."
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
                                }
                                FlashMode.USB -> {
                                    val offset = parseOffset(offsetHex)
                                    if (offset == null) {
                                        log = log + "bad offset: $offsetHex"
                                        uploading = false
                                        return@Button
                                    }
                                    log = log + "USB flash @ 0x${offset.toString(16)} ..."
                                    scope.launch {
                                        val result = UsbFlasher.flash(
                                            context = context,
                                            firmwareUri = uri,
                                            offset = offset,
                                            onProgress = { done, total ->
                                                progress = if (total > 0) done.toFloat() / total.toFloat() else 0f
                                            },
                                            onLog = { line -> log = log + line },
                                        )
                                        uploading = false
                                        log = log + when (result) {
                                            is FlashResult.Ok -> "ok: ${result.bytes} bytes · ${result.blocks} blocks"
                                            is FlashResult.Fail -> "failed: ${result.reason}"
                                        }
                                        usbRefreshTick++
                                    }
                                }
                            }
                        },
                        enabled = !uploading && selectedUri != null && canFlash(mode, hostOverride, usbDevices),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (uploading) "Working" else "Flash") }
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
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Debug · on-device build", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Compile a Blink .ino with the bundled xtensa toolchain. First run builds core.a (~2–4 min); subsequent runs are fast.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (building) return@Button
                        building = true
                        scope.launch {
                            val ino = File(context.filesDir, "sketches/blink/blink.ino").apply {
                                parentFile?.mkdirs()
                                writeText(BLINK_INO)
                            }
                            log = log + "compiling ${ino.name} ..."
                            val result = SketchBuilder.buildSketch(context, ino) { line ->
                                log = log + line
                            }
                            log = log + when (result) {
                                is SketchBuilder.Result.Ok ->
                                    "ok: ${result.output.name} (${result.output.length()} bytes)"
                                is SketchBuilder.Result.Fail ->
                                    "failed: ${result.reason}"
                            }
                            building = false
                        }
                    },
                    enabled = !building && !uploading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (building) "Building" else "Compile Blink .ino") }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Log",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(log.joinToString("\n")))
                        },
                    ) { Text("Copy") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            val path = LogExport.save(context, "firmware", log.joinToString("\n"))
                            Toast.makeText(
                                context,
                                if (path != null) "saved to $path" else "save failed",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    ) { Text("Save") }
                }
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        log.takeLast(200).forEach { line ->
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
    }
}

private val BLINK_INO = """
void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
}

void loop() {
  digitalWrite(LED_BUILTIN, HIGH);
  delay(500);
  digitalWrite(LED_BUILTIN, LOW);
  delay(500);
}
""".trimIndent()

@Composable
private fun WifiTarget(
    host: String, onHost: (String) -> Unit,
    port: String, onPort: (String) -> Unit,
) {
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
                    value = host,
                    onValueChange = onHost,
                    label = { Text("Host / IP") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = onPort,
                    label = { Text("Port") },
                    modifier = Modifier.width(100.dp),
                )
            }
        }
    }
}

@Composable
private fun UsbTarget(
    devices: List<UsbDeviceInfo>,
    offsetHex: String,
    onOffset: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Device", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onRefresh) { Text("Scan") }
            }
            Spacer(Modifier.height(8.dp))
            if (devices.isEmpty()) {
                Text(
                    "no USB serial device detected. Plug chip + OTG cable, then Scan.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            } else {
                devices.forEach { d ->
                    Text(
                        "${d.name}  ${if (d.hasPermission) "✓" else "(no perm — will prompt)"}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = offsetHex,
                onValueChange = onOffset,
                label = { Text("Flash offset (hex)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "0x0 for merged.bin · 0x10000 for app.bin",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

private fun canFlash(mode: FlashMode, host: String, devices: List<UsbDeviceInfo>): Boolean = when (mode) {
    FlashMode.WIFI -> host.isNotBlank()
    FlashMode.USB -> devices.isNotEmpty()
}

private fun parseOffset(s: String): Int? {
    val t = s.trim().lowercase()
    return try {
        if (t.startsWith("0x")) t.removePrefix("0x").toLong(16).toInt()
        else t.toLong(16).toInt()
    } catch (_: Throwable) { null }
}
