package com.autolyrics.util

/**
 * Row math for the Android Auto "Karaoke" tab: a two-row KTV-style page where
 * odd-numbered lines (0-based even) live on the left-justified top row and
 * even-numbered lines on the right-justified bottom row. Each side is replaced
 * by `line + 2` when its own line finishes, so both rows always show the pair
 * of lines being sung back to back.
 */
import kotlin.math.roundToInt

internal object AaKaraokeRows {

    /**
     * Pair of line indices to display for the active line index. The side
     * holding the active line shows it; the other side already previews the
     * next line on its own track (active + 1). Even line indices ride the
     * bottom row, odd the top, so previewed lines never change rows when
     * they start being sung. Indices beyond the song end
     * with -1 so the caller can render a placeholder instead of a seek target.
     */
    fun pairFor(activeIdx: Int, lineCount: Int): Pair<Int, Int> {
        if (activeIdx >= lineCount) return -1 to -1
        if (activeIdx < 0) {
            // Instrumental intro before the first timed line: the sung side
            // is the virtual (-1) slot (rendered as the music symbol) and
            // the preview side already shows the line about to start.
            return -1 to if (lineCount > 0) 0 else -1
        }
        // Even indices (the first line, third, ...) ride the BOTTOM row: the
        // instrumental-intro preview below already shows the first line on
        // the bottom, and a previewed line must sit on the row it will be
        // sung from, or it visibly hops rows the moment it starts.
        val top = if (activeIdx % 2 == 0) activeIdx + 1 else activeIdx
        val bottom = if (activeIdx % 2 == 0) activeIdx else activeIdx + 1
        return (if (top < lineCount) top else -1) to
            (if (bottom < lineCount) bottom else -1)
    }

    /**
     * Approximate rendered width in halfwidth units: CJK glyphs and fullwidth
     * punctuation occupy two terminal cells, Latin one, combining marks none.
     * Surrogate pairs (emoji) sum to two units naturally. Used by the coarse
     * capacity model; padding itself uses the finer [advanceEm] metrics.
     */
    fun displayWidth(text: String): Int {
        var width = 0
        for (ch in text) {
            width += when {
                ch in '\u0300'..'\u036F' -> 0
                isWide(ch) -> 2
                else -> 1
            }
        }
        return width
    }

    private fun isWide(ch: Char): Boolean {
        val c = ch.code
        return c in 0x1100..0x115F ||
            c in 0x2E80..0x303E ||
            c in 0x3041..0x33FF ||
            c in 0x3400..0x4DBF ||
            c in 0x4E00..0x9FFF ||
            c in 0xA000..0xA4CF ||
            c in 0xFE30..0xFE4F ||
            c in 0xFF01..0xFF60 ||
            c in 0xFFE0..0xFFE6
    }

    /**
     * Auto-size the karaoke column from Gearhead's BrowserIconSize root hint.
     * Row text and the hinted art icon scale together on every head unit, so
     * the row's display-unit capacity is linear in icon px, calibrated at 43
     * units for the 143 px reference viewport (DHU). One unit of margin
     * guards against the wrap that over-padding causes. The floor is pinned
     * near the calibrated capacity because the icon-to-text scale is NOT
     * constant across head units: a production unit shipping icon=56 renders
     * rows at the same ~42-unit capacity as the DHU's icon=143 (verified by
     * on-device probes), so a proportional collapse below the reference
     * capacity only mis-justifies real cars.
     */
    fun columnFromIconSize(iconPx: Int, edge: Int, fallbackColumn: Int = 42): Int {
        val base = if (iconPx > 0) (iconPx * 43f / 143f).roundToInt() - 1 else fallbackColumn
        // Edge applies AFTER the floor: on low-hint hosts the raw base sits
        // far below the floor, so pre-floor nudges were swallowed by the
        // clamp and the phone-side knob did nothing.
        return (base.coerceIn(40, 64) + edge).coerceIn(24, 64)
    }

    /**
     * Model advance of a leading marker in ems, deliberately OVER-estimated
     * (the host's rendered ▶ advance is unmeasurable from the service side
     * and has shifted both under- and over-padded rows in live shots). The
     * active pair's lyric and translation lines carry the same marker string
     * and subtract this same constant, so the unknown rendered advance
     * cancels out of both tails equally — they coincide whatever it actually
     * is. The ▶ is charged 1.0 em, above its real advance, so the padded
     * total stays inside the row's wrap threshold.
     */
    fun markerEm(marker: String): Double = advanceEm(marker)

    /**
     * Host advance of U+266A in em units (rendered px per glyph / font px
     * size). The glyph comes from the head unit's symbol fallback font, not
     * Roboto, so this is a pixel-tuned constant: retune from a head-unit
     * screenshot of a right-aligned music-symbol row (edge error px divided
     * by 3 glyphs and the glyph px size), never from local font math.
     */
    private const val NOTE_EM = 0.56

    /**
     * Approximate rendered advance in ems using the head unit's Roboto
     * metrics: CJK and fullwidth glyphs are exactly 1 em, while Latin
     * advances are the font's proportional widths (the coarse one-unit-per-
     * character model left ~5 em gaps after English-heavy lines and wrapped
     * others once the marker ate the phantom width). The ▶ glyph is charged
     * the over-estimated [markerEm] model value, not a real advance.
     */
    fun advanceEm(text: String): Double {
        var em = 0.0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\u25b6') {
                em += 1.0
            } else if (ch == '\u25b8') {
                em += 0.8
            } else if (Character.isHighSurrogate(ch) && i + 1 < text.length &&
                Character.isLowSurrogate(text[i + 1])
            ) {
                em += 1.0
                i += 1
            } else {
                em += widthCh(ch)
            }
            i += 1
        }
        return em
    }

    private fun widthCh(ch: Char): Double = when {
        ch in '\u0300'..'\u036F' -> 0.0
        isWide(ch) -> 1.0
        else -> when (ch) {
            ' ', '\u00A0' -> 0.26
            // The head unit collapses U+2000-family spaces (live rows rendered
            // them at zero width), so they must never be counted or emitted.
            in '\u2002'..'\u200B' -> 0.0
            'i', 'l', '.', ',', '!', ':', ';', '|', '\u2019', '\u2018', '\u00B7',
            '\u0027' -> 0.25
            'j', 't', 'f', 'I', '(', ')', '[', ']', '-', '\u2013', '\u2014' -> 0.33
            'r', '1', '\u201C', '\u201D', '"', '?', '*', '/' -> 0.38
            'c', 'e', 'k', 's', 'x', 'z', 'v', 'y',
            '2', '3', '4', '5', '6', '7', '9' -> 0.47
            'a', 'b', 'd', 'g', 'h', 'n', 'o', 'p', 'q', 'u',
                        '0', '8', '▴', '▲' -> 0.56
            // Note glyph: rendered from the host's symbol fallback font,
            // not Roboto. Calibrate from a head-unit screenshot of a
            // padded symbol row: edge_err_px / 3 glyphs / glyph_px.
            '♪' -> NOTE_EM
            'J', 'T', 'L', '&', '\u00A1' -> 0.55
            'B', 'C', 'D', 'E', 'G', 'H', 'K', 'N', 'P', 'R', 'S', 'U', 'V', 'X', 'Y', 'Z' -> 0.63
            'A', 'O', 'Q' -> 0.67
            'm', 'w' -> 0.75
            'M' -> 0.87
            'W' -> 0.94
            else -> 0.55
        }
    }

    /**
     * Pick the active-line [marker] that fits the board column: the full
     * marker (marker + spacing + text within [column]) or a compact
     * single-glyph one when the full marker would push the padded string past
     * the column and force the row to wrap.
     */
    fun markerFor(active: Boolean, text: String, column: Int, full: String): String {
        if (!active) return ""
        val compact = full.trimEnd()
        return if (advanceEm(text) + markerEm(full) <= column * 0.5) full else compact
    }

    /**
     * Pad [text] with leading ideographic spaces and ASCII spaces (the only
     * two the host renders reliably, quantised to well under a tenth of an
     * em) so its right edge
     * sits at the [columnWidth]-halfwidth-unit column. Android Auto browse
     * rows render left-aligned only and wrap (never shrink) past the row
     * width, so the column must stay under the row's capacity — the Sync
     * tab's edge rows calibrate it per screen. The [marker] (if any) rides
     * inside the padding at its over-estimated [markerEm] allowance: lines of
     * the active pair carry the identical marker string, so the glyph's true
     * advance cancels out of both tails. Text already wider than the column
     * is returned untouched (it fills the row by itself).
     */
    fun rightAlign(text: String, columnWidth: Int, marker: String = ""): String {
        var pad = columnWidth * 0.5 - advanceEm(text) - markerEm(marker)
        if (pad <= 0.02) return marker + text
        val builder = StringBuilder()
        fun take(unit: Double, glyph: String, round: Boolean = false) {
            val bias = if (round) 0.5 else 1e-4
            val n = Math.floor(pad / unit + bias).toInt().coerceAtLeast(0)
            repeat(n) { builder.append(glyph) }
            pad -= n * unit
        }
        take(1.0, "\u3000")
        // Nearest multiple for the fine step: the residual (≤0.13 em) would
        // otherwise read as a visible short edge, and the column's wrap
        // margin absorbs a slight overshoot.
        take(0.26, " ", round = true)
        return builder.toString() + marker + text
    }
}
