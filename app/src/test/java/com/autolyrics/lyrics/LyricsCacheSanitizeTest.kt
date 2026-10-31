package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsCacheSanitizeTest {
    private val words = listOf(LyricLine(0, "hi", listOf(LyricWord(0, "hi", 500))))
    private val bare = listOf(LyricLine(0, "hi"))

    @Test
    fun stripsLegacyAnomalyTags() {
        assertEquals(
            "Musixmatch · Karaoke",
            LyricsCache.sanitizeSource("Musixmatch · RichSync · gap3s", words)
        )
        assertEquals(
            "Musixmatch · Karaoke",
            LyricsCache.sanitizeSource("Musixmatch · RichSync · fix4 oob2 miss1 gap5s", words)
        )
        assertEquals(
            "Musixmatch · Karaoke",
            LyricsCache.sanitizeSource("Musixmatch · RichSync · miss7 · gap5s", words)
        )
    }

    @Test
    fun mapsProviderJargonOntoSharedVocabulary() {
        assertEquals(
            "Musixmatch · Synced",
            LyricsCache.sanitizeSource("Musixmatch · Line", bare)
        )
        assertEquals(
            "LRCLIB · Not synced",
            LyricsCache.sanitizeSource("LRCLIB · Plain", emptyList())
        )
        // Jargon still obeys content: a line-only "RichSync" demotes.
        assertEquals(
            "Musixmatch · Synced",
            LyricsCache.sanitizeSource("Musixmatch · RichSync", bare)
        )
    }

    @Test
    fun variantsFollowPayloadContent() {
        assertEquals(
            "LRCLIB · Karaoke",
            LyricsCache.sanitizeSource("LRCLIB · Synced", words)
        )
        assertEquals(
            "SyncLRC · Synced",
            LyricsCache.sanitizeSource("SyncLRC · Karaoke", bare)
        )
        assertEquals(
            "LRCLIB · Synced",
            LyricsCache.sanitizeSource("LRCLIB · Synced", bare)
        )
    }
}
