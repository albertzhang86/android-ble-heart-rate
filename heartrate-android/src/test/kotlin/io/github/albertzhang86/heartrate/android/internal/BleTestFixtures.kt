package io.github.albertzhang86.heartrate.android.internal

import io.github.albertzhang86.heartrate.HeartRateDevice
import io.github.albertzhang86.heartrate.HeartRateMeasurement
import io.github.albertzhang86.heartrate.connector.ConnectorException
import io.github.albertzhang86.heartrate.connector.HeartRateConnector
import java.util.PriorityQueue

/** Deterministic event loop: transport callbacks and cancellation use the same queue as production. */
internal class TestScheduler : Scheduler {
    private data class Task(val time: Long, val order: Long, val action: () -> Unit, var cancelled: Boolean = false)
    private val tasks = PriorityQueue(compareBy<Task> { it.time }.thenBy { it.order })
    private var sequence = 0L
    var now = 0L
        private set
    val pendingTasks get() = tasks.count { !it.cancelled }

    override fun execute(action: () -> Unit) { after(0, action) }
    override fun after(delayMillis: Long, action: () -> Unit): Resource {
        val task = Task(now + delayMillis, sequence++, action)
        tasks.add(task)
        return Resource { task.cancelled = true }
    }

    fun runCurrent() = advanceBy(0)
    fun advanceBy(duration: Long) {
        val target = now + duration
        while ((tasks.peek()?.time ?: Long.MAX_VALUE) <= target) {
            val task = tasks.remove()
            now = task.time
            if (!task.cancelled) task.action()
        }
        now = target
    }
}

internal class TestBlePlatform : BlePlatform {
    val scans = mutableListOf<Scan>()
    val links = mutableListOf<Link>()
    var scanUnavailable: ConnectorException? = null
    var connectionUnavailable: ConnectorException? = null
    var scanStartError: ConnectorException? = null
    var connectStartError: ConnectorException? = null
    var connectCalls = 0
    var onConnect: () -> Unit = {}

    override fun availability(forScan: Boolean) = if (forScan) scanUnavailable else connectionUnavailable
    override fun scan(filterByService: Boolean, observer: ScanEvents): Resource {
        scanStartError?.let { throw it }
        return Scan(filterByService, observer).also(scans::add)
    }
    override fun connect(deviceId: String, observer: GattEvents): GattLink {
        connectCalls++
        onConnect()
        connectStartError?.let { throw it }
        return Link(deviceId, observer).also(links::add)
    }

    class Scan(val filtered: Boolean, val events: ScanEvents) : Resource {
        var closes = 0
        override fun close() { closes++ }
    }

    class Link(val deviceId: String, val events: GattEvents) : GattLink {
        val operations = mutableListOf<String>()
        var discoveryError: ConnectorException? = null
        var subscriptionError: ConnectorException? = null
        var closes = 0
        override fun discoverServices() {
            operations.add("discover")
            discoveryError?.let { throw it }
        }
        override fun subscribe() {
            operations.add("subscribe")
            subscriptionError?.let { throw it }
        }
        override fun close() { closes++ }
    }
}

internal class ScanRecorder : HeartRateConnector.ScanObserver {
    val devices = mutableListOf<HeartRateDevice>()
    val errors = mutableListOf<ConnectorException>()
    var completions = 0
    override fun onDeviceDiscovered(device: HeartRateDevice) { devices.add(device) }
    override fun onError(error: ConnectorException) { errors.add(error) }
    override fun onScanCompleted() { completions++ }
}

internal class ConnectionRecorder : HeartRateConnector.ConnectionObserver {
    val events = mutableListOf<String>()
    val errors = mutableListOf<ConnectorException>()
    override fun onConnected() { events.add("connected") }
    override fun onMeasurement(measurement: HeartRateMeasurement) { events.add("bpm:${measurement.beatsPerMinute}") }
    override fun onDisconnected() { events.add("disconnected") }
    override fun onError(error: ConnectorException) { errors.add(error); events.add("error:${error.code}") }
    override fun onReconnecting(attempt: Int) { events.add("retry:$attempt") }
    override fun onMeasurementUnavailable() { events.add("stale") }
}
