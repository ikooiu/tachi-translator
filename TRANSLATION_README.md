# Page translation (offline OCR + Google Translate) for TachiyomiSY

What it does
- Finds speech bubbles and caption boxes on the page, reads them **on the phone** (Google ML Kit text recognition, models are
  bundled in the APK, no cloud OCR, no AI model), and translates only the extracted text with Google Translate.
- The translation is drawn into the page image, so zoom, pan, webtoon scrolling and cropping keep working.
- The original page shows immediately; the translated page replaces it a moment later. Translated pages are cached on disk,
  so reopening a chapter is instant. Turning translation on or off in the reader reloads the page.
- Default target language: Arabic (right-to-left text layout, Arabic punctuation).

Where to find it
- **Settings -> Page translation** (first entry in the settings list): enable, original language (automatic / Japanese /
  Chinese / Korean / English), target language, reader button on/off, keep translated pages, clear cache, optional
  Google Cloud Translation API key.
- **Reader bottom bar**: translate button (turns blue when on) toggles translation on demand.
- "Automatic" uses the language of the manga source; if the source has no language, it is detected once per source.

New files (`app/src/main/java/eu/kanade/tachiyomi/ui/reader/translation/`)
- `BubbleDetector.kt`, `TextCleanup.kt` - pure Kotlin, no Android imports (tested on a plain JVM).
- `OcrEngine.kt` (ML Kit), `GoogleTranslate.kt` (OkHttp), `PageRenderer.kt` (draws text), `ReaderTranslator.kt` (pipeline + cache),
  `TranslationPreferences.kt`.
- `app/.../presentation/more/settings/screen/SettingsTranslationScreen.kt`.

Changed files
- `gradle/libs.versions.toml`, `app/build.gradle.kts` (ML Kit text recognition: latin, chinese, japanese, korean 16.0.1)
- `PreferenceModule.kt` (register preferences)
- `SettingsMainScreen.kt`, `SettingsSearchScreen.kt` (settings entry, first in the list)
- `ReaderBottomBar.kt` (translate button)
- `PagerPageHolder.kt`, `WebtoonPageHolder.kt` (hooks, marked with `// TRANSLATION -->`)
- `i18n-sy/.../strings.xml` (strings)
- `.github/workflows/build_debug.yml` (builds the APK in GitHub Actions)

Build
- GitHub: push this folder to a repository, open the Actions tab, run **Build debug APK**, download the artifact.
- Or Android Studio / `./gradlew assembleDebug` (JDK 17). The debug app installs next to your existing Tachiyomi
  (it has its own `.debug` id), so your library is untouched. Use backup/restore to copy the library over if you want.

Known limits
- Only text inside bubbles / caption boxes is translated, not sound effects or text drawn on the artwork.
- Dual-page "split" / "rotate to fit" wide pages are not translated. Double-page mode (two pages shown together) is skipped.
- Vertical Japanese is rearranged into horizontal text before recognition; quality depends on the art style.
- The free Google Translate endpoint is unofficial; if it starts failing, add a Cloud Translation API key in the settings.
