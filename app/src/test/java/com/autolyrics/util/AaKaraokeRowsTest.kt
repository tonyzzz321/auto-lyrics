package com.autolyrics.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AaKaraokeRowsTest {

    @Test
    fun `active even line keeps top and previews odd line below`() {
        assertEquals(0 to 1, AaKaraokeRows.pairFor(0, 10))
        assertEquals(4 to 5, AaKaraokeRows.pairFor(4, 10))
    }

    @Test
    fun `even active index on top previews the odd line below`() {
        assertEquals(2 to 3, AaKaraokeRows.pairFor(2, 10))
    }

    @Test
    fun `odd active index sits on bottom side`() {
        val (top, bottom) = AaKaraokeRows.pairFor(3, 10)
        assertEquals(4, top)
        assertEquals(3, bottom)
    }

    @Test
    fun `beyond last line yields empty placeholders`() {
        assertEquals(-1 to -1, AaKaraokeRows.pairFor(-1, 10))
        assertEquals(-1 to -1, AaKaraokeRows.pairFor(10, 10))
    }

    @Test
    fun `song ending on an odd line leaves the top preview out of range`() {
        val (top, bottom) = AaKaraokeRows.pairFor(9, 10)
        assertEquals(10, top)
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
    fun `odd deficit uses one halfwidth space`() {
        val padded = AaKaraokeRows.rightAlign("あい", 7)
        assertEquals("\u3000 あい", padded)
        assertEquals(7, AaKaraokeRows.displayWidth(padded))
    }

    @Test
    fun `text wider than reference is untouched`() {
        assertEquals("あい", AaKaraokeRows.rightAlign("あい", 2))
        assertEquals("あい", AaKaraokeRows.rightAlign("あい", 1))
    }

    @Test
    fun `marker rides inside the padding so the right edge holds`() {
        val padded = AaKaraokeRows.rightAlign("あい", 8, "▶  ")
        assertEquals(" ▶  あい", padded)
        assertEquals(8, AaKaraokeRows.displayWidth(padded))
    }

    @Test
    fun `active short line stays right-justified to the column`() {
        val column = 32
        val padded = AaKaraokeRows.rightAlign("オンナノコは目線ひとつ", column, "▶  ")
        assertEquals(column, AaKaraokeRows.displayWidth(padded))
    }

    @Test
    fun `markerFor keeps the full marker when it fits the column`() {
        assertEquals("▶  ", AaKaraokeRows.markerFor(true, "あい", 12, "▶  "))
    }

    fun `markerFor compacts the marker when the full one would force a wrap`() {
        assertEquals("▶", AaKaraokeRows.markerFor(true, "あい", 6, "▶  "))
    }

    fun `markerFor drops the marker on inactive rows`() {
        assertEquals("", AaKaraokeRows.markerFor(false, "あい", 12, "▶  "))
    }

    fun `mixed script lines stay aligned by width units`() {
        val top = "Hey! 君も"
        val bottom = "ここ"
        val padded = AaKaraokeRows.rightAlign(bottom, AaKaraokeRows.displayWidth(top))
        assertEquals(AaKaraokeRows.displayWidth(top), AaKaraokeRows.displayWidth(padded))
    }
}
