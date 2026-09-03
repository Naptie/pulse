package me.naptie.pulse

data class DeviceInfo(
    val id: String,
    val name: String,
    val rssi: Int,
    val hr: Boolean,
    val spo2: Boolean = false,
)

data class HrPoint(val t: Long, val bpm: Int)

data class Spo2Point(val t: Long, val spo2: Int)

data class VitalPoint(val t: Long, val value: Int)

interface PulseListener {
    fun onDevicesChanged(devices: List<DeviceInfo>)
    fun onHeartRate(bpm: Int)
    fun onBloodOxygen(spo2: Int)
    fun onStateChanged(state: String, detail: String)
    fun onHistory(history: List<HrPoint>)
    fun onSpo2History(history: List<Spo2Point>)
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

object PlxParser {
    private val PLX_SPOT_CHECK = "2A5F"
    private val PLX_CONTINUOUS = "2A5E"

    /** True when the payload belongs to the Pulse Oximeter service (0x1822). */
    fun owns(uuid16: String): Boolean = uuid16.uppercase() == PLX_SPOT_CHECK || uuid16.uppercase() == PLX_CONTINUOUS

    /**
     * Parses a Pulse Oximeter payload (2A5E continuous or 2A5F spot check).
     * Both use the same leading fields: Flags (1 byte), optional SpO2 (IEEE
     * 11073 16-bit float), optional Pulse Rate (IEEE float). Returns percentage
     * SpO2 (0-100) or null when the field is absent/invalid.
     */
    fun spo2(bytes: ByteArray): Int? {
        if (bytes.size < 4) return null
        val flags = bytes[0].toInt() and 0xFF
        if ((flags and 0x01) == 0) return null
        val raw = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        var mantissa = raw shr 4
        if (mantissa and 0x800 != 0) mantissa -= 0x1000
        var exponent = raw and 0x0F
        if (exponent and 0x08 != 0) exponent -= 0x10
        var value = mantissa.toLong()
        repeat(exponent) { value *= 10 }
        repeat(-exponent) { value /= 10 }
        return value.toInt().takeIf { it in 0..100 }
    }
}

/**
 * Rolling window of per-second vital samples, capped at [capacity] (default
 * 300 = 5 minutes). Duplicate samples within the same second are coalesced.
 */
class VitalHistory(private val capacity: Int = 300) {
    private val points = ArrayDeque<VitalPoint>()

    fun push(value: Int, timeMs: Long) {
        val sec = timeMs / 1000
        if (points.isNotEmpty() && sec == points.last().t / 1000) {
            points.removeLast()
            points.addLast(VitalPoint(sec * 1000, value))
        } else {
            points.addLast(VitalPoint(sec * 1000, value))
        }
        while (points.size > capacity) points.removeFirst()
        if (points.isNotEmpty()) {
            val cutoff = sec * 1000 - 300_000
            while (points.isNotEmpty() && points.first().t < cutoff) points.removeFirst()
        }
    }

    fun snapshot(): List<VitalPoint> = points.toList()

    fun clear() {
        points.clear()
    }
}
