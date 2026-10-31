package com.autolyrics.lyrics

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

object LrcLibClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private const val BASE_URL = "https://lrclib.net/api"
    private const val USER_AGENT = "AutoLyrics/1.9.6 (https://github.com/whoxamxl/auto-lyrics)"

    private const val MIN_MATCH_SCORE = 0.70
    private const val MIN_TITLE_SCORE = 0.60
    private const val MIN_ARTIST_SCORE = 0.40
    private const val EARLY_EXACT_SCORE = 0.95
    private const val MAX_DURATION_DELTA_SEC = 15.0
    private const val CONTRIBUTOR_COMPONENT_SCORE = 0.95

    private val MULTI_SPACE = Regex("""\s+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val CONTRIBUTOR_SEPARATOR = Regex("""\s*[,;]\s*""")
    private val FEAT_SUFFIX = Regex(
        """\s*[\(\[]?\s*(?:feat(?:uring)?|ft)\.?\s+.+?[\)\]]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    private data class VersionQualifier(
        val name: String,
        val englishPattern: Regex,
        val japaneseMarkers: List<String> = emptyList()
    )

    private val VERSION_QUALIFIERS = listOf(
        VersionQualifier("live", Regex("""\blive\b""", RegexOption.IGNORE_CASE), listOf("ライブ")),
        VersionQualifier("acoustic", Regex("""\bacoustic\b""", RegexOption.IGNORE_CASE), listOf("アコースティック")),
        VersionQualifier("remix", Regex("""\bremix(?:ed)?\b""", RegexOption.IGNORE_CASE), listOf("リミックス")),
        VersionQualifier("remaster", Regex("""\bremaster(?:ed)?\b""", RegexOption.IGNORE_CASE), listOf("リマスター")),
        VersionQualifier("instrumental", Regex("""\binstrumental\b""", RegexOption.IGNORE_CASE), listOf("インストゥルメンタル", "インスト")),
        VersionQualifier("edit", Regex("""\bedit(?:ed)?\b""", RegexOption.IGNORE_CASE), listOf("エディット")),
        VersionQualifier("extended", Regex("""\bextended\b""", RegexOption.IGNORE_CASE), listOf("エクステンデッド")),
        VersionQualifier("demo", Regex("""\bdemo\b""", RegexOption.IGNORE_CASE), listOf("デモ"))
    )

    data class LrcLibResponse(
        @SerializedName("id") val id: Int?,
        @SerializedName("trackName") val trackName: String?,
        @SerializedName("artistName") val artistName: String?,
        @SerializedName("albumName") val albumName: String?,
        @SerializedName("duration") val duration: Double?,
        @SerializedName("instrumental") val instrumental: Boolean?,
        @SerializedName("plainLyrics") val plainLyrics: String?,
        @SerializedName("syncedLyrics") val syncedLyrics: String?
    )

    private data class SearchQuery(
        val trackName: String,
        val artistName: String?,
        val albumName: String?
    )

    private data class ScoredCandidate(
        val response: LrcLibResponse,
        val score: Double
    )

    fun getLyrics(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int
    ): LrcLibResponse? {
        val candidates = LinkedHashMap<String, LrcLibResponse>()

        fun addCandidate(candidate: LrcLibResponse?) {
            if (candidate == null) return
            val key = candidate.id?.let { "id:$it" }
                ?: listOf(
                    candidate.trackName.orEmpty(),
                    candidate.artistName.orEmpty(),
                    candidate.albumName.orEmpty(),
                    candidate.duration?.toString().orEmpty()
                ).joinToString("|")
            candidates.putIfAbsent(key, candidate)
        }

        fun addCandidates(results: List<LrcLibResponse>) {
            results.forEach(::addCandidate)
        }

        // A strong /api/get result is already constrained by all available
        // metadata. Fast-path it when our local validation also considers it an
        // excellent synchronized match, avoiding several unnecessary searches.
        if (durationSec > 0 && albumName.isNotBlank()) {
            val exact = getExact(trackName, artistName, albumName, durationSec)
            addCandidate(exact)
            if (exact != null && !exact.syncedLyrics.isNullOrBlank()) {
                val exactScore = scoreCandidate(
                    exact,
                    trackName,
                    artistName,
                    albumName,
                    durationSec
                )
                if (exactScore != null && exactScore >= EARLY_EXACT_SCORE) {
                    return exact
                }
            }
        }

        val structuredQueries = linkedSetOf<SearchQuery>()
        structuredQueries += SearchQuery(
            trackName = trackName,
            artistName = artistName.takeIf { it.isNotBlank() },
            albumName = albumName.takeIf { it.isNotBlank() }
        )
        if (albumName.isNotBlank()) {
            structuredQueries += SearchQuery(
                trackName = trackName,
                artistName = artistName.takeIf { it.isNotBlank() },
                albumName = null
            )
        }

        val strippedTitle = stripFeaturing(trackName)
        val primaryArtist = stripFeaturing(artistName)
        if (strippedTitle != trackName || primaryArtist != artistName) {
            structuredQueries += SearchQuery(
                trackName = strippedTitle,
                artistName = primaryArtist.takeIf { it.isNotBlank() },
                albumName = null
            )
        }

        structuredQueries.forEach { query ->
            addCandidates(searchAll(query.trackName, query.artistName, query.albumName))
        }

        selectBest(
            candidates.values,
            trackName,
            artistName,
            albumName,
            durationSec,
            requireSynced = true
        )?.let { return it }

        // Some media sessions expose a contributor list instead of the performing
        // artist (for example composer, lyricist, vocalist and studio/brand names
        // concatenated with commas). If artist-constrained FTS could not find a
        // usable synchronized result, broaden discovery by title only and let the
        // local matcher validate title, contributor components, duration and album.
        addCandidates(searchAll(strippedTitle, artistName = null, albumName = null))

        selectBest(
            candidates.values,
            trackName,
            artistName,
            albumName,
            durationSec,
            requireSynced = true
        )?.let { return it }

        // A free-text fallback helps when field-specific FTS matching is defeated
        // by collaboration/feature metadata differences. It is only used when no
        // acceptable synchronized result was found by the structured searches.
        val freeText = listOf(trackName, artistName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (freeText.isNotBlank()) {
            addCandidates(searchFreeText(freeText))
        }

        selectBest(
            candidates.values,
            trackName,
            artistName,
            albumName,
            durationSec,
            requireSynced = true
        )?.let { return it }

        // Plain lyrics are a last resort. They use the same metadata validation,
        // so a weak first search result cannot win simply because it was first.
        return selectBest(
            candidates.values,
            trackName,
            artistName,
            albumName,
            durationSec,
            requireSynced = false
        )
    }

    private fun selectBest(
        candidates: Collection<LrcLibResponse>,
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int,
        requireSynced: Boolean
    ): LrcLibResponse? {
        return candidates.asSequence()
            .filter { candidate ->
                if (requireSynced) {
                    !candidate.syncedLyrics.isNullOrBlank()
                } else {
                    !candidate.plainLyrics.isNullOrBlank()
                }
            }
            .mapNotNull { candidate ->
                scoreCandidate(candidate, trackName, artistName, albumName, durationSec)
                    ?.let { score -> ScoredCandidate(candidate, score) }
            }
            .filter { it.score >= MIN_MATCH_SCORE }
            .maxByOrNull { it.score }
            ?.response
    }

    private fun scoreCandidate(
        candidate: LrcLibResponse,
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int
    ): Double? {
        val candidateTitle = candidate.trackName.orEmpty()
        val candidateArtist = candidate.artistName.orEmpty()
        val candidateAlbum = candidate.albumName.orEmpty()

        if (!versionsCompatible(
            requestedTitle = trackName,
            candidateTitle = candidateTitle,
            candidateInstrumental = candidate.instrumental == true,
            requestedAlbum = albumName,
            candidateAlbum = candidateAlbum
        )) {
            return null
        }

        val titleScore = titleSimilarity(trackName, candidateTitle)
        if (titleScore < MIN_TITLE_SCORE) return null

        val artistScore = if (artistName.isBlank() || candidateArtist.isBlank()) {
            null
        } else {
            artistSimilarity(
                left = artistName,
                right = candidateArtist,
                allowContributorComponents = titleScore >= 0.95
            )
        }

        val durationScore = durationSimilarity(durationSec, candidate.duration)
        if (durationScore != null && durationScore < 0.0) return null

        // Reject a clearly different artist unless title + duration are both
        // exceptionally strong. Exact contributor-component matches are admitted
        // only for near-exact titles by artistSimilarity().
        if (artistScore != null && artistScore < MIN_ARTIST_SCORE) {
            val strongTitleAndDuration = titleScore >= 0.95 && (durationScore ?: 0.0) >= 0.85
            if (!strongTitleAndDuration) return null
        }

        var weightedScore = titleScore * 0.55
        var totalWeight = 0.55

        if (artistScore != null) {
            weightedScore += artistScore * 0.30
            totalWeight += 0.30
        }

        if (durationScore != null) {
            weightedScore += durationScore * 0.12
            totalWeight += 0.12
        }

        if (albumName.isNotBlank() && candidateAlbum.isNotBlank() && candidateAlbum != "-") {
            weightedScore += stringSimilarity(albumName, candidateAlbum) * 0.03
            totalWeight += 0.03
        }

        return weightedScore / totalWeight
    }

    internal fun versionsCompatible(
        requestedTitle: String,
        candidateTitle: String,
        candidateInstrumental: Boolean = false,
        requestedAlbum: String = "",
        candidateAlbum: String = ""
    ): Boolean {
        val requestedTitleVersions = extractTitleVersionQualifiers(
            requestedTitle,
            instrumental = false
        )
        val candidateTitleVersions = extractTitleVersionQualifiers(
            candidateTitle,
            instrumental = candidateInstrumental
        )
        if (requestedTitleVersions == candidateTitleVersions) return true

        // Album metadata may corroborate a version qualifier that is missing from
        // one title, but it must never erase an explicit title-to-title conflict.
        if (requestedTitleVersions.isNotEmpty() && candidateTitleVersions.isNotEmpty()) {
            return false
        }

        val requestedContext = requestedTitleVersions +
            extractAlbumVersionQualifiers(requestedAlbum)
        val candidateContext = candidateTitleVersions +
            extractAlbumVersionQualifiers(candidateAlbum)
        return requestedContext.isNotEmpty() && requestedContext == candidateContext
    }

    internal fun extractVersionQualifiers(
        value: String,
        instrumental: Boolean = false
    ): Set<String> {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        val result = linkedSetOf<String>()

        for (qualifier in VERSION_QUALIFIERS) {
            if (qualifier.englishPattern.containsMatchIn(normalized) ||
                qualifier.japaneseMarkers.any { normalized.contains(it) }
            ) {
                result += qualifier.name
            }
        }

        if (instrumental) result += "instrumental"
        return result
    }

    internal fun durationSimilarity(durationSec: Int, candidateDuration: Double?): Double? {
        if (durationSec <= 0 || candidateDuration == null || candidateDuration <= 0.0) return null

        val delta = abs(candidateDuration - durationSec)
        if (delta > MAX_DURATION_DELTA_SEC) return -1.0

        return when {
            delta <= 2.0 -> 1.00
            delta <= 4.0 -> 0.92
            delta <= 7.0 -> 0.78
            delta <= 10.0 -> 0.60
            else -> 0.35
        }
    }

    private fun titleSimilarity(left: String, right: String): Double {
        return maxOf(
            stringSimilarity(left, right),
            stringSimilarity(stripFeaturing(left), stripFeaturing(right))
        )
    }

    internal fun artistSimilarity(
        left: String,
        right: String,
        allowContributorComponents: Boolean = false
    ): Double {
        val direct = maxOf(
            stringSimilarity(left, right),
            stringSimilarity(stripFeaturing(left), stripFeaturing(right))
        )
        if (!allowContributorComponents || direct >= CONTRIBUTOR_COMPONENT_SCORE) {
            return direct
        }

        val leftVariants = artistVariants(left)
        val rightVariants = artistVariants(right)
        val hasComponentList = leftVariants.size > 1 || rightVariants.size > 1
        if (!hasComponentList) return direct

        val exactComponentMatch = leftVariants.any { leftVariant ->
            rightVariants.any { rightVariant ->
                stringSimilarity(leftVariant, rightVariant) >= 0.999
            }
        }

        return if (exactComponentMatch) {
            maxOf(direct, CONTRIBUTOR_COMPONENT_SCORE)
        } else {
            direct
        }
    }

    private fun artistVariants(value: String): List<String> {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return emptyList()

        val variants = linkedSetOf<String>()
        fun addVariant(candidate: String) {
            val normalized = candidate.trim()
            if (normalized.isBlank()) return
            variants += normalized
            val withoutFeaturing = stripFeaturing(normalized)
            if (withoutFeaturing.isNotBlank()) variants += withoutFeaturing
        }

        addVariant(trimmed)
        val components = trimmed.split(CONTRIBUTOR_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (components.size >= 2) {
            components.forEach(::addVariant)
        }

        return variants.toList()
    }

    internal fun stringSimilarity(left: String, right: String): Double {
        val a = normalizeForMatch(left)
        val b = normalizeForMatch(right)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0

        val compactA = a.replace(" ", "")
        val compactB = b.replace(" ", "")
        if (compactA == compactB) return 1.0

        val dice = bigramDice(compactA, compactB)
        val token = tokenJaccard(a, b)
        val containment = if (compactA.contains(compactB) || compactB.contains(compactA)) {
            min(compactA.length, compactB.length).toDouble() /
                maxOf(compactA.length, compactB.length).toDouble()
        } else {
            0.0
        }

        return maxOf(
            dice,
            (dice * 0.72) + (token * 0.28),
            containment * 0.92
        ).coerceIn(0.0, 1.0)
    }

    private fun tokenJaccard(left: String, right: String): Double {
        val a = left.split(' ').filter { it.isNotBlank() }.toSet()
        val b = right.split(' ').filter { it.isNotBlank() }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val union = a union b
        if (union.isEmpty()) return 0.0
        return (a intersect b).size.toDouble() / union.size.toDouble()
    }

    private fun bigramDice(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.length < 2 || right.length < 2) return 0.0

        val leftCounts = HashMap<String, Int>()
        for (i in 0 until left.length - 1) {
            val gram = left.substring(i, i + 2)
            leftCounts[gram] = (leftCounts[gram] ?: 0) + 1
        }

        val rightCounts = HashMap<String, Int>()
        for (i in 0 until right.length - 1) {
            val gram = right.substring(i, i + 2)
            rightCounts[gram] = (rightCounts[gram] ?: 0) + 1
        }

        var overlap = 0
        for ((gram, leftCount) in leftCounts) {
            val rightCount = rightCounts[gram] ?: continue
            overlap += min(leftCount, rightCount)
        }

        val leftTotal = left.length - 1
        val rightTotal = right.length - 1
        return (2.0 * overlap) / (leftTotal + rightTotal).toDouble()
    }

    private fun normalizeForMatch(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('`', '\'')
            .replace('&', ' ')
            .replace(NON_WORD, " ")
            .replace(MULTI_SPACE, " ")
            .trim()
    }

    private fun stripFeaturing(value: String): String {
        return value.replace(FEAT_SUFFIX, "").trim().ifBlank { value.trim() }
    }

    private fun getExact(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int
    ): LrcLibResponse? {
        val urlBuilder = "$BASE_URL/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", trackName)
            .addQueryParameter("artist_name", artistName)
            .addQueryParameter("album_name", albumName)
            .addQueryParameter("duration", durationSec.toString())

        return executeSingle(urlBuilder.build().toString())
    }

    /**
     * User-driven lookup for the manual search UI. LRCLIB's structured params
     * are exact-equality filters and its free-text ANDs every word, so we run
     * several queries and merge them; per-query diagnostics survive to the
     * caller so the UI can show why an empty result happened.
     */
    class ManualSearchOutcome(
        val responses: List<LrcLibResponse>,
        val diagnostics: List<String>
    )

    fun manualSearch(trackName: String, artistName: String): ManualSearchOutcome {
        val track = trackName.trim()
        val artist = artistName.trim()
        val diagnostics = mutableListOf<String>()
        val batches = mutableListOf<List<LrcLibResponse>>()

        var first = true
        fun run(label: String, block: () -> List<LrcLibResponse>) {
            // LRCLIB asks clients to stay under one request per second.
            if (!first) Thread.sleep(1_100)
            first = false
            val results = try {
                block()
            } catch (e: Exception) {
                diagnostics += "${e.javaClass.simpleName} $label"
                return
            }
            batches += results
        }

        if (track.isNotBlank()) {
            run("exact") { searchAll(track, artist.ifBlank { null }, null, diagnostics, "exact") }
        }
        if (track.isNotBlank() && artist.isNotBlank()) {
            run("combined") { searchFreeText("$track $artist", diagnostics, "combined") }
        }
        if (track.isNotBlank()) {
            run("title") { searchFreeText(track, diagnostics, "title") }
        }
        if (artist.isNotBlank()) {
            run("artist") { searchFreeText(artist, diagnostics, "artist") }
        }

        return ManualSearchOutcome(ManualSearch.mergeResponses(batches), diagnostics)
    }

    private fun searchAll(
        trackName: String,
        artistName: String?,
        albumName: String?,
        diagnostics: MutableList<String>? = null,
        label: String = "search"
    ): List<LrcLibResponse> {
        val urlBuilder = "$BASE_URL/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", trackName)

        if (!artistName.isNullOrBlank()) {
            urlBuilder.addQueryParameter("artist_name", artistName)
        }
        if (!albumName.isNullOrBlank()) {
            urlBuilder.addQueryParameter("album_name", albumName)
        }

        return executeList(urlBuilder.build().toString(), diagnostics, label)
    }

    private fun searchFreeText(
        query: String,
        diagnostics: MutableList<String>? = null,
        label: String = "search"
    ): List<LrcLibResponse> {
        val url = "$BASE_URL/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
            .toString()
        return executeList(url, diagnostics, label)
    }

    private fun executeSingle(url: String): LrcLibResponse? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                gson.fromJson(response.body?.string(), LrcLibResponse::class.java)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun executeList(
        url: String,
        diagnostics: MutableList<String>? = null,
        label: String = "search"
    ): List<LrcLibResponse> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    diagnostics?.plusAssign("HTTP ${response.code} $label")
                    return@use emptyList()
                }
                // Array class literal survives R8 without Signature/TypeToken keeps;
                // the anonymous TypeToken subclass did not in release builds.
                val parsed = gson.fromJson(
                    response.body?.string(),
                    Array<LrcLibResponse>::class.java
                )?.toList() ?: emptyList()
                diagnostics?.plusAssign("${parsed.size} hits $label")
                parsed
            }
        } catch (e: Exception) {
            diagnostics?.plusAssign("${e.javaClass.simpleName} $label")
            emptyList()
        }
    }
}