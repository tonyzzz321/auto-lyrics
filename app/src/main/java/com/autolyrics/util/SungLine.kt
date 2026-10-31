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

    /** Assumed end of the final line when the payload carries no word ends. */
    const val LAST_LINE_FALLBACK_END_MS = 5_000L

    /** How far ahead of true time the countdown digits switch. */
    const val DISPLAY_LEAD_MS = 300L

    /**
     * Seconds remaining until the next line starts, as the countdown music
     * symbol's glyph count (1..3): intro counts to the first line, a
     * mid-song gap counts to the line after it.
     */
    fun countdownSeconds(lines: List<LyricLine>, posMs: Long, count: Int): Int {
        val raw = rawIndex(lines, posMs)
        val nextStart = if (raw < 0) lines.firstOrNull()?.timeMs
            else lines.getOrNull(raw + 1)?.timeMs
        val remaining = nextStart?.minus(posMs) ?: return 3
        // Display lead: a browse-row push needs ~0.5 s to reach the screen
        // and the heartbeat samples at 500 ms, so the true final second
        // would always be stale before it paints. Digits therefore switch
        // 300 ms early - they can lead reality slightly, never lag it.
        return kotlin.math.ceil((remaining - DISPLAY_LEAD_MS) / 1000.0).toInt().coerceIn(1, 3)
    }

    /**
     * True once [posMs] is past the END of the last renderable line: the
     * payload's own word end times when present, otherwise the line start
     * plus [LAST_LINE_FALLBACK_END_MS]. The now-playing card shows track
     * identity instead of lyrics beyond this point.
     */
    fun pastLastLineEnd(lines: List<LyricLine>, count: Int, posMs: Long): Boolean {
        if (count <= 0) return false
        val last = lines[minOf(count, lines.size) - 1]
        val ends = last.words?.mapNotNull { it.endTimeMs }
        val end = if (ends != null && ends.isNotEmpty()) ends.max()
            else last.timeMs + LAST_LINE_FALLBACK_END_MS
        return posMs > end
    }

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
     * are rendered as an INSERTED music-symbol row (see [instrumentalGap]),
     * never as a swap of the sung row. The outro keeps the last real lyric
     * lit.
     */
    fun index(lines: List<LyricLine>, posMs: Long, count: Int): Int {
        val raw = rawIndex(lines, posMs)
        if (raw < 0) return -1
        if (raw >= count) return count - 1
        return raw
    }

    /**
     * True when [posMs] sits in an instrumental gap that has REAL end
     * information to prove it: the current line's raw payload carries word
     * end times, its last end is behind the position, and the next line
     * starts at least [GAP_MIN_MS] later. The last renderable line never
     * qualifies (outro shows no symbols). Without word end times there is
     * nothing to trust — synced-only payloads keep the last lyric lit, per
     * project policy of not guessing line ends.
     */
    fun instrumentalGap(lines: List<LyricLine>, posMs: Long, count: Int): Boolean {
        val raw = rawIndex(lines, posMs)
        if (raw < 0 || raw >= count || raw >= count - 1) return false
        val line = lines[raw]
        if (LyricDisplay.isSymbolText(line.text)) return false
        val ends = line.words?.mapNotNull { it.endTimeMs } ?: return false
        if (ends.isEmpty()) return false
        val lineEnd = ends.max()
        val nextStart = lines.getOrNull(raw + 1)?.timeMs ?: return false
        return posMs > lineEnd + GAP_GRACE_MS && nextStart - lineEnd > GAP_MIN_MS
    }
}
