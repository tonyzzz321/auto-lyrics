package com.autolyrics.util

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricWordDisplayRangeTest {

    @Test
    fun englishFragmentsShareWholeDisplayWordRange() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "Provider timing works",
            words = listOf(
                LyricWord(1_000L, "Pro"),
                LyricWord(1_100L, "vi"),
                LyricWord(1_200L, "der"),
                LyricWord(1_500L, "timing"),
                LyricWord(2_000L, "works")
            )
        )

        val expected = LyricWordLayout.DisplayRange(0, 9)
        assertEquals(expected, LyricWordLayout.displayRangeForToken(line, 0))
        assertEquals(expected, LyricWordLayout.displayRangeForToken(line, 1))
        assertEquals(expected, LyricWordLayout.displayRangeForToken(line, 2))
    }

    @Test
    fun japaneseCharacterTokensMapToTheirOwnCharacters() {
        val text = "君を忘れない"
        val line = LyricLine(
            timeMs = 1_000L,
            text = text,
            words = text.mapIndexed { index, char ->
                LyricWord(1_000L + index * 150L, char.toString())
            }
        )

        assertEquals(
            LyricWordLayout.DisplayRange(1, 2),
            LyricWordLayout.displayRangeForToken(line, 1)
        )
        assertEquals(
            LyricWordLayout.DisplayRange(5, text.length),
            LyricWordLayout.displayRangeForToken(line, 5)
        )
    }

    @Test
    fun mismatchedTokenCaseAlignsWithoutDroppingKaraoke() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "Hello world",
            words = listOf(
                LyricWord(1_000L, "HELLO"),
                LyricWord(1_500L, "WORLD")
            )
        )

        assertEquals(
            LyricWordLayout.DisplayRange(0, 6),
            LyricWordLayout.displayRangeForToken(line, 0)
        )
        assertEquals(
            LyricWordLayout.DisplayRange(6, 11),
            LyricWordLayout.displayRangeForToken(line, 1)
        )
    }

    @Test
    fun repeatedTokensPreferEarliestEquivalentMatch() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "Hello HELLO",
            words = listOf(
                LyricWord(1_000L, "HELLO"),
                LyricWord(1_500L, "HELLO")
            )
        )

        assertEquals(
            LyricWordLayout.DisplayRange(0, 6),
            LyricWordLayout.displayRangeForToken(line, 0)
        )
        assertEquals(
            LyricWordLayout.DisplayRange(6, 11),
            LyricWordLayout.displayRangeForToken(line, 1)
        )
    }

    @Test
    fun unevenCaseMismatchesUseActualLexicalPositions() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "extraordinary a b c d e",
            words = listOf(
                LyricWord(1_000L, "EXTRAORDINARY"),
                LyricWord(1_500L, "A"),
                LyricWord(1_600L, "B"),
                LyricWord(1_700L, "C"),
                LyricWord(1_800L, "D"),
                LyricWord(1_900L, "E")
            )
        )

        assertEquals(
            LyricWordLayout.DisplayRange(14, 16),
            LyricWordLayout.displayRangeForToken(line, 1)
        )
        assertEquals(
            LyricWordLayout.DisplayRange(16, 18),
            LyricWordLayout.displayRangeForToken(line, 2)
        )
    }

    @Test
    fun fragmentedMismatchedTokensStayOnTheirReadableWord() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "Hello world",
            words = listOf(
                LyricWord(1_000L, "HE"),
                LyricWord(1_150L, "LLO"),
                LyricWord(1_500L, "WORLD")
            )
        )

        val hello = LyricWordLayout.DisplayRange(0, 6)
        assertEquals(hello, LyricWordLayout.displayRangeForToken(line, 0))
        assertEquals(hello, LyricWordLayout.displayRangeForToken(line, 1))
        assertEquals(
            LyricWordLayout.DisplayRange(6, 11),
            LyricWordLayout.displayRangeForToken(line, 2)
        )
    }

    @Test
    fun onePunctuationMismatchKeepsNeighboringSyllableGrouping() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "Provider timing works",
            words = listOf(
                LyricWord(1_000L, "Pro"),
                LyricWord(1_100L, "vi"),
                LyricWord(1_200L, "der!"),
                LyricWord(1_500L, "timing"),
                LyricWord(2_000L, "works")
            )
        )

        val provider = LyricWordLayout.DisplayRange(0, 9)
        assertEquals(provider, LyricWordLayout.displayRangeForToken(line, 0))
        assertEquals(provider, LyricWordLayout.displayRangeForToken(line, 1))
        assertEquals(provider, LyricWordLayout.displayRangeForToken(line, 2))
        assertEquals(
            LyricWordLayout.DisplayRange(9, 16),
            LyricWordLayout.displayRangeForToken(line, 3)
        )
    }

    @Test
    fun canonicallyEquivalentTokenAlignsToOriginalTextRange() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "café noir",
            words = listOf(
                LyricWord(1_000L, "cafe\u0301"),
                LyricWord(1_500L, "noir")
            )
        )

        assertEquals(
            LyricWordLayout.DisplayRange(0, 5),
            LyricWordLayout.displayRangeForToken(line, 0)
        )
        assertEquals(
            LyricWordLayout.DisplayRange(5, 9),
            LyricWordLayout.displayRangeForToken(line, 1)
        )
    }

    @Test
    fun singleMismatchedTokenFallsBackToWholeVisibleLine() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "Hello world",
            words = listOf(LyricWord(1_000L, "HELLO WORLD!"))
        )

        assertEquals(
            LyricWordLayout.DisplayRange(0, 11),
            LyricWordLayout.displayRangeForToken(line, 0)
        )
    }
}
