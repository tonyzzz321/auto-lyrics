package com.autolyrics.lyrics

import android.util.Log
import com.autolyrics.BuildConfig
import com.autolyrics.model.LyricLine
import com.autolyrics.model.TrackInfo
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Karaoke-only client for the public SyncLRC API.
 *
 * The current public API returns separate `karaoke`, `synced`, and `plain`
 * fields. Older deployments returned a type-specific `lyrics` + `type` shape.
 * Both are accepted for compatibility, but only a genuine karaoke payload is
 * ever promoted to an Auto Lyrics provider candidate.
 */
object SyncLrcClient {

    private const val TAG = "SyncLRC"
    private const val BASE_URL = "https://api.synclrc.dev/lyrics"

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    data class SyncLrcResult(
        val lines: List<LyricLine>,
        val matchedTitle: String,
        val matchedArtist: String,
        val matchedAlbum: String,
        val matchedDurationSec: Double?,
        val artistQueryCorroborated: Boolean = false
    )

    data class ApiResponse(
        @SerializedName("karaoke") val karaoke: String? = null,
        @SerializedName("synced") val synced: String? = null,
        @SerializedName("plain") val plain: String? = null,
        @SerializedName("lyrics") val lyrics: String? = null,
        @SerializedName("type") val type: String? = null,
        @SerializedName("id") val id: String? = null,
        @SerializedName("track") val track: String? = null,
        @SerializedName("artist") val artist: String? = null,
        @SerializedName("album") val album: String? = null,
        @SerializedName("duration") val duration: Double? = null,
        @SerializedName("instrumental") val instrumental: Boolean = false
    )

    fun getKaraokeLyrics(track: TrackInfo): SyncLrcResult? {
        if (track.title.isBlank() || track.artist.isBlank()) return null

        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("track", track.title)
            .addQueryParameter("artist", track.artist)
            .addQueryParameter("type", "karaoke")
            .apply {
                if (track.album.isNotBlank()) {
                    addQueryParameter("album", track.album)
                }
                if (track.durationMs > 0L) {
                    addQueryParameter(
                        "duration",
                        (track.durationMs / 1000.0).roundToInt().toString()
                    )
                }
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "AutoLyrics/${BuildConfig.VERSION_NAME} (Android; SyncLRC)")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                debugLog("HTTP ${response.code}; karaoke request")
                if (!response.isSuccessful) return@use null
                parseApiResponse(response.body?.string().orEmpty(), track)
            }
        } catch (e: Exception) {
            debugLog("request failed: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
            null
        }
    }

    /**
     * Raw lookup for the manual search pick-list. Unlike [getKaraokeLyrics]
     * nothing is rejected here: the caller surfaces synced/plain payloads too
     * so the user can see everything the aggregator knows for the terms.
     */
    fun manualFetch(
        title: String,
        artist: String,
        diagnostics: MutableList<String>? = null
    ): ApiResponse? {
        val track = title.trim()
        val performer = artist.trim()
        if (track.isBlank() && performer.isBlank()) return null

        val urlBuilder = BASE_URL.toHttpUrl().newBuilder()
        if (track.isNotBlank()) urlBuilder.addQueryParameter("track", track)
        if (performer.isNotBlank()) urlBuilder.addQueryParameter("artist", performer)
        urlBuilder.addQueryParameter("type", "karaoke")

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "AutoLyrics/${BuildConfig.VERSION_NAME} (Android; SyncLRC)")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    diagnostics?.plusAssign("synclrc: HTTP ${response.code}")
                    return@use null
                }
                diagnostics?.plusAssign("synclrc: ok")
                gson.fromJson(response.body?.string(), ApiResponse::class.java)
            }
        } catch (e: Exception) {
            diagnostics?.plusAssign("synclrc: ${e.javaClass.simpleName}")
            null
        }
    }

    internal fun parseApiResponse(
        json: String,
        requestedTrack: TrackInfo? = null
    ): SyncLrcResult? {
        if (json.isBlank()) return null

        val response = try {
            gson.fromJson(json, ApiResponse::class.java)
        } catch (_: Exception) {
            return null
        } ?: return null

        if (response.instrumental) {
            debugLog("response rejected: instrumental")
            return null
        }

        val karaokeLyrics = response.karaoke
            ?.takeIf { it.isNotBlank() }
            ?: response.lyrics
                ?.takeIf {
                    it.isNotBlank() && response.type.equals("karaoke", ignoreCase = true)
                }

        // A type=karaoke request can still contain only synced/plain data. LRCLIB
        // already covers those formats directly, so SyncLRC contributes only when
        // a genuine Enhanced-LRC karaoke payload is present.
        if (karaokeLyrics == null) {
            debugLog(
                "response has no karaoke payload; legacyType=${response.type.orEmpty()} " +
                    "synced=${!response.synced.isNullOrBlank()} plain=${!response.plain.isNullOrBlank()}"
            )
            return null
        }

        val lines = LrcParser.parseKaraoke(karaokeLyrics)
        if (lines.none { it.words.isNotEmpty() }) {
            debugLog("karaoke payload rejected: no timed tokens")
            return null
        }

        val fallback = requestedTrack
        val title = response.track.orEmpty().ifBlank { fallback?.title.orEmpty() }
        val artist = response.artist.orEmpty().ifBlank { fallback?.artist.orEmpty() }
        val album = response.album.orEmpty().ifBlank { fallback?.album.orEmpty() }

        debugLog(
            "karaoke accepted lines=${lines.size} tokens=${lines.sumOf { it.words.size }} " +
                "track='${title}' artist='${artist}'"
        )

        return SyncLrcResult(
            lines = lines,
            matchedTitle = title,
            matchedArtist = artist,
            matchedAlbum = album,
            matchedDurationSec = response.duration,
            artistQueryCorroborated = requestedTrack?.artist?.isNotBlank() == true
        )
    }

    private fun debugLog(message: String) {
        if (!BuildConfig.DEBUG) return
        try {
            Log.d(TAG, message)
        } catch (_: RuntimeException) {
            // android.util.Log is not mocked in local JVM unit tests.
        }
    }
}
