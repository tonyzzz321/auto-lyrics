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
 * the real parsers (which strip every non-lyric line at ingest) and steps
 * the playback position across the whole song, asserting the invariants
 * every browse builder and the refresh gate rely on:
 *
 *  - every parsed line owns exactly one row, markers never stack, and the
 *    INTRO marker exists iff the first line starts >= 4 s in;
 *  - the current row only moves forward and never re-enters the intro;
 *  - a line starting to be sung becomes the current row instantly;
 *  - a marker row is current only inside its time span, and its digits
 *    never rise while it is current;
 *  - the outro keeps the last real lyric lit forever (no outro row);
 *  - the karaoke board pairs every row (markers included) on its parity
 *    slot with the sequence itself as anchor.
 */
class AaSequenceSimTest {

    private class Sim(val tag: String, val lines: List<LyricLine>) {
        val count: Int = lines.size
        val rows = SungLine.virtualRows(lines, count)
        private val duration = (lines.maxOfOrNull { it.timeMs } ?: 0L) + 40_000
        private val introIdx = if (rows.firstOrNull()?.isGap == true) 0 else -1

        fun at(pos: Long): Int = SungLine.currentVIndex(rows, lines, pos)

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
                "$tag: every parsed line owns exactly one row",
                (0 until count).toList(),
                realRows
            )
            if (rows.isNotEmpty()) {
                assertFalse("$tag: last row must not be a marker", rows.last().isGap)
            }
            assertEquals(
                "$tag: intro marker iff first line starts 4 s in",
                lines.firstOrNull()?.let { it.timeMs >= SungLine.INTRO_MIN_MS } ?: false,
                rows.firstOrNull()?.isGap ?: false
            )
            for (k in rows.indices) {
                if (!rows[k].isGap) continue
                assertFalse("$tag: markers must not stack", rows.getOrNull(k + 1)?.isGap ?: false)
                if (k == introIdx) continue
                // Interlude markers ride directly after the line that owns
                // the silence they span.
                assertTrue("$tag: marker without owner at $k", k > 0 && !rows[k - 1].isGap)
                assertEquals(
                    "$tag: interlude anchored on previous row",
                    rows[k - 1].realIndex,
                    rows[k].realIndex
                )
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
                if (sawLine && introIdx >= 0) assertTrue("$tag: intro re-entry @$pos", idx > introIdx)
                if (row != null && !row.isGap && lines[row.realIndex].timeMs <= pos) {
                    sawLine = true
                }
                if (row?.isGap == true) {
                    // A current marker means its instrumental is on: from
                    // its span start (previous line's derived end, or 0:00
                    // for the intro) until the coming line starts.
                    val start = SungLine.rowStartMs(rows, lines, idx)
                    val next = SungLine.nextLineMs(rows, lines, idx)!!
                    assertTrue("$tag: marker current too early @$pos ($start)", pos >= start)
                    assertTrue("$tag: marker current too late @$pos ($next)", pos < next)
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
            for (i in 0 until count) {
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
            if (count == 0) return
            assertEquals("$tag: outro holds the last lyric", rows.lastIndex, at(duration))
            val lastStart = lines[rows.last().realIndex].timeMs
            assertEquals(
                "$tag: outro holds from the last onset on",
                rows.lastIndex,
                at(lastStart + 50)
            )
        }

        private fun boardParity() {
            // Sequence-space parity, anchor 0: even offsets ride the top.
            for (v in rows.indices) {
                val (top, bottom) = AaKaraokeRows.pairFor(v, rows.size, 0)
                if (v % 2 == 0) {
                    assertEquals("$tag: row $v rides the top row", v, top)
                } else {
                    assertEquals("$tag: row $v rides the bottom row", v, bottom)
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
        // The parser strips every ♪ line (leading, mid-song and outro tags):
        // the sequence carries only the seven sung lines. Instrumentals are
        // derived from the timing silence, not from tags: line-timed holds
        // end at start + 10 s, so only "third"(+10=31 -> 31: zero silence
        // is NOT a gap) stays tight while "fourth"(41 -> 60) and the first
        // chorus instance (65 -> 90) own interlude markers.
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), s.rows.filter { !it.isGap }.map { it.realIndex })
        assertEquals(listOf(3, 4), s.rows.filter { it.isGap && it.realIndex >= 0 }.map { it.realIndex })
        // First line at 12 s: the intro marker owns row 0.
        assertTrue(s.rows.first().isGap && s.rows.first().realIndex == -1)
    }

    @Test
    fun wordlessGapCountsToNextReal() {
        // Payload without any word timings: the ♪ tag at 0:10 is stripped;
        // the silence it used to mark is derived from "hello there" holding
        // until 0:13 (start + 10 s), and the marker counts to the 0:19 lyric.
        val lines = LrcParser.parse(
            """
            [00:03.00]hello there
            [00:10.00]♪
            [00:19.00]back again
            [01:00.00]outro line
            """.trimIndent()
        )
        assertEquals(3, lines.size)
        val count = lines.size
        val rows = SungLine.virtualRows(lines, count)
        val gap = rows.indexOfFirst { it.isGap }
        check(gap >= 0)
        assertEquals(0, rows[gap].realIndex)
        assertEquals(gap, SungLine.currentVIndex(rows, lines, 13_000))
        assertEquals(4, SungLine.countdownSeconds(lines, 13_000, count))
        assertEquals(1, SungLine.countdownSeconds(lines, 18_200, count))
        // Back on the real lyric the moment it starts ("back again" is
        // line 1 of the STRIPPED payload - the ♪ tag never made it in).
        assertEquals(
            rows.indexOfFirst { !it.isGap && it.realIndex == 1 },
            SungLine.currentVIndex(rows, lines, 19_000)
        )
    }

    @Test
    fun strippedFlourishKeepsPreviousLineLit() {
        // A sub-threshold flourish tag is invisible twice over: stripped at
        // ingest AND too short to derive a marker. The line before it stays
        // current, and the real interlude after it still earns its marker.
        val lines = LrcParser.parse(
            """
            [00:00.00]hello there
            [00:05.00]♪
            [00:06.00]right back
            [00:12.00]♪
            [00:22.00]after the interlude
            """.trimIndent()
        )
        val count = lines.size
        val rows = SungLine.virtualRows(lines, count)
        val hello = rows.indexOfFirst { !it.isGap && it.realIndex == 0 }
        val back = rows.indexOfFirst { !it.isGap && it.realIndex == 1 }
        val gap = rows.indexOfFirst { it.isGap }
        check(hello >= 0 && back > hello && gap > back)
        // During the stripped flourish (0:05): "hello" still lit.
        assertEquals(hello, SungLine.currentVIndex(rows, lines, 5_000))
        assertEquals(hello, SungLine.currentVIndex(rows, lines, 5_900))
        // The 0:12 tag is gone, but the 6 s silence after "right back"'s
        // derived end (0:16) earns its marker.
        assertEquals(gap, SungLine.currentVIndex(rows, lines, 16_000))
        assertEquals(gap, SungLine.currentVIndex(rows, lines, 21_000))
    }

    @Test
    fun shortTagRunSkippedEntirely() {
        // A tag with no sung silence behind it (next line inside the gap
        // threshold of the hold): stripped at ingest and no marker derived
        // - as if it were never in the payload.
        val lines = LrcParser.parse(
            """
            [00:00.00]hello there
            [00:05.00]♪
            [00:06.00]right back
            """.trimIndent()
        )
        val rows = SungLine.virtualRows(lines, lines.size)
        assertTrue(rows.none { it.isGap })
        assertEquals(listOf(0, 1), rows.map { it.realIndex })
    }

    private fun lrclibEnhanced() = LrcParser.parseKaraoke(
        """
        [00:12.00]<00:12.00>first <00:13.50>words
        [00:20.00]<00:20.00>second <00:22.80>line
        [00:33.00]<00:33.00>after <00:34.00>gap
        [00:40.00]<00:40.00>tail
        [01:00.00]♪
        """.trimIndent()
    )

    @Test
    fun lrclibEnhancedSweep() {
        val s = sim("LRCLIB enhanced", lrclibEnhanced())
        // word 2 ends 22.8+5=27.8, next 33.0 -> 5.2 s marker owned by line 1.
        assertTrue(s.rows.any { it.isGap && it.realIndex == 1 })
        // The outro tag never became a row; nothing trails the tail line.
        assertTrue(s.rows.none { it.isGap && it.realIndex >= 3 })
        assertEquals(4, s.count)
    }

    private fun syncLrcKaraoke() = LrcParser.parseKaraoke(
        """
        [00:01.00]<00:01.00>君<00:01.40>を<00:01.80>忘<00:02.20>れ<00:02.60>な<00:03.00>い
        [00:04.00]<00:04.00>短い<00:04.80>词
        [00:16.00]<00:16.00>戻<00:16.50>っ<00:17.00>て
        [01:00.00]<01:00.00>final
        [01:05.00]♪
        [01:07.00]♪
        """.trimIndent()
    )

    @Test
    fun syncLrcKaraokeSweep() {
        val s = sim("SyncLRC karaoke", syncLrcKaraoke())
        // 04.8+5=9.8 end, next 16.0 -> 6.2 s marker owned by line 1; the
        // 16 s line's silence into the final line owns a second marker.
        assertEquals(listOf(1, 2), s.rows.filter { it.isGap }.map { it.realIndex })
        // 1 s intro: no intro marker.
        assertFalse(s.rows.first().isGap)
    }

    private fun syncLrcCreditHeader() = LrcParser.parseKaraoke(
        """
        [00:06.81]<00:06.81>词<00:08.52>：<00:10.22>林<00:11.93>夕
        [00:13.63]<00:13.63>曲<00:14.99>：<00:16.36>Paul<00:17.72>Gray
        [00:20.46]<00:20.46>犹<00:21.34>如<00:22.02>巡行
        [01:00.00]<01:00.00>final
        """.trimIndent()
    )

    @Test
    fun creditHeaderLinesAreStrippedBeforeMarkers() {
        val lines = LyricDisplay.stripNonLyric(syncLrcCreditHeader())
        assertEquals(listOf(20460L, 60000L), lines.map { it.timeMs })
        val s = sim("SyncLRC credit header", lines)
        // The 20.46 s intro rides the intro marker; the timed credit rows
        // never own a slot, never sing, and never reach a row at all.
        assertTrue(s.rows.first().isGap && s.rows.first().realIndex == -1)
        assertEquals(2, s.count)
        // With the credits gone, their 13.6 s of "silence" folds into the
        // intro and the real 36 s silence after line 0 owns the interlude.
        assertEquals(listOf(-1, 0), s.rows.filter { it.isGap }.map { it.realIndex })
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
        // The blank interlude entries are stripped at parse: three sung
        // lines remain, and the 64.5 -> 68 silence (3.5 s) earns no marker.
        assertEquals(3, lines.size)
        val s = sim("Musixmatch richsync", lines)
        assertTrue(s.rows.none { it.isGap && it.realIndex >= 0 })
    }

    @Test
    fun richSyncSingleChunkCountsAsToken() {
        // A whole CJK line riding ONE chunk still counts as token-timed
        // (spreadLongGaps re-spreads it): with the 68.0 start the spread
        // reaches the next line, so NO countdown may be guessed there.
        val lines = richSync()
        val coarse = lines[1]
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
        // 10+10=20 -> 20: zero silence is NOT a gap; the
        // ♪ tail is stripped entirely. (Row 0 is the 10 s intro marker.)
        assertTrue(s.rows.none { it.isGap && it.realIndex >= 0 })
        assertEquals(2, s.count)
    }

    // ---- hand-built edge scenarios (parser-sanitized shapes) ----

    @Test
    fun singleLineOnlySong() {
        val lines = LrcParser.parseKaraoke(
            """
            [00:10.00]<00:10.00>only <00:12.00>line
            [00:20.00]♪
            [00:22.00]♪
            [00:24.00]♪
            """.trimIndent()
        )
        val s = sim("single line", lines)
        // Intro marker owns row 0; the outro tags never become rows.
        assertEquals(2, s.rows.size)
        assertTrue(s.rows.first().isGap)
        assertEquals(17_000L, SungLine.outroStartMs(s.lines, s.count))
    }

    @Test
    fun allInstrumental() {
        val lines = LrcParser.parse(
            """
            [00:00.00]♪
            [00:30.00]🎵
            [01:00.00][Music]
            """.trimIndent()
        )
        val s = sim("all instrumental", lines)
        assertTrue(s.rows.isEmpty())
        assertEquals(-1, s.at(45_000))
    }

    @Test
    fun backToBackGapsStackRows() {
        val s = sim(
            "back-to-back gaps",
            listOf(
                line(60_000L, "A", 60_000L to 61_000L),
                line(72_000L, "B", 72_000L to 73_000L),
                line(84_000L, "C", 84_000L to 85_000L)
            )
        )
        // A: end 65 -> B at 72 (7 s); B: end 77 -> C at 84 (7 s).
        // Plus the 60 s intro marker: [intro A g B g C].
        assertEquals(2, s.rows.count { it.isGap && it.realIndex >= 0 })
        assertTrue(s.rows.first().isGap)
        assertEquals(6, s.rows.size)
    }

    @Test
    fun gapBoundaryExactlyMinimumDoesNotInsert() {
        val exact = listOf(
            line(60_000L, "A", 60_000L to 61_000L), // end 65.0 (last START + 5)
            line(70_000L, "B", 70_000L to 71_000L)  // +5.0 s exactly
        )
        assertTrue(SungLine.virtualRows(exact, 2).none { it.isGap && it.realIndex == 0 })
        val over = listOf(
            line(60_000L, "A", 60_000L to 61_000L),
            line(70_001L, "B", 70_001L to 71_000L)
        )
        assertTrue(SungLine.virtualRows(over, 2).any { it.isGap && it.realIndex == 0 })
    }

    @Test
    fun wordlessLineHoldsFiveSecondsBeforeGap() {
        // Empty word list = line-timed: end = start + 10 s, so 70.0 is
        // exactly the threshold and owns no marker.
        val lines = listOf(
            LyricLine(60_000L, "no words", emptyList()),
            line(70_000L, "next", 70_000L to 71_000L)
        )
        assertTrue(SungLine.virtualRows(lines, 2).none { it.isGap && it.realIndex == 0 })
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
        // [intro, verse, marker, returns]: past the derived end (154 s) the
        // marker is current...
        assertEquals(2, s.at(160_000))
        assertEquals(4, SungLine.countdownSeconds(s.lines, 160_000, s.count))
        // ...until the very last second, then hands over exactly on onset.
        assertEquals(1, SungLine.countdownSeconds(s.lines, 179_000, s.count))
        assertEquals(3, s.at(180_050))
    }

    @Test
    fun strippedLeadingTagsLeaveIntroMarkerOwner() {
        val lines = LrcParser.parseKaraoke(
            """
            [00:00.00]♪
            [00:08.00]♪
            [00:20.00]<00:20.00>first real
            [00:25.00]<00:25.00>second
            """.trimIndent()
        )
        val s = sim("leading tags", lines)
        // The tags are gone; the 20 s intro is derived and its marker owns
        // row 0 - current through the whole intro.
        assertEquals(0, s.at(5_000))
        assertEquals(0, s.at(19_000))
        // The intro marker takes the top slot, so the first real lyric is
        // sung from the BOTTOM - exactly where the intro preview places it.
        val v = s.at(20_050)
        val (_, bottom) = AaKaraokeRows.pairFor(v, s.rows.size, 0)
        assertEquals(v, bottom)
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
            [00:30.00][01:30.00]<00:30.00>sing<00:31.50>ing
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
        assertFalse(s.rows.any { it.isGap && it.realIndex == 1 })
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
    fun mixedSymbolSpellingsStripToOneGap() {
        val lines = LrcParser.parseKaraoke(
            """
            [01:00.00]<01:00.00>sung line
            [01:04.00]🎵
            [01:06.00][Instrumental]
            [01:08.00]♫ ♪
            [01:12.00]<01:12.00>back again
            """.trimIndent()
        )
        val s = sim("symbol spellings", lines)
        // 60+5=65 end, next REAL 72 -> one derived marker; every mixed
        // spelling is gone from the payload entirely.
        assertEquals(listOf(0, 1), s.rows.filter { !it.isGap }.map { it.realIndex })
        assertEquals(
            listOf(0),
            s.rows.filter { it.isGap && it.realIndex >= 0 }.map { it.realIndex }
        )
    }
}
