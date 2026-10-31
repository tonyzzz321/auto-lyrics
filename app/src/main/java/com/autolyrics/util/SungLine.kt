package com.autolyrics.util

import com.autolyrics.model.LyricLine

/**
 * Which lyric line is being sung at a playback position — the single source
 * of truth shared by every AA row builder and the browse refresh gate, so
 * the gate can never again mask a row change the builders would render.
 */
internal object SungLine {
    /** Pause after a line's real end before an instrumental gap registers. */
    const val GAP_GRACE_MS = 500L

    /** Silence between a line end and the next start that reads as music. */
    const val GAP_MIN_MS = 2_500L

    /** A token-timed line is treated as ended this long after its last
     * token START: no provider ships word end times, so the start-plus-
     * sustain model is what proves (or fails to prove) a silence. */
    const val LINE_SUSTAIN_MS = 2_000L

    /** Assumed end of a token-less line's final line (start plus this). */
    const val LAST_LINE_FALLBACK_END_MS = 5_000L

    /** How far ahead of true time the countdown digits switch. */
    const val DISPLAY_LEAD_MS = 300L

    /**
     * Seconds remaining until the next REAL lyric starts, as the countdown
     * music symbol's glyph count (1..4). Symbol runs are skipped, so a
     * countdown ticks through an instrumental all the way to the coming
     * words - intro included.
     */
    fun countdownSeconds(lines: List<LyricLine>, posMs: Long, count: Int): Int {
        val nextStart = (0 until minOf(count, lines.size))
            .asSequence()
            .map { lines[it] }
            .filter { !LyricDisplay.isSymbolText(it.text) }
            .firstOrNull { it.timeMs > posMs }
            ?.timeMs ?: return 4
        val remaining = nextStart - posMs
        // Display lead: a browse-row push needs ~0.5 s to reach the screen
        // and the heartbeat samples at 500 ms, so the true final second
        // would always be stale before it paints. Digits therefore switch
        // 300 ms early - they can lead reality slightly, never lag it.
        return kotlin.math.ceil((remaining - DISPLAY_LEAD_MS) / 1000.0).toInt().coerceIn(1, 4)
    }

    /** Singing end of a token-timed line (last token start + sustain), or
     * null when the line carries no word timings to anchor one. */
    fun lineEndMs(line: LyricLine): Long? =
        line.words?.maxOfOrNull { it.timeMs }?.plus(LINE_SUSTAIN_MS)

    /**
     * True once [posMs] is past the END of the last renderable line: the
     * last token start plus [LINE_SUSTAIN_MS] for token-timed payloads,
     * otherwise the line start plus [LAST_LINE_FALLBACK_END_MS]. The
     * now-playing card shows track identity instead of lyrics beyond this
     * point.
     */
    fun pastLastLineEnd(lines: List<LyricLine>, count: Int, posMs: Long): Boolean {
        if (count <= 0) return false
        val last = lines[minOf(count, lines.size) - 1]
        val end = lineEndMs(last) ?: last.timeMs + LAST_LINE_FALLBACK_END_MS
        return posMs > end
    }

    /** Index of the first line with real lyric text (= lines.size if none).
     * Blank/music-symbol lines timed at the top of a song are still the
     * intro, not lyrics: providers tag intro music with empty `[00:00]`
     * lines all the time. */
    fun firstRealIndex(lines: List<LyricLine>): Int =
        lines.indexOfFirst { !LyricDisplay.isSymbolText(it.text) }

    /** Last line whose time has passed, or -1 before the first line. */
    fun rawIndex(lines: List<LyricLine>, posMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) idx = i else break
        }
        return idx
    }

    /**
     * Index of the line being sung at [posMs] within a list capped to
     * [count] renderable lines, or -1 only in the instrumental INTRO (before
     * the first timed line). A sung line NEVER becomes -1 mid-song: once
     * singing has started, the row stays the sung line; instrumental stretches
     * are carried by the virtual countdown rows (see [virtualRows]),
     * never as a swap of the sung row. The outro keeps the last real lyric
     * lit.
     */
    fun index(lines: List<LyricLine>, posMs: Long, count: Int): Int {
        val raw = rawIndex(lines, posMs)
        if (raw < 0) return -1
        if (raw >= count) return count - 1
        // Leading music-tag lines have not started the song: the intro
        // lasts until the FIRST REAL LYRIC, so the card and boards keep
        // showing identity/countdown through a tagged intro.
        if (LyricDisplay.isSymbolText(lines[raw].text) &&
            raw < firstRealIndex(lines)
        ) return -1
        return raw
    }

    /**
     * One row of the DISPLAY sequence: a real lyric line, or the virtual
     * countdown row belonging to the gap that follows [realIndex]. The
     * countdown is a full member of the list: it previews like any line,
     * pages through windows like any line, and becomes the current row
     * once its instrumental starts.
     */
    data class VRow(val realIndex: Int, val isGap: Boolean)

    /** First non-symbol line at or after [from] (returns [count] if none). */
    private fun firstRealFrom(lines: List<LyricLine>, from: Int, count: Int): Int {
        var k = maxOf(0, from)
        while (k < count && LyricDisplay.isSymbolText(lines[k].text)) k++
        return k
    }

    /**
     * The coming lyric of the instrumental gap owned by line [i], or null
     * when it owns none. Two independent proofs, either sufficient:
     * 1. TAG-DECLARED: the provider tagged the instrumental with symbol
     *    lines and the next REAL lyric (past the whole run) starts at least
     *    [GAP_MIN_MS] after the first tag. Tags are an explicit statement
     *    of "music here", so this works for EVERY payload type, word-less
     *    synced included.
     * 2. TIME-PROVEN (untagged silence): [i] is token-timed and the next
     *    REAL lyric starts more than [GAP_MIN_MS] after its sustained end.
     *    Token-less payloads cannot use this leg: starts alone cannot prove
     *    a silence.
     * Lines past the last renderable lyric never own one (outro shows no
     * countdown).
     */
    fun gapTarget(lines: List<LyricLine>, i: Int, count: Int): LyricLine? {
        if (i < 0 || i >= count) return null
        if (LyricDisplay.isSymbolText(lines[i].text)) return null
        val k = firstRealFrom(lines, i + 1, count)
        if (k >= count) return null
        val next = lines[k]
        if (k > i + 1 && next.timeMs - lines[i + 1].timeMs >= GAP_MIN_MS) return next
        val end = lineEndMs(lines[i]) ?: return null
        return next.takeIf { it.timeMs - end > GAP_MIN_MS }
    }

    /** True when line [i] owns a provable instrumental gap. */
    fun gapAfter(lines: List<LyricLine>, i: Int, count: Int): Boolean =
        gapTarget(lines, i, count) != null

    /** The display sequence: real lines interleaved with virtual countdown
     * rows after every line that owns a gap. A symbol run tagging the
     * instrumental is REPLACED by its countdown row (one music row counting
     * to the coming words instead of a run of static triples). Leading
     * tags are consumed the same way by the INTRO countdown (currentVIndex
     * -1), so the sequence starts at the first real lyric — unless the
     * "song" is all symbols, which keeps its plain rows. */
    fun virtualRows(lines: List<LyricLine>, count: Int): List<VRow> {
        val out = ArrayList<VRow>(count + 4)
        val n = minOf(count, lines.size)
        var i = firstRealFrom(lines, 0, n)
        while (i < n) {
            if (LyricDisplay.isSymbolText(lines[i].text)) {
                // Sub-threshold flourish the countdown declined: skip it
                // as if it were never in the payload.
                i++
                continue
            }
            out.add(VRow(i, false))
            if (gapAfter(lines, i, count)) {
                out.add(VRow(i, true))
                i++
                while (i < n && LyricDisplay.isSymbolText(lines[i].text)) i++
            } else {
                i++
            }
        }
        return out
    }

    /** True once [posMs] is into the gap owned by line [i]: the virtual
     * countdown row is CURRENT. Active from the first tag's time when the
     * provider declared the instrumental, otherwise from the sustained end
     * plus grace; either leg alone suffices (whichever comes first). */
    fun gapActive(lines: List<LyricLine>, i: Int, count: Int, posMs: Long): Boolean {
        val a = lines.getOrNull(i) ?: return false
        val k = firstRealFrom(lines, i + 1, count)
        if (k > i + 1 && k < count && posMs >= lines[i + 1].timeMs) return true
        val end = lineEndMs(a) ?: return false
        return posMs > end + GAP_GRACE_MS
    }

    /**
     * Index of the CURRENT row in the display sequence: the sung real
     * line, its virtual countdown row once the instrumental starts, or -1
     * in the intro (which stays -1 through leading music-tag lines).
     */
    fun currentVIndex(
        rows: List<VRow>,
        lines: List<LyricLine>,
        posMs: Long,
        count: Int
    ): Int {
        val real = index(lines, posMs, count)
        if (real < 0) return -1
        val v = rows.indexOfFirst { !it.isGap && it.realIndex == real }
        if (v < 0) {
            // The sung line is a symbol tag, absent from the sequence.
            // If the row after the previous sequence member is a countdown,
            // the tag is part of that absorbed instrumental: the countdown
            // is current. Otherwise the tag was a sub-threshold flourish
            // skipped as if absent: the line before it stays lit.
            val lastBefore = rows.indexOfLast { !it.isGap && it.realIndex < real }
            return if (rows.getOrNull(lastBefore + 1)?.isGap == true) {
                lastBefore + 1
            } else {
                lastBefore
            }
        }
        if (rows.getOrNull(v + 1)?.isGap == true && gapActive(lines, real, count, posMs)) return v + 1
        return v
    }
}
