package com.autolyrics.util

/**
 * Row math for the Android Auto "Karaoke" tab: a two-row KTV-style page where
 * odd-numbered lines (0-based even) live on the left-justified top row and
 * even-numbered lines on the right-justified bottom row. Each side is replaced
 * by `line + 2` when its own line finishes, so both rows always show the pair
 * of lines being sung back to back.
 */
internal object AaKaraokeRows {

    /**
     * Pair of line indices to display for the active line index. The side
     * holding the active line shows it; the other side already previews the
     * next line on its own track (active + 1). Indices beyond the song end
     * with -1 so the caller can render a placeholder instead of a seek target.
     */
    fun pairFor(activeIdx: Int, lineCount: Int): Pair<Int, Int> {
        if (activeIdx < 0 || activeIdx >= lineCount) return -1 to -1
        val top = if (activeIdx % 2 == 0) activeIdx else activeIdx + 1
        val bottom = if (activeIdx % 2 == 1) activeIdx else activeIdx + 1
        return top to if (bottom < lineCount) bottom else -1
    }

    /**
     * Approximate rendered width in halfwidth units: CJK glyphs and fullwidth
     * punctuation occupy two terminal cells, Latin one, combining marks none.
     * Surrogate pairs (emoji) sum to two units naturally.
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
     * Pad [text] with leading fullwidth (2-unit) and halfwidth (1-unit) spaces
     * so its right edge approximates the right edge of a reference line of
     * [referenceWidth] units. Android Auto browse rows only render
     * left-aligned text, so visual right-justification is emulated; when the
     * text is already wider than the reference it is returned untouched.
     */
    fun rightAlign(text: String, referenceWidth: Int, marker: String = ""): String {
        val pad = referenceWidth - displayWidth(text)
        if (pad <= 0) return marker + text
        val fullwidth = "\u3000".repeat(pad / 2)
        val halfwidth = " ".repeat(pad % 2)
        return marker + fullwidth + halfwidth + text
    }
}
