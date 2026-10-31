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
    fun lastMeaningfulIndexTrimsTrailingSymbols() {
        val lines = listOf(
            LyricLine(0, "a"),
            LyricLine(1000, "\u266A"),
            LyricLine(2000, "b"),
            LyricLine(3000, "[Music]"),
            LyricLine(4000, "")
        )
        assertEquals(2, LyricDisplay.lastMeaningfulIndex(lines))
        assertEquals(-1, LyricDisplay.lastMeaningfulIndex(emptyList()))
        assertEquals(-1, LyricDisplay.lastMeaningfulIndex(listOf(LyricLine(0, "\u266A"))))
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
