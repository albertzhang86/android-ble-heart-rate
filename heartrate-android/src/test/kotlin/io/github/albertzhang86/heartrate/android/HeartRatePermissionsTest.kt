package io.github.albertzhang86.heartrate.android

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRatePermissionsTest {
    @Test fun `modern Android requests nearby devices without location`() {
        assertEquals(listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
            HeartRatePermissions.requiredForScan(31))
    }

    @Test fun `legacy Android scanning needs foreground location`() {
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), HeartRatePermissions.requiredForScan(30))
    }
}
