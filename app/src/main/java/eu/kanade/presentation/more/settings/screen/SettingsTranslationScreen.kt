package eu.kanade.presentation.more.settings.screen

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.translation.ReaderTranslator
import eu.kanade.tachiyomi.ui.reader.translation.TranslationPreferences
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = SYMR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val prefs = remember { Injekt.get<TranslationPreferences>() }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var cacheBytes by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            cacheBytes = withContext(Dispatchers.IO) { ReaderTranslator.instance.cacheSize() }
        }

        val sourceNames = mapOf(
            "auto" to stringResource(SYMR.strings.translation_lang_auto),
            "ja" to stringResource(SYMR.strings.translation_lang_ja),
            "zh" to stringResource(SYMR.strings.translation_lang_zh),
            "ko" to stringResource(SYMR.strings.translation_lang_ko),
            "en" to stringResource(SYMR.strings.translation_lang_en),
        )

        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = prefs.enabled,
                title = stringResource(SYMR.strings.pref_translation_enable),
                subtitle = stringResource(SYMR.strings.pref_translation_enable_summary),
            ),
            Preference.PreferenceGroup(
                title = stringResource(SYMR.strings.pref_category_translation),
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.sourceLanguage,
                        entries = TranslationPreferences.SOURCE_LANGUAGES.associateWith { sourceNames[it] ?: it },
                        title = stringResource(SYMR.strings.pref_translation_source_language),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.targetLanguage,
                        entries = TranslationPreferences.TARGET_LANGUAGES,
                        title = stringResource(SYMR.strings.pref_translation_target_language),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.showReaderButton,
                        title = stringResource(SYMR.strings.pref_translation_reader_button),
                        subtitle = stringResource(SYMR.strings.pref_translation_reader_button_summary),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(SYMR.strings.pref_translation_cache),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.cacheResults,
                        title = stringResource(SYMR.strings.pref_translation_cache),
                        subtitle = stringResource(SYMR.strings.pref_translation_cache_summary),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(SYMR.strings.pref_translation_clear_cache),
                        subtitle = Formatter.formatFileSize(context, cacheBytes),
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { ReaderTranslator.instance.clearCache() }
                                cacheBytes = 0L
                                context.toast(SYMR.strings.pref_translation_cache_cleared)
                            }
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(SYMR.strings.pref_translation_google_key),
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.googleApiKey,
                        title = stringResource(SYMR.strings.pref_translation_google_key),
                        subtitle = stringResource(SYMR.strings.pref_translation_google_key_summary),
                    ),
                ),
            ),
            Preference.PreferenceItem.InfoPreference(stringResource(SYMR.strings.pref_translation_note)),
        )
    }
}
