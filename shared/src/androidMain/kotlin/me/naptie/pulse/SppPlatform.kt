package me.naptie.pulse

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

/**
 * Classic Bluetooth SPP transport for the JDY-31 module: pair first, then
 * discover it (classic devices never appear in BLE scans), connect via RFCMM
 * and decode the ASCII "HR=x SPO2=y%" line stream.
 */
class SppPlatform(private val engine: PulseEngine) : PulsePlatform {
    private val main = Handler(Looper.getMainLooper())
    internal val devices = ConcurrentHashMap<String, DeviceInfo>()
    private var appContext: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var socket: BluetoothSocket? = null
    private var reader: Thread? = null
    @Volatile private var connected = false
    @Volatile private var disconnectRequested = false
    private var lastEmitMs = 0L
    internal var reportDevices: ((List<DeviceInfo>) -> Unit)? = null

    override fun initialize(context: Any?) {
        appContext = context as? Context
        registerReceiver()
    }

    private fun adapter(): BluetoothAdapter? {
        val ctx = appContext ?: return null
        return (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    @SuppressLint("MissingPermission")
    private fun registerReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @SuppressLint("MissingPermission")
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        val name = device.name ?: UiStrings.unnamedDevice
                        val id = device.address
                        devices[id] = DeviceInfo(id, name, 0, hr = true, spo2 = true)
                        emit()
                    }
                }
            }
        }
        receiver = r
        appContext?.registerReceiver(r, IntentFilter(BluetoothDevice.ACTION_FOUND))
    }

    @SuppressLint("MissingPermission")
    private fun emit() {
        val now = System.currentTimeMillis()
        if (now - lastEmitMs >= 3000) {
            lastEmitMs = now
            val snapshot = devices.values.toList()
            main.post { (reportDevices ?: { engine.onDevices(it) })(snapshot) }
        }
    }

    @SuppressLint("MissingPermission")
    override fun startScan() {
        val a = adapter() ?: return
        try {
            a.startDiscovery()
        } catch (e: Exception) {
            android.util.Log.e("PulseScan", "startDiscovery threw", e)
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        try {
            adapter()?.cancelDiscovery()
        } catch (_: Exception) {
        }
        lastEmitMs = 0L
        emit()
    }

    @SuppressLint("MissingPermission")
    override fun connect(deviceId: String) {
        val a = adapter() ?: run { engine.onState("failed", UiStrings.bluetoothUnavailable); return }
        try {
            a.cancelDiscovery()
        } catch (_: Exception) {
        }
        val device = a.getRemoteDevice(deviceId)
        reader = Thread {
            disconnectRequested = false
            try {
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                connected = true
                val name = device.name ?: UiStrings.unnamedDevice
                main.post {
                    engine.rememberLastDevice(deviceId, name)
                    engine.onState("connected", "Connected · $name")
                }
                val decoder = SppLineDecoder()
                val input = s.inputStream
                val buf = ByteArray(4096)
                while (connected) {
                    val n = try {
                        input.read(buf)
                    } catch (_: Exception) {
                        -1
                    }
                    if (n <= 0) break
                    val lines = decoder.push(buf.copyOfRange(0, n))
                    for (line in lines) {
                        SppVitalsParser.parseLine(line)?.let { r ->
                            r.bpm?.let { bpm -> main.post { engine.onBpm(bpm) } }
                            r.spo2?.let { spo2 -> main.post { engine.onSpo2(spo2) } }
                        }
                    }
                }
            } catch (e: Exception) {
                main.post { engine.onState("failed", UiStrings.couldntConnect) }
            } finally {
                val wasConnected = connected
                connected = false
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                socket = null
                if (wasConnected && !disconnectRequested) {
                    main.post { engine.onState("disconnected", UiStrings.connectionLost) }
                }
            }
        }.also { it.start() }
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        disconnectRequested = true
        connected = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        reader = null
        engine.onState("disconnected", UiStrings.stopped)
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

    override fun release() {
        disconnect()
        receiver?.let { appContext?.unregisterReceiver(it) }
        receiver = null
    }
}

/**
 * Merges the BLE and SPP transports behind the shared [PulsePlatform]
 * contract: both scan concurrently, the single device list is merged, and
 * connects route to whichever transport owns the device id.
 */
class AndroidHybridPlatform(private val engine: PulseEngine) : PulsePlatform {
    private val ble = AndroidPlatform(engine)
    private val spp = SppPlatform(engine)

    init {
        ble.reportDevices = { merge() }
        spp.reportDevices = { merge() }
    }

    private fun merge() {
        val merged = LinkedHashMap<String, DeviceInfo>()
        merged.putAll(ble.devices)
        for ((k, v) in spp.devices) merged.putIfAbsent(k, v)
        engine.onDevices(merged.values.toList())
    }

    override fun initialize(context: Any?) {
        ble.initialize(context)
        spp.initialize(context)
    }

    override fun startScan() {
        ble.startScan()
        spp.startScan()
    }

    override fun stopScan() {
        ble.stopScan()
        spp.stopScan()
    }

    override fun connect(deviceId: String) {
        if (spp.devices.containsKey(deviceId)) spp.connect(deviceId) else ble.connect(deviceId)
    }

    override fun disconnect() {
        ble.disconnect()
        spp.disconnect()
    }

    override fun saveSavedDevice(id: String, name: String) = ble.saveSavedDevice(id, name)

    override fun loadSavedDeviceId(): String? = ble.loadSavedDeviceId()

    override fun loadSavedDeviceName(): String = ble.loadSavedDeviceName()

    override fun release() {
        ble.release()
        spp.release()
    }
}
