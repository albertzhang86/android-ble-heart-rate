package io.github.albertzhang86.heartrate.testing

import io.github.albertzhang86.heartrate.ConnectorHeartRateMonitor
import io.github.albertzhang86.heartrate.HeartRateConnection
import io.github.albertzhang86.heartrate.HeartRateDevice
import io.github.albertzhang86.heartrate.HeartRateMeasurement
import io.github.albertzhang86.heartrate.connector.ConnectorException
import io.github.albertzhang86.heartrate.connector.HeartRateConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectorHeartRateMonitorTest {
    @Test fun `late readings from a previous connection cannot replace current data`() {
        val connector = RecordingConnector()
        val monitor = ConnectorHeartRateMonitor(connector) { 42L }
        monitor.connect(FakeHeartRateConnector.DEVICE)
        val previous = connector.listeners.last()
        monitor.connect(HeartRateDevice("another", "Another sensor"))
        connector.listeners.last().onMeasurement(HeartRateMeasurement(80))
        previous.onMeasurement(HeartRateMeasurement(190))
        previous.onDisconnected()
        assertEquals(80, monitor.state.value.latestSample?.measurement?.beatsPerMinute)
        assertEquals(42L, monitor.state.value.latestSample?.receivedAtEpochMillis)
        assertEquals(1, connector.connectionCloses)
        monitor.close()
    }

    @Test fun `connector failure clears last sample`() {
        val connector = FakeHeartRateConnector()
        val monitor = ConnectorHeartRateMonitor(connector)
        monitor.connect(FakeHeartRateConnector.DEVICE)
        connector.emit(HeartRateMeasurement(90))
        connector.fail(ConnectorException(ConnectorException.Code.CONNECTION_FAILED, "Connection lost."))
        assertTrue(monitor.state.value.connection is HeartRateConnection.Failed)
        assertNull(monitor.state.value.latestSample)
        monitor.close()
    }

    @Test fun `reconnect clears old data and accepts fresh readings after connection`() {
        val connector = RecordingConnector()
        val monitor = ConnectorHeartRateMonitor(connector)
        monitor.connect(FakeHeartRateConnector.DEVICE)
        val listener = connector.listeners.single()
        listener.onMeasurement(HeartRateMeasurement(90))
        listener.onReconnecting(1)
        assertEquals(HeartRateConnection.Reconnecting(FakeHeartRateConnector.DEVICE, 1), monitor.state.value.connection)
        assertNull(monitor.state.value.latestSample)
        listener.onMeasurement(HeartRateMeasurement(190))
        assertNull(monitor.state.value.latestSample)
        listener.onConnected()
        listener.onMeasurement(HeartRateMeasurement(95))
        assertEquals(95, monitor.state.value.latestSample?.measurement?.beatsPerMinute)
        assertEquals(0, connector.connectionCloses)
        monitor.close()
    }

    @Test fun `stale measurement clears sample while retaining a usable connection`() {
        val connector = RecordingConnector()
        val monitor = ConnectorHeartRateMonitor(connector)
        monitor.connect(FakeHeartRateConnector.DEVICE)
        val listener = connector.listeners.single()
        listener.onMeasurement(HeartRateMeasurement(90))
        listener.onMeasurementUnavailable()
        assertTrue(monitor.state.value.connection is HeartRateConnection.Connected)
        assertNull(monitor.state.value.latestSample)
        listener.onMeasurement(HeartRateMeasurement(95))
        assertEquals(95, monitor.state.value.latestSample?.measurement?.beatsPerMinute)
        monitor.close()
    }

    @Test fun `bounded scan completes flow and closes its session`() = runTest {
        val connector = RecordingConnector()
        val monitor = ConnectorHeartRateMonitor(connector)
        val results = mutableListOf<List<HeartRateDevice>>()
        val collection = backgroundScope.launch { monitor.scan().collect { results.add(it) } }
        runCurrent()
        connector.scanListeners.single().onScanCompleted()
        runCurrent()
        assertTrue(collection.isCompleted)
        assertEquals(listOf(listOf(FakeHeartRateConnector.DEVICE)), results)
        assertEquals(1, connector.scanCloses)
        monitor.close()
    }

    @Test fun `scan cancellation and monitor close release discovery sessions`() = runTest {
        val connector = RecordingConnector()
        val monitor = ConnectorHeartRateMonitor(connector)
        val first = backgroundScope.launch { monitor.scan().collect {} }
        runCurrent()
        first.cancel()
        runCurrent()
        assertEquals(1, connector.scanCloses)
        val second = backgroundScope.launch { monitor.scan().collect {} }
        runCurrent()
        monitor.close()
        runCurrent()
        assertEquals(2, connector.scanCloses)
        assertTrue(second.isCompleted)
    }

    private class RecordingConnector : HeartRateConnector {
        val listeners = mutableListOf<HeartRateConnector.ConnectionObserver>()
        val scanListeners = mutableListOf<HeartRateConnector.ScanObserver>()
        var connectionCloses = 0
        var scanCloses = 0
        override fun getId() = "test.recording"
        override fun scan(observer: HeartRateConnector.ScanObserver): HeartRateConnector.Session {
            scanListeners.add(observer)
            observer.onDeviceDiscovered(FakeHeartRateConnector.DEVICE)
            return HeartRateConnector.Session { scanCloses++ }
        }
        override fun connect(device: HeartRateDevice, observer: HeartRateConnector.ConnectionObserver): HeartRateConnector.Session {
            listeners.add(observer)
            observer.onConnected()
            return HeartRateConnector.Session { connectionCloses++ }
        }
        override fun close() {}
    }
}
