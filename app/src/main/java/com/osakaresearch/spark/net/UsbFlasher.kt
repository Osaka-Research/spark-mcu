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

data class UsbDeviceInfo(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val hasPermission: Boolean,
)

object UsbFlasher {
    private const val ACTION_USB_PERMISSION = "com.osakaresearch.spark.USB_PERMISSION"

    private const val BAUD = 115200
    private const val BLOCK_SIZE = 1024
    private const val SYNC_ATTEMPTS = 7

    private const val CMD_FLASH_BEGIN: Byte = 0x02
    private const val CMD_FLASH_DATA: Byte = 0x03
    private const val CMD_FLASH_END: Byte = 0x04
    private const val CMD_SYNC: Byte = 0x08

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
            )
        }
    }

    suspend fun flash(
        context: Context,
        firmwareUri: Uri,
        offset: Int,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
    ): FlashResult = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
            ?: return@withContext FlashResult.Fail("no USB serial device found")
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
            port.purgeHwBuffers(true, true)

            val firmware = readAll(context, firmwareUri)
            if (firmware.isEmpty()) return@withContext FlashResult.Fail("empty firmware file")
            onLog("firmware: ${firmware.size} bytes @ offset 0x${Integer.toHexString(offset)}")

            onLog("resetting chip into bootloader ...")
            classicReset(port)

            onLog("SYNC ...")
            var synced = false
            for (attempt in 1..SYNC_ATTEMPTS) {
                if (trySync(port)) { synced = true; break }
                onLog("  sync attempt $attempt failed")
                classicReset(port)
            }
            if (!synced) return@withContext FlashResult.Fail("chip did not respond to SYNC")
            onLog("sync ok")

            // Drain any extra sync echoes
            try { readSlipFrame(port, 100) } catch (_: Throwable) {}
            try { readSlipFrame(port, 100) } catch (_: Throwable) {}

            val numBlocks = (firmware.size + BLOCK_SIZE - 1) / BLOCK_SIZE
            val eraseSize = firmware.size
            onLog("FLASH_BEGIN: $numBlocks blocks, erase=$eraseSize")

            val beginPayload = ByteArrayOutputStream().apply {
                writeU32LE(eraseSize)
                writeU32LE(numBlocks)
                writeU32LE(BLOCK_SIZE)
                writeU32LE(offset)
            }.toByteArray()
            command(port, CMD_FLASH_BEGIN, beginPayload, 0, timeoutMs = 10_000)

            for (seq in 0 until numBlocks) {
                val start = seq * BLOCK_SIZE
                val end = minOf(start + BLOCK_SIZE, firmware.size)
                val chunk = ByteArray(BLOCK_SIZE) { 0xFF.toByte() }
                System.arraycopy(firmware, start, chunk, 0, end - start)

                val payload = ByteArrayOutputStream().apply {
                    writeU32LE(BLOCK_SIZE)
                    writeU32LE(seq)
                    writeU32LE(0)
                    writeU32LE(0)
                    write(chunk)
                }.toByteArray()
                val checksum = checksum(chunk)
                command(port, CMD_FLASH_DATA, payload, checksum, timeoutMs = 3_000)
                onProgress((end).toLong(), firmware.size.toLong())
            }

            onLog("FLASH_END, rebooting ...")
            val endPayload = ByteArrayOutputStream().apply { writeU32LE(0) }.toByteArray()
            try {
                command(port, CMD_FLASH_END, endPayload, 0, timeoutMs = 3_000)
            } catch (_: Throwable) {
                // Some ROMs reset before responding — treat as success
            }

            FlashResult.Ok(firmware.size.toLong(), numBlocks)
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
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (i.action != ACTION_USB_PERMISSION) return
                try { ctx.unregisterReceiver(this) } catch (_: Throwable) {}
                val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
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
        manager.requestPermission(driver.device, intent)
        cont.invokeOnCancellation {
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
        }
    }

    private suspend fun classicReset(port: UsbSerialPort) {
        // ESP reset wiring: RTS -> EN (active high = reset), DTR -> IO0 (active high = pulled low)
        port.dtr = false; port.rts = true     // EN low, chip in reset
        delay(100)
        port.dtr = true; port.rts = false     // EN high, IO0 held low (boot)
        delay(50)
        port.dtr = false                       // release IO0
        delay(50)
        port.purgeHwBuffers(true, true)
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
