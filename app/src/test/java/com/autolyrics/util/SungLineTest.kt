package com.autolyrics.util

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SungLineTest {
    private fun line(ms: Long, text: String, vararg words: Pair<Long, Long?>) = LyricLine(
        timeMs = ms,
        text = text,
        words = words.map { (t, e) -> LyricWord(t, "w", e) }
    )

    // Karaoke payload with REAL word ends: verse line 60.0-63.0, a 4s
    // instrumental, next line 67.0. Outro symbol line trimmed by the caller.
    private val song = listOf(
        line(10_000L, "first line", 10_000L to 11_000L, 11_500L to 12_500L),
        line(60_000L, "verse line", 60_000L to 61_000L, 62_000L to 63_000L),
        line(67_000L, "after gap", 67_000L to 68_000L),
        line(70_000L, "last line", 70_000L to 71_500L),
        line(75_000L, "\u266A")
    )
    private val count = song.size - 1

    @Test
    fun introIsSymbolIndex() {
        assertEquals(-1, SungLine.index(song, 5_000, count))
    }

    @Test
    fun sungLineNeverSwapsOutMidSong() {
        assertEquals(1, SungLine.index(song, 65_000, count))
        assertEquals(3, SungLine.index(song, 120_000, count))
    }

    @Test
    fun provenGapInserts() {
        assertTrue(SungLine.instrumentalGap(song, 64_000, count))
        assertTrue(SungLine.instrumentalGap(song, 66_900, count))
    }

    @Test
    fun graceAndShortBreathDoNotInsert() {
        assertFalse(SungLine.instrumentalGap(song, 63_200, count))
        val tight = listOf(
            line(60_000L, "a", 60_000L to 62_000L),
            line(64_000L, "b", 64_000L to 65_000L)
        )
        assertFalse(SungLine.instrumentalGap(tight, 63_000, 2))
    }

    @Test
    fun wordlessPayloadNeverInserts() {
        val synced = listOf(
            line(164_990L, "\u5207\u306A\u3055\u8A08\u308C\u306A\u3044\u307B\u3069"),
            line(191_400L, "\u6D88\u3048\u306A\u3044\u611B")
        )
        assertFalse(SungLine.instrumentalGap(synced, 176_511, 2))
    }

    @Test
    fun repairedEndToNextStartNeverInserts() {
        // Cache repair extends the held final token to the next line start:
        // no trustworthy end remains, so no insertion is guessed.
        val repaired = listOf(
            line(60_000L, "held", 60_000L to 67_000L),
            line(67_000L, "next", 67_000L to 68_000L)
        )
        assertFalse(SungLine.instrumentalGap(repaired, 65_000, 2))
    }

    @Test
    fun outroNeverInserts() {
        assertFalse(SungLine.instrumentalGap(song, 120_000, count))
    }

    @Test
    fun `countdown ticks down to next line`() {
        val lines = listOf(
            LyricLine(2_000, "one"),
            LyricLine(10_000, "two", listOf(LyricWord(10_000, "t", 11_000))),
            LyricLine(20_000, "three"),
        )
        // Intro: far out -> capped at 3; 1.5 s out -> 2; 0.2 s -> 1.
        assertEquals(2, SungLine.countdownSeconds(lines, 0, 3))
        assertEquals(1, SungLine.countdownSeconds(lines, 1_800, 3))
        assertEquals(2, SungLine.countdownSeconds(lines, 8_500, 3))
        // Mid-song gap counts to the line after the sung one.
        assertEquals(3, SungLine.countdownSeconds(lines, 12_000, 3))
        assertEquals(2, SungLine.countdownSeconds(lines, 18_001, 3))
    }

    @Test
    fun `pastLastLineEnd uses word ends or five second fallback`() {
        val withEnds = listOf(
            LyricLine(1_000, "a"),
            LyricLine(10_000, "b", listOf(LyricWord(10_000, "b", 13_000))),
            LyricLine(30_000, "outro symbol"),
        )
        assertEquals(false, SungLine.pastLastLineEnd(withEnds, 2, 12_000))
        assertEquals(true, SungLine.pastLastLineEnd(withEnds, 2, 13_500))
        val noEnds = listOf(LyricLine(1_000, "a"), LyricLine(10_000, "b"))
        assertEquals(false, SungLine.pastLastLineEnd(noEnds, 2, 14_000))
        assertEquals(true, SungLine.pastLastLineEnd(noEnds, 2, 15_500))
    }
}
