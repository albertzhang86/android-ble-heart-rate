package io.github.albertzhang86.heartrate.testing

import io.github.albertzhang86.heartrate.ConnectorHeartRateMonitor
import io.github.albertzhang86.heartrate.HeartRateDevice
import io.github.albertzhang86.heartrate.HeartRateMeasurement
import io.github.albertzhang86.heartrate.HeartRateMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Demo driver of the plain-Java fake connector, using the same facade as a physical connector. */
class SimulatedHeartRateMonitor(
    parentScope: CoroutineScope,
    private val samples: List<Int> = (112..129).toList(),
    private val intervalMillis: Long = 2_000,
    clock: () -> Long = System::currentTimeMillis,
) : HeartRateMonitor {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val connector = FakeHeartRateConnector()
    private val monitor = ConnectorHeartRateMonitor(connector, clock)
    override val state = monitor.state
    private val lock = Any()
    private var sampling: Job? = null
    private var closed = false

    init {
        require(samples.isNotEmpty() && samples.all { it in 0..65535 }) { "Provide unsigned heart-rate samples." }
        require(intervalMillis > 0) { "Sampling interval must be positive." }
    }

    override fun scan() = monitor.scan()

    override fun connect(device: HeartRateDevice) = synchronized(lock) {
        check(!closed) { "Monitor is closed." }
        sampling?.cancel()
        monitor.connect(device)
        if (state.value.connection is io.github.albertzhang86.heartrate.HeartRateConnection.Connected) {
            connector.emit(HeartRateMeasurement(samples.first()))
            sampling = scope.launch {
                var index = 1
                while (isActive) {
                    delay(intervalMillis)
                    synchronized(lock) {
                        if (isActive && !closed) connector.emit(HeartRateMeasurement(samples[index++ % samples.size]))
                    }
                }
            }
        }
    }

    override fun disconnect() = synchronized(lock) {
        sampling?.cancel()
        sampling = null
        monitor.disconnect()
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            scope.cancel()
            monitor.close()
        }
    }

    companion object {
        val DEVICE: HeartRateDevice = FakeHeartRateConnector.DEVICE
    }
}
