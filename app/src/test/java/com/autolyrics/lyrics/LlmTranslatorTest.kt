package com.autolyrics.lyrics

import com.autolyrics.model.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmTranslatorTest {

    private val track = TrackInfo(
        title = "Blue Spring",
        artist = "YOASOBI",
        album = "EP",
        durationMs = 190_000L
    )

    @Test
    fun parsesPlainJsonArray() {
        val parsed = LlmTranslator.parseTranslationArray("""["one","two"]""", 2)
        assertEquals(listOf("one", "two"), parsed)
    }

    @Test
    fun parsesFencedJsonArray() {
        val raw = """
            Here you go:
            ```json
            ["one", "two", "three"]
            ```
        """.trimIndent()
        assertEquals(listOf("one", "two", "three"), LlmTranslator.parseTranslationArray(raw, 3))
    }

    @Test
    fun rejectsWrongLineCount() {
        assertNull(LlmTranslator.parseTranslationArray("""["one","two"]""", 3))
    }

    @Test
    fun rejectsNonStringElements() {
        assertNull(LlmTranslator.parseTranslationArray("""["one",2]""", 2))
    }

    @Test
    fun numberedLinesFallbackRecoversWhenJsonIsAbsent() {
        val raw = "1. hello\n2. world"
        assertEquals(listOf("hello", "world"), LlmTranslator.parseTranslationArray(raw, 2))
    }

    @Test
    fun numberedLinesFallbackRejectsGapsAndMergedLines() {
        assertNull(LlmTranslator.numberedLinesFallback("1. hello\n3. world", 2))
        assertNull(LlmTranslator.numberedLinesFallback("hello\nworld", 2))
        assertNull(LlmTranslator.numberedLinesFallback("1. hello\n2. world", 3))
    }

    @Test
    fun numberedLinesFallbackStripsNumberingVariants() {
        assertEquals(
            listOf("hello", "world", "again"),
            LlmTranslator.numberedLinesFallback("1) hello\n2. world\n3] again", 3)
        )
    }

    @Test
    fun chunksSkipBlankAndNoteOnlyLines() {
        val texts = listOf("a", "", "♪", "b")
        val chunks = LlmTranslator.buildChunks(texts, maxChars = 1_000)
        assertEquals(listOf(listOf(0, 3)), chunks)
    }

    @Test
    fun chunksSplitOnCharBudget() {
        val texts = listOf("x".repeat(40), "y".repeat(40), "z".repeat(40))
        val chunks = LlmTranslator.buildChunks(texts, maxChars = 100)
        assertEquals(listOf(listOf(0, 1), listOf(2)), chunks)
    }

    @Test
    fun oversizeSingleLineStillGetsOwnChunk() {
        val texts = listOf("x".repeat(500), "short")
        val chunks = LlmTranslator.buildChunks(texts, maxChars = 100)
        assertEquals(listOf(listOf(0), listOf(1)), chunks)
    }

    @Test
    fun endpointResolutionAppendsChatCompletionsOnce() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            LlmTranslator.endpointFor("https://api.openai.com/v1")
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            LlmTranslator.endpointFor("https://api.openai.com/v1/")
        )
        assertEquals(
            "https://gw.example.com/chat/completions",
            LlmTranslator.endpointFor("https://gw.example.com/chat/completions")
        )
    }

    @Test
    fun bareHostGetsSchemeAndV1Path() {
        assertEquals(
            "https://api.openai.com/v1",
            LlmTranslator.normalizeBaseUrl("api.openai.com")
        )
        assertEquals(
            "https://llm.example.com:8443/v1",
            LlmTranslator.normalizeBaseUrl("llm.example.com:8443/")
        )
        assertEquals(
            "http://192.168.1.5:11434/v1",
            LlmTranslator.normalizeBaseUrl("http://192.168.1.5:11434")
        )
    }

    @Test
    fun explicitPathsArePreservedAndNormalizeIsIdempotent() {
        assertEquals(
            "https://gw.example.com/llm-proxy",
            LlmTranslator.normalizeBaseUrl("https://gw.example.com/llm-proxy/")
        )
        assertEquals(
            "http://host.lan:1234/v1",
            LlmTranslator.normalizeBaseUrl(LlmTranslator.normalizeBaseUrl("http://host.lan:1234"))
        )
        assertEquals(
            "https://host.lan:1234/v1",
            LlmTranslator.normalizeBaseUrl(LlmTranslator.normalizeBaseUrl("host.lan:1234"))
        )
        assertEquals("", LlmTranslator.normalizeBaseUrl("   "))
    }

    @Test
    fun promptsCarryLineCountAndSongContext() {
        val system = LlmTranslator.buildSystemPrompt(count = 7, source = "ja", target = "en")
        assertTrue(system.contains("exactly 7 strings"))
        assertTrue(system.contains("Never sanitize profanity"))

        val user = LlmTranslator.buildUserPrompt(track, listOf("あのね"), "ja", "en")
        assertTrue(user.contains("Blue Spring"))
        assertTrue(user.contains("YOASOBI"))
        assertTrue(user.contains("JSON array of 1 strings"))
        assertTrue(user.contains("あのね"))
    }

    @Test
    fun userPromptOmitsMissingSongHeader() {
        val bare = track.copy(title = "", artist = "")
        val user = LlmTranslator.buildUserPrompt(bare, listOf("line"), "ja", "en")
        assertFalse(user.startsWith("Song:"))
    }

    @Test
    fun configIsUsableWithoutApiKey() {
        assertTrue(
            LlmTranslator.isUsable(
                LlmTranslator.LlmConfig(
                    baseUrl = "http://192.168.1.5:11434/v1",
                    apiKey = "",
                    model = "qwen2.5:14b"
                )
            )
        )
    }

    @Test
    fun configNeedsUrlAndModel() {
        assertFalse(
            LlmTranslator.isUsable(
                LlmTranslator.LlmConfig(baseUrl = "https://x.example/v1", apiKey = "k", model = "")
            )
        )
        assertFalse(
            LlmTranslator.isUsable(
                LlmTranslator.LlmConfig(baseUrl = "", apiKey = "", model = "m")
            )
        )
    }

    @Test
    fun modelsUrlTargetsSiblingModelsRoute() {
        assertEquals(
            "https://api.openai.com/v1/models",
            LlmTranslator.modelsUrl("https://api.openai.com/v1")
        )
        assertEquals(
            "http://127.0.0.1:1234/v1/models",
            LlmTranslator.modelsUrl("http://127.0.0.1:1234/v1/")
        )
        assertEquals(
            "https://gw.example.com/models",
            LlmTranslator.modelsUrl("https://gw.example.com/chat/completions")
        )
    }

    @Test
    fun parsesOpenAIModelListing() {
        val body = """
            {"data":[{"id":"gpt-4o-mini"},{"id":"gpt-4o"},{"id":" "},{"name":"no-id"}],"object":"list"}
        """.trimIndent()
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), LlmTranslator.parseModelIds(body))
    }

    @Test
    fun rejectsUnusableModelListings() {
        assertNull(LlmTranslator.parseModelIds("""{"models":["a"]}"""))
        assertNull(LlmTranslator.parseModelIds("""{"data":[]}"""))
        assertNull(LlmTranslator.parseModelIds("not json"))
    }
}
