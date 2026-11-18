package com.osakaresearch.spark.net

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.charset.Charset

/**
 * Minimal always-on serial reader for the attached USB-OTG adapter. Unlike
 * [UsbFlasher] this never drives DTR/RTS — it just opens the port at a chosen
 * baud rate and streams whatever the chip emits.
 *
 * Useful bauds for ESP8266:
 *   - 74880: bootROM boot-time log (reset reason, flash mode, cause of crash)
 *   - 115200: typical user-sketch Serial.print
 */
object UsbSerialMonitor {
    private const val ACTION_USB_PERMISSION = "com.osakaresearch.spark.USB_MONITOR_PERMISSION"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 512)
    val lines: SharedFlow<String> = _lines

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var readerJob: Job? = null
    private var currentPort: UsbSerialPort? = null

    sealed class State {
        data object Idle : State()
        data class Running(val baud: Int, val device: String) : State()
        data class Error(val reason: String) : State()
    }

    suspend fun start(context: Context, baud: Int): Boolean {
        stop()

        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
        if (driver == null) {
            _state.value = State.Error("no USB serial device found")
            return false
        }

        if (!manager.hasPermission(driver.device)) {
            val granted = requestPermission(context, manager, driver.device)
            if (!granted) {
                _state.value = State.Error("USB permission denied")
                return false
            }
        }

        val connection = manager.openDevice(driver.device)
        if (connection == null) {
            _state.value = State.Error("openDevice returned null")
            return false
        }
        val port = driver.ports.firstOrNull()
        if (port == null) {
            _state.value = State.Error("driver has no ports")
            return false
        }

        return try {
            port.open(connection)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Native ESP32-S3 USB serial/JTAG uses CDC-ACM, which does not
            // expose a host-side hardware FIFO to purge.
            try { port.purgeHwBuffers(true, true) } catch (_: UnsupportedOperationException) {}
            currentPort = port
            readerJob = scope.launch { readLoop(port) }
            _state.value = State.Running(
                baud = baud,
                device = driver.device.productName ?: "${driver.javaClass.simpleName.removeSuffix("SerialDriver")}",
            )
            _lines.tryEmit("── monitor open @ $baud 8N1 ──")
            true
        } catch (t: Throwable) {
            try { port.close() } catch (_: Throwable) {}
            _state.value = State.Error(t.message ?: t.javaClass.simpleName)
            false
        }
    }

    fun stop() {
        readerJob?.cancel()
        readerJob = null
        try { currentPort?.close() } catch (_: Throwable) {}
        currentPort = null
        if (_state.value is State.Running) {
            _lines.tryEmit("── monitor closed ──")
        }
        _state.value = State.Idle
    }

    private suspend fun readLoop(port: UsbSerialPort) {
        val buf = ByteArray(1024)
        val line = StringBuilder()
        val utf8 = Charset.forName("UTF-8")

        while (scope.isActive && readerJob?.isActive == true) {
            val n = try {
                port.read(buf, 200)
            } catch (t: Throwable) {
                _state.value = State.Error(t.message ?: t.javaClass.simpleName)
                return
            }
            if (n <= 0) continue
            // Decode leniently — bootROM output is ASCII, user prints may be
            // UTF-8; invalid bytes get replacement chars rather than dropping.
            val chunk = String(buf, 0, n, utf8)
            for (ch in chunk) {
                when (ch) {
                    '\n' -> { _lines.tryEmit(line.toString()); line.clear() }
                    '\r' -> { /* swallow */ }
                    else -> line.append(ch)
                }
            }
            // Periodically flush a partial line so prompts without newlines show up.
            if (line.length > 240) {
                _lines.tryEmit(line.toString()); line.clear()
            }
        }
    }

    private suspend fun requestPermission(
        context: Context,
        manager: UsbManager,
        device: android.hardware.usb.UsbDevice,
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
        manager.requestPermission(device, intent)
        cont.invokeOnCancellation {
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
        }
    }
}
