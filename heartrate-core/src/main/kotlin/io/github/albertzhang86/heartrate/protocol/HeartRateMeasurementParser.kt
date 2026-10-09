package io.github.albertzhang86.heartrate.protocol

import io.github.albertzhang86.heartrate.HeartRateMeasurement
import io.github.albertzhang86.heartrate.SensorContact

/** Decodes characteristic 0x2A37. Malformed or truncated packets throw IllegalArgumentException. */
object HeartRateMeasurementParser {
    fun parse(packet: ByteArray): HeartRateMeasurement {
        var offset = 0
        fun readByte(): Int {
            require(offset < packet.size) { "Truncated heart-rate measurement at byte $offset." }
            return packet[offset++].toInt() and 0xff
        }
        fun readShort(): Int = readByte() or (readByte() shl 8)

        val flags = readByte()
        val bpm = if (flags and 0x01 != 0) readShort() else readByte()
        val contact = when {
            flags and 0x04 == 0 -> SensorContact.NOT_SUPPORTED
            flags and 0x02 == 0 -> SensorContact.NOT_DETECTED
            else -> SensorContact.DETECTED
        }
        val energy = if (flags and 0x08 != 0) readShort() else null
        val intervals = buildList {
            if (flags and 0x10 != 0) {
                require(offset < packet.size) { "RR flag is set without an interval." }
                while (offset < packet.size) add(readShort() / 1024.0)
            }
        }
        require(offset == packet.size) { "Unexpected bytes after heart-rate measurement." }
        return HeartRateMeasurement(bpm, contact, energy, intervals)
    }
}
