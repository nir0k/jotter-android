package io.github.nir0k.jotter.feature.note

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.github.nir0k.jotter.designsystem.components.ConfirmDialog

/**
 * NoteLeave guards whatever takes an open note off the screen: the note saves first, and when
 * that fails the person is warned and chooses to stay, with the text kept, or to leave without
 * saving. Without an open note a request goes through at once.
 */
class NoteLeave {
    /** The editor of the note on the screen, or null. */
    var editing: NoteEditing? = null

    /** What the person asked for while the note could not be saved, waiting for their choice. */
    var pending by mutableStateOf<(() -> Unit)?>(null)
        private set

    /**
     * Runs an action that takes the note off the screen, once the note is saved.
     *
     * @param scope The screen's scope, in which the note saves.
     * @param action What the person asked for.
     */
    fun request(scope: CoroutineScope, action: () -> Unit) {
        val note = editing
        if (note == null) {
            action()
            return
        }
        scope.launch { if (note.flush()) action() else pending = action }
    }

    /** Stays on the note, which keeps the text and offers to retry. */
    fun stay() {
        pending = null
    }

    /** Gives up the unsaved text and does what was asked, after the person confirmed it. */
    fun leaveWithoutSaving() {
        val action = pending
        pending = null
        editing?.abandon()
        action?.invoke()
    }
}

/** Warns that the note could not be saved and asks whether to leave without saving. */
@Composable
fun NoteLeaveDialog(leave: NoteLeave) {
    if (leave.pending == null) return
    ConfirmDialog(
        title = stringResource(R.string.note_leave_unsaved_title),
        text = stringResource(R.string.note_leave_unsaved_text),
        confirmText = stringResource(R.string.note_leave_unsaved_confirm),
        onConfirm = leave::leaveWithoutSaving,
        onDismiss = leave::stay,
        destructive = true,
    )
}
