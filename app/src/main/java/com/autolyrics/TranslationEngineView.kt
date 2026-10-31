package com.autolyrics

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.autolyrics.lyrics.LlmTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranslationEngineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val engineText = TextView(context).apply {
        setTextColor(Color.parseColor("#AAAACC"))
        textSize = 12f
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        maxLines = 1
    }

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == LlmTranslator.PREF_ENGINE_KEY ||
                key == LlmTranslator.PREF_LLM_MODEL ||
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
        contentDescription = "Choose lyric translation engine"
        setPadding(0, dp(3), 0, dp(3))

        addView(
            TextView(context).apply {
                text = "Translation Engine"
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
            engineText,
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
            if (isEnabled) showEngineDialog()
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
        val useLlm = LlmTranslator.isLlmEngine(prefs)
        engineText.text = when {
            !useLlm -> "On-device"
            LlmTranslator.loadConfig(prefs) != null -> {
                "LLM · ${prefs.getString(LlmTranslator.PREF_LLM_MODEL, "").orEmpty()}"
            }
            else -> "LLM (setup needed)"
        }

        val translationEnabled = prefs.getBoolean(TRANSLATION_ENABLED_KEY, true)
        isEnabled = translationEnabled
        alpha = if (translationEnabled) 1f else DISABLED_ALPHA
    }

    private fun showEngineDialog() {
        val labels = arrayOf("On-device (ML Kit)", "Remote LLM")
        val current = if (LlmTranslator.isLlmEngine(prefs)) 1 else 0

        AlertDialog.Builder(context)
            .setTitle("Translation Engine")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs.edit()
                    .putString(
                        LlmTranslator.PREF_ENGINE_KEY,
                        if (which == 1) LlmTranslator.ENGINE_LLM else LlmTranslator.ENGINE_ON_DEVICE
                    )
                    .apply()
                dialog.dismiss()
                if (which == 1 && LlmTranslator.loadConfig(prefs) == null) {
                    showConfigDialog()
                }
            }
            .setNeutralButton("Configure…") { _, _ -> showConfigDialog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showConfigDialog() {
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }

        fun addRow(view: View): View {
            column.addView(
                view,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
            )
            return view
        }

        fun textField(hint: String, value: String, password: Boolean): EditText {
            return addRow(
                EditText(context).apply {
                    this.hint = hint
                    setText(value)
                    inputType = if (password) {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    } else {
                        InputType.TYPE_CLASS_TEXT
                    }
                    maxLines = if (password) 1 else 2
                    textSize = 13f
                }
            ) as EditText
        }

        val savedModel = prefs.getString(LlmTranslator.PREF_LLM_MODEL, "").orEmpty()
        val urlField = textField(
            "Host or base URL (e.g. api.openai.com)",
            prefs.getString(LlmTranslator.PREF_LLM_BASE_URL, "").orEmpty(),
            password = false
        )
        val keyField = textField(
            "API key (optional for local servers)",
            prefs.getString(LlmTranslator.PREF_LLM_API_KEY, "").orEmpty(),
            password = true
        )

        var loadedModelIds = listOf<String>()
        var manualMode = savedModel.isBlank()
        var userChoseManual = false
        var lastFetchedRequestKey: String? = null
        var fetchJob: Job? = null
        val dialogScope = (context as? AppCompatActivity)?.lifecycleScope

        val modelSpinner = Spinner(context)
        addRow(modelSpinner)
        val refreshRow = addRow(
            TextView(context).apply {
                text = "↻ Reload model list"
                setTextColor(Color.parseColor("#AAAACC"))
                textSize = 12f
                gravity = Gravity.END
                setPadding(0, 0, dp(4), 0)
            }
        )
        val manualModelField = textField(
            "Model name (typed manually)",
            savedModel,
            password = false
        )

        fun setItems(items: List<String>, selected: String?) {
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, items)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            modelSpinner.adapter = adapter
            val index = items.indexOf(selected).takeIf { it != null && it >= 0 }
            if (index != null) modelSpinner.setSelection(index)
        }

        fun applyManualVisibility() {
            manualModelField.visibility = if (manualMode) VISIBLE else GONE
        }

        fun startModelFetch() {
            val url = urlField.text.toString().trim()
            if (url.isBlank() || dialogScope == null) return
            val requestKey = url + "\n" + keyField.text.toString().trim()
            if (fetchJob?.isActive == true || requestKey == lastFetchedRequestKey) return
            lastFetchedRequestKey = requestKey

            val previousSelection = modelSpinner.selectedItem?.toString()
                ?.takeIf { !manualMode && it != "Loading models…" && it != MANUAL_MODEL_LABEL }
            setItems(listOf("Loading models…", MANUAL_MODEL_LABEL), null)
            modelSpinner.isEnabled = false

            fetchJob = dialogScope.launch {
                val models = try {
                    withContext(Dispatchers.IO) {
                        LlmTranslator.fetchModels(
                            baseUrl = url,
                            apiKey = requestKey.substringAfter('\n')
                        )
                    }
                } catch (_: Exception) {
                    null
                }
                modelSpinner.isEnabled = true
                if (!modelSpinner.isAttachedToWindow) return@launch
                loadedModelIds = models.orEmpty()
                if (loadedModelIds.isNotEmpty()) {
                    val preferred = previousSelection?.takeIf { it in loadedModelIds }
                        ?: savedModel.takeIf { it in loadedModelIds && !userChoseManual }
                    manualMode = preferred == null
                    setItems(
                        loadedModelIds + MANUAL_MODEL_LABEL,
                        if (manualMode) MANUAL_MODEL_LABEL else preferred
                    )
                } else {
                    manualMode = true
                    setItems(listOf(MANUAL_MODEL_LABEL), MANUAL_MODEL_LABEL)
                }
                applyManualVisibility()
            }
        }

        if (manualMode) {
            setItems(listOf(MANUAL_MODEL_LABEL), MANUAL_MODEL_LABEL)
        } else {
            setItems(listOf(savedModel, MANUAL_MODEL_LABEL), savedModel)
        }
        applyManualVisibility()

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val isManual = parent?.getItemAtPosition(position) == MANUAL_MODEL_LABEL
                if (isManual) userChoseManual = true
                manualMode = isManual
                applyManualVisibility()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        refreshRow.setOnClickListener {
            lastFetchedRequestKey = null
            userChoseManual = false
            startModelFetch()
        }
        urlField.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) startModelFetch()
        }

        startModelFetch()

        AlertDialog.Builder(context)
            .setTitle("Remote LLM")
            .setMessage(
                "Any OpenAI-compatible endpoint. A bare host gets https:// and /v1 " +
                    "added automatically; the API key may be left empty when the " +
                    "server does not require one."
            )
            .setView(column)
            .setPositiveButton("Save") { _, _ ->
                val chosenModel = when {
                    manualMode -> manualModelField.text.toString().trim()
                    else -> modelSpinner.selectedItem?.toString()?.trim().orEmpty()
                }.takeIf { it != MANUAL_MODEL_LABEL && it != "Loading models…" }
                    ?.takeIf { manualMode || loadedModelIds.isEmpty() || it in loadedModelIds }

                prefs.edit().apply {
                    putString(
                        LlmTranslator.PREF_LLM_BASE_URL,
                        LlmTranslator.normalizeBaseUrl(urlField.text.toString())
                    )
                    putString(LlmTranslator.PREF_LLM_API_KEY, keyField.text.toString().trim())
                    if (!chosenModel.isNullOrBlank()) {
                        putString(LlmTranslator.PREF_LLM_MODEL, chosenModel)
                    }
                }.apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS_NAME = "auto_lyrics_prefs"
        private const val TRANSLATION_ENABLED_KEY = "translation_enabled"
        private const val VIEW_TAG = "translation_engine_view"
        private const val MANUAL_MODEL_LABEL = "Other… (type manually)"
        private const val TARGET_VIEW_TAG = "translation_target_view"
        private const val DISABLED_ALPHA = 0.45f

        fun install(activity: AppCompatActivity) {
            val translationSwitch = activity.findViewById<View>(R.id.switch_translation) ?: return
            val translationRow = translationSwitch.parent as? ViewGroup ?: return
            val settingsPanel = translationRow.parent as? LinearLayout ?: return
            if (settingsPanel.findViewWithTag<View>(VIEW_TAG) != null) return

            val view = TranslationEngineView(activity).apply { tag = VIEW_TAG }
            val targetView = settingsPanel.findViewWithTag<View>(TARGET_VIEW_TAG)
            val index = if (targetView != null) {
                settingsPanel.indexOfChild(targetView) + 1
            } else {
                settingsPanel.indexOfChild(translationRow) + 1
            }
            settingsPanel.addView(
                view,
                index,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = view.dp(8)
                    bottomMargin = 0
                }
            )
        }
    }
}
