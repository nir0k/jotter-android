package io.github.nir0k.jotter.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import io.github.nir0k.jotter.designsystem.components.CategoryTrigger
import io.github.nir0k.jotter.designsystem.components.InlineError
import io.github.nir0k.jotter.designsystem.components.ProgressButton
import io.github.nir0k.jotter.designsystem.components.SecondaryButton
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.ui.R as UiR

/**
 * FirstStartScreen is the first screen of a device without a library, laid out as the design
 * system's connection screen: the logo and product name, what a library is and where it lives,
 * Create library, Download from SFTP, and the interface language below them.
 *
 * @param language The stored interface language, null for the system language.
 * @param onLanguage Stores and applies a language, null for the system language.
 * @param creating Whether a library is being created.
 * @param failed Whether the last attempt to create one failed.
 * @param onCreate Creates the library.
 * @param onDownload Opens the connection for downloading a library from an SFTP folder.
 */
@Composable
fun FirstStartScreen(
    language: String?,
    onLanguage: (String?) -> Unit,
    creating: Boolean,
    failed: Boolean,
    onCreate: () -> Unit,
    onDownload: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Dimens.EntryMargin),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = Dimens.EntryMaxWidth).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(UiR.drawable.ic_launcher_art), null, Modifier.size(Dimens.EntryLogo))
                Text(
                    stringResource(UiR.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = Dimens.DialogFieldGap),
                )
                Text(
                    stringResource(R.string.first_start_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Dimens.SectionFieldGap, bottom = Dimens.DialogPadding),
                )
                if (failed) InlineError(stringResource(R.string.first_start_failed), Modifier.fillMaxWidth().padding(bottom = Dimens.DialogFieldGap))
                ProgressButton(stringResource(R.string.first_start_create), onCreate, Modifier.fillMaxWidth(), busy = creating)
                SecondaryButton(
                    stringResource(R.string.first_start_download),
                    onDownload,
                    Modifier.fillMaxWidth().padding(top = Dimens.DialogFieldGap),
                    enabled = !creating,
                    icon = AppIcons.Download,
                )
                Row(Modifier.fillMaxWidth().padding(top = Dimens.SectionFieldGap)) {
                    val languages = languageChoice(language, onLanguage)
                    CategoryTrigger(
                        stringResource(R.string.settings_language),
                        languages.options,
                        languages.selected,
                        languages.onSelect,
                        icon = AppIcons.Globe,
                    )
                }
            }
        }
    }
}
