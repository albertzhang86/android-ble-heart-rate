package io.github.albertzhang86.heartrate.android

import java.util.UUID

/** Standard Bluetooth SIG identifiers, shared by future scanner and GATT transport implementations. */
object HeartRateGatt {
    val SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val CLIENT_CONFIGURATION: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
