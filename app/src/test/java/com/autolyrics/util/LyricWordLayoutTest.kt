package com.autolyrics.util

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricWordLayoutTest {

    @Test
    fun reconstructsEnglishSpacesFromOriginalLine() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "Hey Jude",
            words = listOf(
                LyricWord(1_000L, "Hey"),
                LyricWord(1_500L, "Jude")
            )
        )

        val layout = LyricWordLayout.layout(line)

        assertEquals(listOf("", " "), layout.prefixes)
        assertEquals("", layout.suffix)
        assertEquals("Hey 【Jude】", LyricWordLayout.karaokeText(line, 1))
    }

    @Test
    fun keepsJapaneseChunksAdjacent() {
        val line = LyricLine(
            timeMs = 2_000L,
            text = "君を忘れない",
            words = listOf(
                LyricWord(2_000L, "君を"),
                LyricWord(2_800L, "忘れない")
            )
        )

        val layout = LyricWordLayout.layout(line)

        assertEquals(listOf("", ""), layout.prefixes)
        assertEquals("君を【忘れない】", LyricWordLayout.karaokeText(line, 1))
    }

    @Test
    fun preservesMixedScriptSeparators() {
        val line = LyricLine(
            timeMs = 3_000L,
            text = "君と世界 I love you",
            words = listOf(
                LyricWord(3_000L, "君と"),
                LyricWord(3_300L, "世界"),
                LyricWord(3_600L, "I"),
                LyricWord(3_900L, "love"),
                LyricWord(4_200L, "you")
            )
        )

        val layout = LyricWordLayout.layout(line)

        assertEquals(listOf("", "", " ", " ", " "), layout.prefixes)
        assertEquals("君と世界 【I 】love you", LyricWordLayout.karaokeText(line, 2))
    }

    @Test
    fun englishFragmentsHighlightWholeDisplayWord() {
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

        assertEquals("【Provider 】timing works", LyricWordLayout.karaokeText(line, 0))
        assertEquals("【Provider 】timing works", LyricWordLayout.karaokeText(line, 1))
        assertEquals("【Provider 】timing works", LyricWordLayout.karaokeText(line, 2))
        assertEquals("Provider 【timing 】works", LyricWordLayout.karaokeText(line, 3))
    }

    @Test
    fun japaneseCharacterTokensHighlightCharacterByCharacter() {
        val text = "君を忘れない"
        val line = LyricLine(
            timeMs = 1_000L,
            text = text,
            words = text.mapIndexed { index, char ->
                LyricWord(1_000L + index * 150L, char.toString())
            }
        )

        assertEquals("【君】を忘れない", LyricWordLayout.karaokeText(line, 0))
        assertEquals("君【を】忘れない", LyricWordLayout.karaokeText(line, 1))
        assertEquals("君を【忘】れない", LyricWordLayout.karaokeText(line, 2))
        assertEquals("君を忘れな【い】", LyricWordLayout.karaokeText(line, 5))
    }

    @Test
    fun japaneseEndingPunctuationLightsWithTheFinalSyllable() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "わぁー！",
            words = listOf(
                LyricWord(1_000L, "わ"),
                LyricWord(2_000L, "ぁ"),
                LyricWord(3_000L, "ー！")
            )
        )

        assertEquals("わぁ【ー！】", LyricWordLayout.karaokeText(line, 2))
    }

    @Test
    fun koreanCharacterTokensHighlightCharacterByCharacter() {
        val text = "사랑해요"
        val line = LyricLine(
            timeMs = 1_000L,
            text = text,
            words = text.mapIndexed { index, char ->
                LyricWord(1_000L + index * 150L, char.toString())
            }
        )

        assertEquals("【사】랑해요", LyricWordLayout.karaokeText(line, 0))
        assertEquals("사【랑】해요", LyricWordLayout.karaokeText(line, 1))
        assertEquals("사랑해【요】", LyricWordLayout.karaokeText(line, 3))
    }

    @Test
    fun providerTimingTokensRemainUnmodified() {
        val words = listOf(
            LyricWord(1_000L, "Pro", 1_100L),
            LyricWord(1_100L, "vi", 1_200L),
            LyricWord(1_200L, "der", 1_400L)
        )
        val line = LyricLine(1_000L, "Provider", words)

        LyricWordLayout.karaokeText(line, 1)

        assertEquals(words, line.words)
    }

    @Test
    fun credibleFragmentMismatchRendersSourceTextWithReadableRanges() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "Hello world",
            words = listOf(
                LyricWord(1_000L, "HE"),
                LyricWord(1_150L, "LLO"),
                LyricWord(1_500L, "WORLD")
            )
        )

        val rendered = LyricWordLayout.renderedLine(line)

        assertEquals("Hello world", rendered.text)
        assertArrayEquals(intArrayOf(0, 0, 6), rendered.tokenStart)
        assertArrayEquals(intArrayOf(6, 6, 11), rendered.tokenEnd)
    }

    @Test
    fun incidentalSingleMatchKeepsLegacyProviderFallback() {
        val line = LyricLine(
            timeMs = 0L,
            text = "different line text",
            words = listOf(
                LyricWord(0L, "line"),
                LyricWord(1L, "one")
            )
        )

        val rendered = LyricWordLayout.renderedLine(line)

        assertEquals("line one", rendered.text)
        assertEquals("line 【one】", LyricWordLayout.karaokeText(line, 1))
    }

    @Test
    fun incidentalFineGrainedAnchorsNeedSubstantiveCoverage() {
        val line = LyricLine(
            timeMs = 0L,
            text = "a long day",
            words = listOf(
                LyricWord(0L, "A"),
                LyricWord(100L, "X"),
                LyricWord(200L, "G"),
                LyricWord(300L, "Z")
            )
        )

        val rendered = LyricWordLayout.renderedLine(line)

        assertEquals("A X G Z", rendered.text)
        assertEquals("A X 【G】 Z", LyricWordLayout.karaokeText(line, 2))
    }

    @Test
    fun fallsBackToLanguageAppropriateSeparatorWhenAlignmentFails() {
        val english = LyricLine(
            timeMs = 0L,
            text = "different line text",
            words = listOf(LyricWord(0L, "one"), LyricWord(1L, "two"))
        )
        val japanese = LyricLine(
            timeMs = 0L,
            text = "別テキスト",
            words = listOf(LyricWord(0L, "一"), LyricWord(1L, "二"))
        )

        assertEquals("one 【two】", LyricWordLayout.karaokeText(english, 1))
        assertEquals("一【二】", LyricWordLayout.karaokeText(japanese, 1))
    }
}
