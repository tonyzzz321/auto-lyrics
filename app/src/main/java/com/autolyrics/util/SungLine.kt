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

    /**
     * The coming lyric of the instrumental gap owned by line [i], or null
     * when it owns none: [i] must be token-timed and the next REAL lyric
     * (symbol tag lines in between belong to the gap) must start more than
     * [GAP_MIN_MS] after the sustained end. Token-less payloads never own
     * a gap; starts alone cannot prove a silence. Lines past the last
     * renderable lyric never own one (outro shows no countdown).
     */
    fun gapTarget(lines: List<LyricLine>, i: Int, count: Int): LyricLine? {
        if (i < 0 || i >= count) return null
        val a = lines[i]
        if (LyricDisplay.isSymbolText(a.text)) return null
        val end = lineEndMs(a) ?: return null
        var k = i + 1
        while (k < count && LyricDisplay.isSymbolText(lines[k].text)) k++
        if (k >= count) return null
        val next = lines[k]
        return next.takeIf { it.timeMs - end > GAP_MIN_MS }
    }

    /** True when line [i] owns a provable instrumental gap. */
    fun gapAfter(lines: List<LyricLine>, i: Int, count: Int): Boolean =
        gapTarget(lines, i, count) != null

    /** The display sequence: real lines interleaved with virtual countdown
     * rows after every line that owns a gap. A symbol run tagging the
     * instrumental is REPLACED by its countdown row (one music row counting
     * to the coming words instead of a run of static triples). */
    fun virtualRows(lines: List<LyricLine>, count: Int): List<VRow> {
        val out = ArrayList<VRow>(count + 4)
        var i = 0
        val n = minOf(count, lines.size)
        while (i < n) {
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

    /** True once [posMs] is into the gap owned by line [i] (past its
     * sustained end plus the grace): the virtual countdown row is CURRENT. */
    fun gapActive(lines: List<LyricLine>, i: Int, posMs: Long): Boolean {
        val end = lines.getOrNull(i)?.let { lineEndMs(it) } ?: return false
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
            // The sung line is a symbol tag covered by a countdown row:
            // the instrumental is on, so its countdown row is current.
            return rows.indexOfLast { it.isGap && it.realIndex < real }
        }
        if (rows.getOrNull(v + 1)?.isGap == true && gapActive(lines, real, posMs)) return v + 1
        return v
    }
}
