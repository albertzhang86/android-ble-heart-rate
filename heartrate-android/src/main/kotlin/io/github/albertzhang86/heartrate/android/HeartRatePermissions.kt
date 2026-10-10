package io.github.albertzhang86.heartrate.android

import android.Manifest
import android.os.Build

/** The consuming app owns runtime permission requests and their user-facing explanations. */
object HeartRatePermissions {
    fun requiredForConnection(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()

    fun requiredForScan(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
}
