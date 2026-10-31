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

        fun textField(value: String): EditText {
            return addRow(
                EditText(activity).apply {
                    setText(value)
                    inputType = InputType.TYPE_CLASS_TEXT
                    maxLines = 1
                    textSize = 13f
                }
            ) as EditText
        }

        val session = tracker.manualSearchSession
            ?: MediaTracker.ManualSearchSession(track.title, track.artist)
                .also { tracker.manualSearchSession = it }

        val titleField = textField(session.title)
        val artistField = textField(session.artist)

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

        val statusText = addRow(
            TextView(activity).apply {
                text = if (session.hits.isNotEmpty()) {
                    "${session.hits.size} results · track ${ManualSearch.formatDuration((track.durationMs / 1000).toInt())}"
                } else {
                    "Playing: ${ManualSearch.formatDuration((track.durationMs / 1000).toInt())}" +
                        " · pick a result to override its lyrics"
                }
                setTextColor(Color.parseColor("#AAAACC"))
                textSize = 12f
            }
        ) as TextView

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

        fun renderHits(hits: List<ManualSearchHit>, diagnostics: List<String>) {
            resultsGroup.removeAllViews()
            val visible = ManualSearch.applyUnsyncedFilter(hits, session.includeUnsynced)
            if (hits.isEmpty()) {
                val detail = diagnostics.joinToString(" · ")
                statusText.text = if (detail.isBlank()) {
                    "No usable results · try different terms"
                } else {
                    "No usable results · $detail"
                }
                return
            }
            val hidden = hits.size - visible.size
            statusText.text =
                "${visible.size} results" +
                (if (hidden > 0) " ($hidden unsynced hidden)" else "") +
                " · track ${ManualSearch.formatDuration((track.durationMs / 1000).toInt())}"
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

        if (session.hits.isNotEmpty()) renderHits(session.hits, session.diagnostics)

        includeUnsyncedBox.setOnCheckedChangeListener { _, checked ->
            session.includeUnsynced = checked
            if (session.hits.isNotEmpty()) renderHits(session.hits, session.diagnostics)
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
            resultsGroup.removeAllViews()
            searchButton.text = "Searching…"
            session.searching = true
            session.title = titleField.text.toString()
            session.artist = artistField.text.toString()
            tracker.launchManualSearch(
                title = session.title,
                artist = session.artist
            ) { hits, diagnostics ->
                session.searching = false
                session.hits = hits
                session.diagnostics = diagnostics
                if (dialog.isShowing) {
                    searchButton.text = "Search"
                    renderHits(hits, diagnostics)
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
                    return@setOnClickListener
                }
                if (hit.fetch != null) statusText.text = "Fetching lyrics…"
                useButton.isEnabled = false
                tracker.applyManualLyrics(hit, expectedTrack = track) { applied, message ->
                    useButton.isEnabled = true
                    when {
                        applied -> dialog.dismiss()
                        else -> statusText.text = message ?: "Could not apply result"
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
