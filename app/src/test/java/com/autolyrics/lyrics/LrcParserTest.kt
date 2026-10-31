package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun enhancedLrcPreservesFragmentSpacingAndAbsoluteTiming() {
        val lrc = "[00:01.00]<00:01.00>Pro<00:01.10>vi<00:01.20>der <00:01.50>timing"

        val line = LrcParser.parseKaraoke(lrc).single()

        assertEquals(1_000L, line.timeMs)
        assertEquals("Provider timing", line.text)
        assertEquals(listOf("Pro", "vi", "der ", "timing"), line.words.map { it.text })
        assertEquals(listOf(1_000L, 1_100L, 1_200L, 1_500L), line.words.map { it.timeMs })
    }

    @Test
    fun enhancedLrcIgnoresFormattingSpaceBeforeFirstToken() {
        val lrc = "[00:01.00] <00:01.05>Hello <00:01.50>world"

        val line = LrcParser.parseKaraoke(lrc).single()

        assertEquals("Hello world", line.text)
        assertEquals(listOf("Hello ", "world"), line.words.map { it.text })
        assertEquals(1_050L, line.words.first().timeMs)
    }

    @Test
    fun enhancedLrcKeepsJapaneseCharacterTokensWithoutInventingSpaces() {
        val lrc = "[00:03.00]<00:03.00>君<00:03.15>を<00:03.30>忘<00:03.45>れ<00:03.60>な<00:03.75>い"

        val line = LrcParser.parseKaraoke(lrc).single()

        assertEquals("君を忘れない", line.text)
        assertEquals(listOf("君", "を", "忘", "れ", "な", "い"), line.words.map { it.text })
    }

    @Test
    fun enhancedLrcAcceptsColonFractionSeparator() {
        val lrc = "[00:01:25]<00:01:25>Hello <00:01:75>world"

        val line = LrcParser.parseKaraoke(lrc).single()

        assertEquals(1_250L, line.timeMs)
        assertEquals("Hello world", line.text)
        assertEquals(1_750L, line.words[1].timeMs)
    }

    @Test
    fun karaokeParserLeavesLineOnlyLrcWithoutFakeWordTiming() {
        val lines = LrcParser.parseKaraoke("[00:01.00]Line only")

        assertEquals(1, lines.size)
        assertEquals("Line only", lines.single().text)
        assertTrue(lines.single().words.isEmpty())
    }

    @Test
    fun karaokeWordTimestampsWithoutFractionStillProduceTokens() {
        val lrc = "[00:01.00]<00:01.00>first <00:02>second <00:03.50>third"

        val line = LrcParser.parseKaraoke(lrc).single()

        assertEquals(listOf("first ", "second ", "third"), line.words.map { it.text })
        assertEquals(listOf(1_000L, 2_000L, 3_500L), line.words.map { it.timeMs })
    }

    @Test
    fun lineTimestampsMayOmitFractionOrUseComma() {
        val lines = LrcParser.parse("[00:42]line one\n[01:03.5]line two\n[01:10,25]line three")
        assertEquals(listOf(42_000L, 63_500L, 70_250L), lines.map { it.timeMs })
    }

    @Test
    fun cjkSpacingIsRemovedButLatinSpacingSurvives() {
        val line = LrcParser.parse("[00:01.00] 春 の 光 hello world").single()
        assertEquals("春の光 hello world", line.text)
    }

    @Test
    fun cjkSpacingCleanupKeepsWordTokensAlignedWithDisplayText() {
        val lrc = "[00:01.00]<00:01.00>春 の<00:02.00>光"

        val line = LrcParser.parseKaraoke(lrc).single()

        assertEquals("春の光", line.text)
        assertEquals(line.text, line.words.joinToString("") { it.text })
    }

    @Test
    fun cjkBoundarySpacesBetweenSingleCharTokensAreDropped() {
        val lrc = "[00:01.00]<00:01.00>春 <00:01.50>の <00:02.00>光 <00:03.00>hello <00:04.00>world"

        val line = LrcParser.parseKaraoke(lrc).single()

        assertEquals("春の光 hello world", line.text)
        assertEquals(listOf("春", "の", "光 ", "hello ", "world"), line.words.map { it.text })
        assertEquals(line.text, line.words.joinToString("") { it.text })
    }

    @Test
    fun karaokeWordTokensAreSortedChronologically() {
        val line = LrcParser.parseKaraoke("[00:10.00]<00:12.00>b<00:10.50>a").single()

        // Display order follows sung (time) order when tags disagree with text order.
        assertEquals("ab", line.text)
        assertEquals(listOf(10_500L, 12_000L), line.words.map { it.timeMs })
        assertEquals(listOf("a", "b"), line.words.map { it.text })
    }

    @Test
    fun repeatedLineTagsShiftWordTimesToTheirInstance() {
        val lines = LrcParser.parseKaraoke("[00:10.00][00:40.00]<00:10.00>la<00:11.00>la")

        assertEquals(2, lines.size)
        assertEquals(listOf(10_000L, 11_000L), lines[0].words.map { it.timeMs })
        assertEquals(listOf(40_000L, 41_000L), lines[1].words.map { it.timeMs })
    }

    @Test
    fun untaggedPrefixTextRidesTheFirstToken() {
        val line = LrcParser.parseKaraoke("[00:10.00]spoken <00:10.50>sung").single()

        assertEquals("spoken sung", line.text)
        assertEquals(listOf("spoken sung"), line.words.map { it.text })
        assertEquals(10_500L, line.words.single().timeMs)
    }
}
