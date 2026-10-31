package com.autolyrics.ui

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneKaraokeSweepTest {

    @Test
    fun japaneseCharacterTokensSweepCharacterByCharacter() {
        val text = "君を忘れない"
        val line = LyricLine(
            timeMs = 1_000L,
            text = text,
            words = text.mapIndexed { index, char ->
                LyricWord(1_000L + index * 150L, char.toString())
            }
        )

        val segment = PhoneKaraokeSweep.segmentAtPosition(line, 1_750L)
        assertNotNull(segment)
        assertEquals(5, segment!!.start)
        assertEquals(text.length, segment.end)
        assertEquals(1_750L, segment.startTimeMs)
    }

    @Test
    fun mismatchedProviderTokensStillProduceSweepSegments() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "Hello world",
            words = listOf(
                LyricWord(1_000L, "HELLO", endTimeMs = 1_400L),
                LyricWord(1_500L, "WORLD", endTimeMs = 2_000L)
            )
        )

        val first = PhoneKaraokeSweep.segmentAtPosition(line, 1_200L)
        assertNotNull(first)
        assertEquals(0, first!!.start)
        assertEquals(6, first.end)

        val second = PhoneKaraokeSweep.segmentAtPosition(line, 1_700L)
        assertNotNull(second)
        assertEquals(6, second!!.start)
        assertEquals(11, second.end)
    }

    @Test
    fun animationStopsAtExplicitEndUntilNextWordStarts() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "hello world",
            words = listOf(
                LyricWord(1_000L, "hello", endTimeMs = 1_300L),
                LyricWord(2_000L, "world", endTimeMs = 2_500L)
            )
        )

        assertTrue(PhoneKaraokeSweep.isAnimating(line, 1_200L))
        assertFalse(PhoneKaraokeSweep.isAnimating(line, 1_300L))
        assertFalse(PhoneKaraokeSweep.isAnimating(line, 1_700L))
        assertTrue(PhoneKaraokeSweep.isAnimating(line, 2_100L))
    }

    @Test
    fun finalWordFallbackStopsAfterItsSweepCompletes() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "final",
            words = listOf(LyricWord(1_000L, "final"))
        )

        assertTrue(PhoneKaraokeSweep.isAnimating(line, 1_649L))
        assertFalse(PhoneKaraokeSweep.isAnimating(line, 1_650L))
        assertFalse(PhoneKaraokeSweep.isAnimating(line, 3_000L))
    }
}
