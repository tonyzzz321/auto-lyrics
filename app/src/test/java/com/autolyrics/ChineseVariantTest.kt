package com.autolyrics

import com.autolyrics.lyrics.TranslationLanguages
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import com.autolyrics.util.ChineseVariant
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chinese written-form behaviour: dictionary data integrity, the pure
 * converter/detector core, and the language-code plumbing that split "zh"
 * into Simplified/Traditional targets.
 */
class ChineseVariantTest {

    private val assetDir = File("src/main/assets/chinese")

    private fun lines(file: String): List<String> =
        File(assetDir, file).readLines().filter { it.isNotBlank() }

    private val stTables by lazy {
        ChineseVariant.buildTables(lines("stphrases.txt"), lines("stcharacters.txt"),
            charMarkers("stcharacters.txt"), charMarkers("tscharacters.txt"))
    }
    private val tsTables by lazy {
        ChineseVariant.buildTables(lines("tsphrases.txt"), lines("tscharacters.txt"),
            charMarkers("stcharacters.txt"), charMarkers("tscharacters.txt"))
    }

    private fun charMarkers(file: String): Set<Char> =
        lines(file).mapNotNull { it.split('\t').firstOrNull()?.singleOrNull() }.toSet()

    // ---- data integrity (mirrors scripts/build_chinese_dicts.py) ----

    @Test
    fun dictionaryPairsAreSameLengthAndNonBlank() {
        for (file in listOf("stphrases.txt", "stcharacters.txt", "tsphrases.txt", "tscharacters.txt")) {
            for (line in lines(file)) {
                val pair = line.split('\t')
                assertEquals("$file malformed: $line", 2, pair.size)
                assertEquals("$file length drift: $line", pair[0].length, pair[1].length)
                assertTrue("$file blank target: $line", pair[1].isNotBlank())
            }
        }
    }

    @Test
    fun characterTablesHaveNoIdentityPairs() {
        for (file in listOf("stcharacters.txt", "tscharacters.txt")) {
            for (line in lines(file)) {
                val pair = line.split('\t')
                assertEquals("$file single-char keys: $line", 1, pair[0].length)
                assertFalse("$file identity pair: $line", pair[0] == pair[1])
            }
        }
    }

    // ---- converter goldens (verified against the shipped dictionaries) ----

    @Test
    fun phrasePassProtectsAmbiguousCharacters() {
        assertEquals("皇后", ChineseVariant.convert("皇后", stTables))
        assertEquals("干戈", ChineseVariant.convert("干戈", stTables))
        assertEquals("出面", ChineseVariant.convert("出面", stTables))
        assertEquals("皇后樂隊", ChineseVariant.convert("皇后乐队", stTables))
    }

    @Test
    fun characterFallbackResolvesTheCommonReadings() {
        assertEquals("後面", ChineseVariant.convert("后面", stTables))
        assertEquals("頭髮", ChineseVariant.convert("头发", stTables))
        assertEquals("猶如巡行和匯演", ChineseVariant.convert("犹如巡行和汇演", stTables))
        assertEquals("統一", ChineseVariant.convert("统一", stTables))
    }

    @Test
    fun traditionalToSimplifiedIsUnambiguous() {
        assertEquals("后面", ChineseVariant.convert("後面", tsTables))
        assertEquals("里面", ChineseVariant.convert("裡面", tsTables))
        assertEquals("干脆", ChineseVariant.convert("乾脆", tsTables))
        assertEquals("干旱", ChineseVariant.convert("乾旱", tsTables))
        assertEquals("皇后", ChineseVariant.convert("皇后", tsTables))
    }

    // ---- detector + Japanese guard ----

    @Test
    fun detectorPicksTheDominantForm() {
        assertEquals(
            TranslationLanguages.SIMPLIFIED_CHINESE,
            ChineseVariant.detect("犹如巡行和汇演", stTables)
        )
        assertEquals(
            TranslationLanguages.TRADITIONAL_CHINESE,
            ChineseVariant.detect("猶如巡行和匯演", stTables)
        )
    }

    @Test
    fun detectorAndConverterVetoJapanese() {
        // Kanji-heavy Japanese line: the kana guard must veto BOTH paths.
        val jp = "あの頃に戻れるなら 君の隣で歌いたい"
        assertNull(ChineseVariant.detect(jp, stTables))
        assertEquals(jp, ChineseVariant.convert(jp, stTables))
    }

    @Test
    fun detectorIsSilentOnUndecidableText() {
        assertNull(ChineseVariant.detect("Never gonna give you up", stTables))
        assertNull(ChineseVariant.detect("", stTables))
    }

    // ---- applyTo: index-preserving line conversion ----

    @Test
    fun applyToConvertsOnlyTheWrongFormAndKeepsTokenGeometry() {
        val simplified = LyricLine(
            timeMs = 1000,
            text = "犹如巡行和汇演",
            words = listOf(
                LyricWord(1000, "犹如"),
                LyricWord(1500, "巡行"),
                LyricWord(2000, "和汇演")
            )
        )
        val traditional = LyricLine(2000, "猶如巡行和匯演")

        val toTraditional = ChineseVariant.applyTo(listOf(simplified, traditional),
            TranslationLanguages.TRADITIONAL_CHINESE, stTables, tsTables)
        assertEquals("猶如巡行和匯演", toTraditional[0].text)
        assertEquals(listOf("猶如", "巡行", "和匯演"), toTraditional[0].words.map { it.text })
        assertEquals(listOf(1000L, 1500L, 2000L), toTraditional[0].words.map { it.timeMs })
        assertEquals(traditional, toTraditional[1])

        // Back to Simplified: the first line is already simplified (untouched),
        // the second is traditional (converted) — form is a per-line decision.
        val back = ChineseVariant.applyTo(toTraditional,
            TranslationLanguages.SIMPLIFIED_CHINESE, stTables, tsTables)
        assertEquals(simplified, back[0])
        assertEquals("犹如巡行和汇演", back[1].text)
    }

    @Test
    fun applyToLeavesNonChinesePayloadsUntouched() {
        val lines = listOf(
            LyricLine(0, "あの頃に戻れるなら 君の隣で歌いたい"),
            LyricLine(1000, "Never gonna give you up"),
            LyricLine(2000, "♪ ♪ ♪")
        )
        assertEquals(lines, ChineseVariant.applyTo(lines,
            TranslationLanguages.TRADITIONAL_CHINESE, stTables, tsTables))
    }

    // ---- language plumbing ----

    @Test
    fun chineseVariantsNormalizeAndMigrate() {
        assertEquals(TranslationLanguages.SIMPLIFIED_CHINESE, TranslationLanguages.normalizeLanguageTag("zh"))
        assertEquals(TranslationLanguages.SIMPLIFIED_CHINESE, TranslationLanguages.normalizeLanguageTag("zh-Hans-CN"))
        assertEquals(TranslationLanguages.TRADITIONAL_CHINESE, TranslationLanguages.normalizeLanguageTag("zh-Hant"))
        assertEquals(TranslationLanguages.TRADITIONAL_CHINESE, TranslationLanguages.normalizeLanguageTag("zh-TW"))
        // Legacy stored bare "zh" target resolves to Simplified, not the default.
        assertEquals("zh-CN", TranslationLanguages.normalizeTargetLanguage("zh"))
        assertEquals("zh", TranslationLanguages.baseLanguage("zh-TW"))
        assertTrue(TranslationLanguages.isChineseTarget("zh-CN"))
        assertFalse(TranslationLanguages.isChineseTarget("ja"))
        assertEquals("ja", TranslationLanguages.normalizeTargetLanguage("ja"))
        assertEquals("en", TranslationLanguages.normalizeTargetLanguage("eo"))
    }

    @Test
    fun pickerLabelsCarryNativeTailsAndPromptsStayEnglish() {
        assertTrue(TranslationLanguages.displayName("ja").contains("日本語"))
        assertTrue(TranslationLanguages.displayName("de").contains("Deutsch"))
        assertTrue(TranslationLanguages.displayName("zh-TW").contains("繁體中文"))
        assertEquals("English", TranslationLanguages.displayName("en"))
        assertEquals("Traditional Chinese", TranslationLanguages.englishName("zh-TW"))
        assertEquals("Simplified Chinese", TranslationLanguages.englishName("zh-CN"))
        assertEquals("Japanese", TranslationLanguages.englishName("ja"))
    }

    @Test
    fun modelPacksUseTheConcreteChineseCodes() {
        assertEquals(listOf("ja", "zh-CN"),
            TranslationLanguages.requiredModelLanguages("ja", "zh-CN"))
        assertEquals(listOf("zh-TW"),
            TranslationLanguages.requiredModelLanguages("en", "zh-TW"))
    }
}
