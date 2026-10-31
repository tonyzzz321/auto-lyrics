package com.autolyrics.util

import android.content.Context

/**
 * Estimated count of PRONOUNCED syllables in a lyric line - the sung
 * speech duration proxy that drives the line-timed hold in
 * [SungLine.lineEndMs]. Deliberate approximation in the spirit of
 * [AaKaraokeRows.advanceEm]: dictionary-free (except the tiny per-kanji
 * mora table), one pass over the text, never a linguistic engine.
 *
 * Script rules:
 * - Han: 1 syllable per character (exact for Chinese and Korean hanja);
 *   in lines containing kana the character is priced by the JMdict-derived
 *   [japanese/kanji_mora.txt] table (most common standalone reading's
 *   mora count, fallback 1) because Japanese kanji carry multi-mora
 *   readings;
 * - kana: 1 mora each - small ya/yu/yo merge into the preceding mora,
 *   the sokuon (small tsu) and the prolonged-sound mark each count 1;
 * - Hangul syllable blocks: 1 each (exact);
 * - Latin, Cyrillic, Greek: vowel-group counting with the classic lyrics
 *   heuristics for Latin (silent trailing -e, syllabic -le, silent -ed);
 * - Arabic long-vowel letters counted; Hebrew matres lectionis counted;
 *   other abugidas count base letters, combining marks ignored;
 * - digits: 1 each; punctuation and everything else: 0.
 */
internal object PronouncedLength {

    private const val ASSET = "japanese/kanji_mora.txt"

    @Volatile
    private var kanjiMora: Map<Char, Int> = emptyMap()

    /** Loads the kanji mora table once; a missing asset leaves the
     * uniform 1-mora fallback in place. Safe from any thread. */
    fun ensureLoaded(context: Context) {
        if (kanjiMora.isEmpty()) {
            synchronized(this) {
                if (kanjiMora.isEmpty()) {
                    val map = HashMap<Char, Int>()
                    runCatching {
                        context.assets.open(ASSET).bufferedReader().use { reader ->
                            reader.forEachLine { line ->
                                if (line.startsWith('#')) return@forEachLine
                                val pair = line.split('\t')
                                if (pair.size == 2 && pair[0].length == 1) {
                                    pair[1].toIntOrNull()?.let { map[pair[0][0]] = it }
                                }
                            }
                        }
                    }
                    kanjiMora = map
                }
            }
        }
    }

    internal fun estimate(text: String): Int = count(text, kanjiMora)

    /** Pure core with the mora table injected (tests run without assets). */
    internal fun count(text: String, kanji: Map<Char, Int>): Int {
        // Kana anywhere makes the line Japanese: kanji then price by mora.
        val japanese = text.any { isKana(it) }
        var total = 0
        var word = StringBuilder()
        var prevIsMora = false

        fun flushWord() {
            if (word.isNotEmpty()) {
                total += wordSyllables(word.toString())
                word = StringBuilder()
            }
        }

        for (ch in text) {
            when {
                isKana(ch) -> {
                    flushWord()
                    if (ch in SMALL_Y && prevIsMora) {
                        // Merges into the preceding mora (kyo = 1 mora).
                    } else {
                        total++
                        prevIsMora = true
                    }
                }
                isKanji(ch) -> {
                    flushWord()
                    total += if (japanese) kanji[ch] ?: 1 else 1
                    prevIsMora = false
                }
                isHangul(ch) -> {
                    flushWord()
                    total++
                    prevIsMora = false
                }
                ch.isDigit() || ch in '\uff10'..'\uff19' -> {
                    flushWord()
                    total++
                    prevIsMora = false
                }
                isArabicLongVowel(ch) -> {
                    flushWord()
                    total++
                    prevIsMora = false
                }
                isHebrewLetter(ch) -> {
                    flushWord()
                    if (ch in HEBREW_LONG) total++
                    prevIsMora = false
                }
                ch.code in 0x0e00..0x0e7f -> {
                    flushWord()
                    val type = Character.getType(ch.code)
                    if (type != Character.NON_SPACING_MARK.toInt() &&
                        type != Character.ENCLOSING_MARK.toInt()
                    ) {
                        total++
                    }
                    prevIsMora = false
                }
                Character.isLetter(ch) -> {
                    word.append(ch)
                    prevIsMora = false
                }
                else -> {
                    flushWord()
                    prevIsMora = false
                }
            }
        }
        flushWord()
        return total
    }

    private val SMALL_Y = setOf(
        '\u3083', '\u3085', '\u3087', '\u308E',
        '\u30E3', '\u30E5', '\u30E7', '\u30EE',
        '\uff67', '\uff68', '\uff69',
    )

    private fun isKana(ch: Char): Boolean =
        ch.code in 0x3041..0x309f || ch.code in 0x30a1..0x30ff ||
            ch.code in 0xff66..0xff9f || ch == '\u30fc'

    private fun isKanji(ch: Char): Boolean =
        ch.code in 0x4e00..0x9fff || ch.code in 0x3400..0x4dbf ||
            ch.code in 0xf900..0xfaff

    private fun isHangul(ch: Char): Boolean =
        ch.code in 0xac00..0xd7a3 || ch.code in 0x1100..0x11ff ||
            ch.code in 0x3130..0x318e

    private fun isArabicLongVowel(ch: Char): Boolean =
        ch in "اآأإىيوةؤئ"

    private fun isHebrewLetter(ch: Char): Boolean = ch.code in 0x0590..0x05ff

    private val HEBREW_LONG = setOf('\u05d0', '\u05d4', '\u05d5', '\u05d9')

    /** Vowels across the vowel-group scripts (Latin incl. y, Cyrillic,
     * Greek and their accented forms). */
    private fun isVowel(ch: Char): Boolean = when (ch.lowercaseChar()) {
        'a', 'e', 'i', 'o', 'u', 'y',
        'а', 'е', 'ё', 'и', 'о', 'у', 'ы', 'э', 'ю', 'я',
        'α', 'ε', 'η', 'ι', 'ο', 'υ', 'ω',
        'ά', 'έ', 'ή', 'ί', 'ό', 'ύ', 'ώ',
        'à', 'á', 'â', 'ä', 'ã', 'å', 'ā', 'æ', 'è', 'é', 'ê', 'ë', 'ē',
        'ě', 'ę', 'ì', 'í', 'î', 'ï', 'ī', 'ð', 'ò', 'ó', 'ô', 'ö', 'õ',
        'ø', 'œ', 'ù', 'ú', 'û', 'ü', 'ū', 'ý', 'ÿ',
        -> true
        else -> false
    }

    private fun isLatinScript(ch: Char): Boolean {
        val c = ch.lowercaseChar()
        return c in 'a'..'z' || c.code in 0xc0..0x24f || c.code in 0x1e00..0x1eff ||
            c.code in 0x2c60..0x2c7f || c.code in 0xff41..0xff5a
    }

    /** Syllable estimate for one word of letters. */
    internal fun wordSyllables(word: String): Int {
        val w = word.lowercase()
        if (w.isEmpty()) return 0
        var groups = 0
        var inVowel = false
        for (ch in w) {
            if (isVowel(ch)) {
                if (!inVowel) groups++
                inVowel = true
            } else if (Character.isLetter(ch)) {
                inVowel = false
            }
        }
        if (groups == 0) return if (w.any { Character.isLetter(it) }) 1 else 0
        val latin = w.all { isLatinScript(it) }
        if (latin) {
            // Silent trailing -e: "life", "love" - but never -ee ("see")
            // and never the only vowel group ("the").
            if (groups > 1 && w.endsWith("e") && !w.endsWith("ee")) {
                groups--
            }
            // Syllabic -le after a consonant: "table", "people".
            if (w.length >= 3 && w.endsWith("le") && !isVowel(w[w.length - 3])) {
                groups++
            }
            // Silent -ed after a voiceless consonant: "walked", "lost".
            if (groups > 1 && w.endsWith("ed") && w.length > 3 &&
                !isVowel(w[w.length - 3]) && w[w.length - 3] != 't' &&
                w[w.length - 3] != 'd'
            ) {
                groups--
            }
        }
        return groups.coerceAtLeast(1)
    }
}
