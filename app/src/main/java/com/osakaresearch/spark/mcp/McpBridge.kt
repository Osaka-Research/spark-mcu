package com.osakaresearch.spark.mcp

import android.content.Context
import android.net.Uri
import com.osakaresearch.spark.compile.McuTarget
import com.osakaresearch.spark.compile.SketchBuilder
import com.osakaresearch.spark.compile.Toolchain
import com.osakaresearch.spark.net.FlashBlob
import com.osakaresearch.spark.net.FlashResult
import com.osakaresearch.spark.net.PrecompiledBlobs
import com.osakaresearch.spark.net.UsbFlasher
import com.osakaresearch.spark.net.UsbSerialMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Narrow adapter layer between MCP tool handlers and the real app code.
 * Everything here is suspend-friendly and returns plain data classes so the
 * JSON layer above has nothing to decide.
 *
 * Also owns a short-lived build cache: `compile_sketch` returns an id, and
 * `flash_firmware` can pass that id back instead of re-shipping the whole
 * .bin as base64. Compile outputs are kept under filesDir and evicted on
 * next compile.
 */
class McpBridge(private val appCtx: Context) {

    data class DeviceEntry(
        val name: String,
        val vendorId: Int,
        val productId: Int,
        val hasPermission: Boolean,
    )

    data class CompileOk(
        val buildId: String,
        val binPath: String,
        val sizeBytes: Long,
        val sha256: String,
        val log: String,
    )

    data class CompileErr(val reason: String, val log: String)

    data class FlashOk(val bytes: Long, val blocks: Int, val log: String)
    data class FlashErr(val reason: String, val log: String)

    fun listDevices(): List<DeviceEntry> =
        UsbFlasher.listDevices(appCtx).map {
            DeviceEntry(it.name, it.vendorId, it.productId, it.hasPermission)
        }

    /**
     * Writes [inoSource] into a transient sketch dir, builds it with the
     * on-device toolchain, and stashes the resulting firmware.bin under a
     * sha256-keyed build id for later flash.
     */
    suspend fun compileSketch(
        inoSource: String,
        targetId: String,
    ): Any {
        val target = McuTarget.byId(targetId)
        if (!target.isAvailable(appCtx)) {
            return CompileErr("target '${target.label}' not available: ${target.detail}", "")
        }
        val sketchDir = File(appCtx.filesDir, "mcp/sketch").apply {
            deleteRecursively(); mkdirs()
        }
        val inoFile = File(sketchDir, "sketch.ino").apply { writeText(inoSource) }

        val logBuf = StringBuilder()
        val res = SketchBuilder.buildSketch(appCtx, inoFile, target) { logBuf.appendLine(it) }
        return when (res) {
            is SketchBuilder.Result.Fail -> CompileErr(res.reason, logBuf.toString())
            is SketchBuilder.Result.Ok -> {
                val bin = res.output
                val bytes = bin.readBytes()
                val sha = sha256Hex(bytes)
                val cacheDir = File(appCtx.filesDir, "mcp/builds").apply { mkdirs() }
                val cached = File(cacheDir, "$sha.bin").also { bin.copyTo(it, overwrite = true) }
                builds[sha] = BuildArtifact(cached, target)
                CompileOk(
                    buildId = sha,
                    binPath = cached.absolutePath,
                    sizeBytes = cached.length(),
                    sha256 = sha,
                    log = logBuf.toString(),
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
    ): Any {
        val target = McuTarget.byId(targetId)
        val artifact = if (!buildId.isNullOrBlank()) {
            builds[buildId] ?: return FlashErr("unknown buildId $buildId", "")
        } else null
        if (artifact != null && artifact.target != target) {
            return FlashErr("buildId was compiled for ${artifact.target.label}, not ${target.label}", "")
        }
        val file: File = when {
            artifact != null -> artifact.file
            binBytes != null && binBytes.isNotEmpty() -> {
                val adhoc = File(appCtx.filesDir, "mcp/adhoc.bin")
                adhoc.parentFile?.mkdirs()
                adhoc.writeBytes(binBytes)
                adhoc
            }
            else -> return FlashErr("neither buildId nor bin provided", "")
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
                    onProgress = { _, _ -> },
                    onLog = { logBuf.appendLine(it) },
                    manualBoot = manualBoot,
                    target = target,
                )
                is PrecompiledBlobs.Loaded.Missing ->
                    FlashResult.Fail("bundled S3 image missing: ${loaded.names.joinToString()}")
                is PrecompiledBlobs.Loaded.Error -> FlashResult.Fail(loaded.reason)
            }
        } else UsbFlasher.flash(
            context = appCtx,
            firmwareUri = firmwareUri,
            offset = offset,
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
        val target = McuTarget.byId(targetId)
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
    // The existing UsbSerialMonitor is a hot SharedFlow — for a pull-based
    // "give me lines since cursor" API we attach a collector when started
    // and buffer into a bounded queue. Callers advance a cursor.

    private val serialLines = ConcurrentLinkedQueue<Pair<Long, String>>()
    @Volatile private var serialSeq: Long = 0
    private var serialScope: CoroutineScope? = null
    private var serialJob: Job? = null

    suspend fun startSerial(baud: Int): Boolean {
        stopSerial()
        val ok = UsbSerialMonitor.start(appCtx, baud)
        if (!ok) return false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { serialScope = it }
        serialJob = scope.launch {
            UsbSerialMonitor.lines.onEach { line ->
                val seq = ++serialSeq
                serialLines.add(seq to line)
                while (serialLines.size > SERIAL_BUFFER) serialLines.poll()
            }.collect {}
        }
        return true
    }

    fun stopSerial() {
        serialJob?.cancel(); serialJob = null
        serialScope = null
        UsbSerialMonitor.stop()
    }

    data class SerialBatch(val cursor: Long, val lines: List<String>)

    fun readSerial(sinceCursor: Long, maxLines: Int): SerialBatch {
        val out = ArrayList<String>()
        var last = sinceCursor
        for ((seq, line) in serialLines) {
            if (seq <= sinceCursor) continue
            out.add(line)
            last = seq
            if (out.size >= maxLines) break
        }
        return SerialBatch(cursor = last, lines = out)
    }

    private data class BuildArtifact(val file: File, val target: McuTarget)
    private val builds = HashMap<String, BuildArtifact>()

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val SERIAL_BUFFER = 4000
    }
}
