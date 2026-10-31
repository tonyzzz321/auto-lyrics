package com.autolyrics

import com.autolyrics.media.lyricsOffsetKey
import com.autolyrics.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LyricsOffsetTest {

    @Test
    fun emptyPayloadHasNoKey() {
        assertEquals("", lyricsOffsetKey(emptyList()))
    }

    @Test
    fun keyIsStableAndOrderAndStampSensitive() {
        val a = lyricsOffsetKey(listOf(LyricLine(1000, "one"), LyricLine(2000, "two")))
        val same = lyricsOffsetKey(listOf(LyricLine(1000, "one"), LyricLine(2000, "two")))
        val reTimed = lyricsOffsetKey(listOf(LyricLine(1100, "one"), LyricLine(2000, "two")))
        val reordered = lyricsOffsetKey(listOf(LyricLine(2000, "two"), LyricLine(1000, "one")))
        val reWorded = lyricsOffsetKey(listOf(LyricLine(1000, "one!"), LyricLine(2000, "two")))
        assertEquals(a, same)
        assertNotEquals(a, reTimed)
        assertNotEquals(a, reordered)
        assertNotEquals(a, reWorded)
    }

    @Test
    fun keyIsShortHex() {
        val key = lyricsOffsetKey(listOf(LyricLine(0, "hello")))
        assertEquals(16, key.length)
        assert(key.all { it in "0123456789abcdef" })
    }

    @Test
    fun identicalTextWithDifferentProviderShapeKeysSame() {
        // Two providers delivering the exact same lines share the offset —
        // the key is the lyric content, never the source.
        val a = lyricsOffsetKey(listOf(LyricLine(500, "a"), LyricLine(1500, "b")))
        val b = lyricsOffsetKey(listOf(LyricLine(500, "a"), LyricLine(1500, "b")))
        assertEquals(a, b)
    }
}
