package com.autolyrics.auto

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.autolyrics.lyrics.LlmTranslator
import com.autolyrics.lyrics.LyricsTranslator
import com.autolyrics.lyrics.TranslationLanguages
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import com.autolyrics.util.AaKaraokeRows
import com.autolyrics.util.LyricDisplay
import com.autolyrics.util.SungLine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class LyricsBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaTracker: MediaTracker
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var lastNotifyTime = 0L
    private var pendingNotify = false
    private var pendingNotifyMask = 0
    private var displayedWindowStart = -1
    private var displayedGap = false
    private var displayedCountdown = 0
    private var displayedWindowEnd = -1
    private var displayedCurrentIdx = -1
    private var displayedStatus: LyricsStatus? = null
    private var displayedTrack: TrackInfo? = null
    private var displayedSource: String? = null
    private var displayedDetectedLanguage: String? = null
    private var displayedHasTranslation = false
    private var lastSubtitleText: String? = null
    private var lastCardTrack: TrackInfo? = null
    private var lastCardTitle: String? = null
    private var lastPlaybackTrack: TrackInfo? = null
    private var lastPlaybackIsPlaying: Boolean? = null
    private var lastAlbumArt: Bitmap? = null

    private var aaOffsetMs = 0L
    private var carIconSizePx = -1

    // Minimum display time for changing chunks on the compact now-playing card.
    private val titleChunkHold = ChunkHold(MIN_CHUNK_DISPLAY_MS)
    private val translationChunkHold = ChunkHold(MIN_CHUNK_DISPLAY_MS)

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                "aa_offset_ms" -> {
                    aaOffsetMs = sp.getLong(key, 0L)
                    forceRefresh()
                }
                TranslationLanguages.TARGET_LANGUAGE_PREF_KEY,
                "translation_enabled",
                "aa_karaoke_enabled" -> {
                    forceRefresh()
                }
            }
        }

    companion object {
        /** Per-car nudge (in characters) added to the hint-derived karaoke
         *  column; written by the phone settings SYNC section. */
        internal const val KARAOKE_EDGE_PREF_KEY = "aa_karaoke_edge"

        private const val ROOT_ID = "root"
        private const val LYRICS_MENU_ID = "lyrics_menu"
        private const val KARAOKE_MENU_ID = "karaoke_menu"
        private const val SYNC_MENU_ID = "sync_menu"
        private const val MORE_MENU_ID = "more_menu"
        private const val SYNC_MINUS_ID = "sync_minus"
        private const val SYNC_PLUS_ID = "sync_plus"
        private const val SYNC_STEP_MS = 50L
        private const val SYNC_WINDOW_SIZE = 3
        private const val DEFAULT_WINDOW_SIZE = 5
        // Host rule learned from live rows: title lines drop the U+2000-family
        // spaces but keep ideographic spaces, so leads and padding are built
        // from \u3000 (1.0 em) and the marker from \u25b6 + an ASCII space
        // (~1.06 em) — text starts at one position with or without the marker.
        // Title text must start at one x in both states AND meet the
        // subtitle's text (native indent ~37 px on DHU, which swallows all
        // leading whitespace). The ▶ glyph is ~45 px, so the subtitle is
        // pushed to it instead: no-break space is invisible but NOT
        // Java-whitespace, halting the trim and rendering at full advance.
        // Leads are pixel-tuned constants; retune from live screenshots.
        private const val CURRENT_LINE_PREFIX = "\u25b6"
        private const val IDLE_LINE_PREFIX = "\u200B\u00A0\u00A0\u00A0\u00A0"

        // Subtitle fields strip NBSP (titles render it), but behind the
        // ZWSP guard they DO keep ideographic and ASCII spaces: this run
        // measures onto the bare-marker title text (~150 px on DHU) and the
        // NBSP-guarded idle lead. Pixel-tuned; retune from live shots.
        private const val TRANSLATION_SUBTITLE_PREFIX = "\u200B\u3000 "
        private const val PAD_WIDTH = 60
        // Default right-edge column for the Karaoke tab's right-justified row,
        // in display units (CJK = 2). Rows wrap (never shrink) past their
        // width, so the true column is screen-dependent and beyond what the
        // phone can know: this default suits a Desktop Head Unit row, and the
        // Sync tab's edge rows calibrate it per car. Padded width must stay
        // under the row's capacity or the row wraps to invisible space lines.
        private const val KARAOKE_COLUMN_DEFAULT = 42

        /** Gearhead root hint: art icon px the host wants (viewport-scaled). */
        private const val BROWSER_ICON_SIZE_HINT = "com.google.android.gms.car.media.BrowserIconSize"
        private const val NO_LYRICS_MESSAGE = "No lyrics found for this track"
        private const val TRANSLATION_LOADING_MESSAGE = "Loading translation…"
        private const val NOTIFY_THROTTLE_MS = 500L
        private const val SESSION_REFRESH_MS = 1500L
        private const val PLAIN_LOOP_DELAY_MS = 500L
        private const val NOTIFY_LYRICS = 1
        private const val NOTIFY_SYNC = 2
        private const val NOTIFY_MORE = 4
        private const val NOTIFY_KARAOKE = 8
        private const val NOTIFY_ALL = NOTIFY_LYRICS or NOTIFY_SYNC or NOTIFY_MORE or NOTIFY_KARAOKE

        // Now-playing card width budgets, in display units (1 per Latin char, 2 per
        // fullwidth/CJK char incl. ??). These are estimates: calibrate on the head unit.
        // Title uses a larger font than subtitle, so it fits less.
        private const val TITLE_MAX_WIDTH = 28
        private const val SUBTITLE_MAX_WIDTH = 32
        // Minimum time an estimated (non-ELRC) chunk stays up before the next one, to avoid flicker.
        private const val MIN_CHUNK_DISPLAY_MS = 1000L
        // Assumed duration of the last synced line when the track duration is unknown.
        private const val DEFAULT_LAST_LINE_MS = 5000L
    }

    override fun onCreate() {
        super.onCreate()
        mediaTracker = MediaTracker.getInstance(this)

        val prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
        aaOffsetMs = prefs.getLong("aa_offset_ms", 0L)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        mediaSession = MediaSessionCompat(this, "AutoLyrics").apply {
            setCallback(SessionCallback())
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        scope.launch {
            mediaTracker.state.collectLatest { state ->
                updateMediaSession(state)
                throttledNotifyChildren(state)
            }
        }

        scope.launch {
            while (isActive) {
                delay(200)
                updateSubtitleKaraoke()
            }
        }

        scope.launch {
            while (isActive) {
                delay(PLAIN_LOOP_DELAY_MS)
                val state = mediaTracker.state.value
                // PLAIN rows are position estimates and always need the tick;
                // timed rows additionally need it for instrumental-gap flips
                // and the outro, where MediaTracker emits nothing because no
                // line index changes. The gate itself stays free when the
                // window is unchanged.
                if (state.isPlaying &&
                    (state.status == LyricsStatus.PLAIN_ONLY || state.status == LyricsStatus.FOUND) &&
                    state.lines.isNotEmpty()
                ) {
                    throttledNotifyChildren(state)
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(SESSION_REFRESH_MS)
                val state = mediaTracker.state.value
                if (state.track != null) {
                    mediaSession.isActive = true
                    publishPlaybackState(state, force = true)
                }
            }
        }
    }

    override fun onDestroy() {
        getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        scope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        when (val hint = rootHints?.get(BROWSER_ICON_SIZE_HINT)) {
            is Number -> hint.toInt()
            else -> hint?.toString()?.trim()?.toIntOrNull()
        }?.let { if (it > 0) carIconSizePx = it }
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        buildChildren(parentId, result)
    }

    private fun buildChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val state = mediaTracker.state.value
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()

        when (parentId) {
            ROOT_ID -> {
                // Keep the primary lyrics view first so Android Auto selects it as
                // the default browse tab. Sync is a secondary utility and More is
                // reserved for detailed track/provider information.
                items.add(buildBrowsableItem(LYRICS_MENU_ID, "Lyrics", "Current lyrics"))
                items.add(buildBrowsableItem(KARAOKE_MENU_ID, "Karaoke", "Two-line lyric board"))
                items.add(buildBrowsableItem(SYNC_MENU_ID, "Sync", "Adjust offset"))
                items.add(buildBrowsableItem(MORE_MENU_ID, "More", "Track and lyrics details"))
            }
            LYRICS_MENU_ID -> buildLyricsMenu(state, items)
            KARAOKE_MENU_ID -> buildKaraokeMenu(state, items)
            SYNC_MENU_ID -> buildSyncMenu(state, items)
            MORE_MENU_ID -> buildMoreMenu(state, items)
            SYNC_MINUS_ID, SYNC_PLUS_ID -> Unit
        }

        result.sendResult(items)
    }

    private fun buildLyricsMenu(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        when (state.status) {
            LyricsStatus.NO_MEDIA -> {
                items.add(buildTextItem("no_media", "Play a song to see lyrics"))
            }
            LyricsStatus.LOADING -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("loading", "Loading lyrics…"))
            }
            LyricsStatus.NOT_FOUND -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("not_found", "No lyrics found for this track"))
            }
            LyricsStatus.ERROR -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("error", "Error loading lyrics"))
            }
            LyricsStatus.FOUND -> {
                addTrackHeader(state, items)
                buildWindowedLyrics(state, items)
            }
            LyricsStatus.PLAIN_ONLY -> {
                addTrackHeader(state, items)
                if (state.lines.isEmpty()) {
                    items.add(buildTextItem("empty", "♪"))
                } else {
                    val durationMs = state.track?.durationMs ?: 0
                    val posMs = try {
                        mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
                    } catch (_: Exception) { 0L }

                    val plainCount = renderableLineCount(state.lines)
                    val estimatedIdx = if (durationMs > 0) {
                        ((posMs.toFloat() / durationMs) * plainCount).toInt()
                            .coerceIn(0, plainCount - 1)
                    } else { 0 }

                    val windowSize = DEFAULT_WINDOW_SIZE
                    val adjStart = stableWindowStart(estimatedIdx, plainCount)
                    val winEnd = minOf(plainCount, adjStart + windowSize)

                    var prevBlank = false
                    for (i in adjStart until winEnd) {
                        val blank = LyricDisplay.isSymbolText(state.lines[i].text)
                        if (blank && prevBlank && i != estimatedIdx) continue
                        prevBlank = blank
                        val text = LyricDisplay.displayText(state.lines[i].text)
                        val prefix = linePrefix(i == estimatedIdx)
                        val trans = translationFor(state, i, state.lines[i].text)
                        items.add(buildTextItem("line_$i", "$prefix$text", pad = true, subtitle = trans))
                    }
                }
            }
        }
    }

    /**
     * Chinese-Karaoke style board: the active line pair stays on screen, the
     * top row left-justified and the bottom row right-justified. Each side is
     * replaced by the next line on its own track (current + 2) when its line
     * finishes, so the two rows always carry the next two lines to be sung.
     */
    private fun buildKaraokeMenu(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        if (state.status != LyricsStatus.FOUND || state.lines.isEmpty()) {
            // Without word-timed lines there is no board to build: mirror the
            // Lyrics tab exactly (plain-text window, loading, not-found).
            buildLyricsMenu(state, items)
            return
        }
        addTrackHeader(state, items)
        val column = karaokeColumn()

        val posMs = getAaPositionMs()
        val boardCount = renderableLineCount(state.lines)
        val idx = SungLine.index(state.lines, posMs, boardCount)
        var (topIdx, bottomIdx) = AaKaraokeRows.pairFor(idx, boardCount)
        // Proven instrumental gap: the sung line STAYS lit on its own row and
        // the music symbol takes the opposite slot in place of the preview.
        // -2 is the symbol slot sentinel (inactive: no marker, no seek).
        val boardGap = idx >= 0 && SungLine.instrumentalGap(state.lines, posMs, boardCount)
        if (boardGap) {
            if (bottomIdx == idx) topIdx = -2 else bottomIdx = -2
        }
        if (idx < 0) {
            // Instrumental stretch (intro or interlude): the next line keeps
            // the row it will be sung from (even indices ride the bottom),
            // and the music symbol rides the other slot as the active row.
            val anchorNext = (findLineIndex(state.lines, posMs) + 1).coerceAtMost(boardCount)
            val nextIdx = if (anchorNext < boardCount) anchorNext else -1
            when {
                nextIdx < 0 -> { topIdx = -1; bottomIdx = -1 }
                nextIdx % 2 == 0 -> { topIdx = -1; bottomIdx = nextIdx }
                else -> { topIdx = nextIdx; bottomIdx = -1 }
            }
        }
        val topActive = topIdx == idx
        val bottomActive = bottomIdx == idx
        // Instrumental stretches (intro or proven gap) show a countdown
        // instead of the static triple: one glyph per second until the next
        // line, max three, on the marker-free lead that still holds the
        // ▶ advance.
        val cdSym = if (idx < 0 || boardGap)
            LyricDisplay.countdownSymbol(SungLine.countdownSeconds(state.lines, posMs, boardCount))
        else null

        // A missing side is the virtual slot: sung during the intro (symbol),
        // simply empty past the last lyric so the outro shows no symbols.
        // The -2 gap slot renders the symbol on an idle lead.
        val topLine = state.lines.getOrNull(topIdx)
        val bottomLine = state.lines.getOrNull(bottomIdx)
        val topSymbolSlot = topIdx == -2
        val bottomSymbolSlot = bottomIdx == -2
        val topText = topLine?.let { LyricDisplay.displayText(it.text) }
            ?: (if (topSymbolSlot) cdSym.orEmpty()
                else cdSym.takeIf { topActive }.orEmpty())
        val bottomText = bottomLine?.let { LyricDisplay.displayText(it.text) }
            ?: (if (bottomSymbolSlot) cdSym.orEmpty()
                else cdSym.takeIf { bottomActive }.orEmpty())

        val translationActive = state.translatedLines?.isNotEmpty() == true
        val cdTrans = cdSym?.takeIf { translationActive }
        val topTranslation = topLine?.let { translationFor(state, topIdx, it.text) }
            ?: (if (topSymbolSlot) cdTrans else cdTrans.takeIf { topActive })
        val bottomTranslation = bottomLine?.let { translationFor(state, bottomIdx, it.text) }
            ?: (if (bottomSymbolSlot) cdTrans else cdTrans.takeIf { bottomActive })

        // The host sizes title and subtitle glyphs independently per row
        // (live hosts render the same design's subtitle at ~0.7x or ~1.0x of
        // the title), so padding across the two fields can never hold an
        // edge. One multi-line title shares a single font scale, making the
        // padded tails of both lines land on the same edge by construction.
        val topMarker = if (topActive && idx >= 0) CURRENT_LINE_PREFIX else IDLE_LINE_PREFIX
        val topId = if (topIdx >= 0) "line_$topIdx" else "karaoke_hold"
        items.add(
            buildTextItem(
                topId,
                buildString {
                    append(topMarker).append(topText)
                    if (topTranslation != null) {
                        // Byte-identical lead to the lyric line (marker
                        // included), so both lines start at one edge with no
                        // glyph-width modeling involved.
                        append('\n').append(topMarker).append(topTranslation)
                    }
                }
            )
        )

        val bottomMarker = AaKaraokeRows.markerFor(
            bottomActive && idx >= 0,
            bottomText,
            column,
            CURRENT_LINE_PREFIX
        )
        val bottomId = if (bottomIdx >= 0) "line_$bottomIdx" else "karaoke_hold"
        items.add(
            buildTextItem(
                bottomId,
                buildString {
                    append(AaKaraokeRows.rightAlign(bottomText, column, bottomMarker))
                    if (bottomTranslation != null) {
                        // Same marker string and the same over-estimated
                        // marker allowance as the lyric line: the rendered ▶
                        // advance cancels out of both tails equally, so they
                        // land on one edge whatever it is.
                        append('\n').append(AaKaraokeRows.rightAlign(bottomTranslation, column, bottomMarker))
                    }
                }
            )
        )
    }

    /** Right-edge column of the Karaoke board: host hint plus the phone's edge nudge. */
    private fun karaokeColumn(): Int {
        val edge = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
            .getInt(KARAOKE_EDGE_PREF_KEY, 0)
        return AaKaraokeRows.columnFromIconSize(carIconSizePx, edge, KARAOKE_COLUMN_DEFAULT)
    }

    private fun buildSyncMenu(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val sign = if (aaOffsetMs >= 0) "+" else ""
        items.add(buildTextItem("sync_offset", "AA Offset: ${sign}${aaOffsetMs}ms"))
        if (state.status == LyricsStatus.NOT_FOUND) {
            items.add(buildTextItem("sync_not_found", NO_LYRICS_MESSAGE))
        }
        if (state.lines.isNotEmpty()) {
            items.add(
                buildTextItem(
                    SYNC_MINUS_ID,
                    "◀◀  Delay lyrics",
                    subtitle = "−${SYNC_STEP_MS} ms"
                )
            )

            val posMs = getAaPositionMs()
            val syncCount = renderableLineCount(state.lines)
            val rawIdx = if (state.status == LyricsStatus.FOUND) {
                SungLine.index(state.lines, posMs, syncCount)
            } else {
                val durationMs = state.track?.durationMs ?: 0
                if (durationMs > 0) {
                    ((posMs.toFloat() / durationMs) * syncCount).toInt()
                        .coerceIn(0, syncCount - 1)
                } else 0
            }
            val idx = rawIdx

            // Current line pinned first: the window reads forward from the line
            // being sung; at the song end it shows fewer rows, never back-fills.
            if (idx < 0) {
                val introTrans = translationFor(state, -1, "")
                items.add(
                    buildTextItem(
                        "sync_line_intro",
                        "$CURRENT_LINE_PREFIX${LyricDisplay.MUSIC_SYMBOL}",
                        pad = true,
                        subtitle = introTrans
                    )
                )
                val anchorNext = (findLineIndex(state.lines, posMs) + 1).coerceAtMost(syncCount)
                var prevSymbol = true
                for (i in anchorNext until minOf(syncCount, anchorNext + SYNC_WINDOW_SIZE - 1)) {
                    val line = state.lines[i]
                    if (LyricDisplay.isSymbolText(line.text) && prevSymbol) continue
                    prevSymbol = LyricDisplay.isSymbolText(line.text)
                    val trans = translationFor(state, i, line.text)
                    items.add(
                        buildTextItem(
                            "sync_line_$i",
                            "$IDLE_LINE_PREFIX${LyricDisplay.displayText(line.text)}",
                            pad = true,
                            subtitle = trans
                        )
                    )
                }
                return
            }
            val gap = SungLine.instrumentalGap(state.lines, posMs, syncCount)
            val windowEnd = minOf(syncCount, idx + SYNC_WINDOW_SIZE - (if (gap) 1 else 0))
            val adjustedStart = idx

            var prevSymbol = false
            for (i in adjustedStart until windowEnd) {
                val line = state.lines[i]
                val isCurrent = i == idx
                val symbol = LyricDisplay.isSymbolText(line.text)
                if (symbol && prevSymbol && !isCurrent) continue
                prevSymbol = symbol
                val text = LyricDisplay.displayText(line.text)
                val trans = translationFor(state, i, line.text)
                items.add(
                    buildTextItem(
                        "sync_line_$i",
                        "${linePrefix(isCurrent)}$text",
                        pad = true,
                        subtitle = trans
                    )
                )
                if (isCurrent && gap) {
                    items.add(
                        buildTextItem(
                            "sync_line_gap",
                            "$IDLE_LINE_PREFIX${LyricDisplay.MUSIC_SYMBOL}",
                            pad = true,
                            subtitle = translationFor(state, -1, "")
                        )
                    )
                }
            }

            items.add(
                buildTextItem(
                    SYNC_PLUS_ID,
                    "▶▶  Advance lyrics",
                    subtitle = "+${SYNC_STEP_MS} ms"
                )
            )
        }


    }

    private fun buildMoreMenu(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val track = state.track
        if (track == null) {
            items.add(buildTextItem("more_no_media", "Play a song to see details"))
            return
        }

        items.add(buildTextItem("more_title", "Title", subtitle = track.title))
        if (track.artist.isNotBlank()) {
            items.add(buildTextItem("more_artist", "Artist", subtitle = track.artist))
        }
        if (track.album.isNotBlank()) {
            items.add(buildTextItem("more_album", "Album", subtitle = track.album))
        }

        val provider = state.source
            .substringBefore("·")
            .substringBefore("(")
            .trim()
            .ifBlank { "—" }
        items.add(buildTextItem("more_provider", "Provider", subtitle = provider))


        val syncStatus = when (state.status) {
            LyricsStatus.FOUND -> "Synced"
            LyricsStatus.PLAIN_ONLY -> "Not synced"
            LyricsStatus.LOADING -> "Loading"
            LyricsStatus.NOT_FOUND -> "Not found"
            LyricsStatus.ERROR -> "Error"
            LyricsStatus.NO_MEDIA -> "—"
        }
        items.add(buildTextItem("more_sync", "Lyrics", subtitle = syncStatus))

        val sourceLanguage = TranslationLanguages.normalizeLanguageTag(state.detectedLanguage)
        if (sourceLanguage != null) {
            if (state.translatedLines != null) {
                val targetLanguage = selectedTranslationTarget()
                items.add(
                    buildTextItem(
                        "more_translation",
                        "Translation",
                        subtitle = "${sourceLanguage.uppercase()} → ${targetLanguage.uppercase()}"
                    )
                )
            } else {
                items.add(
                    buildTextItem(
                        "more_language",
                        "Language",
                        subtitle = sourceLanguage.uppercase()
                    )
                )
            }
        }
        if (track.durationMs > 0) {
            items.add(buildTextItem("more_duration", "Duration", subtitle = formatTime(track.durationMs)))
        }

        val sign = if (aaOffsetMs >= 0) "+" else ""
        items.add(buildTextItem("more_offset", "AA Offset", subtitle = "${sign}${aaOffsetMs}ms"))

    }

    private fun addTrackHeader(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val track = state.track ?: return

        val subtitle = buildString {
            append(track.artist)
            val posMs = try {
                mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
            } catch (_: Exception) { -1L }
            if (posMs >= 0 && track.durationMs > 0) {
                append("  ·  ")
                append(formatTime(posMs))
                append(" / ")
                append(formatTime(track.durationMs))
            }
            val typeLabel = lyricsTypeLabel(state)
            if (typeLabel.isNotBlank()) {
                append("  ·  ")
                append(typeLabel)
            }
        }

        val descBuilder = MediaDescriptionCompat.Builder()
            .setMediaId("header")
            .setTitle(track.title)
            .setSubtitle(subtitle)

        val headerArt = state.albumArt ?: lastAlbumArt
        headerArt?.let { art ->
            descBuilder.setIconBitmap(art)
        }

        items.add(
            MediaBrowserCompat.MediaItem(
                descBuilder.build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        )
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    private fun linePrefix(isCurrent: Boolean): String {
        return if (isCurrent) CURRENT_LINE_PREFIX else IDLE_LINE_PREFIX
    }

    private fun buildWindowedLyrics(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val lines = state.lines
        if (lines.isEmpty()) {
            items.add(buildTextItem("empty", LyricDisplay.MUSIC_SYMBOL))
            return
        }

        val count = renderableLineCount(lines)
        val posMs = getAaPositionMs()
        // Past the last real lyric the outro plays under it: the sung line
        // stays on the final lyric instead of advancing into symbol rows.
        val aaCurrentIdx = SungLine.index(lines, posMs, count)
        val windowSize = DEFAULT_WINDOW_SIZE
        val cdSym = if (aaCurrentIdx < 0 || SungLine.instrumentalGap(lines, posMs, count))
            LyricDisplay.countdownSymbol(SungLine.countdownSeconds(lines, posMs, count))
        else null
        val cdTrans = cdSym?.takeIf { state.translatedLines?.isNotEmpty() == true }

        if (aaCurrentIdx < 0) {
            // Mid-song instrumental gaps preview from the line AFTER the gap,
            // not from the top of the song.
            val anchorNext = (findLineIndex(lines, posMs) + 1).coerceAtMost(count)
            // Instrumental intro: the sung row is the music symbol until the
            // first timed line starts, with the coming lines previewed below.
            items.add(
                buildTextItem(
                    "line_intro",
                    "$IDLE_LINE_PREFIX$cdSym",
                    pad = true,
                    subtitle = cdTrans
                )
            )
            var prevSymbol = true
            for (i in anchorNext until minOf(count, anchorNext + windowSize - 1)) {
                val line = lines[i]
                if (LyricDisplay.isSymbolText(line.text) && prevSymbol) continue
                prevSymbol = LyricDisplay.isSymbolText(line.text)
                val trans = translationFor(state, i, line.text)
                items.add(
                    buildTextItem(
                        "line_$i",
                        "$IDLE_LINE_PREFIX${LyricDisplay.displayText(line.text)}",
                        pad = true,
                        subtitle = trans
                    )
                )
            }
            return
        }

        // The sung line is always the first lyric row; near the song end the
        // window simply shows fewer rows instead of back-filling old lines.
        // A proven instrumental gap INSERTS a music-symbol row under the
        // (still lit) sung line rather than swapping the sung row out.
        val gap = SungLine.instrumentalGap(lines, posMs, count)
        val adjustedStart = aaCurrentIdx
        val windowEnd = minOf(count, adjustedStart + windowSize - (if (gap) 1 else 0))

        var prevSymbol = false
        for (i in adjustedStart until windowEnd) {
            val line = lines[i]
            val isCurrent = i == aaCurrentIdx
            // Consecutive interlude symbols mean the same thing as one, so
            // they collapse to a single row. The line being sung never skips.
            val symbol = LyricDisplay.isSymbolText(line.text)
            if (symbol && prevSymbol && !isCurrent) continue
            prevSymbol = symbol
            val prefix = linePrefix(isCurrent)

            val text = LyricDisplay.displayText(line.text)

            val trans = translationFor(state, i, line.text)
            items.add(buildTextItem("line_$i", "$prefix$text", pad = true, subtitle = trans))
            if (isCurrent && gap) {
                items.add(
                    buildTextItem(
                        "line_gap",
                        "$IDLE_LINE_PREFIX$cdSym",
                        pad = true,
                        subtitle = cdTrans
                    )
                )
            }
        }
    }

    private fun buildTextItem(id: String, text: String, pad: Boolean = false, subtitle: String? = null): MediaBrowserCompat.MediaItem {
        val title = if (pad) text.padEnd(PAD_WIDTH) else text
        val alignedSubtitle = if (
            !subtitle.isNullOrBlank() &&
            (id.startsWith("line_") || id.startsWith("sync_line_"))
        ) {
            "$TRANSLATION_SUBTITLE_PREFIX$subtitle"
        } else {
            subtitle
        }
        val builder = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
        if (!alignedSubtitle.isNullOrBlank()) builder.setSubtitle(alignedSubtitle)
        return MediaBrowserCompat.MediaItem(
            builder.build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    private fun buildBrowsableItem(id: String, title: String, subtitle: String): MediaBrowserCompat.MediaItem {
        return MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setSubtitle(subtitle)
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
    }

    private fun lyricsTypeLabel(state: LyricsState): String {
        // Header carries only the not-synced warning: provider, the "Synced"
        // confirmation and language info all live on the More tab.
        return when (state.status) {
            LyricsStatus.PLAIN_ONLY -> "⟳ Not synced"
            else -> ""
        }
    }

    private fun selectedTranslationTarget(): String {
        val prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
        return TranslationLanguages.normalizeTargetLanguage(
            prefs.getString(
                TranslationLanguages.TARGET_LANGUAGE_PREF_KEY,
                TranslationLanguages.DEFAULT_TARGET_LANGUAGE
            )
        )
    }

    // --- Karaoke helpers ---

    private fun getAaPositionMs(): Long {
        return try {
            mediaTracker.getCurrentPositionMs() + aaOffsetMs
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Line count for display purposes: trailing music-symbol lines (outros)
     * are dropped, so the last real lyric stays the sung line while the song
     * plays out instead of showing a run of symbol rows.
     */
    private fun renderableLineCount(lines: List<LyricLine>): Int {
        val last = LyricDisplay.lastMeaningfulIndex(lines)
        return if (last < 0) lines.size else last + 1
    }

    /**
     * Translation subtitle for a rendered line. Translators pass music
     * symbols through unchanged or skip them, so a symbol line's subtitle
     * falls back to the symbol itself whenever translations are active.
     */
    private fun translationFor(state: LyricsState, index: Int, lineText: String): String? {
        val active = state.translatedLines?.isNotEmpty() == true
        if (LyricDisplay.isSymbolText(lineText)) {
            return LyricDisplay.MUSIC_SYMBOL.takeIf { active }
        }
        return state.translatedLines?.getOrNull(index)?.takeIf { it.isNotBlank() }
    }

    private fun findLineIndex(lines: List<LyricLine>, posMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) idx = i
            else break
        }
        return idx
    }

    private fun resetNowPlayingState() {
        titleChunkHold.reset()
        translationChunkHold.reset()
    }

    private class CardText(val lyricTitle: String?, val subtitle: String)

    /**
     * Text for the now-playing card.
     *
     * While a lyric line is active: DISPLAY_TITLE = the current line chunk,
     * DISPLAY_SUBTITLE = what comes next (see [NowPlayingText.subtitleFor]).
     * Otherwise (intro before the first timed line, loading, not found, error)
     * [CardText.lyricTitle] is null so the title stays "Title — Artist", and the subtitle
     * is the status text.
     */
    private fun getCardText(state: LyricsState): CardText {
        getLyricCardText(state)?.let { return it }

        var subtitle = when (state.status) {
            // Intro: preview the first line while the title still shows the track.
            // Intro (and outro past the last line's end): track identity
            // alone — title and artist, no lyrics text.
            LyricsStatus.FOUND -> ""
            LyricsStatus.LOADING -> "Loading lyrics…"
            LyricsStatus.NOT_FOUND -> "No lyrics found"
            LyricsStatus.ERROR -> "Error loading lyrics"
            else -> ""
        }
        if (state.status == LyricsStatus.FOUND && translationInFlight()) {
            subtitle = TRANSLATION_LOADING_MESSAGE
        }
        return CardText(null, subtitle)
    }

    /**
     * True while the selected engine is actively working on a translation
     * (detect, model download, or translate). The now-playing subtitle mirrors
     * the "Loading lyrics…" UX with "Loading translation…" while this holds.
     */
    private fun translationInFlight(): Boolean {
        val prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("translation_enabled", true)) return false
        val engine = if (prefs.getString(LlmTranslator.PREF_ENGINE_KEY, "on_device") == LlmTranslator.ENGINE_LLM) {
            LlmTranslator
        } else {
            LyricsTranslator
        }
        return when (engine.uiState.value.phase) {
            LyricsTranslator.Phase.DETECTING_LANGUAGE,
            LyricsTranslator.Phase.CHECKING_MODEL,
            LyricsTranslator.Phase.DOWNLOADING_MODEL,
            LyricsTranslator.Phase.WAITING_FOR_SYSTEM,
            LyricsTranslator.Phase.TRANSLATING -> true
            else -> false
        }
    }

    private fun getLyricCardText(state: LyricsState): CardText? {
        val lines = state.lines
        if (lines.isEmpty()) return null
        val posMs = getAaPositionMs()
        val durationMs = state.track?.durationMs ?: 0L

        val idx: Int
        val startMs: Long
        val endMs: Long
        when (state.status) {
            LyricsStatus.FOUND -> {
                // Same policy as the browse tabs: the outro keeps the last
                // real lyric lit... until its END (payload word ends, or a
                // 5 s fallback), after which the card shows track identity.
                val cardCount = renderableLineCount(lines)
                if (SungLine.pastLastLineEnd(lines, cardCount, posMs)) return null
                idx = SungLine.index(lines, posMs, cardCount)
                if (idx < 0) return null
                startMs = lines[idx].timeMs
                val next = lines.getOrNull(idx + 1)?.timeMs
                endMs = when {
                    next != null && next > startMs -> next
                    next == null && durationMs > startMs -> durationMs
                    else -> startMs + DEFAULT_LAST_LINE_MS
                }
            }
            LyricsStatus.PLAIN_ONLY -> {
                // Unsynced: same estimated index as before, each line gets an equal time slice.
                idx = if (durationMs > 0) {
                    ((posMs.toFloat() / durationMs) * lines.size).toInt()
                        .coerceIn(0, lines.size - 1)
                } else 0
                if (durationMs > 0) {
                    startMs = durationMs * idx / lines.size
                    endMs = durationMs * (idx + 1) / lines.size
                } else {
                    startMs = 0L
                    endMs = DEFAULT_LAST_LINE_MS
                }
            }
            else -> return null
        }

        val line = lines[idx]
        val nextText = lines.getOrNull(idx + 1)?.text
        val nowMs = SystemClock.elapsedRealtime()

        // A translation of the current line takes the subtitle instead of the next line.
        val translation = state.translatedLines?.getOrNull(idx)?.takeIf { it.isNotBlank() }
        val translationChunk = translation?.let {
            val chunks = NowPlayingText.chunkText(it, SUBTITLE_MAX_WIDTH)
            if (chunks.isEmpty()) return@let null
            val candidate = NowPlayingText.activeChunkByProportion(
                chunks.map(NowPlayingText::displayWidth), startMs, endMs, posMs
            )
            chunks[translationChunkHold.select(idx, candidate, nowMs).coerceIn(0, chunks.lastIndex)]
        }

        // Android Auto refreshes the whole card for every metadata update. Keep
        // the displayed line stable there; word-level timing remains available
        // to the phone UI and Performance view.
        val budget = TITLE_MAX_WIDTH
        val displayLine = LyricDisplay.displayText(line.text)
            .ifBlank { line.words.joinToString(" ") { it.text } }
        val chunkTexts = NowPlayingText.chunkDisplayText(displayLine, budget)
        val loadingTranslation = translationChunk == null && translationInFlight()

        if (chunkTexts.isEmpty()) {
            return CardText(
                NowPlayingText.END_MARK,
                if (loadingTranslation) TRANSLATION_LOADING_MESSAGE
                else NowPlayingText.subtitleFor(emptyList(), 0, translationChunk, nextText)
            )
        }

        val candidate = NowPlayingText.activeChunkByProportion(
            chunkTexts.map(NowPlayingText::displayWidth), startMs, endMs, posMs
        )
        val selectedChunk = titleChunkHold.select(idx, candidate, nowMs)
        val chunkIdx = selectedChunk.coerceIn(0, chunkTexts.lastIndex)

        val title = chunkTexts[chunkIdx]
        val subtitle = if (loadingTranslation) TRANSLATION_LOADING_MESSAGE
        else NowPlayingText.subtitleFor(chunkTexts, chunkIdx, translationChunk, nextText)
        return CardText(title, subtitle)
    }

    // --- MediaSession management ---

    private fun buildBaseMetadata(state: LyricsState, lyricTitle: String? = null): MediaMetadataCompat.Builder {
        val metaBuilder = MediaMetadataCompat.Builder()

        state.track?.let { track ->
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
            if (track.durationMs > 0) {
                metaBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
            }

            val displayTitle = lyricTitle ?: if (track.artist.isNotBlank()) {
                "${track.title} — ${track.artist}"
            } else {
                track.title
            }
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle)
        }

        val art = state.albumArt ?: lastAlbumArt
        if (art != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            lastAlbumArt = art
        }

        return metaBuilder
    }

    private fun updateMediaSession(state: LyricsState) {
        updateNowPlayingCard(state)
    }

    private fun updateNowPlayingCard(state: LyricsState) {
        val trackChanged = state.track != lastCardTrack
        if (trackChanged) {
            mediaSession.isActive = true
            lastSubtitleText = null
            lastAlbumArt = null
            resetNowPlayingState()
        }

        val card = getCardText(state)
        val track = state.track
        val displayTitle = card.lyricTitle ?: track?.let {
            if (it.artist.isNotBlank()) "${it.title} — ${it.artist}" else it.title
        }
        val artChanged = state.albumArt != null && state.albumArt !== lastAlbumArt
        val metadataChanged = trackChanged || artChanged ||
            displayTitle != lastCardTitle || card.subtitle != lastSubtitleText
        if (metadataChanged) {
            val metaBuilder = buildBaseMetadata(state, card.lyricTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, card.subtitle)
            mediaSession.setMetadata(metaBuilder.build())
            lastCardTrack = track
            lastCardTitle = displayTitle
            lastSubtitleText = card.subtitle
        }
        publishPlaybackState(state)
    }

    private fun publishPlaybackState(state: LyricsState, force: Boolean = false) {
        if (!force && state.track == lastPlaybackTrack && state.isPlaying == lastPlaybackIsPlaying) {
            return
        }
        mediaSession.setPlaybackState(buildPlaybackState(state))
        lastPlaybackTrack = state.track
        lastPlaybackIsPlaying = state.isPlaying
    }

    private fun updateSubtitleKaraoke() {
        val state = mediaTracker.state.value
        updateNowPlayingCard(state)
    }

    private fun buildPlaybackState(state: LyricsState): PlaybackStateCompat {
        val pbState = if (state.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else if (state.track != null) {
            PlaybackStateCompat.STATE_PAUSED
        } else {
            PlaybackStateCompat.STATE_NONE
        }

        val position = try {
            mediaTracker.getCurrentPositionMs()
        } catch (_: Exception) {
            0L
        }

        return PlaybackStateCompat.Builder()
            .setState(pbState, position, if (state.isPlaying) 1.0f else 0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .build()
    }

    // --- Throttled browse tree updates ---

    private data class WindowInfo(
        val start: Int,
        val end: Int,
        val currentIdx: Int,
        val gap: Boolean = false,
        val countdown: Int = 0
    )

    private fun computeWindow(state: LyricsState): WindowInfo {
        val lines = state.lines
        if (lines.isEmpty()) return WindowInfo(-1, -1, -1)

        val isPlain = state.status == LyricsStatus.PLAIN_ONLY
        val winSize = DEFAULT_WINDOW_SIZE
        if (isPlain) {
            val plainCount = renderableLineCount(lines)
            val durationMs = state.track?.durationMs ?: 0
            val posMs = try {
                mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
            } catch (_: Exception) { 0L }
            val estimated = if (durationMs > 0) {
                ((posMs.toFloat() / durationMs) * plainCount).toInt()
                    .coerceIn(0, plainCount - 1)
            } else 0
            val start = stableWindowStart(estimated, plainCount)
            return WindowInfo(start, minOf(plainCount, start + winSize), estimated)
        }

        // Mirror buildWindowedLyrics exactly: a change here must imply a
        // visible row change, or the throttled refresh gate swallows it (the
        // intro row clamped to line 0 used to mask the intro -> first-line
        // flip, leaving the symbol painted through the first verse).
        val count = renderableLineCount(lines)
        val posMs = getAaPositionMs()
        val raw = findLineIndex(lines, posMs)
        val idx = SungLine.index(lines, posMs, count)
        return if (idx < 0) {
            val anchor = (raw + 1).coerceAtMost(count)
            WindowInfo(anchor, minOf(count, anchor + winSize - 1), -1,
                countdown = SungLine.countdownSeconds(lines, posMs, count))
        } else {
            val gap = SungLine.instrumentalGap(lines, posMs, count)
            WindowInfo(idx, minOf(count, idx + winSize - (if (gap) 1 else 0)), idx, gap,
                countdown = if (gap) SungLine.countdownSeconds(lines, posMs, count) else 0)
        }
    }

    /** Pin the active line to the first row so it never moves within the list. */
    private fun stableWindowStart(currentIdx: Int, lineCount: Int): Int =
        currentIdx.coerceIn(0, maxOf(0, lineCount - DEFAULT_WINDOW_SIZE))

    private fun notifyBrowseSections(mask: Int = NOTIFY_ALL) {
        if (mask and NOTIFY_LYRICS != 0) notifyChildrenChanged(LYRICS_MENU_ID)
        if (mask and NOTIFY_KARAOKE != 0) notifyChildrenChanged(KARAOKE_MENU_ID)
        if (mask and NOTIFY_SYNC != 0) notifyChildrenChanged(SYNC_MENU_ID)
        if (mask and NOTIFY_MORE != 0) notifyChildrenChanged(MORE_MENU_ID)
    }

    private fun forceRefresh() {
        displayedWindowStart = -1
        displayedWindowEnd = -1
        displayedCurrentIdx = -1
        displayedGap = false
        displayedCountdown = 0
        displayedSource = null
        displayedDetectedLanguage = null
        displayedHasTranslation = false
        lastNotifyTime = 0L
        resetNowPlayingState()
        notifyBrowseSections()
    }

    private fun throttledNotifyChildren(state: LyricsState) {
        val statusChanged = state.status != displayedStatus
        val trackChanged = state.track != displayedTrack
        val sourceChanged = state.source != displayedSource
        val detectedLanguageChanged = state.detectedLanguage != displayedDetectedLanguage
        val hasTranslation = state.translatedLines != null
        val translationAvailabilityChanged = hasTranslation != displayedHasTranslation

        if (trackChanged) {
            displayedWindowStart = -1
            displayedWindowEnd = -1
            displayedCurrentIdx = -1
            displayedGap = false
            displayedStatus = state.status
            displayedTrack = state.track
            displayedSource = state.source
            displayedDetectedLanguage = state.detectedLanguage
            displayedHasTranslation = hasTranslation
            lastNotifyTime = System.currentTimeMillis()
            handler.removeCallbacksAndMessages(null)
            pendingNotify = false
            pendingNotifyMask = 0
            notifyBrowseSections()
            return
        }

        val win = computeWindow(state)
        val windowChanged = win.start != displayedWindowStart || win.end != displayedWindowEnd
        val lineChanged = win.currentIdx != displayedCurrentIdx
        val gapChanged = win.gap != displayedGap
        // The countdown row's text ticks once per second; sync's static
        // symbol rows don't carry it, so only lyrics/karaoke re-render.
        val countdownChanged = win.countdown != displayedCountdown
        if (!windowChanged && !lineChanged && !gapChanged && !countdownChanged &&
            !statusChanged && !sourceChanged && !detectedLanguageChanged &&
            !translationAvailabilityChanged
        ) {
            return
        }

        var notifyMask = 0
        if (windowChanged || lineChanged || gapChanged || countdownChanged || statusChanged ||
            sourceChanged || detectedLanguageChanged || translationAvailabilityChanged
        ) notifyMask = notifyMask or NOTIFY_LYRICS
        if (lineChanged || gapChanged || statusChanged || translationAvailabilityChanged) {
            notifyMask = notifyMask or NOTIFY_SYNC
        }
        if (lineChanged || gapChanged || countdownChanged || statusChanged ||
            translationAvailabilityChanged
        ) {
            notifyMask = notifyMask or NOTIFY_KARAOKE
        }
        if (statusChanged || sourceChanged || detectedLanguageChanged || translationAvailabilityChanged) {
            notifyMask = notifyMask or NOTIFY_MORE
        }

        displayedWindowStart = win.start
        displayedWindowEnd = win.end
        displayedCurrentIdx = win.currentIdx
        displayedGap = win.gap
        displayedCountdown = win.countdown
        displayedStatus = state.status
        displayedSource = state.source
        displayedDetectedLanguage = state.detectedLanguage
        displayedHasTranslation = hasTranslation

        val now = System.currentTimeMillis()
        val elapsed = now - lastNotifyTime

        if (elapsed >= NOTIFY_THROTTLE_MS) {
            lastNotifyTime = now
            notifyBrowseSections(notifyMask)
        } else if (!pendingNotify) {
            pendingNotify = true
            pendingNotifyMask = notifyMask
            handler.postDelayed({
                pendingNotify = false
                lastNotifyTime = System.currentTimeMillis()
                val mask = pendingNotifyMask
                pendingNotifyMask = 0
                notifyBrowseSections(mask)
            }, NOTIFY_THROTTLE_MS - elapsed)
        } else {
            pendingNotifyMask = pendingNotifyMask or notifyMask
        }
    }

    // --- Transport controls ---

    private fun getActiveMediaController(): MediaController? {
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE)
            as? android.media.session.MediaSessionManager ?: return null
        return try {
            val component = android.content.ComponentName(
                this, com.autolyrics.media.MediaListenerService::class.java
            )
            sessionManager.getActiveSessions(component)
                .firstOrNull { it.packageName != packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessionManager.getActiveSessions(component)
                    .firstOrNull { it.packageName != packageName }
        } catch (_: SecurityException) {
            null
        }
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            getActiveMediaController()?.transportControls?.play()
        }

        override fun onPause() {
            getActiveMediaController()?.transportControls?.pause()
        }

        override fun onSkipToNext() {
            getActiveMediaController()?.transportControls?.skipToNext()
        }

        override fun onSkipToPrevious() {
            getActiveMediaController()?.transportControls?.skipToPrevious()
        }

        override fun onStop() {
            getActiveMediaController()?.transportControls?.stop()
        }

        override fun onSeekTo(pos: Long) {
            getActiveMediaController()?.transportControls?.seekTo(pos)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId == null) return

            if (mediaId == SYNC_MINUS_ID || mediaId == SYNC_PLUS_ID) {
                if (mediaId == SYNC_MINUS_ID) aaOffsetMs -= SYNC_STEP_MS
                else aaOffsetMs += SYNC_STEP_MS
                getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
                    .edit().putLong("aa_offset_ms", aaOffsetMs).apply()
                notifyBrowseSections()
                return
            }

            if (!mediaId.startsWith("line_")) return
            val index = mediaId.removePrefix("line_").toIntOrNull() ?: return
            val state = mediaTracker.state.value
            if (state.status != LyricsStatus.FOUND) return
            val line = state.lines.getOrNull(index) ?: return
            if (line.timeMs > 0) {
                getActiveMediaController()?.transportControls?.seekTo(line.timeMs)
            }
        }
    }
}
