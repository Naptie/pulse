package me.naptie.pulse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SppVitalsParserTest {

    @Test
    fun parsesTypicalFrame() {
        val r = SppVitalsParser.parseLine("HR=98 SPO2=91%")
        assertEquals(98, r?.bpm)
        assertEquals(91, r?.spo2)
    }

    @Test
    fun acceptsNoPercent() {
        val r = SppVitalsParser.parseLine("hr=95 spo2=92")
        assertEquals(95, r?.bpm)
        assertEquals(92, r?.spo2)
    }

    @Test
    fun handlesDashePlaceholders() {
        assertNull(SppVitalsParser.parseLine("HR=-- SPO2=--"))
    }

    @Test
    fun carriesOverValidSideOfPartialFrame() {
        val r1 = SppVitalsParser.parseLine("HR=-- SPO2=91%")
        assertEquals(null, r1?.bpm)
        assertEquals(91, r1?.spo2)
        val r2 = SppVitalsParser.parseLine("HR=98 SPO2=--")
        assertEquals(98, r2?.bpm)
        assertEquals(null, r2?.spo2)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(SppVitalsParser.parseLine("hello world"))
        assertNull(SppVitalsParser.parseLine("HR=abc SPO2=91%"))
        assertNull(SppVitalsParser.parseLine("SPO2=91% HR=98"))
    }

    @Test
    fun rejectsOutOfRangeValue() {
        val r = SppVitalsParser.parseLine("HR=999 SPO2=91%")
        assertEquals(null, r?.bpm)
        assertEquals(91, r?.spo2)
        val r2 = SppVitalsParser.parseLine("HR=90 SPO2=250%")
        assertEquals(90, r2?.bpm)
        assertEquals(null, r2?.spo2)
    }

    @Test
    fun decoderSplitsCrlfLines() {
        val d = SppLineDecoder()
        val lines = d.push(
            "HR=98 SPO2=91%\r\nHR=95 SPO2=92%\r\n".encodeToByteArray()
        )
        assertEquals(2, lines.size)
        assertEquals("HR=98 SPO2=91%", lines[0])
        assertEquals("HR=95 SPO2=92%", lines[1])
    }

    @Test
    fun decoderHandlesChunkSplits() {
        val d = SppLineDecoder()
        val full = "HR=98 SPO2=91%\r\nHR=95 SPO2=92%\r\n"
        val bytes = full.encodeToByteArray()
        val chunk1 = d.push(bytes.copyOfRange(0, 9))
        val chunk2 = d.push(bytes.copyOfRange(9, bytes.size))
        assertEquals(0, chunk1.size)
        assertEquals(2, chunk2.size)
        assertEquals("HR=95 SPO2=92%", chunk2.last())
    }

    @Test
    fun decoderSkipsEmptyAndBareLf() {
        val d = SppLineDecoder()
        val lines = d.push("\r\nHR=98 SPO2=91%\n\n".encodeToByteArray())
        assertEquals(1, lines.size)
        assertEquals("HR=98 SPO2=91%", lines[0])
    }
}
