package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import com.autolyrics.util.LyricDisplay
import kotlin.math.abs

/**
 * A normalized candidate returned by a lyrics provider.
 *
 * Provider-specific search logic is still responsible for finding a plausible
 * song. This model lets Auto Lyrics compare the final provider candidates with
 * one scoring system instead of treating any provider as an unconditional first
 * choice.
 */
data class LyricsProviderCandidate(
    val provider: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Double?,
    val lines: List<LyricLine>,
    val status: LyricsStatus,
    val source: String,
    val syncKind: SyncKind,
    val artistQueryCorroborated: Boolean = false
) {
    enum class SyncKind {
        WORD_SYNC,
        LINE_SYNC,
        PLAIN
    }
}

data class LyricsProviderScore(
    val candidate: LyricsProviderCandidate,
    val metadataScore: Double,
    val qualityScore: Double,
    val sourceConfidence: Double,
    val finalScore: Double
)

object LyricsProviderResolver {

    private const val MIN_METADATA_SCORE = 0.70
    private const val MIN_TITLE_SCORE = 0.60
    private const val MIN_ARTIST_SCORE = 0.40
    private const val CROSS_SCRIPT_ALBUM_EVIDENCE = 0.65

    private const val METADATA_WEIGHT = 0.82
    private const val QUALITY_WEIGHT = 0.10
    private const val SOURCE_WEIGHT = 0.08

    // Karaoke mode may prefer a real word-timed candidate over the normal winner,
    // but only when recording identity and payload quality remain near-equivalent.
    // This prevents a weaker live/remix/mismatched result from winning merely
    // because it happens to contain word timestamps.
    private const val KARAOKE_METADATA_TOLERANCE = 0.03
    private const val KARAOKE_QUALITY_TOLERANCE = 0.05

    private val JAPANESE_SCRIPT = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff]")
    private val LATIN_SCRIPT = Regex("[A-Za-z]")

    fun selectBest(
        track: TrackInfo,
        candidates: Collection<LyricsProviderCandidate>,
        preferWordSync: Boolean = false
    ): LyricsProviderScore? {
        val displayable = candidates.filter { hasDisplayableContent(it) }
        val scored = scoreCandidates(track, displayable)
        val synced = scored.filter { it.candidate.status == LyricsStatus.FOUND }
        val standardBest = synced.maxByOrNull { it.finalScore }

        if (standardBest != null) {
            if (preferWordSync) {
                val karaokeBest = synced
                    .asSequence()
                    .filter { hasUsableWordTiming(it.candidate) }
                    .filter {
                        it.metadataScore >= standardBest.metadataScore - KARAOKE_METADATA_TOLERANCE &&
                            it.qualityScore >= standardBest.qualityScore - KARAOKE_QUALITY_TOLERANCE
                    }
                    .maxByOrNull { it.finalScore }

                if (karaokeBest != null) return karaokeBest
            }
            return standardBest
        }

        // A synchronized candidate always beats plain lyrics. Plain LRCLIB text
        // remains a last-resort fallback when no provider has usable timing.
        return scored
            .filter { it.candidate.status == LyricsStatus.PLAIN_ONLY }
            .maxByOrNull { it.finalScore }
    }

    /**
     * Auto-resolution treats an all-blank/all-symbol payload as NO lyrics.
     * Such a payload renders as nothing (the display sequence skips every
     * symbol line), and accepting it would cache the track permanently as a
     * success, out-competing the next-best provider that has real text. The
     * detector is the display's own `isSymbolText`/`isCreditLine` pair, so
     * acceptance and rendering can never disagree about what counts as
     * lyric content.
     */
    internal fun hasDisplayableContent(candidate: LyricsProviderCandidate): Boolean =
        hasDisplayableLines(candidate.lines)

    /** Line-list form of the junk gate, shared with the Manual Search rows. */
    internal fun hasDisplayableLines(lines: List<LyricLine>): Boolean =
        lines.any { !LyricDisplay.isSymbolText(it.text) && !LyricDisplay.isCreditLine(it.text) }

    internal fun hasUsableWordTiming(candidate: LyricsProviderCandidate): Boolean {
        return candidate.syncKind == LyricsProviderCandidate.SyncKind.WORD_SYNC &&
            candidate.lines.any { it.words.isNotEmpty() }
    }

    fun scoreCandidates(
        track: TrackInfo,
        candidates: Collection<LyricsProviderCandidate>
    ): List<LyricsProviderScore> {
        return candidates.mapNotNull { candidate ->
            val metadata = metadataScore(track, candidate) ?: return@mapNotNull null
            if (metadata < MIN_METADATA_SCORE) return@mapNotNull null

            val quality = lyricsQualityScore(candidate, track.durationMs)
            val confidence = sourceConfidence(track, candidate)
            val final = (
                metadata * METADATA_WEIGHT +
                    quality * QUALITY_WEIGHT +
                    confidence * SOURCE_WEIGHT
                ).coerceIn(0.0, 1.0)

            LyricsProviderScore(
                candidate = candidate,
                metadataScore = metadata,
                qualityScore = quality,
                sourceConfidence = confidence,
                finalScore = final
            )
        }
    }

    internal fun metadataScore(
        track: TrackInfo,
        candidate: LyricsProviderCandidate
    ): Double? {
        if (!LrcLibClient.versionsCompatible(
                requestedTitle = track.title,
                candidateTitle = candidate.title,
                requestedAlbum = track.album,
                candidateAlbum = candidate.album
            )
        ) {
            return null
        }

        val titleScore = LrcLibClient.stringSimilarity(track.title, candidate.title)
        if (titleScore < MIN_TITLE_SCORE) return null

        // LRCLIB's duration field is documented and already used by its matcher.
        // PetitLyrics duration fields vary across clients/responses, so do not let
        // an ambiguous unit (seconds vs milliseconds) invalidate an otherwise
        // strong PetitLyrics match.
        val durationSec = if (track.durationMs > 0L) track.durationMs / 1000.0 else null
        val durationScore = if (
            !candidate.provider.equals("PetitLyrics", ignoreCase = true) &&
            durationSec != null &&
            candidate.durationSec != null
        ) {
            LrcLibClient.durationSimilarity(durationSec.toInt(), candidate.durationSec)
        } else {
            null
        }
        if (durationScore != null && durationScore < 0.0) return null

        val albumScore = if (
            track.album.isNotBlank() &&
            candidate.album.isNotBlank() &&
            candidate.album != "-"
        ) {
            val raw = LrcLibClient.stringSimilarity(track.album, candidate.album)
            if (raw < 0.20 && scriptsClearlyDifferent(track.album, candidate.album)) null else raw
        } else {
            null
        }

        val rawArtistScore = if (track.artist.isNotBlank() && candidate.artist.isNotBlank()) {
            LrcLibClient.artistSimilarity(
                left = track.artist,
                right = candidate.artist,
                allowContributorComponents = titleScore >= 0.95
            )
        } else {
            null
        }

        // Romanized player metadata and native Japanese provider metadata are not
        // directly comparable. Exact-title cross-script matches are allowed only
        // when there is independent corroboration. For PetitLyrics, a candidate
        // returned by a request that explicitly included key_artist is itself
        // useful evidence even when the returned artist is written in another
        // script. Title-only fallback candidates still need album/duration evidence.
        //
        // Multi-contributor media metadata is handled before this check: if one
        // comma/semicolon-delimited contributor exactly matches the provider artist,
        // artistSimilarity() supplies a strong score for near-exact titles.
        val crossScriptArtist = rawArtistScore != null &&
            rawArtistScore < MIN_ARTIST_SCORE &&
            titleScore >= 0.95 &&
            scriptsClearlyDifferent(track.artist, candidate.artist)
        val secondaryEvidence =
            candidate.artistQueryCorroborated ||
                (albumScore != null && albumScore >= CROSS_SCRIPT_ALBUM_EVIDENCE) ||
                (durationScore != null && durationScore >= 0.85)

        val artistScore = if (crossScriptArtist && secondaryEvidence) {
            null
        } else {
            rawArtistScore
        }

        if (artistScore != null && artistScore < MIN_ARTIST_SCORE) {
            val strongTitleAndDuration = titleScore >= 0.95 && (durationScore ?: 0.0) >= 0.85
            if (!strongTitleAndDuration) return null
        }

        var weighted = titleScore * 0.55
        var totalWeight = 0.55

        if (artistScore != null) {
            weighted += artistScore * 0.30
            totalWeight += 0.30
        }
        if (durationScore != null) {
            weighted += durationScore * 0.12
            totalWeight += 0.12
        }
        if (albumScore != null) {
            weighted += albumScore * 0.03
            totalWeight += 0.03
        }

        return (weighted / totalWeight).coerceIn(0.0, 1.0)
    }

    /**
     * Scores the payload itself, independently from song metadata.
     *
     * The main penalty currently detects a common LRCLIB failure mode for
     * Japanese songs: native Japanese lines interleaved with Latin-only
     * transliterations as if they were additional timed lyric lines. Occasional
     * genuine English phrases are intentionally tolerated; the penalty only
     * becomes meaningful when Latin-only lines are frequent and repeatedly
     * alternate with Japanese lines. Adjacent Japanese/Latin lines sharing nearly
     * the same timestamp are especially strong evidence of a transliteration copy.
     */
    internal fun lyricsQualityScore(
        candidate: LyricsProviderCandidate,
        trackDurationMs: Long
    ): Double {
        if (candidate.lines.isEmpty()) return 0.0

        val useful = candidate.lines
            .filter { it.text.isNotBlank() && it.text.trim() != "♪" }
        if (useful.isEmpty()) return 0.0

        var quality = 1.0

        val classes = useful.map { line ->
            val text = line.text.trim()
            val japanese = JAPANESE_SCRIPT.containsMatchIn(text)
            val latin = LATIN_SCRIPT.containsMatchIn(text)
            when {
                japanese -> ScriptClass.JAPANESE
                latin -> ScriptClass.LATIN_ONLY
                else -> ScriptClass.OTHER
            }
        }

        val japaneseCount = classes.count { it == ScriptClass.JAPANESE }
        val latinOnlyCount = classes.count { it == ScriptClass.LATIN_ONLY }
        val linguisticCount = japaneseCount + latinOnlyCount

        if (japaneseCount >= 3 && latinOnlyCount >= 2 && linguisticCount >= 6) {
            val latinRatio = latinOnlyCount.toDouble() / linguisticCount.toDouble()

            var alternatingPairs = 0
            var classifiedPairs = 0
            var nearDuplicateTimestampPairs = 0
            for (i in 1 until classes.size) {
                val previous = classes[i - 1]
                val current = classes[i]
                if (previous == ScriptClass.OTHER || current == ScriptClass.OTHER) continue
                classifiedPairs++
                if (previous != current) {
                    alternatingPairs++
                    if (abs(useful[i].timeMs - useful[i - 1].timeMs) <= 750L) {
                        nearDuplicateTimestampPairs++
                    }
                }
            }
            val alternatingRatio = if (classifiedPairs > 0) {
                alternatingPairs.toDouble() / classifiedPairs.toDouble()
            } else {
                0.0
            }
            val duplicateTimestampRatio = if (alternatingPairs > 0) {
                nearDuplicateTimestampPairs.toDouble() / alternatingPairs.toDouble()
            } else {
                0.0
            }

            if (
                latinRatio >= 0.18 &&
                (alternatingRatio >= 0.25 || nearDuplicateTimestampPairs >= 2)
            ) {
                val ratioStrength = ((latinRatio - 0.18) / 0.32).coerceIn(0.0, 1.0)
                val alternatingStrength = ((alternatingRatio - 0.25) / 0.55).coerceIn(0.0, 1.0)
                val duplicateStrength = duplicateTimestampRatio.coerceIn(0.0, 1.0)
                val contamination =
                    ratioStrength * 0.50 +
                        alternatingStrength * 0.30 +
                        duplicateStrength * 0.20
                quality -= 0.52 * contamination
            }
        }

        if (candidate.status == LyricsStatus.FOUND && trackDurationMs > 0L) {
            val timed = candidate.lines.filter { it.timeMs >= 0L }.sortedBy { it.timeMs }
            val first = timed.firstOrNull()?.timeMs
            val last = timed.lastOrNull()?.timeMs

            if (last != null && last > trackDurationMs + 20_000L) {
                quality -= 0.25
            }
            if (last != null && timed.size >= 10 && last < trackDurationMs * 0.50) {
                quality -= 0.15
            }
            if (first != null && first > 75_000L) {
                quality -= 0.08
            }
        }

        return quality.coerceIn(0.0, 1.0)
    }

    internal fun sourceConfidence(
        track: TrackInfo,
        candidate: LyricsProviderCandidate
    ): Double {
        val japaneseTrack = containsJapanese(track.title) ||
            containsJapanese(track.artist) ||
            containsJapanese(track.album)

        return if (japaneseTrack) {
            when {
                candidate.provider.equals("PetitLyrics", ignoreCase = true) &&
                    candidate.syncKind == LyricsProviderCandidate.SyncKind.WORD_SYNC -> 1.00
                candidate.provider.equals("PetitLyrics", ignoreCase = true) &&
                    candidate.syncKind == LyricsProviderCandidate.SyncKind.LINE_SYNC -> 0.96
                candidate.provider.equals("LRCLIB", ignoreCase = true) &&
                    candidate.status == LyricsStatus.FOUND -> 0.78
                candidate.status == LyricsStatus.PLAIN_ONLY -> 0.55
                else -> 0.75
            }
        } else {
            when {
                candidate.provider.equals("LRCLIB", ignoreCase = true) &&
                    candidate.status == LyricsStatus.FOUND -> 1.00
                candidate.provider.equals("PetitLyrics", ignoreCase = true) &&
                    candidate.syncKind == LyricsProviderCandidate.SyncKind.WORD_SYNC -> 0.92
                candidate.provider.equals("PetitLyrics", ignoreCase = true) &&
                    candidate.syncKind == LyricsProviderCandidate.SyncKind.LINE_SYNC -> 0.88
                candidate.status == LyricsStatus.PLAIN_ONLY -> 0.55
                else -> 0.80
            }
        }
    }

    private fun scriptsClearlyDifferent(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) return false
        val leftJapanese = containsJapanese(left)
        val rightJapanese = containsJapanese(right)
        val leftLatin = LATIN_SCRIPT.containsMatchIn(left)
        val rightLatin = LATIN_SCRIPT.containsMatchIn(right)

        return (leftJapanese && !leftLatin && rightLatin && !rightJapanese) ||
            (rightJapanese && !rightLatin && leftLatin && !leftJapanese)
    }

    private fun containsJapanese(value: String): Boolean = JAPANESE_SCRIPT.containsMatchIn(value)

    private enum class ScriptClass {
        JAPANESE,
        LATIN_ONLY,
        OTHER
    }
}
