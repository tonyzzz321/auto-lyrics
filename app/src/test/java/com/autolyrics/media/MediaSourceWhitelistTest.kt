package com.autolyrics.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The media-source whitelist predicate behind the Media apps picker: an empty
 * monitored set follows everything (today's default), a non-empty set follows
 * only its members, and our own package is never a candidate.
 */
class MediaSourceWhitelistTest {

    private val self = "com.autolyrics"

    @Test
    fun `empty whitelist follows every foreign app`() {
        assertEquals(true, sessionAllowed("com.spotify.music", self, emptySet()))
        assertEquals(true, sessionAllowed("com.apple.android.music", self, emptySet()))
    }

    @Test
    fun `own package is never allowed`() {
        assertEquals(false, sessionAllowed(self, self, emptySet()))
        assertEquals(false, sessionAllowed(self, self, setOf(self)))
    }

    @Test
    fun `whitelist admits only members`() {
        val monitored = setOf("com.spotify.music")
        assertEquals(true, sessionAllowed("com.spotify.music", self, monitored))
        assertEquals(false, sessionAllowed("com.apple.android.music", self, monitored))
    }

    @Test
    fun `unlisted playing app cannot stay selected once whitelist saves`() {
        // The sticky-session path re-runs through the same filter: a
        // deselected current app leaves the field and the selection moves on.
        val packages = listOf("com.spotify.music", "com.apple.android.music")
        val monitored = setOf("com.spotify.music")
        val candidates = packages.filter { sessionAllowed(it, self, monitored) }
        assertEquals(listOf("com.spotify.music"), candidates)
    }
}
