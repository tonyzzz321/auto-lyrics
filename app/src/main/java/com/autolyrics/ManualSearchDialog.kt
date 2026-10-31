package com.autolyrics

import android.app.Activity
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.autolyrics.lyrics.ManualSearch
import com.autolyrics.lyrics.ManualSearchHit
import com.autolyrics.media.MediaTracker

/**
 * Manual lyrics lookup: editable song/artist terms, a LRCLIB search, and a
 * pick-list that overrides the current track's lyrics. Opened from the
 * main-screen "Manual Search" button.
 */
object ManualSearchDialog {

    fun show(activity: Activity) {
        val tracker = MediaTracker.getInstance(activity)
        val track = tracker.state.value.track
        if (track == null) {
            Toast.makeText(activity, "Play a song first", Toast.LENGTH_SHORT).show()
            return
        }

        fun dp(value: Int): Int =
            (value * activity.resources.displayMetrics.density).toInt()

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }

        fun addRow(view: View): View {
            column.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
            )
            return view
        }

        fun textField(value: String, placeholder: String): EditText {
            return addRow(
                EditText(activity).apply {
                    setText(value)
                    hint = placeholder
                    setHintTextColor(Color.parseColor("#556677"))
                    inputType = InputType.TYPE_CLASS_TEXT
                    maxLines = 1
                    textSize = 13f
                }
            ) as EditText
        }

        val session = tracker.manualSearchSession
            ?: MediaTracker.ManualSearchSession(track.title, track.artist)
                .also { tracker.manualSearchSession = it }

        val titleField = textField(session.title, "Song title (required)")
        val artistField = textField(session.artist, "Artist (optional)")

        val includeUnsyncedBox = CheckBox(activity).apply {
            text = "Include unsynced results"
            isChecked = session.includeUnsynced
            textSize = 13f
            setTextColor(Color.parseColor("#CCCCDD"))
        }
        val searchButton = TextView(activity).apply {
            text = if (session.searching) "Searching…" else "Search"
            setTextColor(Color.parseColor("#AAAACC"))
            textSize = 13f
            gravity = Gravity.END
            setPadding(0, dp(4), dp(4), dp(4))
        }
        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                includeUnsyncedBox,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                searchButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        addRow(actionRow)

        val durationSec = (track.durationMs / 1000).toInt()
        val statusMuted = Color.parseColor("#AAAACC")
        val statusError = Color.parseColor("#FF7B72")
        val statusText = addRow(
            TextView(activity).apply {
                // A carried-over search re-renders right below via
                // renderHits; this is the never-searched-yet line.
                text = "Playing: ${ManualSearch.formatDuration(durationSec)}" +
                    " · pick a result to override its lyrics"
                setTextColor(statusMuted)
                textSize = 12f
            }
        ) as TextView

        // The status line carries only the outcome; every diagnostic lives
        // behind this tap. It arms once a search has ended (or results are
        // carried over); the never-searched line is not tappable.
        statusText.isClickable = false
        statusText.setOnClickListener {
            val hidden = session.hits.size -
                ManualSearch.applyUnsyncedFilter(session.hits, session.includeUnsynced).size
            val tail = mutableListOf<String>()
            if (hidden > 0) tail += "unsynced hidden: $hidden"
            tail += "track ${ManualSearch.formatDuration(durationSec)}"
            val detail = session.diagnostics + session.deaths + tail
            val lines = TextView(activity).apply {
                text = detail.joinToString("\n")
                setTextColor(Color.parseColor("#CCCCDD"))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(20), dp(8), dp(20), 0)
            }
            AlertDialog.Builder(activity)
                .setTitle("Search diagnostics")
                .setView(ScrollView(activity).apply { addView(lines) })
                .setPositiveButton("Close", null)
                .show()
        }

        val resultsGroup = RadioGroup(activity)
        val resultsScroll = ScrollView(activity).apply {
            addView(
                resultsGroup,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        addRow(resultsScroll)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Manual Lyrics Search")
            .setView(column)
            .setPositiveButton("Use selected", null)
            .setNegativeButton("Cancel", null)
            .create()

        fun renderHits(hits: List<ManualSearchHit>, deaths: List<String>) {
            resultsGroup.removeAllViews()
            val visible = ManualSearch.applyUnsyncedFilter(hits, session.includeUnsynced)
            statusText.text = ManualSearch.statusLine(visible.size, durationSec, deaths)
            statusText.isClickable = true
            statusText.setTextColor(
                if (visible.isEmpty() && deaths.isNotEmpty()) statusError else statusMuted
            )
            if (hits.isEmpty()) return
            visible.forEach { hit ->
                val radio = RadioButton(activity).apply {
                    text = hit.label
                    textSize = 13f
                    setTextColor(Color.parseColor("#CCCCDD"))
                    tag = hit
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                }
                resultsGroup.addView(
                    radio,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                if (hit == session.selected) radio.isChecked = true
            }
        }

        if (session.hits.isNotEmpty() || session.diagnostics.isNotEmpty() ||
            session.deaths.isNotEmpty()
        ) {
            renderHits(session.hits, session.deaths)
        }

        includeUnsyncedBox.setOnCheckedChangeListener { _, checked ->
            session.includeUnsynced = checked
            renderHits(session.hits, session.deaths)
        }

        resultsGroup.setOnCheckedChangeListener { group, checkedId ->
            session.selected = if (checkedId >= 0) {
                group.findViewById<RadioButton>(checkedId)?.tag as? ManualSearchHit
            } else {
                null
            }
        }

        searchButton.setOnClickListener {
            if (session.searching) return@setOnClickListener
            val typedTitle = titleField.text.toString().trim()
            if (typedTitle.isEmpty()) {
                statusText.text = "Song title required"
                statusText.setTextColor(statusError)
                return@setOnClickListener
            }
            resultsGroup.removeAllViews()
            searchButton.text = "Searching…"
            statusText.setTextColor(statusMuted)
            statusText.text = "Searching…"
            session.searching = true
            session.title = typedTitle
            session.artist = artistField.text.toString()
            tracker.launchManualSearch(
                title = session.title,
                artist = session.artist
            ) { hits, diagnostics, deaths ->
                session.searching = false
                session.hits = hits
                session.diagnostics = diagnostics
                session.deaths = deaths
                if (dialog.isShowing) {
                    searchButton.text = "Search"
                    renderHits(hits, deaths)
                }
            }
        }

        dialog.setOnShowListener {
            val useButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            useButton.setOnClickListener {
                val selectedId = resultsGroup.checkedRadioButtonId
                val hit = if (selectedId >= 0) {
                    resultsGroup.findViewById<RadioButton>(selectedId)?.tag as? ManualSearchHit
                } else {
                    null
                }
                if (hit == null) {
                    statusText.text = "Pick a result first"
                    statusText.setTextColor(statusError)
                    return@setOnClickListener
                }
                useButton.isEnabled = false
                tracker.applyManualLyrics(hit, expectedTrack = track) { applied, message ->
                    useButton.isEnabled = true
                    when {
                        applied -> dialog.dismiss()
                        else -> {
                            statusText.text = message ?: "Could not apply result"
                            statusText.setTextColor(statusError)
                        }
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            session.title = titleField.text.toString()
            session.artist = artistField.text.toString()
        }

        dialog.show()
    }
}
