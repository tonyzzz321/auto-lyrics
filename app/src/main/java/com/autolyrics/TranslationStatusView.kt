package com.autolyrics

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autolyrics.lyrics.LlmTranslator
import com.autolyrics.lyrics.LyricsTranslator
import com.autolyrics.lyrics.TranslationEngineUi
import com.autolyrics.lyrics.TranslationLanguages
import com.autolyrics.media.MediaTracker
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TranslationStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private data class DownloadManagerInfo(
        val expectedFiles: Set<String>,
        val matchedFile: String? = null,
        val status: Int? = null,
        val bytesDownloaded: Long? = null,
        val totalBytes: Long? = null,
        val error: String? = null
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val statusText = TextView(context).apply {
        setTextColor(Color.parseColor("#AAAACC"))
        textSize = 11f
        maxLines = 2
    }

    private val retryButton = Button(context).apply {
        text = "Retry"
        textSize = 10f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minimumHeight = 0
        backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4A252C"))
        setTextColor(Color.parseColor("#D7A0A8"))
        setPadding(dp(12), 0, dp(12), 0)
        visibility = View.GONE
        setOnClickListener { retryTranslation() }
    }

    private val redoButton = Button(context).apply {
        text = "Redo"
        textSize = 10f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minimumHeight = 0
        backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2A2A3E"))
        setTextColor(Color.parseColor("#CCCCDD"))
        setPadding(dp(12), 0, dp(12), 0)
        visibility = View.GONE
        setOnClickListener {
            MediaTracker.getInstance(context).resetTranslationForCurrentTrack()
        }
    }

    private val progressBar = LinearProgressIndicator(context).apply {
        isIndeterminate = true
        max = 100
        setIndicatorColor(Color.parseColor("#BB86FC"))
        trackColor = Color.parseColor("#2A2A3E")
        trackThickness = dp(3)
        visibility = View.GONE
    }

    private val debugText = TextView(context).apply {
        setTextColor(Color.parseColor("#777792"))
        textSize = 9f
        typeface = Typeface.MONOSPACE
        visibility = View.GONE
        setPadding(0, dp(6), 0, 0)
    }

    private var observationJob: Job? = null
    private var downloadStatusJob: Job? = null
    private var hostActivity: AppCompatActivity? = null
    private var progressModelLanguage: String? = null

    private fun activeEngine(): TranslationEngineUi =
        if (LlmTranslator.isLlmEngine(prefs)) LlmTranslator else LyricsTranslator

    private val elapsedRefreshRunnable = Runnable {
        render(activeEngine().uiState.value)
    }

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TRANSLATION_ENABLED_KEY ||
                key == TranslationLanguages.TARGET_LANGUAGE_PREF_KEY ||
                key == LlmTranslator.PREF_ENGINE_KEY
            ) {
                if (key == TRANSLATION_ENABLED_KEY &&
                    !prefs.getBoolean(TRANSLATION_ENABLED_KEY, true)
                ) {
                    LyricsTranslator.resetUiState()
                    LlmTranslator.resetUiState()
                }
                if (key == LlmTranslator.PREF_ENGINE_KEY) {
                    restartObservation()
                }
                post { render(activeEngine().uiState.value) }
            }
        }

    init {
        orientation = VERTICAL
        visibility = View.GONE

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            statusText,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            retryButton,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
        )
        row.addView(
            redoButton,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply {
                marginStart = dp(6)
            }
        )
        addView(
            row,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        addView(
            progressBar,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        )

        if (BuildConfig.DEBUG) {
            addView(
                debugText,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        render(activeEngine().uiState.value)
        restartObservation()
    }

    private fun restartObservation() {
        val activity = context as? AppCompatActivity ?: return
        hostActivity = activity
        observationJob?.cancel()
        observationJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                activeEngine().uiState.collect { state ->
                    render(state)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(elapsedRefreshRunnable)
        downloadStatusJob?.cancel()
        downloadStatusJob = null
        hostActivity = null
        observationJob?.cancel()
        observationJob = null
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onDetachedFromWindow()
    }

    private fun render(state: LyricsTranslator.UiState) {
        removeCallbacks(elapsedRefreshRunnable)

        if (!prefs.getBoolean(TRANSLATION_ENABLED_KEY, true)) {
            clearCurrentModelProgress()
            hideProgress()
            hideDebugDiagnostics()
            visibility = View.GONE
            return
        }

        val sourceLanguage = languageName(state.sourceLanguage)
        val targetLanguage = languageName(state.targetLanguage) ?: "English"
        val modelLanguage = languageName(state.modelLanguage)
        val canRetry = when (state.phase) {
            LyricsTranslator.Phase.DOWNLOAD_FAILED,
            LyricsTranslator.Phase.DOWNLOAD_TIMED_OUT,
            LyricsTranslator.Phase.TRANSLATION_FAILED -> true
            else -> false
        }

        if (state.phase == LyricsTranslator.Phase.DOWNLOAD_FAILED ||
            state.phase == LyricsTranslator.Phase.DOWNLOAD_TIMED_OUT
        ) {
            state.modelLanguage?.let { downloadStartedAtByModel.remove(it) }
        }

        val modelOperation = isModelOperationPhase(state.phase)
        val elapsedMs = if (modelOperation) {
            currentDownloadElapsedMs(state.modelLanguage)
        } else {
            null
        }

        val text = when (state.phase) {
            LyricsTranslator.Phase.IDLE -> null
            LyricsTranslator.Phase.DETECTING_LANGUAGE -> "Detecting language…"
            LyricsTranslator.Phase.CHECKING_MODEL ->
                "Checking ${modelLanguage ?: "translation"} model…"
            LyricsTranslator.Phase.DOWNLOADING_MODEL ->
                "Downloading ${modelLanguage ?: "translation"} model…\n" +
                    "${sourceLanguage ?: "Lyrics"} → $targetLanguage · " +
                    "Elapsed ${formatElapsed(elapsedMs ?: 0L)}"
            LyricsTranslator.Phase.WAITING_FOR_SYSTEM ->
                "Waiting to download ${modelLanguage ?: "translation"} model…\n" +
                    "System thermal limit · Elapsed ${formatElapsed(elapsedMs ?: 0L)}"
            LyricsTranslator.Phase.TRANSLATING -> {
                val engineSuffix = if (LlmTranslator.isLlmEngine(prefs)) {
                    prefs.getString(LlmTranslator.PREF_LLM_MODEL, "").orEmpty()
                        .takeIf { it.isNotBlank() }
                        ?.let { " · $it" }
                        .orEmpty()
                } else {
                    ""
                }
                "Translating ${sourceLanguage ?: "lyrics"} → $targetLanguage$engineSuffix…"
            }
            LyricsTranslator.Phase.READY ->
                "${sourceLanguage ?: "Lyrics"} → $targetLanguage"
            LyricsTranslator.Phase.ALREADY_ENGLISH ->
                "Already $targetLanguage"
            LyricsTranslator.Phase.UNSUPPORTED_LANGUAGE ->
                state.error ?: "Unsupported language${state.sourceLanguage?.let { ": $it" } ?: ""}"
            LyricsTranslator.Phase.DOWNLOAD_FAILED ->
                buildFailureText("${modelLanguage ?: "Model"} download failed", state.error)
            LyricsTranslator.Phase.DOWNLOAD_TIMED_OUT ->
                buildFailureText("${modelLanguage ?: "Model"} download timed out", state.error)
            LyricsTranslator.Phase.TRANSLATION_FAILED ->
                buildFailureText("Translation failed", state.error)
        }

        if (text == null) {
            clearCurrentModelProgress()
            hideProgress()
            hideDebugDiagnostics()
            visibility = View.GONE
            return
        }

        visibility = View.VISIBLE
        statusText.text = text
        statusText.setTextColor(
            if (canRetry || state.phase == LyricsTranslator.Phase.UNSUPPORTED_LANGUAGE) {
                Color.parseColor("#FFB4AB")
            } else {
                Color.parseColor("#AAAACC")
            }
        )
        retryButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        redoButton.visibility = when (state.phase) {
            LyricsTranslator.Phase.READY,
            LyricsTranslator.Phase.ALREADY_ENGLISH -> View.VISIBLE
            else -> View.GONE
        }

        when (state.phase) {
            LyricsTranslator.Phase.DETECTING_LANGUAGE,
            LyricsTranslator.Phase.CHECKING_MODEL -> {
                clearCurrentModelProgress()
                showIndeterminateProgress()
            }

            LyricsTranslator.Phase.DOWNLOADING_MODEL,
            LyricsTranslator.Phase.WAITING_FOR_SYSTEM -> {
                if (progressModelLanguage != state.modelLanguage ||
                    progressBar.visibility != View.VISIBLE
                ) {
                    progressModelLanguage?.let { downloadStartedAtByModel.remove(it) }
                    progressModelLanguage = state.modelLanguage
                    showIndeterminateProgress()
                }
            }

            LyricsTranslator.Phase.TRANSLATING -> {
                clearCurrentModelProgress()
                showDeterminateProgress(100, animate = true)
            }
            else -> {
                clearCurrentModelProgress()
                hideProgress()
            }
        }

        if (modelOperation && state.modelLanguage != null) {
            refreshDownloadStatus(state.modelLanguage)
            postDelayed(elapsedRefreshRunnable, ELAPSED_REFRESH_MS)
        } else {
            hideDebugDiagnostics()
        }
    }

    private fun retryTranslation() {
        retryButton.isEnabled = false
        LyricsTranslator.uiState.value.modelLanguage?.let {
            downloadStartedAtByModel.remove(it)
        }
        progressModelLanguage = null

        activeEngine().prepareManualRetry()
        prefs.edit().putBoolean(TRANSLATION_ENABLED_KEY, false).apply()
        postDelayed({
            prefs.edit().putBoolean(TRANSLATION_ENABLED_KEY, true).apply()
            retryButton.isEnabled = true
        }, RETRY_TOGGLE_DELAY_MS)
    }

    private fun refreshDownloadStatus(modelLanguage: String) {
        if (downloadStatusJob?.isActive == true) return
        val activity = hostActivity ?: return

        downloadStatusJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            val info = queryDownloadManager(modelLanguage)
            val thermalStatus = currentThermalStatus()
            withContext(Dispatchers.Main) {
                val current = activeEngine().uiState.value
                if (!isAttachedToWindow ||
                    !isModelOperationPhase(current.phase) ||
                    current.modelLanguage != modelLanguage
                ) {
                    return@withContext
                }

                applyDownloadProgress(info)
                if (BuildConfig.DEBUG) {
                    debugText.text = buildDebugText(info, current.phase, thermalStatus)
                    debugText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun applyDownloadProgress(info: DownloadManagerInfo) {
        val downloaded = info.bytesDownloaded ?: -1L
        val total = info.totalBytes ?: -1L

        if (downloaded > 0L && total > 0L) {
            val transferPercent = ((downloaded.toDouble() / total.toDouble()) * 100.0)
                .toInt()
                .coerceIn(1, MODEL_TRANSFER_MAX_PERCENT)
            showDeterminateProgress(transferPercent, animate = true)
        } else if (progressBar.isIndeterminate || progressBar.progress <= 0) {
            showIndeterminateProgress()
        }
    }

    private fun showIndeterminateProgress() {
        if (progressBar.isIndeterminate && progressBar.visibility == View.VISIBLE) return
        progressBar.visibility = View.INVISIBLE
        progressBar.isIndeterminate = true
        progressBar.visibility = View.VISIBLE
    }

    private fun showDeterminateProgress(percent: Int, animate: Boolean) {
        val safePercent = percent.coerceIn(0, 100)
        if (progressBar.isIndeterminate) {
            progressBar.visibility = View.INVISIBLE
            progressBar.isIndeterminate = false
            progressBar.setProgressCompat(safePercent, false)
            progressBar.visibility = View.VISIBLE
        } else {
            progressBar.visibility = View.VISIBLE
            progressBar.setProgressCompat(safePercent, animate)
        }
    }

    private fun hideProgress() {
        progressBar.hide()
        progressModelLanguage = null
    }

    private fun clearCurrentModelProgress() {
        progressModelLanguage?.let { downloadStartedAtByModel.remove(it) }
        progressModelLanguage = null
    }

    private fun hideDebugDiagnostics() {
        downloadStatusJob?.cancel()
        downloadStatusJob = null
        debugText.visibility = View.GONE
    }

    private fun queryDownloadManager(modelLanguage: String): DownloadManagerInfo {
        val expectedFiles = expectedModelFileNames(modelLanguage)
        if (expectedFiles.isEmpty()) {
            return DownloadManagerInfo(expectedFiles = emptySet(), error = "no filename candidates")
        }

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return DownloadManagerInfo(expectedFiles = expectedFiles, error = "Download progress unavailable")

        return try {
            manager.query(DownloadManager.Query()).use { cursor ->
                val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
                val titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val bytesIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val modifiedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)

                var bestFile: String? = null
                var bestStatus: Int? = null
                var bestBytes: Long? = null
                var bestTotal: Long? = null
                var bestModified = Long.MIN_VALUE

                while (cursor.moveToNext()) {
                    val uri = if (uriIndex >= 0) cursor.getString(uriIndex).orEmpty() else ""
                    val title = if (titleIndex >= 0) cursor.getString(titleIndex).orEmpty() else ""
                    val uriFile = uri.substringAfterLast('/').substringBefore('?').lowercase(Locale.US)
                    val titleFile = title.lowercase(Locale.US)
                    val matchedFile = expectedFiles.firstOrNull { candidate ->
                        uriFile == candidate || titleFile == candidate ||
                            uri.lowercase(Locale.US).endsWith("/$candidate")
                    } ?: continue

                    val modified = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) else 0L
                    val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else null
                    val isActive = status == DownloadManager.STATUS_PENDING ||
                        status == DownloadManager.STATUS_RUNNING ||
                        status == DownloadManager.STATUS_PAUSED
                    val bestIsActive = bestStatus == DownloadManager.STATUS_PENDING ||
                        bestStatus == DownloadManager.STATUS_RUNNING ||
                        bestStatus == DownloadManager.STATUS_PAUSED

                    if (bestFile == null || (isActive && !bestIsActive) ||
                        (isActive == bestIsActive && modified >= bestModified)
                    ) {
                        bestFile = matchedFile
                        bestStatus = status
                        bestBytes = if (bytesIndex >= 0) cursor.getLong(bytesIndex) else null
                        bestTotal = if (totalIndex >= 0) cursor.getLong(totalIndex) else null
                        bestModified = modified
                    }
                }

                DownloadManagerInfo(
                    expectedFiles = expectedFiles,
                    matchedFile = bestFile,
                    status = bestStatus,
                    bytesDownloaded = bestBytes,
                    totalBytes = bestTotal
                )
            }
        } catch (e: Exception) {
            DownloadManagerInfo(expectedFiles = expectedFiles, error = e.javaClass.simpleName)
        }
    }

    private fun expectedModelFileNames(modelLanguage: String): Set<String> {
        val normalized = Locale.forLanguageTag(modelLanguage).language
            .ifBlank { modelLanguage.substringBefore('-') }
            .lowercase(Locale.US)
        if (normalized.isBlank() || normalized == "en") return emptySet()

        val modelCodes = when (normalized) {
            "he" -> setOf("he", "iw")
            "id" -> setOf("id", "in")
            "yi" -> setOf("yi", "ji")
            else -> setOf(normalized)
        }

        return modelCodes.mapTo(linkedSetOf()) { modelCode ->
            listOf(modelCode, "en").sorted().joinToString("_") + ".zip"
        }
    }

    private fun buildDebugText(
        info: DownloadManagerInfo,
        phase: LyricsTranslator.Phase,
        thermalStatus: Int?
    ): String {
        val downloaded = info.bytesDownloaded
        val total = info.totalBytes

        if (downloaded != null && downloaded >= 0L && total != null && total > 0L) {
            val state = when {
                downloaded >= total -> "Processing"
                info.status == DownloadManager.STATUS_RUNNING -> "Downloading"
                info.status == DownloadManager.STATUS_PAUSED -> "Paused"
                phase == LyricsTranslator.Phase.WAITING_FOR_SYSTEM -> "Waiting"
                else -> "Downloading"
            }
            return "${formatBytes(downloaded)} / ${formatBytes(total)} · $state"
        }

        if (phase == LyricsTranslator.Phase.WAITING_FOR_SYSTEM) {
            return "0 B / ? · Waiting · Thermal: ${compactThermalStatusName(thermalStatus)}"
        }

        if (!progressBar.isIndeterminate && progressBar.progress > 0) {
            return if (progressBar.progress >= MODEL_TRANSFER_MAX_PERCENT) {
                "Transfer complete · Processing"
            } else {
                "${progressBar.progress}% · Downloading"
            }
        }

        return when {
            info.error != null -> "Download progress unavailable"
            info.matchedFile == null -> "0 B / ? · Waiting for system"
            info.status == DownloadManager.STATUS_FAILED -> "0 B / ? · Download failed"
            info.status == DownloadManager.STATUS_PAUSED -> "0 B / ? · Paused"
            else -> "0 B / ? · Waiting for system"
        }
    }

    private fun currentThermalStatus(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.currentThermalStatus
        } catch (_: Exception) {
            null
        }
    }

    private fun compactThermalStatusName(status: Int?): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "None"
        PowerManager.THERMAL_STATUS_LIGHT -> "Light"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
        null -> "Unavailable"
        else -> "$status"
    }

    private fun isModelOperationPhase(phase: LyricsTranslator.Phase): Boolean {
        return phase == LyricsTranslator.Phase.DOWNLOADING_MODEL ||
            phase == LyricsTranslator.Phase.WAITING_FOR_SYSTEM
    }

    private fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "?"
        return when {
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun languageName(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        return Locale.forLanguageTag(code)
            .getDisplayLanguage(Locale.ENGLISH)
            .takeIf { it.isNotBlank() }
            ?: code.uppercase(Locale.ENGLISH)
    }

    private fun currentDownloadElapsedMs(modelLanguage: String?): Long {
        val key = modelLanguage ?: UNKNOWN_MODEL_KEY
        val startedAt = downloadStartedAtByModel.getOrPut(key) { SystemClock.elapsedRealtime() }
        return (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            "${minutes}m ${seconds.toString().padStart(2, '0')}s"
        } else {
            "${seconds}s"
        }
    }

    private fun buildFailureText(prefix: String, error: String?): String {
        val detail = error?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_ERROR_LENGTH)
        return if (detail == null) prefix else "$prefix · $detail"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS_NAME = "auto_lyrics_prefs"
        private const val TRANSLATION_ENABLED_KEY = "translation_enabled"
        private const val RETRY_TOGGLE_DELAY_MS = 150L
        private const val ELAPSED_REFRESH_MS = 1_000L
        private const val MODEL_TRANSFER_MAX_PERCENT = 99
        private const val MAX_ERROR_LENGTH = 90
        private const val VIEW_TAG = "translation_status_view"
        private const val UNKNOWN_MODEL_KEY = "__unknown_model__"
        private val downloadStartedAtByModel = ConcurrentHashMap<String, Long>()

        fun install(activity: AppCompatActivity) {
            val translationSwitch = activity.findViewById<View>(R.id.switch_translation) ?: return
            val translationRow = translationSwitch.parent as? ViewGroup ?: return
            val settingsPanel = translationRow.parent as? LinearLayout ?: return
            if (settingsPanel.findViewWithTag<View>(VIEW_TAG) != null) return

            val view = TranslationStatusView(activity).apply { tag = VIEW_TAG }
            val index = settingsPanel.indexOfChild(translationRow)
            settingsPanel.addView(
                view,
                index + 1,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = view.dp(2)
                    bottomMargin = view.dp(4)
                }
            )
        }
    }
}
