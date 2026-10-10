package io.github.albertzhang86.heartrate.android

/** Timing limits in milliseconds. Reconnect attempts are bounded per outage, reset by valid data. */
data class BleHeartRateOptions @JvmOverloads constructor(
    val scanDurationMillis: Long = 10_000,
    val connectionTimeoutMillis: Long = 15_000,
    val staleMeasurementMillis: Long = 10_000,
    val measurementTimeoutMillis: Long = 30_000,
    val maxReconnectAttempts: Int = 3,
    val reconnectDelayMillis: Long = 1_000,
    val maxReconnectDelayMillis: Long = 8_000,
    /** Disable only to discover devices that omit the service UUID in their advertisements. */
    val filterByHeartRateService: Boolean = true,
) {
    init {
        require(scanDurationMillis > 0 && connectionTimeoutMillis > 0)
        require(staleMeasurementMillis > 0 && measurementTimeoutMillis > staleMeasurementMillis)
        require(maxReconnectAttempts in 0..10)
        require(reconnectDelayMillis > 0 && maxReconnectDelayMillis >= reconnectDelayMillis)
    }
}
