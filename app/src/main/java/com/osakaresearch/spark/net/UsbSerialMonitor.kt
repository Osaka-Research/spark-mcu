package com.osakaresearch.spark.net

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDeviceConnection
import android.os.Build
import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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

    val history = SerialLogBuffer()
    @Volatile var captureDeviceKey: String? = null; private set
    @Volatile var captureBaud: Int? = null; private set
    @Volatile var captureId: Long = 0; private set

    private fun emitLine(line: String) { history.append(line); _lines.tryEmit(line) }

    private var readerJob: Job? = null
    @Volatile private var currentPort: UsbSerialPort? = null
    private var currentConnection: UsbDeviceConnection? = null

    sealed class State {
        data object Idle : State()
        data class Running(val baud: Int, val device: String) : State()
        data class Error(val reason: String) : State()
    }

    suspend fun start(context: Context, baud: Int): Boolean = UsbFlasher.operationMutex.withLock {
        startInternal(context, baud)
    }

    private suspend fun startInternal(context: Context, baud: Int): Boolean {
        if (baud !in setOf(9600, 19200, 38400, 57600, 74880, 115200, 230400, 460800, 921600)) {
            _state.value = State.Error("Unsupported baud rate")
            return false
        }
        val attached = UsbFlasher.listDevices(context).singleOrNull()
        if (state.value is State.Running && captureBaud == baud && attached != null &&
            attached.deviceKey == captureDeviceKey && currentPort != null) return true
        stop()
        history.clear()
        captureId++

        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (drivers.size > 1) {
            _state.value = State.Error("Connect only the board you want to monitor; multiple USB serial devices are attached")
            return false
        }
        val driver = drivers.firstOrNull()
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
            connection.close()
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
            currentConnection = connection
            captureDeviceKey = "${driver.device.deviceName}:${driver.device.deviceId}:${driver.device.vendorId}:${driver.device.productId}"
            captureBaud = baud
            _state.value = State.Running(
                baud = baud,
                device = driver.device.productName ?: "${driver.javaClass.simpleName.removeSuffix("SerialDriver")}",
            )
            _lines.tryEmit("── monitor open @ $baud 8N1 ──")
            readerJob = scope.launch { readLoop(port) }
            true
        } catch (t: Throwable) {
            try { port.close() } catch (_: Throwable) {}
            try { connection.close() } catch (_: Throwable) {}
            currentPort = null
            currentConnection = null
            if (t is CancellationException) throw t
            _state.value = State.Error(t.message ?: t.javaClass.simpleName)
            false
        }
    }

    fun stop() {
        readerJob?.cancel()
        readerJob = null
        val port = currentPort
        currentPort = null
        try { port?.close() } catch (_: Throwable) {}
        try { currentConnection?.close() } catch (_: Throwable) {}
        currentConnection = null
        if (_state.value is State.Running) {
            _lines.tryEmit("── monitor closed ──")
        }
        _state.value = State.Idle
    }

    suspend fun write(context: Context, text: String, newline: Boolean): Int = UsbFlasher.operationMutex.withLock {
        require(state.value is State.Running) { "Start the serial monitor before sending a command" }
        require(UsbFlasher.listDevices(context).singleOrNull()?.deviceKey == captureDeviceKey) {
            "USB connection changed; reopen the monitor before sending"
        }
        val bytes = (text + if (newline) "\n" else "").toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= 1024) { "Serial commands must be 1–1024 UTF-8 bytes" }
        val port = currentPort ?: error("Serial port is closed")
        port.write(bytes, 2000)
        bytes.size
    }

    suspend fun resetBoard(context: Context): Long = UsbFlasher.operationMutex.withLock {
        val device = UsbFlasher.listDevices(context).singleOrNull() ?: error("Connect exactly one USB serial board")
        require(state.value is State.Running && device.deviceKey == captureDeviceKey) { "Open the monitor on this board before resetting" }
        val port = currentPort ?: error("Serial port is closed")
        val cursor = history.tail(1).cursor
        // EN pulse, with GPIO0 released, following Espressif's hard-reset sequence.
        // Complete the release even if the user stops while EN is held low.
        withContext(NonCancellable) {
            port.dtr = false
            try {
                port.rts = true
                delay(if (device.vendorId == 0x303A) 200 else 100)
            } finally { port.rts = false }
        }
        cursor
    }

    private suspend fun readLoop(port: UsbSerialPort) {
        val buf = ByteArray(1024)
        val line = StringBuilder()
        val utf8 = Charset.forName("UTF-8")
        var lastReadAt = SystemClock.elapsedRealtime()

        while (currentCoroutineContext().isActive && currentPort === port) {
            val n = try {
                port.read(buf, 200)
            } catch (t: Throwable) {
                if (currentPort === port) _state.value = State.Error(t.message ?: t.javaClass.simpleName)
                return
            }
            if (currentPort !== port || !currentCoroutineContext().isActive) return
            if (n <= 0) {
                // Serial.print prompts and partial crash messages may have no newline.
                if (line.isNotEmpty() && SystemClock.elapsedRealtime() - lastReadAt >= 500) {
                    emitLine(line.toString()); line.clear()
                }
                continue
            }
            lastReadAt = SystemClock.elapsedRealtime()
            // Decode leniently — bootROM output is ASCII, user prints may be
            // UTF-8; invalid bytes get replacement chars rather than dropping.
            val chunk = String(buf, 0, n, utf8)
            for (ch in chunk) {
                when (ch) {
                    '\n' -> { emitLine(line.toString()); line.clear() }
                    '\r' -> { /* swallow */ }
                    else -> line.append(ch)
                }
            }
            // Periodically flush a partial line so prompts without newlines show up.
            if (line.length > 240) {
                emitLine(line.toString()); line.clear()
            }
        }
    }

    private suspend fun requestPermission(
        context: Context,
        manager: UsbManager,
        device: android.hardware.usb.UsbDevice,
    ): Boolean = suspendCancellableCoroutine { cont: CancellableContinuation<Boolean> ->
        // UsbManager fills in the device and grant result when sending this callback.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = Intent(ACTION_USB_PERMISSION)
            .setPackage(context.packageName)
            .setData(android.net.Uri.parse("spark-usb://permission/${device.deviceId}/${device.vendorId}/${device.productId}"))
        val intent = PendingIntent.getBroadcast(context, device.deviceId, permissionIntent, flags)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (i.action != ACTION_USB_PERMISSION) return
                try { ctx.unregisterReceiver(this) } catch (_: Throwable) {}
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
}
