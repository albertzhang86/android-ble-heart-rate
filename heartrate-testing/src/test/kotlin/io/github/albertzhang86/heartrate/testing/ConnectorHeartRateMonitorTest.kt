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
        var connectionCloses = 0
        var scanCloses = 0
        override fun getId() = "test.recording"
        override fun scan(observer: HeartRateConnector.ScanObserver): HeartRateConnector.Session {
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
