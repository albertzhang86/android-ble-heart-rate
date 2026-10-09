package io.github.albertzhang86.heartrate.testing

import io.github.albertzhang86.heartrate.HeartRateDevice
import io.github.albertzhang86.heartrate.HeartRateMeasurement
import io.github.albertzhang86.heartrate.connector.ConnectorException
import io.github.albertzhang86.heartrate.connector.HeartRateConnector
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/**
 * Extend with a deterministic connector fixture. Its emit method must drain resulting callbacks
 * before returning. Real-device qualification is separate from these shared lifecycle checks.
 */
abstract class HeartRateConnectorContractTest {
    interface Fixture : AutoCloseable {
        val connector: HeartRateConnector
        val device: HeartRateDevice
        fun emit(measurement: HeartRateMeasurement)
        override fun close() = connector.close()
    }

    abstract fun createFixture(): Fixture

    @Test fun `connector reports connected before measurements`() {
        createFixture().use { fixture ->
            val connected = CompletableFuture<Unit>()
            val reading = CompletableFuture<HeartRateMeasurement>()
            val listener = object : HeartRateConnector.ConnectionObserver {
                override fun onConnected() { connected.complete(Unit) }
                override fun onMeasurement(measurement: HeartRateMeasurement) {
                    if (!connected.isDone) reading.completeExceptionally(AssertionError("Measurement preceded connection."))
                    else reading.complete(measurement)
                }
                override fun onDisconnected() {}
                override fun onError(error: ConnectorException) { connected.completeExceptionally(error) }
            }
            fixture.connector.connect(fixture.device, listener).use {
                connected.get(5, TimeUnit.SECONDS)
                val expected = HeartRateMeasurement(83)
                fixture.emit(expected)
                assertEquals(expected, reading.get(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `closed connection stops delivery and can be closed repeatedly`() {
        createFixture().use { fixture ->
            val count = AtomicInteger()
            val listener = object : HeartRateConnector.ConnectionObserver {
                override fun onConnected() {}
                override fun onMeasurement(measurement: HeartRateMeasurement) { count.incrementAndGet() }
                override fun onDisconnected() {}
                override fun onError(error: ConnectorException) {}
            }
            val session = fixture.connector.connect(fixture.device, listener)
            session.close()
            session.close()
            fixture.emit(HeartRateMeasurement(90))
            assertEquals(0, count.get())
        }
    }

    @Test fun `connector cleanup is idempotent`() {
        createFixture().use { fixture ->
            fixture.connector.close()
            fixture.connector.close()
        }
    }
}
