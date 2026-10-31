package com.autolyrics.util

import com.autolyrics.model.LyricLine

/**
 * The display sequence every lyric surface renders: sanitized lyric lines
 * (non-lyric payload lines are stripped at ingest - see
 * [LyricDisplay.stripNonLyric]) interleaved with instrumental MARKER rows
 * derived from the timing silence between lines. Markers are full members
 * of the sequence: they preview, page, own row slots, and alternate board
 * parity exactly like lyrics, and the current row is whatever row's time
 * span contains the playback position.
 *
 * Derivation is deterministic from line times alone, so it is re-derived
 * per query instead of being stored - cached payloads never need a marker
 * migration, and translations stay aligned index-for-index with the real
 * lines.
 */
internal object SungLine {
    /** Silence between a derived line end and the next start that reads as
     * music: longer than this and an INTERLUDE marker row owns the gap.
     * Measured from the derived END, so it equals "over 10 s from the last
     * word start to the next start" for token-timed lines and "over 15 s
     * start-to-start" for line-timed ones (a token-less line is assumed to
     * hold [LINE_HOLD_MS] first). */
    const val GAP_MIN_MS = 5_000L

    /** A token-timed line ends this long after its last token START: no
     * provider ships word end times, so the start-plus-sustain model is
     * the derived end everywhere (board, markers, outro). */
    const val LINE_SUSTAIN_MS = 5_000L

    /** A token-less line is assumed to take this long: its derived end is
     * the line start plus this. */
    const val LINE_HOLD_MS = 10_000L

    /** Shortest opening silence that earns an INTRO marker row. Equal to
     * the countdown cap, so a marker's countdown window never reaches
     * before 0:00; shorter intros hold identity/board silence instead. */
    const val INTRO_MIN_MS = 4_000L

    /** Now-playing card: seconds before the next line where the countdown
     * takes over from the track-identity layout. */
    const val CARD_LEAD_MS = 4_000L

    /** How far ahead of true time the countdown digits switch. */
    const val DISPLAY_LEAD_MS = 300L

    /** Derived singing end of a line: last token start plus
     * [LINE_SUSTAIN_MS] when the line carries word timings, line start
     * plus [LINE_HOLD_MS] otherwise. */
    fun lineEndMs(line: LyricLine): Long =
        line.words?.maxOfOrNull { it.timeMs }?.plus(LINE_SUSTAIN_MS)
            ?: line.timeMs + LINE_HOLD_MS

    /** Where the outro starts: the derived end of the last renderable
     * line. The now-playing card shows track identity from this point;
     * browse surfaces keep the last lyric lit. */
    fun outroStartMs(lines: List<LyricLine>, count: Int): Long? {
        if (count <= 0 || lines.isEmpty()) return null
        return lineEndMs(lines[minOf(count, lines.size) - 1])
    }

    /** True once [posMs] is past the outro start (see [outroStartMs]). */
    fun pastLastLineEnd(lines: List<LyricLine>, count: Int, posMs: Long): Boolean {
        val end = outroStartMs(lines, count) ?: return false
        return posMs > end
    }

    /**
     * Seconds remaining until the next lyric line starts, as the countdown
     * glyph count (1..4): four glyphs whenever more than four seconds
     * remain, then one glyph per second down to the line.
     */
    fun countdownSeconds(lines: List<LyricLine>, posMs: Long, count: Int): Int {
        val nextStart = (0 until minOf(count, lines.size))
            .asSequence()
            .map { lines[it] }
            .firstOrNull { it.timeMs > posMs }
            ?.timeMs ?: return 4
        val remaining = nextStart - posMs
        // Display lead: a browse-row push needs ~0.5 s to reach the screen
        // and the heartbeat samples at 500 ms, so the true final second
        // would always be stale before it paints. Digits therefore switch
        // 300 ms early - they can lead reality slightly, never lag it.
        return kotlin.math.ceil((remaining - DISPLAY_LEAD_MS) / 1000.0).toInt().coerceIn(1, 4)
    }

    /**
     * Countdown for the marker row at [v]: it counts toward the line that
     * FOLLOWS the marker, never toward the first future line overall. With
     * two markers on screen (current intro, previewed interlude) the shared
     * first-future target made them tick in lockstep.
     */
    fun markerCountdownSeconds(
        rows: List<VRow>,
        v: Int,
        lines: List<LyricLine>,
        posMs: Long
    ): Int {
        val target = rows.asSequence().drop(v + 1)
            .firstOrNull { !it.isGap }
            ?.let { lines.getOrNull(it.realIndex)?.timeMs }
            ?: return 4
        return kotlin.math.ceil((target - posMs - DISPLAY_LEAD_MS) / 1000.0)
            .toInt().coerceIn(1, 4)
    }

    /**
     * The row the card previews after the row at [v]: NEVER skipping a row,
     * because a marker is a row of the sequence exactly like a lyric - when
     * an instrumental comes next, the marker (its glyph row) is the preview.
     * Null only past the last row.
     */
    fun previewSuccessor(rows: List<VRow>, v: Int): VRow? = rows.getOrNull(v + 1)

    /** Last line whose start has passed, or -1 before the first line. */
    fun rawIndex(lines: List<LyricLine>, posMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) idx = i else break
        }
        return idx
    }

    /**
     * Index of the line being sung at [posMs] within a list capped to
     * [count] lines, or -1 before the first line starts. Instrumental
     * stretches are carried by marker rows in [virtualRows], never by a
     * swap of the sung row; the outro keeps the last lyric lit.
     */
    fun index(lines: List<LyricLine>, posMs: Long, count: Int): Int {
        val raw = rawIndex(lines, posMs)
        if (raw < 0) return -1
        return minOf(raw, count - 1)
    }

    /**
     * One row of the DISPLAY sequence: a real lyric line
     * (`realIndex >= 0`), or an instrumental marker. A marker stores the
     * index of the line whose derived end opens the silence it spans
     * (INTERLUDE), or -1 for the INTRO marker spanning 0:00 to the first
     * line. There is no outro marker row: the outro renders as the last
     * lyric staying lit and is exposed through [outroStartMs] only.
     */
    data class VRow(val realIndex: Int, val isGap: Boolean)

    /** Where row [k] of [rows] becomes current. */
    fun rowStartMs(rows: List<VRow>, lines: List<LyricLine>, k: Int): Long {
        val row = rows[k]
        return if (row.isGap) {
            if (row.realIndex < 0) 0L else lineEndMs(lines[row.realIndex])
        } else {
            lines[row.realIndex].timeMs
        }
    }

    /**
     * The display sequence. An INTRO marker opens the list when the first
     * line starts at least [INTRO_MIN_MS] in; an INTERLUDE marker follows
     * every line whose derived end sits more than [GAP_MIN_MS] before the
     * next line's start. Markers never stack (a marker's span ends where
     * the next row starts), so row starts are strictly ordered and the
     * sequence is a partition of playback time.
     */
    fun virtualRows(lines: List<LyricLine>, count: Int): List<VRow> {
        val out = ArrayList<VRow>(count + 4)
        val n = minOf(count, lines.size)
        if (n == 0) return out
        if (lines[0].timeMs >= INTRO_MIN_MS) out.add(VRow(-1, true))
        for (i in 0 until n) {
            out.add(VRow(i, false))
            if (i + 1 < n && lines[i + 1].timeMs - lineEndMs(lines[i]) > GAP_MIN_MS) {
                out.add(VRow(i, true))
            }
        }
        return out
    }

    /**
     * Index of the CURRENT row: the last row whose start time [posMs] has
     * reached. Before the first row starts (no intro marker and the first
     * line has not begun) the sequence has no current row: -1.
     */
    fun currentVIndex(rows: List<VRow>, lines: List<LyricLine>, posMs: Long): Int {
        var cur = -1
        for (k in rows.indices) {
            if (posMs >= rowStartMs(rows, lines, k)) cur = k else break
        }
        return cur
    }

    /** Start time of the next real lyric after row [v], or null when the
     * sequence has no coming line (outro). */
    fun nextLineMs(rows: List<VRow>, lines: List<LyricLine>, v: Int): Long? {
        for (k in (v + 1) until rows.size) {
            val row = rows[k]
            if (!row.isGap) return lines[row.realIndex].timeMs
        }
        return null
    }
}
