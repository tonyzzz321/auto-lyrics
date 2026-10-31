package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsStatus
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
            PLAIN -> "Plain"
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
     * Providers whose list response omits the lyric body (Musixmatch) supply
     * this instead; [com.autolyrics.media.MediaTracker.applyManualLyrics]
     * runs it off-main when the row is picked.
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
            if (syncedLines.isNotEmpty() && syncedLines.any { it.text != "♪" }) {
                val karaoke = syncedLines.any { it.words.isNotEmpty() }
                hits += ManualSearchHit(
                    provider = "LRCLIB",
                    title = response.trackName.orEmpty(),
                    artist = response.artistName.orEmpty(),
                    durationSec = response.duration?.toInt(),
                    kind = if (karaoke) ManualHitKind.KARAOKE else ManualHitKind.SYNCED,
                    lines = syncedLines,
                    status = LyricsStatus.FOUND,
                    source = if (karaoke) "LRCLIB · Karaoke · Manual" else "LRCLIB · Synced · Manual"
                )
                continue
            }

            val plainLines = response.plainLyrics
                ?.lines()
                ?.filter { it.isNotBlank() }
                ?.map { text -> LyricLine(0L, LrcParser.cleanSpacing(text)) }
                .orEmpty()
            if (plainLines.isNotEmpty() && plainLines.any { it.text != "♪" }) {
                hits += ManualSearchHit(
                    provider = "LRCLIB",
                    title = response.trackName.orEmpty(),
                    artist = response.artistName.orEmpty(),
                    durationSec = response.duration?.toInt(),
                    kind = ManualHitKind.PLAIN,
                    lines = plainLines,
                    status = LyricsStatus.PLAIN_ONLY,
                    source = "LRCLIB · Plain · Manual"
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
            karaokeLines.any { it.text != "♪" }
        if (isKaraoke) {
            return ManualSearchHit(
                provider = "SyncLRC",
                title = response.track.orEmpty(),
                artist = response.artist.orEmpty(),
                durationSec = response.duration?.toInt(),
                kind = ManualHitKind.KARAOKE,
                lines = karaokeLines,
                status = LyricsStatus.FOUND,
                source = "SyncLRC · Karaoke · Manual"
            )
        }

        val syncedLines = response.synced
            ?.takeIf { it.isNotBlank() }
            ?.let { LrcParser.parseKaraoke(it) }
            ?.filter { it.text.isNotBlank() }
            .orEmpty()
        if (syncedLines.isNotEmpty() && syncedLines.any { it.text != "♪" }) {
            return ManualSearchHit(
                provider = "SyncLRC",
                title = response.track.orEmpty(),
                artist = response.artist.orEmpty(),
                durationSec = response.duration?.toInt(),
                kind = ManualHitKind.SYNCED,
                lines = syncedLines,
                status = LyricsStatus.FOUND,
                source = "SyncLRC · Synced · Manual"
            )
        }

        val plainLines = response.plain
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?.map { text -> LyricLine(0L, LrcParser.cleanSpacing(text)) }
            .orEmpty()
        if (plainLines.isNotEmpty() && plainLines.any { it.text != "♪" }) {
            return ManualSearchHit(
                provider = "SyncLRC",
                title = response.track.orEmpty(),
                artist = response.artist.orEmpty(),
                durationSec = response.duration?.toInt(),
                kind = ManualHitKind.PLAIN,
                lines = plainLines,
                status = LyricsStatus.PLAIN_ONLY,
                source = "SyncLRC · Plain · Manual"
            )
        }
        return null
    }

    fun rank(hits: List<ManualSearchHit>, ourDurationSec: Int): List<ManualSearchHit> =
        hits.sortedWith(
            compareBy<ManualSearchHit> { it.kind.ordinal }
                .thenBy { hit ->
                    if (ourDurationSec > 0 && hit.durationSec != null) {
                        abs(hit.durationSec!! - ourDurationSec)
                    } else {
                        Int.MAX_VALUE
                    }
                }
                .thenBy { it.title.lowercase(Locale.US) }
        )

    fun formatDuration(durationSec: Int?): String {
        if (durationSec == null || durationSec <= 0) return "--:--"
        return "%d:%02d".format(durationSec / 60, durationSec % 60)
    }
}
