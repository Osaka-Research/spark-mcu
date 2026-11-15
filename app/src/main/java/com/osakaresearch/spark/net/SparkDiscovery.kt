package com.osakaresearch.spark.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SparkDevice(val name: String, val host: String, val port: Int)

/** Discovers the _spark._tcp service advertised by the firmware. */
class SparkDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _devices = MutableStateFlow<List<SparkDevice>>(emptyList())
    val devices: StateFlow<List<SparkDevice>> = _devices.asStateFlow()
    private val _status = MutableStateFlow("Searching…")
    val status: StateFlow<String> = _status.asStateFlow()
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val current = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _status.value = "Searching…"
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _status.value = "Discovery failed ($errorCode)"
                stop()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                _status.value = "Could not stop discovery ($errorCode)"
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.startsWith("_spark._tcp")) return
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val address = info.host?.hostAddress ?: return
                        val device = SparkDevice(info.serviceName, address, info.port)
                        _devices.value = _devices.value.filterNot { it.name == device.name } + device
                        _status.value = "${_devices.value.size} device(s) found"
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                _devices.value = _devices.value.filterNot { it.name == service.serviceName }
                _status.value = "${_devices.value.size} device(s) found"
            }
        }
        listener = current
        try {
            nsd.discoverServices("_spark._tcp.", NsdManager.PROTOCOL_DNS_SD, current)
        } catch (e: RuntimeException) {
            listener = null
            _status.value = e.message ?: "Discovery unavailable"
        }
    }

    fun stop() {
        val current = listener ?: return
        listener = null
        try { nsd.stopServiceDiscovery(current) } catch (_: RuntimeException) { }
    }
}
