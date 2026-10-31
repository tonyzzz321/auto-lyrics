package com.autolyrics

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.autolyrics.auto.LyricsBrowserService
import com.autolyrics.lyrics.LyricsTranslator
import com.autolyrics.media.LyricsDemandController
import com.autolyrics.media.MediaTracker

class AutoLyricsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LyricsTranslator.init(this)
        MediaTracker.init(this)

        // ProcessLifecycleOwner deliberately delays process ON_STOP across brief
        // Activity recreation gaps, so rotation/configuration changes do not make
        // lyric demand drop to zero and detach MediaTracker between instances.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                LyricsDemandController.setPhoneForeground(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                LyricsDemandController.setPhoneForeground(false)
            }
        })

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                if (activity is AppCompatActivity) {
                    // Install status first; inserting the target row afterwards pushes
                    // status below it, yielding Toggle -> Language -> Status.
                    TranslationStatusView.install(activity)
                    TranslationTargetView.install(activity)
                    TranslationEngineView.install(activity)
                    TranslationMetadataBinder.install(activity)
                }
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // Keep lyric resolution active for the whole Android Auto projection
        // session, even while the user is viewing another AA app. NATIVE is
        // the same relationship without a phone: the app running directly on
        // an Automotive (AAOS) head unit, as in the AAOS test emulator.
        CarConnection(this).type.observeForever { connectionType ->
            LyricsDemandController.setCarProjectionConnected(
                connectionType != CarConnection.CONNECTION_TYPE_NOT_CONNECTED
            )
        }

        // Preserve existing MediaBrowserService startup/discovery behavior. This
        // change gates provider work only; it does not change AA auto-availability.
        try {
            startService(Intent(this, LyricsBrowserService::class.java))
        } catch (_: Exception) { }
    }
}
