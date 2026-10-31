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
    private const val SEARCH_URL = "https://api.synclrc.dev/search"

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

    /** Nested `lyrics` blob of the search endpoint; flat fields are also
     *  accepted in case a result already carries them at the top level. */
    data class SearchLyrics(
        @SerializedName("karaoke") val karaoke: String? = null,
        @SerializedName("synced") val synced: String? = null,
        @SerializedName("plain") val plain: String? = null
    )

    data class SearchResult(
        @SerializedName("id") val id: String? = null,
        @SerializedName("track") val track: String? = null,
        @SerializedName("artist") val artist: String? = null,
        @SerializedName("album") val album: String? = null,
        @SerializedName("duration") val duration: Double? = null,
        @SerializedName("instrumental") val instrumental: Boolean = false,
        @SerializedName("lyrics") val lyrics: SearchLyrics? = null,
        @SerializedName("karaoke") val karaoke: String? = null,
        @SerializedName("synced") val synced: String? = null,
        @SerializedName("plain") val plain: String? = null
    )

    data class SearchResponse(
        // Nullable: Gson bypasses constructors, so a missing key yields a
        // real null despite the Kotlin default.
        @SerializedName("results") val results: List<SearchResult>? = null
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
                if (response.isSuccessful) {
                    return@use parseApiResponse(response.body?.string().orEmpty(), track)
                }
                null
            } ?: searchFallback(track)
        } catch (e: Exception) {
            debugLog("request failed: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
            searchFallback(track)
        }
    }

    /**
     * Raw lookup for the manual search pick-list. Unlike [getKaraokeLyrics]
     * nothing is rejected here: the caller surfaces synced/plain payloads too
     * so the user can see everything the aggregator knows for the terms.
     */
    /**
     * DB-backed candidate search for the manual pick-list. The website uses
     * this endpoint; unlike `/lyrics` it does not live-aggregate upstream
     * sources, so it stays available while `/lyrics` reports 503.
     */
    fun manualSearch(
        title: String,
        artist: String,
        diagnostics: MutableList<String>? = null
    ): List<ApiResponse> {
        // The free-text q AND-joins every word (like LRCLIB), so "title
        // artist" finds nothing; search by title and rank candidates by
        // artist afterwards.
        val query = title.trim().ifBlank { artist.trim() }
        if (query.isBlank()) return emptyList()

        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "AutoLyrics/${BuildConfig.VERSION_NAME} (Android; SyncLRC)")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    diagnostics?.plusAssign("synclrc: HTTP ${response.code}")
                    return@use emptyList()
                }
                diagnostics?.plusAssign("synclrc: ok")
                parseSearchResponse(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            diagnostics?.plusAssign("synclrc: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    internal fun parseSearchResponse(json: String): List<ApiResponse> {
        if (json.isBlank()) return emptyList()
        val parsed = try {
            gson.fromJson(json, SearchResponse::class.java)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return parsed.results.orEmpty().mapNotNull { result ->
            val lyrics = result.lyrics
            val response = ApiResponse(
                id = result.id,
                track = result.track,
                artist = result.artist,
                album = result.album,
                duration = result.duration,
                instrumental = result.instrumental,
                karaoke = lyrics?.karaoke ?: result.karaoke,
                synced = lyrics?.synced ?: result.synced,
                plain = lyrics?.plain ?: result.plain
            )
            response.takeIf {
                listOf(it.karaoke, it.synced, it.plain)
                    .any { field -> !field.isNullOrBlank() }
            }
        }
    }

    /**
     * Best search candidate for [track]: exact title match first, then the
     * smallest duration delta; instrumental and empty rows never qualify.
     */
    internal fun pickBestCandidate(
        candidates: List<ApiResponse>,
        track: TrackInfo
    ): ApiResponse? {
        val wanted = candidates.filter { candidate ->
            !candidate.instrumental &&
                listOf(candidate.karaoke, candidate.synced, candidate.plain)
                    .any { field -> !field.isNullOrBlank() }
        }
        return wanted.minWithOrNull(
            compareBy<ApiResponse> { candidate ->
                -scoreCandidate(candidate, track) +
                    durationDelta(candidate, track) / 1000.0
            }
        )
    }

    private fun scoreCandidate(candidate: ApiResponse, track: TrackInfo): Int {
        var score = 0
        if (candidate.track.equals(track.title, ignoreCase = true)) score += 4
        if (!candidate.artist.isNullOrBlank() && track.artist.isNotBlank() &&
            candidate.artist.contains(track.artist, ignoreCase = true)
        ) score += 2
        return score
    }

    private fun durationDelta(candidate: ApiResponse, track: TrackInfo): Double {
        val durationSec = candidate.duration ?: return 0.0
        if (track.durationMs <= 0L) return 0.0
        return kotlin.math.abs(durationSec - track.durationMs / 1000.0)
    }

    /**
     * The `/lyrics` endpoint live-aggregates upstream sources and can report
     * 503 SOURCES_UNAVAILABLE while the DB-backed endpoints stay healthy;
     * the search endpoint then covers the same catalogue.
     */
    private fun searchFallback(track: TrackInfo): SyncLrcResult? {
        val candidates = manualSearch(track.title, track.artist)
        val best = pickBestCandidate(candidates, track) ?: return null
        debugLog("search fallback selected '${best.track.orEmpty()}'")
        return parseResponse(best, track)
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

        return parseResponse(response, requestedTrack)
    }

    internal fun parseResponse(
        response: ApiResponse,
        requestedTrack: TrackInfo? = null
    ): SyncLrcResult? {

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
