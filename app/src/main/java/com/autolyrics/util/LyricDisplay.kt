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

    /** Bracketed non-lyric tags: instrumental statements AND section
     * headers ([Verse], 【副歌】…). Neither is sung, so neither is a lyric
     * line: ingestion strips everything this set recognizes. */
    private val NON_LYRIC_TAGS = setOf(
        "music", "instrumental", "音乐", "纯音乐",
        "verse", "chorus", "bridge", "intro", "outro", "hook", "refrain",
        "prechorus", "postchorus", "interlude", "solo", "chorusline",
        "副歌", "主歌", "間奏", "前奏", "尾奏", "说唱"
    )

    /**
     * Production-credit lines. Providers (SyncLRC's sources most often) ship
     * "词：林夕" / "曲：Paul Gray" as TIMED rows, so without this filter the
     * credits render and sing like sung lyrics ahead of the first real line.
     * A colon line is a credit only when it STARTS with a known label; the
     * By-form must be a short label prefix of a short tail, so sung lines
     * merely containing colons ("我说：你好") or the words ("Word by word")
     * survive.
     */
    fun isCreditLine(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val colon = t.indexOfFirst { it == ':' || it == '：' }
        if (colon in 1..CREDIT_LABEL_MAX_LEN && t.substring(colon + 1).isNotBlank()) {
            val label = t.substring(0, colon).trim().lowercase()
            if (label in CREDIT_LABELS) return true
        }
        val lower = t.lowercase()
        return CREDIT_PHRASES.any { phrase ->
            lower.startsWith(phrase) &&
                t.length > phrase.length && (t[phrase.length] == ' ' || t[phrase.length] == ':' || t[phrase.length] == '：') &&
                t.length <= 64 && t.count { it == ' ' } <= 5
        }
    }

    /**
     * Provider instrumental lines come in many shapes: single or repeated
     * note glyphs, note emoji, or bracketed [Music]/[纯音乐] tags; section
     * headers ([Verse], [Chorus]) name a song part without any sung words.
     * All of them mean the same as a blank line.
     */
    fun isSymbolText(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        // Bracketed tags: letters-only key folds numbered headers
        // ([Verse 1], 【Chorus 2】) onto their base tag; bracketed lyric
        // text that merely contains digits still matches nothing in the
        // tag set. UNBRACKETED text never enters this set: a whole sung
        // line reading "Verse" is lyrics - only the standalone CJK
        // markers below ship unbracketed as instrumental statements.
        val inner = t.stripBrackets()
        if (inner != null && inner.isNotBlank() &&
            inner.filter { it.isLetter() }.lowercase() in NON_LYRIC_TAGS
        ) return true
        if (t in BARE_NON_LYRIC) return true
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
     * until the next line, capped at four. Glyphs ride the same U+3000
     * separator as [MUSIC_SYMBOL] so padded edges stay predictable.
     */
    fun countdownSymbol(secondsLeft: Int): String =
        (1..secondsLeft.coerceIn(1, 4)).joinToString("\u3000") { "\u266A" }

    fun displayText(text: String): String =
        if (isSymbolText(text)) MUSIC_SYMBOL else text.trim()

    /**
     * Ingest filter: everything a provider ships that is not a sung line -
     * blanks, note-glyph runs, [Music] tags, section headers - removed at
     * parse time, so every consumer downstream (gates, scoring, cache,
     * translation, display) sees lyric-only payloads. Instrumental stretches
     * are no longer payload content; they are derived from the timing
     * silence between lines (see InstrumentalMarkers).
     */
    fun stripNonLyric(lines: List<LyricLine>): List<LyricLine> =
        lines.filterNot { isSymbolText(it.text) || isCreditLine(it.text) }

    /**
     * Metadata-aware ingest filter: SyncLRC's aggregated sources (NetEase/QQ-
     * family uploads) sometimes ship the track's OWN title/artist as the
     * first "lyric" line — "側面 - 張國榮", "歌曲：X 歌手：Y", or an adjacent
     * title-then-artist pair. Undetectable without track context, so it runs
     * once per load in MediaTracker.prepared (cache hit, race winner and
     * manual apply all pass through it) rather than in the context-free
     * [stripNonLyric]. Only LEADING lines qualify, and only when the FULL
     * title and artist both appear: a sung hook that merely equals the title
     * ("Hello") is real lyrics, a leading line spelling title+artist
     * verbatim is not a lyric. Never persisted — like the Chinese form
     * conversion, the cache keeps the provider's original bytes.
     */
    fun stripTitleArtistHeaders(
        lines: List<LyricLine>,
        title: String?,
        artist: String?
    ): List<LyricLine> {
        val nt = headerKey(title.orEmpty())
        val na = headerKey(artist.orEmpty())
        if (nt.length < 2 || na.length < 2) return lines
        val window = minOf(TITLE_HEADER_WINDOW, lines.size)
        fun key(i: Int): String = headerKey(lines[i].text)
        val drop = BooleanArray(lines.size)
        for (i in 0 until window) {
            val n = key(i)
            if (n.isEmpty() || drop[i]) continue
            if (n == nt + na || n == na + nt) {
                drop[i] = true
                continue
            }
            if (n.contains(nt) && n.contains(na) &&
                lines[i].text.trim().length <= nt.length + na.length + 16 &&
                HEADER_LABELS.any { n.contains(it) }
            ) {
                drop[i] = true
                continue
            }
            // Adjacent standalone pair: one line IS the title, the next IS
            // the artist (either order). Exact on both, so a title-equal
            // hook alone is never dropped.
            if (i + 1 < window &&
                (n == nt && key(i + 1) == na || n == na && key(i + 1) == nt)
            ) {
                drop[i] = true
                drop[i + 1] = true
            }
        }
        return if (drop.none { it }) lines else lines.filterIndexed { i, _ -> !drop[i] }
    }

    /** Lowercase, fullwidth-ASCII folded, letters+digits only: spaces and
     * every dash/slash/bracket/colon spelling of a header collapse away. */
    private fun headerKey(text: String): String =
        text.lowercase().map { c ->
            if (c in '\uFF01'..'\uFF5E') (c - 0xFEE0) else c
        }.filter { it.isLetterOrDigit() }.joinToString("")

    private const val TITLE_HEADER_WINDOW = 3

    /** Words that mark a leading title/artist line as a labeled header
     * ("歌曲：X 歌手：Y"). Substring-checked on the header key, so they must
     * be long enough to never survive inside plausible sung text alongside
     * both names. */
    private val HEADER_LABELS = setOf(
        "歌曲", "歌名", "曲名", "標題", "标题", "歌手", "演唱", "主唱", "演唱",
        "singer", "song", "title", "artist", "vocals"
    )

    private const val CREDIT_LABEL_MAX_LEN = 14

    /** Labels accepted before a colon in a credit line ("词：林夕",
     * "Lyrics: X", "OP: …"). Matched exactly on the trimmed, lowercased
     * prefix, so no loose substring ever fires. */
    private val CREDIT_LABELS = setOf(
        "词", "作詞", "作词", "歌詞", "歌词", "lyrics", "lyric", "words", "word",
        "曲", "作曲", "編曲", "编曲", "音乐", "音樂", "music", "composer", "compose",
        "arrangement", "arranged", "arr",
        "制作人", "製作人", "producer", "produced",
        "监制", "監製", "出品", "出品人", "出版", "publisher", "published",
        "原唱", "原創", "原曲", "original",
        "演唱", "主唱", "vocal", "vocals", "feat",
        "混音", "mixed", "mix", "recording", "recorded",
        "words & music", "words and music",
        "op", "sp"
    )

    /** By-form credit openers ("Lyrics by John Smith", "Lyricist: X"). */
    private val CREDIT_PHRASES = listOf(
        "lyrics by", "words by", "music by", "composed by", "written by",
        "arranged by", "produced by", "lyricist", "composer",
        "original singer", "original artist"
    )

    /** Standalone markers providers ship WITHOUT brackets. Restricted to
     * CJK: a lone sung line reading "間奏" is a section tag, one reading
     * "Verse" is lyrics. */
    private val BARE_NON_LYRIC = setOf(
        "音乐", "纯音乐", "副歌", "主歌", "間奏", "前奏", "尾奏", "说唱"
    )

    private fun String.stripBrackets(): String? {
        val pairs = listOf('[' to ']', '(' to ')', '【' to '】', '［' to '］')
        for ((open, close) in pairs) {
            if (length >= 2 && first() == open && last() == close) return substring(1, length - 1).trim()
        }
        return null
    }
}
