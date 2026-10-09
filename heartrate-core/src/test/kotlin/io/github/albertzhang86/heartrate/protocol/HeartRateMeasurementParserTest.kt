package io.github.albertzhang86.heartrate.protocol

import io.github.albertzhang86.heartrate.SensorContact
import org.junit.Assert.*
import org.junit.Test

class HeartRateMeasurementParserTest {
    @Test fun `unsigned eight-bit heart rate is preserved`() {
        val measurement = HeartRateMeasurementParser.parse(byteArrayOf(0, 200.toByte()))
        assertEquals(200, measurement.beatsPerMinute)
        assertEquals(SensorContact.NOT_SUPPORTED, measurement.sensorContact)
        assertNull(measurement.energyExpendedKiloJoules)
        assertTrue(measurement.rrIntervalsSeconds.isEmpty())
    }

    @Test fun `sixteen-bit heart rate energy and multiple RR intervals decode little endian`() {
        val measurement = HeartRateMeasurementParser.parse(byteArrayOf(0x1f, 0x2c, 0x01, 0x34, 0x12, 0x00, 0x04, 0x00, 0x02))
        assertEquals(300, measurement.beatsPerMinute)
        assertEquals(SensorContact.DETECTED, measurement.sensorContact)
        assertEquals(0x1234, measurement.energyExpendedKiloJoules)
        assertEquals(listOf(1.0, 0.5), measurement.rrIntervalsSeconds)
    }

    @Test fun `supported sensor reports missing contact`() {
        assertEquals(SensorContact.NOT_DETECTED, HeartRateMeasurementParser.parse(byteArrayOf(4, 80)).sensorContact)
    }

    @Test fun `malformed payloads fail rather than fabricate readings`() {
        val malformed = listOf(
            byteArrayOf(), byteArrayOf(0), byteArrayOf(1, 80),
            byteArrayOf(8, 80, 1), byteArrayOf(16, 80),
            byteArrayOf(16, 80, 1), byteArrayOf(0, 80, 1),
        )
        malformed.forEach { packet ->
            assertThrows(IllegalArgumentException::class.java) { HeartRateMeasurementParser.parse(packet) }
        }
    }
}
