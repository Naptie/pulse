package me.naptie.pulse

data class SppReading(val bpm: Int?, val spo2: Int?)

/**
 * JDY-31 SPP line protocol: each frame is a CRLF-terminated ASCII line like
 * "HR=98 SPO2=91%". A "--" placeholder means the sensor has no valid reading.
 */
object SppVitalsParser {
    private val re = Regex("^HR=([0-9]{2,3}|--)\\s+SPO2=([0-9]{1,3}|--)%?$", RegexOption.IGNORE_CASE)

    fun parseLine(line: String): SppReading? {
        val m = re.matchEntire(line.trim()) ?: return null
        val bpm = m.groupValues[1].toIntOrNull()
        val spo2 = m.groupValues[2].toIntOrNull()
        if (bpm == null && spo2 == null) return null
        return SppReading(
            bpm = bpm?.takeIf { it in 20..250 },
            spo2 = spo2?.takeIf { it in 0..100 },
        )
    }
}

/**
 * Incremental byte stream decoder: converts raw RX bytes into complete lines
 * split on LF, tolerating CRLF and lines broken across chunk boundaries.
 */
class SppLineDecoder {
    private val buf = StringBuilder()

    fun push(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        for (b in bytes) {
            val c = b.toInt().toChar()
            when (c) {
                '\n' -> {
                    var line = buf.toString()
                    if (line.endsWith('\r')) line = line.dropLast(1)
                    if (line.isNotBlank()) out.add(line)
                    buf.clear()
                }
                '\r' -> Unit
                else -> buf.append(c)
            }
        }
        return out
    }

    fun flush(): String? =
        if (buf.isNotEmpty()) {
            val line = buf.toString()
            buf.clear()
            line.takeIf { it.isNotBlank() }
        } else null
}
