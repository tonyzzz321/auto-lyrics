package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine
import com.autolyrics.model.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranslationCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val track = TrackInfo(
        title = "Blue Spring",
        artist = "YOASOBI",
        album = "EP",
        durationMs = 190_400L
    )

    private val lines = listOf(
        LyricLine(0L, "あのね"),
        LyricLine(1_000L, "ずっと言えなかった"),
        LyricLine(2_000L, "♪")
    )

    private val result = TranslationResult(
        translatedLines = listOf("Listen", "I could never say it", ""),
        detectedLanguage = "ja",
        targetLanguage = "en"
    )

    @Test
    fun roundTripStoresAndReturnsTranslation() {
        val cache = TranslationCache(tempFolder.newFolder())

        cache.put(track, lines, result, engine = "llm", model = "Test-Model")

        val cached = cache.get(track, lines, "en", "llm", "test-model")
        assertEquals(listOf("Listen", "I could never say it", ""), cached?.lines)
        assertEquals("ja", cached?.detectedLanguage)
        assertEquals("en", cached?.targetLanguage)
        assertEquals("llm", cached?.engine)
    }

    @Test
    fun changedLyricTextInvalidatesEntry() {
        val cache = TranslationCache(tempFolder.newFolder())
        cache.put(track, lines, result, engine = "llm", model = "m")

        val refreshed = lines.toMutableList().apply {
            set(1, LyricLine(1_000L, "ずっと言えなかったよ"))
        }

        assertNull(cache.get(track, refreshed, "en", "llm", "m"))
    }

    @Test
    fun changedTargetEngineOrModelInvalidatesEntry() {
        val cache = TranslationCache(tempFolder.newFolder())
        cache.put(track, lines, result, engine = "llm", model = "m")

        assertNull(cache.get(track, lines, "ja", "llm", "m"))
        assertNull(cache.get(track, lines, "en", "on_device", "m"))
        assertNull(cache.get(track, lines, "en", "llm", "other-model"))
    }

    @Test
    fun differentTrackDoesNotHitEntry() {
        val cache = TranslationCache(tempFolder.newFolder())
        cache.put(track, lines, result, engine = "llm", model = "m")

        val other = track.copy(title = "Another Song")
        assertNull(cache.get(other, lines, "en", "llm", "m"))
    }

    @Test
    fun corruptedCacheFileIsRejectedAndRemoved() {
        val dir = tempFolder.newFolder()
        val cache = TranslationCache(dir)
        cache.put(track, lines, result, engine = "llm", model = "m")

        val file = checkNotNull(dir.listFiles()?.firstOrNull())
        file.writeText("{not json")

        assertNull(cache.get(track, lines, "en", "llm", "m"))
        assertEquals(emptyArray<File>(), dir.listFiles()?.filter { it.name.endsWith(".json") }?.toTypedArray())
    }

    @Test
    fun sameEngineAndTextAcrossTracksProduceDistinctFiles() {
        val dir = tempFolder.newFolder()
        val cache = TranslationCache(dir)

        cache.put(track, lines, result, engine = "llm", model = "m")
        cache.put(track.copy(title = "Other"), lines, result, engine = "llm", model = "m")

        assertEquals(2, dir.listFiles()?.size)
    }
}
