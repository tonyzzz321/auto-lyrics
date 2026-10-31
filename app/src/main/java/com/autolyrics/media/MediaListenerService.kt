package com.autolyrics.media

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

internal fun sessionAllowed(pkg: String, ownPackage: String, monitored: Set<String>): Boolean =
    pkg != ownPackage && (monitored.isEmpty() || pkg in monitored)

class MediaListenerService : NotificationListenerService() {

    private lateinit var sessionManager: MediaSessionManager
    private var selectedSessionToken: MediaSession.Token? = null
    private val reconciliationHandler = Handler(Looper.getMainLooper())
    private val reconciliationRunnable = object : Runnable {
        override fun run() {
            if (!LyricsDemandController.isActive) return
            updateSessions()
            reconciliationHandler.postDelayed(this, SESSION_RECONCILIATION_INTERVAL_MS)
        }
    }

    companion object {
        private const val SESSION_RECONCILIATION_INTERVAL_MS = 2_000L
        private const val PREFS_NAME = "auto_lyrics_prefs"
        const val KEY_MONITORED = "monitored_packages"
        const val KEY_SEEN = "seen_media_packages"
    }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    /** Empty means no whitelist: every app's session is followed. */
    private var monitored: Set<String> = emptySet()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_MONITORED) {
            monitored = readMonitored()
            // Apply a whitelist change mid-playback without waiting for the
            // reconciliation tick.
            updateSessions()
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            pickBestSession(controllers)
        }

    private val lyricsDemandListener: (Boolean) -> Unit = { active ->
        if (active) {
            // Demand can become active without a media-session ordering change
            // (opening the phone UI or connecting Android Auto), so immediately
            // forward the currently selected/playing session.
            startSessionReconciliation()
            updateSessions()
        } else {
            reconciliationHandler.removeCallbacks(reconciliationRunnable)
            // Detach MediaTracker from the controller so later metadata changes do
            // not trigger provider work while the app is unused. The notification
            // listener itself keeps selecting sessions in the background.
            MediaTracker.getInstance(this).onMediaSessionChanged(null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        monitored = readMonitored()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        LyricsDemandController.addListener(lyricsDemandListener)
        if (LyricsDemandController.isActive) startSessionReconciliation()
    }

    override fun onDestroy() {
        reconciliationHandler.removeCallbacks(reconciliationRunnable)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        LyricsDemandController.removeListener(lyricsDemandListener)
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsListener,
                ComponentName(this, MediaListenerService::class.java)
            )
            updateSessions()
        } catch (_: SecurityException) { }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        updateSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    private fun updateSessions() {
        try {
            val controllers = sessionManager.getActiveSessions(
                ComponentName(this, MediaListenerService::class.java)
            )
            pickBestSession(controllers)
        } catch (_: SecurityException) { }
    }

    private fun startSessionReconciliation() {
        reconciliationHandler.removeCallbacks(reconciliationRunnable)
        reconciliationHandler.postDelayed(reconciliationRunnable, SESSION_RECONCILIATION_INTERVAL_MS)
    }

    private fun readMonitored(): Set<String> =
        prefs.getStringSet(KEY_MONITORED, null)?.toSet() ?: emptySet()

    /** Remembers every package that ever showed a media session for the picker dialog. */
    private fun recordSeen(controllers: List<MediaController>?) {
        val pkgs = controllers?.map { it.packageName }?.filter { it != packageName }?.toSet()
        if (pkgs.isNullOrEmpty()) return
        val current = prefs.getStringSet(KEY_SEEN, null)?.toSet() ?: emptySet()
        val merged = current + pkgs
        if (merged != current) prefs.edit().putStringSet(KEY_SEEN, merged).apply()
    }

    private fun pickBestSession(controllers: List<MediaController>?) {
        recordSeen(controllers)
        val filtered = controllers?.filter { sessionAllowed(it.packageName, packageName, monitored) }
        if (filtered.isNullOrEmpty()) {
            selectedSessionToken = null
            if (LyricsDemandController.isActive) {
                MediaTracker.getInstance(this).onMediaSessionChanged(null)
            }
            return
        }

        val current = selectedSessionToken?.let { token ->
            filtered.firstOrNull { controller -> controller.sessionToken == token }
        }
        val best = current?.takeIf { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: filtered.firstOrNull { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: filtered.first()

        selectedSessionToken = best.sessionToken
        if (LyricsDemandController.isActive) {
            MediaTracker.getInstance(this).onMediaSessionChanged(best)
        }
    }
}
