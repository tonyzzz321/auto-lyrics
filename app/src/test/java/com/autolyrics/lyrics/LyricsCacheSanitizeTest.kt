package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsCacheSanitizeTest {
    @Test
    fun stripsLegacyAnomalyTags() {
        assertEquals(
            "Musixmatch · RichSync",
            LyricsCache.sanitizeSource("Musixmatch · RichSync · gap3s")
        )
        assertEquals(
            "Musixmatch · RichSync",
            LyricsCache.sanitizeSource("Musixmatch · RichSync · fix4 oob2 miss1 gap5s")
        )
        assertEquals(
            "Musixmatch · RichSync",
            LyricsCache.sanitizeSource("Musixmatch · RichSync · miss7 · gap5s")
        )
    }

    @Test
    fun leavesCleanLabelsAlone() {
        assertEquals("Musixmatch · RichSync", LyricsCache.sanitizeSource("Musixmatch · RichSync"))
        assertEquals("LRCLIB · Karaoke", LyricsCache.sanitizeSource("LRCLIB · Karaoke"))
        assertEquals("SyncLRC · Karaoke", LyricsCache.sanitizeSource("SyncLRC · Karaoke"))
    }
}
