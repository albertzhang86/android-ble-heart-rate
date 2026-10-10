package io.github.albertzhang86.heartrate.android.internal

import io.github.albertzhang86.heartrate.HeartRateDevice
import io.github.albertzhang86.heartrate.connector.ConnectorException

/** Android SDK boundary. The coordinator can be tested without a radio or framework objects. */
internal interface BlePlatform {
    fun availability(forScan: Boolean): ConnectorException?
    fun scan(filterByService: Boolean, observer: ScanEvents): Resource
    fun connect(deviceId: String, observer: GattEvents): GattLink
}

internal fun interface Resource { fun close() }

internal interface ScanEvents {
    fun found(device: HeartRateDevice)
    fun failed(error: ConnectorException)
}

internal interface GattEvents {
    fun connected()
    fun servicesDiscovered()
    fun subscribed()
    fun packet(value: ByteArray)
    fun disconnected(error: ConnectorException)
}

internal interface GattLink : Resource {
    fun discoverServices()
    /** Validates the service/notify characteristic/CCCD and starts subscription; acknowledgement is asynchronous. */
    fun subscribe()
}

internal interface Scheduler {
    fun execute(action: () -> Unit)
    fun after(delayMillis: Long, action: () -> Unit): Resource
}
