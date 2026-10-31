package com.autolyrics.util

import com.autolyrics.model.LyricLine
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * Reconstructs separators around timed lyric tokens from the provider's original
 * line text and maps fine-grained provider timing tokens onto human-readable
 * display units for karaoke highlighting.
 *
 * Provider timing is deliberately left untouched in [LyricLine.words]. A source
 * may expose a whole word, a syllable, or even a single character per timed token.
 * Rendering therefore finds the lexical range that contains the active timing
 * token and highlights that range instead of assuming one token equals one word.
 */
object LyricWordLayout {

    data class Layout(
        val prefixes: List<String>,
        val suffix: String
    )

    internal data class DisplayRange(
        val start: Int,
        val end: Int
    )

    internal data class RenderedLine(
        val text: String,
        val tokenStart: IntArray,
        val tokenEnd: IntArray
    )

    private data class TokenSpan(
        val start: Int,
        val end: Int
    )

    private val JAPANESE_SCRIPT = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff]")
    private val CJK_SCRIPT = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uac00-\\ud7af]")
    private val HAN_SCRIPT = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]")
    private val HIRAGANA_ONLY = Regex("[\\u3040-\\u309fー]+")

    fun layout(line: LyricLine): Layout {
        val words = line.words
        if (words.isEmpty()) return Layout(emptyList(), line.text)

        val prefixes = ArrayList<String>(words.size)
        var cursor = 0

        for (word in words) {
            val index = line.text.indexOf(word.text, startIndex = cursor)
            if (index < 0) return fallback(line)

            prefixes += line.text.substring(cursor, index)
            cursor = index + word.text.length
        }

        return Layout(
            prefixes = prefixes,
            suffix = line.text.substring(cursor)
        )
    }

    /**
     * Returns the text/ranges a visual karaoke renderer should use. Credibly
     * aligned payloads keep the provider's source line verbatim and map timing
     * tokens onto readable lexical ranges. Unrelated payloads retain the legacy
     * provider-token reconstruction instead of inventing ranges on the source.
     */
    internal fun renderedLine(line: LyricLine): RenderedLine {
        if (line.words.isEmpty()) {
            return RenderedLine(line.text, IntArray(0), IntArray(0))
        }

        val displayRanges = displayRangesForLine(line)
        if (displayRanges != null) {
            return RenderedLine(
                text = line.text,
                tokenStart = IntArray(displayRanges.size) { displayRanges[it].start },
                tokenEnd = IntArray(displayRanges.size) { displayRanges[it].end }
            )
        }

        val wordLayout = layout(line)
        val builder = StringBuilder()
        val starts = IntArray(line.words.size)
        val ends = IntArray(line.words.size)
        line.words.forEachIndexed { index, word ->
            builder.append(wordLayout.prefixes.getOrElse(index) { "" })
            starts[index] = builder.length
            builder.append(word.text)
            ends[index] = builder.length
        }
        builder.append(wordLayout.suffix)
        return RenderedLine(builder.toString(), starts, ends)
    }

    fun karaokeText(
        line: LyricLine,
        activeWordIndex: Int,
        openMarker: String = "【",
        closeMarker: String = "】"
    ): String {
        val words = line.words
        if (words.isEmpty() || activeWordIndex !in words.indices) return line.text

        val displayRange = displayRangeForToken(line, activeWordIndex)
        if (displayRange != null) {
            return buildString(line.text.length + openMarker.length + closeMarker.length) {
                append(line.text, 0, displayRange.start)
                append(openMarker)
                append(line.text, displayRange.start, displayRange.end)
                append(closeMarker)
                append(line.text, displayRange.end, line.text.length)
            }
        }

        // Conservative compatibility fallback for provider payloads that are not
        // sufficiently related to the visible source line.
        val layout = layout(line)
        return buildString {
            words.forEachIndexed { index, word ->
                append(layout.prefixes.getOrElse(index) { "" })
                if (index == activeWordIndex) append(openMarker)
                append(word.text)
                if (index == activeWordIndex) append(closeMarker)
            }
            append(layout.suffix)
        }
    }

    /**
     * Returns the human-readable character range that should be highlighted for
     * a provider timing token. The range is expressed against [LyricLine.text]
     * with an exclusive [DisplayRange.end].
     */
    internal fun displayRangeForToken(line: LyricLine, activeTokenIndex: Int): DisplayRange? =
        displayRangesForLine(line)?.getOrNull(activeTokenIndex)

    /**
     * Returns readable ranges for every timing token only when the timing payload
     * has enough evidence that it belongs to [LyricLine.text]. This prevents a
     * small number of incidental substring matches from replacing the legacy
     * compatibility path.
     */
    internal fun displayRangesForLine(line: LyricLine): List<DisplayRange>? {
        // Views re-query ranges per token per animation frame; the alignment
        // search below is quadratic, so memoize by line.
        synchronized(rangesCache) {
            if (rangesCache.containsKey(line)) return rangesCache[line]
        }
        val computed = computeDisplayRanges(line)
        synchronized(rangesCache) {
            if (rangesCache.size > RANGES_CACHE_MAX) rangesCache.clear()
            rangesCache[line] = computed
        }
        return computed
    }

    /**
     * Ranges in [LyricLine.text] space even when the credibility heuristic
     * refuses the payload: as long as every token is sequentially locatable,
     * token-space ranges keep the sweep stepping instead of dying entirely.
     */
    internal fun textSpaceRanges(line: LyricLine): List<DisplayRange>? {
        displayRangesForLine(line)?.let { return it }
        if (line.words.isEmpty()) return null
        return locateTokensBestEffort(line).map { span ->
            span?.let { DisplayRange(it.start, it.end) } ?: return null
        }
    }

    private fun computeDisplayRanges(line: LyricLine): List<DisplayRange>? {
        if (line.words.isEmpty()) return emptyList()
        val ranges = lexicalRanges(line.text)
        if (ranges.isEmpty()) return null

        val tokenSpans = locateTokensBestEffort(line)
        if (!hasCredibleAlignment(line, tokenSpans)) return null

        // Character-level payloads for Japanese text carry the fidelity to
        // highlight glyph-by-glyph; forcing dictionary-word ranges would make
        // the highlight jump several syllables ahead and sit still after.
        if (isCharacterLevelJapanese(line)) {
            return tokenSpans.map { span ->
                span?.let { extendPastTrailingGap(line, it.start, it.end) } ?: return null
            }
        }

        val result = ArrayList<DisplayRange>(line.words.size)
        line.words.indices.forEach { tokenIndex ->
            result += mapDisplayRange(line, tokenIndex, ranges, tokenSpans) ?: return null
        }
        return result
    }

    private fun isCharacterLevelJapanese(line: LyricLine): Boolean =
        // Per-character payloads come from CJK uploads generally (SyncLRC
        // tokenizes Korean and Chinese the same way), not just Japanese.
        CJK_SCRIPT.containsMatchIn(line.text) &&
            line.words.isNotEmpty() &&
            // Syllable-sized tokens (optionally carrying collapsed punctuation)
            // carry glyph-by-glyph fidelity; multi-syllable chunks do not.
            line.words.all { word ->
                word.text.trim().count { !isLayoutPunctuation(it) } <= 1
            }

    private fun isLayoutPunctuation(c: Char): Boolean =
        !c.isLetterOrDigit() && !c.isWhitespace()

    /**
     * Trailing punctuation and spaces belong to no lexical range, so a highlight
     * that stops at the range edge leaves them unlit until the line changes.
     * Sweep them into the active token's range up to wherever the next token
     * starts (the line's end for the final token).
     */
    private fun extendPastTrailingGap(line: LyricLine, rangeStart: Int, rangeEnd: Int): DisplayRange {
        var gapEnd = rangeEnd
        while (gapEnd < line.text.length && !line.text[gapEnd].isLetterOrDigit()) {
            gapEnd++
        }
        return DisplayRange(rangeStart, maxOf(rangeEnd, gapEnd))
    }

    private fun mapDisplayRange(
        line: LyricLine,
        activeTokenIndex: Int,
        ranges: List<DisplayRange>,
        tokenSpans: List<TokenSpan?>
    ): DisplayRange? {
        val token = tokenSpans.getOrNull(activeTokenIndex)
        if (token == null) {
            return fallbackDisplayRange(line, activeTokenIndex, ranges, tokenSpans)
        }

        ranges.firstOrNull { range ->
            token.start < range.end && token.end > range.start
        }?.let { return extendPastTrailingGap(line, it.start, it.end) }

        // Whitespace and punctuation can themselves be timed by some providers.
        // Keep the visible word stable through those tiny bridge tokens by mapping
        // them to the nearest lexical range, preferring the preceding word on ties.
        return ranges.minWithOrNull(
            compareBy<DisplayRange> { distance(token, it) }
                .thenBy { if (it.end <= token.start) 0 else 1 }
        )
    }

    private fun locateTokensBestEffort(line: LyricLine): List<TokenSpan?> {
        val spans = MutableList<TokenSpan?>(line.words.size) { null }
        var cursor = 0

        line.words.forEachIndexed { index, word ->
            val span = findTokenSpan(line.text, word.text, cursor) ?: return@forEachIndexed
            spans[index] = span
            cursor = span.end
        }

        return spans
    }

    private fun findTokenSpan(text: String, token: String, startIndex: Int): TokenSpan? {
        if (token.isEmpty() || startIndex >= text.length) return null

        val exactIndex = text.indexOf(token, startIndex = startIndex)
        val exactSpan = if (exactIndex >= 0) {
            TokenSpan(exactIndex, exactIndex + token.length)
        } else {
            null
        }

        val caseInsensitiveIndex = text.indexOf(token, startIndex = startIndex, ignoreCase = true)
        val caseInsensitiveSpan = if (caseInsensitiveIndex >= 0) {
            TokenSpan(caseInsensitiveIndex, caseInsensitiveIndex + token.length)
        } else {
            null
        }

        val normalizedToken = normalizeForAlignment(token)
        var normalizedSpan: TokenSpan? = null
        for (candidateStart in startIndex until text.length) {
            val maxEnd = minOf(
                text.length,
                candidateStart + token.length + NORMALIZATION_SLACK_CHARS
            )
            for (candidateEnd in (candidateStart + 1)..maxEnd) {
                if (normalizeForAlignment(text.substring(candidateStart, candidateEnd)) == normalizedToken) {
                    normalizedSpan = TokenSpan(candidateStart, candidateEnd)
                    break
                }
            }
            if (normalizedSpan != null) break
        }

        // Sequential karaoke alignment cares about source order first. A later
        // exact-case occurrence must not beat an earlier case/normalization-
        // equivalent occurrence, otherwise repeated words can shift by one token.
        return listOfNotNull(exactSpan, caseInsensitiveSpan, normalizedSpan)
            .minByOrNull { it.start }
    }

    private fun hasCredibleAlignment(line: LyricLine, tokenSpans: List<TokenSpan?>): Boolean {
        if (line.words.isEmpty()) return false

        val sourceCanonical = canonicalContent(line.text)
        val timingCanonical = canonicalContent(line.words.joinToString(separator = "") { it.text })
        if (sourceCanonical.isNotEmpty() && sourceCanonical == timingCanonical) return true
        if (sourceCanonical.isEmpty() || timingCanonical.isEmpty() || line.words.size == 1) return false

        val alignedIndices = line.words.indices.filter { tokenSpans.getOrNull(it) != null }
        if (alignedIndices.size < MIN_PARTIAL_ANCHORS) return false

        // Count substantive canonical characters rather than token hits. This
        // prevents unrelated fine-grained payloads such as [A, X, G, Z] from
        // being accepted merely because one-letter substrings happen to occur in
        // the source. Sequential matching guarantees these covered spans do not
        // overlap or reorder.
        val alignedCanonicalChars = alignedIndices.sumOf { index ->
            canonicalContent(line.words[index].text).length
        }
        if (alignedCanonicalChars == 0) return false

        return alignedCanonicalChars * MIN_COVERAGE_DENOMINATOR >=
            sourceCanonical.length * MIN_COVERAGE_NUMERATOR &&
            alignedCanonicalChars * MIN_COVERAGE_DENOMINATOR >=
            timingCanonical.length * MIN_COVERAGE_NUMERATOR
    }

    private fun normalizeForAlignment(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
            .filter { !it.isWhitespace() }

    private fun canonicalContent(text: String): String =
        normalizeForAlignment(text).filter { it.isLetterOrDigit() }

    private fun fallbackDisplayRange(
        line: LyricLine,
        activeTokenIndex: Int,
        ranges: List<DisplayRange>,
        tokenSpans: List<TokenSpan?>
    ): DisplayRange? {
        if (activeTokenIndex !in line.words.indices || ranges.isEmpty()) return null
        if (line.words.size == 1) {
            return DisplayRange(ranges.first().start, ranges.last().end)
        }

        val previousIndex = (activeTokenIndex - 1 downTo 0)
            .firstOrNull { tokenSpans[it] != null }
        val nextIndex = (activeTokenIndex + 1..line.words.lastIndex)
            .firstOrNull { tokenSpans[it] != null }
        val blockStartIndex = (previousIndex ?: -1) + 1
        val blockEndIndex = (nextIndex ?: line.words.size) - 1
        val intervalStart = tokenSpans.getOrNull(previousIndex ?: -1)?.end
            ?: ranges.first().start
        val intervalEnd = tokenSpans.getOrNull(nextIndex ?: tokenSpans.size)?.start
            ?: ranges.last().end

        // Only the unaligned block is estimated. Successfully aligned neighbors
        // remain anchors, so a single case/punctuation/normalization mismatch does
        // not redistribute the surrounding syllable timing across later words.
        val tokenLengths = (blockStartIndex..blockEndIndex).map { index ->
            line.words[index].text.length.coerceAtLeast(1)
        }
        val totalLength = tokenLengths.sum().coerceAtLeast(1)
        val activeOffset = activeTokenIndex - blockStartIndex
        val consumedBefore = tokenLengths.take(activeOffset).sum()
        val tokenMidpoint = consumedBefore + tokenLengths[activeOffset] / 2f
        val fraction = (tokenMidpoint / totalLength).coerceIn(0f, 1f)
        val start = intervalStart.toFloat()
        val end = intervalEnd.coerceAtLeast(intervalStart).toFloat()
        val target = start + (end - start) * fraction

        return ranges.minWithOrNull(
            compareBy<DisplayRange> { pointDistance(target, it) }
                .thenBy { if (it.end.toFloat() <= target) 0 else 1 }
        )?.let { extendPastTrailingGap(line, it.start, it.end) }
    }

    private fun lexicalRanges(text: String): List<DisplayRange> {
        if (text.isBlank()) return emptyList()

        val locale = if (JAPANESE_SCRIPT.containsMatchIn(text)) Locale.JAPANESE else Locale.ROOT
        val iterator = BreakIterator.getWordInstance(locale)
        iterator.setText(text)

        val rawRanges = ArrayList<DisplayRange>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val segment = text.substring(start, end)
            if (segment.any { it.isLetterOrDigit() } || JAPANESE_SCRIPT.containsMatchIn(segment)) {
                rawRanges += DisplayRange(start, end)
            }
            start = end
            end = iterator.next()
        }

        if (rawRanges.size < 2 || !JAPANESE_SCRIPT.containsMatchIn(text)) return rawRanges

        // Japanese dictionary boundaries can still expose a stem and its okurigana
        // as separate ranges (e.g. 忘 + れない). Merge an adjacent hiragana suffix
        // into a preceding kanji-containing range. This is intentionally a display
        // heuristic only; the original per-token timestamps remain available.
        val merged = ArrayList<DisplayRange>(rawRanges.size)
        for (range in rawRanges) {
            val currentText = text.substring(range.start, range.end)
            val previous = merged.lastOrNull()
            if (previous != null && previous.end == range.start) {
                val previousText = text.substring(previous.start, previous.end)
                if (HAN_SCRIPT.containsMatchIn(previousText) && HIRAGANA_ONLY.matches(currentText)) {
                    merged[merged.lastIndex] = DisplayRange(previous.start, range.end)
                    continue
                }
            }
            merged += range
        }
        return merged
    }

    private fun distance(token: TokenSpan, range: DisplayRange): Int {
        return when {
            token.end <= range.start -> range.start - token.end
            range.end <= token.start -> token.start - range.end
            else -> 0
        }
    }

    private fun pointDistance(point: Float, range: DisplayRange): Float {
        return when {
            point < range.start -> range.start - point
            point > range.end -> point - range.end
            else -> 0f
        }
    }

    private fun fallback(line: LyricLine): Layout {
        val separator = if (line.text.any { it.isWhitespace() }) " " else ""
        return Layout(
            prefixes = line.words.indices.map { index ->
                if (index == 0) "" else separator
            },
            suffix = ""
        )
    }

    private val rangesCache = LinkedHashMap<LyricLine, List<DisplayRange>?>(64, 0.75f, false)
    private const val RANGES_CACHE_MAX = 256
    private const val NORMALIZATION_SLACK_CHARS = 4
    private const val MIN_PARTIAL_ANCHORS = 2
    private const val MIN_COVERAGE_NUMERATOR = 1
    private const val MIN_COVERAGE_DENOMINATOR = 2
}
