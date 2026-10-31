package com.autolyrics.lyrics

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.autolyrics.model.LyricLine
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TranslationResult(
    val translatedLines: List<String>,
    val detectedLanguage: String,
    val targetLanguage: String
)

object LyricsTranslator : TranslationEngineUi {

    const val MODEL_ID = "mlkit"

    enum class Phase {
        IDLE,
        DETECTING_LANGUAGE,
        CHECKING_MODEL,
        DOWNLOADING_MODEL,
        WAITING_FOR_SYSTEM,
        TRANSLATING,
        READY,
        ALREADY_ENGLISH,
        UNSUPPORTED_LANGUAGE,
        DOWNLOAD_FAILED,
        DOWNLOAD_TIMED_OUT,
        TRANSLATION_FAILED
    }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val sourceLanguage: String? = null,
        val targetLanguage: String = TranslationLanguages.DEFAULT_TARGET_LANGUAGE,
        val modelLanguage: String? = null,
        val error: String? = null
    )

    private data class TranslationRequest(
        val sourceLanguage: String,
        val targetLanguage: String
    )

    private data class RetryState(
        val phase: Phase,
        val error: String
    )

    private const val TAG = "LyricsTranslator"
    private const val MODEL_DOWNLOAD_TIMEOUT_MS = 5L * 60 * 1000
    private const val MODEL_POLL_INTERVAL_MS = 2_000L
    private const val MODEL_CHECK_TIMEOUT_MS = 10_000L
    private const val MODEL_POLL_LOG_INTERVAL = 5

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var statusOwnerRequest: TranslationRequest? = null

    private val modelDownloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ML Kit language packs are shared by every translation pair that needs them.
    private val activeModelDownloads = ConcurrentHashMap<String, Task<Void>>()
    private val activeModelMonitors = ConcurrentHashMap<String, Deferred<Boolean>>()

    // The most recent foreground request waiting on each model owns that model's UI.
    // This lets a newer request reuse an older background monitor without allowing the
    // old request to overwrite the shared status view.
    private val modelUiOwners = ConcurrentHashMap<String, TranslationRequest>()

    // Download failures belong to a language pack. Translation failures belong to a
    // source -> target request after all required packs are available.
    private val modelRetryRequired = ConcurrentHashMap<String, RetryState>()
    private val translationRetryRequired = ConcurrentHashMap<TranslationRequest, RetryState>()

    private val _uiState = MutableStateFlow(UiState())
    override val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    override fun resetUiState() {
        _uiState.value = UiState()
    }

    override fun publishReady(sourceLanguage: String, targetLanguage: String) {
        _uiState.value = UiState(
            phase = Phase.READY,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage
        )
    }

    override fun prepareManualRetry() {
        val current = _uiState.value
        val source = current.sourceLanguage ?: run {
            resetUiState()
            return
        }
        val request = TranslationRequest(
            sourceLanguage = source,
            targetLanguage = TranslationLanguages.normalizeTargetLanguage(current.targetLanguage)
        )
        statusOwnerRequest = request

        when (current.phase) {
            Phase.DOWNLOAD_FAILED,
            Phase.DOWNLOAD_TIMED_OUT -> {
                current.modelLanguage?.let { modelLanguage ->
                    val removed = modelRetryRequired.remove(modelLanguage)
                    if (removed != null) {
                        Log.d(TAG, "manual retry enabled; clearing model retry gate for $modelLanguage")
                        startModelDownloadMonitor(modelLanguage, request)
                    }
                }
            }

            Phase.TRANSLATION_FAILED -> {
                if (translationRetryRequired.remove(request) != null) {
                    Log.d(
                        TAG,
                        "manual retry enabled; clearing translation retry gate for " +
                            "${request.sourceLanguage}->${request.targetLanguage}"
                    )
                }
            }

            else -> Unit
        }

        resetUiState()
    }

    suspend fun translateLines(
        lines: List<LyricLine>,
        targetLanguage: String = TranslationLanguages.DEFAULT_TARGET_LANGUAGE
    ): TranslationResult? {
        val target = TranslationLanguages.normalizeTargetLanguage(targetLanguage)
        Log.d(TAG, "translateLines start: lines=${lines.size} target=$target")
        // A newly requested track/target immediately owns the foreground status, even
        // before language detection finishes. Older model monitors must continue only
        // in the background and must not reclaim the status UI during early-return paths.
        statusOwnerRequest = null
        updateState(Phase.DETECTING_LANGUAGE, targetLanguage = target)

        val sampleText = lines
            .map { it.text }
            .filter { it.isNotBlank() && it != "♪" }
            .take(5)
            .joinToString("\n")

        if (sampleText.isBlank()) {
            val reason = "No translatable lyric text"
            Log.w(TAG, "skip: $reason")
            updateState(Phase.TRANSLATION_FAILED, targetLanguage = target, error = reason)
            return null
        }

        Log.d(TAG, "detecting source language")
        val detectedLanguage = detectLanguage(sampleText)
        if (detectedLanguage == null) {
            val reason = "Language detection failed"
            Log.w(TAG, "skip: $reason")
            updateState(Phase.TRANSLATION_FAILED, targetLanguage = target, error = reason)
            return null
        }
        if (detectedLanguage == "und") {
            val reason = "Could not determine lyric language"
            Log.w(TAG, "skip: $reason")
            updateState(
                Phase.UNSUPPORTED_LANGUAGE,
                sourceLanguage = detectedLanguage,
                targetLanguage = target,
                error = reason
            )
            return null
        }

        val source = TranslationLanguages.normalizeLanguageTag(detectedLanguage)
        if (source == null) {
            val reason = "Could not normalize lyric language '$detectedLanguage'"
            Log.w(TAG, "skip: $reason")
            updateState(
                Phase.UNSUPPORTED_LANGUAGE,
                sourceLanguage = detectedLanguage,
                targetLanguage = target,
                error = reason
            )
            return null
        }

        Log.d(TAG, "detected source language=$source target=$target")

        if (TranslationLanguages.baseLanguage(source) == TranslationLanguages.baseLanguage(target)) {
            Log.d(TAG, "skip: source already matches target base language=$target")
            // The legacy enum name is retained until the Settings/status UI stage.
            updateState(
                Phase.ALREADY_ENGLISH,
                sourceLanguage = source,
                targetLanguage = target
            )
            return null
        }

        val sourceMlLanguage = TranslateLanguage.fromLanguageTag(source)
        if (sourceMlLanguage == null) {
            val reason = "ML Kit does not support source language '$source'"
            Log.w(TAG, "skip: $reason")
            updateState(
                Phase.UNSUPPORTED_LANGUAGE,
                sourceLanguage = source,
                targetLanguage = target,
                error = reason
            )
            return null
        }

        val targetMlLanguage = TranslateLanguage.fromLanguageTag(target)
        if (targetMlLanguage == null) {
            val reason = "ML Kit does not support target language '$target'"
            Log.w(TAG, "skip: $reason")
            updateState(
                Phase.UNSUPPORTED_LANGUAGE,
                sourceLanguage = source,
                targetLanguage = target,
                error = reason
            )
            return null
        }

        val request = TranslationRequest(source, target)
        statusOwnerRequest = request

        translationRetryRequired[request]?.let { blocked ->
            Log.d(
                TAG,
                "automatic translation retry suppressed until user presses Retry: " +
                    "request=$source->$target phase=${blocked.phase}"
            )
            publishRetryState(request, modelLanguage = null, retry = blocked)
            return null
        }

        val requiredModels = TranslationLanguages.requiredModelLanguages(source, target)
        for (modelLanguage in requiredModels) {
            val blockedModel = modelRetryRequired[modelLanguage]
            if (blockedModel != null) {
                Log.d(
                    TAG,
                    "automatic model retry suppressed until user presses Retry: " +
                        "model=$modelLanguage request=$source->$target phase=${blockedModel.phase}"
                )
                modelUiOwners[modelLanguage] = request
                publishRetryState(request, modelLanguage, blockedModel)
                return null
            }

            if (!ensureModelAvailable(modelLanguage, request)) {
                return null
            }
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceMlLanguage)
            .setTargetLanguage(targetMlLanguage)
            .build()
        val translator = Translation.getClient(options)

        try {
            publishState(Phase.TRANSLATING, request)
            var failures = 0
            var attempted = 0

            val translated = lines.map { line ->
                val text = line.text.trim()
                if (text.isBlank() || text == "♪") {
                    ""
                } else {
                    attempted++
                    try {
                        translateText(translator, text)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures++
                        Log.w(TAG, "line translation failed; keeping original", e)
                        text
                    }
                }
            }

            if (attempted > 0 && failures == attempted) {
                val reason = "All lyric lines failed to translate"
                Log.e(TAG, reason)
                markTranslationRetryRequired(
                    request = request,
                    phase = Phase.TRANSLATION_FAILED,
                    reason = reason
                )
                return null
            }

            Log.d(
                TAG,
                "translation complete: request=$source->$target lines=${translated.size}, " +
                    "failures=$failures"
            )
            translationRetryRequired.remove(request)
            publishState(Phase.READY, request)
            return TranslationResult(
                translatedLines = translated,
                detectedLanguage = source,
                targetLanguage = target
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.localizedMessage ?: e.javaClass.simpleName
            Log.e(TAG, "translation failed: request=$source->$target", e)
            markTranslationRetryRequired(
                request = request,
                phase = Phase.TRANSLATION_FAILED,
                reason = reason
            )
            return null
        } finally {
            translator.close()
        }
    }

    private suspend fun ensureModelAvailable(
        modelLanguage: String,
        request: TranslationRequest
    ): Boolean {
        val mlLanguage = TranslateLanguage.fromLanguageTag(modelLanguage)
        if (mlLanguage == null) {
            val reason = "ML Kit does not support model language '$modelLanguage'"
            markModelRetryRequired(
                modelLanguage = modelLanguage,
                phase = Phase.DOWNLOAD_FAILED,
                reason = reason,
                requestHint = request
            )
            return false
        }

        val manager = RemoteModelManager.getInstance()
        val model = TranslateRemoteModel.Builder(mlLanguage).build()

        publishState(Phase.CHECKING_MODEL, request, modelLanguage)

        val downloaded = try {
            isModelDownloaded(manager, model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.localizedMessage ?: e.javaClass.simpleName
            Log.e(TAG, "translation model check failed: $modelLanguage", e)
            markModelRetryRequired(
                modelLanguage = modelLanguage,
                phase = Phase.DOWNLOAD_FAILED,
                reason = reason,
                requestHint = request
            )
            return false
        }

        if (downloaded) {
            modelRetryRequired.remove(modelLanguage)
            Log.d(TAG, "translation model already downloaded: $modelLanguage")
            return true
        }

        Log.d(
            TAG,
            "translation model missing; ensuring explicit download: " +
                "model=$modelLanguage request=${request.sourceLanguage}->${request.targetLanguage}"
        )

        modelUiOwners[modelLanguage] = request
        return try {
            val becameAvailable = waitForModelDownload(
                manager = manager,
                model = model,
                modelLanguage = modelLanguage,
                conditions = DownloadConditions.Builder().build()
            )
            if (becameAvailable) {
                modelRetryRequired.remove(modelLanguage)
                Log.d(TAG, "translation model confirmed available: $modelLanguage")
                true
            } else {
                modelRetryRequired[modelLanguage]?.let { failure ->
                    if (statusOwnerRequest == request) {
                        publishRetryState(request, modelLanguage, failure)
                    }
                }
                false
            }
        } catch (e: CancellationException) {
            // Only this track's waiter is cancelled. The application-level monitor
            // continues and can be reused by the next request that needs this model.
            throw e
        }
    }

    private fun markModelRetryRequired(
        modelLanguage: String,
        phase: Phase,
        reason: String,
        requestHint: TranslationRequest? = null
    ) {
        val retry = RetryState(phase = phase, error = reason)
        modelRetryRequired[modelLanguage] = retry

        val request = modelUiOwners[modelLanguage] ?: requestHint
        if (request != null && statusOwnerRequest == request) {
            publishRetryState(request, modelLanguage, retry)
        } else {
            Log.d(
                TAG,
                "model retry state latched in background without replacing foreground UI: " +
                    "model=$modelLanguage owner=$request foreground=$statusOwnerRequest phase=$phase"
            )
        }
    }

    private fun markTranslationRetryRequired(
        request: TranslationRequest,
        phase: Phase,
        reason: String
    ) {
        val retry = RetryState(phase = phase, error = reason)
        translationRetryRequired[request] = retry

        if (statusOwnerRequest == request) {
            publishRetryState(request, modelLanguage = null, retry = retry)
        } else {
            Log.d(
                TAG,
                "translation retry state latched in background without replacing foreground UI: " +
                    "request=${request.sourceLanguage}->${request.targetLanguage} " +
                    "foreground=$statusOwnerRequest phase=$phase"
            )
        }
    }

    private fun publishRetryState(
        request: TranslationRequest,
        modelLanguage: String?,
        retry: RetryState
    ) {
        _uiState.value = UiState(
            phase = retry.phase,
            sourceLanguage = request.sourceLanguage,
            targetLanguage = request.targetLanguage,
            modelLanguage = modelLanguage,
            error = retry.error
        )
    }

    private fun publishState(
        phase: Phase,
        request: TranslationRequest,
        modelLanguage: String? = null,
        error: String? = null
    ) {
        _uiState.value = UiState(
            phase = phase,
            sourceLanguage = request.sourceLanguage,
            targetLanguage = request.targetLanguage,
            modelLanguage = modelLanguage,
            error = error
        )
    }

    private fun updateState(
        phase: Phase,
        sourceLanguage: String? = null,
        targetLanguage: String = TranslationLanguages.DEFAULT_TARGET_LANGUAGE,
        modelLanguage: String? = null,
        error: String? = null
    ) {
        _uiState.value = UiState(
            phase = phase,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            modelLanguage = modelLanguage,
            error = error
        )
    }

    internal suspend fun detectLanguage(text: String): String? {
        val identifier = LanguageIdentification.getClient()
        return try {
            suspendCancellableCoroutine { cont ->
                identifier.identifyLanguage(text)
                    .addOnSuccessListener { language ->
                        if (cont.isActive) cont.resume(language)
                    }
                    .addOnFailureListener { error ->
                        Log.e(TAG, "language identification failed", error)
                        if (cont.isActive) cont.resume(null)
                    }
                    .addOnCanceledListener {
                        cont.cancel()
                    }
            }
        } finally {
            identifier.close()
        }
    }

    private suspend fun isModelDownloaded(
        manager: RemoteModelManager,
        model: TranslateRemoteModel
    ): Boolean = suspendCancellableCoroutine { cont ->
        manager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (cont.isActive) cont.resume(downloaded)
            }
            .addOnFailureListener { error ->
                if (cont.isActive) cont.resumeWithException(error)
            }
            .addOnCanceledListener {
                cont.cancel()
            }
    }

    private fun getOrStartDownloadTask(
        manager: RemoteModelManager,
        model: TranslateRemoteModel,
        modelLanguage: String,
        conditions: DownloadConditions
    ): Task<Void> {
        synchronized(activeModelDownloads) {
            activeModelDownloads[modelLanguage]?.let { existing ->
                if (!existing.isComplete) {
                    Log.d(TAG, "reusing active model download task: $modelLanguage")
                    return existing
                }
                activeModelDownloads.remove(modelLanguage, existing)
            }

            Log.d(TAG, "starting model download task: $modelLanguage")
            val task = manager.download(model, conditions)
            activeModelDownloads[modelLanguage] = task
            task.addOnSuccessListener {
                Log.d(TAG, "model download task reported success: $modelLanguage")
                activeModelDownloads.remove(modelLanguage, task)
            }
            task.addOnFailureListener { error ->
                Log.e(TAG, "model download task reported failure: $modelLanguage", error)
                activeModelDownloads.remove(modelLanguage, task)
            }
            task.addOnCanceledListener {
                Log.w(TAG, "model download task reported cancellation: $modelLanguage")
                activeModelDownloads.remove(modelLanguage, task)
            }
            return task
        }
    }

    private fun startModelDownloadMonitor(
        modelLanguage: String,
        request: TranslationRequest
    ): Deferred<Boolean>? {
        val mlLanguage = TranslateLanguage.fromLanguageTag(modelLanguage) ?: return null
        val manager = RemoteModelManager.getInstance()
        val model = TranslateRemoteModel.Builder(mlLanguage).build()
        modelUiOwners[modelLanguage] = request
        return getOrStartModelMonitor(
            manager = manager,
            model = model,
            modelLanguage = modelLanguage,
            conditions = DownloadConditions.Builder().build()
        )
    }

    private fun getOrStartModelMonitor(
        manager: RemoteModelManager,
        model: TranslateRemoteModel,
        modelLanguage: String,
        conditions: DownloadConditions
    ): Deferred<Boolean> {
        synchronized(activeModelMonitors) {
            activeModelMonitors[modelLanguage]?.let { existing ->
                if (!existing.isCompleted) {
                    Log.d(TAG, "reusing active model download monitor: $modelLanguage")
                    return existing
                }
                activeModelMonitors.remove(modelLanguage, existing)
            }

            val monitor = modelDownloadScope.async {
                try {
                    monitorModelDownload(
                        manager = manager,
                        model = model,
                        modelLanguage = modelLanguage,
                        conditions = conditions
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val reason = e.localizedMessage ?: e.javaClass.simpleName
                    Log.e(TAG, "translation model monitor failed: $modelLanguage", e)
                    markModelRetryRequired(
                        modelLanguage = modelLanguage,
                        phase = Phase.DOWNLOAD_FAILED,
                        reason = reason
                    )
                    false
                }
            }
            activeModelMonitors[modelLanguage] = monitor
            monitor.invokeOnCompletion {
                activeModelMonitors.remove(modelLanguage, monitor)
            }
            return monitor
        }
    }

    private suspend fun waitForModelDownload(
        manager: RemoteModelManager,
        model: TranslateRemoteModel,
        modelLanguage: String,
        conditions: DownloadConditions
    ): Boolean {
        return getOrStartModelMonitor(
            manager = manager,
            model = model,
            modelLanguage = modelLanguage,
            conditions = conditions
        ).await()
    }

    private suspend fun monitorModelDownload(
        manager: RemoteModelManager,
        model: TranslateRemoteModel,
        modelLanguage: String,
        conditions: DownloadConditions
    ): Boolean {
        val task = getOrStartDownloadTask(manager, model, modelLanguage, conditions)
        val startedAt = SystemClock.elapsedRealtime()
        var activeTimeoutElapsedMs = 0L
        var pollCount = 0
        var previousThermalRestriction: Boolean? = null

        while (activeTimeoutElapsedMs < MODEL_DOWNLOAD_TIMEOUT_MS) {
            val loopStartedAt = SystemClock.elapsedRealtime()
            val thermalStatus = currentThermalStatus()
            val thermallyRestricted = isThermallyRestricted(thermalStatus)
            val waitPhase = if (thermallyRestricted) {
                Phase.WAITING_FOR_SYSTEM
            } else {
                Phase.DOWNLOADING_MODEL
            }

            modelUiOwners[modelLanguage]?.let { request ->
                if (statusOwnerRequest == request) {
                    publishState(waitPhase, request, modelLanguage)
                }
            }

            if (previousThermalRestriction != thermallyRestricted) {
                if (thermallyRestricted) {
                    Log.d(
                        TAG,
                        "model download waiting for system thermal conditions: " +
                            "$modelLanguage thermalStatus=${thermalStatus ?: "unknown"}"
                    )
                } else if (previousThermalRestriction == true) {
                    Log.d(TAG, "model download resumed after thermal restriction: $modelLanguage")
                }
                previousThermalRestriction = thermallyRestricted
            }

            val downloaded = withTimeoutOrNull(MODEL_CHECK_TIMEOUT_MS) {
                isModelDownloaded(manager, model)
            }
            if (downloaded == true) {
                Log.d(TAG, "model became available while polling: $modelLanguage")
                activeModelDownloads.remove(modelLanguage, task)
                modelRetryRequired.remove(modelLanguage)
                clearModelWaitUi(modelLanguage)
                return true
            }

            if (task.isComplete && !task.isSuccessful) {
                val reason = task.exception?.localizedMessage
                    ?: task.exception?.javaClass?.simpleName
                    ?: "Model download task failed"
                Log.e(TAG, "translation model download failed: $modelLanguage", task.exception)
                markModelRetryRequired(
                    modelLanguage = modelLanguage,
                    phase = Phase.DOWNLOAD_FAILED,
                    reason = reason
                )
                return false
            }

            pollCount++
            if (pollCount % MODEL_POLL_LOG_INTERVAL == 0) {
                val wallElapsedSec = (SystemClock.elapsedRealtime() - startedAt) / 1000
                val activeTimeoutSec = activeTimeoutElapsedMs / 1000
                Log.d(
                    TAG,
                    "model download still pending: $modelLanguage " +
                        "wallElapsed=${wallElapsedSec}s activeTimeout=${activeTimeoutSec}s " +
                        "thermalStatus=${thermalStatus ?: "unknown"} taskComplete=${task.isComplete}"
                )
            }

            delay(MODEL_POLL_INTERVAL_MS)

            if (!thermallyRestricted) {
                activeTimeoutElapsedMs +=
                    (SystemClock.elapsedRealtime() - loopStartedAt).coerceAtLeast(0L)
            }
        }

        val finalDownloaded = withTimeoutOrNull(MODEL_CHECK_TIMEOUT_MS) {
            isModelDownloaded(manager, model)
        } == true
        if (finalDownloaded) {
            Log.d(TAG, "model became available on final check: $modelLanguage")
            activeModelDownloads.remove(modelLanguage, task)
            modelRetryRequired.remove(modelLanguage)
            clearModelWaitUi(modelLanguage)
            return true
        }

        val reason =
            "Model still unavailable after ${MODEL_DOWNLOAD_TIMEOUT_MS / 60_000} min of active download time"
        Log.e(TAG, "$reason: $modelLanguage")
        markModelRetryRequired(
            modelLanguage = modelLanguage,
            phase = Phase.DOWNLOAD_TIMED_OUT,
            reason = reason
        )
        return false
    }

    private fun clearModelWaitUi(modelLanguage: String) {
        val request = modelUiOwners.remove(modelLanguage)
        val current = _uiState.value
        if (request != null &&
            statusOwnerRequest == request &&
            current.modelLanguage == modelLanguage &&
            isModelWaitPhase(current.phase)
        ) {
            resetUiState()
        }
    }

    private fun isModelWaitPhase(phase: Phase): Boolean {
        return phase == Phase.DOWNLOADING_MODEL || phase == Phase.WAITING_FOR_SYSTEM
    }

    private fun currentThermalStatus(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val context = appContext ?: return null
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.currentThermalStatus
        } catch (e: Exception) {
            Log.w(TAG, "failed to read thermal status", e)
            null
        }
    }

    private fun isThermallyRestricted(status: Int?): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            status != null &&
            status >= PowerManager.THERMAL_STATUS_MODERATE
    }

    private suspend fun translateText(
        translator: com.google.mlkit.nl.translate.Translator,
        text: String
    ): String = suspendCancellableCoroutine { cont ->
        translator.translate(text)
            .addOnSuccessListener { translated ->
                if (cont.isActive) cont.resume(translated)
            }
            .addOnFailureListener { error ->
                if (cont.isActive) cont.resumeWithException(error)
            }
            .addOnCanceledListener {
                cont.cancel()
            }
    }
}
