package com.autolyrics.media

import com.autolyrics.lyrics.LyricsProviderCandidate
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one-shot auto retry fires exactly on races where nothing was scoreable
 * AND a leg died - never on clean provider misses.
 */
class TransientMissTest {
    private fun attempt(
        provider: String,
        candidate: LyricsProviderCandidate? = null,
        timedOut: Boolean = false,
        transport: List<String> = emptyList()
    ) = ProviderAttempt(provider, candidate, timedOut, 100L, transport)

    private fun candidate() = LyricsProviderCandidate(
        provider = "LRCLIB",
        title = "Test Song",
        artist = "Test Artist",
        album = "Test Album",
        durationSec = 180.0,
        lines = listOf(LyricLine(1000, "a real lyric line")),
        status = LyricsStatus.FOUND,
        source = "LRCLIB · Synced",
        syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
    )

    @Test
    fun cleanEmptiesAreGenuineMiss() {
        assertEquals(false, isTransientMiss(listOf(attempt("LRCLIB"), attempt("SyncLRC"))))
    }

    @Test
    fun timeoutMakesMissTransient() {
        assertEquals(
            true,
            isTransientMiss(listOf(attempt("LRCLIB"), attempt("SyncLRC", timedOut = true)))
        )
    }

    @Test
    fun transportErrorMakesMissTransient() {
        assertEquals(
            true,
            isTransientMiss(listOf(attempt("LRCLIB", transport = listOf("HTTP 503"))))
        )
    }

    @Test
    fun scoreableCandidateIsNeverTransient() {
        assertEquals(
            false,
            isTransientMiss(listOf(attempt("LRCLIB", candidate()), attempt("SyncLRC", timedOut = true)))
        )
    }

    @Test
    fun emptyAttemptListIsNotTransient() {
        assertEquals(false, isTransientMiss(emptyList()))
    }
}
