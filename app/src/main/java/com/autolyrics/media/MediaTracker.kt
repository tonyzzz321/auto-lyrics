package com.autolyrics.media

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.autolyrics.BuildConfig
import com.autolyrics.lyrics.KaraokeTiming
import com.autolyrics.lyrics.LrcLibClient
import com.autolyrics.lyrics.LrcParser
import com.autolyrics.lyrics.LyricsCache
import com.autolyrics.lyrics.LyricsProviderCandidate
import com.autolyrics.lyrics.LyricsProviderResolver
import com.autolyrics.lyrics.LyricsTranslator
import com.autolyrics.lyrics.LlmTranslator
import com.autolyrics.lyrics.ManualSearch
import com.autolyrics.lyrics.ManualHitKind
import com.autolyrics.lyrics.ManualSearchHit
import com.autolyrics.lyrics.MetadataCleaner
import com.autolyrics.lyrics.MusixmatchClient
import com.autolyrics.lyrics.PetitLyricsClient
import com.autolyrics.lyrics.SyncLrcClient
import com.autolyrics.lyrics.TranslationCache
import com.autolyrics.lyrics.TranslationEngineUi
import com.autolyrics.lyrics.TranslationLanguages
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import com.autolyrics.util.AlbumColorExtractor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaTracker private constructor(context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        context.getSharedPreferences("auto_lyrics_prefs", Context.MODE_PRIVATE)
    private val lyricsCache = LyricsCache(context)
    private val translationCache = TranslationCache(context)

    private val _state = MutableStateFlow(LyricsState())
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private var activeController: MediaController? = null
    private var lastPositionMs: Long = 0
    private var lastPositionUpdateTime: Long = 0
    private var playbackSpeed: Float = 1.0f
    private var fetchJob: Job? = null
    private var artJob: Job? = null
    private var translationJob: Job? = null
    private var manualSearchJob: Job? = null

    /**
     * Dialog state for the manual search (edited terms, last results and
     * selection) kept alive while the same track plays so reopening the
     * dialog does not lose the user's work. Cleared on track change.
     */
    data class ManualSearchSession(
        var title: String,
        var artist: String,
        var hits: List<ManualSearchHit> = emptyList(),
        var diagnostics: List<String> = emptyList(),
        var selected: ManualSearchHit? = null,
        var searching: Boolean = false,
        var includeUnsynced: Boolean = false
    )

    var manualSearchSession: ManualSearchSession? = null
    private var pendingTrack: TrackInfo? = null
    private var pendingArt: Bitmap? = null
    private var endRewindHeldMs = 0L
    private var lyricsOffsetMs: Long = 0L

    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                TRANSLATION_ENABLED_KEY -> {
                    handler.post { handleTranslationPreferenceChanged() }
                }
                TranslationLanguages.TARGET_LANGUAGE_PREF_KEY,
                LlmTranslator.PREF_ENGINE_KEY,
                LlmTranslator.PREF_LLM_BASE_URL,
                LlmTranslator.PREF_LLM_MODEL,
                LlmTranslator.PREF_LLM_API_KEY -> {
                    handler.post { handleTranslationTargetChanged() }
                }
                AA_KARAOKE_ENABLED_KEY -> {
                    handler.post { handleKaraokePreferenceChanged() }
                }
            }
        }

    init {
        lyricsOffsetMs = prefs.getLong("lyrics_offset_ms", 0L)
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        startPreferenceReconciliation()
    }

    /**
     * Polls the settings keys the instant-apply handlers depend on and invokes
     * the same handlers when a change was missed. Listeners stay registered as
     * the fast path; this loop guarantees toggles land even when a listener
     * callback is lost (seen in the field: switches flipping with no callback).
     */
    private fun startPreferenceReconciliation() {
        scope.launch {
            var translationEnabled = prefs.getBoolean(TRANSLATION_ENABLED_KEY, true)
            var karaoke = prefs.getBoolean(AA_KARAOKE_ENABLED_KEY, true)
            var target = prefs.getString(TranslationLanguages.TARGET_LANGUAGE_PREF_KEY, null)
            var engine = prefs.getString(LlmTranslator.PREF_ENGINE_KEY, null)
            var model = prefs.getString(LlmTranslator.PREF_LLM_MODEL, null)
            while (isActive) {
                delay(PREFERENCE_RECONCILE_MS)
                val nowTranslation = prefs.getBoolean(TRANSLATION_ENABLED_KEY, true)
                val nowKaraoke = prefs.getBoolean(AA_KARAOKE_ENABLED_KEY, true)
                val nowTarget = prefs.getString(TranslationLanguages.TARGET_LANGUAGE_PREF_KEY, null)
                val nowEngine = prefs.getString(LlmTranslator.PREF_ENGINE_KEY, null)
                val nowModel = prefs.getString(LlmTranslator.PREF_LLM_MODEL, null)

                if (nowTarget != target || nowEngine != engine || nowModel != model) {
                    target = nowTarget
                    engine = nowEngine
                    model = nowModel
                    translationEnabled = nowTranslation
                    karaoke = nowKaraoke
                    handleTranslationTargetChanged()
                } else if (nowTranslation != translationEnabled) {
                    translationEnabled = nowTranslation
                    handleTranslationPreferenceChanged()
                } else if (nowKaraoke != karaoke) {
                    karaoke = nowKaraoke
                    handleKaraokePreferenceChanged()
                }
            }
        }
    }

    private val positionChecker = object : Runnable {
        override fun run() {
            updateCurrentPosition()
            if (_state.value.isPlaying) {
                handler.postDelayed(this, 150)
            }
        }
    }

    private val trackChangeRunnable = Runnable {
        val track = pendingTrack ?: return@Runnable
        val art = pendingArt
        pendingTrack = null
        pendingArt = null
        val current = _state.value.track
        if (track == current) return@Runnable

        translationJob?.cancel()
        manualSearchJob?.cancel()
        manualSearchSession = null
        // New track: the held end-of-song position is stale baseline now.
        endRewindHeldMs = 0L
        lastPositionMs = 0L
        _state.value = _state.value.copy(
            track = track,
            lines = emptyList(),
            currentIndex = -1,
            currentWordIndex = -1,
            status = LyricsStatus.LOADING,
            source = "",
            albumArt = art,
            albumColors = null,
            translatedLines = null,
            detectedLanguage = null
        )
        fetchLyrics(track)
        extractAlbumColors(art)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadataChanged(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackStateChanged(state)
        }

        override fun onSessionDestroyed() {
            onMediaSessionChanged(null)
        }
    }

    fun adjustOffset(deltaMs: Long) {
        lyricsOffsetMs += deltaMs
        prefs.edit().putLong("lyrics_offset_ms", lyricsOffsetMs).apply()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun resetOffset() {
        lyricsOffsetMs = 0L
        prefs.edit().putLong("lyrics_offset_ms", 0L).apply()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun resumePlayback() {
        activeController?.transportControls?.play()
    }

    fun setOffset(ms: Long) {
        lyricsOffsetMs = ms
        prefs.edit().putLong("lyrics_offset_ms", ms).apply()
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun getCurrentPositionMs(): Long {
        val basePos = if (!_state.value.isPlaying) {
            lastPositionMs
        } else {
            val elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTime
            lastPositionMs + (elapsed * playbackSpeed).toLong()
        }
        return basePos + lyricsOffsetMs
    }

    private fun updateCurrentPosition() {
        val currentState = _state.value
        val lines = currentState.lines
        if (lines.isEmpty() || currentState.status != LyricsStatus.FOUND) return

        val posMs = getCurrentPositionMs()

        var newLineIndex = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) {
                newLineIndex = i
            } else {
                break
            }
        }

        val newWordIndex = if (newLineIndex >= 0) {
            KaraokeTiming.activeWordIndex(lines[newLineIndex].words, posMs)
        } else {
            -1
        }

        if (newLineIndex != currentState.currentIndex || newWordIndex != currentState.currentWordIndex) {
            _state.value = currentState.copy(
                currentIndex = newLineIndex,
                currentWordIndex = newWordIndex
            )
        }
    }

    fun onMediaSessionChanged(controller: MediaController?) {
        val current = activeController
        if (controller == null && current == null) return
        if (controller != null && current != null &&
            controller.sessionToken == current.sessionToken
        ) {
            // Notification/session-list callbacks are frequent. Keep our callback
            // registered and refresh the snapshot in case metadata changed before
            // the framework delivered its callback.
            handleMetadataChanged(controller.metadata)
            handlePlaybackStateChanged(controller.playbackState)
            return
        }
        // A delayed metadata change belongs to the previous media session and
        // must never be committed after the controller has been replaced.
        handler.removeCallbacks(trackChangeRunnable)
        pendingTrack = null
        pendingArt = null
        activeController?.unregisterCallback(mediaCallback)
        activeController = controller

        if (controller == null) {
            handler.removeCallbacks(positionChecker)
            artJob?.cancel()
            _state.value = LyricsState(offsetMs = lyricsOffsetMs)
            return
        }

        controller.registerCallback(mediaCallback)
        handleMetadataChanged(controller.metadata)
        handlePlaybackStateChanged(controller.playbackState)
    }

    private fun handleMetadataChanged(metadata: MediaMetadata?) {
        if (metadata == null) return

        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return
        val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val rawAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val title = MetadataCleaner.cleanTitle(rawTitle)
        val artist = MetadataCleaner.cleanArtist(rawArtist)
        val album = MetadataCleaner.cleanAlbum(rawAlbum)

        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val sourcePackage = activeController?.packageName.orEmpty()
        val isSpotify = sourcePackage == SpotifyTrackIdentity.PACKAGE_NAME
        val description = metadata.description
        val sourceMediaId = if (isSpotify) {
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                ?: description.mediaId
                ?: ""
        } else {
            ""
        }
        val sourceMediaUri = if (isSpotify) {
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
                ?: description.mediaUri?.toString()
                ?: ""
        } else {
            ""
        }

        val newTrack = TrackInfo(
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            playbackSourcePackage = if (isSpotify) sourcePackage else "",
            playbackSourceMediaId = sourceMediaId,
            playbackSourceMediaUri = sourceMediaUri
        )
        val current = _state.value.track

        if (current != null && newTrack == current) {
            // Metadata may briefly report an intermediate item while a player is
            // transitioning. If it settles back to the current item, discard the
            // delayed transition rather than switching to stale pending metadata.
            handler.removeCallbacks(trackChangeRunnable)
            pendingTrack = null
            pendingArt = null
            if (art != null && _state.value.albumArt == null) {
                _state.value = _state.value.copy(albumArt = art)
                extractAlbumColors(art)
            }
            return
        }

        if (pendingTrack == newTrack) {
            // Repeated active-session/notification callbacks often carry the
            // same incoming metadata. Preserve the original debounce deadline.
            if (art != null) pendingArt = art
            return
        }

        pendingTrack = newTrack
        pendingArt = art
        handler.removeCallbacks(trackChangeRunnable)
        handler.postDelayed(trackChangeRunnable, 600)
    }

    private fun extractAlbumColors(bitmap: Bitmap?) {
        artJob?.cancel()
        if (bitmap == null) return
        artJob = scope.launch(Dispatchers.Default) {
            val colors = AlbumColorExtractor.extract(bitmap)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(albumColors = colors)
            }
        }
    }

    private fun handlePlaybackStateChanged(pbState: PlaybackState?) {
        if (pbState == null) return

        val isPlaying = pbState.state == PlaybackState.STATE_PLAYING
        // End-of-song rewind artifact: players briefly report position 0 in
        // a non-playing state while the next track's callbacks are still in
        // flight. Accepting it rewinds the AA boards to the intro mid-
        // outro. Hold the song-end position instead: real seeks land in a
        // playing state (accepted), and a genuine track change resets the
        // baseline below.
        val endRewind = !isPlaying && pbState.position < 2_000L && lastPositionMs > 10_000L
        if (endRewind) {
            if (endRewindHeldMs == 0L) endRewindHeldMs = SystemClock.elapsedRealtime()
        } else {
            endRewindHeldMs = 0L
            lastPositionMs = pbState.position
            lastPositionUpdateTime = pbState.lastPositionUpdateTime
            if (lastPositionUpdateTime == 0L) {
                lastPositionUpdateTime = SystemClock.elapsedRealtime()
            }
        }
        playbackSpeed = if (pbState.playbackSpeed > 0) pbState.playbackSpeed else 1.0f

        _state.value = _state.value.copy(isPlaying = isPlaying)

        handler.removeCallbacks(positionChecker)
        if (isPlaying) {
            handler.post(positionChecker)
        }
    }

    private fun fetchLyrics(track: TrackInfo) {
        fetchJob?.cancel()
        fetchJob = scope.launch(Dispatchers.IO) {
            try {
                val preferWordSync = prefs.getBoolean(AA_KARAOKE_ENABLED_KEY, true)
                val cacheVariant = if (preferWordSync) {
                    LyricsCache.Variant.KARAOKE
                } else {
                    LyricsCache.Variant.STANDARD
                }
                val cached = lyricsCache.get(track, cacheVariant)
                if (cached != null) {
                    val (lines, status, source) = cached
                    withContext(Dispatchers.Main) {
                        if (_state.value.track != track ||
                            prefs.getBoolean(AA_KARAOKE_ENABLED_KEY, true) != preferWordSync
                        ) {
                            return@withContext
                        }
                        _state.value = _state.value.copy(
                            lines = lines,
                            currentIndex = -1,
                            currentWordIndex = -1,
                            status = status,
                            source = "$source (cached)"
                        )
                        if (status == LyricsStatus.FOUND) {
                            updateCurrentPosition()
                        }
                        translateIfNeeded(lines, track)
                    }

                    // Cached lyrics are permanent; Manual Search is the
                    // user-facing refresh path, so never silently re-fetch.
                    return@launch
                }

                val decision = fetchBestLyrics(track, preferWordSync)
                val result = decision.candidate

                withContext(Dispatchers.Main) {
                    if (_state.value.track != track ||
                        prefs.getBoolean(AA_KARAOKE_ENABLED_KEY, true) != preferWordSync
                    ) {
                        return@withContext
                    }

                    if (result != null) {
                        lyricsCache.put(
                            track = track,
                            lines = result.lines,
                            status = result.status,
                            source = result.source,
                            variant = cacheVariant
                        )
                        _state.value = _state.value.copy(
                            lines = result.lines,
                            currentIndex = -1,
                            currentWordIndex = -1,
                            status = result.status,
                            source = result.source,
                            translatedLines = null,
                            detectedLanguage = null
                        )
                        if (result.status == LyricsStatus.FOUND) {
                            updateCurrentPosition()
                        }
                        translateIfNeeded(result.lines, track)
                    } else if (cached == null) {
                        _state.value = _state.value.copy(
                            status = LyricsStatus.NOT_FOUND,
                            source = ""
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (_state.value.track == track && _state.value.status == LyricsStatus.LOADING) {
                        _state.value = _state.value.copy(
                            status = LyricsStatus.ERROR,
                            source = ""
                        )
                    }
                }
            }
        }
    }

    private data class ProviderAttempt(
        val provider: String,
        val candidate: LyricsProviderCandidate?,
        val timedOut: Boolean,
        val elapsedMs: Long
    )

    private data class FetchDecision(
        val candidate: LyricsProviderCandidate?
    )

    private suspend fun fetchBestLyrics(
        track: TrackInfo,
        preferWordSync: Boolean
    ): FetchDecision = coroutineScope {
        // Five seconds is a hard per-provider budget. Healthy responses observed in
        // practice are normally sub-second; waiting 10-15 seconds for one source
        // makes a track change feel broken. runInterruptible allows timeout/cancel
        // to interrupt the synchronous OkHttp work rather than merely abandoning
        // the Deferred while the blocking call keeps this fetch waiting.
        val lrcLibDeferred = async {
            fetchProviderWithBudget("LRCLIB") { fetchFromLrcLib(track) }
        }
        val petitLyricsDeferred = if (PetitLyricsClient.isConfigured) {
            async {
                fetchProviderWithBudget("PetitLyrics") { fetchFromPetitLyrics(track) }
            }
        } else {
            null
        }
        val musixmatchDeferred = async {
            fetchProviderWithBudget("Musixmatch") { fetchFromMusixmatch(track) }
        }
        val syncLrcDeferred = async {
            fetchProviderWithBudget("SyncLRC") { fetchFromSyncLrc(track) }
        }

        val lrcAttempt = lrcLibDeferred.await()
        val petitAttempt = petitLyricsDeferred?.await()
        val musixmatchAttempt = musixmatchDeferred.await()
        val syncLrcAttempt = syncLrcDeferred?.await()
        val attempts = listOfNotNull(lrcAttempt, petitAttempt, musixmatchAttempt, syncLrcAttempt)
        val candidates = attempts.mapNotNull { it.candidate }

        val scored = LyricsProviderResolver.scoreCandidates(track, candidates)
        if (BuildConfig.DEBUG) {
            attempts.forEach { attempt ->
                Log.d(
                    PROVIDER_RESOLVER_TAG,
                    "${attempt.provider} fetch elapsed=${attempt.elapsedMs}ms " +
                        "timedOut=${attempt.timedOut} candidate=${attempt.candidate != null}"
                )
            }
            scored.forEach { score ->
                Log.d(
                    PROVIDER_RESOLVER_TAG,
                    "%s metadata=%.3f quality=%.3f confidence=%.3f final=%.3f kind=%s".format(
                        score.candidate.provider,
                        score.metadataScore,
                        score.qualityScore,
                        score.sourceConfidence,
                        score.finalScore,
                        score.candidate.syncKind
                    )
                )
            }
        }

        if (BuildConfig.DEBUG) {
            candidates.forEach { c ->
                if (!LyricsProviderResolver.hasDisplayableContent(c)) {
                    Log.d(PROVIDER_RESOLVER_TAG, "${c.provider} rejected: no displayable content")
                }
            }
        }

        val selected = LyricsProviderResolver.selectBest(
            track = track,
            candidates = candidates,
            preferWordSync = preferWordSync
        )
        if (BuildConfig.DEBUG) {
            Log.d(
                PROVIDER_RESOLVER_TAG,
                selected?.let {
                    "selected=${it.candidate.provider} final=${"%.3f".format(it.finalScore)} " +
                        "kind=${it.candidate.syncKind} mode=${if (preferWordSync) "karaoke" else "standard"}"
                } ?: "selected=none mode=${if (preferWordSync) "karaoke" else "standard"}"
            )
        }

        FetchDecision(candidate = selected?.candidate)
    }

    private suspend fun fetchProviderWithBudget(
        provider: String,
        block: () -> LyricsProviderCandidate?
    ): ProviderAttempt {
        val started = SystemClock.elapsedRealtime()
        return try {
            val candidate = withTimeout(PROVIDER_BUDGET_MS) {
                runInterruptible(Dispatchers.IO) { block() }
            }
            ProviderAttempt(
                provider = provider,
                candidate = candidate,
                timedOut = false,
                elapsedMs = SystemClock.elapsedRealtime() - started
            )
        } catch (e: TimeoutCancellationException) {
            ProviderAttempt(
                provider = provider,
                candidate = null,
                timedOut = true,
                elapsedMs = SystemClock.elapsedRealtime() - started
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ProviderAttempt(
                provider = provider,
                candidate = null,
                timedOut = false,
                elapsedMs = SystemClock.elapsedRealtime() - started
            )
        }
    }

    private fun fetchFromPetitLyrics(track: TrackInfo): LyricsProviderCandidate? {
        if (!PetitLyricsClient.isConfigured) return null

        val result = try {
            PetitLyricsClient.getSyncedLyrics(
                album = track.album,
                artist = track.artist,
                title = track.title
            )
        } catch (_: Exception) {
            null
        } ?: return null

        val hasRealText = result.lines.any { it.text != "♪" && it.text.isNotBlank() }
        if (!hasRealText) return null

        val syncKind = when {
            result.lyricsType == 3 && result.lines.any { it.words.isNotEmpty() } ->
                LyricsProviderCandidate.SyncKind.WORD_SYNC
            else -> LyricsProviderCandidate.SyncKind.LINE_SYNC
        }

        return LyricsProviderCandidate(
            provider = "PetitLyrics",
            title = result.matchedTitle.ifBlank { track.title },
            artist = result.matchedArtist,
            album = result.matchedAlbum,
            durationSec = result.matchedDurationSec,
            lines = result.lines,
            status = LyricsStatus.FOUND,
            source = if (syncKind == LyricsProviderCandidate.SyncKind.WORD_SYNC)
                "PetitLyrics · Karaoke" else "PetitLyrics · Synced",
            syncKind = syncKind,
            artistQueryCorroborated = result.artistQueryCorroborated
        )
    }

    private fun fetchFromMusixmatch(track: TrackInfo): LyricsProviderCandidate? {
        val result = try {
            MusixmatchClient.getLyrics(track, prefs)
        } catch (_: Exception) {
            null
        } ?: return null

        val hasRealText = result.lines.any { it.text != "♪" && it.text.isNotBlank() }
        if (!hasRealText) return null

        val syncKind = when {
            result.isRichSync && result.lines.any { it.words.isNotEmpty() } ->
                LyricsProviderCandidate.SyncKind.WORD_SYNC
            else -> LyricsProviderCandidate.SyncKind.LINE_SYNC
        }

        return LyricsProviderCandidate(
            provider = "Musixmatch",
            title = result.matchedTitle.ifBlank { track.title },
            artist = result.matchedArtist,
            album = result.matchedAlbum,
            durationSec = result.matchedDurationSec,
            lines = result.lines,
            status = LyricsStatus.FOUND,
            source = if (result.isRichSync) "Musixmatch · Karaoke" else "Musixmatch · Synced",
            syncKind = syncKind,
            artistQueryCorroborated = result.artistQueryCorroborated
        )
    }

    private fun fetchFromSyncLrc(track: TrackInfo): LyricsProviderCandidate? {
        val result = try {
            SyncLrcClient.getKaraokeLyrics(track)
        } catch (_: Exception) {
            null
        } ?: return null

        val hasRealText = result.lines.any { it.text != "♪" && it.text.isNotBlank() }
        if (!hasRealText) return null

        val (status, source) = when (result.syncKind) {
            LyricsProviderCandidate.SyncKind.WORD_SYNC ->
                LyricsStatus.FOUND to "SyncLRC · Karaoke"
            LyricsProviderCandidate.SyncKind.LINE_SYNC ->
                LyricsStatus.FOUND to "SyncLRC · Synced"
            LyricsProviderCandidate.SyncKind.PLAIN ->
                LyricsStatus.PLAIN_ONLY to "SyncLRC · Not synced"
        }

        return LyricsProviderCandidate(
            provider = "SyncLRC",
            title = result.matchedTitle.ifBlank { track.title },
            artist = result.matchedArtist,
            album = result.matchedAlbum,
            durationSec = result.matchedDurationSec,
            lines = result.lines,
            status = status,
            source = source,
            syncKind = result.syncKind,
            artistQueryCorroborated = result.artistQueryCorroborated
        )
    }

    private fun fetchFromLrcLib(track: TrackInfo): LyricsProviderCandidate? {
        val durationSec = if (track.durationMs > 0) (track.durationMs / 1000).toInt() else 0

        val result = try {
            LrcLibClient.getLyrics(
                trackName = track.title,
                artistName = track.artist,
                albumName = track.album,
                durationSec = durationSec
            )
        } catch (_: Exception) {
            null
        } ?: return null

        if (result.syncedLyrics != null) {
            // parseKaraoke also handles plain synced lines; parse would
            // leave Enhanced-LRC word tags as literal display text.
            val lines = LrcParser.parseKaraoke(result.syncedLyrics)
            val hasRealText = lines.any { it.text != "♪" && it.text.isNotBlank() }
            if (hasRealText) {
                // Enhanced uploads carry word tags; the variant and the
                // ranking kind must describe the payload, not the endpoint.
                val karaoke = lines.any { it.words.isNotEmpty() }
                return LyricsProviderCandidate(
                    provider = "LRCLIB",
                    title = result.trackName.orEmpty().ifBlank { track.title },
                    artist = result.artistName.orEmpty(),
                    album = result.albumName.orEmpty(),
                    durationSec = result.duration,
                    lines = lines,
                    status = LyricsStatus.FOUND,
                    source = if (karaoke) "LRCLIB · Karaoke" else "LRCLIB · Synced",
                    syncKind = if (karaoke) LyricsProviderCandidate.SyncKind.WORD_SYNC
                        else LyricsProviderCandidate.SyncKind.LINE_SYNC
                )
            }
        }

        if (result.plainLyrics != null) {
            val lines = result.plainLyrics.lines()
                .filter { it.isNotBlank() }
                .map { text -> LyricLine(0L, LrcParser.cleanSpacing(text)) }
            if (lines.isNotEmpty()) {
                return LyricsProviderCandidate(
                    provider = "LRCLIB",
                    title = result.trackName.orEmpty().ifBlank { track.title },
                    artist = result.artistName.orEmpty(),
                    album = result.albumName.orEmpty(),
                    durationSec = result.duration,
                    lines = lines,
                    status = LyricsStatus.PLAIN_ONLY,
                    source = "LRCLIB · Not synced",
                    syncKind = LyricsProviderCandidate.SyncKind.PLAIN
                )
            }
        }

        return null
    }

    /**
     * User-initiated lookup from the manual search dialog. Results are ranked
     * against the current track duration; the dialog applies a hit via
     * [applyManualLyrics], which is a no-op once the track changed.
     */
    fun launchManualSearch(
        title: String,
        artist: String,
        onResult: (hits: List<ManualSearchHit>, diagnostics: List<String>) -> Unit
    ) {
        manualSearchJob?.cancel()
        manualSearchJob = scope.launch(Dispatchers.IO) {
            // Fault isolation: providers run in parallel, each under its own
            // timeout, so a stalled or failing provider can neither eat the
            // others' budget nor void answers already fetched.
            val lrcLibDeferred = async(Dispatchers.IO) {
                manualLeg("lrclib") { LrcLibClient.manualSearch(title, artist) }
            }
            val syncLrcDeferred = async(Dispatchers.IO) {
                manualLeg("synclrc") {
                    val legDiagnostics = mutableListOf<String>()
                    SyncLrcClient.manualSearch(title, artist, legDiagnostics) to legDiagnostics
                }
            }
            val musixmatchDeferred = async(Dispatchers.IO) {
                manualLeg("musixmatch") {
                    val legDiagnostics = mutableListOf<String>()
                    MusixmatchClient.manualSearch(title, artist, prefs, legDiagnostics) to
                        legDiagnostics
                }
            }
            // Joins the manual race only when the provider identity is
            // configured; silent (not a failure) when it is not.
            val petitLyricsDeferred = async(Dispatchers.IO) {
                manualLeg("petitlyrics") {
                    val legDiagnostics = mutableListOf<String>()
                    PetitLyricsClient.manualSearch(title, artist, legDiagnostics) to
                        legDiagnostics
                }
            }
            val (lrcLibOutcome, lrcLibLegFailure) = lrcLibDeferred.await()
            val (syncLrcResult, syncLrcLegFailure) = syncLrcDeferred.await()
            val (musixmatchResult, musixmatchLegFailure) = musixmatchDeferred.await()
            val (petitLyricsResult, petitLyricsLegFailure) = petitLyricsDeferred.await()
            val lrcLibResponses = lrcLibOutcome?.responses.orEmpty()
            val syncLrcResponse = syncLrcResult?.first.orEmpty()
            val musixmatchCandidates = musixmatchResult?.first.orEmpty()
            val diagnostics = mutableListOf<String>()
            lrcLibOutcome?.let { diagnostics += it.diagnostics }
            syncLrcResult?.let { diagnostics += it.second }
            musixmatchResult?.let { diagnostics += it.second }
            petitLyricsResult?.let { diagnostics += it.second }
            diagnostics += lrcLibLegFailure
            diagnostics += syncLrcLegFailure
            diagnostics += musixmatchLegFailure
            diagnostics += petitLyricsLegFailure
            val hits = (
                ManualSearch.toHits(lrcLibResponses) +
                    petitLyricsResult?.first.orEmpty() +
                    syncLrcResponse.flatMap { response -> listOfNotNull(ManualSearch.toSyncLrcHit(response)) } +
                    musixmatchCandidates.map { candidate ->
                        ManualSearchHit(
                            provider = "Musixmatch",
                            title = candidate.title,
                            artist = candidate.artist,
                            durationSec = candidate.durationSec,
                            kind = if (candidate.hasRichSync == true) {
                                ManualHitKind.KARAOKE
                            } else {
                                ManualHitKind.SYNCED
                            },
                            lines = emptyList(),
                            status = LyricsStatus.FOUND,
                            source = "Musixmatch · Line",
                            fetch = { MusixmatchClient.fetchManualLyrics(candidate, prefs) }
                        )
                    }
                )
            val ourDurationSec = (_state.value.track?.durationMs ?: 0L) / 1000
            val relevant = hits.filter { ManualSearch.passesRelevance(it, title) }
            val dropped = hits.size - relevant.size
            if (dropped > 0) {
                diagnostics += "title filter: -$dropped"
            }
            val ranked = ManualSearch.rank(
                relevant,
                ourDurationSec.toInt(),
                _state.value.track?.artist.orEmpty()
            )
            withContext(Dispatchers.Main) { onResult(ranked, diagnostics.toList()) }
        }
    }

    /**
     * Applies a manual search hit as the current lyrics and caches it. Hits
     * carrying a [ManualSearchHit.fetch] resolve their lyrics off-main first;
     * [onResult] reports success and an optional user-facing failure reason.
     */
    fun applyManualLyrics(
        hit: ManualSearchHit,
        expectedTrack: TrackInfo,
        onResult: (applied: Boolean, message: String?) -> Unit
    ) {
        val track = _state.value.track ?: run {
            onResult(false, "No playing track")
            return
        }
        if (track != expectedTrack) {
            onResult(false, "Track changed · search again")
            return
        }

        val fetch = hit.fetch
        if (fetch == null) {
            onResult(applyHit(hit, track), null)
            return
        }

        manualSearchJob?.cancel()
        manualSearchJob = scope.launch(Dispatchers.IO) {
            val fetched = runInterruptible {
                try {
                    fetch()
                } catch (e: Exception) {
                    Log.w("ManualSearch", "manual fetch failed", e)
                    null
                }
            }
            withContext(Dispatchers.Main) {
                if (fetched == null ||
                    !LyricsProviderResolver.hasDisplayableLines(fetched.lines)) {
                    onResult(false, "Provider returned no usable lyrics")
                } else {
                    val applied = applyHit(
                        hit.copy(
                            lines = fetched.lines,
                            status = fetched.status,
                            source = fetched.source,
                            fetch = null
                        ),
                        track
                    )
                    onResult(applied, if (applied) null else "Track changed · search again")
                }
            }
        }
    }

    private fun applyHit(hit: ManualSearchHit, track: TrackInfo): Boolean {
        val variant = if (prefs.getBoolean(AA_KARAOKE_ENABLED_KEY, true)) {
            LyricsCache.Variant.KARAOKE
        } else {
            LyricsCache.Variant.STANDARD
        }
        lyricsCache.put(
            track = track,
            lines = hit.lines,
            status = hit.status,
            source = hit.source,
            variant = variant
        )
        // A manual pick is an explicit user refresh: drop any cached
        // translation so retranslation runs against the new lyrics even if
        // the picked text happens to match what was cached before.
        translationCache.delete(track, hit.lines, selectedTargetLanguage())
        _state.value = _state.value.copy(
            lines = hit.lines,
            currentIndex = -1,
            currentWordIndex = -1,
            status = hit.status,
            source = hit.source,
            translatedLines = null,
            detectedLanguage = null
        )
        if (hit.status == LyricsStatus.FOUND) {
            updateCurrentPosition()
        }
        translateIfNeeded(hit.lines, track)
        return true
    }

    /**
     * Drops the cached translation for the playing track's current lyrics and
     * target language, then re-runs translation. Manual Search apply remains
     * the only way to redo lyrics; this is the translation-side shortcut.
     */
    fun resetTranslationForCurrentTrack(): Boolean {
        val state = _state.value
        val track = state.track ?: return false
        val lines = state.lines
        if (state.status != LyricsStatus.FOUND || lines.isEmpty()) return false
        translationJob?.cancel()
        translationCache.delete(track, lines, selectedTargetLanguage())
        _state.value = state.copy(translatedLines = null, detectedLanguage = null)
        resetTranslationUiState()
        translateIfNeeded(lines, track)
        return true
    }

    private fun handleKaraokePreferenceChanged() {
        val track = _state.value.track ?: return
        translationJob?.cancel()
        resetTranslationUiState()
        _state.value = clearTranslationForLyricsVariantSwitch(_state.value)
        fetchLyrics(track)
    }

    private fun handleTranslationPreferenceChanged() {
        translationJob?.cancel()

        if (!prefs.getBoolean(TRANSLATION_ENABLED_KEY, true)) {
            resetTranslationUiState()
            _state.value = _state.value.copy(
                translatedLines = null,
                detectedLanguage = null
            )
            return
        }

        val currentState = _state.value
        val track = currentState.track ?: return
        if (currentState.lines.isEmpty()) return
        if (currentState.status != LyricsStatus.FOUND &&
            currentState.status != LyricsStatus.PLAIN_ONLY
        ) {
            return
        }

        translateIfNeeded(currentState.lines, track)
    }

    private fun handleTranslationTargetChanged() {
        translationJob?.cancel()
        resetTranslationUiState()
        _state.value = _state.value.copy(
            translatedLines = null,
            detectedLanguage = null
        )

        if (!prefs.getBoolean(TRANSLATION_ENABLED_KEY, true)) return

        val currentState = _state.value
        val track = currentState.track ?: return
        if (currentState.lines.isEmpty()) return
        if (currentState.status != LyricsStatus.FOUND &&
            currentState.status != LyricsStatus.PLAIN_ONLY
        ) {
            return
        }

        translateIfNeeded(currentState.lines, track)
    }

    private fun selectedTargetLanguage(): String {
        return TranslationLanguages.normalizeTargetLanguage(
            prefs.getString(
                TranslationLanguages.TARGET_LANGUAGE_PREF_KEY,
                TranslationLanguages.DEFAULT_TARGET_LANGUAGE
            )
        )
    }

    private fun translateIfNeeded(lines: List<LyricLine>, track: TrackInfo) {
        if (!prefs.getBoolean(TRANSLATION_ENABLED_KEY, true)) return
        val targetLanguage = selectedTargetLanguage()
        val useLlm = LlmTranslator.isLlmEngine(prefs)
        val llmConfig = if (useLlm) LlmTranslator.loadConfig(prefs) else null

        translationJob?.cancel()
        translationJob = scope.launch(Dispatchers.IO) {
            try {
                if (useLlm && llmConfig == null) {
                    LlmTranslator.publishNotConfigured(targetLanguage)
                    return@launch
                }

                val engineUi: TranslationEngineUi = if (useLlm) LlmTranslator else LyricsTranslator
                val engine = if (useLlm) LlmTranslator.ENGINE_LLM else LlmTranslator.ENGINE_ON_DEVICE
                val model = if (useLlm) llmConfig!!.model else LyricsTranslator.MODEL_ID

                val cached = translationCache.get(track, lines, targetLanguage, engine, model)
                if (cached != null) {
                    val applied = withContext(Dispatchers.Main) {
                        if (!translationStillValid(track, targetLanguage)) {
                            false
                        } else {
                            _state.value = _state.value.copy(
                                translatedLines = cached.lines,
                                detectedLanguage = cached.detectedLanguage
                            )
                            true
                        }
                    }
                    if (applied) {
                        engineUi.publishReady(cached.detectedLanguage, targetLanguage)
                    }
                    return@launch
                }

                val result = if (useLlm) {
                    LlmTranslator.translateLines(lines, targetLanguage, llmConfig!!, track)
                } else {
                    LyricsTranslator.translateLines(lines, targetLanguage)
                } ?: return@launch

                if (result.targetLanguage != targetLanguage) return@launch
                translationCache.put(track, lines, result, engine, model)

                withContext(Dispatchers.Main) {
                    if (!translationStillValid(track, targetLanguage)) return@withContext
                    _state.value = _state.value.copy(
                        translatedLines = result.translatedLines,
                        detectedLanguage = result.detectedLanguage
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
    }

    private fun translationStillValid(track: TrackInfo, targetLanguage: String): Boolean {
        return _state.value.track == track &&
            prefs.getBoolean(TRANSLATION_ENABLED_KEY, true) &&
            selectedTargetLanguage() == targetLanguage
    }

    private fun resetTranslationUiState() {
        LyricsTranslator.resetUiState()
        LlmTranslator.resetUiState()
    }

    /**
     * One manual-search provider leg: the value, or null plus a dialog
     * diagnostic when the leg times out or throws.
     */
    private suspend fun <T> manualLeg(
        label: String,
        block: () -> T
    ): Pair<T?, List<String>> = try {
        withTimeout(MANUAL_SEARCH_LEG_TIMEOUT_MS) { runInterruptible { block() } } to emptyList()
    } catch (e: TimeoutCancellationException) {
        null to listOf("$label: timed out after ${MANUAL_SEARCH_LEG_TIMEOUT_MS / 1000}s")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("ManualSearch", "$label leg failed", e)
        null to listOf("$label: ${e.javaClass.simpleName}")
    }

    companion object {
        private const val PROVIDER_BUDGET_MS = 5_000L
        /** Per-provider wall-clock cap for one manual search leg. */
        private const val MANUAL_SEARCH_LEG_TIMEOUT_MS = 10_000L
        private const val PROVIDER_RESOLVER_TAG = "ProviderResolver"
        private const val TRANSLATION_ENABLED_KEY = "translation_enabled"
        private const val AA_KARAOKE_ENABLED_KEY = "aa_karaoke_enabled"
        private const val PREFERENCE_RECONCILE_MS = 500L

        @Volatile
        private var instance: MediaTracker? = null

        fun init(context: Context) {
            getInstance(context)
        }

        fun getInstance(context: Context): MediaTracker {
            return instance ?: synchronized(this) {
                instance ?: MediaTracker(context.applicationContext).also { instance = it }
            }
        }
    }
}
