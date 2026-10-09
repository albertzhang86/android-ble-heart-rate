package io.github.albertzhang86.heartrate

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** UI-free boundary for one connected sensor. Implementations never request permissions or show dialogs. */
interface HeartRateMonitor : AutoCloseable {
    val state: StateFlow<HeartRateState>

    /** Cold scan: collect to start; cancel collection to stop. IDs are opaque and must not be logged. */
    fun scan(): Flow<List<HeartRateDevice>>

    /** Starts a connection, replacing the previous sample. Observe state for success or failure. */
    fun connect(device: HeartRateDevice)

    /** Releases the connection and clears the last sample. */
    fun disconnect()

    /** Terminal, idempotent cleanup. Also stops scanning and clears the last sample. */
    override fun close()
}

data class HeartRateDevice(val id: String, val name: String?)

sealed interface HeartRateConnection {
    data object Disconnected : HeartRateConnection
    data class Failed(val error: io.github.albertzhang86.heartrate.connector.ConnectorException) : HeartRateConnection
    data class Connecting(val device: HeartRateDevice) : HeartRateConnection
    data class Connected(val device: HeartRateDevice) : HeartRateConnection
}

data class HeartRateState(
    val connection: HeartRateConnection = HeartRateConnection.Disconnected,
    val latestSample: HeartRateSample? = null,
    val isSimulated: Boolean = false,
)

data class HeartRateSample(
    val measurement: HeartRateMeasurement,
    /** Reception time supplied by the adapter; the BLE measurement does not contain a timestamp. */
    val receivedAtEpochMillis: Long,
)

data class HeartRateMeasurement @JvmOverloads constructor(
    val beatsPerMinute: Int,
    val sensorContact: SensorContact = SensorContact.NOT_SUPPORTED,
    val energyExpendedKiloJoules: Int? = null,
    val rrIntervalsSeconds: List<Double> = emptyList(),
)

enum class SensorContact { NOT_SUPPORTED, NOT_DETECTED, DETECTED }
