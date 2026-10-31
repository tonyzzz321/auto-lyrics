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

    data class CachedResult(
        val lines: List<CachedLine>,
        val status: String,
        val source: String,
        val timestamp: Long,
        val refreshAfterMs: Long = 0L
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
            Triple(lines, status, cached.source)
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
        refreshAfterMs: Long = DEFAULT_REFRESH_AFTER_MS,
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
                timestamp = System.currentTimeMillis(),
                refreshAfterMs = refreshAfterMs.coerceAtLeast(1L)
            )
            val file = cacheFile(track, variant)
            file.writeText(gson.toJson(cached))
        } catch (_: Exception) {
            // cache write failures are non-fatal
        }
    }

    fun getAge(
        track: TrackInfo,
        variant: Variant = Variant.STANDARD
    ): Long {
        val file = cacheFile(track, variant)
        if (!file.exists()) return Long.MAX_VALUE
        return try {
            val json = file.readText()
            val cached = gson.fromJson(json, CachedResult::class.java)
            System.currentTimeMillis() - cached.timestamp
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    fun getRefreshAfterMs(
        track: TrackInfo,
        variant: Variant = Variant.STANDARD
    ): Long {
        val file = cacheFile(track, variant)
        if (!file.exists()) return 0L
        return try {
            val json = file.readText()
            val cached = gson.fromJson(json, CachedResult::class.java)
            cached.refreshAfterMs.takeIf { it > 0L } ?: DEFAULT_REFRESH_AFTER_MS
        } catch (_: Exception) {
            0L
        }
    }

    private fun cacheFile(track: TrackInfo, variant: Variant): File {
        val durationSec = if (track.durationMs > 0) {
            ((track.durationMs + 500L) / 1000L).toString()
        } else {
            "unknown"
        }

        val keyParts = mutableListOf(
            "v14",
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

    companion object {
        const val DEFAULT_REFRESH_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}
