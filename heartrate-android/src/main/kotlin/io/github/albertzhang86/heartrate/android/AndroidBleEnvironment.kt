package io.github.albertzhang86.heartrate.android

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings

sealed interface BleReadiness {
    data object Ready : BleReadiness
    data object Unsupported : BleReadiness
    data object BluetoothDisabled : BleReadiness
    data object LocationServicesDisabled : BleReadiness
    data class PermissionsRequired(val permissions: List<String>) : BleReadiness
}

/** Read-only preflight. Does not scan, connect, prompt, or change system settings. */
class AndroidBleEnvironment(context: Context) {
    private val context = context.applicationContext

    @JvmOverloads
    fun readiness(forScan: Boolean = true): BleReadiness {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return BleReadiness.Unsupported
        }
        val permissions = if (forScan) HeartRatePermissions.requiredForScan() else HeartRatePermissions.requiredForConnection()
        val missing = permissions.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) return BleReadiness.PermissionsRequired(missing)
        // Keep the explicit check for lint and for permission revocation races.
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return BleReadiness.PermissionsRequired(listOf(Manifest.permission.BLUETOOTH_CONNECT))
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return BleReadiness.Unsupported
        try {
            if (!adapter.isEnabled) return BleReadiness.BluetoothDisabled
        } catch (_: SecurityException) {
            return BleReadiness.PermissionsRequired(permissions)
        }
        if (forScan && Build.VERSION.SDK_INT <= 30 && !locationEnabled()) return BleReadiness.LocationServicesDisabled
        return BleReadiness.Ready
    }

    @Suppress("DEPRECATION")
    private fun locationEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= 28) context.getSystemService(LocationManager::class.java).isLocationEnabled
        else Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE,
            Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
}
