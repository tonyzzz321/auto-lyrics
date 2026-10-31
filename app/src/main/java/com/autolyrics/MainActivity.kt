package com.autolyrics

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autolyrics.auto.LyricsBrowserService
import com.autolyrics.media.MediaListenerService
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.AlbumColors
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.autolyrics.ui.KaraokeSweepSpan
import com.autolyrics.ui.PhoneKaraokeSweep
import com.autolyrics.util.LyricDisplay
import com.autolyrics.util.SungLine
import com.autolyrics.util.SyncCalibration
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var mediaTracker: MediaTracker
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var rootLayout: LinearLayout
    private lateinit var appBar: LinearLayout
    private lateinit var tvAppTitle: TextView
    private lateinit var tvAppSubtitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnPermission: Button
    private lateinit var ivAlbumArt: ImageView
    private lateinit var tvTrack: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvLyrics: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var delayBar: LinearLayout
    private lateinit var btnPhoneSyncReset: Button
    private lateinit var divider: View
    private lateinit var fontSettingsPanel: LinearLayout
    private lateinit var tvFontSize: TextView
    private lateinit var switchAaKaraoke: SwitchCompat
    private lateinit var switchKaraokeRows: SwitchCompat
    private lateinit var switchTranslation: SwitchCompat
    private lateinit var tvAaDelay: TextView
    private lateinit var btnAaDelayReset: Button
    private lateinit var btnKaraokeEdgeReset: Button
    private lateinit var btnJumpToCurrent: Button
    private lateinit var prefs: SharedPreferences
    private var lastScrolledIndex = -1
    private var currentColors: AlbumColors? = null
    private var lyricsFontSizeSp = 16
    private var lyricsFontFamily = "sans-serif"
    private var aaOffsetMs = 0L
    private var userScrolling = false
    private var userTouching = false

    private var plainScrollAnimator: ValueAnimator? = null
    private var lastPlainTrackTitle: String? = null

    private lateinit var btnTapSync: Button
    private lateinit var tvSyncStatus: TextView
    private var tapSyncActive = false
    private var tapSyncOffsets = mutableListOf<Long>()
    private var tapSyncTargetIndices: List<Int> = emptyList()
    private var tapSyncTrackKey: String? = null
    private var activeUndoSnackbar: Snackbar? = null
    private var phoneKaraokeFramesRunning = false

    private val phoneKaraokeFrameRunnable = object : Runnable {
        override fun run() {
            if (!phoneKaraokeFramesRunning) return

            val state = mediaTracker.state.value
            val line = state.lines.getOrNull(state.currentIndex)
            val positionMs = safeCurrentPositionMs()
            if (
                state.status != LyricsStatus.FOUND ||
                !state.isPlaying ||
                line == null ||
                !PhoneKaraokeSweep.isAnimating(line, positionMs)
            ) {
                phoneKaraokeFramesRunning = false
                return
            }

            tvLyrics.invalidate()
            tvLyrics.postOnAnimation(this)
        }
    }

    private val fontButtons = mutableMapOf<String, Button>()
    private val scrollResetRunnable = Runnable {
        userScrolling = false
        btnJumpToCurrent.visibility = View.GONE
    }
    private val aaPrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == AA_OFFSET_PREF_KEY) {
                val newOffset = sp.getLong(AA_OFFSET_PREF_KEY, 0L)
                if (newOffset != aaOffsetMs) {
                    dismissUndoSnackbar()
                }
                aaOffsetMs = newOffset
                updateAaDelayDisplay()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaTracker = MediaTracker.getInstance(this)
        prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)

        rootLayout = findViewById(R.id.root_layout)
        appBar = findViewById(R.id.layout_app_bar)
        tvAppTitle = findViewById(R.id.tv_app_title)
        tvAppSubtitle = findViewById(R.id.tv_app_subtitle)
        tvStatus = findViewById(R.id.tv_status)
        btnPermission = findViewById(R.id.btn_permission)
        ivAlbumArt = findViewById(R.id.iv_album_art)
        tvTrack = findViewById(R.id.tv_track)
        tvSource = findViewById(R.id.tv_source)
        tvLyrics = findViewById(R.id.tv_lyrics)
        scrollView = findViewById(R.id.scroll_lyrics)
        delayBar = findViewById(R.id.layout_delay)
        btnPhoneSyncReset = findViewById(R.id.btn_delay_reset)
        divider = findViewById(R.id.divider)

        ivAlbumArt.clipToOutline = true
        btnJumpToCurrent = findViewById(R.id.btn_jump_to_current)

        setupScrollDetection()

        btnPermission.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        btnPhoneSyncReset.setOnClickListener { resetPhoneSyncOffset() }

        fontSettingsPanel = findViewById(R.id.layout_font_settings)
        tvFontSize = findViewById(R.id.tv_font_size)

        lyricsFontSizeSp = prefs.getInt("lyrics_font_size", 16)
        lyricsFontFamily = prefs.getString("lyrics_font_family", "sans-serif") ?: "sans-serif"

        applyFontSettings()

        findViewById<ImageButton>(R.id.btn_settings_toggle).setOnClickListener {
            fontSettingsPanel.visibility = if (fontSettingsPanel.visibility == View.VISIBLE)
                View.GONE else View.VISIBLE
        }

        findViewById<Button>(R.id.btn_performance_mode).setOnClickListener {
            startActivity(Intent(this, PerformanceActivity::class.java))
        }

        findViewById<Button>(R.id.btn_manual_search).setOnClickListener {
            ManualSearchDialog.show(this)
        }

        findViewById<View>(R.id.row_media_apps).setOnClickListener {
            MediaAppsDialog.show(this) { updateMediaAppsDisplay() }
        }
        updateMediaAppsDisplay()

        findViewById<Button>(R.id.btn_font_size_minus).setOnClickListener {
            if (lyricsFontSizeSp > 12) {
                lyricsFontSizeSp -= 2
                saveFontSettings()
                applyFontSettings()
            }
        }
        findViewById<Button>(R.id.btn_font_size_plus).setOnClickListener {
            if (lyricsFontSizeSp < 28) {
                lyricsFontSizeSp += 2
                saveFontSettings()
                applyFontSettings()
            }
        }

        val btnSans = findViewById<Button>(R.id.btn_font_sans)
        val btnSerif = findViewById<Button>(R.id.btn_font_serif)
        val btnMono = findViewById<Button>(R.id.btn_font_mono)
        val btnCursive = findViewById<Button>(R.id.btn_font_cursive)

        fontButtons["sans-serif"] = btnSans
        fontButtons["serif"] = btnSerif
        fontButtons["monospace"] = btnMono
        fontButtons["cursive"] = btnCursive

        btnSans.setOnClickListener { selectFont("sans-serif") }
        btnSerif.setOnClickListener { selectFont("serif") }
        btnMono.setOnClickListener { selectFont("monospace") }
        btnCursive.setOnClickListener { selectFont("cursive") }

        updateFontButtonHighlights()

        switchAaKaraoke = findViewById(R.id.switch_aa_karaoke)
        tvAaDelay = findViewById(R.id.tv_aa_delay)
        btnAaDelayReset = findViewById(R.id.btn_aa_delay_reset)
        btnKaraokeEdgeReset = findViewById(R.id.btn_karaoke_edge_reset)

        switchAaKaraoke.isChecked = prefs.getBoolean("aa_karaoke_enabled", true)
        aaOffsetMs = prefs.getLong(AA_OFFSET_PREF_KEY, 0L)
        updateAaDelayDisplay()
        prefs.registerOnSharedPreferenceChangeListener(aaPrefsListener)

        switchAaKaraoke.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("aa_karaoke_enabled", isChecked).apply()
        }

        // Karaoke board row height without translations: single compact rows
        // vs the constant-height grid (LyricsBrowserService reads the pref).
        switchKaraokeRows = findViewById(R.id.switch_karaoke_rows)
        switchKaraokeRows.isChecked = prefs.getBoolean(
            LyricsBrowserService.AA_KARAOKE_SINGLE_LINE_KEY, false
        )
        switchKaraokeRows.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit()
                .putBoolean(LyricsBrowserService.AA_KARAOKE_SINGLE_LINE_KEY, isChecked)
                .apply()
        }

        switchTranslation = findViewById(R.id.switch_translation)
        switchTranslation.isChecked = prefs.getBoolean("translation_enabled", true)
        switchTranslation.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("translation_enabled", isChecked).apply()
        }

        findViewById<Button>(R.id.btn_aa_delay_minus).setOnClickListener {
            adjustAaOffset(-AA_SYNC_STEP_MS)
        }
        findViewById<Button>(R.id.btn_aa_delay_plus).setOnClickListener {
            adjustAaOffset(AA_SYNC_STEP_MS)
        }

        updateKaraokeEdgeDisplay()
        // The edge knob calibrates one head unit at a time: the row only
        // exists while a car owns the browse connection.
        androidx.car.app.connection.CarConnection(this).type.observeForever { type ->
            val connected =
                type != androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NOT_CONNECTED
            // The whole Android Auto section (AA Sync + Karaoke edge) is
            // head-unit calibration, so it exists only while a car owns the
            // connection.
            findViewById<View>(R.id.section_aa).visibility =
                if (connected) View.VISIBLE else View.GONE
            findViewById<View>(R.id.row_karaoke_edge).visibility =
                if (connected) View.VISIBLE else View.GONE
            updateKaraokeEdgeDisplay()
        }
        findViewById<Button>(R.id.btn_karaoke_edge_minus).setOnClickListener {
            adjustKaraokeEdge(-1)
        }
        findViewById<Button>(R.id.btn_karaoke_edge_plus).setOnClickListener {
            adjustKaraokeEdge(1)
        }
        btnKaraokeEdgeReset.setOnClickListener { resetKaraokeEdge() }
        btnAaDelayReset.setOnClickListener {
            val previousOffset = aaOffsetMs
            if (previousOffset == 0L) return@setOnClickListener

            dismissUndoSnackbar()
            aaOffsetMs = 0L
            prefs.edit().putLong(AA_OFFSET_PREF_KEY, 0L).apply()
            updateAaDelayDisplay()

            showUndoSnackbar("AA Sync reset") {
                aaOffsetMs = previousOffset
                prefs.edit().putLong(AA_OFFSET_PREF_KEY, previousOffset).apply()
                updateAaDelayDisplay()
            }
        }

        btnTapSync = findViewById(R.id.btn_tap_sync)
        tvSyncStatus = findViewById(R.id.tv_sync_status)
        configureQuickSyncButtons()

        btnTapSync.setOnClickListener { onTapSyncPressed() }

        findViewById<Button>(R.id.btn_quick_minus_1s).setOnClickListener {
            adjustPhoneOffset(-1000)
        }
        findViewById<Button>(R.id.btn_quick_minus_half).setOnClickListener {
            adjustPhoneOffset(-500)
        }
        findViewById<Button>(R.id.btn_quick_minus_tenth).setOnClickListener {
            adjustPhoneOffset(-100)
        }
        findViewById<Button>(R.id.btn_quick_plus_tenth).setOnClickListener {
            adjustPhoneOffset(100)
        }
        findViewById<Button>(R.id.btn_quick_plus_half).setOnClickListener {
            adjustPhoneOffset(500)
        }
        findViewById<Button>(R.id.btn_quick_plus_1s).setOnClickListener {
            adjustPhoneOffset(1000)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mediaTracker.state.collect { state ->
                    cancelTapSyncIfTrackChanged(state)
                    updatePermissionUi()
                    updateDelayDisplay(state.offsetMs)
                    updateAlbumArt(state)
                    applyThemeColors(state.albumColors)

                    if (state.track != null) {
                        val artistText = if (state.track.artist.isNotBlank())
                            state.track.artist else "Unknown Artist"
                        tvTrack.text = "${state.track.title}\n$artistText"
                        tvTrack.visibility = View.VISIBLE
                    } else {
                        tvTrack.text = ""
                        tvTrack.visibility = View.GONE
                    }

                    if (state.source.isNotBlank()) {
                        val srcText = if (state.detectedLanguage != null) {
                            "${state.source} · ${state.detectedLanguage}→en"
                        } else state.source
                        tvSource.text = srcText
                        tvSource.visibility = View.VISIBLE
                    } else {
                        tvSource.visibility = View.GONE
                    }

                    if (state.status != LyricsStatus.PLAIN_ONLY) {
                        stopPlainScroll()
                    } else {
                        plainScrollAnimator?.let { anim ->
                            if (state.isPlaying && anim.isPaused) anim.resume()
                            else if (!state.isPlaying && anim.isRunning) anim.pause()
                        }
                    }

                    when (state.status) {
                        LyricsStatus.NO_MEDIA -> {
                            tvLyrics.text = "Play a song to see lyrics here.\n\nLyrics will also appear on Android Auto."
                        }
                        LyricsStatus.LOADING -> {
                            tvLyrics.text = "Loading lyrics…"
                        }
                        LyricsStatus.NOT_FOUND -> {
                            tvLyrics.text = "No lyrics found for this track."
                        }
                        LyricsStatus.ERROR -> {
                            tvLyrics.text = "Error loading lyrics.\nCheck your internet connection."
                        }
                        LyricsStatus.FOUND -> {
                            renderSyncedLyrics(state)
                        }
                        LyricsStatus.PLAIN_ONLY -> {
                            stopPlainScroll()
                            val hasTrans = state.translatedLines != null
                            val sb = SpannableStringBuilder()
                            sb.append("ℹ  Lyrics are not synced to playback\n\n")
                            sb.append("─────────────────────\n\n")
                            val dimColor = state.albumColors?.textDim ?: DEFAULT_DIM
                            state.lines.forEachIndexed { i, line ->
                                sb.append(LyricDisplay.displayText(line.text).toString() + "\n")
                                if (hasTrans) {
                                    val tl = state.translatedLines?.getOrNull(i)?.takeIf { it.isNotBlank() }
                                    if (!tl.isNullOrBlank()) {
                                        val tlStart = sb.length
                                        sb.append("$tl\n")
                                        sb.setSpan(
                                            RelativeSizeSpan(0.8f),
                                            tlStart, sb.length,
                                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                        )
                                        sb.setSpan(
                                            ForegroundColorSpan(dimColor),
                                            tlStart, sb.length,
                                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                        )
                                    }
                                }
                                sb.append("\n")
                            }
                            tvLyrics.text = sb
                            startPlainScroll(state)
                        }
                    }

                    syncPhoneKaraokeFrames(state)
                }
            }
        }
    }

    private fun updateAlbumArt(state: LyricsState) {
        val art = state.albumArt
        if (art != null) {
            ivAlbumArt.setImageBitmap(art)
            ivAlbumArt.visibility = View.VISIBLE
        } else {
            ivAlbumArt.setImageDrawable(null)
            ivAlbumArt.visibility = View.GONE
        }
    }

    private fun applyThemeColors(colors: AlbumColors?) {
        if (colors == currentColors) return
        currentColors = colors

        if (colors != null) {
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(colors.dominant, colors.dominantDark)
            )
            rootLayout.background = gradient

            appBar.setBackgroundColor(lighten(colors.dominant, 1.3f))
            delayBar.setBackgroundColor(lighten(colors.dominantDark, 1.2f))
            divider.setBackgroundColor(lighten(colors.dominant, 1.8f))

            tvAppTitle.setTextColor(colors.textPrimary)
            tvAppSubtitle.setTextColor(colors.textDim)
            tvTrack.setTextColor(colors.vibrant)
            tvLyrics.setTextColor(colors.textPrimary)
        } else {
            rootLayout.setBackgroundColor(DEFAULT_BG)
            appBar.setBackgroundColor(DEFAULT_APP_BAR)
            delayBar.setBackgroundColor(DEFAULT_DELAY_BAR)
            divider.setBackgroundColor(DEFAULT_DIVIDER)

            tvAppTitle.setTextColor(Color.parseColor("#E0E0FF"))
            tvAppSubtitle.setTextColor(Color.parseColor("#8888AA"))
            tvTrack.setTextColor(Color.parseColor("#BB86FC"))
            tvLyrics.setTextColor(Color.parseColor("#CCCCDD"))
        }
    }

    private fun lighten(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    /**
     * Per-LYRICS sync edits (the offset belongs to the loaded timing set).
     * With nothing loaded there is no key to store under — say so instead of
     * silently buffering a value the next lyrics would inherit.
     */
    private fun adjustPhoneOffset(deltaMs: Long) {
        if (mediaTracker.state.value.lines.isEmpty()) {
            showSyncStatus("No lyrics loaded to sync")
            return
        }
        dismissUndoSnackbar()
        mediaTracker.adjustOffset(deltaMs)
        showSyncStatus("Offset: ${formatOffset(mediaTracker.state.value.offsetMs)} · this lyrics")
    }

    private fun resetPhoneSyncOffset() {
        val previousOffset = mediaTracker.state.value.offsetMs
        if (previousOffset == 0L) return

        dismissUndoSnackbar()
        mediaTracker.resetOffset()
        showUndoSnackbar("Lyrics offset reset") {
            mediaTracker.setOffset(previousOffset)
        }
    }

    private fun resetKaraokeEdge() {
        val previous = com.autolyrics.auto.AaHost.edge(prefs)
        val previousGlobal = com.autolyrics.auto.AaHost.globalEdge(prefs)
        if (previous == 0 && previousGlobal == 0) return

        dismissUndoSnackbar()
        // Zero BOTH scopes: the per-car key only exists per fingerprint, and
        // every un-keyed read (identity dropped by a service restart or a
        // hint-less reconnect) falls back to the global default — a stale
        // global would silently restore the old edge after the reset.
        com.autolyrics.auto.AaHost.resetEdge(prefs, com.autolyrics.auto.AaHost.fingerprint)
        updateKaraokeEdgeDisplay()
        showUndoSnackbar("Karaoke edge reset") {
            com.autolyrics.auto.AaHost.putEdge(prefs, previous, com.autolyrics.auto.AaHost.iconSizePx)
            prefs.edit().putInt(com.autolyrics.auto.AaHost.EDGE_PREF_KEY, previousGlobal)
                .apply()
            updateKaraokeEdgeDisplay()
        }
    }

    private fun adjustKaraokeEdge(delta: Int) {
        val current = com.autolyrics.auto.AaHost.edge(prefs)
        val updated = (current + delta).coerceIn(-16, 16)
        if (updated == current) return
        com.autolyrics.auto.AaHost.putEdge(prefs, updated, com.autolyrics.auto.AaHost.iconSizePx)
        updateKaraokeEdgeDisplay()
    }

    /** Summarizes the media-source whitelist on the Media source section row. */
    private fun updateMediaAppsDisplay() {
        val monitored = prefs.getStringSet(MediaListenerService.KEY_MONITORED, null)?.toSet()
            ?: emptySet()
        val tv = findViewById<TextView>(R.id.tv_media_apps_value)
        // Read the pref straight back: what the dialog checked is what the
        // row shows. Re-probing package visibility here would put a second,
        // independently flaky source of truth behind the row (it once showed
        // "No installed apps" while the dialog had Symfonium checked).
        tv.text = when {
            monitored.isEmpty() -> "All apps"
            monitored.size == 1 -> mediaAppLabel(monitored.first())
            else -> "${monitored.size} apps"
        }
    }

    private fun mediaAppLabel(pkg: String): String = runCatching {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    }.getOrDefault(pkg)

    private fun updateKaraokeEdgeDisplay() {
        val edge = com.autolyrics.auto.AaHost.edge(prefs)
        findViewById<TextView>(R.id.tv_karaoke_edge).text = if (edge > 0) "+$edge" else "$edge"
        btnKaraokeEdgeReset.isEnabled = edge != 0
        btnKaraokeEdgeReset.alpha = if (edge != 0) 1f else 0.35f
        findViewById<View>(R.id.info_karaoke_edge).contentDescription =
            "Moves the right edge of the Android Auto Karaoke board and the header " +
                "truncation width by one character per tap, for ${com.autolyrics.auto.AaHost.describe()}; " +
                "each head unit keeps its own value."
    }

    private fun adjustAaOffset(deltaMs: Long) {
        dismissUndoSnackbar()
        aaOffsetMs += deltaMs
        prefs.edit().putLong(AA_OFFSET_PREF_KEY, aaOffsetMs).apply()
        updateAaDelayDisplay()
    }

    private fun updateDelayDisplay(offsetMs: Long) {
        val canReset = offsetMs != 0L
        btnPhoneSyncReset.isEnabled = canReset
        btnPhoneSyncReset.alpha = if (canReset) 1f else 0.35f
    }

    private fun dismissUndoSnackbar() {
        activeUndoSnackbar?.dismiss()
        activeUndoSnackbar = null
    }

    private fun showUndoSnackbar(message: String, onUndo: () -> Unit) {
        dismissUndoSnackbar()
        val snackbar = Snackbar.make(rootLayout, message, Snackbar.LENGTH_LONG)
            .setAction("↩ Undo") {
                activeUndoSnackbar = null
                onUndo()
            }

        val snackbarView = snackbar.view
        val params = snackbarView.layoutParams
        if (params is FrameLayout.LayoutParams) {
            params.width = FrameLayout.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            snackbarView.layoutParams = params
        }

        activeUndoSnackbar = snackbar
        snackbar.show()
    }

    private fun configureQuickSyncButtons() {
        val quickButtonIds = listOf(
            R.id.btn_quick_minus_1s,
            R.id.btn_quick_minus_half,
            R.id.btn_quick_minus_tenth,
            R.id.btn_delay_reset,
            R.id.btn_quick_plus_tenth,
            R.id.btn_quick_plus_half,
            R.id.btn_quick_plus_1s
        )
        val spacing = dp(2)

        quickButtonIds.forEachIndexed { index, id ->
            val button = findViewById<Button>(id)
            val params = button.layoutParams as? LinearLayout.LayoutParams ?: return@forEachIndexed
            params.width = 0
            params.weight = 1f
            params.marginStart = if (index == 0) 0 else spacing
            button.layoutParams = params
            button.maxLines = 1
            button.setPadding(dp(2), button.paddingTop, dp(2), button.paddingBottom)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                button,
                7,
                if (id == R.id.btn_delay_reset) 9 else 10,
                1,
                TypedValue.COMPLEX_UNIT_SP
            )
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * Constant leading gutter for one lyric row (lyric line + optional
     * translation subline). The gutter width is IDENTICAL for every row and
     * the ▶ is painted into it — never flowed — so gravity=center_horizontal
     * centers all lyric text on one true axis regardless of marker state.
     */
    private class LyricMarkerGutter(
        private val marginPx: Int,
        private val drawMarker: Boolean,
        private val lyricAnchor: Int,
        private val markerColor: Int
    ) : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean) = marginPx

        override fun drawLeadingMargin(
            c: Canvas,
            p: Paint,
            x: Int,
            dir: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence?,
            start: Int,
            end: Int,
            first: Boolean,
            layout: android.text.Layout?
        ) {
            // The gutter spans the whole row; only the lyric line (the one
            // that starts at the anchor) wears the marker, never the
            // translation subline.
            if (!drawMarker || start != lyricAnchor) return
            val previous = p.color
            p.color = markerColor
            c.drawText("▶", x.toFloat() + dir * marginPx * 0.5f - p.measureText("▶") / 2f,
                baseline.toFloat(), p)
            p.color = previous
        }
    }

    private fun renderSyncedLyrics(state: LyricsState) {
        val ssb = SpannableStringBuilder()
        val colors = state.albumColors
        val highlightColor = colors?.vibrant ?: DEFAULT_HIGHLIGHT
        val dimColor = colors?.textDim ?: DEFAULT_DIM
        val positionMs = safeCurrentPositionMs()

        // Display sequence: real lines interleaved with instrumental marker
        // rows (intro/interlude), same model as the Android Auto surfaces.
        // On the phone markers are STATIC triples - the countdown digits
        // are an Android Auto affordance only - and they highlight, page and
        // scroll exactly like lyric rows.
        val rows = SungLine.virtualRows(state.lines, state.lines.size)
        val curV = SungLine.currentVIndex(rows, state.lines, positionMs)
        // The ▶ marker lives in a fixed leading GUTTER (drawn, not flowed):
        // flowed prefixes ("▶  " vs "    ") center at different widths per
        // line, so the centered block sat visibly off-center and the current
        // line jogged. Every row — lyrics, translations, markers — reserves
        // the identical gutter, so all text centers on the true view axis.
        val gutterPx = Math.ceil(
            tvLyrics.paint.measureText("▶  ").toDouble()
        ).toInt() + dp(4)
        rows.forEachIndexed { v, row ->
            val line = if (row.isGap) null else state.lines[row.realIndex]
            val isPastLine = curV >= 0 && v < curV
            val isCurrentLine = v == curV
            val isFutureLine = curV < 0 || v > curV
            val lineStart = ssb.length

            val lyricStart = ssb.length
            ssb.append(line?.let { LyricDisplay.displayText(it.text) } ?: LyricDisplay.MUSIC_SYMBOL)
            val lyricEnd = ssb.length

            if (isPastLine) {
                ssb.setSpan(
                    ForegroundColorSpan(highlightColor),
                    lyricStart, lyricEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else if (isCurrentLine) {
                ssb.setSpan(
                    StyleSpan(Typeface.BOLD),
                    lineStart, lyricEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                if (line == null || line.words.isEmpty()) {
                    ssb.setSpan(
                        ForegroundColorSpan(highlightColor),
                        lyricStart, lyricEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else {
                    val sweep = PhoneKaraokeSweep.segmentAtPosition(line, positionMs)
                    if (sweep == null) {
                        ssb.setSpan(
                            ForegroundColorSpan(dimColor),
                            lyricStart, lyricEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    } else {
                        if (sweep.start > 0) {
                            ssb.setSpan(
                                ForegroundColorSpan(highlightColor),
                                lyricStart,
                                lyricStart + sweep.start,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }

                        ssb.setSpan(
                            KaraokeSweepSpan(
                                segment = sweep,
                                pendingColor = dimColor,
                                completedColor = highlightColor,
                                positionProvider = ::safeCurrentPositionMs
                            ),
                            lyricStart + sweep.start,
                            lyricStart + sweep.end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )

                        if (sweep.end < line.text.length) {
                            ssb.setSpan(
                                ForegroundColorSpan(dimColor),
                                lyricStart + sweep.end,
                                lyricEnd,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                }
            } else if (isFutureLine) {
                ssb.setSpan(
                    ForegroundColorSpan(dimColor),
                    lineStart, lyricEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            // Only real lines have translations; a marker row never looks
            // up (or displays) a translated line.
            val translatedLine = line?.let {
                state.translatedLines?.getOrNull(row.realIndex)?.takeIf { t -> t.isNotBlank() }
            }
            if (!translatedLine.isNullOrBlank()) {
                ssb.append("\n")
                val tlStart = ssb.length
                ssb.append(translatedLine)
                ssb.setSpan(
                    RelativeSizeSpan(0.8f),
                    tlStart, ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                ssb.setSpan(
                    ForegroundColorSpan(dimColor),
                    tlStart, ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            ssb.setSpan(
                LyricMarkerGutter(gutterPx, isCurrentLine, lyricStart, highlightColor),
                lineStart, ssb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            ssb.append("\n\n")
        }

        // paddingEnd swallows the gutter's half-width line-box shift, so the
        // text column is centered on the VIEW, not on the gutted box.
        tvLyrics.setPadding(dp(20), dp(20), dp(20) + gutterPx, dp(20))
        tvLyrics.text = ssb

        if (curV != lastScrolledIndex && curV >= 0) {
            lastScrolledIndex = curV
            if (!userScrolling) {
                autoScrollToCurrentLine(curV, rows.size)
            }
        }
    }

    private fun safeCurrentPositionMs(): Long = try {
        mediaTracker.getCurrentPositionMs()
    } catch (_: Exception) {
        0L
    }

    private fun syncPhoneKaraokeFrames(state: LyricsState) {
        val line = state.lines.getOrNull(state.currentIndex)
        val positionMs = safeCurrentPositionMs()
        val shouldRun = state.status == LyricsStatus.FOUND &&
            state.isPlaying &&
            line != null &&
            PhoneKaraokeSweep.isAnimating(line, positionMs)

        if (shouldRun) {
            if (!phoneKaraokeFramesRunning) {
                phoneKaraokeFramesRunning = true
                tvLyrics.postOnAnimation(phoneKaraokeFrameRunnable)
            }
        } else {
            stopPhoneKaraokeFrames()
        }
    }

    private fun stopPhoneKaraokeFrames() {
        phoneKaraokeFramesRunning = false
        if (::tvLyrics.isInitialized) {
            tvLyrics.removeCallbacks(phoneKaraokeFrameRunnable)
        }
    }

    override fun onResume() {
        super.onResume()
        aaOffsetMs = prefs.getLong(AA_OFFSET_PREF_KEY, 0L)
        updateAaDelayDisplay()
        updatePermissionUi()
    }

    override fun onStop() {
        stopPhoneKaraokeFrames()
        super.onStop()
    }

    override fun onDestroy() {
        dismissUndoSnackbar()
        prefs.unregisterOnSharedPreferenceChangeListener(aaPrefsListener)
        stopPhoneKaraokeFrames()
        stopPlainScroll()
        super.onDestroy()
    }

    private fun updatePermissionUi() {
        val enabled = isNotificationListenerEnabled()
        if (enabled) {
            tvStatus.visibility = View.GONE
            btnPermission.visibility = View.GONE
        } else {
            tvStatus.text = "⚠  Notification access required"
            tvStatus.setBackgroundResource(R.drawable.bg_status_warn)
            tvStatus.visibility = View.VISIBLE
            btnPermission.visibility = View.VISIBLE
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        val component = ComponentName(this, MediaListenerService::class.java)
        return flat?.contains(component.flattenToString()) == true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupScrollDetection() {
        scrollView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    userTouching = true
                    handler.removeCallbacks(scrollResetRunnable)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    userTouching = false
                    if (userScrolling) {
                        handler.removeCallbacks(scrollResetRunnable)
                        handler.postDelayed(scrollResetRunnable, 8000)
                    }
                }
            }
            false
        }

        scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            if (userTouching && !userScrolling) {
                userScrolling = true
                btnJumpToCurrent.visibility = View.VISIBLE
            }
        }

        btnJumpToCurrent.setOnClickListener {
            userScrolling = false
            btnJumpToCurrent.visibility = View.GONE
            handler.removeCallbacks(scrollResetRunnable)
            val state = mediaTracker.state.value
            val rows = SungLine.virtualRows(state.lines, state.lines.size)
            val curV = SungLine.currentVIndex(rows, state.lines, safeCurrentPositionMs())
            if (curV >= 0) {
                lastScrolledIndex = curV
                autoScrollToCurrentLine(curV, rows.size)
            }
        }
    }

    private fun autoScrollToCurrentLine(currentIndex: Int, totalLines: Int) {
        if (totalLines == 0) return
        scrollView.post {
            val targetY = (currentIndex.toFloat() / totalLines * tvLyrics.height).toInt()
            val offset = scrollView.height / 3
            scrollView.smoothScrollTo(0, maxOf(0, targetY - offset))
        }
    }

    private fun updateAaDelayDisplay() {
        val sign = if (aaOffsetMs > 0) "+" else ""
        tvAaDelay.text = "${sign}${aaOffsetMs}ms"

        val canReset = aaOffsetMs != 0L
        btnAaDelayReset.isEnabled = canReset
        btnAaDelayReset.alpha = if (canReset) 1f else 0.35f
    }

    private fun selectFont(family: String) {
        lyricsFontFamily = family
        saveFontSettings()
        applyFontSettings()
        updateFontButtonHighlights()
    }

    private fun saveFontSettings() {
        prefs.edit()
            .putInt("lyrics_font_size", lyricsFontSizeSp)
            .putString("lyrics_font_family", lyricsFontFamily)
            .apply()
    }

    private fun applyFontSettings() {
        tvLyrics.textSize = lyricsFontSizeSp.toFloat()
        tvLyrics.typeface = Typeface.create(lyricsFontFamily, Typeface.NORMAL)

        val sizeLabel = SpannableStringBuilder().apply {
            append(lyricsFontSizeSp.toString())
            val unitStart = length
            append(" sp")
            setSpan(
                RelativeSizeSpan(0.77f),
                unitStart, length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                ForegroundColorSpan(Color.parseColor("#8888AA")),
                unitStart, length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        tvFontSize.text = sizeLabel
    }

    private fun updateFontButtonHighlights() {
        val selectedTint = Color.parseColor("#3A3A5E")
        val normalTint = Color.parseColor("#2A2A3E")
        fontButtons.forEach { (family, button) ->
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (family == lyricsFontFamily) selectedTint else normalTint
            )
        }
    }

    private fun onTapSyncPressed() {
        val state = mediaTracker.state.value
        if (state.status != LyricsStatus.FOUND || !state.isPlaying) {
            showSyncStatus("Play a synced song first")
            return
        }

        if (!tapSyncActive) {
            val targets = SyncCalibration.upcomingTimedLineIndices(
                state.lines,
                state.currentIndex
            )
            if (targets.size < SyncCalibration.REQUIRED_TAPS) {
                showSyncStatus("Need at least 3 upcoming synced lines")
                return
            }

            tapSyncActive = true
            tapSyncOffsets.clear()
            tapSyncTargetIndices = targets
            tapSyncTrackKey = tapSyncTrackKey(state)
            setTapSyncActiveStyle(true)
            showTapSyncPrompt(state)
            return
        }

        if (tapSyncTrackKey(state) != tapSyncTrackKey) {
            cancelTapSync("Track changed. Tap to Sync cancelled.")
            return
        }

        val targetIndex = tapSyncTargetIndices.getOrNull(tapSyncOffsets.size)
        val targetLine = targetIndex?.let { state.lines.getOrNull(it) }
        if (targetLine == null || targetLine.timeMs <= 0L) {
            cancelTapSync("Tap to Sync cancelled. Timing target is no longer available.")
            return
        }

        val rawPos = getCurrentRawPositionMs()
        tapSyncOffsets.add(
            SyncCalibration.offsetForTap(
                targetTimeMs = targetLine.timeMs,
                rawPositionMs = rawPos
            )
        )

        if (tapSyncOffsets.size >= SyncCalibration.REQUIRED_TAPS) {
            val avgOffset = tapSyncOffsets.average().toLong()
            dismissUndoSnackbar()
            mediaTracker.setOffset(avgOffset)
            finishTapSync()
            showSyncStatus("Synced! Offset: ${formatOffset(avgOffset)} · this lyrics")
        } else {
            showTapSyncPrompt(state)
        }
    }

    private fun showTapSyncPrompt(state: LyricsState) {
        val targetIndex = tapSyncTargetIndices.getOrNull(tapSyncOffsets.size) ?: return
        val lineText = state.lines.getOrNull(targetIndex)?.text?.take(60) ?: "…"
        val tapNumber = tapSyncOffsets.size + 1
        btnTapSync.text = "⏎  TAP when you hear it ($tapNumber/${SyncCalibration.REQUIRED_TAPS})"
        showSyncStatus("Listen for:\n\"$lineText\"")
    }

    private fun cancelTapSyncIfTrackChanged(state: LyricsState) {
        if (tapSyncActive && tapSyncTrackKey(state) != tapSyncTrackKey) {
            cancelTapSync("Track changed. Tap to Sync cancelled.")
        }
    }

    private fun cancelTapSync(message: String) {
        finishTapSync()
        showSyncStatus(message)
    }

    private fun finishTapSync() {
        tapSyncActive = false
        tapSyncOffsets.clear()
        tapSyncTargetIndices = emptyList()
        tapSyncTrackKey = null
        setTapSyncActiveStyle(false)
        btnTapSync.text = "Tap to Sync"
    }

    private fun tapSyncTrackKey(state: LyricsState): String? {
        return state.track?.let { track ->
            "${track.title}\u0000${track.artist}\u0000${track.album}\u0000${track.durationMs}"
        }
    }

    private fun setTapSyncActiveStyle(active: Boolean) {
        if (active) {
            btnTapSync.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#4A2A6E")
            )
            btnTapSync.setTextColor(Color.parseColor("#EEDDFF"))
        } else {
            btnTapSync.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#2A2A3E")
            )
            btnTapSync.setTextColor(Color.parseColor("#CCCCDD"))
        }
    }

    private fun getCurrentRawPositionMs(): Long {
        return try {
            mediaTracker.getCurrentPositionMs() - mediaTracker.state.value.offsetMs
        } catch (_: Exception) { 0L }
    }

    private val hideSyncStatusRunnable = Runnable {
        if (!tapSyncActive) {
            tvSyncStatus.visibility = View.GONE
        }
    }

    private fun showSyncStatus(text: String) {
        tvSyncStatus.text = text
        tvSyncStatus.visibility = View.VISIBLE
        handler.removeCallbacks(hideSyncStatusRunnable)
        handler.postDelayed(hideSyncStatusRunnable, 8000)
    }

    private fun formatOffset(ms: Long): String {
        val sign = if (ms >= 0) "+" else ""
        return "${sign}${ms}ms"
    }

    private fun startPlainScroll(state: LyricsState) {
        val durationMs = state.track?.durationMs ?: 0
        if (durationMs <= 0 || state.lines.isEmpty()) {
            scrollView.scrollTo(0, 0)
            return
        }

        val trackTitle = state.track?.title
        val isNewTrack = trackTitle != lastPlainTrackTitle
        lastPlainTrackTitle = trackTitle

        tvLyrics.post {
            val maxScroll = tvLyrics.height - scrollView.height
            if (maxScroll <= 0) return@post

            val posMs = try {
                mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
            } catch (_: Exception) { 0L }
            val remainingMs = (durationMs - posMs).coerceAtLeast(0)
            val startFraction = posMs.toFloat() / durationMs
            val startScrollY = (startFraction * maxScroll).toInt().coerceIn(0, maxScroll)

            if (isNewTrack || !userScrolling) {
                scrollView.scrollTo(0, startScrollY)
            }

            plainScrollAnimator?.cancel()
            plainScrollAnimator = ValueAnimator.ofInt(startScrollY, maxScroll).apply {
                duration = remainingMs
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    if (!userScrolling) {
                        scrollView.scrollTo(0, anim.animatedValue as Int)
                    }
                }
                start()
                if (!state.isPlaying) pause()
            }
        }
    }

    private fun stopPlainScroll() {
        plainScrollAnimator?.cancel()
        plainScrollAnimator = null
        lastPlainTrackTitle = null
    }

    companion object {
        private const val AA_OFFSET_PREF_KEY = "aa_offset_ms"
        private const val AA_SYNC_STEP_MS = 100L
        private val DEFAULT_BG = Color.parseColor("#121212")
        private val DEFAULT_APP_BAR = Color.parseColor("#1E1E2E")
        private val DEFAULT_DELAY_BAR = Color.parseColor("#1A1A2A")
        private val DEFAULT_DIVIDER = Color.parseColor("#2A2A3A")
        private val DEFAULT_HIGHLIGHT = Color.parseColor("#FFD54F")
        private val DEFAULT_DIM = Color.parseColor("#99FFFFFF")
    }
}
