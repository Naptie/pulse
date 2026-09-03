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
    private val histories = LinkedHashMap<String, VitalHistory>(8)
    private val spo2Histories = LinkedHashMap<String, VitalHistory>(8)
    private var activeHistory = VitalHistory(300) // 5 minutes at 1s
    private var activeSpo2History = VitalHistory(300)
    private var latestSpo2 = 0
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
        activeHistory = histories.getOrPut(deviceId) { VitalHistory(300) }
        activeSpo2History = spo2Histories.getOrPut(deviceId) { VitalHistory(300) }
        if (histories.size > 8) {
            val oldest = histories.keys.first()
            histories.remove(oldest)
            spo2Histories.remove(oldest)
        }
        latestSpo2 = 0
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
        listener.onHistory(activeHistory.snapshot().map { HrPoint(it.t, it.value) })
    }

    internal fun onSpo2(spo2: Int) {
        activeSpo2History.push(spo2, nowMs())
        latestSpo2 = spo2
        listener.onBloodOxygen(spo2)
        listener.onSpo2History(activeSpo2History.snapshot().map { Spo2Point(it.t, it.value) })
    }

    internal fun onDevices(devices: List<DeviceInfo>) = listener.onDevicesChanged(devices)
    internal fun onState(state: String, detail: String) = listener.onStateChanged(state, detail)

    fun history() = activeHistory.snapshot().map { HrPoint(it.t, it.value) }

    fun spo2History() = activeSpo2History.snapshot().map { Spo2Point(it.t, it.value) }

    fun spo2Value(): Int = latestSpo2

    fun savedDeviceId(): String? = lastDeviceId

    fun savedDeviceName(): String = lastDeviceName

    fun release() = platform.release()
}

internal class MockPlatform(private val engine: PulseEngine) : PulsePlatform {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var bpm = 72
    private var spo2 = 97
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
        DeviceInfo("mock-$i", n, -55 - i * 6, i == 0, i == 0 || i == 2)
    }

    override fun startScan() {
        job?.cancel()
        job = scope.launch {
            engine.onDevices(mockDevices())
            while (true) {
                delay(1200)
                engine.onDevices(mockNames.mapIndexed { i, n ->
                    DeviceInfo("mock-$i", n, -55 - i * 6 + (0..5).random(), i == 0, i == 0 || i == 2)
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
                val drift2 = (0..2).random() - 1
                spo2 = (spo2 + drift2).coerceIn(93, 99)
                engine.onSpo2(spo2)
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
