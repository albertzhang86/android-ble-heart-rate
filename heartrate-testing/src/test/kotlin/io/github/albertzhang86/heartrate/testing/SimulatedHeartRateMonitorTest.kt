package io.github.albertzhang86.heartrate.testing

import io.github.albertzhang86.heartrate.HeartRateConnection
import io.github.albertzhang86.heartrate.HeartRateDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedHeartRateMonitorTest {
    @Test fun `scan connects and emits timestamped samples in order`() = runTest {
        val monitor = SimulatedHeartRateMonitor(backgroundScope, listOf(75, 125), 1_000) { testScheduler.currentTime }
        assertEquals(listOf(SimulatedHeartRateMonitor.DEVICE), monitor.scan().first())
        monitor.connect(SimulatedHeartRateMonitor.DEVICE)
        assertEquals(75, monitor.state.value.latestSample?.measurement?.beatsPerMinute)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(125, monitor.state.value.latestSample?.measurement?.beatsPerMinute)
        assertEquals(1_000L, monitor.state.value.latestSample?.receivedAtEpochMillis)
        assertTrue(monitor.state.value.isSimulated)
        monitor.close()
    }

    @Test fun `disconnect clears readings and stops generation until reconnect`() = runTest {
        val monitor = SimulatedHeartRateMonitor(backgroundScope)
        monitor.connect(SimulatedHeartRateMonitor.DEVICE)
        runCurrent()
        monitor.disconnect()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(HeartRateConnection.Disconnected, monitor.state.value.connection)
        assertNull(monitor.state.value.latestSample)
        monitor.connect(SimulatedHeartRateMonitor.DEVICE)
        assertEquals(112, monitor.state.value.latestSample?.measurement?.beatsPerMinute)
        monitor.close()
    }

    @Test fun `close is terminal and clears the current reading`() = runTest {
        val monitor = SimulatedHeartRateMonitor(backgroundScope)
        monitor.connect(SimulatedHeartRateMonitor.DEVICE)
        monitor.close()
        monitor.close()
        assertNull(monitor.state.value.latestSample)
        assertTrue(runCatching { monitor.connect(SimulatedHeartRateMonitor.DEVICE) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { monitor.scan().first() }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun `unknown sensors do not become connected`() = runTest {
        val monitor = SimulatedHeartRateMonitor(backgroundScope)
        monitor.connect(HeartRateDevice("unknown", null))
        assertTrue(monitor.state.value.connection is HeartRateConnection.Failed)
        monitor.close()
    }
}
