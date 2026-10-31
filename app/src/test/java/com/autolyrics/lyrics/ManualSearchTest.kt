package com.autolyrics.lyrics

import com.autolyrics.model.LyricsStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualSearchTest {

    private fun response(
        id: Int?,
        trackName: String?,
        artistName: String?,
        duration: Double?,
        syncedLyrics: String?,
        plainLyrics: String?
    ) = LrcLibClient.LrcLibResponse(
        id = id,
        trackName = trackName,
        artistName = artistName,
        albumName = "Album",
        duration = duration,
        instrumental = false,
        plainLyrics = plainLyrics,
        syncedLyrics = syncedLyrics
    )

    private val syncedHit = ManualSearchHit(
        provider = "LRCLIB",
        title = "Synced Song",
        artist = "Artist",
        durationSec = 240,
        kind = ManualHitKind.SYNCED,
        lines = emptyList(),
        status = LyricsStatus.FOUND,
        source = "LRCLIB"
    )

    @Test
    fun convertsSyncedResponsesPreferringSyncedLyrics() {
        val hits = ManualSearch.toHits(
            listOf(
                response(
                    id = 1,
                    trackName = "Blue Spring",
                    artistName = "YOASOBI",
                    duration = 212.4,
                    syncedLyrics = "[00:01.00]first\n[00:05.00]second",
                    plainLyrics = "should be ignored"
                )
            )
        )

        assertEquals(1, hits.size)
        assertEquals(LyricsStatus.FOUND, hits.single().status)
        assertEquals(212, hits.single().durationSec)
        assertEquals(2, hits.single().lines.size)
        assertEquals("LRCLIB · Synced", hits.single().source)
    }

    @Test
    fun plainOnlyResponsesBecomePlainHitsAndNoiseIsSkipped() {
        val hits = ManualSearch.toHits(
            listOf(
                response(
                    id = 2,
                    trackName = "Plain Song",
                    artistName = "Artist",
                    duration = 180.0,
                    syncedLyrics = "[00:01.00]♪",
                    plainLyrics = "line one\n\nline two"
                ),
                response(
                    id = 3,
                    trackName = "Instrumental-ish",
                    artistName = "Artist",
                    duration = 90.0,
                    syncedLyrics = null,
                    plainLyrics = "  \n \n"
                )
            )
        )

        val hit = hits.single()
        assertEquals(LyricsStatus.PLAIN_ONLY, hit.status)
        assertEquals(listOf("line one", "line two"), hit.lines.map { it.text })
    }

    @Test
    fun duplicateIdsAppearOnce() {
        val base = response(
            id = 7,
            trackName = "Same",
            artistName = "Artist",
            duration = 100.0,
            syncedLyrics = "[00:01.00]text",
            plainLyrics = null
        )
        assertEquals(1, ManualSearch.toHits(listOf(base, base)).size)
    }

    @Test
    fun rankingPrefersBestKindThenClosestDurationThenTitle() {
        val plainClose = syncedHit.copy(title = "Plain Close", kind = ManualHitKind.PLAIN, durationSec = 241)
        val syncedFar = syncedHit.copy(title = "Far Sync", durationSec = 600)
        val syncedNoDuration = syncedHit.copy(title = "No Duration Sync", durationSec = null)
        val syncedExact = syncedHit.copy(title = "Exact Sync", durationSec = 240)

        val ranked = ManualSearch.rank(
            listOf(plainClose, syncedFar, syncedNoDuration, syncedExact),
            ourDurationSec = 240
        )

        assertEquals(
            listOf("Exact Sync", "Far Sync", "No Duration Sync", "Plain Close"),
            ranked.map { it.title }
        )
    }

    @Test
    fun karaokeBeatsSyncedEvenWithWorseDurationMatch() {
        val karaokeFar = syncedHit.copy(
            title = "Karaoke Far",
            kind = ManualHitKind.KARAOKE,
            durationSec = 300
        )
        val syncedExact = syncedHit.copy(title = "Exact Sync", durationSec = 240)

        val ranked = ManualSearch.rank(listOf(syncedExact, karaokeFar), ourDurationSec = 240)
        assertEquals(listOf("Karaoke Far", "Exact Sync"), ranked.map { it.title })
    }

    @Test
    fun enhancedLrcResponsesBecomeKaraokeHits() {
        val hits = ManualSearch.toHits(
            listOf(
                response(
                    id = 9,
                    trackName = "Karaoke Song",
                    artistName = "Artist",
                    duration = 200.0,
                    syncedLyrics = "[00:01.00]<00:01.00>hello <00:02.50>world",
                    plainLyrics = null
                )
            )
        )

        val hit = hits.single()
        assertEquals(ManualHitKind.KARAOKE, hit.kind)
        assertEquals("LRCLIB · Karaoke", hit.source)
        assertEquals(listOf("hello ", "world"), hit.lines.single().words.map { it.text })
    }

    @Test
    fun syncLrcResponsesBecomeProviderLabelledHits() {
        val karaoke = ManualSearch.toSyncLrcHit(
            SyncLrcClient.ApiResponse(
                karaoke = "[00:01.00]<00:01.00>hello <00:02.00>world",
                track = "Karaoke Track",
                artist = "Performer",
                duration = 199.0
            )
        )!!
        assertEquals(ManualHitKind.KARAOKE, karaoke.kind)
        assertEquals("SyncLRC", karaoke.provider)
        assertEquals("SyncLRC · Karaoke", karaoke.source)
        assertEquals(199, karaoke.durationSec)

        val syncedOnly = ManualSearch.toSyncLrcHit(
            SyncLrcClient.ApiResponse(
                synced = "[00:01.00]line",
                track = "T",
                artist = "A"
            )
        )!!
        assertEquals(ManualHitKind.SYNCED, syncedOnly.kind)

        assertEquals(null, ManualSearch.toSyncLrcHit(null))
        assertEquals(null, ManualSearch.toSyncLrcHit(SyncLrcClient.ApiResponse(instrumental = true)))
        assertEquals(null, ManualSearch.toSyncLrcHit(SyncLrcClient.ApiResponse(plain = "  ")))
    }

    @Test
    fun plainHitsGetCjkSpacingCleanup() {
        val hits = ManualSearch.toHits(
            listOf(
                response(
                    id = 10,
                    trackName = "Plain CJK",
                    artistName = "Artist",
                    duration = 100.0,
                    syncedLyrics = null,
                    plainLyrics = "春 の 光"
                )
            )
        )
        assertEquals("春の光", hits.single().lines.single().text)
    }

    @Test
    fun mergeKeepsFirstSeenOrderAndDedupesAcrossBatches() {
        val first = response(1, "A", "X", 100.0, "[00:01.00]a", null)
        val second = response(2, "B", "Y", 200.0, "[00:01.00]b", null)
        val idless = response(null, "C", "Z", 300.0, "[00:01.00]c", null)

        val merged = ManualSearch.mergeResponses(
            listOf(
                listOf(first, second),
                listOf(first.copy(trackName = "dup"), second.copy(trackName = "B alt"), idless, idless)
            )
        )

        assertEquals(listOf(1, 2, null), merged.map { it.id })
        assertEquals(listOf("A", "B", "C"), merged.map { it.trackName })
    }

    @Test
    fun durationFormatting() {
        assertEquals("4:35", ManualSearch.formatDuration(275))
        assertEquals("0:07", ManualSearch.formatDuration(7))
        assertEquals("--:--", ManualSearch.formatDuration(null))
        assertEquals("--:--", ManualSearch.formatDuration(0))
    }

    @Test
    fun hitLabelCarriesKindDurationAndCredits() {
        assertEquals(
            "Synced · LRCLIB · 4:00 — Synced Song — Artist",
            syncedHit.copy(durationSec = 240).label
        )
        assertEquals(
            "Not synced · LRCLIB · --:-- — ? — ?",
            syncedHit.copy(kind = ManualHitKind.PLAIN, durationSec = null, title = "", artist = "").label
        )
    }

    @Test
    fun titleFilterKeepsRelatedTitlesAndDropsForeignOnes() {
        assert(ManualSearch.titleIsRelevant("Hello (Official Video)", "hello"))
        assert(ManualSearch.titleIsRelevant("Hello", "Hello (Remastered 2011)"))
        assert(ManualSearch.titleIsRelevant("Hello World", "hello"))
        assert(ManualSearch.titleIsRelevant("柠檬", "Lemon 柠檬"))
        assert(ManualSearch.titleIsRelevant("Lemon 柠檬", "柠檬"))
        assert(ManualSearch.titleIsRelevant("Yesterday", ""))
        assert(!ManualSearch.titleIsRelevant("Come Together", "hello"))
        assert(!ManualSearch.titleIsRelevant("Yellow Submarine", "hello world"))
    }
    @Test
    fun rankPrefersMatchingArtistOverCover() {
        val real = ManualSearchHit(
            provider = "LRCLIB",
            title = "Yoru ni Kakeru",
            artist = "Yorushika",
            durationSec = 267,
            kind = ManualHitKind.SYNCED,
            lines = emptyList(),
            status = LyricsStatus.FOUND,
            source = "LRCLIB · Synced"
        )
        val cover = real.copy(artist = "Rainych; AVIAND")
        val ranked = ManualSearch.rank(
            listOf(cover, real),
            ourDurationSec = 258,
            ourArtist = "Yorushika"
        )
        assertEquals("Yorushika", ranked.first().artist)

        // Without a reference artist the order stays duration-driven.
        assertEquals(
            2,
            ManualSearch.rank(listOf(cover, real), ourDurationSec = 267).size
        )
    }

    @Test
    fun artistPenaltyIgnoresFeaturingsAndParentheticals() {
        assertEquals(0, ManualSearch.artistPenalty("First Aid Kit", "First Aid Kit feat. Emiline"))
        assertEquals(0, ManualSearch.artistPenalty("Yorushika (よるしか)", "Yorushika"))
        assertEquals(0, ManualSearch.artistPenalty("", "Yorushika"))
        assertEquals(1, ManualSearch.artistPenalty("Rainych", "Yorushika"))
    }

}
