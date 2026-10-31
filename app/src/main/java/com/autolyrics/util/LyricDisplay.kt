package com.autolyrics.util

import com.autolyrics.model.LyricLine

/**
 * Display-time treatment of instrumental (music-symbol) lines. Providers
 * blank-line payloads reach the renderers as parser-stored note glyphs, in
 * shapes that vary by provider, so symbol detection must recognize all of
 * them rather than compare against one exact string.
 */
internal object LyricDisplay {
    /**
     * Three note glyphs separated by ideographic spaces: U+3000 is the one
     * space atom with an empirically pinned host advance (1.0 em), so the
     * padded edge error of a symbol row grows only with the note-glyph
     * count, not with two unknown space widths on top.
     */
    const val MUSIC_SYMBOL = "♪　♪　♪"

    private val SYMBOL_STRINGS = setOf("♪", "♫", "♬", "♩", "🎵", "🎶")

    /**
     * Provider instrumental lines come in many shapes: single or repeated
     * note glyphs, note emoji, or bracketed [Music]/[纯音乐] tags. All of
     * them mean the same as a blank line.
     */
    fun isSymbolText(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val inner = t.removeSurroundingBrackets()
        if (inner.isNotBlank() &&
            inner.lowercase() in setOf("music", "instrumental", "音乐", "纯音乐")
        ) return true
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            if (ch.isWhitespace()) { i++; continue }
            val token = if (ch.isHighSurrogate() && i + 1 < t.length && t[i + 1].isLowSurrogate()) {
                t.substring(i, i + 2)
            } else ch.toString()
            if (token !in SYMBOL_STRINGS) return false
            i += token.length
        }
        return true
    }

    /**
     * Countdown form of the music symbol: one glyph per remaining second
     * until the next line, capped at three. Glyphs ride the same U+3000
     * separator as [MUSIC_SYMBOL] so padded edges stay predictable.
     */
    fun countdownSymbol(secondsLeft: Int): String =
        (1..secondsLeft.coerceIn(1, 3)).joinToString("\u3000") { "\u266A" }

    fun displayText(text: String): String =
        if (isSymbolText(text)) MUSIC_SYMBOL else text.trim()

    /**
     * Index of the last line with real words, or -1 when every line is an
     * instrumental marker. Trailing symbol lines mean "outro", which the
     * sung line already conveys by staying lit, so renderers cap their lists
     * at this index and show no music symbols at the end of a song.
     */
    fun lastMeaningfulIndex(lines: List<LyricLine>): Int {
        for (i in lines.indices.reversed()) {
            if (!isSymbolText(lines[i].text)) return i
        }
        return -1
    }

    private fun String.removeSurroundingBrackets(): String {
        val pairs = listOf('[' to ']', '(' to ')', '【' to '】', '［' to '］')
        for ((open, close) in pairs) {
            if (length >= 2 && first() == open && last() == close) return substring(1, length - 1).trim()
        }
        return this
    }
}
