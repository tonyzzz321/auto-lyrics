package com.autolyrics.lyrics

import com.autolyrics.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

class KaraokeTimingTest {

    @Test
    fun outOfOrderTokensStillAdvance() {
        val words = listOf(
            LyricWord(timeMs = 5_000L, text = "早"),
            LyricWord(timeMs = 1_000L, text = "春"),
            LyricWord(timeMs = 2_000L, text = "の"),
            LyricWord(timeMs = 3_000L, text = "光")
        )
        assertEquals(-1, KaraokeTiming.activeWordIndex(words, 999L))
        assertEquals(1, KaraokeTiming.activeWordIndex(words, 1_000L))
        assertEquals(2, KaraokeTiming.activeWordIndex(words, 2_500L))
        assertEquals(3, KaraokeTiming.activeWordIndex(words, 3_000L))
        assertEquals(0, KaraokeTiming.activeWordIndex(words, 5_000L))
    }

    @Test
    fun explicitEndTimesLeaveGapsUnhighlighted() {
        val words = listOf(
            LyricWord(timeMs = 1_000L, text = "A", endTimeMs = 1_400L),
            LyricWord(timeMs = 1_800L, text = "B", endTimeMs = 2_200L)
        )

        assertEquals(-1, KaraokeTiming.activeWordIndex(words, 999L))
        assertEquals(0, KaraokeTiming.activeWordIndex(words, 1_000L))
        assertEquals(0, KaraokeTiming.activeWordIndex(words, 1_399L))
        assertEquals(-1, KaraokeTiming.activeWordIndex(words, 1_400L))
        assertEquals(-1, KaraokeTiming.activeWordIndex(words, 1_799L))
        assertEquals(1, KaraokeTiming.activeWordIndex(words, 1_800L))
        assertEquals(-1, KaraokeTiming.activeWordIndex(words, 2_200L))
    }

    @Test
    fun missingEndTimeFallsBackToLatestStartedWord() {
        val words = listOf(
            LyricWord(timeMs = 1_000L, text = "A"),
            LyricWord(timeMs = 2_000L, text = "B")
        )

        assertEquals(-1, KaraokeTiming.activeWordIndex(words, 999L))
        assertEquals(0, KaraokeTiming.activeWordIndex(words, 1_500L))
        assertEquals(1, KaraokeTiming.activeWordIndex(words, 2_500L))
    }

    @Test
    fun endedLatestWordDoesNotReactivateOlderOpenEndedWord() {
        val words = listOf(
            LyricWord(timeMs = 1_000L, text = "A"),
            LyricWord(timeMs = 2_000L, text = "B", endTimeMs = 2_400L)
        )

        assertEquals(1, KaraokeTiming.activeWordIndex(words, 2_300L))
        assertEquals(-1, KaraokeTiming.activeWordIndex(words, 2_400L))
        assertEquals(-1, KaraokeTiming.activeWordIndex(words, 2_800L))
    }

    @Test
    fun seekingBackwardSelectsEarlierWordAgain() {
        val words = listOf(
            LyricWord(timeMs = 1_000L, text = "A"),
            LyricWord(timeMs = 2_000L, text = "B"),
            LyricWord(timeMs = 3_000L, text = "C")
        )

        assertEquals(2, KaraokeTiming.activeWordIndex(words, 3_500L))
        assertEquals(0, KaraokeTiming.activeWordIndex(words, 1_500L))
    }
}
