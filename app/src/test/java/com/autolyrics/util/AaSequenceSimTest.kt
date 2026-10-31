package com.autolyrics.util

import com.autolyrics.lyrics.LrcParser
import com.autolyrics.lyrics.MusixmatchClient
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dry-runs the AA display sequence against RAW provider payloads through
 * the real parsers (including the cache-repair path) and steps the
 * playback position across the whole song, asserting the invariants every
 * browse builder and the refresh gate rely on:
 *
 *  - every renderable line owns exactly one row (covered tag runs excepted);
 *  - the current row only moves forward and never re-enters the intro;
 *  - a line starting to be sung becomes the current row instantly;
 *  - a countdown row is current only while its instrumental actually
 *    sounds, and its digits never rise;
 *  - the outro keeps the last real lyric lit forever;
 *  - the karaoke board always rides the sung line on its parity row.
 */
class AaSequenceSimTest {

    private class Sim(val tag: String, val lines: List<LyricLine>) {
        val count: Int = run {
            val last = LyricDisplay.lastMeaningfulIndex(lines)
            if (last < 0) lines.size else last + 1
        }
        val rows = SungLine.virtualRows(lines, count)
        val base = maxOf(0, SungLine.firstRealIndex(lines))
        private val hasReal = SungLine.firstRealIndex(lines) >= 0
        private val duration = (lines.maxOfOrNull { it.timeMs } ?: 0L) + 40_000

        /** Real indices of the symbol run a countdown row replaced. */
        private val covered: Set<Int> = buildSet {
            for (r in rows.filter { it.isGap }) {
                var k = r.realIndex + 1
                while (k < count && LyricDisplay.isSymbolText(lines[k].text)) {
                    add(k)
                    k++
                }
            }
        }

        fun at(pos: Long): Int = SungLine.currentVIndex(rows, lines, pos, count)

        fun run() {
            structure()
            sweep()
            onset()
            outro()
            boardParity()
        }

        private fun structure() {
            val realRows = rows.filter { !it.isGap }.map { it.realIndex }
            assertEquals("$tag: duplicate rows", realRows.toSet().size, realRows.size)
            assertEquals(
                "$tag: rows must be every line except covered tags",
                (0 until count).toSet() - covered,
                realRows.toSet()
            )
            if (rows.isNotEmpty()) {
                assertFalse("$tag: last row must not be a countdown", rows.last().isGap)
            }
        }

        private fun sweep() {
            var prev = -2
            var sawLine = false
            var cdBefore = Int.MAX_VALUE
            var pos = 0L
            while (pos <= duration) {
                val idx = at(pos)
                assertTrue("$tag: idx=$idx @$pos", idx >= -1 && idx < rows.size)
                assertTrue("$tag: monotonic @$pos: $prev->$idx", idx >= prev)
                val row = rows.getOrNull(idx)
                if (sawLine) assertTrue("$tag: intro re-entry @$pos", idx >= 0)
                if (row != null && !row.isGap && lines[row.realIndex].timeMs <= pos) {
                    sawLine = true
                }
                if (row?.isGap == true) {
                    // A current countdown means the instrumental is on:
                    // past the anchor's sustained end (grace) or into the
                    // tag run it covers.
                    val end = SungLine.lineEndMs(lines[row.realIndex])!!
                    val coverStart = lines.getOrNull(row.realIndex + 1)
                        ?.takeIf { LyricDisplay.isSymbolText(it.text) }?.timeMs
                    assertTrue(
                        "$tag: countdown current too early @$pos",
                        pos > end + SungLine.GAP_GRACE_MS ||
                            (coverStart != null && pos >= coverStart)
                    )
                    val cd = SungLine.countdownSeconds(lines, pos, count)
                    assertTrue("$tag: cd=$cd @$pos", cd in 1..4)
                    assertTrue("$tag: countdown rising @$pos: $cdBefore->$cd", cd <= cdBefore)
                } else {
                    cdBefore = Int.MAX_VALUE
                }
                prev = idx
                pos += 100
            }
        }

        private fun onset() {
            if (!hasReal) return
            for (i in 0 until count) {
                if (covered.contains(i)) continue
                // Leading tag lines are intro by design, never "sung".
                if (LyricDisplay.isSymbolText(lines[i].text) &&
                    i < SungLine.firstRealIndex(lines)) continue
                // Same-time lines have no unique onset instant.
                if (i + 1 < count && lines[i + 1].timeMs <= lines[i].timeMs) continue
                val v = rows.indexOfFirst { !it.isGap && it.realIndex == i }
                assertEquals(
                    "$tag: line $i (${lines[i].text}) must be current at onset",
                    v,
                    at(lines[i].timeMs + 50)
                )
            }
        }

        private fun outro() {
            if (!hasReal || count == 0) return
            assertEquals("$tag: outro holds the last lyric", rows.lastIndex, at(duration))
            val lastStart = lines[rows.last().realIndex].timeMs
            assertEquals(
                "$tag: outro holds from the last onset on",
                rows.lastIndex,
                at(lastStart + 50)
            )
        }

        private fun boardParity() {
            if (!hasReal) return
            for (i in 0 until count) {
                if (covered.contains(i)) continue
                val v = rows.indexOfFirst { !it.isGap && it.realIndex == i }
                val (top, bottom) = AaKaraokeRows.pairFor(v, rows.size, base)
                if ((v - base) % 2 == 0) {
                    assertEquals("$tag: line $i rides the bottom row", v, bottom)
                } else {
                    assertEquals("$tag: line $i rides the top row", v, top)
                }
            }
        }
    }

    private fun line(timeMs: Long, text: String, vararg words: Pair<Long, Long>) =
        LyricLine(
            timeMs = timeMs,
            text = text,
            words = words.map { (s, e) ->
                LyricWord(s, "", e)
            }
        )

    private fun sim(tag: String, lines: List<LyricLine>): Sim =
        Sim(tag, lines).also { it.run() }

    // ---- provider payloads (raw formats through the real parsers) ----

    private fun lrclibSynced() = LrcParser.parse(
        """
        [00:00.00]♪
        [00:12.00]first line words
        [00:20.00]second line here
        [00:21.00]third line quick
        [00:24.00]♪
        [00:31.00]fourth after music
        [01:00.00][01:30.00]chorus repeats
        [01:34.00]tail line
        [01:38.00]♪
        [01:40.00]♪
        """.trimIndent()
    )

    @Test
    fun lrclibSyncedSweep() {
        val s = sim("LRCLIB synced", lrclibSynced())
        // No word timings anywhere: starts alone must never synthesize a
        // countdown row.
        assertTrue(s.rows.none { it.isGap })
    }

    private fun lrclibEnhanced() = LrcParser.parseKaraoke(
        """
        [00:12.00]<00:12.00>first <00:13.50>words
        [00:20.00]<00:20.00>second <00:22.80>line
        [00:30.00]<00:30.00>after <00:31.00>gap
        [00:33.00]<00:33.00>tail
        [01:00.00]♪
        """.trimIndent()
    )

    @Test
    fun lrclibEnhancedSweep() {
        val s = sim("LRCLIB enhanced", lrclibEnhanced())
        // word 2 ends 22.8+2=24.8, next 30.0 -> countdown owned by line 1.
        assertTrue(s.rows.any { it.isGap && it.realIndex == 1 })
        // The outro tag must not earn a countdown row.
        assertTrue(s.rows.none { it.isGap && it.realIndex >= 3 })
    }

    private fun syncLrcKaraoke() = LrcParser.parseKaraoke(
        """
        [00:01.00]<00:01.00>君<00:01.40>を<00:01.80>忘<00:02.20>れ<00:02.60>な<00:03.00>い
        [00:04.00]<00:04.00>短い<00:04.80>词
        [00:12.00]<00:12.00>戻<00:12.50>っ<00:13.00>て
        [01:00.00]<01:00.00>final
        [01:05.00]♪
        [01:07.00]♪
        """.trimIndent()
    )

    @Test
    fun syncLrcKaraokeSweep() {
        val s = sim("SyncLRC karaoke", syncLrcKaraoke())
        // 04.8+2=6.8 end, next 12.0 -> gap owned by line 1.
        assertTrue(s.rows.any { it.isGap && it.realIndex == 1 })
    }

    // RichSync bodies are arrays of {ts,x,te,l:[{o,e,x}]} with chunk
    // offsets relative to each line start.
    private fun richSyncBody(): String =
        """
        [{"ts":60.0,"x":"long held words","te":60.5,"l":[{"o":0.0,"e":0.4,"c":"long "},{"o":1.5,"e":0.4,"c":"held "},{"o":2.5,"e":0.4,"c":"words"}]},
         {"ts":63.0,"x":"","te":63.5,"l":[]},
         {"ts":68.0,"x":"このひとことば","te":68.2,"l":[{"o":0.0,"e":8.0,"c":"このひとことば"}]},
         {"ts":80.0,"x":"final line","te":82.0,"l":[{"o":0.0,"e":0.5,"c":"final "},{"o":1.0,"e":0.5,"c":"line"}]},
         {"ts":84.0,"x":"","te":84.5,"l":[]}]
        """.trimIndent()

    private fun richSync(): List<LyricLine> {
        val body = richSyncBody().replace("\n", "").replace("\"", "\\\"")
        val json = """
            {"message":{
              "header":{"status_code":200},
              "body":{"richsync":{"richsync_body":"$body"}}}}
        """.trimIndent()
        return MusixmatchClient.parseRichSyncResponse(json)
    }

    @Test
    fun musixmatchRichSync() {
        val lines = richSync()
        assertEquals(5, lines.size)
        val s = sim("Musixmatch richsync", lines)
        // Line 0: last token 62.5, end 64.5; covered tag at 63; next real
        // line 68 -> gap owned by line 0 replacing the blank tag.
        assertTrue(s.rows.any { it.isGap && it.realIndex == 0 })
        // Line 1 is the covered blank tag: exactly one countdown row there.
        assertEquals(1, s.rows.count { it.isGap })
    }

    @Test
    fun richSyncSingleChunkCountsAsToken() {
        // A whole CJK line riding ONE chunk still counts as token-timed
        // (spreadLongGaps re-spreads it): with a 68.0 start the spread
        // reaches the next line, so NO countdown may be guessed there.
        val lines = richSync()
        val coarse = lines[2]
        assertTrue(coarse.words!!.isNotEmpty())
    }

    private fun musixmatchSubtitle() = MusixmatchClient.parseSubtitleBody(
        """
        [00:10.00]subtitle synced text
        [00:20.00]next line text
        [00:55.00]♪
        """.trimIndent()
    )

    @Test
    fun musixmatchSubtitleSynced() {
        val s = sim("Musixmatch subtitle", musixmatchSubtitle())
        assertTrue(s.rows.none { it.isGap }) // wordless payload: no digits
    }

    // ---- hand-built edge scenarios ----

    @Test
    fun singleLinePlusOutroTags() {
        sim(
            "single line",
            listOf(
                line(10_000L, "only line", 10_000L to 11_000L, 12_000L to 13_000L),
                line(20_000L, "\u266a"),
                line(22_000L, "\u266a"),
                line(24_000L, "\u266a")
            )
        )
    }

    @Test
    fun allInstrumental() {
        val s = sim(
            "all instrumental",
            listOf(line(0L, "\u266a"), line(30_000L, "\u266a"), line(60_000L, "[Music]"))
        )
        assertTrue(s.rows.none { it.isGap })
    }

    @Test
    fun backToBackGapsStackRows() {
        val s = sim(
            "back-to-back gaps",
            listOf(
                line(60_000L, "A", 60_000L to 61_000L),
                line(66_000L, "B", 66_000L to 67_000L),
                line(73_000L, "C", 73_000L to 74_000L)
            )
        )
        // A: end 62 -> B at 66 = 4s; B: end 68 -> C at 73 = 5s.
        assertEquals(2, s.rows.count { it.isGap })
        assertEquals(5, s.rows.size) // A D B E C
    }

    @Test
    fun gapBoundaryExactlyMinimumDoesNotInsert() {
        val exact = listOf(
            line(60_000L, "A", 60_000L to 61_000L), // end 62.0
            line(64_500L, "B", 64_500L to 65_000L)  // +2.5 s exactly
        )
        assertFalse(SungLine.gapAfter(exact, 0, 2))
        val over = listOf(
            line(60_000L, "A", 60_000L to 61_000L),
            line(64_501L, "B", 64_501L to 65_000L)
        )
        assertTrue(SungLine.gapAfter(over, 0, 2))
    }

    @Test
    fun emptyWordListNeverAnchorsGap() {
        val lines = listOf(
            LyricLine(60_000L, "empty words", emptyList()),
            line(70_000L, "next", 70_000L to 71_000L)
        )
        assertFalse(SungLine.gapAfter(lines, 0, 2))
    }

    @Test
    fun longInstrumentalCountsDownAndHandsOver() {
        val s = sim(
            "long instrumental",
            listOf(
                line(150_000L, "verse", 150_000L to 151_000L, 152_000L to 153_000L),
                line(180_000L, "returns", 180_000L to 181_000L)
            )
        )
        // Past the sustain+grace (155.5 s) the countdown row is current...
        assertEquals(1, s.at(160_000))
        assertEquals(4, SungLine.countdownSeconds(s.lines, 160_000, s.count))
        // ...until the very last second, then hands over exactly on onset.
        assertEquals(1, SungLine.countdownSeconds(s.lines, 179_000, s.count))
        assertEquals(2, s.at(180_050))
    }

    @Test
    fun leadingTagsKeepIntroUntilFirstRealLine() {
        val s = sim(
            "leading tags",
            listOf(
                line(0L, "\u266a"),
                line(8_000L, "♪"),
                line(20_000L, "first real", 20_000L to 21_000L),
                line(25_000L, "second", 25_000L to 26_000L)
            )
        )
        assertEquals(-1, s.at(5_000))
        assertEquals(-1, s.at(19_000))
        // Board anchors on the first REAL line (V idx 2, even offset 0 =
        // bottom row), exactly where the intro preview places it.
        val (_, bottom) = AaKaraokeRows.pairFor(s.at(20_050), s.rows.size, s.base)
        assertEquals(s.at(20_050), bottom)
    }

    @Test
    fun zeroTimeAndSameTimeLines() {
        sim(
            "zero/same time",
            listOf(
                line(0L, "at zero", 0L to 1_000L),
                line(0L, "also zero", 0L to 1_000L),
                line(20_000L, "next", 20_000L to 21_000L)
            )
        )
    }

    @Test
    fun repeatedChorusTagShiftsWordsWithoutGap() {
        val lines = LrcParser.parseKaraoke(
            """
            [00:30.00][01:00.00]<00:30.00>cho<00:31.50>rus
            [01:34.00]<01:34.00>tail
            """.trimIndent()
        )
        val s = sim("repeated chorus", lines)
        // The repeated instance must carry shifted word starts, otherwise
        // its end anchors a phantom 30 s countdown before the repeat.
        val repeat = lines.maxByOrNull { it.timeMs }!!
        assertTrue(repeat.words!!.all { it.timeMs >= repeat.timeMs })
        // The gap anchored on SHIFTED starts belongs to the silence before
        // the repeat; anchoring on unshifted words would double-book it.
        assertTrue(s.rows.any { it.isGap && it.realIndex == 0 })
        assertFalse(SungLine.gapAfter(lines, 1, 2))
    }

    @Test
    fun legacyCacheLinesSelfHeal() {
        // Entries written before the auto-fetch fix store the word tags
        // verbatim in the display text; the cache load repairs them.
        val legacy = LyricLine(12_000L, "<00:12.00>first <00:13.50>words")
        val healed = LrcParser.repairWordTags(legacy)
        assertEquals("first words", healed.text)
        assertEquals(listOf(12_000L, 13_500L), healed.words.map { it.timeMs })
        // Untouched when there is nothing to repair.
        val plain = legacy.copy(text = "plain")
        assertEquals(plain, LrcParser.repairWordTags(plain))
        // A line may legitimately contain a bare "<" without tag timestamps.
        val math = LyricLine(0L, "a <b <c")
        assertEquals(math, LrcParser.repairWordTags(math))
    }

    @Test
    fun emptyPayloadSurvives() {
        val s = sim("empty", emptyList())
        assertEquals(-1, s.at(1_000))
    }

    @Test
    fun mixedSymbolSpellingsAreCoveredByOneRow() {
        val s = sim(
            "symbol spellings",
            listOf(
                line(60_000L, "verse", 60_000L to 61_000L),
                line(64_000L, "🎵"),
                line(66_000L, "[Instrumental]"),
                line(68_000L, "♫ ♪"),
                line(72_000L, "back", 72_000L to 73_000L)
            )
        )
        // 61+2=63 end, next REAL 72 -> one countdown row covers the run.
        assertEquals(1, s.rows.count { it.isGap })
        assertEquals(listOf(0, 4), s.rows.filter { !it.isGap }.map { it.realIndex })
    }
}
