package io.github.albertzhang86.heartrate.android.internal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import io.github.albertzhang86.heartrate.HeartRateDevice
import io.github.albertzhang86.heartrate.android.AndroidBleEnvironment
import io.github.albertzhang86.heartrate.android.BleReadiness
import io.github.albertzhang86.heartrate.android.HeartRateGatt
import io.github.albertzhang86.heartrate.connector.ConnectorException
import io.github.albertzhang86.heartrate.connector.ConnectorException.Code
import java.util.concurrent.atomic.AtomicBoolean

/** Permission checks precede operations; SecurityException also handles revocation races. */
@SuppressLint("MissingPermission")
internal class AndroidBlePlatform(private val context: Context) : BlePlatform {
    private val environment = AndroidBleEnvironment(context)
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override fun availability(forScan: Boolean): ConnectorException? = when (environment.readiness(forScan)) {
        BleReadiness.Ready -> null
        BleReadiness.Unsupported -> ConnectorException(Code.UNSUPPORTED, "Bluetooth LE is unavailable.")
        BleReadiness.BluetoothDisabled -> ConnectorException(Code.UNAVAILABLE, "Bluetooth is disabled.")
        BleReadiness.LocationServicesDisabled -> ConnectorException(Code.UNAVAILABLE, "Location services are required for scanning on this Android version.")
        is BleReadiness.PermissionsRequired -> ConnectorException(Code.PERMISSION_DENIED, "Bluetooth permissions are required.")
    }

    override fun scan(filterByService: Boolean, observer: ScanEvents): Resource {
        availability(forScan = true)?.let { throw it }
        return bluetoothOperation {
            val scanner = adapter?.bluetoothLeScanner
                ?: throw ConnectorException(Code.UNAVAILABLE, "Bluetooth scanner is unavailable.")
            val closed = AtomicBoolean()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) = report(result)
                override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::report) }
                override fun onScanFailed(errorCode: Int) {
                    if (!closed.get()) observer.failed(ConnectorException(Code.UNAVAILABLE, "Bluetooth scan failed (status $errorCode)."))
                }
                private fun report(result: ScanResult) {
                    if (closed.get() || !result.isConnectable) return
                    try {
                        observer.found(HeartRateDevice(result.device.address, result.scanRecord?.deviceName ?: result.device.name))
                    } catch (_: SecurityException) {
                        observer.failed(ConnectorException(Code.PERMISSION_DENIED, "Bluetooth permission was revoked."))
                    }
                }
            }
            val filters = if (filterByService) listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HeartRateGatt.SERVICE)).build())
                else emptyList()
            try {
                scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            } catch (error: Exception) {
                closed.set(true)
                runCatching { scanner.stopScan(callback) }
                throw error
            }
            Resource {
                if (!closed.getAndSet(true)) runCatching { scanner.stopScan(callback) }
            }
        }
    }

    override fun connect(deviceId: String, observer: GattEvents): GattLink {
        availability(forScan = false)?.let { throw it }
        if (!BluetoothAdapter.checkBluetoothAddress(deviceId)) {
            throw ConnectorException(Code.UNSUPPORTED, "Select a device discovered by this Bluetooth connector.")
        }
        return bluetoothOperation {
            val device = adapter?.getRemoteDevice(deviceId)
                ?: throw ConnectorException(Code.UNAVAILABLE, "Bluetooth adapter is unavailable.")
            AndroidGattLink(context, device, observer)
        }
    }
}

/** One native GATT handle per attempt. The coordinator owns sequencing, deadlines and retry policy. */
@SuppressLint("MissingPermission")
private class AndroidGattLink(context: Context, device: BluetoothDevice, private val observer: GattEvents) : GattLink {
    private val closed = AtomicBoolean()
    private var notificationDescriptor: BluetoothGattDescriptor? = null
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (closed.get()) return
            when {
                status != BluetoothGatt.GATT_SUCCESS -> failure("Bluetooth connection failed", status)
                newState == BluetoothProfile.STATE_CONNECTED -> observer.connected()
                newState == BluetoothProfile.STATE_DISCONNECTED -> failure("Sensor disconnected", status)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (closed.get()) return
            if (status == BluetoothGatt.GATT_SUCCESS) observer.servicesDiscovered()
            else failure("Service discovery failed", status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (closed.get() || descriptor !== notificationDescriptor) return
            if (status == BluetoothGatt.GATT_SUCCESS) observer.subscribed()
            else failure("Heart-rate subscription failed", status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receive(characteristic, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) characteristic.value?.let { receive(characteristic, it) }
        }

        private fun receive(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (!closed.get() && characteristic.uuid == HeartRateGatt.MEASUREMENT &&
                characteristic.service?.uuid == HeartRateGatt.SERVICE
            ) observer.packet(value.copyOf())
        }

        private fun failure(message: String, status: Int) {
            observer.disconnected(ConnectorException(Code.CONNECTION_FAILED, "$message (status $status)."))
        }
    }

    private val gatt: BluetoothGatt = device.connectGatt(
        context, false, callback, BluetoothDevice.TRANSPORT_LE,
        BluetoothDevice.PHY_LE_1M_MASK, Handler(Looper.getMainLooper()),
    ) ?: throw ConnectorException(Code.CONNECTION_FAILED, "Could not create a Bluetooth connection.")

    override fun discoverServices() = bluetoothOperation {
        if (!closed.get() && !gatt.discoverServices()) {
            throw ConnectorException(Code.CONNECTION_FAILED, "Could not start service discovery.")
        }
    }

    @Suppress("DEPRECATION")
    override fun subscribe() = bluetoothOperation {
        if (closed.get()) return@bluetoothOperation
        val characteristic = gatt.getService(HeartRateGatt.SERVICE)?.getCharacteristic(HeartRateGatt.MEASUREMENT)
            ?: throw ConnectorException(Code.UNSUPPORTED, "Sensor does not expose the standard Heart Rate Service.")
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
            throw ConnectorException(Code.UNSUPPORTED, "Sensor does not support heart-rate notifications.")
        }
        val descriptor = characteristic.getDescriptor(HeartRateGatt.CLIENT_CONFIGURATION)
            ?: throw ConnectorException(Code.UNSUPPORTED, "Sensor does not expose the notification descriptor.")
        notificationDescriptor = descriptor
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            throw ConnectorException(Code.CONNECTION_FAILED, "Could not enable local heart-rate notifications.")
        }
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
        if (!started) throw ConnectorException(Code.CONNECTION_FAILED, "Could not subscribe to heart-rate notifications.")
    }

    override fun close() {
        if (closed.getAndSet(true)) return
        // Attempt both even if permission was revoked or disconnect fails.
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        notificationDescriptor = null
    }
}

private inline fun <T> bluetoothOperation(action: () -> T): T = try {
    action()
} catch (_: SecurityException) {
    throw ConnectorException(Code.PERMISSION_DENIED, "Bluetooth permission was revoked.")
} catch (_: IllegalStateException) {
    throw ConnectorException(Code.UNAVAILABLE, "Bluetooth adapter is unavailable.")
}
