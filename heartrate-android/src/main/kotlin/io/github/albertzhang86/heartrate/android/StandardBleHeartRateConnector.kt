package io.github.albertzhang86.heartrate.android

import android.content.Context
import io.github.albertzhang86.heartrate.android.internal.AndroidBlePlatform
import io.github.albertzhang86.heartrate.android.internal.BleConnector
import io.github.albertzhang86.heartrate.android.internal.HandlerScheduler
import io.github.albertzhang86.heartrate.connector.HeartRateConnector

/**
 * Standard Heart Rate Service (0x180D) connector for Android 8+.
 * The app grants permissions and owns lifecycle; this class has no UI or foreground service.
 * Callbacks run on the main looper and must return promptly.
 */
class StandardBleHeartRateConnector private constructor(
    connector: BleConnector,
) : HeartRateConnector by connector {
    @JvmOverloads
    constructor(context: Context, options: BleHeartRateOptions = BleHeartRateOptions()) :
        this(BleConnector(AndroidBlePlatform(context.applicationContext), HandlerScheduler(), options))
}
