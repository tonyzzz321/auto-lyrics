package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsStatus
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** How precisely a manual-search result is time-aligned with the recording. */
enum class ManualHitKind {
    KARAOKE,
    SYNCED,
    PLAIN;

    val label: String
        get() = when (this) {
            KARAOKE -> "Karaoke"
            SYNCED -> "Synced"
            PLAIN -> "Not synced"
        }
}

/**
 * One selectable row in the manual lyrics search dialog. Built from raw
 * provider responses and ranked against the playing track's duration.
 */
data class ManualSearchHit(
    val provider: String,
    val title: String,
    val artist: String,
    val durationSec: Int?,
    val kind: ManualHitKind,
    val lines: List<LyricLine>,
    val status: LyricsStatus,
    val source: String,
    /**
     * Internal transport between a provider client's list response and
     * [com.autolyrics.media.MediaTracker]'s search-time eager-body phase:
     * lazy providers hand out bodyless rows plus this lambda, the leg
     * resolves it BEFORE the row is listed, and applied rows never carry
     * it (fetch is null on everything the dialog can see).
     */
    val fetch: (() -> MusixmatchClient.ManualFetched?)? = null
) {
    val label: String
        get() = "%s · %s · %s — %s — %s".format(
            kind.label,
            provider,
            ManualSearch.formatDuration(durationSec),
            title.ifBlank { "?" },
            artist.ifBlank { "?" }
        )
}

object ManualSearch {

    /** Keeps first-seen order across batches; dedupes by id, else title+artist+duration. */
    fun mergeResponses(batches: List<List<LrcLibClient.LrcLibResponse>>): List<LrcLibClient.LrcLibResponse> {
        val merged = LinkedHashMap<String, LrcLibClient.LrcLibResponse>()
        for (batch in batches) {
            for (response in batch) {
                val key = response.id?.let { "id:$it" }
                    ?: "ta:${response.trackName.orEmpty()}|${response.artistName.orEmpty()}|${response.duration}"
                merged.putIfAbsent(key, response)
            }
        }
        return merged.values.toList()
    }

    fun toHits(responses: List<LrcLibClient.LrcLibResponse>): List<ManualSearchHit> {
        val hits = mutableListOf<ManualSearchHit>()
        val seenIds = mutableSetOf<Int>()

        for (response in responses) {
            val id = response.id
            if (id != null && !seenIds.add(id)) continue

            // parseKaraoke keeps word-level timing when an upload carries it
            // and degrades to plain line timing otherwise.
            val syncedLines = response.syncedLyrics
                ?.let { LrcParser.parseKaraoke(it) }
                ?.filter { it.text.isNotBlank() }
                .orEmpty()
            if (LyricsProviderResolver.hasDisplayableLines(syncedLines)) {
                val karaoke = syncedLines.any { it.words.isNotEmpty() }
                hits += ManualSearchHit(
                    provider = "LRCLIB",
                    title = response.trackName.orEmpty(),
                    artist = response.artistName.orEmpty(),
                    durationSec = response.duration?.toInt(),
                    kind = if (karaoke) ManualHitKind.KARAOKE else ManualHitKind.SYNCED,
                    lines = syncedLines,
                    status = LyricsStatus.FOUND,
                    source = if (karaoke) "LRCLIB · Karaoke" else "LRCLIB · Synced"
                )
                continue
            }

            val plainLines = response.plainLyrics
                ?.lines()
                ?.filter { it.isNotBlank() }
                ?.map { text -> LyricLine(0L, LrcParser.cleanSpacing(text)) }
                .orEmpty()
            if (LyricsProviderResolver.hasDisplayableLines(plainLines)) {
                hits += ManualSearchHit(
                    provider = "LRCLIB",
                    title = response.trackName.orEmpty(),
                    artist = response.artistName.orEmpty(),
                    durationSec = response.duration?.toInt(),
                    kind = ManualHitKind.PLAIN,
                    lines = plainLines,
                    status = LyricsStatus.PLAIN_ONLY,
                    source = "LRCLIB · Not synced"
                )
            }
        }

        return hits
    }

    /** Single candidate (or null) from the SyncLRC aggregator response. */
    fun toSyncLrcHit(response: SyncLrcClient.ApiResponse?): ManualSearchHit? {
        if (response == null || response.instrumental) return null

        val karaokeLines = response.karaoke
            ?.takeIf { it.isNotBlank() }
            ?.let { LrcParser.parseKaraoke(it) }
            ?.filter { it.text.isNotBlank() }
            .orEmpty()
        val isKaraoke = karaokeLines.any { it.words.isNotEmpty() } &&
            LyricsProviderResolver.hasDisplayableLines(karaokeLines)
        if (isKaraoke) {
            return ManualSearchHit(
                provider = "SyncLRC",
                title = response.track.orEmpty(),
                artist = response.artist.orEmpty(),
                durationSec = response.duration?.toInt(),
                kind = ManualHitKind.KARAOKE,
                lines = karaokeLines,
                status = LyricsStatus.FOUND,
                source = "SyncLRC · Karaoke"
            )
        }

        val syncedLines = response.synced
            ?.takeIf { it.isNotBlank() }
            ?.let { LrcParser.parseKaraoke(it) }
            ?.filter { it.text.isNotBlank() }
            .orEmpty()
        if (LyricsProviderResolver.hasDisplayableLines(syncedLines)) {
            return ManualSearchHit(
                provider = "SyncLRC",
                title = response.track.orEmpty(),
                artist = response.artist.orEmpty(),
                durationSec = response.duration?.toInt(),
                kind = ManualHitKind.SYNCED,
                lines = syncedLines,
                status = LyricsStatus.FOUND,
                source = "SyncLRC · Synced"
            )
        }

        val plainLines = response.plain
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?.map { text -> LyricLine(0L, LrcParser.cleanSpacing(text)) }
            .orEmpty()
        if (LyricsProviderResolver.hasDisplayableLines(plainLines)) {
            return ManualSearchHit(
                provider = "SyncLRC",
                title = response.track.orEmpty(),
                artist = response.artist.orEmpty(),
                durationSec = response.duration?.toInt(),
                kind = ManualHitKind.PLAIN,
                lines = plainLines,
                status = LyricsStatus.PLAIN_ONLY,
                source = "SyncLRC · Not synced"
            )
        }
        return null
    }

    /**
     * Dialog-side visibility filter: with the "Include unsynced" toggle off,
     * rows the app would render as not-synced (no timing at all) leave the
     * pick-list. Karaoke and line-synced rows always stay.
     */
    internal fun applyUnsyncedFilter(
        hits: List<ManualSearchHit>,
        includeUnsynced: Boolean
    ): List<ManualSearchHit> =
        if (includeUnsynced) hits else hits.filter { it.kind != ManualHitKind.PLAIN }

    /**
     * Keeps results whose title plausibly matches the searched title, so an
     * artist-wide fallback query cannot bury the actual song under other
     * recordings by the same artist. Deliberately lenient: dropping a real
     * match would be worse than keeping a near miss.
     */
    /**
     * Relevance gate for built rows. SyncLRC rows are exempt: every one is
     * an iTunes-catalog resolution whose whole purpose is to answer an alias
     * search (a kana query over a romanized catalog name), and iTunes
     * already corroborated the artist — the literal title filter would drop
     * exactly the rows the ladder exists to find.
     */
    internal fun passesRelevance(hit: ManualSearchHit, queryTitle: String): Boolean =
        hit.provider == "SyncLRC" || titleIsRelevant(hit.title, queryTitle)

    internal fun titleIsRelevant(resultTitle: String, queryTitle: String): Boolean {
        val query = normalizeSearchTitle(queryTitle)
        if (query.isBlank()) return true
        val candidate = normalizeSearchTitle(resultTitle)
        if (candidate.isBlank()) return false

        if (candidate.contains(query) || query.contains(candidate)) return true

        val queryTokens = query.split(' ').filter { it.isNotBlank() }.toSet()
        val candidateTokens = candidate.split(' ').filter { it.isNotBlank() }.toSet()
        val sharedTokens = queryTokens.count { candidateTokens.contains(it) }
        if (queryTokens.isNotEmpty() && sharedTokens * 2 >= queryTokens.size) return true

        // Script-based fallback: CJK queries tokenize as whole phrases, so
        // compare character coverage instead. Latin stays token-only because
        // letter coverage is meaningless across space-separated words.
        if (query.none { it in 'a'..'z' }) {
            val queryChars = query.filter { !it.isWhitespace() }.toSet()
            if (queryChars.isNotEmpty() &&
                queryChars.count { candidate.contains(it) } * 2 >= queryChars.size
            ) {
                return true
            }
        }

        return false
    }

    private val TITLE_PARENTHESES = Regex("[\\[(][^\\])]*[\\])]")

    private val TITLE_NOISE_TOKENS = setOf(
        "official", "lyrics", "lyric", "video", "audio", "visualiser", "visualizer",
        "mv", "hd", "hq", "4k", "remaster", "remastered"
    )

    internal fun normalizeSearchTitle(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
            .let { TITLE_PARENTHESES.replace(it, " ") }
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() && it !in TITLE_NOISE_TOKENS }
            .joinToString(" ")

    fun rank(
        hits: List<ManualSearchHit>,
        ourDurationSec: Int,
        ourArtist: String = ""
    ): List<ManualSearchHit> =
        hits.sortedWith(
            compareBy<ManualSearchHit> { it.kind.ordinal }
                // Title-only provider queries (LRCLIB) surface same-title
                // covers; the artist match keeps the real artist first
                // without suppressing the alternatives.
                .thenBy { artistPenalty(it.artist, ourArtist) }
                .thenBy { hit ->
                    if (ourDurationSec > 0 && hit.durationSec != null) {
                        abs(hit.durationSec!! - ourDurationSec)
                    } else {
                        Int.MAX_VALUE
                    }
                }
                .thenBy { it.title.lowercase(Locale.US) }
        )

    /** 0 when the artists plausibly match, 1 otherwise. */
    internal fun artistPenalty(hitArtist: String, ourArtist: String): Int {
        if (ourArtist.isBlank() || hitArtist.isBlank()) return 0
        val ours = normalizeArtist(ourArtist)
        val theirs = normalizeArtist(hitArtist)
        if (ours.isEmpty() || theirs.isEmpty()) return 0
        return if (ours == theirs || ours.contains(theirs) || theirs.contains(ours)) 0 else 1
    }

    private fun normalizeArtist(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
            .replace(Regex("\\b(feat|ft|featuring|with)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun formatDuration(durationSec: Int?): String {
        if (durationSec == null || durationSec <= 0) return "--:--"
        return "%d:%02d".format(durationSec / 60, durationSec % 60)
    }

    /**
     * Search status line, per the status contract: the outcome and nothing
     * else. `Error` only when NOTHING listed AND at least one leg died
     * transport-wise; clean all-empty is `0 results`. Every diagnostic —
     * counts, catalog misses, bodies kept, per-leg causes — lives in the
     * tap-to-open diagnostics view, never on this line.
     */
    internal fun statusLine(visible: Int, durationSec: Int?, deaths: List<String>): String {
        val outcome = when {
            visible > 0 -> "$visible results"
            deaths.isNotEmpty() -> "Error"
            else -> "0 results"
        }
        return "$outcome · track ${formatDuration(durationSec)}"
    }
}
