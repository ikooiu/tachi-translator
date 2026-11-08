package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.translation.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun ReaderBottomBar(
    // SY -->
    enabledButtons: Set<String>,
    // SY <--
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    // SY -->
    currentReadingMode: ReadingMode,
    dualPageSplitEnabled: Boolean,
    doublePages: Boolean,
    onClickChapterList: () -> Unit,
    onClickWebView: (() -> Unit)?,
    onClickBrowser: (() -> Unit)?,
    onClickShare: (() -> Unit)?,
    onClickPageLayout: () -> Unit,
    onClickShiftPage: () -> Unit,
    // SY <--
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // SY -->
        if (ReaderBottomButton.ViewChapters.isIn(enabledButtons)) {
            IconButton(onClick = onClickChapterList) {
                Icon(
                    imageVector = Icons.Outlined.FormatListNumbered,
                    contentDescription = stringResource(MR.strings.chapters),
                )
            }
        }

        if (ReaderBottomButton.WebView.isIn(enabledButtons) && onClickWebView != null) {
            IconButton(onClick = onClickWebView) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = stringResource(MR.strings.action_open_in_web_view),
                )
            }
        }

        if (ReaderBottomButton.Browser.isIn(enabledButtons) && onClickBrowser != null) {
            IconButton(onClick = onClickBrowser) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = stringResource(MR.strings.action_open_in_browser),
                )
            }
        }

        if (ReaderBottomButton.Share.isIn(enabledButtons) && onClickShare != null) {
            IconButton(onClick = onClickShare) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = stringResource(MR.strings.action_share),
                )
            }
        }

        if (ReaderBottomButton.ReadingMode.isIn(enabledButtons)) {
            IconButton(onClick = onClickReadingMode) {
                Icon(
                    painter = painterResource(readingMode.iconRes),
                    contentDescription = stringResource(MR.strings.viewer),
                )
            }
        }

        if (ReaderBottomButton.Rotation.isIn(enabledButtons)) {
            IconButton(onClick = onClickOrientation) {
                Icon(
                    imageVector = orientation.icon,
                    contentDescription = stringResource(MR.strings.pref_rotation_type),
                )
            }
        }

        val cropBorders = when (currentReadingMode) {
            ReadingMode.WEBTOON -> ReaderBottomButton.CropBordersWebtoon
            ReadingMode.CONTINUOUS_VERTICAL -> ReaderBottomButton.CropBordersContinuesVertical
            else -> ReaderBottomButton.CropBordersPager
        }
        if (cropBorders.isIn(enabledButtons)) {
            IconButton(onClick = onClickCropBorder) {
                Icon(
                    painter = painterResource(
                        if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp,
                    ),
                    contentDescription = stringResource(MR.strings.pref_crop_borders),
                )
            }
        }

        if (
            !dualPageSplitEnabled &&
            ReaderBottomButton.PageLayout.isIn(enabledButtons) &&
            ReadingMode.isPagerType(currentReadingMode.flagValue)
        ) {
            IconButton(onClick = onClickPageLayout) {
                Icon(
                    painter = painterResource(R.drawable.ic_book_open_variant_24dp),
                    contentDescription = stringResource(SYMR.strings.page_layout),
                )
            }
        }

        if (doublePages) {
            IconButton(onClick = onClickShiftPage) {
                Icon(
                    painter = painterResource(R.drawable.ic_page_next_outline_24dp),
                    contentDescription = stringResource(SYMR.strings.shift_double_pages),
                )
            }
        }

        val translationPrefs = remember { Injekt.get<TranslationPreferences>() }
        val showTranslate by translationPrefs.showReaderButton.collectAsState()
        val translateOn by translationPrefs.enabled.collectAsState()
        if (showTranslate) {
            IconButton(onClick = { translationPrefs.enabled.set(!translateOn) }) {
                Icon(
                    imageVector = Icons.Outlined.Translate,
                    contentDescription = stringResource(
                        if (translateOn) SYMR.strings.translation_toggle_on else SYMR.strings.translation_toggle_off,
                    ),
                    tint = if (translateOn) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
        }

        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
            )
        }
        // SY <--
    }
}
