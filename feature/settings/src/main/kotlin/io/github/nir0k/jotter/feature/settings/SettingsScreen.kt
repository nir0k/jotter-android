package io.github.nir0k.jotter.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.data.library.LibraryEntry
import io.github.nir0k.jotter.data.location.LocationPermissions
import io.github.nir0k.jotter.data.location.hasLocationPermission
import io.github.nir0k.jotter.data.settings.Appearance
import io.github.nir0k.jotter.ui.SectionColumn
import io.github.nir0k.jotter.designsystem.R as DesignR
import io.github.nir0k.jotter.designsystem.components.AppTopBar
import io.github.nir0k.jotter.designsystem.components.Choice
import io.github.nir0k.jotter.designsystem.components.ChoiceRow
import io.github.nir0k.jotter.designsystem.components.IconAction
import io.github.nir0k.jotter.designsystem.components.LoadingState
import io.github.nir0k.jotter.designsystem.components.NavigationRow
import io.github.nir0k.jotter.designsystem.components.SettingsSection
import io.github.nir0k.jotter.designsystem.components.SwitchRow
import io.github.nir0k.jotter.designsystem.components.ThemeModeSelector
import io.github.nir0k.jotter.designsystem.components.ValueRow
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.theme.ThemeMode
import io.github.nir0k.jotter.designsystem.theme.ThemePalette
import io.github.nir0k.jotter.ui.dateTimeText
import io.github.nir0k.jotter.ui.R as UiR

// The key of the language choice that follows the system; the stored value for it is null.
private const val SystemLanguage = "system"

/**
 * LanguageChoice is the interface language choice shared by Settings and the first start.
 *
 * @param options System, then the languages written in their own language, as the design system asks.
 * @param selected The key of the stored language.
 * @param onSelect Turns a chosen key back into a language tag, null for the system language.
 */
class LanguageChoice(val options: List<Choice<String>>, val selected: String, val onSelect: (String) -> Unit)

/**
 * Builds the language choice for a stored language.
 *
 * @param language The stored language tag, null for the system language.
 * @param onLanguage Receives the chosen tag, null for the system language.
 */
@Composable
fun languageChoice(language: String?, onLanguage: (String?) -> Unit) = LanguageChoice(
    listOf(Choice(SystemLanguage, stringResource(R.string.language_system)), Choice("en", "English"), Choice("ru", "Русский")),
    language ?: SystemLanguage,
) { key -> onLanguage(key.takeUnless { it == SystemLanguage }) }

/**
 * SettingsTab is a tab of Settings.
 *
 * @param label The tab's name.
 * @param icon The tab's glyph.
 */
enum class SettingsTab(@StringRes val label: Int, val icon: ImageVector) {
    General(R.string.settings_general, AppIcons.Settings),
    Library(R.string.settings_library, AppIcons.Storage),
    Sync(R.string.settings_sync, AppIcons.Sync),
}

/**
 * LibrarySettings is what the Library tab of Settings shows and changes of the open library.
 *
 * @param current The open library, or null when the list of libraries cannot tell it.
 * @param notes The number of its notes, the archive and the trash included.
 * @param problems The number of note files that cannot be read.
 * @param retention Days a trashed note is kept, or null to keep it until the trash is emptied.
 * @param readOnly Whether the library is of a newer format, so its settings cannot change.
 * @param localOnly Whether it exists only on this phone, without a sync storage.
 * @param rules The number of its rules, or null when the rules file cannot be read.
 * @param onRetention Stores the retention period.
 * @param onLibraries Opens the libraries of this phone.
 * @param onProblems Opens the note files that cannot be read.
 * @param onRules Opens the rules.
 */
class LibrarySettings(
    val current: LibraryEntry?,
    val notes: Int,
    val problems: Int,
    val retention: Int?,
    val readOnly: Boolean,
    val localOnly: Boolean,
    val rules: Int?,
    val onRetention: (Int?) -> Unit,
    val onLibraries: () -> Unit,
    val onProblems: () -> Unit,
    val onRules: () -> Unit,
)

/** The retention periods offered, as on the desktop: 7, 30 or 90 days, or never (null). */
private val RetentionDays = listOf(7, 30, 90, null)

/** Names a library of the device: its remote folder, or that it exists only on this phone. */
@Composable
fun libraryLabel(entry: LibraryEntry): String = entry.name ?: stringResource(R.string.library_on_phone)

/** Says when a library of the device last changed, the line under its name. */
@Composable
fun libraryChanged(entry: LibraryEntry): String = stringResource(R.string.library_changed, dateTimeText(entry.changed))

/** Names a retention period: a number of days, or keeping notes until the trash is emptied. */
@Composable
private fun retentionName(days: Int?): String =
    if (days == null) stringResource(R.string.retention_never) else pluralStringResource(R.plurals.retention_days, days, days)

/**
 * SettingsScreen is the preferences template as a full-screen window: the title and Close at its
 * top, then the tabs, each a column of sections. Closing it returns to where it was opened from.
 * Every choice applies at once.
 *
 * @param initialTab The tab shown first.
 * @param appearance The current appearance.
 * @param palette The palette in use, resolved from the stored id.
 * @param onMode Stores a light or dark choice.
 * @param language The interface language's code, or null when it follows the system.
 * @param onLanguage Chooses a language tag, null for the system language.
 * @param onOpenThemes Opens the theme picker.
 * @param addLocation Whether new notes get a geotag.
 * @param onAddLocation Stores the geotag preference; turning it on asks for the location
 *   permission first, and a refusal leaves it off.
 * @param onOpenAbout Opens the version and the licences.
 * @param library The open library's settings, or null while they are read.
 * @param syncTab Draws the Sync tab.
 * @param snackbar Messages of the settings.
 * @param onClose Closes the window.
 */
@Composable
fun SettingsScreen(
    initialTab: SettingsTab,
    appearance: Appearance,
    palette: ThemePalette,
    onMode: (ThemeMode) -> Unit,
    language: String?,
    onLanguage: (String?) -> Unit,
    onOpenThemes: () -> Unit,
    addLocation: Boolean,
    onAddLocation: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    library: LibrarySettings?,
    syncTab: @Composable () -> Unit,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    Scaffold(
        topBar = {
            AppTopBar(
                stringResource(UiR.string.settings_title),
                actions = { IconAction(AppIcons.Close, stringResource(DesignR.string.ds_close), onClose, iconSize = Dimens.IconToolbar, tint = MaterialTheme.colorScheme.onSurface) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                SettingsTab.entries.forEach { item ->
                    Tab(
                        selected = tab == item,
                        onClick = { tab = item },
                        text = { Text(stringResource(item.label)) },
                        icon = { Icon(item.icon, null, Modifier.size(Dimens.IconToolbar)) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (tab) {
                SettingsTab.General -> GeneralTab(appearance, palette, onMode, language, onLanguage, onOpenThemes, addLocation, onAddLocation, onOpenAbout)
                SettingsTab.Library -> if (library == null) LoadingState() else LibraryTab(library)
                SettingsTab.Sync -> syncTab()
            }
        }
    }
}

/** The General tab: the appearance, the settings of notes on this device and About. */
@Composable
private fun GeneralTab(
    appearance: Appearance,
    palette: ThemePalette,
    onMode: (ThemeMode) -> Unit,
    language: String?,
    onLanguage: (String?) -> Unit,
    onOpenThemes: () -> Unit,
    addLocation: Boolean,
    onAddLocation: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) onAddLocation(true)
    }
    SectionColumn {
        item {
            SettingsSection(stringResource(R.string.settings_appearance)) {
                NavigationRow(stringResource(R.string.settings_theme), onOpenThemes, icon = AppIcons.Theme, value = palette.title)
                ThemeModeSelector(appearance.mode, onMode, label = stringResource(R.string.settings_mode), enabled = palette.hasBothModes)
                if (!palette.hasBothModes) {
                    Text(
                        stringResource(R.string.settings_mode_fixed, palette.title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val languages = languageChoice(language, onLanguage)
                ChoiceRow(stringResource(R.string.settings_language), languages.options, languages.selected, languages.onSelect, icon = AppIcons.Language)
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_notes)) {
                // A permission withdrawn in the system's settings turns the switch off.
                SwitchRow(
                    stringResource(R.string.settings_location),
                    addLocation && hasLocationPermission(context),
                    { on -> if (!on) onAddLocation(false) else if (hasLocationPermission(context)) onAddLocation(true) else askLocation.launch(LocationPermissions) },
                    subtitle = stringResource(R.string.settings_location_text),
                )
            }
        }
        item {
            SettingsSection(null) { NavigationRow(stringResource(R.string.settings_about), onOpenAbout, icon = AppIcons.Info) }
        }
    }
}

/** The Library tab: the open library with its retention period, its problems and its rules. */
@Composable
private fun LibraryTab(library: LibrarySettings) {
    SectionColumn {
        item {
            SettingsSection(null) {
                NavigationRow(
                    library.current?.let { libraryLabel(it) } ?: stringResource(R.string.library_on_phone),
                    library.onLibraries,
                    icon = AppIcons.Storage,
                    subtitle = library.current?.let { libraryChanged(it) },
                )
                ValueRow(stringResource(R.string.library_notes), library.notes.toString())
                if (library.localOnly) {
                    Text(stringResource(R.string.library_local_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Problems matter only when there are any: a note that cannot be read is missing
                // from the lists, and this says why.
                if (library.problems > 0) {
                    NavigationRow(stringResource(R.string.library_problems), library.onProblems, icon = AppIcons.Warning, value = library.problems.toString())
                }
                if (library.readOnly) {
                    ValueRow(stringResource(R.string.settings_retention), retentionName(library.retention))
                } else {
                    // A period set elsewhere that the list lacks is offered as it is.
                    val periods = if (library.retention in RetentionDays) RetentionDays else RetentionDays + library.retention
                    ChoiceRow(
                        stringResource(R.string.settings_retention),
                        periods.map { Choice(it, retentionName(it)) },
                        library.retention,
                        library.onRetention,
                        icon = AppIcons.Delete,
                    )
                }
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_rules)) {
                NavigationRow(
                    stringResource(R.string.settings_rules_row),
                    library.onRules,
                    icon = AppIcons.Rules,
                    value = library.rules?.let { if (it == 0) stringResource(R.string.settings_rules_none) else it.toString() },
                )
            }
        }
    }
}
