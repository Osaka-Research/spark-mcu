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
import androidx.compose.material3.Switch
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
import com.osakaresearch.spark.compile.McuTarget
import com.osakaresearch.spark.compile.SketchBuilder
import com.osakaresearch.spark.compile.Toolchain
import com.osakaresearch.spark.net.ChipDetection
import com.osakaresearch.spark.net.FlashResult
import com.osakaresearch.spark.net.Ota
import com.osakaresearch.spark.net.OtaResult
import com.osakaresearch.spark.net.PrecompiledBlobs
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
    // 0x0 because the on-device build now emits a full eboot + app image (via
    // Elf2Image). The old 0x10000 default was for raw esptool-style app bins.
    var offsetHex by remember { mutableStateOf("0x0") }
    var selectedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedName by remember { mutableStateOf("") }
    var compiledS3Selected by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var log by remember { mutableStateOf(listOf("firmware upload ready.")) }
    var uploading by remember { mutableStateOf(false) }
    var usbDevices by remember { mutableStateOf<List<UsbDeviceInfo>>(emptyList()) }
    var usbRefreshTick by remember { mutableIntStateOf(0) }
    var manualBoot by remember { mutableStateOf(false) }
    var building by remember { mutableStateOf(false) }
    var detectingChip by remember { mutableStateOf(false) }
    var sketchUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var sketchName by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(McuTarget.Default) }
    var blinkFolderUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var blinkFolderName by remember { mutableStateOf("") }

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
            if (usbDevices.size == 1 && !building && !uploading) {
                detectingChip = true
                try {
                    when (val result = UsbFlasher.detectChip(context)) {
                        is ChipDetection.Found -> {
                            target = result.target
                            compiledS3Selected = false
                            offsetHex = if (target == McuTarget.ESP32_S3) "0x10000" else "0x0"
                            log = log + "detected ${target.label} from chip ROM"
                        }
                        is ChipDetection.Fail -> log = log + "automatic chip detection: ${result.reason}"
                    }
                } finally {
                    detectingChip = false
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        OpenDocumentInDownloads()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            compiledS3Selected = false
            selectedName = uri.lastPathSegment?.substringAfterLast('/') ?: "firmware.bin"
            log = log + "picked: $selectedName"
        }
    }

    val sketchPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            sketchUri = uri
            sketchName = resolveSketchName(context, uri)
            log = log + "sketch: $sketchName"
        }
    }

    // Folder picker: user selects a sketch folder (foo/ with foo.ino + optional
    // siblings). We stash the tree Uri and defer the actual copy to build time
    // so the on-disk state always reflects what's being built.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            sketchUri = uri
            sketchName = androidx.documentfile.provider.DocumentFile
                .fromTreeUri(context, uri)?.name ?: "sketch/"
            log = log + "sketch folder: $sketchName"
        }
    }

    // Separate tree picker for the precompiled S3 Blink folder. Kept distinct
    // from folderPicker so switching between compile- and precompiled-flows
    // doesn't clobber either selection.
    val blinkFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            blinkFolderUri = uri
            blinkFolderName = androidx.documentfile.provider.DocumentFile
                .fromTreeUri(context, uri)?.name ?: "blink/"
            log = log + "blink folder: $blinkFolderName"
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

        // Board picker lives at the top because it gates which flash paths
        // appear below (precompiled Blink for S3, on-device compile only
        // where a toolchain is bundled). Users were previously tapping Compile
        // with target=S3 because the board selector was buried inside the
        // compile card.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            McuTarget.values().forEachIndexed { i, t ->
                SegmentedButton(
                    selected = target == t,
                    onClick = {
                        if (!building && !uploading && target != t) {
                            target = t
                            compiledS3Selected = false
                            offsetHex = if (t == McuTarget.ESP32_S3) "0x10000" else "0x0"
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, McuTarget.values().size),
                ) { Text(t.label) }
            }
        }
        if (mode == FlashMode.USB) {
            OutlinedButton(
                enabled = !building && !uploading && !detectingChip && usbDevices.isNotEmpty(),
                onClick = {
                    detectingChip = true
                    scope.launch {
                        try {
                            when (val result = UsbFlasher.detectChip(context, manualBoot)) {
                                is ChipDetection.Found -> {
                                    target = result.target
                                    compiledS3Selected = false
                                    offsetHex = if (target == McuTarget.ESP32_S3) "0x10000" else "0x0"
                                    log = log + "detected ${target.label} from chip ROM"
                                }
                                is ChipDetection.Fail -> log = log + "chip detection: ${result.reason}"
                            }
                        } finally {
                            detectingChip = false
                        }
                    }
                },
            ) { Text(if (detectingChip) "Detecting chip…" else "Detect USB chip") }
        }
        Text(
            if (target.isAvailable(context)) target.detail
            else "${target.detail} · on-device compiler assets not bundled",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (target.isAvailable(context)) 0.6f else 0.9f,
            ),
        )

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
                manualBoot = manualBoot,
                onManualBoot = { manualBoot = it },
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
                                    log = log + if (target == McuTarget.ESP32_S3 && compiledS3Selected)
                                        "USB flash S3 bootloader + partitions + app ..."
                                    else "USB flash @ 0x${offset.toString(16)} ..."
                                    scope.launch {
                                        val result = if (target == McuTarget.ESP32_S3 && compiledS3Selected) {
                                            when (val loaded = PrecompiledBlobs.loadS3Bundled(
                                                context, uri, Toolchain.coreDir(context, target)
                                            )) {
                                                is PrecompiledBlobs.Loaded.Ok -> UsbFlasher.flashPlan(
                                                    context = context,
                                                    plan = loaded.plan,
                                                    onProgress = { done, total ->
                                                        progress = if (total > 0) done.toFloat() / total.toFloat() else 0f
                                                    },
                                                    onLog = { line -> log = log + line },
                                                    manualBoot = manualBoot,
                                                    target = target,
                                                )
                                                is PrecompiledBlobs.Loaded.Missing -> FlashResult.Fail(
                                                    "bundled S3 image missing: ${loaded.names.joinToString()}"
                                                )
                                                is PrecompiledBlobs.Loaded.Error -> FlashResult.Fail(loaded.reason)
                                            }
                                        } else UsbFlasher.flash(
                                            context = context,
                                            firmwareUri = uri,
                                            offset = offset,
                                            onProgress = { done, total ->
                                                progress = if (total > 0) done.toFloat() / total.toFloat() else 0f
                                            },
                                            onLog = { line -> log = log + line },
                                            manualBoot = manualBoot,
                                            target = target,
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

        // Keep the precompiled recovery path for builds without the S3
        // compiler. Normal app builds use the sketch compiler below.
        if (mode == FlashMode.USB && target == McuTarget.ESP32_S3 && !target.isAvailable(context)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Precompiled Blink (S3)", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Pick a folder with arduino-cli outputs: bootloader.bin, partitions.bin, boot_app0.bin, firmware.bin. Flashes all four in one session.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(10.dp))

                    // Inline cheat sheet so the user has the escape hatch in
                    // view before they tap Flash. The automatic DTR/RTS reset
                    // doesn't work on every S3 board (some wire BOOT/RST to
                    // separate buttons with no reset circuit, others have
                    // firmware that hijacks the USB-JTAG peripheral), so it's
                    // cheap insurance to always show the manual sequence.
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                "If SYNC fails: enter download mode manually",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "1.  Hold  BOOT  (or IO0)\n" +
                                    "2.  Tap   RST   (keep holding BOOT)\n" +
                                    "3.  Release BOOT\n" +
                                    "4.  Tap  Flash blink  below within ~3 s",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))

                    Text(
                        if (blinkFolderUri == null) "no folder selected" else blinkFolderName,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        OutlinedButton(
                            onClick = { blinkFolderPicker.launch(null) },
                            enabled = !uploading,
                            modifier = Modifier.weight(1f),
                        ) { Text("Pick folder") }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = {
                                val uri = blinkFolderUri ?: return@Button
                                uploading = true
                                progress = 0f
                                scope.launch {
                                    log = log + "loading blobs from $blinkFolderName ..."
                                    when (val loaded = PrecompiledBlobs.loadS3Blink(context, uri)) {
                                        is PrecompiledBlobs.Loaded.Missing -> {
                                            log = log + "missing: ${loaded.names.joinToString()}"
                                            uploading = false
                                        }
                                        is PrecompiledBlobs.Loaded.Error -> {
                                            log = log + "load failed: ${loaded.reason}"
                                            uploading = false
                                        }
                                        is PrecompiledBlobs.Loaded.Ok -> {
                                            loaded.plan.forEach { b ->
                                                log = log + "  ${b.name} @ 0x${b.offset.toString(16)} (${b.data.size} bytes)"
                                            }
                                            val result = UsbFlasher.flashPlan(
                                                context = context,
                                                plan = loaded.plan,
                                                onProgress = { done, total ->
                                                    progress = if (total > 0) done.toFloat() / total.toFloat() else 0f
                                                },
                                                onLog = { line -> log = log + line },
                                                manualBoot = manualBoot,
                                                target = target,
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
                            enabled = !uploading && blinkFolderUri != null && usbDevices.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) { Text(if (uploading) "Working" else "Flash blink") }
                    }
                }
            }
        }

        // The S3 build card appears once the optional compiler and core assets
        // have been packaged into this APK.
        if (target.isAvailable(context)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Sketch · on-device build", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (target == McuTarget.ESP32_S3)
                            "Compile an .ino for ESP32-S3. The USB Flash button uses the bundled bootloader, partition images, and new app image together."
                        else
                            "Compile an .ino with the bundled xtensa toolchain. First run builds core.a (~2–4 min); subsequent runs are fast. Libraries shipped with the core (ESP8266WiFi, Servo, SPI, Wire, …) are auto-resolved by #include.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(10.dp))

                    Text(
                    if (sketchUri == null) "no sketch selected (demo Blink will be used)" else sketchName,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    OutlinedButton(
                        onClick = { sketchPicker.launch(arrayOf("*/*")) },
                        enabled = !building,
                        modifier = Modifier.weight(1f),
                    ) { Text("Pick .ino") }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = { folderPicker.launch(null) },
                        enabled = !building,
                        modifier = Modifier.weight(1f),
                    ) { Text("Pick folder") }
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    Button(
                        onClick = {
                            if (building) return@Button
                            building = true
                            compiledS3Selected = false
                            scope.launch {
                                val picked = sketchUri
                                val ino: File = if (picked != null) {
                                    val ok = materializeSketch(context, picked, sketchName)
                                    if (ok == null) {
                                        log = log + "failed to read picked sketch"
                                        building = false
                                        return@launch
                                    }
                                    ok
                                } else {
                                    File(context.filesDir, "sketches/blink/blink.ino").apply {
                                        parentFile?.mkdirs()
                                        writeText(BLINK_INO)
                                    }
                                }
                                val slug = ino.nameWithoutExtension
                                log = log + "compiling ${ino.name} for ${target.label} ..."
                                val result = SketchBuilder.buildSketch(context, ino, target) { line ->
                                    log = log + line
                                }
                                log = log + when (result) {
                                    is SketchBuilder.Result.Ok ->
                                        "ok: ${result.output.name} (${result.output.length()} bytes)"
                                    is SketchBuilder.Result.Fail ->
                                        "failed: ${result.reason}"
                                }
                                // Persist the fresh bin to Downloads/ so it survives the
                                // next build's work-dir wipe, and auto-select it for flashing.
                                if (result is SketchBuilder.Result.Ok) {
                                    val exported = BinExport.save(context, slug, result.output.readBytes())
                                    if (exported != null) {
                                        selectedUri = exported.uri
                                        selectedName = exported.displayName
                                        compiledS3Selected = target == McuTarget.ESP32_S3
                                        log = log + "saved: ${exported.humanPath}"
                                    } else {
                                        log = log + "bin export failed"
                                    }
                                    val elfExport = BinExport.saveAs(context, slug, "elf", result.elf.readBytes())
                                    log = log + (elfExport?.let { "saved: ${it.humanPath}" } ?: "elf export failed")
                                }
                                building = false
                            }
                        },
                        enabled = !building && !uploading,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (building) "Building" else "Compile") }
                }
            }
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

/**
 * [ActivityResultContracts.OpenDocument] variant that hints the system picker to
 * open rooted at Downloads/. The hint is honoured on API 26+ picker UIs that
 * respect [android.provider.DocumentsContract.EXTRA_INITIAL_URI]; older pickers
 * ignore it and fall back to their own default root.
 */
private class OpenDocumentInDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: android.content.Context, input: Array<String>): android.content.Intent {
        val intent = super.createIntent(context, input)
        val downloadsUri = android.provider.DocumentsContract.buildRootUri(
            "com.android.providers.downloads.documents",
            "downloads",
        )
        intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, downloadsUri)
        return intent
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
    manualBoot: Boolean,
    onManualBoot: (Boolean) -> Unit,
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

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Manual boot mode", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        "Skips DTR/RTS auto-reset. Use when your board has no auto-reset circuit.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(checked = manualBoot, onCheckedChange = onManualBoot)
            }

            if (manualBoot) {
                Spacer(Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Enter download mode:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "1.  Hold  FLASH\n" +
                                "2.  Tap  RST  (keep holding FLASH)\n" +
                                "3.  Release  FLASH\n" +
                                "4.  Within ~3 s, tap  Flash  below.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
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

/**
 * Pulls the user-visible display name out of a SAF Uri. SAF Uris don't carry
 * a filesystem path — only a documentId — so ContentResolver.query is the
 * only reliable way to recover the original filename for logging and for
 * naming the on-disk copy under `sketches/<slug>/`.
 */
private fun resolveSketchName(context: android.content.Context, uri: android.net.Uri): String {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && it.moveToFirst()) {
            val name = it.getString(idx)
            if (!name.isNullOrBlank()) return name
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "sketch.ino"
}

/**
 * Copies the SAF-picked sketch (either a single-file `.ino` Uri or a tree
 * Uri from [ActivityResultContracts.OpenDocumentTree]) into the app's
 * private sketches dir and returns the primary `.ino` file to feed the
 * compile pipeline.
 *
 * Tree copies preserve layout: sibling `.cpp/.h`, a nested `src/` directory,
 * etc. all land next to the primary `.ino` so [SketchBuilder]'s multi-file
 * scan picks them up. The primary `.ino` is resolved by matching the folder
 * basename (Arduino convention: `Blink/Blink.ino`), falling back to the
 * first `.ino` alphabetically if no match.
 *
 * Non-`.ino` single-file picks are accepted — the user may paste a `.cpp`
 * or forget the extension — but we always name the on-disk copy with
 * `.ino` so `preprocessSketch` recognises it.
 */
private fun materializeSketch(
    context: android.content.Context,
    uri: android.net.Uri,
    displayName: String,
): File? = runCatching {
    val isTree = android.provider.DocumentsContract.isTreeUri(uri)
    if (isTree) materializeTree(context, uri, displayName)
    else materializeSingle(context, uri, displayName)
}.getOrNull()

private fun materializeSingle(
    context: android.content.Context,
    uri: android.net.Uri,
    displayName: String,
): File? {
    val baseRaw = displayName.substringBeforeLast('.', displayName).ifBlank { "sketch" }
    val slug = slugify(baseRaw)
    val dir = File(context.filesDir, "sketches/$slug").apply {
        deleteRecursively()
        mkdirs()
    }
    val out = File(dir, "$slug.ino")
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { input.copyTo(it) }
    } ?: return null
    return out
}

private fun materializeTree(
    context: android.content.Context,
    tree: android.net.Uri,
    displayName: String,
): File? {
    val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree) ?: return null
    val folderName = doc.name ?: displayName.ifBlank { "sketch" }
    val slug = slugify(folderName)
    val dir = File(context.filesDir, "sketches/$slug").apply {
        deleteRecursively()
        mkdirs()
    }
    copyDocTree(context, doc, dir)

    val inos = dir.listFiles()
        ?.filter { it.isFile && it.extension.equals("ino", ignoreCase = true) }
        ?.sortedBy { it.name }
        .orEmpty()
    if (inos.isEmpty()) return null

    // Arduino convention: sketch folder and primary .ino share a name.
    // Match case-insensitively since SAF display names are user-controlled.
    val primary = inos.firstOrNull { it.nameWithoutExtension.equals(folderName, ignoreCase = true) }
        ?: inos.first()
    return primary
}

private fun copyDocTree(
    context: android.content.Context,
    from: androidx.documentfile.provider.DocumentFile,
    to: File,
) {
    for (child in from.listFiles()) {
        val childName = child.name ?: continue
        val target = File(to, childName)
        if (child.isDirectory) {
            target.mkdirs()
            copyDocTree(context, child, target)
        } else {
            runCatching {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }
}

private fun slugify(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').ifBlank { "sketch" }
