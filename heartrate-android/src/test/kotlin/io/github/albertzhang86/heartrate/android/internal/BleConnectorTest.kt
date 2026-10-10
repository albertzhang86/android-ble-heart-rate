package io.github.albertzhang86.heartrate.android.internal

import io.github.albertzhang86.heartrate.HeartRateDevice
import io.github.albertzhang86.heartrate.android.BleHeartRateOptions
import io.github.albertzhang86.heartrate.connector.ConnectorException
import io.github.albertzhang86.heartrate.connector.ConnectorException.Code
import io.github.albertzhang86.heartrate.connector.HeartRateConnector
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BleConnectorTest {
    private val scheduler = TestScheduler()
    private val platform = TestBlePlatform()
    private val options = BleHeartRateOptions(
        scanDurationMillis = 5_000, connectionTimeoutMillis = 2_000,
        staleMeasurementMillis = 1_000, measurementTimeoutMillis = 3_000,
        reconnectDelayMillis = 100, maxReconnectDelayMillis = 250,
    )
    private val connector = BleConnector(platform, scheduler, options)
    private val device = HeartRateDevice("test-sensor", "Test sensor")
    private val observer = ConnectionRecorder()

    @After fun releaseResources() {
        connector.close()
        scheduler.runCurrent()
        assertEquals("No scheduled work after close", 0, scheduler.pendingTasks)
        platform.links.forEach { assertEquals("Every native link closed exactly once", 1, it.closes) }
        platform.scans.forEach { assertEquals("Every native scan stopped exactly once", 1, it.closes) }
    }

    @Test fun `scan is filtered deduplicated and bounded`() {
        val scan = ScanRecorder()
        connector.scan(scan)
        scheduler.runCurrent()
        val nativeScan = platform.scans.single()
        assertTrue(nativeScan.filtered)
        nativeScan.events.found(device)
        nativeScan.events.found(device)
        nativeScan.events.found(device.copy(name = "Updated name"))
        scheduler.runCurrent()
        assertEquals(listOf(device, device.copy(name = "Updated name")), scan.devices)
        scheduler.advanceBy(5_000)
        nativeScan.events.found(HeartRateDevice("late", null))
        scheduler.runCurrent()
        assertEquals(2, scan.devices.size)
        assertEquals(1, scan.completions)
        assertEquals(1, nativeScan.closes)
    }

    @Test fun `unfiltered discovery is an explicit option`() {
        val unfiltered = BleConnector(platform, scheduler, options.copy(filterByHeartRateService = false))
        unfiltered.scan(ScanRecorder())
        scheduler.runCurrent()
        assertFalse(platform.scans.single().filtered)
        unfiltered.close()
    }

    @Test fun `scan cancellation suppresses already queued callbacks`() {
        val scan = ScanRecorder()
        val session = connector.scan(scan)
        scheduler.runCurrent()
        platform.scans.single().events.found(device)
        session.close()
        session.close()
        scheduler.runCurrent()
        assertTrue(scan.devices.isEmpty())
        assertEquals(0, scan.completions)
        assertEquals(0, scheduler.pendingTasks)
    }

    @Test fun `scan preflight failure never opens a radio session`() {
        platform.scanUnavailable = error(Code.PERMISSION_DENIED)
        val scan = ScanRecorder()
        connector.scan(scan)
        scheduler.runCurrent()
        assertEquals(Code.PERMISSION_DENIED, scan.errors.single().code)
        assertTrue(platform.scans.isEmpty())
    }

    @Test fun `native scan start failure is reported without leaving timers`() {
        platform.scanStartError = error(Code.UNAVAILABLE)
        val scan = ScanRecorder()
        connector.scan(scan)
        scheduler.runCurrent()
        assertEquals(Code.UNAVAILABLE, scan.errors.single().code)
        assertEquals(0, scheduler.pendingTasks)
    }

    @Test fun `native scan failure is terminal`() {
        val scan = ScanRecorder()
        connector.scan(scan)
        scheduler.runCurrent()
        platform.scans.single().events.failed(error(Code.UNAVAILABLE))
        scheduler.runCurrent()
        assertEquals(Code.UNAVAILABLE, scan.errors.single().code)
        assertEquals(1, platform.scans.single().closes)
        assertEquals(0, scan.completions)
    }

    @Test fun `scan stops when permissions are revoked`() {
        val scan = ScanRecorder()
        connector.scan(scan)
        scheduler.runCurrent()
        platform.scanUnavailable = error(Code.PERMISSION_DENIED)
        scheduler.advanceBy(1_000)
        assertEquals(Code.PERMISSION_DENIED, scan.errors.single().code)
        assertEquals(1, platform.scans.single().closes)
    }

    @Test fun `selecting a sensor completes discovery`() {
        val scan = ScanRecorder()
        connector.scan(scan)
        scheduler.runCurrent()
        connect()
        assertEquals(1, scan.completions)
        assertEquals(1, platform.scans.single().closes)
    }

    @Test fun `connected means service discovery and notification subscription succeeded`() {
        connect()
        val link = platform.links.single()
        link.events.packet(byteArrayOf(0, 70)) // Ignore premature packets before service discovery.
        scheduler.runCurrent()
        assertTrue(observer.events.isEmpty())
        link.events.connected()
        scheduler.runCurrent()
        assertEquals(listOf("discover"), link.operations)
        link.events.servicesDiscovered()
        scheduler.runCurrent()
        assertEquals(listOf("discover", "subscribe"), link.operations)
        assertTrue(observer.events.isEmpty())
        // A sensor can notify between the write request and Android's descriptor-write callback.
        val packet = byteArrayOf(0, 85)
        link.events.packet(packet)
        packet[1] = 120
        link.events.subscribed()
        scheduler.runCurrent()
        assertEquals(listOf("connected", "bpm:85"), observer.events)
    }

    @Test fun `missing service or notification support is terminal without retry`() {
        connect()
        val link = platform.links.single()
        link.subscriptionError = error(Code.UNSUPPORTED)
        link.events.connected()
        link.events.servicesDiscovered()
        scheduler.runCurrent()
        assertEquals(listOf("error:UNSUPPORTED"), observer.events)
        assertEquals(1, link.closes)
        scheduler.advanceBy(10_000)
        assertEquals(1, platform.connectCalls)
    }

    @Test fun `connection preflight and native permission failures do not retry`() {
        platform.connectionUnavailable = error(Code.PERMISSION_DENIED)
        connect()
        assertEquals(Code.PERMISSION_DENIED, observer.errors.single().code)
        assertEquals(0, platform.connectCalls)
        platform.connectionUnavailable = null
        platform.connectStartError = error(Code.PERMISSION_DENIED)
        connect()
        assertEquals(2, observer.errors.size)
        assertEquals(0, scheduler.pendingTasks)
    }

    @Test fun `discovery operation failure retries after releasing the native link`() {
        connect()
        val first = platform.links.single()
        first.discoveryError = error(Code.CONNECTION_FAILED)
        first.events.connected()
        scheduler.runCurrent()
        assertEquals(listOf("retry:1"), observer.events)
        assertEquals(1, first.closes)
        scheduler.advanceBy(100)
        assertEquals(2, platform.links.size)
    }

    @Test fun `connection deadline includes service discovery and subscription acknowledgement`() {
        connect()
        platform.links.single().events.connected()
        platform.links.single().events.servicesDiscovered()
        scheduler.runCurrent()
        scheduler.advanceBy(2_000)
        assertEquals(listOf("retry:1"), observer.events)
        assertEquals(1, platform.links.single().closes)
    }

    @Test fun `retry backoff is bounded and eventually reports failure`() {
        connect()
        listOf(100L, 200L, 250L).forEachIndexed { index, delay ->
            platform.links.last().events.disconnected(error(Code.CONNECTION_FAILED))
            scheduler.runCurrent()
            assertEquals("retry:${index + 1}", observer.events.last())
            scheduler.advanceBy(delay - 1)
            assertEquals(index + 1, platform.links.size)
            scheduler.advanceBy(1)
            assertEquals(index + 2, platform.links.size)
        }
        platform.links.last().events.disconnected(error(Code.CONNECTION_FAILED))
        scheduler.runCurrent()
        assertEquals(Code.CONNECTION_FAILED, observer.errors.single().code)
        scheduler.advanceBy(60_000)
        assertEquals(4, platform.links.size)
    }

    @Test fun `valid measurement resets retry budget but merely connecting does not`() {
        connect()
        platform.links.last().events.disconnected(error(Code.CONNECTION_FAILED))
        scheduler.runCurrent()
        scheduler.advanceBy(100)
        ready()
        platform.links.last().events.disconnected(error(Code.CONNECTION_FAILED))
        scheduler.runCurrent()
        assertEquals("retry:2", observer.events.last())
        scheduler.advanceBy(200)
        ready()
        platform.links.last().events.packet(byteArrayOf(0, 90))
        scheduler.runCurrent()
        platform.links.last().events.disconnected(error(Code.CONNECTION_FAILED))
        scheduler.runCurrent()
        assertEquals("retry:1", observer.events.last())
    }

    @Test fun `callbacks from a released link cannot affect its replacement`() {
        connect()
        val first = platform.links.single()
        first.events.disconnected(error(Code.CONNECTION_FAILED))
        scheduler.runCurrent()
        scheduler.advanceBy(100)
        ready()
        platform.links.last().events.packet(byteArrayOf(0, 90))
        first.events.connected()
        first.events.servicesDiscovered()
        first.events.subscribed()
        first.events.packet(byteArrayOf(0, 180.toByte()))
        first.events.disconnected(error(Code.CONNECTION_FAILED))
        scheduler.runCurrent()
        assertEquals(listOf("retry:1", "connected", "bpm:90"), observer.events)
    }

    @Test fun `stale readings are invalidated and silence triggers reconnect`() {
        connect()
        ready()
        platform.links.last().events.packet(byteArrayOf(0, 90))
        scheduler.runCurrent()
        scheduler.advanceBy(999)
        assertEquals("bpm:90", observer.events.last())
        scheduler.advanceBy(1)
        assertEquals("stale", observer.events.last())
        scheduler.advanceBy(2_000)
        assertEquals("retry:1", observer.events.last())
    }

    @Test fun `new measurements refresh freshness and silence deadlines`() {
        connect()
        ready()
        repeat(5) {
            scheduler.advanceBy(900)
            platform.links.last().events.packet(byteArrayOf(0, 90))
            scheduler.runCurrent()
        }
        assertFalse(observer.events.contains("stale"))
        assertEquals(1, platform.links.size)
        scheduler.advanceBy(1_000)
        assertEquals("stale", observer.events.last())
    }

    @Test fun `malformed packet fails without retaining a live connection`() {
        connect()
        ready()
        platform.links.single().events.packet(byteArrayOf(1))
        scheduler.runCurrent()
        assertEquals(Code.PROTOCOL_ERROR, observer.errors.single().code)
        assertEquals(1, platform.links.single().closes)
        assertEquals(0, scheduler.pendingTasks)
    }

    @Test fun `adapter off terminates connection and pending retry`() {
        connect()
        ready()
        platform.connectionUnavailable = error(Code.UNAVAILABLE)
        scheduler.advanceBy(1_000)
        assertEquals(Code.UNAVAILABLE, observer.errors.single().code)
        assertEquals(0, scheduler.pendingTasks)
        assertEquals(1, platform.links.single().closes)
    }

    @Test fun `revoked permission wins over a transient disconnect`() {
        connect()
        platform.connectionUnavailable = error(Code.PERMISSION_DENIED)
        platform.links.single().events.disconnected(error(Code.CONNECTION_FAILED))
        scheduler.runCurrent()
        assertEquals(listOf("error:PERMISSION_DENIED"), observer.events)
    }

    @Test fun `closing during backoff cancels reconnection`() {
        val session = connect()
        platform.links.single().events.disconnected(error(Code.CONNECTION_FAILED))
        scheduler.runCurrent()
        session.close()
        scheduler.advanceBy(60_000)
        assertEquals(1, platform.links.size)
        assertEquals(listOf("retry:1"), observer.events)
    }

    @Test fun `close suppresses queued measurements immediately`() {
        connect()
        ready()
        platform.links.single().events.packet(byteArrayOf(0, 90))
        connector.close()
        scheduler.runCurrent()
        assertEquals(listOf("connected"), observer.events)
        assertThrows(IllegalStateException::class.java) { connector.scan(ScanRecorder()) }
        assertThrows(IllegalStateException::class.java) { connector.connect(device, observer) }
    }

    @Test fun `closing before scheduled start does not open resources`() {
        connector.scan(ScanRecorder())
        connector.connect(device, observer)
        connector.close()
        scheduler.runCurrent()
        assertTrue(platform.scans.isEmpty())
        assertTrue(platform.links.isEmpty())
    }

    @Test fun `cancelled queued connect leaves the current connection alone`() {
        connect()
        ready()
        connector.connect(device.copy(id = "second"), ConnectionRecorder()).close()
        scheduler.runCurrent()
        assertEquals(0, platform.links.single().closes)
        platform.links.single().events.packet(byteArrayOf(0, 90))
        scheduler.runCurrent()
        assertEquals("bpm:90", observer.events.last())
    }

    @Test fun `new connection closes old handle and ignores late packets`() {
        connect()
        ready()
        val old = platform.links.single()
        val next = ConnectionRecorder()
        platform.onConnect = { assertEquals("Old GATT must close before opening another", 1, old.closes) }
        connector.connect(device.copy(id = "second"), next)
        scheduler.runCurrent()
        old.events.packet(byteArrayOf(0, 90))
        ready()
        assertEquals(listOf("connected"), observer.events)
        assertEquals(listOf("connected"), next.events)
        assertEquals(1, old.closes)
    }

    @Test fun `retry can be disabled`() {
        val noRetry = BleConnector(platform, scheduler, options.copy(maxReconnectAttempts = 0))
        noRetry.connect(device, observer)
        scheduler.runCurrent()
        scheduler.advanceBy(2_000)
        assertEquals(listOf("error:TIMEOUT"), observer.events)
        noRetry.close()
    }

    private fun connect(): HeartRateConnector.Session = connector.connect(device, observer).also { scheduler.runCurrent() }
    private fun ready() {
        platform.links.last().events.connected()
        platform.links.last().events.servicesDiscovered()
        platform.links.last().events.subscribed()
        scheduler.runCurrent()
    }
    private fun error(code: Code) = ConnectorException(code, "Synthetic test failure.")
}
