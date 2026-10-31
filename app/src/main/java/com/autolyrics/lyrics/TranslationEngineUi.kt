package com.autolyrics.lyrics

import kotlinx.coroutines.flow.StateFlow

/**
 * Shared status-UI contract implemented by every translation engine so the
 * settings/status surface can observe and retry whichever engine is selected.
 */
interface TranslationEngineUi {
    val uiState: StateFlow<LyricsTranslator.UiState>

    fun resetUiState()

    fun publishReady(sourceLanguage: String, targetLanguage: String)

    fun prepareManualRetry()
}
