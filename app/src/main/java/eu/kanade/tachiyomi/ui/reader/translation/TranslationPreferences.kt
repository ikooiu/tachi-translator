package eu.kanade.tachiyomi.ui.reader.translation

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class TranslationPreferences(
    preferenceStore: PreferenceStore,
) {

    /** Master switch. Also toggled by the translate button in the reader. */
    val enabled: Preference<Boolean> = preferenceStore.getBoolean("translation_enabled", false)

    /** Show the translate toggle in the reader's bottom bar. */
    val showReaderButton: Preference<Boolean> = preferenceStore.getBoolean("translation_reader_button", true)

    /** "auto" (use the source's language, then detect), or one of ja / zh / ko / en. */
    val sourceLanguage: Preference<String> = preferenceStore.getString("translation_source_language", "auto")

    /** Google Translate language code of the output. Arabic by default. */
    val targetLanguage: Preference<String> = preferenceStore.getString("translation_target_language", "ar")

    /** Optional Google Cloud Translation key. Empty = use the free Google Translate endpoint. */
    val googleApiKey: Preference<String> = preferenceStore.getString(Preference.privateKey("translation_google_api_key"), "")

    /** Keep translated pages on disk so reopening a chapter is instant. */
    val cacheResults: Preference<Boolean> = preferenceStore.getBoolean("translation_cache_results", true)

    /** Anything that changes the rendered result must change this (it is part of the cache key). */
    fun signature(): String {
        return listOf(sourceLanguage.get(), targetLanguage.get(), RENDER_VERSION).joinToString("|")
    }

    companion object {
        /** Bump when detection / rendering changes so old cached pages are not reused. */
        const val RENDER_VERSION = "1"

        val SOURCE_LANGUAGES = listOf("auto", "ja", "zh", "ko", "en")

        /** Common target languages: Google Translate code -> name shown in settings. */
        val TARGET_LANGUAGES: Map<String, String> = linkedMapOf(
            "ar" to "العربية (Arabic)",
            "en" to "English",
            "fr" to "Français",
            "es" to "Español",
            "pt" to "Português",
            "de" to "Deutsch",
            "it" to "Italiano",
            "ru" to "Русский",
            "tr" to "Türkçe",
            "id" to "Bahasa Indonesia",
            "vi" to "Tiếng Việt",
            "th" to "ไทย",
            "fa" to "فارسی",
            "ur" to "اردو",
            "he" to "עברית",
            "hi" to "हिन्दी",
        )

        /** Languages written right to left. */
        val RTL_LANGUAGES = setOf("ar", "fa", "ur", "he", "iw")
    }
}
