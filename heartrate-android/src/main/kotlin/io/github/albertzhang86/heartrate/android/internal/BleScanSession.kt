package io.github.albertzhang86.heartrate.android.internal

import io.github.albertzhang86.heartrate.HeartRateDevice
import io.github.albertzhang86.heartrate.android.BleHeartRateOptions
import io.github.albertzhang86.heartrate.connector.ConnectorException
import io.github.albertzhang86.heartrate.connector.HeartRateConnector

internal class BleScanSession(
    private val platform: BlePlatform,
    scheduler: Scheduler,
    private val options: BleHeartRateOptions,
    private val observer: HeartRateConnector.ScanObserver,
    removed: (ManagedSession) -> Unit,
) : ManagedSession(scheduler, removed) {
    private var scan: Resource? = null
    private var timeout: Resource? = null
    private var healthCheck: Resource? = null
    private val seen = mutableMapOf<String, HeartRateDevice>()

    override fun start() {
        if (closed) return
        platform.availability(forScan = true)?.let { fail(it); return }
        try {
            scan = platform.scan(options.filterByHeartRateService, object : ScanEvents {
                override fun found(device: HeartRateDevice) = dispatch {
                    if (seen[device.id] != device) {
                        seen[device.id] = device
                        deliver { observer.onDeviceDiscovered(device) }
                    }
                }
                override fun failed(error: ConnectorException) = dispatch { fail(error) }
            })
            timeout = scheduler.after(options.scanDurationMillis) { if (!closed) complete() }
            checkAvailability()
        } catch (error: ConnectorException) { fail(error) }
    }

    fun complete() = finish { observer.onScanCompleted() }

    private fun checkAvailability() {
        healthCheck = scheduler.after(1_000) {
            if (!closed) {
                val error = platform.availability(forScan = true)
                if (error != null) fail(error) else checkAvailability()
            }
        }
    }

    private fun fail(error: ConnectorException) = finish { observer.onError(error) }

    override fun cleanup() {
        timeout?.close()
        healthCheck?.close()
        scan?.close()
        scan = null
    }
}
