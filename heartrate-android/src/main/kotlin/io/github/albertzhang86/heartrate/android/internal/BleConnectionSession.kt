package io.github.albertzhang86.heartrate.android.internal

import io.github.albertzhang86.heartrate.HeartRateDevice
import io.github.albertzhang86.heartrate.android.BleHeartRateOptions
import io.github.albertzhang86.heartrate.connector.ConnectorException
import io.github.albertzhang86.heartrate.connector.ConnectorException.Code
import io.github.albertzhang86.heartrate.connector.HeartRateConnector
import io.github.albertzhang86.heartrate.protocol.HeartRateMeasurementParser

internal class BleConnectionSession(
    private val platform: BlePlatform,
    scheduler: Scheduler,
    private val options: BleHeartRateOptions,
    private val device: HeartRateDevice,
    private val observer: HeartRateConnector.ConnectionObserver,
    removed: (ManagedSession) -> Unit,
) : ManagedSession(scheduler, removed) {
    private enum class Phase { CONNECTING, DISCOVERING, SUBSCRIBING, READY, RETRYING }
    private var phase = Phase.CONNECTING
    private var link: GattLink? = null
    private var generation = 0
    private var retries = 0
    private var pendingPacket: ByteArray? = null
    private var connectionTimeout: Resource? = null
    private var staleTimeout: Resource? = null
    private var measurementTimeout: Resource? = null
    private var retry: Resource? = null
    private var healthCheck: Resource? = null

    override fun start() {
        if (closed) return
        checkAvailability()
        attempt()
    }

    private fun attempt() {
        if (closed) return
        platform.availability(forScan = false)?.let { fail(it); return }
        phase = Phase.CONNECTING
        val token = ++generation
        fun event(action: () -> Unit) = dispatch { if (token == generation) action() }
        try {
            link = platform.connect(device.id, object : GattEvents {
                override fun connected() = event {
                    if (phase == Phase.CONNECTING) {
                        phase = Phase.DISCOVERING
                        perform { link?.discoverServices() }
                    }
                }
                override fun servicesDiscovered() = event {
                    if (phase == Phase.DISCOVERING) {
                        phase = Phase.SUBSCRIBING
                        perform { link?.subscribe() }
                    }
                }
                override fun subscribed() = event {
                    if (phase == Phase.SUBSCRIBING) {
                        connectionTimeout?.close()
                        phase = Phase.READY
                        deliver { observer.onConnected() }
                        refreshMeasurementDeadlines()
                        pendingPacket?.let { consume(it) }
                        pendingPacket = null
                    }
                }
                override fun packet(value: ByteArray) {
                    val copy = value.copyOf()
                    event {
                        when (phase) {
                            Phase.SUBSCRIBING -> pendingPacket = copy
                            Phase.READY -> consume(copy)
                            else -> Unit
                        }
                    }
                }
                override fun disconnected(error: ConnectorException) = event { recover(error) }
            })
            connectionTimeout = scheduler.after(options.connectionTimeoutMillis) {
                if (!closed && token == generation) recover(ConnectorException(Code.TIMEOUT, "Sensor connection timed out."))
            }
        } catch (error: ConnectorException) { recover(error) }
    }

    private fun consume(packet: ByteArray) {
        val measurement = try {
            HeartRateMeasurementParser.parse(packet)
        } catch (_: IllegalArgumentException) {
            fail(ConnectorException(Code.PROTOCOL_ERROR, "Sensor sent an invalid heart-rate measurement."))
            return
        }
        retries = 0
        refreshMeasurementDeadlines()
        deliver { observer.onMeasurement(measurement) }
    }

    private fun refreshMeasurementDeadlines() {
        staleTimeout?.close()
        measurementTimeout?.close()
        staleTimeout = scheduler.after(options.staleMeasurementMillis) {
            if (!closed && phase == Phase.READY) deliver { observer.onMeasurementUnavailable() }
        }
        measurementTimeout = scheduler.after(options.measurementTimeoutMillis) {
            if (!closed && phase == Phase.READY) recover(ConnectorException(Code.TIMEOUT, "Sensor stopped sending heart-rate measurements."))
        }
    }

    private fun perform(operation: () -> Unit) {
        try { operation() } catch (error: ConnectorException) { recover(error) }
    }

    private fun recover(error: ConnectorException) {
        releaseLink()
        val unavailable = platform.availability(forScan = false)
        if (unavailable != null) { fail(unavailable); return }
        val transient = error.code in setOf(Code.CONNECTION_FAILED, Code.TIMEOUT)
        if (!transient || retries >= options.maxReconnectAttempts) { fail(error); return }
        retries++
        phase = Phase.RETRYING
        deliver { observer.onReconnecting(retries) }
        val multiplier = 1L shl (retries - 1)
        val delay = if (options.reconnectDelayMillis > options.maxReconnectDelayMillis / multiplier) {
            options.maxReconnectDelayMillis
        } else options.reconnectDelayMillis * multiplier
        retry = scheduler.after(delay) { if (!closed) attempt() }
    }

    private fun checkAvailability() {
        healthCheck = scheduler.after(1_000) {
            if (!closed) {
                val error = platform.availability(forScan = false)
                if (error != null) fail(error) else checkAvailability()
            }
        }
    }

    private fun fail(error: ConnectorException) = finish { observer.onError(error) }

    private fun releaseLink() {
        generation++
        connectionTimeout?.close()
        staleTimeout?.close()
        measurementTimeout?.close()
        pendingPacket = null
        link?.close()
        link = null
    }

    override fun cleanup() {
        retry?.close()
        healthCheck?.close()
        releaseLink()
    }
}
