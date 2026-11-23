package com.osakaresearch.spark.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.osakaresearch.spark.compile.BuildArtifacts
import com.osakaresearch.spark.compile.McuTarget
import com.osakaresearch.spark.net.ChipDetection
import com.osakaresearch.spark.net.SparkClient
import com.osakaresearch.spark.net.UsbFlasher
import com.osakaresearch.spark.net.UsbSerialMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Observations, never guesses from a USB bridge name or the target selector. */
object SparkDeviceContext {
    private var initialized = false
    private val mutableTarget = MutableStateFlow(McuTarget.Default)
    val selectedTarget = mutableTarget.asStateFlow()
    @Volatile private var detected: JSONObject? = null
    @Volatile private var lastOperation: String = "{}"
    private val attachmentGeneration = AtomicLong()
    val attachmentVersion: Long get() = attachmentGeneration.get()

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("spark_workspace", Context.MODE_PRIVATE)
        mutableTarget.value = McuTarget.byId(prefs.getString("target", "").orEmpty())
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { detected = null; attachmentGeneration.incrementAndGet() }
        }, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_EXPORTED)
        initialized = true
    }

    fun selectTarget(context: Context, target: McuTarget) {
        initialize(context)
        mutableTarget.value = target
        context.getSharedPreferences("spark_workspace", Context.MODE_PRIVATE).edit()
            .putString("target", target.id).putBoolean("target_explicit", true).apply()
    }

    fun explicitlySelectedTarget(context: Context): McuTarget? {
        initialize(context)
        val prefs = context.getSharedPreferences("spark_workspace", Context.MODE_PRIVATE)
        return if (prefs.getBoolean("target_explicit", false)) McuTarget.byId(prefs.getString("target", "").orEmpty()) else null
    }

    fun detectedTargetFor(deviceKey: String): McuTarget? = detected
        ?.takeIf { it.optString("usbDeviceKey") == deviceKey }
        ?.optString("target")?.let(McuTarget::byId)

    fun recordDetection(context: Context, deviceKey: String?, result: ChipDetection) {
        detected = if (deviceKey != null && result is ChipDetection.Found) {
            mutableTarget.value = result.target
            context.getSharedPreferences("spark_workspace", Context.MODE_PRIVATE).edit()
                .putString("target", result.target.id).putBoolean("target_explicit", false).apply()
            JSONObject().put("target", result.target.id).put("usbDeviceKey", deviceKey)
                .put("verifiedAt", System.currentTimeMillis()).put("evidence", "chip ROM response")
        } else null
    }

    fun recordOperation(kind: String, target: String, ok: Boolean, detail: String, log: String = "") {
        lastOperation = JSONObject().put("kind", kind).put("target", target).put("ok", ok)
            .put("detail", detail.take(1000)).put("log", log.takeLast(5000))
            .put("at", System.currentTimeMillis()).toString()
    }

    fun snapshot(context: Context): JSONObject {
        initialize(context)
        val devices = UsbFlasher.listDevices(context)
        val detection = detected?.takeIf { observation ->
            devices.size == 1 && devices.single().deviceKey == observation.optString("usbDeviceKey")
        }
        val monitor = when (val state = UsbSerialMonitor.state.value) {
            is UsbSerialMonitor.State.Running -> JSONObject().put("status", "running").put("baud", state.baud).put("device", state.device)
            is UsbSerialMonitor.State.Error -> JSONObject().put("status", "error").put("reason", state.reason)
            UsbSerialMonitor.State.Idle -> JSONObject().put("status", "stopped")
        }
        val recent = UsbSerialMonitor.history.tail(30)
        monitor.put("cursor", recent.cursor).put("recentLines", JSONArray(recent.lines))
            .put("captureId", UsbSerialMonitor.captureId)
            .put("captureDeviceKey", UsbSerialMonitor.captureDeviceKey ?: JSONObject.NULL)
            .put("captureBaud", UsbSerialMonitor.captureBaud ?: JSONObject.NULL)
            .put("captureMatchesCurrentAttachment", devices.any { it.deviceKey == UsbSerialMonitor.captureDeviceKey })
        return JSONObject().put("observedAt", System.currentTimeMillis())
            .put("selectedTarget", selectedTarget.value.id)
            .put("selectedTargetExplicit", context.getSharedPreferences("spark_workspace", Context.MODE_PRIVATE)
                .getBoolean("target_explicit", false))
            .put("selectionIsChipEvidence", false)
            .put("detectedChip", detection ?: JSONObject.NULL)
            .put("usbDevices", JSONArray().apply { devices.forEach { d ->
                put(JSONObject().put("key", d.deviceKey).put("name", d.name).put("vendorId", d.vendorId)
                    .put("productId", d.productId).put("permissionGranted", d.hasPermission)
                    .put("serialInterface", if (d.vendorId == 0x303A && d.productId == 0x1001) "native-usb-serial-jtag" else "usb-serial-interface"))
            } })
            .put("supportedTargets", JSONArray().apply {
                listOf(McuTarget.ESP8266_NODEMCU, McuTarget.ESP32_S3).forEach { target ->
                    put(JSONObject().put("target", target.id).put("label", target.label)
                        .put("compilerAvailable", target.isAvailable(context)).put("configuration", target.detail)
                        .put("serialConfiguration", if (target == McuTarget.ESP32_S3)
                            "Bundled core uses ARDUINO_USB_MODE=1 and ARDUINO_USB_CDC_ON_BOOT=0: Serial means UART0. For a ROM-confirmed S3 on native USB serial/JTAG (303a:1001), include <HWCDC.h>, declare HWCDC DebugSerial, call DebugSerial.begin(115200), and print/read using DebugSerial. Do not wait forever for a host. Use Serial for an external UART bridge."
                            else "Serial uses UART0 through the USB serial bridge. Match Serial.begin baud."))
                }
            })
            .put("serial", monitor).put("recentBuilds", BuildArtifacts.list(context))
            .put("workspace", AgentWorkspace(File(context.filesDir, "assistant")).read(false).apply {
                optJSONObject("lastBuild")?.remove("log")
            })
            .put("lastOperation", JSONObject(lastOperation))
            .put("wifiConnection", SparkClient.state.value.toString())
            .put("wifiBoard", SparkClient.latestBoardHello ?: JSONObject.NULL)
            .put("notes", "USB adapter identity does not prove the MCU or board pinout. A Wi-Fi connection may be a different board. ROM detection can reset the board. Snapshots can become stale after reconnect.")
    }
}
