package com.autolyrics

import com.autolyrics.model.LyricLine
import com.autolyrics.util.PronouncedLength
import com.autolyrics.util.SungLine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Syllable-estimate corpus + the line-timed hold it drives. English
 * numbers are the shipped heuristic's exact outputs (documented, not
 * linguistic truth); CJK/Hangul numbers are exact by script.
 */
class PronouncedLengthTest {

    private fun count(text: String, kanji: Map<Char, Int> = emptyMap()) =
        PronouncedLength.count(text, kanji)

    @Test
    fun latinWordsUseVowelGroupsAndSilentRules() {
        assertEquals(1, count("love"))
        assertEquals(1, count("the"))
        assertEquals(2, count("people"))
        assertEquals(2, count("table"))
        assertEquals(1, count("smile"))
        assertEquals(3, count("rhapsody"))
        assertEquals(1, count("walked"))
        assertEquals(1, count("fire"))
        assertEquals(6, count("is this just fantasy"))
    }

    @Test
    fun hanPricesOneSyllablePerCharacterOutsideKanaLines() {
        assertEquals(7, count("月亮代表我的心"))
        // No kana -> not Japanese: the mora table must NOT apply.
        assertEquals(2, count("漢字", mapOf('漢' to 3)))
    }

    @Test
    fun kanjiPriceByMoraTableInKanaLines() {
        val table = mapOf('僕' to 2, '恋' to 2, '心' to 3)
        // 僕(2)が(1)恋(2)し(1)た(1)
        assertEquals(7, count("僕が恋した", table))
        // Unlisted kanji fall back to one mora.
        assertEquals(2, count("味方", mapOf('味' to 1)))
    }

    @Test
    fun kanaCountMoraeWithMergeAndLengthMarks() {
        assertEquals(2, count("きょう")) // small yo merges
        assertEquals(4, count("がっこう")) // sokuon counts
        assertEquals(4, count("ボーカル")) // long mark counts
        assertEquals(3, count("こころ"))
    }

    @Test
    fun hangulCountsOnePerBlock() {
        assertEquals(5, count("사랑합니다"))
    }

    @Test
    fun cyrillicCountsVowelGroups() {
        assertEquals(4, count("люблю тебя"))
    }

    @Test
    fun digitsCountPunctuationDoesNot() {
        assertEquals(3, count("123"))
        assertEquals(0, count("…!? "))
        assertEquals(1, count("Hey…"))
    }

    @Test
    fun lineTimedEndRidesSyllableHoldWithClamp() {
        // "Hey" = 1 syllable -> 800 ms, floored at 2 s.
        val short = LyricLine(timeMs = 10_000L, text = "Hey")
        assertEquals(12_000L, SungLine.lineEndMs(short))
        // 9 syllables (is1 this1 just1 fan-ta-sy3 love1 ta-ble2) -> 7.2 s.
        val line = LyricLine(timeMs = 0L, text = "is this just fantasy love table")
        assertEquals(7_200L, SungLine.lineEndMs(line))
        // A rap block caps at 15 s.
        val rap = LyricLine(
            timeMs = 0L,
            text = List(30) { "rappa" }.joinToString(" ")
        )
        assertEquals(15_000L, SungLine.lineEndMs(rap))
    }

    @Test
    fun tokenTimedLinesIgnoreTheHold() {
        val tokens = listOf(
            com.autolyrics.model.LyricWord(timeMs = 1_000L, text = "hi"),
            com.autolyrics.model.LyricWord(timeMs = 2_500L, text = "there"),
        )
        val line = LyricLine(timeMs = 0L, text = "hi there", words = tokens)
        assertEquals(7_500L, SungLine.lineEndMs(line))
    }
}
