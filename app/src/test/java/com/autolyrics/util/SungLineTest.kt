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

    // Karaoke payload: verse line's last token starts at 62.0, so its end
    // is 64.0 (start + 2 s sustain); a 3 s instrumental follows, next line
    // at 67.0. Outro symbol line trimmed by the caller.
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
    fun gapRowIsASequenceMemberAndCurrentWhileActive() {
        // V sequence: [first, gapAfterFirst, verse, gapAfterVerse, afterGap, last]
        val rows = SungLine.virtualRows(song, count)
        assertEquals(
            listOf(
                SungLine.VRow(0, false), SungLine.VRow(0, true),
                SungLine.VRow(1, false), SungLine.VRow(1, true),
                SungLine.VRow(2, false), SungLine.VRow(3, false)
            ),
            rows
        )
        // While the verse is sung the gap row previews AFTER it...
        assertEquals(2, SungLine.currentVIndex(rows, song, 61_000, count))
        // ...and becomes current once the instrumental starts.
        assertEquals(3, SungLine.currentVIndex(rows, song, 65_000, count))
        assertEquals(3, SungLine.currentVIndex(rows, song, 66_900, count))
    }

    @Test
    fun graceAndShortBreathNoGapRow() {
        val rows = SungLine.virtualRows(song, count)
        assertEquals(2, SungLine.currentVIndex(rows, song, 63_200, count))
        val tight = listOf(
            line(60_000L, "a", 60_000L to 62_000L),
            line(64_000L, "b", 64_000L to 65_000L)
        )
        assertFalse(SungLine.gapAfter(tight, 0, 2))
    }

    @Test
    fun wordlessPayloadOwnsNoGapRow() {
        val synced = listOf(
            line(164_990L, "\u5207\u306A\u3055\u8A08\u308C\u306A\u3044\u307B\u3069"),
            line(191_400L, "\u6D88\u3048\u306A\u3044\u611B")
        )
        assertFalse(SungLine.gapAfter(synced, 0, 2))
        val rows = SungLine.virtualRows(synced, 2)
        assertEquals(0, SungLine.currentVIndex(rows, synced, 176_511, 2))
    }

    @Test
    fun repairedSpreadStartsOwnNoGapRow() {
        // Cache repair re-spreads a coarse-chunk line character-by-character
        // across the hold, so the FINAL token start lands near the real sung
        // end; start + 2 s then reaches past the next line and nothing is
        // guessed.
        val repaired = listOf(
            line(60_000L, "held", 66_500L to 67_000L),
            line(67_000L, "next", 67_000L to 68_000L)
        )
        assertFalse(SungLine.gapAfter(repaired, 0, 2))
        val rows = SungLine.virtualRows(repaired, 2)
        assertEquals(0, SungLine.currentVIndex(rows, repaired, 65_000, 2))
        assertEquals(1, SungLine.currentVIndex(rows, repaired, 68_000, 2))
    }

    @Test
    fun countdownRowReplacesSymbolTaggedInstrumental() {
        // Providers tag instrumentals with blank/\u266a lines; the countdown
        // row takes over the run and counts to the next REAL lyric.
        val tagged = listOf(
            line(60_000L, "verse", 60_000L to 61_000L, 62_000L to 62_500L),
            line(65_000L, "\u266a"),
            line(66_000L, "\u266a"),
            line(68_000L, "next", 68_000L to 69_000L)
        )
        val rows = SungLine.virtualRows(tagged, 4)
        assertEquals(
            listOf(
                SungLine.VRow(0, false), SungLine.VRow(0, true),
                SungLine.VRow(3, false)
            ),
            rows
        )
        // Past the sustained end (+grace) the countdown row is current and
        // the digits count to the coming words, not the tag line.
        assertEquals(1, SungLine.currentVIndex(rows, tagged, 65_000, 4))
        assertEquals(3, SungLine.countdownSeconds(tagged, 65_000, 4))
        // Before the end the verse stays current, countdown previewing.
        assertEquals(0, SungLine.currentVIndex(rows, tagged, 61_000, 4))
    }

    @Test
    fun outroNeverCountsDown() {
        val rows = SungLine.virtualRows(song, count)
        val v = SungLine.currentVIndex(rows, song, 120_000, count)
        assertEquals(5, v)
        assertFalse(rows[v].isGap)
        assertFalse(SungLine.gapAfter(song, count - 1, count))
    }

    @Test
    fun `countdown ticks down to next line`() {
        val lines = listOf(
            LyricLine(2_000, "one"),
            LyricLine(10_000, "two", listOf(LyricWord(10_000, "t", 11_000))),
            LyricLine(20_000, "three"),
        )
        // Intro: far out -> capped at 4; 1.5 s out -> 2; 0.2 s -> 1.
        assertEquals(2, SungLine.countdownSeconds(lines, 0, 3))
        assertEquals(1, SungLine.countdownSeconds(lines, 1_800, 3))
        assertEquals(2, SungLine.countdownSeconds(lines, 8_500, 3))
        // Mid-song gap counts to the line after the sung one.
        assertEquals(4, SungLine.countdownSeconds(lines, 12_000, 3))
        assertEquals(2, SungLine.countdownSeconds(lines, 18_001, 3))
    }

    @Test
    fun `pastLastLineEnd uses token sustain or five second fallback`() {
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

    @Test
    fun `leading music tag lines stay intro`() {
        // Providers tag intro music with a blank [00:00] line; the song has
        // not started until the FIRST REAL LYRIC.
        val tagged = listOf(
            LyricLine(0, "\u266a"),
            LyricLine(8_470, "first real", listOf(LyricWord(8_470, "f", null))),
            LyricLine(12_350, "second")
        )
        assertEquals(-1, SungLine.index(tagged, 4_000, 3))
        assertEquals(1, SungLine.index(tagged, 9_000, 3))
        // Intro countdown counts to the real lyric, not the tag at 0 s.
        assertEquals(4, SungLine.countdownSeconds(tagged, 4_000, 3))
        assertEquals(2, SungLine.countdownSeconds(tagged, 6_500, 3))
    }
}
