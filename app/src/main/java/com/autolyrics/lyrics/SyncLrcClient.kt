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
    private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"

    /** iTunes gives up to 6 songs; fetching every one would burn the manual
     *  leg budget. Three exact-match fetches cover the realistic candidates. */
    private const val CATALOG_LOOKUP_LIMIT = 3

    /** Wall-clock budget for the fallback chain inside the 10 s manual leg. */
    private const val CATALOG_BUDGET_SEC = 6L

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
        val artistQueryCorroborated: Boolean = false,
        val syncKind: LyricsProviderCandidate.SyncKind = LyricsProviderCandidate.SyncKind.WORD_SYNC
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

    /** iTunes Search API entries; the catalog's alias matching resolves
     *  script/romanized titles to the exact names catalog services index. */
    data class ItunesTrack(
        @SerializedName("trackName") val trackName: String? = null,
        @SerializedName("artistName") val artistName: String? = null
    )

    data class ItunesResponse(
        @SerializedName("results") val results: List<ItunesTrack>? = null
    )

    fun getKaraokeLyrics(track: TrackInfo, transport: MutableList<String>? = null): SyncLrcResult? {
        if (track.title.isBlank() || track.artist.isBlank()) return null

        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("track", track.title)
            .addQueryParameter("artist", track.artist)
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
                debugLog("HTTP ${response.code}; auto request")
                if (response.isSuccessful) {
                    return@use parseApiResponse(response.body?.string().orEmpty(), track)
                }
                // 404 is the catalog's clean miss; 5xx (aggregation deaths)
                // and everything else are transient leg failures.
                if (response.code != 404) transport?.add("HTTP ${response.code}")
                null
            } ?: itunesFallback(track, transport)
        } catch (e: Exception) {
            transport?.add("network: ${e.javaClass.simpleName}")
            debugLog("request failed: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
            itunesFallback(track, transport)
        }
    }

    fun manualSearch(
        title: String,
        artist: String,
        diagnostics: MutableList<String>? = null
    ): List<ApiResponse> {
        // Same engine as the auto iTunes fallback. The literal DB `/search`
        // (kana/alias-blind) and the exact playing-name retry (auto already
        // caches exactly that payload) were dropped, leaving the alias-aware
        // catalog lookup as the single manual ladder.
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return emptyList()
        val rows = if (artist.isBlank()) {
            listOfNotNull(lyricsFor(trimmedTitle, ""))
        } else {
            itunesRows(trimmedTitle, artist)
        }
        diagnostics?.plusAssign(
            if (rows.isEmpty()) "synclrc: no catalog match"
            else "synclrc: catalog match x${rows.size}"
        )
        return rows
    }

    /**
     * Alias-resolved catalog rows: iTunes candidates for "title artist"
     * (romanized/kana spellings survive where the DB's literal matching
     * cannot), each fetched as an exact-name `/lyrics` row. Shared by the
     * manual catalog fallback and the auto iTunes fallback.
     */
    private fun itunesRows(
        title: String,
        artist: String,
        transport: MutableList<String>? = null
    ): List<ApiResponse> {
        if (title.isBlank() || artist.isBlank()) return emptyList()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit
            .SECONDS.toNanos(CATALOG_BUDGET_SEC)
        val rows = mutableListOf<ApiResponse>()
        val seen = mutableSetOf(title.trim().lowercase() + "\u0000" + artist.trim().lowercase())
        for ((trackName, artistName) in itunesCandidates(title, artist, transport)) {
            if (System.nanoTime() > deadline) break
            val key = trackName.lowercase() + "\u0000" + artistName.lowercase()
            if (!seen.add(key)) continue
            lyricsFor(trackName, artistName, transport)?.let { rows += it }
            if (rows.size >= CATALOG_LOOKUP_LIMIT) break
        }
        return rows
    }

    /** Exact-name `/lyrics` fetch; null unless the row carries real text. */
    private fun lyricsFor(
        title: String,
        artist: String,
        transport: MutableList<String>? = null
    ): ApiResponse? {
        if (title.isBlank()) return null
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("track", title.trim())
            .apply {
                if (artist.isNotBlank()) addQueryParameter("artist", artist.trim())
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
                if (!response.isSuccessful) {
                    if (response.code != 404) transport?.add("HTTP ${response.code}")
                    return@use null
                }
                val parsed = try {
                    gson.fromJson(response.body?.string(), ApiResponse::class.java)
                } catch (e: Exception) {
                    transport?.add("parse: ${e.javaClass.simpleName}")
                    null
                }
                parsed?.takeIf { row ->
                    !row.instrumental &&
                        listOf(row.karaoke, row.synced, row.plain)
                            .any { field -> !field.isNullOrBlank() }
                }
            }
        } catch (e: Exception) {
            transport?.add("network: ${e.javaClass.simpleName}")
            null
        }
    }

    internal fun parseItunesResponse(json: String): List<Pair<String, String>> =
        (try {
            gson.fromJson(json, ItunesResponse::class.java)
        } catch (_: Exception) {
            null
        })?.results.orEmpty()
            .mapNotNull { track ->
                val name = track.trackName?.trim().orEmpty()
                if (name.isBlank()) null
                else name to track.artistName?.trim().orEmpty()
            }
            .distinct()

    private fun itunesCandidates(
        title: String,
        artist: String,
        transport: MutableList<String>? = null
    ): List<Pair<String, String>> {
        val term = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        val url = ITUNES_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "6")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "AutoLyrics/${BuildConfig.VERSION_NAME}")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    transport?.add("iTunes HTTP ${response.code}")
                    emptyList()
                } else {
                    parseItunesResponse(response.body?.string().orEmpty())
                        .filter { (_, artistName) ->
                            // Without an artist tiebreak, iTunes' top hits for
                            // a common title are other artists' songs.
                            artistName.isBlank() || artist.isBlank() ||
                                artistName.contains(artist, ignoreCase = true) ||
                                artist.contains(artistName, ignoreCase = true)
                        }
                }
            }
        } catch (e: Exception) {
            transport?.add("iTunes network: ${e.javaClass.simpleName}")
            emptyList()
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
     * Second auto stage and the whole manual ladder: the website-style
     * iTunes-resolved catalog lookup, for the cases where the exact
     * `/lyrics` call cannot match the session names (alias/script spellings,
     * or live aggregation down while the DB behind `/lyrics` lookups is
     * healthy).
     */
    private fun itunesFallback(
        track: TrackInfo,
        transport: MutableList<String>? = null
    ): SyncLrcResult? {
        // Auto ladder: the exact /lyrics call with the session names already
        // failed above, and the DB-backed /search matches those same literal
        // names, so it adds almost nothing for a single auto winner. Go
        // straight to the website-style iTunes-resolved catalog lookup.
        val best = pickBestCandidate(itunesRows(track.title, track.artist, transport), track) ?: return null
        debugLog("catalog fallback selected '${best.track.orEmpty()}'")
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

        // Every tier is a candidate now: the aggregator sources NetEase/QQ/
        // Kugou/LDDC, so its synced lines are sometimes the ONLY synced copy of
        // a CJK track anywhere we can reach. Karaoke still wins when present.
        val legacyType = response.type?.lowercase().orEmpty()
        val karaokeLyrics = response.karaoke
            ?.takeIf { it.isNotBlank() }
            ?: response.lyrics
                ?.takeIf { it.isNotBlank() && legacyType == "karaoke" }
        val syncedLyrics = response.synced
            ?.takeIf { it.isNotBlank() }
            ?: response.lyrics
                ?.takeIf { it.isNotBlank() && legacyType == "synced" }
        val plainLyrics = response.plain
            ?.takeIf { it.isNotBlank() }
            ?: response.lyrics
                ?.takeIf { it.isNotBlank() && legacyType == "plain" }

        val fallback = requestedTrack
        val title = response.track.orEmpty().ifBlank { fallback?.title.orEmpty() }
        val artist = response.artist.orEmpty().ifBlank { fallback?.artist.orEmpty() }
        val album = response.album.orEmpty().ifBlank { fallback?.album.orEmpty() }

        fun accepted(
            lines: List<LyricLine>,
            kind: LyricsProviderCandidate.SyncKind,
            note: String
        ): SyncLrcResult {
            debugLog(note)
            return SyncLrcResult(
                lines = lines,
                matchedTitle = title,
                matchedArtist = artist,
                matchedAlbum = album,
                matchedDurationSec = response.duration,
                artistQueryCorroborated = requestedTrack?.artist?.isNotBlank() == true,
                syncKind = kind
            )
        }

        if (karaokeLyrics != null) {
            val lines = LrcParser.parseKaraoke(karaokeLyrics)
            if (lines.any { it.words.isNotEmpty() }) {
                return accepted(
                    lines,
                    LyricsProviderCandidate.SyncKind.WORD_SYNC,
                    "karaoke accepted lines=${lines.size} tokens=${lines.sumOf { it.words.size }} " +
                        "track='${title}' artist='${artist}'"
                )
            }
            debugLog("karaoke payload has no timed tokens; trying lower tiers")
        }
        if (syncedLyrics != null) {
            val lines = LrcParser.parseKaraoke(syncedLyrics)
            if (lines.isNotEmpty()) {
                return accepted(
                    lines,
                    LyricsProviderCandidate.SyncKind.LINE_SYNC,
                    "synced accepted lines=${lines.size} track='${title}' artist='${artist}'"
                )
            }
        }
        if (plainLyrics != null) {
            val lines = plainLyrics.lines()
                .filter { it.isNotBlank() }
                .map { text -> LyricLine(0L, LrcParser.cleanSpacing(text)) }
            if (lines.isNotEmpty()) {
                return accepted(lines, LyricsProviderCandidate.SyncKind.PLAIN, "plain accepted lines=${lines.size}")
            }
        }

        debugLog(
            "response rejected: no usable payload; legacyType=${legacyType} " +
                "karaoke=${!karaokeLyrics.isNullOrBlank()} synced=${!syncedLyrics.isNullOrBlank()} " +
                "plain=${!plainLyrics.isNullOrBlank()}"
        )
        return null
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
