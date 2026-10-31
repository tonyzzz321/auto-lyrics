package com.autolyrics.lyrics

import android.content.SharedPreferences
import android.util.Log
import com.autolyrics.model.LyricLine
import com.autolyrics.model.TrackInfo
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Remote translation engine backed by any OpenAI-compatible
 * `/chat/completions` endpoint. Lyric lines are sent as JSON arrays in
 * size-bounded chunks so the model must return one string per input line.
 */
object LlmTranslator : TranslationEngineUi {

    const val ENGINE_ON_DEVICE = "on_device"
    const val ENGINE_LLM = "llm"

    const val PREF_ENGINE_KEY = "translation_engine"
    const val PREF_LLM_BASE_URL = "llm_base_url"
    const val PREF_LLM_MODEL = "llm_model"
    const val PREF_LLM_API_KEY = "llm_api_key"

    private const val TAG = "LlmTranslator"
    private const val MAX_SOURCE_CHARS_PER_REQUEST = 6_000
    private const val MAX_REPAIR_RETRIES = 2
    private const val TEMPERATURE = 0.2
    private const val MAX_ERROR_BODY_CHARS = 180
    private const val NOT_CONFIGURED_REASON =
        "Remote LLM not configured · set base URL and model in Translation Engine settings"

    data class LlmConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String
    )

    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val _uiState = MutableStateFlow(LyricsTranslator.UiState())
    override val uiState: StateFlow<LyricsTranslator.UiState> = _uiState.asStateFlow()

    private data class TranslationRequest(
        val sourceLanguage: String,
        val targetLanguage: String
    )

    private data class RetryState(
        val phase: LyricsTranslator.Phase,
        val error: String
    )

    private val translationRetryRequired = ConcurrentHashMap<TranslationRequest, RetryState>()

    fun isLlmEngine(prefs: SharedPreferences): Boolean =
        prefs.getString(PREF_ENGINE_KEY, ENGINE_ON_DEVICE) == ENGINE_LLM

    fun loadConfig(prefs: SharedPreferences): LlmConfig? {
        val config = LlmConfig(
            baseUrl = normalizeBaseUrl(prefs.getString(PREF_LLM_BASE_URL, "").orEmpty()),
            apiKey = prefs.getString(PREF_LLM_API_KEY, "").orEmpty().trim(),
            model = prefs.getString(PREF_LLM_MODEL, "").orEmpty().trim()
        )
        return config.takeIf { isUsable(it) }
    }

    /**
     * Accepts bare hosts like "llm.example.com" or "192.168.1.5:11434" and fills in
     * the usual https scheme and /v1 API path; explicit URLs pass through untouched.
     */
    internal fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim().removeSuffix("/")
        if (url.isEmpty()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        val afterScheme = url.substringAfter("://")
        val hasPath = afterScheme.contains('/') &&
            afterScheme.indexOf('/') < afterScheme.length - 1
        if (!hasPath) url += "/v1"
        return url
    }

    /** The API key is optional: self-hosted endpoints often need none. */
    fun isUsable(config: LlmConfig): Boolean =
        config.baseUrl.isNotBlank() && config.model.isNotBlank()

    override fun resetUiState() {
        _uiState.value = LyricsTranslator.UiState()
    }

    override fun publishReady(sourceLanguage: String, targetLanguage: String) {
        _uiState.value = LyricsTranslator.UiState(
            phase = LyricsTranslator.Phase.READY,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage
        )
    }

    fun publishNotConfigured(targetLanguage: String) {
        _uiState.value = LyricsTranslator.UiState(
            phase = LyricsTranslator.Phase.TRANSLATION_FAILED,
            targetLanguage = targetLanguage,
            error = NOT_CONFIGURED_REASON
        )
    }

    override fun prepareManualRetry() {
        val current = _uiState.value
        val source = current.sourceLanguage ?: run {
            resetUiState()
            return
        }
        val request = TranslationRequest(
            sourceLanguage = source,
            targetLanguage = TranslationLanguages.normalizeTargetLanguage(current.targetLanguage)
        )
        if (translationRetryRequired.remove(request) != null) {
            Log.d(TAG, "manual retry enabled; clearing translation retry gate for $request")
        }
        resetUiState()
    }

    suspend fun translateLines(
        lines: List<LyricLine>,
        targetLanguage: String,
        config: LlmConfig,
        track: TrackInfo
    ): TranslationResult? {
        val target = TranslationLanguages.normalizeTargetLanguage(targetLanguage)
        Log.d(TAG, "translateLines start: lines=${lines.size} target=$target model=${config.model}")
        _uiState.value = LyricsTranslator.UiState(
            phase = LyricsTranslator.Phase.DETECTING_LANGUAGE,
            targetLanguage = target
        )

        val texts = lines.map { it.text.trim() }
        val sampleText = texts
            .filter { it.isNotBlank() && it != "♪" }
            .take(5)
            .joinToString("\n")

        if (sampleText.isBlank()) {
            return failTranslation(
                request = null,
                targetLanguage = target,
                reason = "No translatable lyric text"
            )
        }

        val detectedLanguage = LyricsTranslator.detectLanguage(sampleText)
            ?: return failTranslation(request = null, targetLanguage = target, reason = "Language detection failed")
        if (detectedLanguage == "und") {
            return failUnsupported(
                detected = detectedLanguage,
                target = target,
                reason = "Could not determine lyric language"
            )
        }

        val source = TranslationLanguages.normalizeLanguageTag(detectedLanguage)
            ?: return failUnsupported(
                detected = detectedLanguage,
                target = target,
                reason = "Could not normalize lyric language '$detectedLanguage'"
            )

        if (TranslationLanguages.baseLanguage(source) == TranslationLanguages.baseLanguage(target)) {
            _uiState.value = LyricsTranslator.UiState(
                phase = LyricsTranslator.Phase.ALREADY_ENGLISH,
                sourceLanguage = source,
                targetLanguage = target
            )
            return null
        }

        val request = TranslationRequest(source, target)
        translationRetryRequired[request]?.let { blocked ->
            Log.d(TAG, "automatic retry suppressed until user presses Retry: $request")
            _uiState.value = LyricsTranslator.UiState(
                phase = blocked.phase,
                sourceLanguage = source,
                targetLanguage = target,
                error = blocked.error
            )
            return null
        }

        val chunks = buildChunks(texts, MAX_SOURCE_CHARS_PER_REQUEST)
        if (chunks.isEmpty()) {
            return failTranslation(
                request = request,
                targetLanguage = target,
                reason = "No translatable lyric text"
            )
        }

        _uiState.value = LyricsTranslator.UiState(
            phase = LyricsTranslator.Phase.TRANSLATING,
            sourceLanguage = source,
            targetLanguage = target
        )

        return try {
            val translatedNulls = arrayOfNulls<String>(lines.size)
            for ((chunkPosition, chunk) in chunks.withIndex()) {
                val chunkTexts = chunk.map(texts::get)
                val translatedChunk = translateChunk(config, chunkTexts, source, target, track)
                chunk.forEachIndexed { offset, lineIndex ->
                    translatedNulls[lineIndex] = translatedChunk[offset]
                }
                Log.d(
                    TAG,
                    "chunk ${chunkPosition + 1}/${chunks.size} translated: " +
                        "lines=${chunk.size} model=${config.model}"
                )
            }

            val translated = lines.mapIndexed { index, line ->
                val text = line.text.trim()
                if (text.isBlank() || text == "♪") {
                    ""
                } else {
                    translatedNulls[index].orEmpty().ifBlank { text }
                }
            }

            translationRetryRequired.remove(request)
            _uiState.value = LyricsTranslator.UiState(
                phase = LyricsTranslator.Phase.READY,
                sourceLanguage = source,
                targetLanguage = target
            )
            TranslationResult(
                translatedLines = translated,
                detectedLanguage = source,
                targetLanguage = target
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmTranslationFailure) {
            failTranslation(
                request = request,
                targetLanguage = target,
                reason = e.message ?: "LLM translation failed",
                sourceLanguage = source
            )
        } catch (e: Exception) {
            failTranslation(
                request = request,
                targetLanguage = target,
                reason = e.localizedMessage ?: e.javaClass.simpleName,
                sourceLanguage = source
            )
        }
    }

    private class LlmTranslationFailure(reason: String) : Exception(reason)

    private suspend fun translateChunk(
        config: LlmConfig,
        chunkTexts: List<String>,
        source: String,
        target: String,
        track: TrackInfo
    ): List<String> {
        val messages = JsonArray().apply {
            add(chatMessage("system", buildSystemPrompt(chunkTexts.size, source, target)))
            add(chatMessage("user", buildUserPrompt(track, chunkTexts, source, target)))
        }

        var attempt = 0
        while (true) {
            val content = requestCompletion(config, messages)
            val parsed = parseTranslationArray(content, chunkTexts.size)
            if (parsed != null) return parsed

            if (attempt >= MAX_REPAIR_RETRIES) {
                throw LlmTranslationFailure(
                    "Model reply was not a JSON list of ${chunkTexts.size} lines " +
                        "(after $attempt repair attempt${if (attempt == 1) "" else "s"})"
                )
            }
            Log.w(TAG, "chunk reply unusable; asking model to repair (attempt ${attempt + 1})")
            messages.add(chatMessage("assistant", content))
            messages.add(
                chatMessage(
                    "user",
                    "That reply was not a JSON array of exactly ${chunkTexts.size} strings. " +
                        "Reply again with only the corrected JSON array of ${chunkTexts.size} strings."
                )
            )
            attempt++
        }
    }

    private suspend fun requestCompletion(
        config: LlmConfig,
        messages: JsonArray
    ): String {
        val payload = JsonObject().apply {
            addProperty("model", config.model)
            addProperty("temperature", TEMPERATURE)
            add("messages", messages)
        }

        val (statusCode, body) = httpExchange(
            url = endpointFor(config.baseUrl),
            apiKey = config.apiKey,
            payloadJson = gson.toJson(payload)
        )

        if (statusCode !in 200..299) {
            val detail = body.take(MAX_ERROR_BODY_CHARS).replace('\n', ' ').trim()
            throw LlmTranslationFailure("HTTP $statusCode · ${detail.ifBlank { "no error body" }}")
        }

        return try {
            JsonParser.parseString(body).asJsonObject
                .getAsJsonArray("choices").first()
                .asJsonObject.getAsJsonObject("message").get("content").asString
        } catch (e: Exception) {
            throw LlmTranslationFailure("HTTP $statusCode · response JSON did not contain message content")
        }
    }

    /** Lists model ids from the endpoint's OpenAI-compatible GET /models route. */
    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val (statusCode, body) = httpExchange(
                url = modelsUrl(normalizeBaseUrl(baseUrl)),
                apiKey = apiKey,
                payloadJson = null
            )
            if (statusCode !in 200..299) {
                val detail = body.take(MAX_ERROR_BODY_CHARS).replace('\n', ' ').trim()
                throw LlmTranslationFailure("HTTP $statusCode · ${detail.ifBlank { "no error body" }}")
            }
            parseModelIds(body) ?: throw LlmTranslationFailure(
                "HTTP $statusCode · response JSON did not contain a data[] model list"
            )
        }

    internal fun modelsUrl(baseUrl: String): String {
        var trimmed = baseUrl.trim().removeSuffix("/")
        if (trimmed.endsWith("/chat/completions")) {
            trimmed = trimmed.removeSuffix("/chat/completions")
        }
        return "$trimmed/models"
    }

    internal fun parseModelIds(body: String): List<String>? {
        return try {
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("data")
                .mapNotNull { element ->
                    element.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.get("id")
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString
                }
                .filter { it.isNotBlank() }
                .sorted()
                .takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun httpExchange(
        url: String,
        apiKey: String,
        payloadJson: String?
    ): Pair<Int, String> {
        val builder = Request.Builder().url(url)
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        if (payloadJson != null) {
            builder.header("Content-Type", "application/json")
                .post(payloadJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }

        return suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(builder.build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val code = response.code
                        val body = try {
                            response.body?.string().orEmpty()
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(e)
                            return
                        } finally {
                            response.close()
                        }
                        if (cont.isActive) cont.resume(code to body)
                    }
                }
            )
        }
    }

    private fun chatMessage(role: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

    internal fun buildSystemPrompt(count: Int, source: String, target: String): String {
        val sourceName = TranslationLanguages.englishName(source)
        val targetName = TranslationLanguages.englishName(target)
        return """
            You are a professional lyric translator. You translate song lyrics line by line.

            Rules:
            1. OUTPUT: a JSON array of exactly $count strings — one per input line, same order. No markdown, no notes, no alternates.
            2. FIDELITY: translate meaning and imagery, not words. Keep metaphors, wordplay intent, tone, register, and ambiguity of the original; do not explain, expand, moralize, or soften. Never sanitize profanity or explicit content — match the original's intensity.
            3. LINE DISCIPLINE: never merge, split, skip, or reorder lines. A line that is a non-lexical token (e.g. ♪, oh, la-la, ad-libs) is kept as-is. Parenthetical backing vocals stay parenthesized in the same line.
            4. CONSISTENCY: identical source lines must get identical translations. Keep one translation per term across the song; render names/titles in target-language convention, don't translate them.
            5. DENSITY: lyrics are compressed speech. Prefer natural, concise phrasing in $targetName; do not turn verse into prose.
            6. Preserve speaker perspective and gender markers only as strongly as the source states them.
            Input: a JSON array of $count $sourceName lyric lines. Output only the $targetName JSON array.
        """.trimIndent()
    }

    internal fun buildUserPrompt(
        track: TrackInfo,
        chunkTexts: List<String>,
        source: String,
        target: String
    ): String {
        val header = StringBuilder()
        if (track.title.isNotBlank()) {
            header.append("Song: \"${track.title}\"")
            if (track.artist.isNotBlank()) {
                header.append(" — ${track.artist}")
            }
            header.append('\n')
        }
        val lines = JsonArray().apply { chunkTexts.forEach { add(it) } }
        return header.append(
            "Translate these ${chunkTexts.size} lyric lines from " +
                "${TranslationLanguages.englishName(source)} to " +
                "${TranslationLanguages.englishName(target)}. " +
                "Return only the JSON array of ${chunkTexts.size} strings.\n\n"
        ).append(gson.toJson(lines)).toString()
    }

    internal fun parseTranslationArray(raw: String, expectedCount: Int): List<String>? {
        extractJsonArray(raw)?.let { candidate ->
            try {
                val parsed = JsonParser.parseString(candidate)
                if (parsed.isJsonArray) {
                    val array = parsed.asJsonArray
                    if (array.size() != expectedCount) return null
                    return array.map { element ->
                        val primitive = element.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                        if (primitive == null || !primitive.isString) return null
                        primitive.asString
                    }
                }
            } catch (_: Exception) {
                // fall through to the numbered-line fallback
            }
        }
        return numberedLinesFallback(raw, expectedCount)
    }

    private fun extractJsonArray(raw: String): String? {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues
            ?.get(1)
        val source = (fenced ?: raw).trim()
        val start = source.indexOf('[')
        val end = source.lastIndexOf(']')
        return if (start in 0 until end) source.substring(start, end + 1) else null
    }

    internal fun numberedLinesFallback(raw: String, expectedCount: Int): List<String>? {
        val fenced = Regex("```(?:[a-z]*)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues
            ?.get(1)
        val body = (fenced ?: raw).trim()
        if (body.contains('[')) return null

        val entries = mutableListOf<Pair<Int, String>>()
        for (rawLine in body.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val match = Regex("^(\\d{1,3})[.)\\]]?\\s(.*)$").find(line) ?: return null
            entries += match.groupValues[1].toInt() to match.groupValues[2]
        }

        if (entries.size != expectedCount) return null
        if (entries.withIndex().any { (index, entry) -> entry.first != index + 1 }) return null
        return entries.map { it.second }
    }

    internal fun buildChunks(texts: List<String>, maxChars: Int): List<List<Int>> {
        val chunks = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var budget = 0
        texts.forEachIndexed { index, text ->
            val trimmed = text.trim()
            if (trimmed.isBlank() || trimmed == "♪") return@forEachIndexed
            if (current.isNotEmpty() && budget + trimmed.length > maxChars) {
                chunks += current
                current = mutableListOf()
                budget = 0
            }
            current.add(index)
            budget += trimmed.length
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    internal fun endpointFor(baseUrl: String): String {
        val trimmed = normalizeBaseUrl(baseUrl).removeSuffix("/")
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    private fun failTranslation(
        request: TranslationRequest?,
        targetLanguage: String,
        reason: String,
        sourceLanguage: String? = null
    ): TranslationResult? {
        val source = sourceLanguage ?: _uiState.value.sourceLanguage
        Log.w(TAG, "translation failed: $reason")
        if (request != null) {
            translationRetryRequired[request] = RetryState(
                phase = LyricsTranslator.Phase.TRANSLATION_FAILED,
                error = reason
            )
        }
        _uiState.value = LyricsTranslator.UiState(
            phase = LyricsTranslator.Phase.TRANSLATION_FAILED,
            sourceLanguage = source,
            targetLanguage = targetLanguage,
            error = reason
        )
        return null
    }

    private fun failUnsupported(detected: String, target: String, reason: String): TranslationResult? {
        Log.w(TAG, "unsupported language: $reason")
        _uiState.value = LyricsTranslator.UiState(
            phase = LyricsTranslator.Phase.UNSUPPORTED_LANGUAGE,
            sourceLanguage = detected,
            targetLanguage = target,
            error = reason
        )
        return null
    }
}
