package com.autolyrics.lyrics

import android.util.Log
import com.autolyrics.BuildConfig
import com.autolyrics.util.LyricDisplay
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.min

/**
 * PetitLyrics provider client.
 *
 * Supports both PetitLyrics word sync (lyricsType=3 / WSY) and line sync
 * (lyricsType=2 / LSY). Line-sync timing is paired with a lyricsType=1 plain-text
 * response for the same lyrics id, following the format used by the reference
 * petitlyric_sync_lyric_download implementation.
 */
object PetitLyricsClient {

    private const val TAG = "PetitLyrics"
    private const val ENDPOINT = "https://on.petitlyrics.com/api/GetPetitLyricsData.php"
    private const val SDK_VERSION = "1.3.4"
    private const val SEARCH_MAX_COUNT = 10
    private const val MIN_PROVIDER_METADATA_SCORE = 0.70

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    data class PetitLyricsResult(
        val lyricsType: Int,
        val lines: List<LyricLine>,
        val matchedTitle: String = "",
        val matchedArtist: String = "",
        val matchedAlbum: String = "",
        val matchedDurationSec: Double? = null,
        val lyricsId: String? = null,
        val artistQueryCorroborated: Boolean = false
    )

    internal data class PetitLyricsCandidate(
        val lyricsId: String?,
        val title: String,
        val artist: String,
        val album: String,
        val lyricsType: Int,
        val lyricsData: String,
        val durationSec: Double? = null,
        val artistQueryCorroborated: Boolean = false
    )

    private data class SearchQuery(
        val artist: String,
        val album: String,
        val label: String
    )

    val isConfigured: Boolean
        get() = BuildConfig.PETITLYRICS_USER_ID.isNotBlank() &&
            BuildConfig.PETITLYRICS_APP_NAME.isNotBlank() &&
            BuildConfig.PETITLYRICS_PKG_NAME.isNotBlank() &&
            BuildConfig.PETITLYRICS_CLIENT_APP_ID.isNotBlank()

    fun getSyncedLyrics(
        album: String,
        artist: String,
        title: String,
        transport: MutableList<String>? = null
    ): PetitLyricsResult? {
        if (!isConfigured) {
            debugLog("provider skipped: configuration is incomplete")
            return null
        }
        if (title.isBlank()) {
            debugLog("provider skipped: title is blank")
            return null
        }

        // PetitLyrics metadata frequently uses Japanese artist names while media
        // sessions may expose romanized names. Start strict, then progressively
        // relax album/artist constraints while keeping candidate validation local.
        val queries = linkedSetOf(
            SearchQuery(artist = artist, album = album, label = "title+artist+album"),
            SearchQuery(artist = artist, album = "", label = "title+artist"),
            SearchQuery(artist = "", album = "", label = "title-only")
        )

        val attempted = hashSetOf<String>()

        for (query in queries) {
            val candidates = requestCandidates(
                title = title,
                artist = query.artist,
                album = query.album,
                lyricsType = 3,
                maxCount = SEARCH_MAX_COUNT,
                logLabel = query.label
            )

            val ranked = rankCandidates(
                candidates = candidates,
                requestedTitle = title,
                requestedArtist = artist,
                requestedAlbum = album,
                artistQueryCorroborated = query.artist.isNotBlank()
            )
            debugLog("${query.label}: candidates=${candidates.size}, acceptable=${ranked.size}")

            for (candidate in ranked) {
                val candidateKey = candidate.lyricsId
                    ?: listOf(candidate.title, candidate.artist, candidate.album, candidate.lyricsType)
                        .joinToString("|")
                if (!attempted.add(candidateKey)) continue

                val result = decodeCandidate(candidate, transport)
                if (result != null) {
                    debugLog(
                        "accepted lyricsType=${result.lyricsType}, lines=${result.lines.size}, " +
                            "match=${candidate.lyricsId ?: "metadata"}, " +
                            "artistQuery=${candidate.artistQueryCorroborated}"
                    )
                    return result
                }
            }
        }

        debugLog("response not usable (no acceptable synced PetitLyrics candidate)")
        return null
    }

    private fun decodeCandidate(
        candidate: PetitLyricsCandidate,
        transport: MutableList<String>? = null
    ): PetitLyricsResult? {
        return when (candidate.lyricsType) {
            3 -> {
                val decoded = decodeBase64(candidate.lyricsData) ?: return null
                val payload = decoded.toString(Charsets.UTF_8)
                val lines = parseWordSyncPayload(payload)
                lines.takeIf { it.isNotEmpty() }?.let {
                    buildResult(candidate, lyricsType = 3, lines = it)
                }
            }

            2 -> {
                val plainCandidate = fetchPlainLyricsFor(candidate, transport) ?: return null
                val lines = decodeLineSyncPayload(
                    lineSyncBase64 = candidate.lyricsData,
                    plainTextBase64 = plainCandidate.lyricsData
                )
                lines.takeIf { it.isNotEmpty() }?.let {
                    buildResult(candidate, lyricsType = 2, lines = it)
                }
            }

            else -> null
        }
    }

    private fun buildResult(
        candidate: PetitLyricsCandidate,
        lyricsType: Int,
        lines: List<LyricLine>
    ): PetitLyricsResult {
        return PetitLyricsResult(
            lyricsType = lyricsType,
            lines = lines,
            matchedTitle = candidate.title,
            matchedArtist = candidate.artist,
            matchedAlbum = candidate.album,
            matchedDurationSec = candidate.durationSec,
            lyricsId = candidate.lyricsId,
            artistQueryCorroborated = candidate.artistQueryCorroborated
        )
    }

    private fun fetchPlainLyricsFor(
        candidate: PetitLyricsCandidate,
        transport: MutableList<String>? = null
    ): PetitLyricsCandidate? {
        candidate.lyricsId?.takeIf { it.isNotBlank() }?.let { lyricsId ->
            val byId = requestCandidatesById(lyricsId, lyricsType = 1, transport = transport)
            selectPlainCompanion(candidate, byId)?.let {
                debugLog("line-sync companion text resolved by lyricsId=$lyricsId")
                return it
            }
        }

        // Fallback for responses that omit lyricsId, or if the ID lookup failed:
        // query using provider-native metadata and rank with the same metadata
        // resolver used for final cross-provider selection.
        val byMetadata = requestCandidates(
            title = candidate.title,
            artist = candidate.artist,
            album = candidate.album,
            lyricsType = 1,
            maxCount = 3,
            logLabel = "line-sync-text",
            transport = transport
        )

        return selectPlainCompanion(candidate, byMetadata)
    }

    internal fun selectPlainCompanion(
        syncedCandidate: PetitLyricsCandidate,
        plainCandidates: List<PetitLyricsCandidate>
    ): PetitLyricsCandidate? {
        val usable = plainCandidates.filter {
            it.lyricsType == 1 && it.lyricsData.isNotBlank()
        }
        if (usable.isEmpty()) return null

        syncedCandidate.lyricsId?.takeIf { it.isNotBlank() }?.let { lyricsId ->
            usable.firstOrNull { it.lyricsId == lyricsId }?.let { return it }
        }

        return rankCandidates(
            candidates = usable,
            requestedTitle = syncedCandidate.title,
            requestedArtist = syncedCandidate.artist,
            requestedAlbum = syncedCandidate.album
        ).firstOrNull()
    }

    /**
     * Candidate list for the manual pick-list, mirroring the other manual
     * legs: list now, decode the body only when a row is applied (Type 3
     * carries its body inline in the candidate; Type 2 fetches on demand).
     * Silent no-op when the provider identity is unconfigured.
     */
    fun manualSearch(
        title: String,
        artist: String,
        diagnostics: MutableList<String>? = null,
        transport: MutableList<String>? = null
    ): List<ManualSearchHit> {
        if (!isConfigured || title.isBlank()) return emptyList()
        val candidates = requestCandidates(
            title = title,
            artist = artist.trim(),
            album = "",
            lyricsType = 3,
            maxCount = SEARCH_MAX_COUNT,
            logLabel = "manual",
            transport = transport
        )
        val hits = manualHits(candidates, title, artist)
        diagnostics?.plusAssign("petitlyrics: ${hits.size}")
        return hits
    }

    internal fun manualHits(
        candidates: List<PetitLyricsCandidate>,
        requestedTitle: String,
        requestedArtist: String
    ): List<ManualSearchHit> = rankCandidates(
        candidates = candidates,
        requestedTitle = requestedTitle,
        requestedArtist = requestedArtist,
        requestedAlbum = "",
        artistQueryCorroborated = requestedArtist.isNotBlank()
    ).map { candidate ->
        val karaoke = candidate.lyricsType == 3
        val source = if (karaoke) "PetitLyrics · Karaoke" else "PetitLyrics · Synced"
        ManualSearchHit(
            provider = "PetitLyrics",
            title = candidate.title,
            artist = candidate.artist,
            durationSec = candidate.durationSec?.toInt(),
            kind = if (karaoke) ManualHitKind.KARAOKE else ManualHitKind.SYNCED,
            lines = emptyList(),
            status = LyricsStatus.FOUND,
            source = source,
            fetch = {
                decodeCandidate(candidate)?.let { result ->
                    MusixmatchClient.ManualFetched(
                        lines = result.lines,
                        status = LyricsStatus.FOUND,
                        source = source
                    )
                }
            }
        )
    }

    private fun requestCandidates(
        title: String,
        artist: String,
        album: String,
        lyricsType: Int,
        maxCount: Int,
        logLabel: String,
        transport: MutableList<String>? = null
    ): List<PetitLyricsCandidate> {
        val body = newRequestBody(lyricsType, maxCount).apply {
            add("key_title", title)
            if (artist.isNotBlank()) add("key_artist", artist)
            if (album.isNotBlank()) add("key_album", album)
        }.build()

        val xml = executeRequest(body, logLabel, transport) ?: return emptyList()
        return parseCandidates(xml)
    }

    private fun requestCandidatesById(
        lyricsId: String,
        lyricsType: Int,
        transport: MutableList<String>? = null
    ): List<PetitLyricsCandidate> {
        val body = newRequestBody(lyricsType, 1).apply {
            add("key_lyricsId", lyricsId)
        }.build()

        val xml = executeRequest(body, "lyricsId", transport) ?: return emptyList()
        return parseCandidates(xml)
    }

    private fun newRequestBody(lyricsType: Int, maxCount: Int): FormBody.Builder {
        return FormBody.Builder()
            .add("lyricsType", lyricsType.toString())
            .add("sdkVer", SDK_VERSION)
            .add("userId", BuildConfig.PETITLYRICS_USER_ID)
            .add("appName", BuildConfig.PETITLYRICS_APP_NAME)
            .add("pkgName", BuildConfig.PETITLYRICS_PKG_NAME)
            .add("clientAppId", BuildConfig.PETITLYRICS_CLIENT_APP_ID)
            .add("index", "0")
            .add("logFlag", "0")
            .add("verCode", BuildConfig.VERSION_CODE.toString())
            .add("verName", BuildConfig.VERSION_NAME)
            .add("maxcount", maxCount.toString())
            .add("terminalType", "0")
    }

    private fun executeRequest(
        body: FormBody,
        label: String,
        transport: MutableList<String>? = null
    ): String? {
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(body)
            .header("User-Agent", "AutoLyrics/${BuildConfig.VERSION_NAME} (Android)")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            .build()

        debugLog("request started: $label")

        return try {
            client.newCall(request).execute().use { response ->
                debugLog(
                    "HTTP ${response.code}; contentType=${response.header("Content-Type").orEmpty()}; " +
                        "query=$label"
                )
                if (!response.isSuccessful) {
                    transport?.add("HTTP ${response.code}")
                    debugLog("request rejected by HTTP layer: $label")
                    return@use null
                }

                val xml = response.body?.string().orEmpty()
                debugLog(
                    "response bytes=${xml.toByteArray(Charsets.UTF_8).size}; " +
                        "${responseSummary(xml)}; query=$label"
                )

                val parseError = xmlParseError(xml)
                if (parseError != null) {
                    transport?.add("parse: xml")
                    debugLog("outer XML parse error: $parseError")
                    return@use null
                }
                xml
            }
        } catch (e: Exception) {
            transport?.add("network: ${e.javaClass.simpleName}")
            debugLog("request failed: ${e.javaClass.simpleName}: ${e.message.orEmpty()}; query=$label")
            null
        }
    }

    /** Kept for focused parser tests and compatibility with the initial provider implementation. */
    internal fun parseApiResponse(xml: String): PetitLyricsResult? {
        val candidate = parseCandidates(xml).firstOrNull { it.lyricsType == 3 } ?: return null
        return decodeCandidate(candidate)
    }

    internal fun parseCandidates(xml: String): List<PetitLyricsCandidate> {
        if (xml.isBlank()) return emptyList()
        val document = parseXml(xml) ?: return emptyList()
        val songs = document.getElementsByTagName("song")
        if (songs.length == 0) return emptyList()

        val result = ArrayList<PetitLyricsCandidate>(songs.length)
        for (i in 0 until songs.length) {
            val song = songs.item(i) as? Element ?: continue
            val lyricsType = childText(song, "lyricsType")?.toIntOrNull() ?: continue
            val lyricsData = childText(song, "lyricsData")?.takeIf { it.isNotBlank() } ?: continue

            result += PetitLyricsCandidate(
                lyricsId = childText(song, "lyricsId")?.takeIf { it.isNotBlank() },
                title = childText(song, "title").orEmpty(),
                artist = childText(song, "artist").orEmpty(),
                album = childText(song, "album").orEmpty(),
                lyricsType = lyricsType,
                lyricsData = lyricsData,
                durationSec = childText(song, "duration")?.toDoubleOrNull()
                    ?: childText(song, "trackDuration")?.toDoubleOrNull()
            )
        }
        return result
    }

    internal fun selectBestCandidate(
        candidates: List<PetitLyricsCandidate>,
        requestedTitle: String,
        requestedArtist: String,
        requestedAlbum: String,
        artistQueryCorroborated: Boolean = false
    ): PetitLyricsCandidate? {
        return rankCandidates(
            candidates = candidates,
            requestedTitle = requestedTitle,
            requestedArtist = requestedArtist,
            requestedAlbum = requestedAlbum,
            artistQueryCorroborated = artistQueryCorroborated
        ).firstOrNull()
    }

    private fun rankCandidates(
        candidates: List<PetitLyricsCandidate>,
        requestedTitle: String,
        requestedArtist: String,
        requestedAlbum: String,
        artistQueryCorroborated: Boolean = false
    ): List<PetitLyricsCandidate> {
        val track = TrackInfo(
            title = requestedTitle,
            artist = requestedArtist,
            album = requestedAlbum,
            durationMs = 0L
        )

        return candidates.mapIndexedNotNull { index, candidate ->
            val evidencedCandidate = candidate.copy(
                artistQueryCorroborated = candidate.artistQueryCorroborated || artistQueryCorroborated
            )
            val normalized = LyricsProviderCandidate(
                provider = "PetitLyrics",
                title = evidencedCandidate.title.ifBlank { requestedTitle },
                artist = evidencedCandidate.artist,
                album = evidencedCandidate.album,
                durationSec = null,
                lines = listOf(LyricLine(0L, "candidate")),
                status = LyricsStatus.FOUND,
                source = "PetitLyrics · candidate",
                syncKind = when (evidencedCandidate.lyricsType) {
                    3 -> LyricsProviderCandidate.SyncKind.WORD_SYNC
                    2 -> LyricsProviderCandidate.SyncKind.LINE_SYNC
                    else -> LyricsProviderCandidate.SyncKind.PLAIN
                },
                artistQueryCorroborated = evidencedCandidate.artistQueryCorroborated
            )
            val score = LyricsProviderResolver.metadataScore(track, normalized)
                ?: return@mapIndexedNotNull null
            if (score < MIN_PROVIDER_METADATA_SCORE) return@mapIndexedNotNull null
            Triple(evidencedCandidate, score, index)
        }
            .sortedWith(
                compareByDescending<Triple<PetitLyricsCandidate, Double, Int>> { it.second }
                    .thenByDescending { syncPreference(it.first.lyricsType) }
                    .thenBy { it.third }
            )
            .map { it.first }
    }

    private fun syncPreference(lyricsType: Int): Int = when (lyricsType) {
        3 -> 2
        2 -> 1
        else -> 0
    }

    internal fun parseWordSyncPayload(xml: String): List<LyricLine> {
        val document = parseXml(xml) ?: return emptyList()
        val lineNodes = document.getElementsByTagName("line")
        val lines = ArrayList<LyricLine>(lineNodes.length)

        for (i in 0 until lineNodes.length) {
            val line = lineNodes.item(i) as? Element ?: continue
            val wordNodes = line.getElementsByTagName("word")
            if (wordNodes.length == 0) continue

            var lineStartMs: Long? = null
            val words = ArrayList<LyricWord>(wordNodes.length)

            for (wordIndex in 0 until wordNodes.length) {
                val word = wordNodes.item(wordIndex) as? Element ?: continue
                val rawStartMs = childText(word, "starttime")?.toLongOrNull() ?: continue
                val startMs = rawStartMs.coerceAtLeast(0L)
                if (lineStartMs == null) lineStartMs = startMs

                val endTimeMs = childText(word, "endtime")
                    ?.toLongOrNull()
                    ?.takeIf { it >= startMs }
                val wordText = childTextRaw(word, "wordstring").orEmpty()

                // Empty wordstrings are used by PetitLyrics for timed blank lines.
                // Keep their line timestamp, but do not create an invisible karaoke
                // token. Non-empty whitespace is meaningful and must be preserved.
                if (wordText.isNotEmpty()) {
                    words += LyricWord(
                        timeMs = startMs,
                        text = wordText,
                        endTimeMs = endTimeMs
                    )
                }
            }

            val startMs = lineStartMs ?: continue
            val lineText = childTextRaw(line, "linestring").orEmpty().ifBlank { "♪" }

            lines += LyricLine(
                timeMs = startMs,
                text = lineText,
                words = words
            )
        }

        return LyricDisplay.stripNonLyric(
            lines.sortedBy { it.timeMs }
        )
            .distinctBy { it.timeMs to it.text }
    }

    internal fun decodeLineSyncPayload(
        lineSyncBase64: String,
        plainTextBase64: String
    ): List<LyricLine> {
        val encrypted = decodeBase64(lineSyncBase64) ?: return emptyList()
        val plainBytes = decodeBase64(plainTextBase64) ?: return emptyList()
        if (encrypted.size < 0x3c || encrypted.size < 0xce) return emptyList()

        val lineCountLong = readUInt32Le(encrypted, 0x38) ?: return emptyList()
        if (lineCountLong <= 0L || lineCountLong > 10_000L) return emptyList()
        val lineCount = lineCountLong.toInt()
        if (0xcc + lineCount * 2 > encrypted.size) return emptyList()

        var protectionKey = readUInt16Le(encrypted, 0x1a) ?: return emptyList()
        val switchKey = encrypted.getOrNull(0x19)?.toInt()?.and(0xff) != 0
        if (switchKey) protectionKey = permuteProtectionKey(protectionKey)

        val plain = plainBytes.toString(Charsets.UTF_8)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val textLines = plain.split('\n')

        val lines = ArrayList<LyricLine>(lineCount)
        var epoch = 0L
        var previousCs = -1L

        for (lineIndex in 0 until lineCount) {
            val raw = readUInt16Le(encrypted, 0xcc + lineIndex * 2) ?: break
            val decodedModulo = raw xor protectionKey
            var timeCs = decodedModulo.toLong() + epoch * 65_536L
            if (previousCs >= 0L && timeCs < previousCs) {
                epoch += 1L
                timeCs = decodedModulo.toLong() + epoch * 65_536L
            }
            previousCs = timeCs

            val text = textLines.getOrNull(lineIndex).orEmpty().ifBlank { "♪" }
            lines += LyricLine(
                timeMs = timeCs * 10L,
                text = text
            )
        }

        return LyricDisplay.stripNonLyric(
            lines.filter { it.timeMs >= 0L }
        )
            .distinctBy { it.timeMs to it.text }
    }

    private fun permuteProtectionKey(key: Int): Int {
        return (
            (key and 0x0003) or
                ((key and 0x000c) shl 2) or
                ((key and 0x0030) shr 2) or
                ((key and 0x00c0) shl 2) or
                ((key and 0x0300) shr 2) or
                ((key and 0x0c00) shl 2) or
                ((key and 0x3000) shr 2) or
                (key and 0xc000)
            ) and 0xffff
    }

    private fun readUInt16Le(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 1 >= bytes.size) return null
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun readUInt32Le(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 3 >= bytes.size) return null
        return (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
    }

    private fun decodeBase64(value: String): ByteArray? {
        return try {
            Base64.getMimeDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun responseSummary(xml: String): String {
        val document = parseXml(xml) ?: return "XML=invalid"

        fun firstText(vararg names: String): String? {
            for (name in names) {
                val nodes = document.getElementsByTagName(name)
                if (nodes.length > 0) {
                    val value = nodes.item(0)?.textContent?.trim()
                    if (!value.isNullOrBlank()) return value
                }
            }
            return null
        }

        val status = firstText("status", "statusCode", "resultCode") ?: "?"
        val matched = firstText("matchedCount") ?: "?"
        val returned = firstText("returnedCount") ?: "?"
        val lyricsType = firstText("lyricsType") ?: "?"
        val songs = document.getElementsByTagName("song").length

        return "status=$status, matchedCount=$matched, returnedCount=$returned, songs=$songs, lyricsType=$lyricsType"
    }

    private fun stringSimilarity(left: String, right: String): Double {
        val a = normalizeForMatch(left)
        val b = normalizeForMatch(right)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0

        val compactA = a.replace(" ", "")
        val compactB = b.replace(" ", "")
        if (compactA == compactB) return 1.0
        if (compactA.length < 2 || compactB.length < 2) return 0.0

        val leftCounts = HashMap<String, Int>()
        for (i in 0 until compactA.length - 1) {
            val gram = compactA.substring(i, i + 2)
            leftCounts[gram] = (leftCounts[gram] ?: 0) + 1
        }
        val rightCounts = HashMap<String, Int>()
        for (i in 0 until compactB.length - 1) {
            val gram = compactB.substring(i, i + 2)
            rightCounts[gram] = (rightCounts[gram] ?: 0) + 1
        }

        var overlap = 0
        for ((gram, count) in leftCounts) {
            overlap += min(count, rightCounts[gram] ?: 0)
        }
        val dice = (2.0 * overlap) /
            ((compactA.length - 1) + (compactB.length - 1)).toDouble()

        val containment = if (compactA.contains(compactB) || compactB.contains(compactA)) {
            min(compactA.length, compactB.length).toDouble() /
                maxOf(compactA.length, compactB.length).toDouble()
        } else 0.0

        return maxOf(dice, containment * 0.92).coerceIn(0.0, 1.0)
    }

    private fun normalizeForMatch(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun xmlParseError(xml: String): String? {
        if (xml.isBlank()) return "empty response"
        return try {
            newDocumentBuilder().parse(
                ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
            )
            null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
        }
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private fun newDocumentBuilder() = DocumentBuilderFactory.newInstance().let { factory ->
        // Android's bundled JAXP implementation does not support every optional
        // DocumentBuilderFactory property. Apply parser hardening opportunistically
        // without allowing an unsupported optional property to abort parsing.
        runCatching { factory.isNamespaceAware = false }
        runCatching { factory.isXIncludeAware = false }
        runCatching { factory.isExpandEntityReferences = false }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.newDocumentBuilder()
    }

    private fun parseXml(xml: String) = try {
        newDocumentBuilder().parse(
            ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))
        )
    } catch (_: Exception) {
        null
    }

    private fun childText(parent: Element, tagName: String): String? {
        return childTextRaw(parent, tagName)?.trim()
    }

    private fun childTextRaw(parent: Element, tagName: String): String? {
        val nodes = parent.getElementsByTagName(tagName)
        if (nodes.length == 0) return null
        return nodes.item(0)?.textContent
    }
}
