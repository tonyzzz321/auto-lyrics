package com.autolyrics.util

import com.autolyrics.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricDisplayTest {
    @Test
    fun detectsProviderSymbolShapes() {
        assertEquals(true, LyricDisplay.isSymbolText("\u266A"))
        assertEquals(true, LyricDisplay.isSymbolText("\u266A \u266A \u266A"))
        assertEquals(true, LyricDisplay.isSymbolText("\u266B\u266B"))
        assertEquals(true, LyricDisplay.isSymbolText("\uD83C\uDFB5\uD83C\uDFB6"))
        assertEquals(true, LyricDisplay.isSymbolText("[Music]"))
        assertEquals(true, LyricDisplay.isSymbolText("(Instrumental)"))
        assertEquals(true, LyricDisplay.isSymbolText(""))
        assertEquals(false, LyricDisplay.isSymbolText("\u6708\u8272"))
        assertEquals(false, LyricDisplay.isSymbolText("Music on my mind"))
    }

    @Test
    fun displayTextNormalizesSymbols() {
        assertEquals("\u266A\u3000\u266A\u3000\u266A", LyricDisplay.displayText("\u266A\u266A \u266B"))
        assertEquals("\u266A\u3000\u266A\u3000\u266A", LyricDisplay.displayText("[Music]"))
        assertEquals("\u6708\u8272", LyricDisplay.displayText("\u6708\u8272"))
    }

    @Test
    fun detectsSectionHeaders() {
        assertEquals(true, LyricDisplay.isSymbolText("[Verse]"))
        assertEquals(true, LyricDisplay.isSymbolText("[Chorus]"))
        assertEquals(true, LyricDisplay.isSymbolText("[Verse 1]"))
        assertEquals(true, LyricDisplay.isSymbolText("[Pre-Chorus]"))
        assertEquals(true, LyricDisplay.isSymbolText("[Bridge]"))
        assertEquals(true, LyricDisplay.isSymbolText("\u3010\u526f\u6b4c\u3011"))
        assertEquals(true, LyricDisplay.isSymbolText("\u9593\u594f"))
        assertEquals(false, LyricDisplay.isSymbolText("[Versace]"))
        assertEquals(false, LyricDisplay.isSymbolText("Chorused into the night"))
        assertEquals(false, LyricDisplay.isSymbolText("99 Luftballons"))
    }

    @Test
    fun stripNonLyricKeepsOnlySungLines() {
        val lines = listOf(
            LyricLine(0, "\u266A"),
            LyricLine(500, "[Verse 1]"),
            LyricLine(1000, "a"),
            LyricLine(1500, ""),
            LyricLine(2000, "\uD83C\uDFB5\uD83C\uDFB6"),
            LyricLine(2500, "b"),
            LyricLine(3000, "[Music]"),
            LyricLine(3500, "(Instrumental)")
        )
        assertEquals(listOf("a", "b"), LyricDisplay.stripNonLyric(lines).map { it.text })
        assertEquals(emptyList<LyricLine>(), LyricDisplay.stripNonLyric(listOf(LyricLine(0, "\u266A"))))
    }

    @Test
    fun creditColonLines() {
        assertEquals(true, LyricDisplay.isCreditLine("词：林夕"))
        assertEquals(true, LyricDisplay.isCreditLine("曲：Paul Gray"))
        assertEquals(true, LyricDisplay.isCreditLine("作詞：中島みゆき"))
        assertEquals(true, LyricDisplay.isCreditLine("Lyrics: John Smith"))
        assertEquals(true, LyricDisplay.isCreditLine("OP: WARNER MUSIC"))
        assertEquals(true, LyricDisplay.isCreditLine("原唱：周杰倫"))
        assertEquals(true, LyricDisplay.isCreditLine("词：林夕 曲：Paul Gray"))
        assertEquals(false, LyricDisplay.isCreditLine("月色"))
        assertEquals(false, LyricDisplay.isCreditLine("我说：你好"))
        assertEquals(false, LyricDisplay.isCreditLine("Word by word"))
        assertEquals(false, LyricDisplay.isCreditLine("99 Luftballons"))
    }

    @Test
    fun creditByForms() {
        assertEquals(true, LyricDisplay.isCreditLine("Lyrics by John Smith"))
        assertEquals(true, LyricDisplay.isCreditLine("Composed by: Paul Gray"))
        assertEquals(true, LyricDisplay.isCreditLine("Words & Music: 林夕"))
        // Long sung lines opening with the same words survive the By-form.
        assertEquals(
            false,
            LyricDisplay.isCreditLine(
                "Written by the wind that carries you away across the ocean of my dreaming life"
            )
        )
    }

    @Test
    fun creditLinesAreStrippedAtIngest() {
        val lines = listOf(
            LyricLine(6810, "词：林夕"),
            LyricLine(13630, "曲：Paul Gray"),
            LyricLine(20460, "犹如巡行和汇演"),
            LyricLine(24000, "Lyrics by nobody")
        )
        assertEquals(listOf("犹如巡行和汇演"), LyricDisplay.stripNonLyric(lines).map { it.text })
    }

    @Test
    fun countdownSymbols() {
        assertEquals("\u266A", LyricDisplay.countdownSymbol(1))
        assertEquals(LyricDisplay.MUSIC_SYMBOL, LyricDisplay.countdownSymbol(3))
        assertEquals("\u266A\u3000\u266A\u3000\u266A\u3000\u266A", LyricDisplay.countdownSymbol(9))
        assertEquals("\u266A", LyricDisplay.countdownSymbol(0))
        assertEquals("\u266A\u3000\u266A", LyricDisplay.countdownSymbol(2))
    }
}
