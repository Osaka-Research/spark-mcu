package com.osakaresearch.spark.mcp

import android.content.Context
import android.net.Uri
import com.osakaresearch.spark.compile.McuTarget
import com.osakaresearch.spark.compile.BuildArtifacts
import com.osakaresearch.spark.compile.ArduinoLibraryManager
import com.osakaresearch.spark.ai.SparkDeviceContext
import com.osakaresearch.spark.ai.AgentWorkspace
import com.osakaresearch.spark.ai.AgentDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import com.osakaresearch.spark.compile.SketchBuilder
import com.osakaresearch.spark.compile.Toolchain
import com.osakaresearch.spark.net.FlashBlob
import com.osakaresearch.spark.net.FlashResult
import com.osakaresearch.spark.net.ChipDetection
import com.osakaresearch.spark.net.PrecompiledBlobs
import com.osakaresearch.spark.net.UsbFlasher
import com.osakaresearch.spark.net.UsbSerialMonitor
import com.osakaresearch.spark.net.SparkClient
import com.osakaresearch.spark.net.SparkConnState
import com.osakaresearch.spark.net.Ota
import com.osakaresearch.spark.net.OtaResult
import java.io.File
import java.security.MessageDigest

/**
 * Narrow adapter layer between MCP tool handlers and the real app code.
 * Everything here is suspend-friendly and returns plain data classes so the
 * JSON layer above has nothing to decide.
 *
 * Uses the shared, persistent build cache: `compile_sketch` returns an id, and
 * `flash_firmware` can pass that id back instead of re-shipping the whole
 * .bin as base64. Compile outputs are kept under filesDir across later turns
 * and app restarts.
 */
class McpBridge(private val appCtx: Context, private val onProgress: (String) -> Unit = {}) {
    private val workspace = AgentWorkspace(File(appCtx.filesDir, "assistant"))
    private val wifiCommandMutex = kotlinx.coroutines.sync.Mutex()

    fun readWorkspace() = workspace.read()
    fun writeWorkspace(source: String, target: String, revision: String) = workspace.write(source, target, revision)
    fun patchWorkspace(revision: String, search: String, replacement: String) = workspace.patch(revision, search, replacement)

    suspend fun compileWorkspace(revision: String): Any {
        val data = workspace.read()
        require(data.getString("revision") == revision) { "Working sketch changed; read get_workspace before compiling" }
        return compileSketch(data.getString("source"), data.getString("target"), revision)
    }

    fun libraries(targetId: String): JSONObject {
        val target = supportedTarget(targetId)
        val root = Toolchain.coreDir(appCtx, target)
        val assetCore = if (target == McuTarget.ESP32_S3) "arduino-core-esp32" else "arduino-core-esp8266"
        val names = (File(root, "libraries").listFiles().orEmpty().filter { it.isDirectory }.map { it.name } +
            ArduinoLibraryManager.installed(appCtx))
            .ifEmpty { appCtx.assets.list("toolchain/$assetCore/libraries")?.toList().orEmpty() }
        return JSONObject().put("target", target.id).put("compilerAvailable", target.isAvailable(appCtx))
            .put("libraries", JSONArray(names.sorted()))
            .put("note", "Bundled libraries plus libraries installed on demand from Arduino Library Manager.")
    }

    suspend fun installLibrary(name: String): JSONObject {
        val results = ArduinoLibraryManager.install(appCtx, name)
        return JSONObject().put("ok", true).put("installed", JSONArray().apply {
            results.forEach { put(JSONObject().put("name", it.name).put("version", it.version).put("dependencies", JSONArray(it.dependencies))) }
        }).put("libraryRoot", ArduinoLibraryManager.libraryRoot(appCtx).absolutePath)
    }

    data class DeviceEntry(
        val name: String,
        val vendorId: Int,
        val productId: Int,
        val hasPermission: Boolean,
    )

    data class CompileOk(
        val buildId: String,
        val target: String,
        val binPath: String,
        val sizeBytes: Long,
        val sha256: String,
        val log: String,
        val revision: String,
    )

    data class CompileErr(val reason: String, val log: String, val revision: String)

    data class FlashOk(val bytes: Long, val blocks: Int, val log: String)
    data class FlashErr(val reason: String, val log: String)

    fun listDevices(): List<DeviceEntry> =
        UsbFlasher.listDevices(appCtx).map {
            DeviceEntry(it.name, it.vendorId, it.productId, it.hasPermission)
        }

    suspend fun detectChip(manualBoot: Boolean): ChipDetection =
        UsbFlasher.detectChip(appCtx, manualBoot)

    fun selectTarget(targetId: String): JSONObject {
        val target = supportedTarget(targetId)
        com.osakaresearch.spark.ai.SparkDeviceContext.selectTarget(appCtx, target)
        return JSONObject().put("ok", true).put("target", target.id).put("label", target.label)
            .put("evidence", "user-provided target; not ROM verified")
    }

    /**
     * Writes [inoSource] into a transient sketch dir, builds it with the
     * on-device toolchain, and stashes the resulting firmware.bin under a
     * sha256-keyed build id for later flash.
     */
    suspend fun compileSketch(
        inoSource: String,
        targetId: String,
        expectedRevision: String? = null,
    ): Any {
        val target = supportedTarget(targetId)
        val revision = workspace.write(inoSource, targetId, expectedRevision).getString("revision")
        if (!target.isAvailable(appCtx)) {
            val reason = "target '${target.label}' not available: ${target.detail}"
            workspace.recordBuild(revision, JSONObject().put("ok", false).put("reason", reason))
            return CompileErr(reason, "", revision)
        }
        val sketchDir = File(appCtx.cacheDir, "mcp-sketch-${UUID.randomUUID()}").apply { mkdirs() }
        val inoFile = File(sketchDir, "sketch.ino").apply { writeText(inoSource) }

        val logBuf = StringBuilder()
        val res = try {
            SketchBuilder.buildSketch(appCtx, inoFile, target) { logBuf.appendLine(it); onProgress(it.takeLast(240)) }
        } catch (cancelled: CancellationException) {
            workspace.recordBuild(revision, JSONObject().put("ok", false).put("reason", "Build stopped"))
            throw cancelled
        } catch (error: Exception) {
            workspace.recordBuild(revision, JSONObject().put("ok", false).put("reason", error.message)
                .put("log", logBuf.toString().takeLast(12000)).put("diagnostics", AgentDiagnostics.compiler(logBuf.toString())))
            throw error
        } finally { sketchDir.deleteRecursively() }
        workspace.recordBuild(revision, JSONObject().put("ok", res is SketchBuilder.Result.Ok)
            .put("reason", if (res is SketchBuilder.Result.Fail) res.reason else "Compiled successfully")
            .put("buildId", if (res is SketchBuilder.Result.Ok) res.output.nameWithoutExtension else JSONObject.NULL)
            .put("diagnostics", AgentDiagnostics.compiler(logBuf.toString())).put("log", logBuf.toString().takeLast(12000)))
        return when (res) {
            is SketchBuilder.Result.Fail -> CompileErr(res.reason, logBuf.toString(), revision)
            is SketchBuilder.Result.Ok -> {
                val bin = res.output
                val bytes = bin.readBytes()
                val sha = sha256Hex(bytes)
                val artifact = BuildArtifacts.get(appCtx, bin.nameWithoutExtension)
                    ?: return CompileErr("Compiled firmware could not be retained", logBuf.toString(), revision)
                val cached = artifact.bin
                CompileOk(
                    buildId = artifact.id,
                    target = target.id,
                    binPath = cached.absolutePath,
                    sizeBytes = cached.length(),
                    sha256 = sha,
                    log = logBuf.toString(),
                    revision = revision,
                )
            }
        }
    }

    /**
     * Flash either by [buildId] (from a prior compileSketch) or by inline
     * [binBytes]. Returns [FlashOk] or [FlashErr]; progress + per-step log
     * accumulate into the returned `log` field so the caller sees the full
     * ROM-session transcript.
     */
    suspend fun flashFirmware(
        buildId: String?,
        binBytes: ByteArray?,
        offset: Int,
        targetId: String,
        manualBoot: Boolean,
        expectedDeviceKey: String? = null,
    ): Any {
        val target = supportedTarget(targetId)
        val artifact = if (!buildId.isNullOrBlank()) {
            BuildArtifacts.get(appCtx, buildId) ?: return FlashErr("Unknown or expired buildId; compile the sketch again", "")
        } else null
        if (artifact != null && artifact.target != target) {
            return FlashErr("buildId was compiled for ${artifact.target.label}, not ${target.label}", "")
        }
        val file: File = when {
            artifact != null -> artifact.bin
            binBytes != null && binBytes.isNotEmpty() -> {
                val adhoc = File(appCtx.filesDir, "mcp/adhoc.bin")
                adhoc.parentFile?.mkdirs()
                adhoc.writeBytes(binBytes)
                adhoc
            }
            else -> return FlashErr("neither buildId nor bin provided", "")
        }
        val devices = UsbFlasher.listDevices(appCtx)
        if (expectedDeviceKey != null && devices.singleOrNull()?.deviceKey != expectedDeviceKey) {
            return FlashErr("USB board changed after confirmation; inspect the connection again", "")
        }
        if (devices.size != 1) return FlashErr("Connect exactly one USB serial board before flashing", "")
        val knownRomTarget = com.osakaresearch.spark.ai.SparkDeviceContext.detectedTargetFor(devices.single().deviceKey)
        val manualTarget = com.osakaresearch.spark.ai.SparkDeviceContext.explicitlySelectedTarget(appCtx)
        when {
            knownRomTarget != null && knownRomTarget != target ->
                return FlashErr("Connected chip is ROM-verified as ${knownRomTarget.label}; firmware is for ${target.label}. Recompile for the detected chip.", "")
            knownRomTarget != null -> onProgress("Using current ROM-verified ${knownRomTarget.label} detection")
            manualTarget == target -> onProgress("Using your selected ${target.label} · chip was not queried through ROM")
            else -> when (val detected = detectChip(manualBoot)) {
                is ChipDetection.Fail -> return FlashErr(
                    "ROM detection failed (${detected.reason}). Choose the MCU in chat or Settings → Build target, then continue; no firmware was written.", ""
                )
                is ChipDetection.Found -> if (detected.target != target) {
                    return FlashErr("Connected chip is ${detected.target.label}; firmware is for ${target.label}. Recompile for the connected chip.", "")
                }
            }
        }
        val verifiedDeviceKey = devices.single().deviceKey
        if (UsbFlasher.listDevices(appCtx).singleOrNull()?.deviceKey != verifiedDeviceKey) {
            return FlashErr("USB connection changed during detection; reconnect and confirm the board", "")
        }
        if (artifact != null && sha256Hex(file.readBytes()) != artifact.metadata.optString("sha256")) {
            return FlashErr("Retained firmware checksum changed; recompile before flashing", "")
        }
        val logBuf = StringBuilder()
        val firmwareUri = Uri.fromFile(file)
        val res = if (artifact != null && target == McuTarget.ESP32_S3) {
            // A freshly compiled S3 app needs the matching bootloader and
            // partition images. Mirror the Firmware screen's four-blob path.
            when (val loaded = PrecompiledBlobs.loadS3Bundled(
                appCtx, firmwareUri, Toolchain.coreDir(appCtx, target)
            )) {
                is PrecompiledBlobs.Loaded.Ok -> UsbFlasher.flashPlan(
                    context = appCtx,
                    plan = loaded.plan,
                    onProgress = { done, total -> onProgress("Writing firmware: ${if (total > 0) done * 100 / total else 0}%") },
                    onLog = { logBuf.appendLine(it); onProgress(it.takeLast(240)) },
                    manualBoot = manualBoot,
                    target = target,
                    expectedDeviceKey = verifiedDeviceKey,
                )
                is PrecompiledBlobs.Loaded.Missing ->
                    FlashResult.Fail("bundled S3 image missing: ${loaded.names.joinToString()}")
                is PrecompiledBlobs.Loaded.Error -> FlashResult.Fail(loaded.reason)
            }
        } else UsbFlasher.flash(
            context = appCtx,
            firmwareUri = firmwareUri,
            offset = offset,
            onProgress = { done, total -> onProgress("Writing firmware: ${if (total > 0) done * 100 / total else 0}%") },
            onLog = { logBuf.appendLine(it); onProgress(it.takeLast(240)) },
            manualBoot = manualBoot,
            target = target,
            expectedDeviceKey = verifiedDeviceKey,
        )
        return when (res) {
            is FlashResult.Ok -> FlashOk(res.bytes, res.blocks, logBuf.toString())
            is FlashResult.Fail -> FlashErr(res.reason, logBuf.toString())
        }
    }

    suspend fun flashWifiFirmware(buildId: String, targetId: String, expectedUrl: String): JSONObject {
        val target = supportedTarget(targetId)
        val artifact = BuildArtifacts.get(appCtx, buildId) ?: return JSONObject().put("ok", false).put("reason", "Unknown or expired buildId; compile again")
        if (artifact.target != target) return JSONObject().put("ok", false).put("reason", "Build target does not match the requested target")
        val connected = SparkClient.state.value as? SparkConnState.Connected
            ?: return JSONObject().put("ok", false).put("reason", "Wi-Fi board disconnected before upload")
        if (connected.url != expectedUrl) return JSONObject().put("ok", false).put("reason", "Wi-Fi board changed after confirmation")
        val hello = SparkClient.latestBoardHello ?: return JSONObject().put("ok", false).put("reason", "Spark firmware identity is unavailable")
        val board = hello.optString("board")
        val matches = when (target) {
            McuTarget.ESP32_S3 -> board == "esp32-s3"
            McuTarget.ESP8266_NODEMCU -> board == "esp8266"
            else -> false
        }
        if (!matches) return JSONObject().put("ok", false).put("reason", "Connected Spark firmware reports '$board'; build is for ${target.label}")
        if (sha256Hex(artifact.bin.readBytes()) != artifact.metadata.optString("sha256")) {
            return JSONObject().put("ok", false).put("reason", "Retained firmware checksum changed; recompile before upload")
        }
        val host = Uri.parse(connected.url).host
            ?: return JSONObject().put("ok", false).put("reason", "Could not read host from the Wi-Fi board connection")
        val uri = Uri.fromFile(artifact.bin)
        return when (val result = Ota.upload(appCtx, host, 80, uri) { done, total ->
            onProgress("Wi-Fi firmware upload: ${if (total > 0) done * 100 / total else 0}%")
        }) {
            is OtaResult.Fail -> JSONObject().put("ok", false).put("reason", result.reason)
            is OtaResult.Ok -> {
                delay(1200)
                var hello: JSONObject? = null
                repeat(5) {
                    if (hello == null) {
                        SparkClient.connect(host, 81)
                        delay(1500)
                        val state = SparkClient.state.value as? SparkConnState.Connected
                        val found = SparkClient.latestBoardHello
                        if (state != null && state.url == connected.url && found?.optString("board") == board) hello = found
                    }
                }
                val ping = if (hello != null) runCatching { wifiCommand(JSONObject().put("cmd", "ping"), "pong") }.getOrNull() else null
                JSONObject().put("ok", true).put("bytes", result.bytes).put("transport", "wifi")
                    .put("response", result.response.take(1000)).put("runtimeVerified", ping != null)
                    .put("boardHello", hello ?: JSONObject.NULL).put("ping", ping ?: JSONObject.NULL)
                    .put("next", if (ping != null) "The board reconnected and answered ping after OTA." else "OTA upload succeeded, but the board has not reconnected yet. Reconnect to its Wi-Fi board and ping it to verify runtime.")
            }
        }
    }

    /**
     * Multi-blob flash — sends a sequence of blobs at distinct offsets inside
     * one ROM session. The right entry point for ESP32-family targets that
     * need bootloader + partitions + boot_app0 + app across four offsets.
     * Degenerates cleanly to the single-blob case.
     */
    suspend fun flashPlan(
        blobs: List<Triple<String, Int, ByteArray>>,
        targetId: String,
        manualBoot: Boolean,
    ): Any {
        if (blobs.isEmpty()) return FlashErr("empty plan", "")
        val target = supportedTarget(targetId)
        val plan = blobs.map { (name, offset, data) -> FlashBlob(name, offset, data) }
        val logBuf = StringBuilder()
        val res = UsbFlasher.flashPlan(
            context = appCtx,
            plan = plan,
            onProgress = { _, _ -> },
            onLog = { logBuf.appendLine(it) },
            manualBoot = manualBoot,
            target = target,
        )
        return when (res) {
            is FlashResult.Ok -> FlashOk(res.bytes, res.blocks, logBuf.toString())
            is FlashResult.Fail -> FlashErr(res.reason, logBuf.toString())
        }
    }

    // --- serial tail --------------------------------------------------------
    // UI, MCP and the assistant share the same cursor-addressable history.
    suspend fun startSerial(baud: Int): Boolean = UsbSerialMonitor.start(appCtx, baud)

    suspend fun writeSerial(text: String, newline: Boolean) = UsbSerialMonitor.write(appCtx, text, newline)

    suspend fun resetBoard(baud: Int): JSONObject {
        val device = UsbFlasher.listDevices(appCtx).singleOrNull()
            ?: error("Connect exactly one USB board before restarting it")
        val knownTarget = com.osakaresearch.spark.ai.SparkDeviceContext.detectedTargetFor(device.deviceKey)
            ?: com.osakaresearch.spark.ai.SparkDeviceContext.explicitlySelectedTarget(appCtx)
        require(knownTarget != null) { "Tell Spark which MCU is connected before restarting it" }
        check(startSerial(baud)) { "Could not open the monitor before reset" }
        val cursor = UsbSerialMonitor.resetBoard(appCtx)
        return JSONObject().put("ok", true).put("cursor", cursor).put("resetPulseSent", true)
            .put("note", "Read serial lines since this cursor to check startup. A reset pulse does not prove the sketch started. Native USB may re-enumerate; refresh context and reopen if needed.")
    }

    /** Sample a complete window, excluding old output and monitor UI banners. */
    suspend fun captureSerial(baud: Int, durationMs: Int): JSONObject {
        require(durationMs in 500..10000) { "Capture duration must be 500–10000 ms" }
        val device = UsbFlasher.listDevices(appCtx).singleOrNull()
            ?: return JSONObject().put("ok", false).put("reason", "Connect exactly one USB serial board")
        val cursor = UsbSerialMonitor.history.tail(1).cursor
        if (!startSerial(baud)) return JSONObject().put("ok", false)
            .put("reason", (UsbSerialMonitor.state.value as? UsbSerialMonitor.State.Error)?.reason ?: "Could not open monitor")
        val captureId = UsbSerialMonitor.captureId
        onProgress("Observing fresh serial output for ${durationMs / 1000.0}s at $baud baud…")
        delay(durationMs.toLong())
        val batch = UsbSerialMonitor.history.read(cursor, 200)
        val sameDevice = UsbFlasher.listDevices(appCtx).singleOrNull()?.deviceKey == device.deviceKey &&
            UsbSerialMonitor.captureId == captureId && UsbSerialMonitor.state.value is UsbSerialMonitor.State.Running
        return JSONObject().put("ok", sameDevice).put("baud", baud).put("durationMs", durationMs)
            .put("captureId", captureId).put("usbDeviceKey", device.deviceKey).put("cursor", batch.cursor)
            .put("lines", JSONArray(batch.lines)).put("lineCount", batch.lines.size)
            .put("truncated", batch.truncated || UsbSerialMonitor.history.tail(1).cursor > batch.cursor)
            .put("signals", AgentDiagnostics.serial(batch.lines))
            .put("reason", if (!sameDevice) "USB connection or monitor changed during capture; inspect context again"
                else if (batch.lines.isEmpty()) "No new serial output observed; this does not prove a fault or successful execution"
                else "Fresh output captured; compare it with the expected behavior")
    }

    fun stopSerial() = UsbSerialMonitor.stop()

    data class SerialBatch(val cursor: Long, val lines: List<String>, val truncated: Boolean)

    suspend fun readSerial(sinceCursor: Long, maxLines: Int, waitMillis: Int = 0): SerialBatch {
        val deadline = System.nanoTime() + waitMillis.coerceIn(0, 5000) * 1_000_000L
        var batch = UsbSerialMonitor.history.read(sinceCursor, maxLines)
        while (batch.lines.isEmpty() && System.nanoTime() < deadline &&
            UsbSerialMonitor.state.value is UsbSerialMonitor.State.Running) {
            delay(100)
            batch = UsbSerialMonitor.history.read(sinceCursor, maxLines)
        }
        return SerialBatch(batch.cursor, batch.lines, batch.truncated)
    }

    fun deviceContext(): JSONObject = SparkDeviceContext.snapshot(appCtx)

    /** Send one known Spark firmware command and wait for its matching event. */
    suspend fun wifiCommand(command: JSONObject, expectedEvent: String, match: (JSONObject) -> Boolean = { true }): JSONObject =
        wifiCommandMutex.withLock {
            check(SparkClient.state.value is SparkConnState.Connected) { "Connect to the Spark MCU board over Wi-Fi first" }
            check(SparkClient.latestBoardHello != null) { "The connected board has not sent its Spark firmware hello event" }
            withTimeout(4000) {
                coroutineScope {
                    val response = async(start = CoroutineStart.UNDISPATCHED) {
                        SparkClient.incoming.first { raw ->
                            val event = runCatching { JSONObject(raw) }.getOrNull()
                            event != null && event.optString("evt") == expectedEvent && match(event)
                        }
                    }
                    if (!SparkClient.send(command.toString())) {
                        response.cancel()
                        error("The Wi-Fi connection closed before the command was sent")
                    }
                    JSONObject(response.await())
                }
            }
        }

    fun buildInfo(id: String): JSONObject = BuildArtifacts.get(appCtx, id)?.metadata
        ?: throw ToolError(-32602, "Unknown buildId; use get_device_context to list retained builds")

    private fun supportedTarget(id: String): McuTarget =
        listOf(McuTarget.ESP8266_NODEMCU, McuTarget.ESP32_S3).firstOrNull { it.id == id }
            ?: throw ToolError(-32602, "Specify target explicitly: esp8266-nodemcu or esp32-s3")

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

}
