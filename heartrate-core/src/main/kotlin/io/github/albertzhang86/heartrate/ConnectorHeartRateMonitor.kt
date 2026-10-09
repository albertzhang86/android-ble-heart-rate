package io.github.albertzhang86.heartrate

import io.github.albertzhang86.heartrate.connector.ConnectorException
import io.github.albertzhang86.heartrate.connector.HeartRateConnector
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Kotlin Flow facade shared by every Java/Kotlin connector. Owns the supplied connector.
 * Serialize connect/disconnect/close calls on the owner thread; callbacks may arrive on any thread.
 */
class ConnectorHeartRateMonitor(
    private val connector: HeartRateConnector,
    private val clock: () -> Long = System::currentTimeMillis,
) : HeartRateMonitor {
    private val lock = Any()
    private val mutableState = MutableStateFlow(HeartRateState(isSimulated = connector.isSimulated))
    override val state = mutableState.asStateFlow()
    private val scans = mutableSetOf<() -> Unit>()
    private var connection: HeartRateConnector.Session? = null
    private var generation = 0L
    private var closed = false

    override fun scan() = callbackFlow {
        val devices = linkedMapOf<String, HeartRateDevice>()
        val stop: () -> Unit = { close(); Unit }
        synchronized(lock) {
            check(!closed) { "Monitor is closed." }
            scans.add(stop)
        }
        val session = try {
            connector.scan(object : HeartRateConnector.ScanObserver {
                override fun onDeviceDiscovered(device: HeartRateDevice) = synchronized(lock) {
                    devices[device.id] = device
                    trySend(devices.values.toList())
                    Unit
                }
                override fun onError(error: ConnectorException) { close(error) }
            })
        } catch (error: Exception) {
            synchronized(lock) { scans.remove(stop) }
            throw error
        }
        awaitClose {
            synchronized(lock) { scans.remove(stop) }
            session.close()
        }
    }

    override fun connect(device: HeartRateDevice) {
        val previous: HeartRateConnector.Session?
        val token: Long
        synchronized(lock) {
            check(!closed) { "Monitor is closed." }
            previous = detachConnection()
            token = generation
            mutableState.value = HeartRateState(HeartRateConnection.Connecting(device), isSimulated = connector.isSimulated)
        }
        previous?.close()
        val observer = object : HeartRateConnector.ConnectionObserver {
            override fun onConnected() = synchronized(lock) {
                if (token == generation && !closed) {
                    mutableState.value = HeartRateState(HeartRateConnection.Connected(device), isSimulated = connector.isSimulated)
                }
            }

            override fun onMeasurement(measurement: HeartRateMeasurement) = synchronized(lock) {
                if (token == generation && !closed && state.value.connection is HeartRateConnection.Connected) {
                    mutableState.value = state.value.copy(latestSample = HeartRateSample(measurement, clock()))
                }
            }

            override fun onDisconnected() { finish(token, HeartRateConnection.Disconnected) }
            override fun onError(error: ConnectorException) { finish(token, HeartRateConnection.Failed(error)) }
        }
        try {
            val started = connector.connect(device, observer)
            val keep = synchronized(lock) {
                (token == generation && !closed).also { if (it) connection = started }
            }
            // Synchronous failure/disconnect callbacks may invalidate the attempt before return.
            if (!keep) started.close()
        } catch (error: ConnectorException) {
            observer.onError(error)
        }
    }

    private fun finish(token: Long, next: HeartRateConnection) {
        val previous = synchronized(lock) {
            if (token != generation || closed) return
            val handle = detachConnection()
            mutableState.value = HeartRateState(next, isSimulated = connector.isSimulated)
            handle
        }
        previous?.close()
    }

    override fun disconnect() {
        val previous = synchronized(lock) {
            val handle = detachConnection()
            mutableState.value = HeartRateState(isSimulated = connector.isSimulated)
            handle
        }
        previous?.close()
    }

    override fun close() {
        val previous: HeartRateConnector.Session?
        val stopScans: List<() -> Unit>
        synchronized(lock) {
            if (closed) return
            closed = true
            previous = detachConnection()
            mutableState.value = HeartRateState(isSimulated = connector.isSimulated)
            stopScans = scans.toList()
            scans.clear()
        }
        previous?.close()
        stopScans.forEach { it() }
        connector.close()
    }

    /** Invalidate queued callbacks under lock; call third-party cleanup outside our lock. */
    private fun detachConnection(): HeartRateConnector.Session? {
        generation++
        return connection.also { connection = null }
    }
}
