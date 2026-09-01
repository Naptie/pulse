package me.naptie.pulse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PulseEngine(
    private val listener: PulseListener,
    private val mock: Boolean = false,
) {
    private val histories = LinkedHashMap<String, HeartHistory>(8)
    private var activeHistory = HeartHistory(300) // 5 minutes at 1s
    private val platform: PulsePlatform = if (mock) MockPlatform(this) else createPlatform(this)

    val isMock: Boolean get() = mock

    var lastDeviceId: String? = platform.loadSavedDeviceId()
        private set
    var lastDeviceName: String = platform.loadSavedDeviceName()
        private set

    fun initialize(context: Any? = null) {
        platform.initialize(context)
    }

    fun startScan() {
        platform.startScan()
        listener.onStateChanged("scanning", "Looking for devices…")
    }

    fun stopScan() = platform.stopScan()

    fun connect(deviceId: String) {
        activeHistory = histories.getOrPut(deviceId) { HeartHistory(300) }
        if (histories.size > 8) {
            val oldest = histories.keys.first()
            histories.remove(oldest)
        }
        listener.onStateChanged("connecting", "Connecting…")
        platform.connect(deviceId)
    }

    fun connectLastDevice() {
        val id = lastDeviceId ?: return
        connect(id)
    }

    fun disconnect() {
        platform.disconnect()
        listener.onStateChanged("disconnected", "Stopped")
    }

    internal fun rememberLastDevice(id: String, name: String) {
        lastDeviceId = id
        lastDeviceName = name
        platform.saveSavedDevice(id, name)
    }

    internal fun onBpm(bpm: Int) {
        activeHistory.push(bpm, nowMs())
        listener.onHeartRate(bpm)
        listener.onHistory(activeHistory.snapshot())
    }

    internal fun onDevices(devices: List<DeviceInfo>) = listener.onDevicesChanged(devices)
    internal fun onState(state: String, detail: String) = listener.onStateChanged(state, detail)

    fun history() = activeHistory.snapshot()

    fun savedDeviceId(): String? = lastDeviceId

    fun savedDeviceName(): String = lastDeviceName

    fun release() = platform.release()
}

internal class MockPlatform(private val engine: PulseEngine) : PulsePlatform {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var bpm = 72
    private var connected = false
    private var savedId: String? = null
    private var savedName: String = ""

    private val mockNames = listOf("Vitals Watch (sim)", "Chest Strap (sim)", "Fitness Band (sim)")

    override fun initialize(context: Any?) {
        scope.launch {
            engine.onDevices(mockDevices())
        }
    }

    private fun mockDevices() = mockNames.mapIndexed { i, n ->
        DeviceInfo("mock-$i", n, -55 - i * 6, i == 0)
    }

    override fun startScan() {
        job?.cancel()
        job = scope.launch {
            engine.onDevices(mockDevices())
            while (true) {
                delay(1200)
                engine.onDevices(mockNames.mapIndexed { i, n ->
                    DeviceInfo("mock-$i", n, -55 - i * 6 + (0..5).random(), i == 0)
                })
            }
        }
    }

    override fun stopScan() {
        job?.cancel()
    }

    override fun connect(deviceId: String) {
        connected = true
        val idx = deviceId.removePrefix("mock-").toIntOrNull() ?: 0
        engine.rememberLastDevice(deviceId, mockNames.getOrNull(idx) ?: "Mock device")
        engine.onState("connected", "Connected · ${engine.lastDeviceName}")
        scope.launch {
            while (connected) {
                delay(250)
                val drift = (1..6).random() - 3
                bpm = (bpm + drift).coerceIn(56, 98)
                engine.onBpm(bpm)
            }
        }
    }

    override fun disconnect() {
        connected = false
    }

    override fun saveSavedDevice(id: String, name: String) {
        savedId = id
        savedName = name
    }

    override fun loadSavedDeviceId(): String? = savedId

    override fun loadSavedDeviceName(): String = savedName

    override fun release() {
        job?.cancel()
        connected = false
    }
}
