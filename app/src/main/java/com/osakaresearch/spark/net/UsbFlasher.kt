package com.osakaresearch.spark.net

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.osakaresearch.spark.compile.McuTarget
import com.osakaresearch.spark.ai.SparkDeviceContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

sealed class FlashResult {
    data class Ok(val bytes: Long, val blocks: Int) : FlashResult()
    data class Fail(val reason: String) : FlashResult()
}

sealed class ChipDetection {
    data class Found(val target: McuTarget) : ChipDetection()
    data class Fail(val reason: String) : ChipDetection()
}

data class UsbDeviceInfo(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val hasPermission: Boolean,
    val deviceKey: String,
)

/** One blob in a multi-image flash plan (e.g. bootloader + partitions + app). */
data class FlashBlob(val name: String, val offset: Int, val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FlashBlob) return false
        return name == other.name && offset == other.offset && data.contentEquals(other.data)
    }
    override fun hashCode(): Int =
        name.hashCode() * 31 * 31 + offset * 31 + data.contentHashCode()
}

object UsbFlasher {
    internal val operationMutex = Mutex()
    private const val ACTION_USB_PERMISSION = "com.osakaresearch.spark.USB_PERMISSION"

    private const val BAUD = 115200
    private const val BLOCK_SIZE = 1024
    private const val SYNC_ATTEMPTS = 7

    private const val CMD_FLASH_BEGIN: Byte = 0x02
    private const val CMD_FLASH_DATA: Byte = 0x03
    private const val CMD_FLASH_END: Byte = 0x04
    private const val CMD_SYNC: Byte = 0x08
    private const val CMD_READ_REG: Byte = 0x0A
    private const val CMD_GET_SECURITY_INFO: Byte = 0x14

    // Espressif's VID for the ESP32-S3 (and S2/C3/C6/H2) built-in USB-JTAG-serial
    // interface. When the device is attached via this interface rather than an
    // external bridge (CP2102=0x10C4, CH340=0x1A86), DTR/RTS semantics change
    // — see s3UsbJtagReset.
    private const val VID_ESPRESSIF_USB_JTAG = 0x303A

    fun listDevices(context: Context): List<UsbDeviceInfo> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        return drivers.map { d ->
            val dev = d.device
            UsbDeviceInfo(
                name = dev.productName ?: "${chipName(d)} (${hex4(dev.vendorId)}:${hex4(dev.productId)})",
                vendorId = dev.vendorId,
                productId = dev.productId,
                hasPermission = manager.hasPermission(dev),
                deviceKey = "${dev.deviceName}:${dev.deviceId}:${dev.vendorId}:${dev.productId}",
            )
        }
    }

    /** Query the ROM instead of guessing the chip from a shared USB adapter ID. */
    suspend fun detectChip(context: Context, manualBoot: Boolean = false): ChipDetection = operationMutex.withLock {
        val devices = listDevices(context)
        if (devices.size != 1) return@withLock ChipDetection.Fail("Connect exactly one supported USB serial board before detecting the chip")
        UsbSerialMonitor.stop()
        val result = detectChipInternal(context, manualBoot)
        SparkDeviceContext.recordDetection(context, devices.single().deviceKey, result)
        result
    }

    private suspend fun detectChipInternal(context: Context, manualBoot: Boolean): ChipDetection =
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
                ?: return@withContext ChipDetection.Fail("no USB serial device found")
            if (!manager.hasPermission(driver.device) && !requestPermission(context, manager, driver)) {
                return@withContext ChipDetection.Fail("USB permission denied")
            }
            val connection = manager.openDevice(driver.device)
                ?: return@withContext ChipDetection.Fail("cannot open USB device")
            val port = driver.ports.firstOrNull()
                ?: return@withContext ChipDetection.Fail("USB device has no serial port")
            try {
                port.open(connection)
                port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                try { port.purgeHwBuffers(true, true) } catch (_: UnsupportedOperationException) {}
                var synced = false
                repeat(if (manualBoot) SYNC_ATTEMPTS else 3) {
                    if (!synced) synced = trySync(port)
                }
                if (!synced && !manualBoot) {
                    reset(port, if (driver.device.vendorId == VID_ESPRESSIF_USB_JTAG) "s3-usb-jtag" else "classic")
                    delay(200)
                    repeat(SYNC_ATTEMPTS) { if (!synced) synced = trySync(port) }
                }
                if (!synced) return@withContext ChipDetection.Fail("chip did not respond; enter download mode and retry")
                // SYNC can yield extra echoes that must not be mistaken for
                // the next command's response.
                try { readSlipFrame(port, 100) } catch (_: Throwable) {}
                try { readSlipFrame(port, 100) } catch (_: Throwable) {}
                try {
                    sendCommand(port, CMD_GET_SECURITY_INFO, byteArrayOf(), 0)
                    val response = readResponse(port, CMD_GET_SECURITY_INFO, 1000)
                    // Header is 8 bytes. Security info has flags (4), flash
                    // crypt count (1), key purposes (7), then chip ID (4).
                    if (response.size >= 24 && readU32LE(response, 20) == 9) {
                        return@withContext ChipDetection.Found(McuTarget.ESP32_S3)
                    }
                } catch (_: Throwable) {
                    // ESP8266 ROM does not implement GET_SECURITY_INFO.
                }
                try {
                    val address = ByteArrayOutputStream().apply { writeU32LE(0x40001000) }.toByteArray()
                    sendCommand(port, CMD_READ_REG, address, 0)
                    val response = readResponse(port, CMD_READ_REG, 1000)
                    if (readU32LE(response, 4) == 0xFFF0C101.toInt()) {
                        return@withContext ChipDetection.Found(McuTarget.ESP8266_NODEMCU)
                    }
                } catch (_: Throwable) {}
                ChipDetection.Fail("connected chip is not a supported ESP8266 or ESP32-S3")
            } catch (t: Throwable) {
                ChipDetection.Fail(t.message ?: t.javaClass.simpleName)
            } finally {
                try { port.close() } catch (_: Throwable) {}
                try { connection.close() } catch (_: Throwable) {}
            }
        }

    /** Single-blob flash — reads the uri and dispatches through [flashPlan]. */
    suspend fun flash(
        context: Context,
        firmwareUri: Uri,
        offset: Int,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        manualBoot: Boolean = false,
        target: McuTarget = McuTarget.Default,
        expectedDeviceKey: String? = null,
    ): FlashResult = withContext(Dispatchers.IO) {
        val data = runCatching { readAll(context, firmwareUri) }.getOrElse {
            return@withContext FlashResult.Fail("cannot read firmware: ${it.message}")
        }
        if (data.isEmpty()) return@withContext FlashResult.Fail("empty firmware file")
        flashPlan(
            context = context,
            plan = listOf(FlashBlob("firmware", offset, data)),
            onProgress = onProgress,
            onLog = onLog,
            manualBoot = manualBoot,
            target = target,
            expectedDeviceKey = expectedDeviceKey,
        )
    }

    /**
     * Flashes a sequence of blobs at distinct offsets within a single bootROM
     * session: one reset + SYNC, then one FLASH_BEGIN/DATA per blob, then one
     * FLASH_END to boot. Used for ESP32-family targets that want bootloader +
     * partitions + boot_app0 + app across four offsets, but also degenerates
     * cleanly to the single-blob case.
     *
     * Progress is reported as cumulative-bytes / total-bytes across the plan,
     * so the caller can drive a single progress bar.
     */
    suspend fun flashPlan(
        context: Context,
        plan: List<FlashBlob>,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        manualBoot: Boolean = false,
        target: McuTarget = McuTarget.Default,
        expectedDeviceKey: String? = null,
    ): FlashResult = operationMutex.withLock {
        if (listDevices(context).size != 1) return@withLock FlashResult.Fail("Connect exactly one USB serial board before flashing")
        UsbSerialMonitor.stop()
        val result = flashPlanInternal(context, plan, onProgress, onLog, manualBoot, target, expectedDeviceKey)
        SparkDeviceContext.recordOperation("flash", target.id, result is FlashResult.Ok,
            when (result) { is FlashResult.Ok -> "Wrote ${result.bytes} bytes; runtime behavior is not yet verified"
                is FlashResult.Fail -> result.reason })
        result
    }

    private suspend fun flashPlanInternal(
        context: Context,
        plan: List<FlashBlob>,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        manualBoot: Boolean = false,
        target: McuTarget = McuTarget.Default,
        expectedDeviceKey: String? = null,
    ): FlashResult = withContext(Dispatchers.IO) {
        if (plan.isEmpty()) return@withContext FlashResult.Fail("empty flash plan")

        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
            ?: return@withContext FlashResult.Fail("no USB serial device found")
        val actualDeviceKey = "${driver.device.deviceName}:${driver.device.deviceId}:${driver.device.vendorId}:${driver.device.productId}"
        if (expectedDeviceKey != null && actualDeviceKey != expectedDeviceKey) {
            return@withContext FlashResult.Fail("USB board changed after confirmation; inspect and confirm the new connection")
        }
        onLog("device: ${chipName(driver)} ${hex4(driver.device.vendorId)}:${hex4(driver.device.productId)}")

        if (!manager.hasPermission(driver.device)) {
            onLog("requesting USB permission ...")
            val granted = requestPermission(context, manager, driver)
            if (!granted) return@withContext FlashResult.Fail("USB permission denied")
        }

        val connection = manager.openDevice(driver.device)
            ?: return@withContext FlashResult.Fail("openDevice returned null")
        val port = driver.ports.firstOrNull()
            ?: return@withContext FlashResult.Fail("driver has no ports")

        try {
            port.open(connection)
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Best-effort — CdcAcmSerialDriver (used by the S3 native USB-JTAG
            // interface) throws UnsupportedOperationException since CDC-ACM
            // has no host-side hardware FIFO to flush.
            try { port.purgeHwBuffers(true, true) } catch (_: UnsupportedOperationException) {}

            val totalBytes = plan.sumOf { it.data.size.toLong() }
            onLog("plan: ${plan.size} blob(s), $totalBytes bytes total")
            for (b in plan) {
                onLog("  ${b.name}: ${b.data.size} B @ 0x${Integer.toHexString(b.offset)}")
            }

            val nativeUsbJtag = driver.device.vendorId == VID_ESPRESSIF_USB_JTAG
            val resetKind = when {
                manualBoot -> "manual"
                target == McuTarget.ESP32_S3 && nativeUsbJtag -> "s3-usb-jtag"
                else -> "classic"
            }

            // Order: try SYNC bare first, then fall back to reset + SYNC.
            //
            // Why: an S3 that has never been flashed (or was most recently in
            // the bootROM) is already parked in download mode when its native
            // USB-JTAG-serial enumerates, so SYNC answers immediately. Pulsing
            // DTR/RTS at that point causes the usb_serial_jtag peripheral to
            // tear the USB link down and bring it back up, which (a) invalidates
            // our open port handle for a window and (b) wastes seconds.
            //
            // For a chip already running user firmware, bare SYNC fails fast
            // and we fall through to the real reset pulse.
            if (manualBoot) {
                onLog("manual boot mode: skipping DTR/RTS reset pulse")
            } else {
                onLog("trying SYNC bare (chip may already be in download mode) ...")
            }

            onLog("SYNC ...")
            var synced = false
            val bareAttempts = if (manualBoot) SYNC_ATTEMPTS else 3
            for (attempt in 1..bareAttempts) {
                if (trySync(port)) { synced = true; break }
                onLog("  sync attempt $attempt failed")
            }
            if (!synced && !manualBoot) {
                onLog("bare sync failed — pulsing reset ($resetKind) and retrying ...")
                reset(port, resetKind)
                // Give the USB-JTAG peripheral time to come back up after the
                // reset pulse before we start writing SYNC frames at it.
                delay(200)
                for (attempt in 1..SYNC_ATTEMPTS) {
                    if (trySync(port)) { synced = true; break }
                    onLog("  post-reset sync attempt $attempt failed")
                }
            }
            if (!synced) return@withContext FlashResult.Fail("chip did not respond to SYNC")
            onLog("sync ok")

            // Drain any extra sync echoes
            try { readSlipFrame(port, 100) } catch (_: Throwable) {}
            try { readSlipFrame(port, 100) } catch (_: Throwable) {}

            var bytesDone = 0L
            var totalBlocks = 0
            for (blob in plan) {
                val data = blob.data
                val numBlocks = (data.size + BLOCK_SIZE - 1) / BLOCK_SIZE
                val eraseSize = data.size
                onLog("FLASH_BEGIN ${blob.name}: $numBlocks blocks @ 0x${Integer.toHexString(blob.offset)}")

                val beginPayload = ByteArrayOutputStream().apply {
                    writeU32LE(eraseSize)
                    writeU32LE(numBlocks)
                    writeU32LE(BLOCK_SIZE)
                    writeU32LE(blob.offset)
                }.toByteArray()
                command(port, CMD_FLASH_BEGIN, beginPayload, 0, timeoutMs = 10_000)

                for (seq in 0 until numBlocks) {
                    val start = seq * BLOCK_SIZE
                    val end = minOf(start + BLOCK_SIZE, data.size)
                    val chunk = ByteArray(BLOCK_SIZE) { 0xFF.toByte() }
                    System.arraycopy(data, start, chunk, 0, end - start)

                    val payload = ByteArrayOutputStream().apply {
                        writeU32LE(BLOCK_SIZE)
                        writeU32LE(seq)
                        writeU32LE(0)
                        writeU32LE(0)
                        write(chunk)
                    }.toByteArray()
                    val checksum = checksum(chunk)
                    command(port, CMD_FLASH_DATA, payload, checksum, timeoutMs = 3_000)
                    onProgress(bytesDone + end, totalBytes)
                }
                bytesDone += data.size
                totalBlocks += numBlocks
            }

            onLog("FLASH_END, rebooting ...")
            val endPayload = ByteArrayOutputStream().apply { writeU32LE(0) }.toByteArray()
            try {
                command(port, CMD_FLASH_END, endPayload, 0, timeoutMs = 3_000)
            } catch (_: Throwable) {
                // Some ROMs reset before responding — treat as success
            }

            FlashResult.Ok(bytesDone, totalBlocks)
        } catch (t: Throwable) {
            FlashResult.Fail(t.message ?: t.javaClass.simpleName)
        } finally {
            try { port.close() } catch (_: Throwable) {}
        }
    }

    private suspend fun requestPermission(
        context: Context,
        manager: UsbManager,
        driver: UsbSerialDriver,
    ): Boolean = suspendCancellableCoroutine { cont: CancellableContinuation<Boolean> ->
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE else 0
        val device = driver.device
        // PendingIntent identity excludes extras. Give each USB device its own
        // identity so a cached request cannot deliver a stale grant result.
        val permissionIntent = Intent(ACTION_USB_PERMISSION)
            .setPackage(context.packageName)
            .setData(android.net.Uri.parse("spark-usb://permission/${device.deviceId}/${device.vendorId}/${device.productId}"))
        val intent = PendingIntent.getBroadcast(context, device.deviceId, permissionIntent, flags)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (i.action != ACTION_USB_PERMISSION) return
                try { ctx.unregisterReceiver(this) } catch (_: Throwable) {}
                // The manager state is authoritative; some Android builds omit
                // or misreport the result extra despite having granted access.
                val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                    manager.hasPermission(device)
                if (cont.isActive) cont.resumeWith(Result.success(ok))
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        manager.requestPermission(device, intent)
        cont.invokeOnCancellation {
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
        }
    }

    private suspend fun reset(port: UsbSerialPort, kind: String) {
        when (kind) {
            "s3-usb-jtag" -> s3UsbJtagReset(port)
            else -> classicReset(port)
        }
    }

    private suspend fun classicReset(port: UsbSerialPort) {
        // ESP reset wiring via CP2102/CH340: RTS -> EN (active high = reset),
        // DTR -> IO0 (active high = pulled low).
        port.dtr = false; port.rts = true     // EN low, chip in reset
        delay(100)
        port.dtr = true; port.rts = false     // EN high, IO0 held low (boot)
        delay(50)
        port.dtr = false                       // release IO0
        delay(50)
        try { port.purgeHwBuffers(true, true) } catch (_: UnsupportedOperationException) {}
    }

    private suspend fun s3UsbJtagReset(port: UsbSerialPort) {
        // ESP32-S3 (and S2/C3/C6/H2) native USB-JTAG-serial reset.
        //
        // On native USB CDC, DTR/RTS are routed through the chip's built-in
        // USB-serial-JTAG peripheral, which inverts the polarity vs the
        // CP2102 wiring: DTR *low* holds IO0 low, RTS *low* holds EN low.
        // Also, a stray DTR=low at open can latch the chip in download mode
        // forever, so the sequence resets both lines to high first.
        //
        // Mirrors esptool.py's HardReset.usb_jtag_serial_reset.
        port.dtr = false; port.rts = false   // IO0=HIGH, EN=HIGH (idle)
        delay(100)
        port.dtr = true;  port.rts = false   // IO0=LOW  (will stay low across reset)
        port.dtr = false; port.rts = true    // IO0=HIGH, EN=LOW  (begin reset)
        delay(100)
        port.dtr = true;  port.rts = false   // IO0=LOW,  EN=HIGH (exit reset with IO0 held low -> bootROM)
        delay(100)
        port.dtr = false                      // release IO0
        try { port.purgeHwBuffers(true, true) } catch (_: UnsupportedOperationException) {}
    }

    private fun trySync(port: UsbSerialPort): Boolean {
        val payload = ByteArray(36)
        payload[0] = 0x07; payload[1] = 0x07; payload[2] = 0x12; payload[3] = 0x20
        for (i in 4 until 36) payload[i] = 0x55
        return try {
            sendCommand(port, CMD_SYNC, payload, 0)
            readResponse(port, CMD_SYNC, 500)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun command(
        port: UsbSerialPort, cmd: Byte, payload: ByteArray, checksum: Int, timeoutMs: Int,
    ) {
        sendCommand(port, cmd, payload, checksum)
        readResponse(port, cmd, timeoutMs)
    }

    private fun sendCommand(port: UsbSerialPort, cmd: Byte, payload: ByteArray, checksum: Int) {
        val pkt = ByteArrayOutputStream(payload.size + 8).apply {
            write(0x00)
            write(cmd.toInt() and 0xFF)
            writeU16LE(payload.size)
            writeU32LE(checksum)
            write(payload)
        }.toByteArray()
        val encoded = slipEncode(pkt)
        port.write(encoded, 2000)
    }

    private fun readResponse(port: UsbSerialPort, expectedCmd: Byte, timeoutMs: Int): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val frame = readSlipFrame(port, (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1))
                ?: continue
            if (frame.size < 10) continue
            if (frame[0].toInt() != 0x01) continue
            if (frame[1] != expectedCmd) continue
            val status = frame[frame.size - 2].toInt() and 0xFF
            val error = frame[frame.size - 1].toInt() and 0xFF
            if (status != 0) throw RuntimeException("cmd 0x${hex2(expectedCmd.toInt())} failed status=$status err=$error")
            return frame
        }
        throw RuntimeException("timeout waiting for response to cmd 0x${hex2(expectedCmd.toInt())}")
    }

    private fun readSlipFrame(port: UsbSerialPort, timeoutMs: Int): ByteArray? {
        val buf = ByteArray(2048)
        val out = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        var inFrame = false
        var escape = false
        while (System.currentTimeMillis() < deadline) {
            val n = try {
                port.read(buf, (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1))
            } catch (_: Throwable) { 0 }
            if (n <= 0) continue
            for (i in 0 until n) {
                val b = buf[i].toInt() and 0xFF
                if (!inFrame) {
                    if (b == 0xC0) inFrame = true
                    continue
                }
                if (escape) {
                    when (b) {
                        0xDC -> out.write(0xC0)
                        0xDD -> out.write(0xDB)
                        else -> out.write(b)
                    }
                    escape = false
                } else when (b) {
                    0xC0 -> {
                        if (out.size() > 0) return out.toByteArray()
                        // empty frame (0xC0 0xC0) — just the frame start
                    }
                    0xDB -> escape = true
                    else -> out.write(b)
                }
            }
        }
        return null
    }

    private fun slipEncode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 2)
        out.write(0xC0)
        for (bByte in data) {
            when (val b = bByte.toInt() and 0xFF) {
                0xC0 -> { out.write(0xDB); out.write(0xDC) }
                0xDB -> { out.write(0xDB); out.write(0xDD) }
                else -> out.write(b)
            }
        }
        out.write(0xC0)
        return out.toByteArray()
    }

    private fun checksum(data: ByteArray): Int {
        var state = 0xEF
        for (b in data) state = state xor (b.toInt() and 0xFF)
        return state
    }

    private fun readU32LE(data: ByteArray, offset: Int): Int {
        if (offset < 0 || data.size < offset + 4) return 0
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun ByteArrayOutputStream.writeU16LE(v: Int) {
        write(v and 0xFF); write((v ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32LE(v: Int) {
        write(v and 0xFF); write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
    }

    private fun readAll(context: Context, uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw RuntimeException("cannot open $uri")
        return input.use { it.readBytes() }
    }

    private fun chipName(driver: UsbSerialDriver): String =
        driver.javaClass.simpleName.removeSuffix("SerialDriver")

    private fun hex4(v: Int) = "0x%04x".format(v)
    private fun hex2(v: Int) = "0x%02x".format(v and 0xFF)
}
