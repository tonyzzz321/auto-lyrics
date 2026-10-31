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
import java.util.Locale
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
    private var displayedHeaderSec = -1L
    private var displayedOffsetMs = 0L
    private var displayedWindowEnd = -1
    private var displayedCurrentIdx = -1
    private var displayedStatus: LyricsStatus? = null
    private var displayedTrack: TrackInfo? = null
    private var displayedSource: String? = null
    private var displayedDetectedLanguage: String? = null
    private var displayedHasTranslation = false
    private var displayedTranslationsVisible = false
    private var lastSubtitleText: String? = null
    private var lastCardTrack: TrackInfo? = null
    // Failure-state subtitle clock: "No lyrics found" / "Error loading
    // lyrics" hold the subtitle for a while, then the card relaxes to the
    // normal player layout (title + artist). Stamped when the status or
    // track changes; read by getCardText on every card rebuild.
    private var statusCardEntryMs = 0L
    private var statusCardTrack: TrackInfo? = null
    private var statusCardStatus: LyricsStatus? = null
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
        /**
         * User choice (settings, Android Auto section): with NO published
         * translation the Karaoke board rides compact SINGLE-line rows
         * instead of the constant-height two-line grid. Trade-off is the
         * grid's whole purpose — tall constant rows repaint silently under
         * the host's per-second rebind, short rows visibly nudge. With
         * translations published the board is always two-line.
         */
        const val AA_KARAOKE_SINGLE_LINE_KEY = "aa_karaoke_single_line"
        private const val ROOT_ID = "root"
        private const val LYRICS_MENU_ID = "lyrics_menu"
        private const val KARAOKE_MENU_ID = "karaoke_menu"
        private const val SYNC_MENU_ID = "sync_menu"
        private const val MORE_MENU_ID = "more_menu"
        private const val SYNC_OFFSET_ID = "sync_offset"
        private const val SYNC_MINUS_ID = "sync_minus"
        private const val SYNC_PLUS_ID = "sync_plus"
        private const val SYNC_STEP_MS = 200L
        private const val SYNC_WINDOW_SIZE = 3
        private const val DEFAULT_WINDOW_SIZE = 5
        // Host rule learned from live rows: title lines drop the U+2000-family
        // spaces but keep ideographic spaces, so leads and padding are built
        // from \u3000 (1.0 em) and the marker from \u25b6 + an ASCII space
        // (~1.06 em) — text starts at one position with or without the marker.
        // Title text must start at one x in both states AND meet the
        // subtitle's text (subtitle fields have a native indent that
        // swallows unguarded leading whitespace). The idle lead is ONE
        // \u3000 behind the ZWSP trim guard — the exact atom lattice the
        // Karaoke bottom row's right-align padding is built from: there,
        // marked-vs-idle differs by exactly one \u3000's budget plus the ▶
        // glyph, and live head units render it pixel-perfect, proving
        // px(▶) = 1.0 em on those hosts. NBSP runs are OFF that lattice
        // (the old 4\u00A0 idle lead visibly drifted against the marker);
        // \u3000/ASCII only. If a board ever shows marked-vs-idle shift, that
        // host's ▶ left the lattice — retune from live screenshots.
        private const val CURRENT_LINE_PREFIX = "\u25b6"
        private const val IDLE_LINE_PREFIX = "\u200B\u3000"

        // Subtitle fields strip NBSP (titles render it), but behind the
        // ZWSP guard they DO keep ideographic and ASCII spaces: this run
        // measures onto the bare-marker title text (~150 px on DHU) and the
        // guarded one-\u3000 idle lead. Pixel-tuned; retune from live shots.
        private const val TRANSLATION_SUBTITLE_PREFIX = "\u200B\u3000 "
        private const val PAD_WIDTH = 60
        // Default right-edge column for the Karaoke tab's right-justified row,
        // in display units (CJK = 2). Rows wrap (never shrink) past their
        // width, so the true column is screen-dependent and beyond what the
        // phone can know: this default suits a Desktop Head Unit row, and the
        // Sync tab's edge rows calibrate it per car. Padded width must stay
        // under the row's capacity or the row wraps to invisible space lines.
        // The header's TITLE starts right of the album-art icon while the
        // row's right edge is shared, so its field capacity is the column
        // MINUS the art inset (DHU-measured ~2 em): truncating the title at
        // the full column overflowed it and the host WRAPPED the spill to a
        // second line ("…キホーテ" then "の…" on its own line). The subtitle
        // is empirically exact at the unadjusted column: its smaller glyph
        // scale and native indent cancel the inset, so only the title pays.
        private const val HEADER_TITLE_INSET_EM = 2.0

        /** Gearhead root hint: art icon px the host wants (viewport-scaled). */
        private const val BROWSER_ICON_SIZE_HINT = "com.google.android.gms.car.media.BrowserIconSize"
        private const val NO_LYRICS_MESSAGE = "No lyrics found for this track"
        private const val TRANSLATION_LOADING_MESSAGE = "Loading translation…"
        private const val NOTIFY_THROTTLE_MS = 500L
        // How long the now-playing card holds "No lyrics found" /
        // "Error loading lyrics" before relaxing to the artist subtitle.
        private const val FAILURE_SUBTITLE_MS = 15_000L
        // Message rows (loading / not-found / error) pad to two lines: the
        // header clock tick re-pushes these sections every second and the
        // host animates re-binds of SHORT rows as a visible nudge (same
        // mechanism as the karaoke board's constant grid; U+2800 survives
        // the host's whitespace-line trim, a truly empty line would
        // collapse the row back to one).
        private const val MESSAGE_FOOT = "\n\u2800"
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
        // The head unit wraps long now-playing text onto a second line on
        // its own (live shots: a long track title wraps cleanly at the
        // field edge), while our width model's manual split cuts lines into
        // chunks that can end in a one-character tail. The chunk machinery
        // stays behind this static flag: ON restores the split (title
        // chunks, translated chunks, first-chunk preview); OFF - the
        // default - hands whole lines to the host's own wrap.
        private const val MANUAL_CARD_SPLIT = false
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
                // timed rows additionally need it for instrumental-gap flips,
                // the outro, and SEEKS WHILE PAUSED (a paused seek changes
                // the position without any MediaTracker emission, and the
                // main page picks it up from its own polling while the
                // browse tabs would sit on the old window). NOT_FOUND keeps
                // the tick too: its header row still shows playback time.
                // The gate itself stays free when the window is unchanged,
                // so ticking while paused costs nothing.
                val tickingStatus = state.track != null
                if (tickingStatus) {
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
        }?.let {
            if (it > 0) {
                carIconSizePx = it
                AaHost.iconSizePx = it
            }
        }
        AaHost.fingerprint = rootHints?.keySet()
            ?.associateWith { key -> rootHints.get(key)?.toString() }
            ?.let(AaHost::fingerprintOf)
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
                items.add(buildTextItem("loading", "Loading lyrics…$MESSAGE_FOOT"))
            }
            LyricsStatus.NOT_FOUND -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("not_found", "No lyrics found for this track$MESSAGE_FOOT"))
            }
            LyricsStatus.ERROR -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("error", "Error loading lyrics$MESSAGE_FOOT"))
            }
            LyricsStatus.FOUND -> {
                addTrackHeader(state, items)
                buildWindowedLyrics(state, items)
            }
            LyricsStatus.PLAIN_ONLY -> {
                addTrackHeader(state, items)
                if (state.lines.isEmpty()) {
                    items.add(buildTextItem("empty", "♪$MESSAGE_FOOT"))
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

                    for (i in adjStart until winEnd) {
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
        val plain = state.status == LyricsStatus.PLAIN_ONLY
        if (!plain && state.status != LyricsStatus.FOUND || state.lines.isEmpty()) {
            // Loading / not-found / error have no rows to place: mirror the
            // Lyrics tab exactly.
            buildLyricsMenu(state, items)
            return
        }
        addTrackHeader(state, items)
        val column = karaokeColumn()

        val posMs = getAaPositionMs()
        val boardCount = renderableLineCount(state.lines)
        // Instrumental marker rows join the alternating flow: they pair
        // like any line (previewing glyphs, counting down while current)
        // and shift the parity of everything after them, exactly as a real
        // lyric line would. Plain payloads carry no timing to derive
        // markers from, so their sequence is the line list itself.
        val rows = SungLine.virtualRows(state.lines, boardCount)
        // Plain payloads have no timing to drive SungLine: use the same
        // position/duration estimate the Lyrics window runs, mapped into
        // the row space.
        val durationMs = state.track?.durationMs ?: 0
        val idx = when {
            plain -> if (durationMs > 0) ((posMs.coerceAtLeast(0).toFloat() / durationMs) *
                rows.size).toInt().coerceIn(0, rows.size - 1) else 0
            else -> SungLine.currentVIndex(rows, state.lines, posMs)
        }
        // Parity anchors on the SEQUENCE (user-settled board model): an
        // intro marker is row 0, so it owns the top slot and the first
        // real lyric rides the bottom; without one the first lyric takes
        // the top. Mid-song markers shift later rows' parity the same way.
        val (topIdx, bottomIdx) = AaKaraokeRows.pairFor(idx, rows.size, 0)
        // Marker rows are never marker-pinned: an active marker counts
        // down marker-free. Plain rows never count down, so the estimate
        // keeps its marker.
        val curRow = rows.getOrNull(idx)
        val musicCurrent = !plain && curRow != null && curRow.isGap
        val topActive = topIdx == idx && !musicCurrent
        val bottomActive = bottomIdx == idx && !musicCurrent
        // Every gap row counts toward ITS OWN following line: with two
        // markers on screen (current intro, previewed interlude) a shared
        // first-future target made them tick in lockstep.
        fun cdSymFor(side: Int): String? = when {
            plain -> null
            side in rows.indices && rows[side].isGap -> LyricDisplay.countdownSymbol(
                SungLine.markerCountdownSeconds(rows, side, state.lines, posMs)
            )
            else -> LyricDisplay.countdownSymbol(
                SungLine.countdownSeconds(state.lines, posMs, boardCount)
            )
        }
        // With translations the board is a two-line grid: an empty hold
        // slot (past the last lyric) must occupy BOTH rows, or the other
        // side's lyric jumps up a row height when the pair ends.
        val twoLineBoard = visibleTranslations(state) != null
        val singleLineRows = !twoLineBoard && getSharedPreferences(
            "auto_lyrics_prefs", MODE_PRIVATE
        ).getBoolean(AA_KARAOKE_SINGLE_LINE_KEY, false)

        // One render decision per side (rowRender): countdown/symbol rows
        // carry IDENTICAL text in both fields, so the two lines of the
        // board can never disagree about digits vs static triples.
        // A negative side is the virtual slot: sung during the intro
        // (countdown pair), empty past the last lyric so the outro shows
        // no symbols.
        fun sidePair(side: Int, active: Boolean): Pair<String, String?> =
            if (side < 0 || side >= rows.size) {
                if (active) cdSymFor(idx).orEmpty() to cdSymFor(idx).takeIf { twoLineBoard }
                else "" to null
            } else {
                val (text, trans) = rowRender(state, rows[side], cdSymFor(side), twoLineBoard)
                // Board height is CONSTANT whenever translations are
                // active: a row whose translation is missing (translator
                // skipped the line, partially translated list) renders an
                // invisible second row instead of collapsing to one line.
                // Row heights then never change mid-song, so the host's
                // every-second header refresh repaints without any row
                // ever growing or shrinking - the "jumping bottom row".
                text to (trans ?: "".takeIf { twoLineBoard })
            }
        fun rowId(side: Int): String = when {
            side < 0 || side >= rows.size -> "karaoke_hold"
            rows[side].isGap -> "karaoke_gap"
            else -> "line_${rows[side].realIndex}"
        }
        val (topText, topTranslation) = sidePair(topIdx, topActive)
        val (bottomText, bottomTranslation) = sidePair(bottomIdx, bottomActive)

        // The host sizes title and subtitle glyphs independently per row
        // (live hosts render the same design's subtitle at ~0.7x or ~1.0x of
        // the title), so padding across the two fields can never hold an
        // edge. One multi-line title shares a single font scale, making the
        // padded tails of both lines land on the same edge by construction.
        val topMarker = if (topActive && idx >= 0) CURRENT_LINE_PREFIX else IDLE_LINE_PREFIX
        items.add(
            buildTextItem(
                rowId(topIdx),
                buildString {
                    // The board is a CONSTANT two-line grid in every mode:
                    // with translations the lyric leads and the translation
                    // follows; without them the first line carries the
                    // printable blank and the lyric rides the second. Row
                    // heights then never depend on content, so the host's
                    // per-second clock refresh can only repaint - the
                    // every-second "jumping row" was the host re-animating
                    // a list whose row heights varied between builds.
                    if (!twoLineBoard && !singleLineRows) append('\u2800').append('\n')
                    append(topMarker).append(topText)
                    if (!topTranslation.isNullOrEmpty()) {
                        // Byte-identical lead to the lyric line (marker
                        // included), so both lines start at one edge with no
                        // glyph-width modeling involved.
                        append('\n').append(topMarker).append(topTranslation)
                    } else if (topTranslation != null) {
                        // Pinned blank translation row: U+2800 (printable
                        // blank) occupies the row height; whitespace runs
                        // are trimmed away and collapse the line.
                        append('\n').append('\u2800')
                    } else if (topText.isEmpty() && twoLineBoard) {
                        append('\n').append(topMarker)
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
        items.add(
            buildTextItem(
                rowId(bottomIdx),
                buildString {
                    // Constant-height grid without translations too: the
                    // blank leads (U+2800 survives the host's line trim),
                    // the right-aligned lyric rides the second line.
                    if (!twoLineBoard && !singleLineRows) append('\u2800').append('\n')
                    val bottomPad = AaKaraokeRows.rightAlign(bottomText, column, bottomMarker)
                    if (bottomText.isEmpty() && !twoLineBoard) {
                        // Empty hold slot: the right-align pad of an empty
                        // line is pure whitespace and would trim away,
                        // collapsing the row back to one line.
                        append('\u2800')
                    } else {
                        append(bottomPad)
                    }
                    if (!bottomTranslation.isNullOrEmpty()) {
                        // Same marker string and the same over-estimated
                        // marker allowance as the lyric line: the rendered
                        // ▶ advance cancels out of both tails equally, so
                        // they land on one edge whatever it is.
                        append('\n').append(AaKaraokeRows.rightAlign(bottomTranslation, column, bottomMarker))
                    } else if (bottomTranslation != null) {
                        // Pinned blank translation row: printable blank
                        // (whitespace padding runs get trimmed and the
                        // line collapses).
                        append('\n').append('\u2800')
                    } else if (bottomText.isEmpty() && twoLineBoard) {
                        append('\n').append('\u2800')
                    }
                }
            )
        )
    }

    /** Right-edge column of the Karaoke board: host hint plus the phone's edge nudge. */
    private fun karaokeColumn(): Int {
        return AaHost.column(getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE), carIconSizePx)
    }

    private fun buildSyncMenu(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        // The Sync tab owns the PER-LYRICS offset — the global AA delta has
        // no row here at all (phone-only editor, like the edge knob). The
        // ± rows are PLAYABLE and the tap jumps to the player: that is the
        // host's documented browse semantics (any playable selection opens
        // the playback view), and the only stay-in-place alternative —
        // adjust-on-load inside BROWSABLE folders — broke because Gearhead
        // loads listed folders' children without any tap (prefetch moves
        // the offset on its own).
        val stepSec = String.format(Locale.US, "%.1f", SYNC_STEP_MS / 1000.0)
        if (state.lines.isNotEmpty()) {
            val sec = String.format(Locale.US, "%.1f", state.offsetMs / 1000.0)
            val sign = if (state.offsetMs >= 0) "+" else ""
            items.add(buildTextItem(SYNC_OFFSET_ID, "Current offset: $sign${sec}s"))
        }
        if (state.status == LyricsStatus.NOT_FOUND) {
            items.add(buildTextItem("sync_not_found", NO_LYRICS_MESSAGE))
        }
        if (state.lines.isNotEmpty()) {
            items.add(buildTextItem(SYNC_MINUS_ID, "◀◀  Delay lyrics: −${stepSec}s"))
            items.add(buildTextItem(SYNC_PLUS_ID, "▶▶  Advance lyrics: +${stepSec}s"))

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
            val rows = SungLine.virtualRows(state.lines, syncCount)
            val curV = SungLine.currentVIndex(rows, state.lines, posMs)
            // The virtual gap row is a normal member of the window, just
            // like in the lyrics/karaoke tabs.
            val twoLine = visibleTranslations(state) != null
            // Per-row target: each marker ticks toward its own following line.
            fun cdSymFor(i: Int): String = LyricDisplay.countdownSymbol(
                if (rows[i].isGap) SungLine.markerCountdownSeconds(rows, i, state.lines, posMs)
                else SungLine.countdownSeconds(state.lines, posMs, syncCount)
            )

            fun addRow(i: Int, isCurrent: Boolean) {
                val row = rows[i]
                val (text, trans) = rowRender(state, row, cdSymFor(i), twoLine)
                // Marker rows are never marker-pinned: no ▶.
                val prefix = if (row.isGap) IDLE_LINE_PREFIX else linePrefix(isCurrent)
                val id = when {
                    row.isGap && row.realIndex < 0 -> "sync_line_intro_gap"
                    row.isGap -> "sync_line_gap_${row.realIndex}"
                    else -> "sync_line_${row.realIndex}"
                }
                items.add(buildTextItem(id, "$prefix$text", pad = true, subtitle = trans))
            }

            // Current row pinned first: the window reads forward; at the
            // song end it shows fewer rows, never back-fills.
            if (curV < 0) {
                for (i in 0 until minOf(rows.size, SYNC_WINDOW_SIZE)) addRow(i, false)
                return
            }
            for (i in curV until minOf(rows.size, curV + SYNC_WINDOW_SIZE)) {
                addRow(i, i == curV)
            }

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
            LyricsStatus.FOUND ->
                if (state.lines.any { it.words.isNotEmpty() }) "Karaoke" else "Synced"
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


    }

    private fun addTrackHeader(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val track = state.track ?: return

        val tail = buildString {
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
        // One line each: the host wraps instead of shrinking, so the title
        // is ellipsized outright and the artist yields space to keep the
        // time label on the same row. Budget rides the same hint-sized,
        // edge-adjusted column as the karaoke board (halfwidth units ->
        // ems): the phone's Karaoke edge knob calibrates every row width
        // per head unit at once. Subtitles get the title's budget rather
        // than a scale-up; their exact glyph ratio varies per host and
        // truncating slightly early beats wrapping.
        // Head-unit calibration: the header's fields share the board's
        // column UNADJUSTED (DHU: the earlier art-inset and wrap-margin
        // deductions left the info row ellipsizing ~2 em short of the edge
        // the lyrics rows pad to - at the same edge setting the user
        // measured +12 needed where the board was happy at +8). One column,
        // one edge, both surfaces.
        val headerBudgetEm = karaokeColumn() * 0.5
        val subtitle = AaKaraokeRows.truncate(
            track.artist,
            headerBudgetEm - AaKaraokeRows.advanceEm(tail)
        ) + tail

        val descBuilder = MediaDescriptionCompat.Builder()
            .setMediaId("header")
            .setTitle(
                AaKaraokeRows.truncate(
                    track.title,
                    headerBudgetEm - HEADER_TITLE_INSET_EM -
                        AaKaraokeRows.wordGuardEm(track.title)
                )
            )
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

    /**
     * ONE render decision for a display-sequence row: (original field,
     * translation field). Countdown and symbol states render the SAME text
     * in both fields - the lyric line and the translation line can never
     * disagree about digits vs static triples - and the translation field
     * only exists when the song has translations. Only real-lyric rows
     * consult the translation array.
     */
    private fun rowRender(
        state: LyricsState,
        row: SungLine.VRow,
        cdSym: String?,
        twoLine: Boolean,
    ): Pair<String, String?> {
        if (row.isGap) return cdSym.orEmpty() to cdSym.takeIf { twoLine }
        val line = state.lines[row.realIndex]
        // Ingest stripped every non-lyric line, so a real row always carries
        // real text and only real rows consult the translation array.
        return LyricDisplay.displayText(line.text) to
            translationFor(state, row.realIndex, line.text)
    }

    private fun linePrefix(isCurrent: Boolean): String {
        return if (isCurrent) CURRENT_LINE_PREFIX else IDLE_LINE_PREFIX
    }

    private fun buildWindowedLyrics(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val lines = state.lines
        val count = renderableLineCount(lines)
        val posMs = getAaPositionMs()
        // Display sequence: real lines PLUS a virtual countdown row after
        // every line that owns a proven instrumental gap. The countdown is
        // a member of the list: it previews like any line, pages through
        // the window like any line, and becomes the current row once its
        // instrumental starts.
        val rows = SungLine.virtualRows(lines, count)
        val curV = SungLine.currentVIndex(rows, lines, posMs)
        val windowSize = DEFAULT_WINDOW_SIZE
        // Countdown rows render the live digit form, preview included -
        // it ticks on every push. Both fields of a countdown/symbol row
        // carry the SAME text (rowRender): the two lines never disagree.
        val twoLine = visibleTranslations(state) != null
        // Per-row target: each marker ticks toward its own following line.
        fun cdSymFor(i: Int): String = LyricDisplay.countdownSymbol(
            if (rows[i].isGap) SungLine.markerCountdownSeconds(rows, i, lines, posMs)
            else SungLine.countdownSeconds(lines, posMs, count)
        )

        fun addRow(i: Int, isCurrent: Boolean) {
            val row = rows[i]
            val (text, trans) = rowRender(state, row, cdSymFor(i), twoLine)
            // Marker rows are never marker-pinned: no ▶.
            val prefix = if (row.isGap) IDLE_LINE_PREFIX else linePrefix(isCurrent)
            val id = when {
                row.isGap && row.realIndex < 0 -> "line_intro_gap"
                row.isGap -> "line_gap_${row.realIndex}"
                else -> "line_${row.realIndex}"
            }
            items.add(buildTextItem(id, "$prefix$text", pad = true, subtitle = trans))
        }

        // The current row is always the first lyric row; near the song end
        // the window simply shows fewer rows instead of back-filling.
        if (curV < 0) {
            // Sub-threshold intro (no marker row): the opening window rides
            // idle until the first line starts.
            for (i in 0 until minOf(rows.size, windowSize)) addRow(i, false)
            return
        }
        for (i in curV until minOf(rows.size, curV + windowSize)) {
            addRow(i, i == curV)
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
     * Line count for display purposes. Ingest strips every non-lyric line
     * (blanks, note glyphs, music tags, section headers), so every stored
     * line is renderable; the outro is a timing point, not trailing rows.
     */
    private fun renderableLineCount(lines: List<LyricLine>): Int = lines.size

    /**
     * Translation subtitle for a rendered line. Translators pass music
     * symbols through unchanged or skip them, so a symbol line's subtitle
     * falls back to the symbol itself whenever translations are active.
     */
    private fun translationFor(state: LyricsState, index: Int, lineText: String): String? =
        visibleTranslations(state)?.getOrNull(index)?.takeIf { it.isNotBlank() }

    /**
     * Translation content the browse surfaces may paint: a partial list
     * stays invisible while the engine runs. Streaming rows in mid-run shows
     * blank second lines (the chunk hasn't arrived yet) and flips row heights
     * line by line, which the host animates as jumping. Publishing at run end
     * is the single height transition per section.
     */
    private fun visibleTranslations(state: LyricsState): List<String>? =
        state.translatedLines?.takeIf { it.isNotEmpty() && !translationInFlight() }

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
    /** The card's artist line; a playing track with no artist tag reads like the phone UI. */
    private fun artistLine(state: LyricsState): String =
        state.track?.artist?.ifBlank { "Unknown Artist" }.orEmpty()

    private fun getCardText(state: LyricsState): CardText {
        getLyricCardText(state)?.let { return it }

        var subtitle = when (state.status) {
            // Intro: preview the first line while the title still shows the track.
            // Intro (and outro past the last line's end): track identity
            // like a normal player card — title above, artist here.
            LyricsStatus.FOUND -> artistLine(state)
            LyricsStatus.LOADING -> "Loading lyrics…"
            LyricsStatus.NOT_FOUND -> "No lyrics found"
            LyricsStatus.ERROR -> "Error loading lyrics"
            else -> ""
        }
        // Failure messages are informational, not permanent furniture: after
        // a grace period the identity card returns to its normal artist line
        // (the browse tabs keep reporting the failure state).
        if (state.status == LyricsStatus.NOT_FOUND || state.status == LyricsStatus.ERROR) {
            val now = SystemClock.elapsedRealtime()
            if (state.status != statusCardStatus || state.track != statusCardTrack) {
                statusCardStatus = state.status
                statusCardTrack = state.track
                statusCardEntryMs = now
            }
            if (now - statusCardEntryMs >= FAILURE_SUBTITLE_MS) {
                subtitle = artistLine(state)
            }
        } else {
            statusCardStatus = state.status
            statusCardTrack = state.track
        }
        // FOUND identity phases (intro/outro) ride the same loading
        // override as the lyric card: the artist line yields to
        // "Loading translation…" while the engine works.
        if (state.status == LyricsStatus.FOUND && subtitle.isNotEmpty() && translationInFlight()) {
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
        val cardCount = renderableLineCount(lines)

        val idx: Int
        val startMs: Long
        val endMs: Long
        var foundRows: List<SungLine.VRow>? = null
        var foundV = -1
        when (state.status) {
            LyricsStatus.FOUND -> {
                // Same policy as the browse tabs: the outro keeps the last
                // real lyric lit... until its END (payload word ends, or a
                // 5 s fallback), after which the card shows track identity.
                if (SungLine.pastLastLineEnd(lines, cardCount, posMs)) return null
                // Same marker sequence as the tabs. A marker row drives the
                // card through two phases (user-settled model): until
                // CARD_LEAD_MS before the next line the card holds track
                // identity - the finished line is gone and the instrumental
                // owns the moment - then the final seconds count 4->1 with
                // the coming line as subtitle. Intro and interlude behave
                // identically; there is never a line before an intro.
                val rows = SungLine.virtualRows(lines, cardCount)
                val v = SungLine.currentVIndex(rows, lines, posMs)
                val cur = rows.getOrNull(v) ?: return null
                foundRows = rows
                foundV = v
                if (cur.isGap) {
                    // Markers never stack: the next row is the coming line.
                    val next = rows.getOrNull(v + 1) ?: return null
                    if (lines[next.realIndex].timeMs - posMs > SungLine.CARD_LEAD_MS) {
                        return null
                    }
                    val sym = LyricDisplay.countdownSymbol(
                        SungLine.markerCountdownSeconds(rows, v, lines, posMs)
                    )
                    val coming = LyricDisplay.displayText(lines[next.realIndex].text)
                    val sub = if (MANUAL_CARD_SPLIT) {
                        NowPlayingText.chunkText(coming, SUBTITLE_MAX_WIDTH)
                            .firstOrNull().orEmpty()
                    } else coming
                    return CardText(sym, sub)
                }
                idx = cur.realIndex
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
        // Preview the NEXT ROW of the sequence, never skipping a row: a
        // marker is a row like any lyric, so when an instrumental comes
        // next the subtitle previews the MARKER (its live glyph row,
        // counting toward its own successor), exactly like the browse tabs
        // preview it. Only past the last row is the preview blank.
        val nextText = when (state.status) {
            LyricsStatus.FOUND -> foundRows?.let { r ->
                when (val next = SungLine.previewSuccessor(r, foundV)) {
                    null -> null
                    next.takeIf { it.isGap } -> LyricDisplay.countdownSymbol(
                        SungLine.markerCountdownSeconds(r, foundV + 1, lines, posMs)
                    )
                    else -> LyricDisplay.displayText(lines[next.realIndex].text)
                }
            }
            else -> lines.getOrNull(idx + 1)?.text
        }
        val nowMs = SystemClock.elapsedRealtime()

        // A translation of the current line takes the subtitle instead of the next line.
        val translation = state.translatedLines?.getOrNull(idx)?.takeIf { it.isNotBlank() }
        val translationChunk = translation?.let {
            if (!MANUAL_CARD_SPLIT) return@let it
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
        val chunkTexts = if (MANUAL_CARD_SPLIT) {
            NowPlayingText.chunkDisplayText(displayLine, budget)
        } else listOf(displayLine)
        val loadingTranslation = translationChunk == null && translationInFlight()

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

            // Track identity renders like a normal now-playing card: title
            // on the top line, artist on the subtitle line below it.
            val displayTitle = lyricTitle ?: track.title
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
        val countdown: Int = 0,
        val headerSec: Long = -1L
    )

    private fun computeWindow(state: LyricsState): WindowInfo {
        val lines = state.lines
        if (lines.isEmpty()) {
            // Header rows still render playback time with no lyric lines
            // (not-found/loading states), so the header tick must live even
            // when there is no window to compute.
            val posMs = try {
                mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
            } catch (_: Exception) { 0L }
            return WindowInfo(-1, -1, -1, headerSec = posMs / 1000)
        }

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
            return WindowInfo(start, minOf(plainCount, start + winSize), estimated,
                headerSec = posMs / 1000)
        }

        // Mirror buildWindowedLyrics exactly: a change here must imply a
        // visible row change, or the throttled refresh gate swallows it (the
        // intro row clamped to line 0 used to mask the intro -> first-line
        // flip, leaving the symbol painted through the first verse).
        val count = renderableLineCount(lines)
        val posMs = getAaPositionMs()
        // Mirror the builders' virtual-row sequence: the gap countdown row
        // is a window member and the current row while active, so the gate
        // sees its insertion, activation and paging-out as real changes.
        val rows = SungLine.virtualRows(lines, count)
        val idx = SungLine.currentVIndex(rows, lines, posMs)
        val headerSec = posMs / 1000
        val start = maxOf(0, idx)
        val windowEnd = minOf(rows.size, start + winSize)
        // Any visible marker row (current OR previewing) ticks toward its
        // own target, so the digits stay live wherever the row sits.
        // Every visible marker row ticks toward its OWN target, so the
        // gate watches a base-5 signature of all visible counts - any
        // single row's digit change pushes, and rows far from their
        // target hold the cap 4 and stay silent.
        var countdownSignature = 0
        for (i in start until windowEnd) {
            if (rows[i].isGap) {
                countdownSignature = countdownSignature * 5 +
                    SungLine.markerCountdownSeconds(rows, i, lines, posMs)
            }
        }
        return WindowInfo(start, windowEnd, idx, idx >= 0 && rows[idx].isGap,
            countdown = countdownSignature,
            headerSec = headerSec)
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
        displayedHeaderSec = -1L
        displayedTranslationsVisible = false
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
        // Publish-at-end: rows flip from the single-line grid to the two-line
        // grid when the engine finishes — with no state emission behind it.
        // The gate must watch the visibility decision itself.
        val translationsVisible = visibleTranslations(state) != null
        val translationsVisibleChanged = translationsVisible != displayedTranslationsVisible

        if (trackChanged) {
            displayedWindowStart = -1
            displayedWindowEnd = -1
            displayedCurrentIdx = -1
            displayedGap = false
            displayedCountdown = 0
            displayedHeaderSec = -1L
            displayedStatus = state.status
            displayedTrack = state.track
            displayedSource = state.source
            displayedDetectedLanguage = state.detectedLanguage
            displayedHasTranslation = hasTranslation
            displayedTranslationsVisible = translationsVisible
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
        // The countdown row's text ticks once per second in every tab.
        val countdownChanged = win.countdown != displayedCountdown
        // The Lyrics/Karaoke header row shows playback time; tick it every second.
        // Row heights are constant while this ticks (the karaoke board pins
        // its two-line grid whenever translations are active), so the
        // host's per-second refresh can only repaint, never shift rows.
        val headerTickChanged = win.headerSec != displayedHeaderSec
        // The Sync tab prints the per-lyrics offset; phone-side edits must
        // reach the head unit even while paused (no other field moves).
        val offsetChanged = state.offsetMs != displayedOffsetMs
        if (!windowChanged && !lineChanged && !gapChanged && !countdownChanged &&
            !headerTickChanged && !offsetChanged &&
            !statusChanged && !sourceChanged && !detectedLanguageChanged &&
            !translationAvailabilityChanged && !translationsVisibleChanged
        ) {
            return
        }

        var notifyMask = 0
        if (windowChanged || lineChanged || gapChanged || countdownChanged ||
            headerTickChanged ||
            statusChanged || sourceChanged || detectedLanguageChanged ||
            translationAvailabilityChanged || translationsVisibleChanged
        ) notifyMask = notifyMask or NOTIFY_LYRICS
        if (lineChanged || gapChanged || countdownChanged || statusChanged ||
            translationAvailabilityChanged || translationsVisibleChanged ||
            offsetChanged
        ) {
            notifyMask = notifyMask or NOTIFY_SYNC
        }
        if (lineChanged || gapChanged || countdownChanged || headerTickChanged ||
            statusChanged || translationAvailabilityChanged ||
            translationsVisibleChanged
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
        displayedHeaderSec = win.headerSec
        displayedOffsetMs = state.offsetMs
        displayedStatus = state.status
        displayedSource = state.source
        displayedDetectedLanguage = state.detectedLanguage
        displayedHasTranslation = hasTranslation
        displayedTranslationsVisible = translationsVisible

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
                // Per-lyrics store through the tracker (no-op while nothing
                // is loaded — the ± rows only render with lyrics on screen).
                // The tap also opens the host's playback view (unavoidable
                // for playables); the card there rides the new offset.
                mediaTracker.adjustOffset(
                    if (mediaId == SYNC_MINUS_ID) -SYNC_STEP_MS else SYNC_STEP_MS
                )
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
