package com.autolyrics.lyrics

import android.content.SharedPreferences
import android.util.Log
import com.autolyrics.BuildConfig
import com.autolyrics.media.SpotifyTrackIdentity
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Musixmatch provider using the anonymous mobile API flow.
 *
 * The desktop endpoint currently returns poisoned / unrelated matches, so this
 * client follows the active mobile path used by current Musixmatch clients:
 *  1. token.get -> short-lived anonymous user_token.
 *  2. macro.subtitles.get -> matcher metadata + richsync/subtitle macro calls.
 *  3. track.richsync.get -> fallback RichSync request if the macro omitted it.
 *
 * This is an unofficial endpoint. Failures remain isolated to this provider.
 */
object MusixmatchClient {

    private const val TAG = "Musixmatch"
    private const val BASE_URL = "https://apic-appmobile.musixmatch.com/ws/1.1/"
    private const val APP_ID = "mac-ios-v2.0"
    private const val APP_VERSION = "10.1.1"
    private const val MIN_PROVIDER_METADATA_SCORE = 0.70
    private const val ABSOLUTE_OFFSET_TOLERANCE_SEC = 2.0
    private const val GAP_SPREAD_MS = 1_200L
    private const val LINE_HOLD_SPREAD_MS = 1_200L

    private const val TOKEN_PREF_KEY = "musixmatch_mobile_user_token"
    private const val TOKEN_TIME_PREF_KEY = "musixmatch_mobile_user_token_time_ms"
    private const val TOKEN_TTL_MS = 60L * 1000L

    private const val MOBILE_USER_AGENT =
        "Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    private val tokenLock = Any()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedTokenAtMs: Long = 0L

    data class MusixmatchResult(
        val lines: List<LyricLine>,
        val matchedTitle: String,
        val matchedArtist: String,
        val matchedAlbum: String,
        val matchedDurationSec: Double?,
        val isRichSync: Boolean,
        val artistQueryCorroborated: Boolean = false,
        /** Compact RichSync payload-anomaly tag for the source label. */
        val timingTag: String = ""
    )

    internal data class TrackCandidate(
        val trackId: Long?,
        val commonTrackId: Long?,
        val spotifyTrackId: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationSec: Double?,
        val hasRichSync: Boolean,
        val instrumental: Boolean
    )

    internal data class MacroMatch(
        val candidate: TrackCandidate,
        val subtitleBody: String,
        val richSyncResponseJson: String? = null
    )

    private data class HttpJsonResponse(
        val httpCode: Int,
        val json: JsonObject?
    )

    fun getLyrics(
        track: TrackInfo,
        prefs: SharedPreferences? = null
    ): MusixmatchResult? {
        if (track.title.isBlank()) return null

        val macro = fetchMacro(track, prefs) ?: return null
        val match = parseMacroResponse(macro.toString()) ?: return null
        val candidate = match.candidate
        val requestedSpotifyTrackId = SpotifyTrackIdentity.trackId(track)

        logMatchDiagnostics(track, candidate)

        if (!spotifyIdentityCompatible(requestedSpotifyTrackId, candidate.spotifyTrackId)) {
            debugLog(
                "mobile macro match rejected: spotify id mismatch " +
                    "requested=$requestedSpotifyTrackId matched=${candidate.spotifyTrackId}"
            )
            return null
        }

        if (candidate.instrumental) {
            debugLog("mobile macro match rejected: instrumental")
            return null
        }

        val normalized = normalizedCandidate(
            candidate = candidate,
            artistQueryCorroborated = track.artist.isNotBlank()
        )
        val metadataScore = LyricsProviderResolver.metadataScore(track, normalized)
        if (metadataScore == null || metadataScore < MIN_PROVIDER_METADATA_SCORE) {
            debugLog("mobile macro match rejected: metadata=${scoreText(metadataScore)}")
            return null
        }

        if (candidate.hasRichSync) {
            val inlineRichSync = match.richSyncResponseJson
                ?.let { parseRichSyncResponseWithStats(it) }
                ?: (emptyList<LyricLine>() to "")
            val richSync = if (inlineRichSync.first.isNotEmpty()) {
                debugLog("using RichSync embedded in mobile macro response")
                inlineRichSync.first to inlineRichSync.second
            } else if (candidate.commonTrackId != null) {
                fetchRichSync(candidate, prefs)
            } else {
                emptyList<LyricLine>() to ""
            }

            if (richSync.first.isNotEmpty()) {
                debugLog("mobile richsync accepted lines=${richSync.first.size}")
                return MusixmatchResult(
                    lines = richSync.first,
                    matchedTitle = candidate.title,
                    matchedArtist = candidate.artist,
                    matchedAlbum = candidate.album,
                    matchedDurationSec = candidate.durationSec,
                    isRichSync = true,
                    artistQueryCorroborated = track.artist.isNotBlank(),
                    timingTag = richSync.second
                )
            }
        }

        val lineSync = parseSubtitleBody(match.subtitleBody)
        if (lineSync.isNotEmpty()) {
            debugLog("mobile line subtitle accepted lines=${lineSync.size}")
            return MusixmatchResult(
                lines = lineSync,
                matchedTitle = candidate.title,
                matchedArtist = candidate.artist,
                matchedAlbum = candidate.album,
                matchedDurationSec = candidate.durationSec,
                isRichSync = false,
                artistQueryCorroborated = track.artist.isNotBlank()
            )
        }

        debugLog("mobile macro match had no usable synced lyrics")
        return null
    }

    internal fun buildMacroParams(track: TrackInfo): LinkedHashMap<String, String> {
        val params = linkedMapOf(
            "namespace" to "lyrics_richsynched",
            "optional_calls" to "track.richsync",
            "subtitle_format" to "lrc",
            "q_artist" to track.artist,
            "q_track" to track.title
        )
        if (track.album.isNotBlank()) {
            params["q_album"] = track.album
        }
        if (track.durationMs > 0L) {
            params["q_duration"] = (track.durationMs / 1000.0).roundToLong().toString()
        }
        SpotifyTrackIdentity.trackId(track)?.let { spotifyTrackId ->
            params["track_spotify_id"] = spotifyTrackId
        }
        return params
    }

    internal fun spotifyIdentityCompatible(
        requestedSpotifyTrackId: String?,
        matchedSpotifyTrackId: String
    ): Boolean {
        return requestedSpotifyTrackId == null ||
            matchedSpotifyTrackId.isBlank() ||
            matchedSpotifyTrackId == requestedSpotifyTrackId
    }

    private fun fetchMacro(
        track: TrackInfo,
        prefs: SharedPreferences?
    ): JsonObject? {
        val params = buildMacroParams(track)

        debugLog(
            "mobile macro.subtitles.get q_track='${track.title}' q_artist='${track.artist}' " +
                "q_album='${params["q_album"].orEmpty()}' q_duration='${params["q_duration"].orEmpty()}' " +
                "spotify_id='${params["track_spotify_id"].orEmpty()}'"
        )

        return authenticatedRequest("macro.subtitles.get", params, prefs)
    }

    private fun fetchRichSync(
        candidate: TrackCandidate,
        prefs: SharedPreferences?
    ): Pair<List<LyricLine>, String> {
        val commonTrackId = candidate.commonTrackId ?: return emptyList<LyricLine>() to ""
        val response = authenticatedRequest(
            endpoint = "track.richsync.get",
            params = linkedMapOf("commontrack_id" to commonTrackId.toString()),
            prefs = prefs
        ) ?: return emptyList<LyricLine>() to ""

        return parseRichSyncResponseWithStats(response.toString())
    }

    /** One search candidate for the manual pick-list. */
    data class ManualCandidate(
        val commonTrackId: Long,
        val trackId: String?,
        val title: String,
        val artist: String,
        val durationSec: Int?,
        /** True/False when the search row carries `has_richsync`; null = unknown. */
        val hasRichSync: Boolean?
    )

    /** Lyrics resolved after the user picks a manual candidate. */
    data class ManualFetched(
        val lines: List<LyricLine>,
        val status: LyricsStatus,
        val source: String
    )

    /**
     * Candidate list for the manual search dialog. `mobile.track.search` is
     * the only list endpoint on the mobile contract; bodies arrive later via
     * [fetchManualLyrics] when the user picks a row.
     */
    fun manualSearch(
        title: String,
        artist: String,
        prefs: SharedPreferences? = null,
        diagnostics: MutableList<String>? = null
    ): List<ManualCandidate> {
        val track = title.trim()
        val performer = artist.trim()
        if (track.isBlank() && performer.isBlank()) return emptyList()

        val params = LinkedHashMap<String, String>()
        if (track.isNotBlank()) params["q_track"] = track
        if (performer.isNotBlank()) params["q_artist"] = performer
        params["page_size"] = "10"

        val response = authenticatedRequest("track.search", params, prefs)
        if (response == null) {
            diagnostics?.plusAssign("musixmatch: request failed")
            return emptyList()
        }

        val candidates = parseTrackSearchResponse(response.toString())
        diagnostics?.plusAssign("musixmatch: ${candidates.size}")
        return candidates
    }

    internal fun parseTrackSearchResponse(json: String): List<ManualCandidate> {
        val root = parseJsonObject(json) ?: return emptyList()
        if (apiStatus(root) != 200) return emptyList()

        return root.getAsJsonObject("message")
            ?.getAsJsonObject("body")
            ?.getAsJsonArray("track_list")
            ?.mapNotNull { element ->
                val track = element.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.getAsJsonObject("track")
                    ?: return@mapNotNull null
                val commonTrackId = track.longOrNull("commontrack_id")
                    ?: return@mapNotNull null
                val richSyncFlag = track.intOrNull("has_richsync")
                // Field spellings differ between contract generations; take the
                // first populated alias so rows never render with blank titles.
                fun firstFilled(vararg keys: String): String {
                    for (key in keys) {
                        val value = track.string(key)
                        if (value.isNotBlank()) return value
                    }
                    return ""
                }
                val durationSec = track.doubleOrNull("track_length_seconds")
                    ?: track.doubleOrNull("track_length")
                ManualCandidate(
                    commonTrackId = commonTrackId,
                    trackId = track.string("track_id").takeIf { it.isNotBlank() },
                    title = firstFilled("name", "track_name", "commontrack_name"),
                    artist = firstFilled(
                        "primary_artist_name", "artist_name", "primary_artists_name"
                    ),
                    durationSec = durationSec?.toInt()?.takeIf { it > 0 },
                    hasRichSync = richSyncFlag?.let { it == 1 }
                )
            }
            .orEmpty()
    }

    /**
     * Resolves lyrics for a picked candidate: RichSync (word timing) first,
     * then line subtitles, then the plain body as a last resort.
     */
    fun fetchManualLyrics(
        candidate: ManualCandidate,
        prefs: SharedPreferences? = null
    ): ManualFetched? {
        val commonTrackId = candidate.commonTrackId.toString()

        val richSyncJson = authenticatedRequest(
            endpoint = "track.richsync.get",
            params = linkedMapOf("commontrack_id" to commonTrackId),
            prefs = prefs
        )
        val richSyncParsed = richSyncJson?.let { parseRichSyncResponseWithStats(it.toString()) }
            ?: (emptyList<LyricLine>() to "")
        val richSyncLines = richSyncParsed.first
        if (richSyncLines.any { it.words.isNotEmpty() } &&
            richSyncLines.any { it.text.isNotBlank() && it.text != "♪" }
        ) {
            return ManualFetched(
                lines = richSyncLines,
                status = LyricsStatus.FOUND,
                source = "Musixmatch · RichSync" + richSyncParsed.second
            )
        }

        val subtitleLines = authenticatedRequest(
            endpoint = "track.subtitles.get",
            params = linkedMapOf("commontrack_id" to commonTrackId),
            prefs = prefs
        )
            ?.let { extractSubtitleBody(it) }
            ?.let { parseSubtitleBody(it) }
            ?.filter { it.text.isNotBlank() }
            .orEmpty()
        if (subtitleLines.isNotEmpty() && subtitleLines.any { it.text != "♪" }) {
            return ManualFetched(
                lines = subtitleLines,
                status = LyricsStatus.FOUND,
                source = "Musixmatch · Line"
            )
        }

        val trackId = candidate.trackId ?: return null
        val plainBody = authenticatedRequest(
            endpoint = "track.text.get",
            params = linkedMapOf("track_id" to trackId),
            prefs = prefs
        )
            ?.let { deepFind(it, "lyrics_body")?.asStringOrNull() }
            .orEmpty()
        val plainLines = cleanPlainBody(plainBody)
            .map { text -> LyricLine(0L, LrcParser.cleanSpacing(text)) }
        if (plainLines.isNotEmpty()) {
            return ManualFetched(
                lines = plainLines,
                status = LyricsStatus.PLAIN_ONLY,
                source = "Musixmatch · Plain"
            )
        }

        return null
    }

    internal fun extractSubtitleBody(root: JsonObject): String {
        return root.getAsJsonObject("message")
            ?.getAsJsonObject("body")
            ?.getAsJsonArray("subtitle_list")
            ?.firstOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.getAsJsonObject("subtitle")
            ?.string("subtitle_body")
            .orEmpty()
    }

    internal fun cleanPlainBody(body: String): List<String> {
        return body.lines()
            .map { it.trim() }
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith("***") &&
                    !it.contains("Advertisement", ignoreCase = true)
            }
    }

    private fun authenticatedRequest(
        endpoint: String,
        params: LinkedHashMap<String, String>,
        prefs: SharedPreferences?
    ): JsonObject? {
        var token = getToken(prefs, force = false) ?: return null
        var response = request(
            endpoint = endpoint,
            params = LinkedHashMap(params).apply { put("usertoken", token) }
        )

        if (response.httpCode == 401 || apiStatus(response.json) == 401) {
            debugLog("mobile $endpoint token rejected; refreshing once")
            invalidateToken(prefs)
            token = getToken(prefs, force = true) ?: return null
            response = request(
                endpoint = endpoint,
                params = LinkedHashMap(params).apply { put("usertoken", token) }
            )
        }

        return response.json?.takeIf { apiStatus(it) == 200 }
    }

    private fun getToken(
        prefs: SharedPreferences?,
        force: Boolean
    ): String? {
        synchronized(tokenLock) {
            val now = System.currentTimeMillis()

            if (cachedToken == null && prefs != null) {
                cachedToken = prefs.getString(TOKEN_PREF_KEY, null)
                cachedTokenAtMs = prefs.getLong(TOKEN_TIME_PREF_KEY, 0L)
            }

            val memoryToken = cachedToken
            if (
                !force &&
                !memoryToken.isNullOrBlank() &&
                now - cachedTokenAtMs in 0 until TOKEN_TTL_MS
            ) {
                debugLog("mobile token cache hit")
                return memoryToken
            }

            debugLog(if (force) "mobile token.get refresh" else "mobile token.get")
            val response = request(
                endpoint = "token.get",
                params = linkedMapOf("user_language" to "en")
            )
            val token = parseTokenResponse(response.json?.toString().orEmpty())
            if (!token.isNullOrBlank()) {
                cachedToken = token
                cachedTokenAtMs = now
                prefs?.edit()
                    ?.putString(TOKEN_PREF_KEY, token)
                    ?.putLong(TOKEN_TIME_PREF_KEY, now)
                    ?.apply()
                debugLog("mobile token cached")
                return token
            }

            return cachedToken
        }
    }

    private fun invalidateToken(prefs: SharedPreferences?) {
        cachedToken = null
        cachedTokenAtMs = 0L
        prefs?.edit()
            ?.remove(TOKEN_PREF_KEY)
            ?.remove(TOKEN_TIME_PREF_KEY)
            ?.apply()
    }

    private fun request(
        endpoint: String,
        params: LinkedHashMap<String, String>
    ): HttpJsonResponse {
        val url = buildUrl(endpoint, params)
        val request = Request.Builder()
            .url(url)
            .header("x-mxm-app-version", APP_VERSION)
            .header("X-User-Agent", MOBILE_USER_AGENT)
            .header("User-Agent", MOBILE_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = parseJsonObject(body)
                debugLog(
                    "mobile ${response.code} api=${apiStatus(json) ?: "-"} $endpoint"
                )
                HttpJsonResponse(response.code, json)
            }
        } catch (e: Exception) {
            debugLog("mobile $endpoint failed: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
            HttpJsonResponse(-1, null)
        }
    }

    private fun buildUrl(
        endpoint: String,
        params: LinkedHashMap<String, String>
    ): String {
        val all = linkedMapOf(
            "app_id" to APP_ID,
            "format" to "json",
            "t" to System.currentTimeMillis().toString()
        )
        all.putAll(params)

        val query = all.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        return "$BASE_URL$endpoint?$query"
    }

    internal fun parseTokenResponse(json: String): String? {
        val root = parseJsonObject(json) ?: return null
        if (apiStatus(root) != 200) return null
        val token = root.getAsJsonObject("message")
            ?.getAsJsonObject("body")
            ?.string("user_token")
            .orEmpty()
        return token.takeIf { it.isNotBlank() && !it.startsWith("UpgradeOnly") }
    }

    internal fun parseMacroResponse(json: String): MacroMatch? {
        val root = parseJsonObject(json) ?: return null
        if (apiStatus(root) != 200) return null

        val macroCalls = root.getAsJsonObject("message")
            ?.getAsJsonObject("body")
            ?.getAsJsonObject("macro_calls")
            ?: return null

        val matcherCall = macroCalls.getAsJsonObject("matcher.track.get") ?: return null
        if (apiStatus(matcherCall) != 200) return null
        val track = matcherCall.getAsJsonObject("message")
            ?.getAsJsonObject("body")
            ?.getAsJsonObject("track")
            ?: return null

        val title = track.string("track_name")
        if (title.isBlank()) return null

        val candidate = TrackCandidate(
            trackId = track.longOrNull("track_id"),
            commonTrackId = track.longOrNull("commontrack_id"),
            spotifyTrackId = track.string("track_spotify_id"),
            title = title,
            artist = track.string("artist_name"),
            album = track.string("album_name"),
            durationSec = track.doubleOrNull("track_length"),
            hasRichSync = track.intOrNull("has_richsync") == 1,
            instrumental = track.intOrNull("instrumental") == 1
        )

        val subtitleCall = macroCalls.getAsJsonObject("track.subtitles.get")
        val subtitleBody = if (apiStatus(subtitleCall) == 200) {
            subtitleCall
                ?.getAsJsonObject("message")
                ?.getAsJsonObject("body")
                ?.getAsJsonArray("subtitle_list")
                ?.firstOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.getAsJsonObject("subtitle")
                ?.string("subtitle_body")
                .orEmpty()
        } else {
            ""
        }

        val richSyncCall = macroCalls.getAsJsonObject("track.richsync.get")
        val richSyncResponseJson = richSyncCall
            ?.takeIf { apiStatus(it) == 200 }
            ?.toString()

        return MacroMatch(candidate, subtitleBody, richSyncResponseJson)
    }

    internal fun parseSubtitleBody(subtitleBody: String): List<LyricLine> {
        if (subtitleBody.isBlank()) return emptyList()
        return LrcParser.parse(subtitleBody)
            .filter { it.timeMs >= 0L }
            .distinctBy { it.timeMs to it.text }
    }

    internal fun parseRichSyncResponse(json: String): List<LyricLine> =
        parseRichSyncResponseWithStats(json).first

    internal fun parseRichSyncResponseWithStats(json: String): Pair<List<LyricLine>, String> {
        val stats = RichSyncStats()
        val root = parseJsonObject(json) ?: return emptyList<LyricLine>() to ""
        if (apiStatus(root) != 200) return emptyList<LyricLine>() to ""

        val richSyncBody = deepFind(root, "richsync_body")
            ?.asStringOrNull()
            .orEmpty()
        if (richSyncBody.isBlank()) return emptyList<LyricLine>() to ""

        return try {
            val lines = JsonParser.parseString(richSyncBody).asJsonArray
            val parsed = lines.mapNotNull { element ->
                val line = element.asJsonObject
                val startSec = line.doubleOrNull("ts") ?: return@mapNotNull null
                val text = line.string("x").ifBlank { "♪" }
                val startMs = secondsToMs(startSec)
                val words = parseRichSyncWords(line, startSec, stats)

                ParsedRichLine(
                    line = LyricLine(
                        timeMs = startMs,
                        text = text,
                        words = words
                    ),
                    endTimeMs = line.doubleOrNull("te")?.let { secondsToMs(it) }
                )
            }
                .filter { it.line.timeMs >= 0L }
                .sortedBy { it.line.timeMs }
                .distinctBy { it.line.timeMs to it.line.text }
            val repaired = parsed.mapIndexed { index, entry ->
                val words = entry.line.words
                val boundaryMs = effectiveLineEndMs(
                    teMs = entry.endTimeMs,
                    lastTokenMs = words.lastOrNull()?.timeMs,
                    nextLineMs = parsed.getOrNull(index + 1)?.line?.timeMs
                )
                entry.line.copy(
                    words = LrcParser.collapsePunctuation(
                        spreadLongGaps(fillUncoveredText(entry.line.text, words, boundaryMs), boundaryMs)
                    )
                )
            }
            repaired to stats.tag()
        } catch (_: Exception) {
            emptyList<LyricLine>() to ""
        }
    }

    private data class ParsedRichLine(val line: LyricLine, val endTimeMs: Long?)

    /** Field-visible anomaly counters for RichSync payloads (see source tag). */
    private class RichSyncStats {
        var fixed = 0
        var outOfBounds = 0
        var duplicates = 0
        var missingText = 0
        var maxGapSec = 0

        fun tag(): String {
            val parts = listOfNotNull(
                "fix$fixed".takeIf { fixed > 0 },
                "oob$outOfBounds".takeIf { outOfBounds > 0 },
                "dup$duplicates".takeIf { duplicates > 0 },
                "miss$missingText".takeIf { missingText > 0 },
                "gap${maxGapSec}s".takeIf { maxGapSec >= GAP_TAG_MIN_SEC }
            )
            return if (parts.isEmpty()) "" else " · ${parts.joinToString(" ")}"
        }

        private companion object {
            const val GAP_TAG_MIN_SEC = 3
        }
    }

    private fun parseRichSyncWords(
        line: JsonObject,
        lineStartSec: Double,
        stats: RichSyncStats? = null
    ): List<LyricWord> {
        val chunks = line.getAsJsonArray("l") ?: return emptyList()
        val words = mutableListOf<LyricWord>()
        val lineSpanSec = line.doubleOrNull("te")?.let { it - lineStartSec }

        for (chunkElement in chunks) {
            val chunk = chunkElement.asJsonObject
            val raw = chunk.string("c")
            val text = raw.trim()
            if (text.isBlank()) continue

            var offsetSec = chunk.doubleOrNull("o") ?: 0.0
            // Some payloads mix conventions and store absolute track times in
            // `o` instead of line-relative offsets. Such a chunk lands far past
            // the line end (te) and freezes the highlight until the next line;
            // reinterpret it relative to the line start when that fits.
            if (lineSpanSec != null && lineSpanSec > 0 &&
                offsetSec > lineSpanSec + ABSOLUTE_OFFSET_TOLERANCE_SEC
            ) {
                val asRelative = offsetSec - lineStartSec
                if (asRelative >= -ABSOLUTE_OFFSET_TOLERANCE_SEC &&
                    asRelative <= lineSpanSec + ABSOLUTE_OFFSET_TOLERANCE_SEC
                ) {
                    offsetSec = asRelative
                    stats?.let { it.fixed += 1 }
                }
            }
            val timeMs = secondsToMs(lineStartSec + offsetSec.coerceAtLeast(0.0))

            if (isPunctuationOnly(text) && words.isNotEmpty()) {
                val previous = words.removeAt(words.lastIndex)
                words += previous.copy(text = previous.text + text)
            } else {
                words += LyricWord(timeMs = timeMs, text = text)
            }
        }

        // Chunk offsets are occasionally non-monotonic (notably for CJK lines);
        // restore chronological order so downstream timing can rely on it.
        var monotonic = true
        for (index in 1 until words.size) {
            if (words[index].timeMs < words[index - 1].timeMs) {
                monotonic = false
                break
            }
        }
        if (!monotonic) {
            words.sortBy { it.timeMs }
        }

        if (stats != null) {
            var maxGapMs = 0L
            words.zipWithNext { previous, next ->
                if (next.timeMs == previous.timeMs) stats.duplicates += 1
                maxGapMs = maxOf(maxGapMs, next.timeMs - previous.timeMs)
            }
            stats.maxGapSec = maxOf(stats.maxGapSec, (maxGapMs / 1000L).toInt())
            if (lineSpanSec != null) {
                val endMs = secondsToMs(lineStartSec + lineSpanSec)
                stats.outOfBounds += words.count { it.timeMs > endMs + 2_000L }
            }
            // Token texts that cannot be found in sequence inside the line's
            // own `x` indicate interleaved or foreign token streams.
            val lineText = line.string("x")
            if (lineText.isNotBlank()) {
                var cursor = 0
                for (word in words) {
                    val index = lineText.indexOf(word.text, startIndex = cursor)
                    if (index < 0) {
                        stats.missingText += 1
                    } else {
                        cursor = index + word.text.length
                    }
                }
            }
        }

        return words
    }

    /**
     * Boundary for a line's final token. `te` is only trusted when it leaves
     * a meaningful tail past the last token; polluted payloads set `te` at
     * (or near) the line start, which would strand held CJK lines. In that
     * case the line can plausibly be sung until the next line starts.
     */
    internal fun effectiveLineEndMs(teMs: Long?, lastTokenMs: Long?, nextLineMs: Long?): Long? {
        if (lastTokenMs == null) return nextLineMs ?: teMs
        if (teMs != null && teMs - lastTokenMs >= GAP_SPREAD_MS) return teMs
        if (nextLineMs != null && nextLineMs > lastTokenMs) return nextLineMs
        return teMs?.takeIf { it > lastTokenMs }
    }

    /**
     * Some RichSync payloads only time the first stretch of the line text and
     * leave the rest chunkless, so the highlight freezes while the singing
     * continues through untimed words. Any run of line text no token covers
     * gets per-character tokens spread evenly between the surrounding token
     * times (or the line end for a trailing run).
     */
    internal fun fillUncoveredText(
        lineText: String,
        words: List<LyricWord>,
        lineEndMs: Long?
    ): List<LyricWord> {
        if (lineText.isBlank() || words.isEmpty()) return words
        val out = ArrayList<LyricWord>(words.size + 16)
        var cursor = 0
        var previousTimeMs = words.first().timeMs
        for (word in words) {
            val index = lineText.indexOf(word.text, startIndex = cursor)
            if (index >= cursor) {
                if (index > cursor) {
                    out += spreadRun(lineText.substring(cursor, index), previousTimeMs, word.timeMs)
                }
                out += word
                cursor = index + word.text.length
            } else {
                out += word
            }
            previousTimeMs = word.timeMs
        }
        if (cursor < lineText.length && lineEndMs != null) {
            out += spreadRun(lineText.substring(cursor), previousTimeMs, lineEndMs)
        }
        return out
    }

    private fun spreadRun(run: String, fromMs: Long, toMs: Long): List<LyricWord> {
        val characters = run.filter { !it.isWhitespace() }
        if (characters.isEmpty() || toMs <= fromMs) return emptyList()
        return characters.mapIndexed { index, character ->
            LyricWord(
                timeMs = fromMs + (toMs - fromMs) * index / characters.length,
                text = character.toString()
            )
        }
    }

    /**
     * RichSync payloads sometimes hold one chunk (often a whole CJK clause)
     * across seconds of continuing singing. Freezing the highlight there
     * reads as a stall followed by a jump, so multi-character tokens that
     * start a long gap get their characters spread evenly across it — the
     * same progressive sweep karaoke UIs render for held words.
     */
    internal fun spreadLongGaps(words: List<LyricWord>, lineEndMs: Long?): List<LyricWord> {
        if (words.none { it.text.length > 1 }) return words
        // A line carried by a single chunk freezes at singing tempo even for
        // short spans, so it gets a far lower spread threshold than gaps
        // between multiple chunks.
        val thresholdMs = if (words.size == 1) LINE_HOLD_SPREAD_MS else GAP_SPREAD_MS
        val boundaries = words.indices.map { index ->
            words.getOrNull(index + 1)?.timeMs ?: lineEndMs ?: words[index].timeMs
        }
        if (words.indices.none { index ->
                words[index].text.length > 1 &&
                    boundaries[index] - words[index].timeMs >= thresholdMs
            }
        ) {
            return words
        }
        val spread = ArrayList<LyricWord>(words.size + 8)
        for (index in words.indices) {
            val word = words[index]
            val span = boundaries[index] - word.timeMs
            val characters = word.text.length
            if (characters < 2 || span < thresholdMs) {
                spread += word
                continue
            }
            word.text.forEachIndexed { characterIndex, character ->
                spread += LyricWord(
                    timeMs = word.timeMs + span * characterIndex / characters,
                    text = character.toString()
                )
            }
        }
        return spread
    }

    private fun normalizedCandidate(
        candidate: TrackCandidate,
        artistQueryCorroborated: Boolean
    ): LyricsProviderCandidate {
        return LyricsProviderCandidate(
            provider = "Musixmatch",
            title = candidate.title,
            artist = candidate.artist,
            album = candidate.album,
            durationSec = candidate.durationSec,
            lines = listOf(LyricLine(0L, "candidate")),
            status = LyricsStatus.FOUND,
            source = "Musixmatch · candidate",
            syncKind = if (candidate.hasRichSync) {
                LyricsProviderCandidate.SyncKind.WORD_SYNC
            } else {
                LyricsProviderCandidate.SyncKind.LINE_SYNC
            },
            artistQueryCorroborated = artistQueryCorroborated
        )
    }

    private fun logMatchDiagnostics(
        track: TrackInfo,
        candidate: TrackCandidate
    ) {
        if (!BuildConfig.DEBUG) return

        val targetDurationSec = track.durationMs
            .takeIf { it > 0L }
            ?.div(1000.0)
        val titleScore = LrcLibClient.stringSimilarity(track.title, candidate.title)
        val artistScore = if (track.artist.isNotBlank() && candidate.artist.isNotBlank()) {
            LrcLibClient.artistSimilarity(
                left = track.artist,
                right = candidate.artist,
                allowContributorComponents = titleScore >= 0.95
            )
        } else {
            null
        }
        val albumScore = if (track.album.isNotBlank() && candidate.album.isNotBlank()) {
            LrcLibClient.stringSimilarity(track.album, candidate.album)
        } else {
            null
        }
        val durationScore = if (targetDurationSec != null && candidate.durationSec != null) {
            LrcLibClient.durationSimilarity(targetDurationSec.toInt(), candidate.durationSec)
        } else {
            null
        }
        val metadata = LyricsProviderResolver.metadataScore(
            track,
            normalizedCandidate(candidate, track.artist.isNotBlank())
        )

        debugLog(
            "target title='${track.title}' artist='${track.artist}' album='${track.album}' " +
                "duration=${scoreText(targetDurationSec)}s"
        )
        debugLog(
            "mobile macro match id=${candidate.trackId ?: "-"}/${candidate.commonTrackId ?: "-"} " +
                "spotify=${candidate.spotifyTrackId.ifBlank { "-" }} " +
                "title='${candidate.title}' artist='${candidate.artist}' album='${candidate.album}' " +
                "duration=${scoreText(candidate.durationSec)}s rich=${candidate.hasRichSync} " +
                "instrumental=${candidate.instrumental}"
        )
        debugLog(
            "mobile macro scores title=${scoreText(titleScore)} artist=${scoreText(artistScore)} " +
                "album=${scoreText(albumScore)} duration=${scoreText(durationScore)} " +
                "metadata=${scoreText(metadata)}"
        )
    }

    private fun deepFind(element: JsonElement?, key: String): JsonElement? {
        if (element == null || element.isJsonNull) return null

        if (element.isJsonObject) {
            val obj = element.asJsonObject
            obj.get(key)?.let { return it }
            for ((_, value) in obj.entrySet()) {
                deepFind(value, key)?.let { return it }
            }
        } else if (element.isJsonArray) {
            for (value in element.asJsonArray) {
                deepFind(value, key)?.let { return it }
            }
        }

        return null
    }

    private fun parseJsonObject(json: String): JsonObject? {
        if (json.isBlank() || json.trimStart().startsWith("<")) return null
        return try {
            JsonParser.parseString(json).asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun apiStatus(root: JsonObject?): Int? {
        return root
            ?.getAsJsonObject("message")
            ?.getAsJsonObject("header")
            ?.intOrNull("status_code")
    }

    private fun JsonObject.string(name: String): String {
        return get(name)?.asStringOrNull().orEmpty()
    }

    private fun JsonObject.intOrNull(name: String): Int? {
        return try {
            get(name)?.takeUnless { it.isJsonNull }?.asInt
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.longOrNull(name: String): Long? {
        return try {
            get(name)?.takeUnless { it.isJsonNull }?.asLong
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.doubleOrNull(name: String): Double? {
        return try {
            get(name)?.takeUnless { it.isJsonNull }?.asDouble
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonElement.asStringOrNull(): String? {
        return try {
            if (isJsonNull) null else asString
        } catch (_: Exception) {
            null
        }
    }

    private fun isPunctuationOnly(value: String): Boolean {
        return value.isNotBlank() && value.none { it.isLetterOrDigit() }
    }

    private fun secondsToMs(seconds: Double): Long = (seconds * 1000.0).roundToLong()

    private fun scoreText(value: Double?): String {
        return value?.let { String.format(Locale.US, "%.3f", it) } ?: "n/a"
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
