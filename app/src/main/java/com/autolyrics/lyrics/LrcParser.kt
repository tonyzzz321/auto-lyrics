package com.autolyrics.lyrics

import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord

object LrcParser {

    // Fractional seconds are optional in the wild: files carry [mm:ss],
    // <mm:ss>, two-digit centiseconds, and three-digit milliseconds alike.
    // Requiring the fraction silently dropped word tokens mid-line, which
    // froze karaoke highlighting until the line ended.
    private val LINE_TIMESTAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:,](\d{1,3}))?]""")
    private val WORD_TIMESTAMP = Regex("""<(\d{1,3}):(\d{2})(?:[.:,](\d{1,3}))?>""")

    // Karaoke-adjacent spacing artifacts insert real spaces between CJK
    // characters. Genuine CJK prose does not separate characters with spaces,
    // so a space flanked by CJK on both sides is always noise.
    private const val CJK_CHARS =
        """\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}\u3000-\u303F\uFF01-\uFF65"""
    private val CJK_SPACE = Regex("""(?<=[$CJK_CHARS])[ \u00A0\u3000]+(?=[$CJK_CHARS])""")
    private val CJK_CHAR = Regex("""[$CJK_CHARS]""")

    private fun isCjk(c: Char): Boolean = CJK_CHAR.matches(c.toString())

    fun cleanSpacing(text: String): String = CJK_SPACE.replace(text, "")

    fun parse(lrc: String): List<LyricLine> {
        return lrc.lines()
            .flatMap { line -> parseLine(line) }
            .sortedBy { it.timeMs }
    }

    fun parseKaraoke(lrc: String): List<LyricLine> {
        return lrc.lines()
            .flatMap { line -> parseKaraokeLine(line) }
            .sortedBy { it.timeMs }
    }

    private fun parseLine(line: String): List<LyricLine> {
        val timestamps = mutableListOf<Long>()
        var remaining = line.trim()

        while (remaining.startsWith("[")) {
            val match = LINE_TIMESTAMP.find(remaining) ?: break
            if (match.range.first != 0) break
            timestamps.add(parseTimestamp(match))
            remaining = remaining.substring(match.range.last + 1)
        }

        if (timestamps.isEmpty()) return emptyList()

        val text = cleanSpacing(remaining.trim())
        return timestamps.map { ts ->
            LyricLine(ts, text.ifBlank { "♪" })
        }
    }

    private fun parseKaraokeLine(line: String): List<LyricLine> {
        val timestamps = mutableListOf<Long>()
        var remaining = line.trim()

        while (remaining.startsWith("[")) {
            val match = LINE_TIMESTAMP.find(remaining) ?: break
            if (match.range.first != 0) break
            timestamps.add(parseTimestamp(match))
            remaining = remaining.substring(match.range.last + 1)
        }

        if (timestamps.isEmpty()) return emptyList()

        // Clean before tokenizing so display text and word tokens stay
        // character-aligned: removals happen once on the raw line instead of
        // per token, where a boundary space would survive in one view only.
        remaining = cleanSpacing(remaining)

        // Some Enhanced-LRC producers insert formatting whitespace between the
        // line timestamp and the first timed-token tag. That whitespace is not
        // part of the sung lyric text, so remove it only when the prefix before
        // the first word tag contains no visible characters.
        val firstWordMatch = WORD_TIMESTAMP.find(remaining)
        if (firstWordMatch != null &&
            remaining.substring(0, firstWordMatch.range.first).isBlank()
        ) {
            remaining = remaining.substring(firstWordMatch.range.first)
        }

        val wordMatches = WORD_TIMESTAMP.findAll(remaining).toList()
        val rawWords = wordMatches.mapIndexedNotNull { index, match ->
            val textStart = match.range.last + 1
            val textEnd = wordMatches.getOrNull(index + 1)?.range?.first ?: remaining.length
            if (textStart > textEnd) return@mapIndexedNotNull null

            // Enhanced LRC often leaves the separating space attached to the
            // preceding timed token. Preserve it exactly so the full source line
            // can be reconstructed and display grouping can operate independently
            // from provider token granularity.
            val tokenText = remaining.substring(textStart, textEnd)
            if (tokenText.isEmpty()) return@mapIndexedNotNull null
            LyricWord(
                timeMs = parseTimestamp(match),
                text = tokenText
            )
        }

        // Per-character karaoke uploads put each CJK glyph in its own token
        // with a trailing space; the space is flanked by CJK across the token
        // boundary, invisible to the raw-line pass above. Drop it from the
        // preceding token so display text and word spans stay character-exact.
        val words = rawWords.mapIndexed { index, word ->
            val next = rawWords.getOrNull(index + 1)?.text?.firstOrNull { !it.isWhitespace() }
            if (next != null && isCjk(next) &&
                word.text.isNotEmpty() && word.text.last().isWhitespace() &&
                isCjk(word.text.last { !it.isWhitespace() })
            ) {
                word.copy(text = word.text.trimEnd())
            } else {
                word
            }
        }

        // Display text is exactly the concatenation of word tokens so word
        // highlight spans always map onto it.
        val fullText = if (words.isNotEmpty()) {
            words.joinToString("") { it.text }
        } else {
            WORD_TIMESTAMP.replace(remaining, "")
        }

        return timestamps.map { ts ->
            if (words.isNotEmpty() && fullText.isNotBlank()) {
                LyricLine(ts, fullText, words)
            } else {
                LyricLine(ts, fullText.ifBlank { "♪" })
            }
        }
    }

    private fun parseTimestamp(match: MatchResult): Long {
        val min = match.groupValues[1].toLong()
        val sec = match.groupValues[2].toLong()
        val msRaw = match.groupValues[3]
        val ms = when (msRaw.length) {
            0 -> 0L
            1 -> msRaw.toLong() * 100
            2 -> msRaw.toLong() * 10
            else -> msRaw.toLong()
        }
        return min * 60_000 + sec * 1_000 + ms
    }
}
