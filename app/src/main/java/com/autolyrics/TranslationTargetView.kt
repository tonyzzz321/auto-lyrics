package com.autolyrics

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.autolyrics.lyrics.TranslationLanguages

class TranslationTargetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val targetText = TextView(context).apply {
        setTextColor(Color.parseColor("#AAAACC"))
        textSize = 12f
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TranslationLanguages.TARGET_LANGUAGE_PREF_KEY ||
                key == TRANSLATION_ENABLED_KEY
            ) {
                post { render() }
            }
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        contentDescription = "Choose lyric translation language"
        setPadding(0, dp(3), 0, dp(3))

        addView(
            TextView(context).apply {
                text = "Translation Language"
                setTextColor(Color.parseColor("#CCCCDD"))
                textSize = 13f
            },
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        addView(
            View(context),
            LayoutParams(0, 1, 1f)
        )

        addView(
            targetText,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        addView(
            TextView(context).apply {
                text = "›"
                setTextColor(Color.parseColor("#777792"))
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, 0, 0)
            },
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        setOnClickListener {
            if (isEnabled) showLanguageDialog()
        }
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        render()
    }

    override fun onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onDetachedFromWindow()
    }

    private fun render() {
        val target = TranslationLanguages.normalizeTargetLanguage(
            prefs.getString(
                TranslationLanguages.TARGET_LANGUAGE_PREF_KEY,
                TranslationLanguages.DEFAULT_TARGET_LANGUAGE
            )
        )
        // Selected-row value stays a compact English name; the native tail
        // belongs to the picker entries only.
        targetText.text = TranslationLanguages.englishName(target)

        val translationEnabled = prefs.getBoolean(TRANSLATION_ENABLED_KEY, true)
        isEnabled = translationEnabled
        alpha = if (translationEnabled) 1f else DISABLED_ALPHA
    }

    private fun showLanguageDialog() {
        val targets = TranslationLanguages.primaryTargets
        val labels = targets.map(TranslationLanguages::displayName).toTypedArray()
        val current = TranslationLanguages.normalizeTargetLanguage(
            prefs.getString(
                TranslationLanguages.TARGET_LANGUAGE_PREF_KEY,
                TranslationLanguages.DEFAULT_TARGET_LANGUAGE
            )
        )
        val checkedIndex = targets.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(context)
            .setTitle("Translation Language")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selected = targets[which]
                prefs.edit()
                    .putString(TranslationLanguages.TARGET_LANGUAGE_PREF_KEY, selected)
                    .apply()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS_NAME = "auto_lyrics_prefs"
        private const val TRANSLATION_ENABLED_KEY = "translation_enabled"
        private const val VIEW_TAG = "translation_target_view"
        private const val DISABLED_ALPHA = 0.45f

        fun install(activity: AppCompatActivity) {
            val translationSwitch = activity.findViewById<View>(R.id.switch_translation) ?: return
            val translationRow = translationSwitch.parent as? ViewGroup ?: return
            val settingsPanel = translationRow.parent as? LinearLayout ?: return
            if (settingsPanel.findViewWithTag<View>(VIEW_TAG) != null) return

            val view = TranslationTargetView(activity).apply { tag = VIEW_TAG }
            val index = settingsPanel.indexOfChild(translationRow)
            settingsPanel.addView(
                view,
                index + 1,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = view.dp(8)
                    bottomMargin = 0
                }
            )
        }
    }
}
