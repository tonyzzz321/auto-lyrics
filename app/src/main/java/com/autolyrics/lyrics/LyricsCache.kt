package com.autolyrics.lyrics

import android.content.Context
import com.autolyrics.media.SpotifyTrackIdentity
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import com.google.gson.Gson
import java.io.File
import java.util.Locale

class LyricsCache(context: Context) {

    enum class Variant {
        STANDARD,
        KARAOKE
    }

    private val cacheDir = File(context.filesDir, "lyrics_cache")
    private val gson = Gson()

    init {
        cacheDir.mkdirs()
    }

    companion object {
        /**
         * Strip legacy RichSync anomaly-tag pills (fixN/oobN/dupN/missN/gapXs)
         * from stored source labels (they only ever rode the RichSync label).
         * They were diagnostic-only and are no longer produced, but entries
         * are permanent, so old labels are cleaned at load.
         */
        internal fun sanitizeSource(source: String, lines: List<LyricLine>): String {
            var label = source
            val marker = label.indexOf("RichSync")
            if (marker >= 0) label = label.substring(0, marker + "RichSync".length)
            // Provider jargon from older builds maps onto the shared
            // vocabulary every viewer shows: Karaoke / Synced / Not synced.
            label = when {
                label.endsWith("· RichSync") -> label.removeSuffix("RichSync") + "Karaoke"
                label.endsWith("· Line") -> label.removeSuffix("Line") + "Synced"
                label.endsWith("· Plain") -> label.removeSuffix("Plain") + "Not synced"
                else -> label
            }
            // Variants must describe the payload: providers ship enhanced
            // (word-timed) uploads under "Synced" labels and line-only
            // payloads under "Karaoke" ones, so the word content wins.
            val hasWords = lines.any { it.words.isNotEmpty() }
            label = when {
                label.endsWith("· Synced") && hasWords ->
                    label.removeSuffix("Synced") + "Karaoke"
                label.endsWith("· Karaoke") && !hasWords ->
                    label.removeSuffix("Karaoke") + "Synced"
                else -> label
            }
            return label
        }
    }

    data class CachedResult(
        val lines: List<CachedLine>,
        val status: String,
        val source: String,
        val timestamp: Long
    )

    data class CachedLine(
        val timeMs: Long,
        val text: String,
        val words: List<CachedWord>
    )

    data class CachedWord(
        val timeMs: Long,
        val text: String,
        val endTimeMs: Long? = null
    )

    fun get(
        track: TrackInfo,
        variant: Variant = Variant.STANDARD
    ): Triple<List<LyricLine>, LyricsStatus, String>? {
        val file = cacheFile(track, variant)
        if (!file.exists()) return null

        return try {
            val json = file.readText()
            val cached = gson.fromJson(json, CachedResult::class.java)
            val status = try {
                LyricsStatus.valueOf(cached.status)
            } catch (_: Exception) {
                return null
            }
            val lines = cached.lines.map { cl ->
                LyricLine(
                    timeMs = cl.timeMs,
                    text = cl.text,
                    words = cl.words.map { cw ->
                        LyricWord(
                            timeMs = cw.timeMs,
                            text = cw.text,
                            endTimeMs = cw.endTimeMs
                        )
                    }
                )
            }
            // Legacy entries stored Enhanced-LRC word tags as literal text;
            // re-tokenize them so display and labels self-heal on read.
            val healed = lines.map(LrcParser::repairWordTags)
            Triple(healed, status, sanitizeSource(cached.source, healed))
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun put(
        track: TrackInfo,
        lines: List<LyricLine>,
        status: LyricsStatus,
        source: String,
        variant: Variant = Variant.STANDARD
    ) {
        try {
            val cached = CachedResult(
                lines = lines.map { line ->
                    CachedLine(
                        timeMs = line.timeMs,
                        text = line.text,
                        words = line.words.map { w ->
                            CachedWord(
                                timeMs = w.timeMs,
                                text = w.text,
                                endTimeMs = w.endTimeMs
                            )
                        }
                    )
                },
                status = status.name,
                source = source,
                timestamp = System.currentTimeMillis()
            )
            val file = cacheFile(track, variant)
            file.writeText(gson.toJson(cached))
        } catch (_: Exception) {
            // cache write failures are non-fatal
        }
    }

    private fun cacheFile(track: TrackInfo, variant: Variant): File {
        val durationSec = if (track.durationMs > 0) {
            ((track.durationMs + 500L) / 1000L).toString()
        } else {
            "unknown"
        }

        val keyParts = mutableListOf(
            "v16",
            variant.name.lowercase(Locale.ROOT),
            normalizeCacheKeyPart(track.title),
            normalizeCacheKeyPart(track.artist),
            normalizeCacheKeyPart(track.album),
            durationSec
        )
        SpotifyTrackIdentity.trackId(track)?.let { spotifyTrackId ->
            keyParts += "spotify:$spotifyTrackId"
        }
        val key = keyParts.joinToString("|")

        val hash = key.hashCode().toUInt().toString(16)
        return File(cacheDir, "$hash.json")
    }

}
