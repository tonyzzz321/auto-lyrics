package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsProviderResolverTest {

    @Test
    fun japaneseTrackPrefersPetitLyricsWhenMetadataIsEquallyStrong() {
        val track = TrackInfo(
            title = "みずいろの雨",
            artist = "Junko Yagami",
            album = "Test Album",
            durationMs = 204_000L
        )

        val lrcLib = candidate(
            provider = "LRCLIB",
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = 204.0,
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )
        val petit = candidate(
            provider = "PetitLyrics",
            title = track.title,
            artist = "八神純子",
            album = track.album,
            durationSec = null,
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC
        )

        val selected = LyricsProviderResolver.selectBest(track, listOf(lrcLib, petit))

        assertEquals("PetitLyrics", selected?.candidate?.provider)
    }

    @Test
    fun interleavedRomanizationReceivesQualityPenalty() {
        val track = TrackInfo("アイドル", "YOASOBI", "", 210_000L)
        val contaminated = LyricsProviderCandidate(
            provider = "LRCLIB",
            title = track.title,
            artist = track.artist,
            album = "",
            durationSec = 210.0,
            lines = listOf(
                LyricLine(1_000, "これは日本語です"),
                LyricLine(2_000, "kore wa nihongo desu"),
                LyricLine(3_000, "次の日本語です"),
                LyricLine(4_000, "tsugi no nihongo desu"),
                LyricLine(5_000, "さらに日本語です"),
                LyricLine(6_000, "sarani nihongo desu"),
                LyricLine(7_000, "最後の日本語です"),
                LyricLine(8_000, "saigo no nihongo desu")
            ),
            status = LyricsStatus.FOUND,
            source = "LRCLIB · Synced",
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )
        val clean = contaminated.copy(
            provider = "PetitLyrics",
            lines = listOf(
                LyricLine(1_000, "これは日本語です"),
                LyricLine(2_000, "次の日本語です"),
                LyricLine(3_000, "さらに日本語です"),
                LyricLine(4_000, "最後の日本語です"),
                LyricLine(5_000, "別の日本語です"),
                LyricLine(6_000, "終わりの日本語です")
            ),
            source = "PetitLyrics · Synced",
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC
        )

        val badScore = LyricsProviderResolver.lyricsQualityScore(contaminated, track.durationMs)
        val cleanScore = LyricsProviderResolver.lyricsQualityScore(clean, track.durationMs)

        assertTrue(badScore < cleanScore)
        assertTrue(badScore < 0.80)
    }

    @Test
    fun romanizedArtistVsJapaneseArtistIsNeutralWithAlbumEvidence() {
        val track = TrackInfo("巡恋歌", "Tsuyoshi Nagabuchi", "風は南から", 215_000L)
        val petit = candidate(
            provider = "PetitLyrics",
            title = "巡恋歌",
            artist = "長渕 剛",
            album = "風は南から",
            durationSec = null,
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )

        val metadata = LyricsProviderResolver.metadataScore(track, petit)

        assertTrue(metadata != null && metadata > 0.95)
    }

    @Test
    fun artistConstrainedPetitLyricsQueryCorroboratesCrossScriptArtist() {
        val track = TrackInfo(
            title = "君を忘れない",
            artist = "Chiharu Matsuyama",
            album = "TOUR",
            durationMs = 288_000L
        )
        val petit = candidate(
            provider = "PetitLyrics",
            title = "君を忘れない",
            artist = "松山千春",
            album = "別アルバム",
            durationSec = null,
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC,
            artistQueryCorroborated = true
        )

        val metadata = LyricsProviderResolver.metadataScore(track, petit)

        assertTrue(metadata != null && metadata > 0.90)
    }

    @Test
    fun titleOnlyCrossScriptArtistMismatchWithoutSecondaryEvidenceIsRejected() {
        val track = TrackInfo("同じタイトル", "Romanized Artist", "", 200_000L)
        val petit = candidate(
            provider = "PetitLyrics",
            title = "同じタイトル",
            artist = "別の歌手",
            album = "",
            durationSec = null,
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC,
            artistQueryCorroborated = false
        )

        assertNull(LyricsProviderResolver.metadataScore(track, petit))
    }

    @Test
    fun compositeMediaArtistAcceptsExactContributorForExactTitle() {
        val track = TrackInfo(
            title = "Under the Sea",
            artist = "Alan Menken, Howard Ashman, Samuel E. Wright, Disney",
            album = "The Little Mermaid",
            durationMs = 195_000L
        )
        val petit = candidate(
            provider = "PetitLyrics",
            title = "Under the Sea",
            artist = "Samuel E. Wright",
            album = "The Little Mermaid",
            durationSec = null,
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC
        )

        val metadata = LyricsProviderResolver.metadataScore(track, petit)

        assertTrue(metadata != null && metadata > 0.95)
    }

    @Test
    fun compositeMediaArtistStillRejectsUnrelatedArtist() {
        val track = TrackInfo(
            title = "Under the Sea",
            artist = "Alan Menken, Howard Ashman, Samuel E. Wright, Disney",
            album = "",
            durationMs = 195_000L
        )
        val unrelated = candidate(
            provider = "PetitLyrics",
            title = "Under the Sea",
            artist = "Completely Different Singer",
            album = "",
            durationSec = null,
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC
        )

        assertNull(LyricsProviderResolver.metadataScore(track, unrelated))
    }

    @Test
    fun westernTrackKeepsLrcLibTiePreferenceInStandardMode() {
        val track = TrackInfo("Example Song", "Example Artist", "Example Album", 200_000L)
        val lrcLib = candidate(
            provider = "LRCLIB",
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = 200.0,
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )
        val musixmatch = candidate(
            provider = "Musixmatch",
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = 200.0,
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC,
            withWords = true
        )

        val selected = LyricsProviderResolver.selectBest(track, listOf(lrcLib, musixmatch))

        assertEquals("LRCLIB", selected?.candidate?.provider)
    }

    @Test
    fun westernTrackPrefersEquivalentWordSyncInKaraokeMode() {
        val track = TrackInfo("Example Song", "Example Artist", "Example Album", 200_000L)
        val lrcLib = candidate(
            provider = "LRCLIB",
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = 200.0,
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )
        val musixmatch = candidate(
            provider = "Musixmatch",
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = 200.0,
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC,
            withWords = true
        )

        val selected = LyricsProviderResolver.selectBest(
            track,
            listOf(lrcLib, musixmatch),
            preferWordSync = true
        )

        assertEquals("Musixmatch", selected?.candidate?.provider)
    }

    @Test
    fun karaokePreferenceRequiresActualWordPayload() {
        val track = TrackInfo("Example Song", "Example Artist", "Example Album", 200_000L)
        val lrcLib = candidate(
            provider = "LRCLIB",
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = 200.0,
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )
        val mislabeledWordSync = candidate(
            provider = "Musixmatch",
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = 200.0,
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC,
            withWords = false
        )

        val selected = LyricsProviderResolver.selectBest(
            track,
            listOf(lrcLib, mislabeledWordSync),
            preferWordSync = true
        )

        assertEquals("LRCLIB", selected?.candidate?.provider)
    }

    @Test
    fun karaokePreferenceDoesNotOverrideMateriallyWorsePayload() {
        val track = TrackInfo("Example Song", "Example Artist", "Example Album", 200_000L)
        val lrcLib = candidate(
            provider = "LRCLIB",
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = 200.0,
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )
        val shortWordLines = (0 until 10).map { index ->
            val timeMs = 1_000L + index * 4_000L
            LyricLine(
                timeMs = timeMs,
                text = "word$index",
                words = listOf(LyricWord(timeMs, "word$index"))
            )
        }
        val weakWordSync = LyricsProviderCandidate(
            provider = "Musixmatch",
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = 200.0,
            lines = shortWordLines,
            status = LyricsStatus.FOUND,
            source = "Musixmatch · test",
            syncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC
        )

        val selected = LyricsProviderResolver.selectBest(
            track,
            listOf(lrcLib, weakWordSync),
            preferWordSync = true
        )

        assertEquals("LRCLIB", selected?.candidate?.provider)
    }

    @Test
    fun synchronizedCandidateAlwaysBeatsPlainLyrics() {
        val track = TrackInfo("テスト曲", "テスト歌手", "", 180_000L)
        val plain = candidate(
            provider = "LRCLIB",
            title = track.title,
            artist = track.artist,
            album = "",
            durationSec = 180.0,
            status = LyricsStatus.PLAIN_ONLY,
            syncKind = LyricsProviderCandidate.SyncKind.PLAIN
        )
        val synced = candidate(
            provider = "PetitLyrics",
            title = track.title,
            artist = track.artist,
            album = "",
            durationSec = null,
            status = LyricsStatus.FOUND,
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )

        val selected = LyricsProviderResolver.selectBest(track, listOf(plain, synced))

        assertEquals("PetitLyrics", selected?.candidate?.provider)
    }

    @Test
    fun symbolOnlyPayloadsAreRejectedSoRealCandidatesWin() {
        fun symbolCandidate(provider: String, texts: List<String>) = LyricsProviderCandidate(
            provider = provider,
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            durationSec = 180.0,
            lines = texts.mapIndexed { i, t -> LyricLine((i + 1) * 5_000L, t) },
            status = LyricsStatus.FOUND,
            source = "$provider · Synced",
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )
        val junk = symbolCandidate(
            "LRCLIB",
            listOf("♪", "♪ ♪", "🎵", "[Music]", "(Instrumental)", "词：林夕", "Lyrics by X", "   ")
        )
        val real = candidate(
            provider = "Musixmatch",
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            durationSec = 180.0,
            syncKind = LyricsProviderCandidate.SyncKind.LINE_SYNC
        )

        assertFalse(LyricsProviderResolver.hasDisplayableContent(junk))
        assertTrue(LyricsProviderResolver.hasDisplayableContent(real))
        assertTrue(
            LyricsProviderResolver.hasDisplayableContent(
                symbolCandidate("LRCLIB", listOf("♪", "a real lyric line"))
            )
        )

        val track = TrackInfo("Test Song", "Test Artist", "Test Album", 180_000L)
        // The junk payload is the FIRST candidate and from the more trusted
        // provider; it must still lose to the payload with actual lyrics.
        val selected = LyricsProviderResolver.selectBest(track, listOf(junk, real))
        assertEquals("Musixmatch", selected?.candidate?.provider)

        // All-junk field resolves as if no lyrics were found at all.
        assertNull(LyricsProviderResolver.selectBest(track, listOf(junk)))
    }

    private fun candidate(
        provider: String,
        title: String,
        artist: String,
        album: String,
        durationSec: Double?,
        status: LyricsStatus = LyricsStatus.FOUND,
        syncKind: LyricsProviderCandidate.SyncKind,
        artistQueryCorroborated: Boolean = false,
        withWords: Boolean = false
    ): LyricsProviderCandidate {
        val lines = listOf(
            LyricLine(1_000, "test line one"),
            LyricLine(10_000, "test line two"),
            LyricLine(20_000, "test line three"),
            LyricLine(30_000, "test line four")
        ).map { line ->
            if (withWords) {
                line.copy(words = listOf(LyricWord(line.timeMs, line.text)))
            } else {
                line
            }
        }

        return LyricsProviderCandidate(
            provider = provider,
            title = title,
            artist = artist,
            album = album,
            durationSec = durationSec,
            lines = lines,
            status = status,
            source = "$provider · test",
            syncKind = syncKind,
            artistQueryCorroborated = artistQueryCorroborated
        )
    }
}
