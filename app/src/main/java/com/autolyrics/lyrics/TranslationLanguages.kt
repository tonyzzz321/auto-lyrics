package com.autolyrics.lyrics

import java.util.Locale

/** Central translation-language configuration shared by Settings and the translator. */
object TranslationLanguages {
    const val DEFAULT_TARGET_LANGUAGE = "en"
    const val TARGET_LANGUAGE_PREF_KEY = "translation_target_language"

    const val SIMPLIFIED_CHINESE = "zh-CN"
    const val TRADITIONAL_CHINESE = "zh-TW"

    /**
     * Intentionally limited to the main languages we want to expose in Settings.
     * ML Kit supports more languages, but a smaller list keeps the UI predictable.
     * Chinese is deliberately split into its two written forms: both engines
     * treat them as separate targets (ML Kit ships separate model packs, and
     * LLM prompts honor the named form), and already-Chinese lyrics in the
     * other form are converted offline via [ChineseVariant] instead of
     * re-translated.
     */
    val primaryTargets: List<String> = listOf(
        "en", // English
        "ja", // Japanese
        "fr", // French
        "de", // German
        "es", // Spanish
        "ko", // Korean
        SIMPLIFIED_CHINESE,  // Simplified Chinese
        TRADITIONAL_CHINESE, // Traditional Chinese
        "it", // Italian
        "pt"  // Portuguese
    )

    /** Picker labels: "English name · native name" whenever they differ. */
    private val displayNames = mapOf(
        "en" to "English",
        "ja" to "Japanese \u00B7 日本語",
        "fr" to "French \u00B7 Français",
        "de" to "German \u00B7 Deutsch",
        "es" to "Spanish \u00B7 Español",
        "ko" to "Korean \u00B7 한국어",
        SIMPLIFIED_CHINESE to "Simplified Chinese \u00B7 简体中文",
        TRADITIONAL_CHINESE to "Traditional Chinese \u00B7 繁體中文",
        "it" to "Italian \u00B7 Italiano",
        "pt" to "Portuguese \u00B7 Português"
    )

    /** Plain English names for prompts, status lines and the selected-row value. */
    private val englishNames = mapOf(
        SIMPLIFIED_CHINESE to "Simplified Chinese",
        TRADITIONAL_CHINESE to "Traditional Chinese"
    )

    /**
     * Normalizes BCP-47-ish tags to this app's language codes. Chinese keeps
     * its written form (script or region tags collapse onto the two target
     * codes); a bare "zh" — including legacy stored targets — resolves to
     * Simplified, matching what the historical bare-Chinese target produced.
     */
    fun normalizeLanguageTag(languageTag: String?): String? {
        if (languageTag.isNullOrBlank()) return null
        val tag = languageTag.trim().lowercase(Locale.US)
        // Script/region markers are parsed from the raw tag: java.util.Locale
        // (JVM unit tests) drops script subtags entirely, Android's does not.
        val language = Locale.forLanguageTag(languageTag).language
            .ifBlank { tag.substringBefore('-') }
        if (language != "zh") {
            return language.takeIf { it.isNotBlank() && it != "und" }
        }
        val traditional = tag.contains("hant") || tag.contains("-tw") || tag.endsWith("-tw") ||
            tag.contains("-hk") || tag.endsWith("-hk") || tag.contains("-mo")
        return if (traditional) TRADITIONAL_CHINESE else SIMPLIFIED_CHINESE
    }

    fun normalizeTargetLanguage(languageTag: String?): String {
        val normalized = normalizeLanguageTag(languageTag)
        return normalized?.takeIf { it in primaryTargets } ?: DEFAULT_TARGET_LANGUAGE
    }

    /** Two Chinese targets share one base language for already-in-language checks. */
    fun baseLanguage(languageTag: String?): String =
        normalizeLanguageTag(languageTag)?.substringBefore('-') ?: ""

    /** Settings picker label with the native-name tail. */
    fun displayName(languageTag: String?): String {
        val normalized = normalizeLanguageTag(languageTag) ?: DEFAULT_TARGET_LANGUAGE
        return displayNames[normalized]
            ?: Locale.forLanguageTag(normalized)
                .getDisplayLanguage(Locale.ENGLISH)
                .takeIf { it.isNotBlank() }
                ?: normalized.uppercase(Locale.ENGLISH)
    }

    /** Plain English name for prompts, status text and the selected-row value. */
    fun englishName(languageTag: String?): String {
        val normalized = normalizeLanguageTag(languageTag) ?: DEFAULT_TARGET_LANGUAGE
        return englishNames[normalized]
            ?: Locale.forLanguageTag(normalized)
                .getDisplayLanguage(Locale.ENGLISH)
                .takeIf { it.isNotBlank() }
                ?: normalized.uppercase(Locale.ENGLISH)
    }

    fun isChineseTarget(languageTag: String?): Boolean =
        normalizeLanguageTag(languageTag) in setOf(SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE)

    /**
     * ML Kit translation models are language-specific, not source/target-pair files.
     * English is built in; translating between two non-English languages therefore
     * requires both language packs, which ML Kit uses through English internally.
     */
    fun requiredModelLanguages(sourceLanguage: String, targetLanguage: String): List<String> {
        val source = normalizeLanguageTag(sourceLanguage)
        val target = normalizeLanguageTag(targetLanguage)

        return listOfNotNull(source, target)
            .filter { it != DEFAULT_TARGET_LANGUAGE }
            .distinct()
    }
}
