package me.naptie.pulse

data class DeviceInfo(val id: String, val name: String, val rssi: Int, val hr: Boolean)

data class HrPoint(val t: Long, val bpm: Int)

interface PulseListener {
    fun onDevicesChanged(devices: List<DeviceInfo>)
    fun onHeartRate(bpm: Int)
    fun onStateChanged(state: String, detail: String)
    fun onHistory(history: List<HrPoint>)
}

interface PulsePlatform {
    fun initialize(context: Any?)
    fun startScan()
    fun stopScan()
    fun connect(deviceId: String)
    fun disconnect()
    fun saveSavedDevice(id: String, name: String)
    fun loadSavedDeviceId(): String?
    fun loadSavedDeviceName(): String
    fun release()
}

expect fun createPlatform(engine: PulseEngine): PulsePlatform
expect fun nowMs(): Long

class HrParser {
    companion object {
        /** Parses a Heart Rate Measurement (0x2A37) payload; returns BPM or -1. */
        fun bpm(bytes: ByteArray): Int {
            if (bytes.isEmpty()) return -1
            val flags = bytes[0].toInt() and 0xFF
            return if ((flags and 0x01) != 0) {
                if (bytes.size < 3) return -1
                (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
            } else {
                if (bytes.size < 2) return -1
                bytes[1].toInt() and 0xFF
            }
        }
    }
}

/** Per-second heart rate history, capped at [capacity] samples (default 300 = 5 minutes). */
class HeartHistory(private val capacity: Int = 300) {
    private val points = ArrayDeque<HrPoint>()

    fun push(bpm: Int, timeMs: Long) {
        val sec = timeMs / 1000
        val last = points.lastOrNull()
        if (last != null && sec == last.t / 1000) {
            points.removeLast()
            points.addLast(HrPoint(sec * 1000, bpm))
        } else {
            points.addLast(HrPoint(sec * 1000, bpm))
        }
        while (points.size > capacity) points.removeFirst()
        if (points.isNotEmpty()) {
            val cutoff = sec * 1000 - 300_000
            while (points.isNotEmpty() && points.first().t < cutoff) points.removeFirst()
        }
    }

    fun snapshot(): List<HrPoint> = points.toList()

    fun clear() {
        points.clear()
    }
}
