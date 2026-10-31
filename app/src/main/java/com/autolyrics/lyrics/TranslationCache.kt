package com.autolyrics.lyrics

import android.content.Context
import com.autolyrics.media.SpotifyTrackIdentity
import com.autolyrics.model.LyricLine
import com.autolyrics.model.TrackInfo
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Persistent per-track translation cache. Entries are keyed by track identity,
 * target language, engine, model, and a hash of the source lyric text, so a
 * lyric refresh or any engine-side change naturally invalidates the entry.
 * Entries never expire on their own.
 */
class TranslationCache internal constructor(
    private val cacheDir: File,
    private val gson: Gson = Gson()
) {

    constructor(context: Context) : this(File(context.filesDir, TRANSLATION_CACHE_DIR))

    init {
        cacheDir.mkdirs()
    }

    data class CachedTranslation(
        val lines: List<String>,
        val detectedLanguage: String,
        val targetLanguage: String,
        val engine: String,
        val model: String,
        val sourceTextHash: String,
        val timestamp: Long
    )

    fun get(
        track: TrackInfo,
        lines: List<LyricLine>,
        targetLanguage: String,
        engine: String,
        model: String
    ): CachedTranslation? {
        val sourceTextHash = sourceTextHash(lines)
        val file = cacheFile(track, targetLanguage, engine, model, sourceTextHash)
        if (!file.exists()) return null

        return try {
            val cached = gson.fromJson(file.readText(), CachedTranslation::class.java)
            cached?.takeIf {
                it.sourceTextHash == sourceTextHash && it.lines.size == lines.size
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun put(
        track: TrackInfo,
        lines: List<LyricLine>,
        result: TranslationResult,
        engine: String,
        model: String
    ) {
        try {
            val sourceTextHash = sourceTextHash(lines)
            val cached = CachedTranslation(
                lines = result.translatedLines,
                detectedLanguage = result.detectedLanguage,
                targetLanguage = result.targetLanguage,
                engine = engine,
                model = model.lowercase(Locale.US).trim(),
                sourceTextHash = sourceTextHash,
                timestamp = System.currentTimeMillis()
            )
            cacheFile(track, result.targetLanguage, engine, model, sourceTextHash)
                .writeText(gson.toJson(cached))
        } catch (_: Exception) {
            // cache write failures are non-fatal
        }
    }

    internal fun sourceTextHash(lines: List<LyricLine>): String =
        sha256Hex(lines.joinToString("\n") { it.text })

    private fun cacheFile(
        track: TrackInfo,
        targetLanguage: String,
        engine: String,
        model: String,
        sourceTextHash: String
    ): File {
        val durationSec = if (track.durationMs > 0) {
            ((track.durationMs + 500L) / 1000L).toString()
        } else {
            "unknown"
        }

        val keyParts = mutableListOf(
            "tc1",
            normalizeCacheKeyPart(track.title),
            normalizeCacheKeyPart(track.artist),
            normalizeCacheKeyPart(track.album),
            durationSec,
            normalizeCacheKeyPart(targetLanguage),
            engine.lowercase(Locale.US),
            normalizeCacheKeyPart(model),
            sourceTextHash
        )
        SpotifyTrackIdentity.trackId(track)?.let { spotifyTrackId ->
            keyParts += "spotify:$spotifyTrackId"
        }
        val key = keyParts.joinToString("|")

        return File(cacheDir, "${sha256Hex(key).take(32)}.json")
    }

    companion object {
        const val TRANSLATION_CACHE_DIR = "translation_cache"
    }
}

internal fun normalizeCacheKeyPart(value: String): String {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .trim()
}

internal fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
