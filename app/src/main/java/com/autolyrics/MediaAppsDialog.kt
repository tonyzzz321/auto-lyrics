package com.autolyrics

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.autolyrics.media.MediaListenerService

/**
 * Media-source picker: restricts which apps' media notifications the listener
 * follows. Row 0 ("Monitor all apps") is the empty-whitelist default; the app
 * list is the union of live sessions and every package ever seen with a media
 * session. Whitelist entries whose app is no longer installed stay in the pref
 * (they survive a reinstall) but are hidden from the dialog.
 *
 * The note + checkbox rows live in one custom content view: AlertDialog drops
 * its list panel entirely when a message is also set, so the stock
 * setMessage + setMultiChoiceItems pair renders the note with no list.
 */
object MediaAppsDialog {

    private const val PREFS_NAME = "auto_lyrics_prefs"
    private const val ALL_APPS = "Monitor all apps"
    private const val NOW_PLAYING = " \u00b7 now playing"

    fun show(activity: Activity, onSaved: () -> Unit = {}) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val monitored = prefs.getStringSet(MediaListenerService.KEY_MONITORED, null)?.toSet()
            ?: emptySet()
        val seen = prefs.getStringSet(MediaListenerService.KEY_SEEN, null)?.toSet()
            ?: emptySet()
        val live = liveSessions(activity)
        val pm = activity.packageManager

        // Our own LyricsBrowserService registers a media session too, so the
        // live set contains us: the listener never follows ourselves.
        val visible = (seen + monitored + live)
            .filter { it != activity.packageName }
            .filter { runCatching { pm.getApplicationInfo(it, 0) }.isSuccess }
            .sortedWith(
                compareByDescending<String> { it in live }
                    .thenBy { appLabel(pm, it).lowercase() }
            )

        val labels = mutableListOf(ALL_APPS)
        labels.addAll(visible.map { appLabel(pm, it) + if (it in live) NOW_PLAYING else "" })

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val note = android.widget.TextView(activity).apply {
            text = "Lyrics follow only the checked apps, on the phone and in Android Auto. " +
                "An app appears here after it shows a media notification once."
            setTextAppearance(android.R.style.TextAppearance_Small)
        }
        val boxes = labels.mapIndexed { index, label ->
            CheckBox(activity).apply {
                text = label
                textSize = 16f
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                isChecked = if (index == 0) monitored.isEmpty()
                else visible[index - 1] in monitored
            }
        }
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            boxes.forEach { addView(it) }
        }
        val content = ScrollView(activity).apply {
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(
                    note,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(4) }
                )
                addView(
                    rows,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            addView(container)
        }

        // Cascade: "all apps" is exclusive, and unchecking the last app falls
        // back to "all apps" (an empty whitelist means follow-everything).
        var suppress = false
        boxes.forEachIndexed { index, box ->
            box.setOnCheckedChangeListener { _, checked ->
                if (suppress) return@setOnCheckedChangeListener
                suppress = true
                when {
                    index == 0 && checked ->
                        boxes.drop(1).forEach { it.isChecked = false }
                    index == 0 ->
                        box.isChecked = true // "all" is never uncheckable alone
                    checked ->
                        boxes[0].isChecked = false
                    boxes.drop(1).none { it.isChecked } ->
                        boxes[0].isChecked = true
                }
                suppress = false
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("Media apps")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val selected = if (boxes[0].isChecked) {
                    emptySet()
                } else {
                    // Keep hidden entries (uninstalled but whitelisted) so the
                    // whitelist survives a reinstall of the app.
                    val checkedVisible =
                        visible.filterIndexed { i, _ -> boxes[i + 1].isChecked }.toSet()
                    (monitored - visible.toSet()) + checkedVisible
                }
                prefs.edit().putStringSet(MediaListenerService.KEY_MONITORED, selected).apply()
                onSaved()
            }
            .show()
    }

    private fun liveSessions(activity: Activity): Set<String> = try {
        val manager =
            activity.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        manager.getActiveSessions(
            ComponentName(activity, MediaListenerService::class.java)
        ).orEmpty()
            // Active sessions include paused apps holding a session open;
            // only STATE_PLAYING earns the "now playing" suffix.
            .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            .map { it.packageName }.toSet()
    } catch (_: SecurityException) {
        // Notification access not granted (or listener not connected): the
        // dialog still lists every seen package.
        emptySet()
    }

    private fun appLabel(pm: PackageManager, pkg: String): String =
        runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
}
