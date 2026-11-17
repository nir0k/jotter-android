package io.github.nir0k.jotter.capture

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nir0k.jotter.JotterApplication
import io.github.nir0k.jotter.MainActivity
import io.github.nir0k.jotter.core.format.FavoriteRef
import io.github.nir0k.jotter.data.library.LibraryState
import io.github.nir0k.jotter.feature.note.NoteDetail
import io.github.nir0k.jotter.feature.note.NoteLeave
import io.github.nir0k.jotter.feature.note.NoteLeaveDialog
import io.github.nir0k.jotter.ui.collectionChoices
import io.github.nir0k.jotter.feature.notes.rememberLibraryLists
import io.github.nir0k.jotter.designsystem.components.AppTopBar
import io.github.nir0k.jotter.designsystem.components.EmptyState
import io.github.nir0k.jotter.designsystem.components.ErrorState
import io.github.nir0k.jotter.designsystem.components.LoadingState
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.AppTheme
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.theme.ThemePalette
import io.github.nir0k.jotter.R

/**
 * CaptureActivity is the capture screen: the note alone, opened by the app shortcut, the Quick Settings tile and the share sheet. Shared content opens
 * over the sharing application, so Back returns there; the shortcut and the tile open it in a task of its own, outside the recent screens. It shares the
 * library with the main screen, which it leaves as it was.
 */
class CaptureActivity : ComponentActivity() {
    private val model: CaptureModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as JotterApplication
        // Read before the activity is set up, so the stored language applies to it.
        app.initialAppearance
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val request = captureRequest(intent)
        model.start(request)
        setContent { CaptureApp(app, model, request, ::openInJotter, ::finish) }
    }

    /** Opens the main screen of Jotter, on a note when given, and closes the capture screen. */
    private fun openInJotter(noteId: String?) {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        noteId?.let { intent.putExtra(MainActivity.OpenNoteExtra, it) }
        startActivity(intent)
        finish()
    }
}

/**
 * CaptureApp wears the stored appearance and shows the capture: the progress of copying shared
 * files, the new note in the editor, or why no note could be created.
 *
 * @param openInJotter Opens the main screen, on a note when given.
 * @param close Closes the capture screen.
 */
@Composable
private fun CaptureApp(app: JotterApplication, model: CaptureModel, request: CaptureRequest, openInJotter: (String?) -> Unit, close: () -> Unit) {
    val appearance by app.preferences.appearance.collectAsStateWithLifecycle(app.initialAppearance)
    val palette = ThemePalette.resolve(appearance.paletteId)
    AppTheme(appearance.mode, palette) {
        val library by app.library.current.collectAsStateWithLifecycle()
        val open = library as? LibraryState.Open
        when (val state = model.state) {
            is CaptureState.Ready -> if (open != null) {
                CaptureNote(app, open, state, palette.id == ThemePalette.Monokai.id, openInJotter, close)
            } else {
                CaptureStatus(CaptureState.Working(0, 0), {}, {}, close)
            }
            else -> CaptureStatus(state, { model.retry(request) }, { openInJotter(null) }, close)
        }
    }
}

/**
 * CaptureStatus is the capture screen before its note opens: the progress of copying shared
 * files, or why no note could be created.
 *
 * @param state What to show; not [CaptureState.Ready].
 * @param onRetry Tries again after a failure.
 * @param onOpenJotter Opens the main screen.
 * @param onClose Closes the screen.
 */
@Composable
fun CaptureStatus(state: CaptureState, onRetry: () -> Unit, onOpenJotter: () -> Unit, onClose: () -> Unit) {
    Scaffold(topBar = { AppTopBar(stringResource(R.string.capture_title), onBack = onClose) }) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when (state) {
            is CaptureState.Working -> if (state.total == 0) LoadingState(modifier) else Copying(state.done, state.total, modifier)
            CaptureState.NoLibrary -> EmptyState(
                AppIcons.Note, stringResource(R.string.capture_no_library_title), stringResource(R.string.capture_no_library_text), modifier,
                actionText = stringResource(R.string.capture_open_jotter), onAction = onOpenJotter,
            )
            CaptureState.ReadOnly -> EmptyState(
                AppIcons.Note, stringResource(R.string.capture_read_only_title), stringResource(R.string.capture_read_only_text), modifier,
                actionText = stringResource(R.string.capture_open_jotter), onAction = onOpenJotter,
            )
            CaptureState.Failed -> ErrorState(stringResource(R.string.capture_failed), onRetry, modifier)
            is CaptureState.Ready -> LoadingState(modifier)
        }
    }
}

/** Tells how many of the shared files are copied. */
@Composable
private fun Copying(done: Int, total: Int, modifier: Modifier) {
    Column(
        modifier.padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.CardInnerGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.capture_copying, done + 1, total), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The captured note in the editor, as the note screen shows it; leaving it, archiving or trashing it
 * closes the screen, and a link to another note opens that note in Jotter.
 */
@Composable
private fun CaptureNote(app: JotterApplication, open: LibraryState.Open, state: CaptureState.Ready, monokai: Boolean, openInJotter: (String?) -> Unit, close: () -> Unit) {
    val addLocation by app.preferences.addLocation.collectAsStateWithLifecycle(false)
    val lists = rememberLibraryLists(open)
    val choices = remember(lists) { collectionChoices(lists.collections) }
    var announced by rememberSaveable { mutableStateOf(false) }
    val id = state.created.id
    // Leaving the screen, also by the system's Back, first asks the note to save.
    val scope = rememberCoroutineScope()
    val leave = remember { NoteLeave() }
    val leaveScreen: () -> Unit = { leave.request(scope, close) }
    BackHandler(onBack = leaveScreen)
    NoteLeaveDialog(leave)
    NoteDetail(
        open, app.library, id, leaveScreen, onOpenNote = { note -> leave.request(scope) { openInJotter(note) } }, monokai, lists.tags, lists.collections, addLocation,
        favorite = FavoriteRef(FavoriteRef.Kind.Note, id) in open.library.info.favorites,
        collectionChoices = choices,
        onLeft = { _, _ -> close() },
        created = state.created.takeIf { !announced },
        onCreatedShown = { announced = true },
        leave = leave,
    )
}
