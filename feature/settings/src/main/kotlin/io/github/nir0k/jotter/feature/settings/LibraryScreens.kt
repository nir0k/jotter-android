package io.github.nir0k.jotter.feature.settings

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.core.net.toUri
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.UnsupportedBody
import io.github.nir0k.jotter.core.index.Problem
import io.github.nir0k.jotter.data.library.LibraryEntry
import io.github.nir0k.jotter.ui.SectionScreen
import io.github.nir0k.jotter.ui.startActivity
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.nir0k.jotter.designsystem.components.AddButton
import io.github.nir0k.jotter.designsystem.components.AppTopBar
import io.github.nir0k.jotter.designsystem.components.ConfirmDialog
import io.github.nir0k.jotter.designsystem.components.DangerButton
import io.github.nir0k.jotter.designsystem.components.EmptyState
import io.github.nir0k.jotter.designsystem.components.LoadingState
import io.github.nir0k.jotter.designsystem.components.MenuAction
import io.github.nir0k.jotter.designsystem.components.NavigationRow
import io.github.nir0k.jotter.designsystem.components.ProgressButton
import io.github.nir0k.jotter.designsystem.components.SecondaryButton
import io.github.nir0k.jotter.designsystem.components.SettingsSection
import io.github.nir0k.jotter.designsystem.components.ValueRow
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.ui.R as UiR

/** Returns the localized reason a note file cannot be read. */
@Composable
private fun problemReason(code: ErrorCode): String = stringResource(
    when (code) {
        ErrorCode.Malformed -> R.string.problem_malformed
        ErrorCode.NewerFormat -> R.string.problem_newer
        ErrorCode.NotFound -> R.string.problem_missing
        else -> R.string.problem_unreadable
    },
)

/**
 * ProblemsScreen lists the note files that cannot be read (Settings, the Library tab, Problems): each
 * file's name, the reason in the interface language and the technical detail. The files are left
 * unchanged, as on the desktop. A note whose body is outside the vocabulary offers a normalised
 * copy after a confirmation (desktop spec section 3.3) and, as a library on the phone cannot be
 * reached otherwise, the permanent deletion of the original after another.
 *
 * @param problems The problems, or null while they are read.
 * @param readOnly Whether the library refuses writes, which offers neither action.
 * @param snackbar Shows the result of an action.
 * @param onNormalize Creates the copy of a problem's note.
 * @param onDelete Deletes a problem's note permanently.
 * @param onBack Closes the screen.
 */
@Composable
fun ProblemsScreen(
    problems: List<Problem>?,
    readOnly: Boolean,
    snackbar: SnackbarHostState,
    onNormalize: suspend (Problem) -> Unit,
    onDelete: suspend (Problem) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf<Problem?>(null) }
    var deleting by remember { mutableStateOf<Problem?>(null) }
    var busy by remember { mutableStateOf(false) }
    deleting?.let { problem ->
        ConfirmDialog(
            title = stringResource(R.string.problems_delete_action),
            text = stringResource(R.string.problems_delete_confirm),
            confirmText = stringResource(UiR.string.delete),
            onConfirm = {
                deleting = null
                busy = true
                scope.launch {
                    onDelete(problem)
                    busy = false
                }
            },
            onDismiss = { deleting = null },
            destructive = true,
        )
    }
    confirming?.let { problem ->
        ConfirmDialog(
            title = stringResource(R.string.problems_copy_action),
            text = stringResource(R.string.problems_copy_confirm),
            confirmText = stringResource(R.string.problems_copy_action),
            onConfirm = {
                confirming = null
                busy = true
                scope.launch {
                    onNormalize(problem)
                    busy = false
                }
            },
            onDismiss = { confirming = null },
        )
    }
    when {
        problems == null -> Scaffold(topBar = { AppTopBar(stringResource(R.string.library_problems), onBack = onBack) }) {
            LoadingState(Modifier.padding(it))
        }
        problems.isEmpty() -> Scaffold(topBar = { AppTopBar(stringResource(R.string.library_problems), onBack = onBack) }) {
            EmptyState(AppIcons.StatusOk, stringResource(R.string.problems_empty_title), stringResource(R.string.problems_empty_text), Modifier.padding(it))
        }
        else -> SectionScreen(stringResource(R.string.library_problems), onBack, snackbar = snackbar) {
            item {
                Text(stringResource(R.string.problems_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (problem in problems) {
                item(key = problem.fileName) {
                    SettingsSection(problem.fileName) {
                        Text(problemReason(problem.code), style = MaterialTheme.typography.bodyMedium)
                        if (problem.detail.isNotEmpty()) {
                            SelectionContainer {
                                Text(problem.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!readOnly && problem.detail.startsWith(UnsupportedBody)) {
                            ProgressButton(stringResource(R.string.problems_copy_action), { confirming = problem }, busy = busy, enabled = !busy)
                            SecondaryButton(stringResource(R.string.problems_delete_action), { deleting = problem }, enabled = !busy, icon = AppIcons.Delete)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A component of the application with its licence, as About → Licences lists it.
 *
 * @param name The component's name.
 * @param license The licence's name.
 * @param file The licence's text in `assets/licenses/`.
 */
class Component(@StringRes val name: Int, @StringRes val license: Int, val file: String)

// The Apache 2.0 text, shipped once for every component under it.
private const val Apache = "Apache-2.0.txt"

/**
 * The components the release build ships, after R8 has removed the unused code, whose licences require
 * their notice: neither the design system's custom glyphs, the work of Jotter's own author, nor
 * SQLite, which is in the public domain. Dependencies R8 removes entirely (Okio, atomicfu, JSpecify)
 * are not listed.
 */
val Components = listOf(
    Component(R.string.component_bootstrap, R.string.license_mit, "Bootstrap-Icons-MIT.txt"),
    Component(R.string.component_androidx, R.string.license_apache, Apache),
    Component(R.string.component_kotlin, R.string.license_apache, Apache),
    Component(R.string.component_protobuf, R.string.license_bsd, "Protocol-Buffers-BSD-3-Clause.txt"),
    Component(R.string.component_snowball, R.string.license_bsd, "Snowball-BSD-3-Clause.txt"),
    Component(R.string.component_sshj, R.string.license_apache, Apache),
    Component(R.string.component_slf4j, R.string.license_mit, "SLF4J-MIT.txt"),
    Component(R.string.component_bouncycastle, R.string.license_mit, "Bouncy-Castle-MIT.txt"),
    Component(R.string.component_ksyntaxhighlighting, R.string.license_ksyntaxhighlighting, "KSyntaxHighlighting.txt"),
)

/** Where the privacy policy is published; Google Play requires a link to it inside the application. */
private const val PrivacyPolicyUrl = "https://github.com/nir0k/jotter-android/blob/main/PRIVACY.md"

/**
 * AboutScreen shows the application's version, opens the licences of its components and opens the
 * privacy policy in the browser; without a browser it says so in a snackbar.
 *
 * @param version The application's version.
 * @param onOpenLicenses Opens the licences.
 * @param onBack Closes the screen.
 */
@Composable
fun AboutScreen(version: String, onOpenLicenses: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val noBrowser = stringResource(R.string.about_no_browser)
    SectionScreen(stringResource(R.string.settings_about), onBack, snackbar = snackbar) {
        item {
            SettingsSection(null) {
                ValueRow(stringResource(R.string.about_version), version)
                NavigationRow(stringResource(R.string.about_licenses), onOpenLicenses, icon = AppIcons.Document)
                NavigationRow(
                    stringResource(R.string.about_privacy_policy),
                    {
                        if (!startActivity(context, Intent(Intent.ACTION_VIEW, PrivacyPolicyUrl.toUri()))) {
                            scope.launch { snackbar.showSnackbar(noBrowser) }
                        }
                    },
                    icon = AppIcons.Lock,
                )
            }
        }
    }
}

/**
 * LibrariesScreen lists the libraries of this phone, each with its name and, smaller, when it last
 * changed; the open one carries a check and a tap opens another. Add creates a library, ⋮ holds
 * Rebuild index, and deleting the open library comes last, confirmed with its note count.
 *
 * @param libraries The libraries, or null while they are read.
 * @param notes The number of notes of the open library, the archive and the trash included.
 * @param busy Whether a library action runs, which disables the others.
 * @param onSwitch Opens a library.
 * @param onCreate Creates a library and opens it.
 * @param onRebuild Rebuilds the index of the open library.
 * @param onDelete Deletes the open library from this phone.
 * @param snackbar Messages of the library actions.
 * @param onBack Closes the screen.
 */
@Composable
fun LibrariesScreen(
    libraries: List<LibraryEntry>?,
    notes: Int,
    busy: Boolean,
    onSwitch: (String) -> Unit,
    onCreate: () -> Unit,
    onRebuild: () -> Unit,
    onDelete: () -> Unit,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    var deleting by remember { mutableStateOf(false) }
    SectionScreen(
        stringResource(R.string.libraries_title),
        onBack,
        menu = listOf(MenuAction(stringResource(R.string.library_rebuild), AppIcons.Refresh) { if (!busy) onRebuild() }),
        snackbar = snackbar,
        add = { AddButton(stringResource(R.string.library_create), { if (!busy) onCreate() }) },
    ) {
        if (libraries == null) {
            item { LoadingState() }
            return@SectionScreen
        }
        item {
            SettingsSection(null) {
                for (library in libraries) {
                    NavigationRow(
                        libraryLabel(library),
                        { if (!busy && !library.open) onSwitch(library.id) },
                        icon = AppIcons.Storage,
                        subtitle = libraryChanged(library),
                        trailing = if (library.open) AppIcons.Check else AppIcons.ChevronRight,
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SectionFieldGap)) {
                Text(stringResource(R.string.library_delete_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DangerButton(stringResource(R.string.library_delete), { deleting = true }, Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
    }
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.library_delete_title),
            text = stringResource(R.string.library_delete_confirm, pluralStringResource(R.plurals.notes_count, notes, notes)),
            confirmText = stringResource(R.string.library_delete_action),
            onConfirm = {
                deleting = false
                onDelete()
            },
            onDismiss = { deleting = false },
            destructive = true,
        )
    }
}

/**
 * LicensesScreen lists the components with their licences; a row opens the licence's text.
 *
 * @param onOpen Opens a component's licence.
 * @param onBack Closes the screen.
 */
@Composable
fun LicensesScreen(onOpen: (Component) -> Unit, onBack: () -> Unit) {
    SectionScreen(stringResource(R.string.about_licenses), onBack) {
        item {
            SettingsSection(null) {
                for (component in Components) NavigationRow(stringResource(component.name), { onOpen(component) }, value = stringResource(component.license))
            }
        }
    }
}

/**
 * LicenseTextScreen shows the text of one licence, read from the application's assets.
 *
 * @param component The component whose licence shows.
 * @param onBack Closes the screen.
 */
@Composable
fun LicenseTextScreen(component: Component, onBack: () -> Unit) {
    val context = LocalContext.current
    val text by produceState<String?>(null, component.file) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open("licenses/${component.file}").bufferedReader().use { it.readText() }
            } catch (_: IOException) {
                ""
            }
        }
    }
    Scaffold(topBar = { AppTopBar(stringResource(component.name), onBack = onBack) }) { padding ->
        val shown = text
        if (shown == null) {
            LoadingState(Modifier.padding(padding))
        } else {
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding)) {
                SelectionContainer { Text(shown, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
