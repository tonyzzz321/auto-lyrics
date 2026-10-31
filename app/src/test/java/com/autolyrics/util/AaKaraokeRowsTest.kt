package com.autolyrics.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AaKaraokeRowsTest {

    @Test
    fun `board starts on top - even active line rides the top row`() {
        assertEquals(0 to 1, AaKaraokeRows.pairFor(0, 10))
        assertEquals(4 to 5, AaKaraokeRows.pairFor(4, 10))
    }

    @Test
    fun `even active index on top previews the odd line below`() {
        assertEquals(2 to 3, AaKaraokeRows.pairFor(2, 10))
    }

    @Test
    fun `odd active index sits on the bottom side`() {
        val (top, bottom) = AaKaraokeRows.pairFor(3, 10)
        assertEquals(4, top)
        assertEquals(3, bottom)
    }

    @Test
    fun `intro previews on top with the countdown slot below`() {
        assertEquals(0 to -1, AaKaraokeRows.pairFor(-1, 10))
        assertEquals(-1 to -1, AaKaraokeRows.pairFor(-1, 0))
        assertEquals(-1 to -1, AaKaraokeRows.pairFor(10, 10))
    }

    @Test
    fun `existing intro countdown owns the top row and lyrics ride bottom`() {
        // base = -1: the countdown occupies the sequence's top slot, so
        // the intro shows the countdown ON TOP with the first lyric
        // previewed (and later sung) on the bottom.
        assertEquals(-1 to 0, AaKaraokeRows.pairFor(-1, 10, base = -1))
        // First real lyric (idx 0, odd offset) rides the bottom row.
        assertEquals(1 to 0, AaKaraokeRows.pairFor(0, 10, base = -1))
    }

    @Test
    fun `song ending on an odd line leaves the top preview out of range`() {
        val (top, bottom) = AaKaraokeRows.pairFor(9, 10)
        assertEquals(-1, top)
        assertEquals(9, bottom)
    }

    @Test
    fun `song ending on an even line leaves the bottom preview out of range`() {
        val (top, bottom) = AaKaraokeRows.pairFor(8, 9)
        assertEquals(8, top)
        assertEquals(-1, bottom)
    }

    @Test
    fun `width counts cjk as two units and latin as one`() {
        assertEquals(2, AaKaraokeRows.displayWidth("あ"))
        assertEquals(4, AaKaraokeRows.displayWidth("あい"))
        assertEquals(2, AaKaraokeRows.displayWidth("ab"))
        assertEquals(6, AaKaraokeRows.displayWidth("abあい"))
    }

    @Test
    fun `width ignores combining marks and counts fullwidth punctuation wide`() {
        assertEquals(1, AaKaraokeRows.displayWidth("e\u0301"))
        assertEquals(2, AaKaraokeRows.displayWidth("！"))
        assertEquals(2, AaKaraokeRows.displayWidth("（"))
    }

    @Test
    fun `surrogate pairs sum to two units`() {
        assertEquals(2, AaKaraokeRows.displayWidth("😀"))
    }

    @Test
    fun `right align pads to reference width with fullwidth spaces`() {
        val padded = AaKaraokeRows.rightAlign("あい", 8)
        assertEquals("\u3000\u3000あい", padded)
        assertEquals(8, AaKaraokeRows.displayWidth(padded))
    }

    @Test
    fun `odd deficit tops up with ascii spaces`() {
        val padded = AaKaraokeRows.rightAlign("あい", 7)
        assertEquals("\u3000  あい", padded)
        assertTrue(Math.abs(3.5 - AaKaraokeRows.advanceEm(padded)) < 0.15)
    }

    @Test
    fun `text wider than reference is untouched`() {
        assertEquals("あい", AaKaraokeRows.rightAlign("あい", 2))
        assertEquals("あい", AaKaraokeRows.rightAlign("あい", 1))
    }

    @Test
    fun `marker rides inside the padding so the right edge holds`() {
        val padded = AaKaraokeRows.rightAlign("あい", 8, "\u25b6 ")
        assertEquals("   \u25b6 あい", padded)
        // The over-charged marker keeps the physical total inside the column.
        assertTrue(AaKaraokeRows.advanceEm(padded) <= 4.05)
    }

    @Test
    fun `active short line stays right-justified to the column`() {
        val column = 32
        val padded = AaKaraokeRows.rightAlign("オンナノコは目線ひとつ", column, "▶ ")
        // The over-charged marker keeps the physical total inside the column.
        assertTrue(AaKaraokeRows.advanceEm(padded) <= column * 0.5 + 0.05)
    }

    @Test
    fun `markerFor keeps the full marker when it fits the column`() {
        assertEquals("▶ ", AaKaraokeRows.markerFor(true, "あい", 12, "▶ "))
    }

    @Test
    fun `markerFor compacts the marker when the full one would force a wrap`() {
        assertEquals("▶", AaKaraokeRows.markerFor(true, "あい", 5, "▶ "))
    }

    @Test
    fun `markerFor drops the marker on inactive rows`() {
        assertEquals("", AaKaraokeRows.markerFor(false, "あい", 12, "▶ "))
    }

    @Test
    fun `marker cancellation puts lyric and translation tails on one column`() {
        val marker = "\u25b6\u2002"
        val lyric = AaKaraokeRows.rightAlign("冷たい涙が頬を濡らしても", 42, marker)
        val translation = AaKaraokeRows.rightAlign("即使冰冷的泪水打湿面颊", 42, marker)
        // Both strings charge the same model allowance for the same marker
        // string, so their display widths match exactly: whatever the host
        // renders the ▶ advance as, both tails shift by the same amount.
        assertEquals(AaKaraokeRows.advanceEm(lyric), AaKaraokeRows.advanceEm(translation), 0.05)
        assertTrue(AaKaraokeRows.advanceEm(lyric) <= 21.15)
    }

    @Test
    fun `latin-heavy lines pad by real advance not character counts`() {
        val line = "大丈夫傍にいる That\u0027s a promise forever"
        val padded = AaKaraokeRows.rightAlign(line, 42)
        // The unit-per-character model over-counted English lyrics by ~5 em,
        // leaving a visible gap at the right edge; advance-based padding must
        // land within a tenth of an em of the column instead.
        assertTrue(Math.abs(21.0 - AaKaraokeRows.advanceEm(padded)) < 0.15)
        val marker = "\u25b6\u2002"
        val active = AaKaraokeRows.rightAlign(line, 42, marker)
        assertTrue(AaKaraokeRows.advanceEm(active) <= 21.2)
        val pair = AaKaraokeRows.rightAlign("没关系 我在你身边 That\u0027s a promise forever", 42, marker)
        assertEquals(AaKaraokeRows.advanceEm(active), AaKaraokeRows.advanceEm(pair), 0.2)
    }

    @Test
    fun `autoColumnTracksHintedIconSize`() {
        // 143 px reference viewport calibrates to the 42-unit default.
        assertEquals(42, AaKaraokeRows.columnFromIconSize(143, 0))
        // Bigger head unit (proportional scaling) widens the column.
        assertEquals(59, AaKaraokeRows.columnFromIconSize(200, 0))
        assertEquals(40, AaKaraokeRows.columnFromIconSize(120, 0))
        // No hint keeps the fallback column; edge always layers on top.
        assertEquals(45, AaKaraokeRows.columnFromIconSize(-1, 3))
        assertEquals(44, AaKaraokeRows.columnFromIconSize(143, 2))
        // Clamped so wild hints can't produce wrap-prone columns.
        assertEquals(64, AaKaraokeRows.columnFromIconSize(500, 0))
        // Production head unit shipped icon=56 but rows at DHU-scale capacity;
        // the floor keeps its board justified.
        assertEquals(40, AaKaraokeRows.columnFromIconSize(56, 0))
        assertEquals(24, AaKaraokeRows.columnFromIconSize(60, -30))
    }

    @Test
    fun `latin and cjk lines padded to one column share an advance`() {
        val top = AaKaraokeRows.rightAlign("Hey! 君も", 42)
        val bottom = AaKaraokeRows.rightAlign("ここ", 42)
        assertEquals(AaKaraokeRows.advanceEm(top), AaKaraokeRows.advanceEm(bottom), 0.25)
    }

    @Test
    fun `parity anchors on the first real line`() {
        // Parity is SEQUENCE space: with an intro countdown (base -1) the
        // first real lyric rides the bottom; without one it rides the top.
        assertEquals(-1 to 0, AaKaraokeRows.pairFor(-1, 4, base = -1))
        assertEquals(1 to 0, AaKaraokeRows.pairFor(0, 4, base = -1))
        assertEquals(0 to 1, AaKaraokeRows.pairFor(0, 4))
        assertEquals(0 to -1, AaKaraokeRows.pairFor(-1, 4))
    }
    @Test
    fun truncateKeepsShortTextWhole() {
        assertEquals("Yoru ni Kakeru", AaKaraokeRows.truncate("Yoru ni Kakeru", 20.0))
        assertEquals("米津玄師", AaKaraokeRows.truncate("米津玄師", 20.0))
    }

    @Test
    fun truncateCutsLongTitlesWithEllipsis() {
        val long = "In the hour of my darkness, on a bed of raging sea"
        val cut = AaKaraokeRows.truncate(long, 20.0)
        assertTrue(cut.endsWith("\u2026"))
        assertTrue(AaKaraokeRows.advanceEm(cut) <= 20.0)
        assertTrue(cut.length > 1)
    }

    @Test
    fun truncateCountsCjkAtOneEmEach() {
        // 20 em budget: 19 CJK chars (19 em) + ellipsis (0.9 em) fit;
        // 20 chars + ellipsis do not.
        val cjk = "あ".repeat(25)
        val cut = AaKaraokeRows.truncate(cjk, 20.0)
        assertEquals(19 + 1, cut.length)
    }

    @Test
    fun truncateSurvivesTinyBudgetsAndSurrogates() {
        assertEquals("\u2026", AaKaraokeRows.truncate("abc", 0.9))
        assertEquals("abc", AaKaraokeRows.truncate("abc", 0.5))
        val emoji = "\uD83D\uDD25".repeat(30)
        val cut = AaKaraokeRows.truncate(emoji, 5.0)
        assertEquals("\uD83D\uDD25".repeat(4) + "\u2026", cut)
    }


    @Test
    fun wordGuardOnlyForProportionalRuns() {
        // Wide-char titles are model-exact: no reserve, edge-landing kept.
        assertEquals(0.0, AaKaraokeRows.wordGuardEm("ミラクルショッピング 〜ドン・キホーテのテーマ〜"), 0.001)
        assertEquals(0.0, AaKaraokeRows.wordGuardEm("思い立ったらいつだって"), 0.001)
        // Latin-heavy mixed title: the widest proportional run is reserved
        // (the run Gearhead would tip onto the second line).
        val mixed = AaKaraokeRows.wordGuardEm("レイジョアハンズ!! ~Raise Y/Our Hands")
        org.junit.Assert.assertTrue("guard $mixed", mixed >= 2.5)
        org.junit.Assert.assertTrue("guard $mixed", mixed <= 2.501)
        // Short proportional fragments never trip the guard.
        assertEquals(0.0, AaKaraokeRows.wordGuardEm("レイ!クレア"), 0.001)
        // A 3-char fragment reserves only its own width.
        assertEquals(1.84, AaKaraokeRows.wordGuardEm("レイ(CV.優)"), 0.01)
    }
}
