package com.autolyrics.util

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Marker-sequence model: sanitized lyric lines interleaved with derived
 * instrumental markers (intro when the first line starts >= 4 s in,
 * interlude when the silence after a DERIVED line end exceeds 5 s), no
 * outro row - the outro is a timing point.
 */
class SungLineTest {
    private fun line(ms: Long, text: String, vararg words: Long) = LyricLine(
        timeMs = ms,
        text = text,
        words = words.map { LyricWord(it, "w", null) }
    )

    private fun plainLine(ms: Long, text: String) = LyricLine(timeMs = ms, text = text)

    // Token-timed song: L0 ends 13.5 (11.5 + 2 sustain), 46.5 s of silence
    // to L1 (marker), L1 ends 64.0, 6 s gap to L2 (marker), L2 ends 72.0,
    // 1 s tight to L3 (no marker), L3 ends 76.5 = outro start. The first
    // line starts at 10 s, so an intro marker owns row 0.
    private val song = listOf(
        line(10_000L, "first line", 10_000L, 11_500L),
        line(60_000L, "verse line", 60_000L, 62_000L),
        line(70_000L, "after gap", 70_000L),
        line(73_000L, "last line", 73_000L, 74_500L)
    )

    private val songRows = listOf(
        SungLine.VRow(-1, true),
        SungLine.VRow(0, false), SungLine.VRow(0, true),
        SungLine.VRow(1, false), SungLine.VRow(1, true),
        SungLine.VRow(2, false),
        SungLine.VRow(3, false)
    )

    @Test
    fun markersAreSequenceMembers() {
        assertEquals(songRows, SungLine.virtualRows(song, song.size))
    }

    @Test
    fun introMarkerOnlyWhenFirstLineStartsAtLeastFourSecondsIn() {
        assertEquals(-1, SungLine.virtualRows(song, song.size).first().realIndex)
        val shortIntro = listOf(line(3_999L, "a", 4_000L), line(9_000L, "b", 9_000L))
        assertTrue(SungLine.virtualRows(shortIntro, 2).none { it.isGap })
    }

    @Test
    fun currentRowIsLastRowStarted() {
        val rows = SungLine.virtualRows(song, song.size)
        assertEquals(0, SungLine.currentVIndex(rows, song, 0L))          // intro
        assertEquals(0, SungLine.currentVIndex(rows, song, 9_999L))
        assertEquals(1, SungLine.currentVIndex(rows, song, 10_000L))     // first line
        assertEquals(1, SungLine.currentVIndex(rows, song, 13_499L))     // still inside sustain
        assertEquals(2, SungLine.currentVIndex(rows, song, 13_500L))     // interlude marker
        assertEquals(2, SungLine.currentVIndex(rows, song, 59_999L))
        assertEquals(3, SungLine.currentVIndex(rows, song, 60_000L))     // line takes over
        assertEquals(4, SungLine.currentVIndex(rows, song, 64_000L))     // second marker
        assertEquals(5, SungLine.currentVIndex(rows, song, 70_000L))
        assertEquals(6, SungLine.currentVIndex(rows, song, 73_000L))
        assertEquals(6, SungLine.currentVIndex(rows, song, 240_000L))    // outro keeps last line
    }

    @Test
    fun noCurrentRowBeforeFirstLineWithoutIntro() {
        val shortIntro = listOf(line(3_999L, "a", 4_000L), line(20_000L, "b", 20_000L))
        val rows = SungLine.virtualRows(shortIntro, 2)
        assertEquals(-1, SungLine.currentVIndex(rows, shortIntro, 0L))
        assertEquals(-1, SungLine.currentVIndex(rows, shortIntro, 3_998L))
        assertEquals(0, SungLine.currentVIndex(rows, shortIntro, 3_999L))
    }

    @Test
    fun tightBreathsEarnNoMarker() {
        // 1 s after the derived end (72.0 -> 73.0): sung handoff, no row.
        val rows = SungLine.virtualRows(song, song.size)
        assertEquals(-1, rows.indexOfLast { it.isGap && it.realIndex == 2 })
    }

    @Test
    fun derivedEndDependsOnTimingKind() {
        // Token-timed end = last token start + 2 s; exactly 5 s of silence
        // is NOT a marker (threshold is strictly greater).
        // (First line 60 s in: an intro marker owns row 0 in every case
        // here, so the song-marker queries filter it out.)
        val token = listOf(line(60_000L, "a", 62_000L), line(69_000L, "b", 69_000L))
        assertTrue(SungLine.virtualRows(token, 2).none { it.isGap && it.realIndex >= 0 })
        val marker = listOf(line(60_000L, "a", 62_000L), line(69_001L, "b", 69_001L))
        assertEquals(1, SungLine.virtualRows(marker, 2).count { it.isGap && it.realIndex >= 0 })
        // Line-timed: end = start + 5 s, so >10 s start-to-start earns a
        // marker - the user's synced rule, same threshold.
        val synced = listOf(plainLine(60_000L, "a"), plainLine(70_000L, "b"))
        assertTrue(SungLine.virtualRows(synced, 2).none { it.isGap && it.realIndex >= 0 })
        val syncedGap = listOf(plainLine(60_000L, "a"), plainLine(71_000L, "b"))
        assertEquals(1, SungLine.virtualRows(syncedGap, 2).count { it.isGap && it.realIndex >= 0 })
    }

    @Test
    fun outroIsTimingPointNotRow() {
        val rows = SungLine.virtualRows(song, song.size)
        assertTrue(!rows.last().isGap)
        assertEquals(76_500L, SungLine.outroStartMs(song, song.size))
        assertEquals(false, SungLine.pastLastLineEnd(song, song.size, 76_500L))
        assertEquals(true, SungLine.pastLastLineEnd(song, song.size, 76_501L))
        // Line-timed outro: last start + 5 s.
        val synced = listOf(plainLine(1_000L, "a"), plainLine(10_000L, "b"))
        assertEquals(15_000L, SungLine.outroStartMs(synced, 2))
    }

    @Test
    fun countdownCountsToNextLineCappedAtFour() {
        assertEquals(4, SungLine.countdownSeconds(song, 0L, song.size))        // intro far out
        assertEquals(4, SungLine.countdownSeconds(song, 66_000L, song.size))   // exactly 4 s out
        assertEquals(3, SungLine.countdownSeconds(song, 67_000L, song.size))
        assertEquals(1, SungLine.countdownSeconds(song, 69_900L, song.size))
        assertEquals(4, SungLine.countdownSeconds(song, 240_000L, song.size))  // outro: no target
    }

    @Test
    fun eachMarkerCountsToItsOwnSuccessor() {
        val rows = SungLine.virtualRows(song, song.size)
        // Intro (row 0) is 1 s from "first line" (10 s); the previewed
        // interlude (row 2) sits 51 s from its successor (60 s). Before the
        // per-row target they both read the FIRST-FUTURE line and ticked in
        // lockstep under the intro countdown.
        assertEquals(1, SungLine.markerCountdownSeconds(rows, 0, song, 9_000L))
        assertEquals(4, SungLine.markerCountdownSeconds(rows, 2, song, 9_000L))
        // The interlude itself ticks only inside its own final seconds.
        assertEquals(4, SungLine.markerCountdownSeconds(rows, 4, song, 66_000L))
        assertEquals(1, SungLine.markerCountdownSeconds(rows, 4, song, 69_000L))
    }

    @Test
    fun previewNeverSkipsAMarkerRow() {
        val rows = SungLine.virtualRows(song, song.size)
        // A marker IS the next row after L0 and L1: the preview lands on
        // the marker itself, never skipping to the lyric behind it.
        assertEquals(SungLine.VRow(0, true), SungLine.previewSuccessor(rows, 1))
        assertEquals(SungLine.VRow(1, true), SungLine.previewSuccessor(rows, 3))
        // Real-row successors unchanged.
        assertEquals(0, SungLine.previewSuccessor(rows, 0)?.realIndex)   // intro -> first line
        assertEquals(3, SungLine.previewSuccessor(rows, 5)?.realIndex)   // L2 -> L3
        assertNull(SungLine.previewSuccessor(rows, 6))                   // last row
    }

    @Test
    fun nextLineAfterMarkerIsTheComingLyric() {
        val rows = SungLine.virtualRows(song, song.size)
        assertEquals(10_000L, SungLine.nextLineMs(rows, song, 0))   // after intro
        assertEquals(60_000L, SungLine.nextLineMs(rows, song, 2))   // after first interlude
        assertNull(SungLine.nextLineMs(rows, song, 6))              // outro
    }

    @Test
    fun plainPayloadDegeneratesToLineList() {
        // No timing information (every stamp 0): no intro, no markers.
        val plain = listOf(plainLine(0L, "one"), plainLine(0L, "two"))
        assertEquals(
            listOf(SungLine.VRow(0, false), SungLine.VRow(1, false)),
            SungLine.virtualRows(plain, 2)
        )
    }

    @Test
    fun sungIndexClampsAndStaysNegativeInIntro() {
        assertEquals(-1, SungLine.index(song, 9_999L, song.size))
        assertEquals(1, SungLine.index(song, 65_000L, song.size))
        assertEquals(3, SungLine.index(song, 240_000L, song.size))
    }
}
