package com.autolyrics.util

import android.content.Context
import com.autolyrics.model.LyricLine
import com.autolyrics.lyrics.TranslationLanguages

/**
 * Offline Simplified/Traditional Chinese conversion, applying OpenCC's
 * two-stage algorithm over the official OpenCC dictionaries (Apache-2.0;
 * see scripts/build_chinese_dicts.py): a greedy longest-match phrase pass
 * over ~49k context pairs — which is what resolves the one-to-many
 * characters (干→幹/乾/干, 后→後/后, …) correctly — then a per-character
 * fallback table whose first candidate is the most common mapping.
 *
 * Every dictionary pair is guaranteed same-length at data-build time, so
 * conversion is index-preserving: karaoke token spans, marker derivation
 * and line counts ride through untouched.
 *
 * Japanese is untouchable by construction: callers only convert payloads
 * whose selected target is a Chinese form, and both [detect] and [convert]
 * hard-veto any text containing kana (Japanese kanji would otherwise be
 * rewritten).
 */
object ChineseVariant {

    private const val ASSET_DIR = "chinese/"
    private const val MAX_PHRASE_LEN = 12

    internal class Tables(
        val phrases: Map<String, String>,
        val chars: Map<Char, String>,
        /** Chars that only occur in simplified writing (identity pairs are pre-filtered). */
        val hansMarkers: Set<Char>,
        /** Chars that only occur in traditional writing. */
        val hantMarkers: Set<Char>
    )

    @Volatile
    private var simplifiedToTraditional: Tables? = null

    @Volatile
    private var traditionalToSimplified: Tables? = null

    /** Loads both directions once from assets; safe to call from any thread. */
    fun ensureLoaded(context: Context) {
        if (simplifiedToTraditional == null) {
            synchronized(this) {
                if (simplifiedToTraditional == null) {
                    val (stPhrases, stChars) = readPairs(context, "stphrases.txt", "stcharacters.txt")
                    val (tsPhrases, tsChars) = readPairs(context, "tsphrases.txt", "tscharacters.txt")
                    // The data build drops identity character pairs, so every
                    // key of each table is a form-exclusive marker.
                    val hans = stChars.keys
                    val hant = tsChars.keys
                    simplifiedToTraditional = Tables(stPhrases, stChars, hans, hant)
                    traditionalToSimplified = Tables(tsPhrases, tsChars, hans, hant)
                }
            }
        }
    }

    private fun readPairs(
        context: Context,
        phraseFile: String,
        charFile: String
    ): Pair<Map<String, String>, Map<Char, String>> {
        val phrases = LinkedHashMap<String, String>()
        context.assets.open(ASSET_DIR + phraseFile).bufferedReader().use { reader ->
            reader.forEachLine { line ->
                val pair = line.split('\t')
                if (pair.size == 2) phrases[pair[0]] = pair[1]
            }
        }
        val chars = HashMap<Char, String>()
        context.assets.open(ASSET_DIR + charFile).bufferedReader().use { reader ->
            reader.forEachLine { line ->
                val pair = line.split('\t')
                if (pair.size == 2 && pair[0].length == 1) chars[pair[0][0]] = pair[1]
            }
        }
        return phrases to chars
    }

    /** Kana anywhere vetoes both detection and conversion (Japanese guard). */
    internal fun hasKana(text: String): Boolean = text.any {
        it in '\u3040'..'\u30ff' || it in '\uff66'..'\uff9f'
    }

    /**
     * Which written form [text] is written in, or null when nothing decides
     * it (Latin-only, kana-guarded Japanese, or balanced/mixed text). The
     * dominant marker side wins; a tie reports null (leave text alone).
     */
    internal fun detect(text: String, tables: Tables?): String? {
        if (tables == null || hasKana(text)) return null
        var hans = 0
        var hant = 0
        for (c in text) {
            if (c in tables.hansMarkers) hans++
            if (c in tables.hantMarkers) hant++
        }
        return when {
            hans == hant -> null
            hans > hant -> TranslationLanguages.SIMPLIFIED_CHINESE
            else -> TranslationLanguages.TRADITIONAL_CHINESE
        }
    }

    /**
     * Unconditional trad->simp fold for COMPARISON use (header matching):
     * unlike [applyTo] there is no detect gate — the caller wants both
     * written forms to land on one key. Kana still vetoes (Japanese guard).
     */
    fun toSimplified(text: String): String = convert(text, traditionalToSimplified)

    /** OpenCC order: greedy longest phrase match first, then per-char fallback. */
    internal fun convert(text: String, tables: Tables?): String {
        if (tables == null || hasKana(text) || text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            var matched = false
            var len = minOf(MAX_PHRASE_LEN, text.length - i)
            while (len >= 2) {
                val hit = tables.phrases[text.substring(i, i + len)]
                if (hit != null) {
                    out.append(hit)
                    i += len
                    matched = true
                    break
                }
                len--
            }
            if (!matched) {
                out.append(tables.chars[text[i]] ?: text[i])
                i++
            }
        }
        return out.toString()
    }

    /**
     * Converts [lines] into [target] ([TranslationLanguages.SIMPLIFIED_CHINESE]
     * or [TRADITIONAL_CHINESE]) when the text is detectably written in the
     * OTHER form; otherwise returns the list untouched. Token texts convert
     * char-index-preservingly, so word timings and spans stay valid.
     */
    fun applyTo(lines: List<LyricLine>, target: String): List<LyricLine> =
        applyTo(lines, target, simplifiedToTraditional, traditionalToSimplified)

    /** Table-injection seam for pure JVM tests of the [applyTo] logic. */
    internal fun applyTo(
        lines: List<LyricLine>,
        target: String,
        toTraditionalTables: Tables?,
        toSimplifiedTables: Tables?
    ): List<LyricLine> {
        val toTraditional = target == TranslationLanguages.TRADITIONAL_CHINESE
        val tables = if (toTraditional) toTraditionalTables else toSimplifiedTables
        val other = if (toTraditional) {
            TranslationLanguages.SIMPLIFIED_CHINESE
        } else {
            TranslationLanguages.TRADITIONAL_CHINESE
        }
        return lines.map { line ->
            val sample = line.text + line.words.joinToString("") { it.text }
            if (detect(sample, tables) != other) {
                line
            } else {
                line.copy(
                    text = convert(line.text, tables),
                    words = line.words.map { word -> word.copy(text = convert(word.text, tables)) }
                )
            }
        }
    }

    /** Pure core for tests: build tables from already-read pair lines. */
    internal fun buildTables(
        phraseLines: List<String>,
        charLines: List<String>,
        hansMarkers: Set<Char>,
        hantMarkers: Set<Char>
    ): Tables {
        val phrases = LinkedHashMap<String, String>()
        phraseLines.forEach { line ->
            val pair = line.split('\t')
            if (pair.size == 2) phrases[pair[0]] = pair[1]
        }
        val chars = HashMap<Char, String>()
        charLines.forEach { line ->
            val pair = line.split('\t')
            if (pair.size == 2 && pair[0].length == 1) chars[pair[0][0]] = pair[1]
        }
        return Tables(phrases, chars, hansMarkers, hantMarkers)
    }
}
