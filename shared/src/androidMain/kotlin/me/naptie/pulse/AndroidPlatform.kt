package me.naptie.pulse

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

private val HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
private val HR_MEASURE = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private val HR_UUID16 = ParcelUuid(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"))

private var appContext: Context? = null

actual fun createPlatform(engine: PulseEngine): PulsePlatform = AndroidPlatform(engine)

actual fun nowMs(): Long = System.currentTimeMillis()

class AndroidPlatform(private val engine: PulseEngine) : PulsePlatform {
    private val main = Handler(Looper.getMainLooper())
    private val gatts = LinkedHashMap<String, BluetoothGatt>()
    private val devices = LinkedHashMap<String, DeviceInfo>()
    private var scanning = false
    private var currentGatt: BluetoothGatt? = null
    private var lastEmitMs = 0L

    override fun initialize(context: Any?) {
        appContext = context as? Context
    }

    private fun adapter(): BluetoothAdapter? {
        val ctx = appContext ?: return null
        return (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    @SuppressLint("MissingPermission")
    override fun startScan() {
        val a = adapter() ?: run { android.util.Log.w("PulseScan", "startScan: adapter null"); return }
        val scanner = a.bluetoothLeScanner ?: run { android.util.Log.w("PulseScan", "startScan: scanner null"); return }
        scanning = true
        lastEmitMs = 0L
        android.util.Log.i("PulseScan", "startScan called, bt state=${a.state}")
        try {
            scanner.startScan(
                null,
                android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                scanCallback
            )
        } catch (e: Exception) {
            android.util.Log.e("PulseScan", "startScan threw", e)
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: ""
            val id = device.address
            if (id.isEmpty()) return
            val hr = result.scanRecord?.serviceUuids?.contains(HR_UUID16) == true ||
                    result.scanRecord?.serviceData?.keys?.any { it.uuid.toString().lowercase().startsWith("0000180d") } == true
            android.util.Log.i("PulseScan", "onScanResult: ${id} rssi=${result.rssi} name=$name hr=$hr")
            devices[id] = DeviceInfo(id, name.ifEmpty { UiStrings.unnamedDevice }, result.rssi, hr)
            val now = System.currentTimeMillis()
            if (now - lastEmitMs >= 3000) {
                lastEmitMs = now
                main.post { engine.onDevices(devices.values.toList()) }
                android.util.Log.i("PulseScan", "ui emit: ${devices.size} devices")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            android.util.Log.e("PulseScan", "onScanFailed error=$errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(deviceId: String) {
        val a = adapter() ?: run { engine.onState("failed", UiStrings.bluetoothUnavailable); return }
        stopScan()
        val device = a.getRemoteDevice(deviceId)
        device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                currentGatt = gatt
                gatt.discoverServices()
            } else {
                engine.onState("disconnected", UiStrings.connectionLost)
                gatt.close()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val svc = gatt.getService(HR_SERVICE) ?: run {
                engine.onState("failed", UiStrings.monitorServiceNotFound)
                gatt.close()
                return
            }
            val ch = svc.getCharacteristic(HR_MEASURE) ?: return
            gatt.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD)
            if (cccd != null) {
                cccd.value = byteArrayOf(0x01, 0x00)
                gatt.writeDescriptor(cccd)
            } else {
                engine.onState("connected", UiStrings.connected)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            val id = gatt.device.address
            val name = devices[id]?.name ?: "device"
            engine.rememberLastDevice(id, name)
            engine.onState("connected", UiStrings.connectedWith(name))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            characteristic.value?.let {
                val bpm = HrParser.bpm(it)
                if (bpm > 0) engine.onBpm(bpm)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
        }
    }

    override fun disconnect() {
        stopScan()
        val g = currentGatt ?: return
        g.disconnect()
        g.close()
        currentGatt = null
        engine.onState("disconnected", UiStrings.stopped)
    }

    override fun release() {
        disconnect()
        gatts.clear()
        devices.clear()
    }

    override fun saveSavedDevice(id: String, name: String) {
        appContext?.getSharedPreferences("pulse", Context.MODE_PRIVATE)?.edit()?.apply {
            putString("lastDeviceId", id)
            putString("lastDeviceName", name)
        }?.apply()
    }

    override fun loadSavedDeviceId(): String? =
        appContext?.getSharedPreferences("pulse", Context.MODE_PRIVATE)?.getString("lastDeviceId", null)

    override fun loadSavedDeviceName(): String =
        appContext?.getSharedPreferences("pulse", Context.MODE_PRIVATE)?.getString("lastDeviceName", "") ?: ""
}
