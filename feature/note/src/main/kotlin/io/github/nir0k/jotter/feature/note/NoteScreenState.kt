package io.github.nir0k.jotter.feature.note

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import io.github.nir0k.jotter.editor.EditorSelection

/**
 * What a note's screen keeps in the saved state: the find bar and its query, the open sheet or
 * dialog, the camera's photo and the file being saved (which outlive the activity the system may
 * recreate while another application is in front), the cursor and the body's focus (which outlive
 * the editor a rotation creates anew) and how far the body has scrolled.
 */
internal class NoteScreenState(
    find: Boolean = false,
    findQuery: String = "",
    info: Boolean = false,
    dialog: NoteDialog? = null,
    cameraPath: String? = null,
    savingFile: String? = null,
    selection: EditorSelection? = null,
    bodyFocused: Boolean = false,
    scrolled: Int = 0,
) {
    var find by mutableStateOf(find)
    var findQuery by mutableStateOf(findQuery)
    var info by mutableStateOf(info)
    var dialog by mutableStateOf(dialog)
    var cameraPath by mutableStateOf(cameraPath)
    var savingFile by mutableStateOf(savingFile)
    var selection by mutableStateOf(selection)
    var bodyFocused by mutableStateOf(bodyFocused)
    val scroll = ScrollState(scrolled)

    /** Returns the state as values a saved-state bundle holds, in the order [restore] reads them. */
    fun save(): ArrayList<Any?> = arrayListOf(
        find, findQuery, info, dialog?.name, cameraPath, savingFile,
        selection?.let { intArrayOf(it.anchor, it.head) }, bodyFocused, scroll.value,
    )

    companion object {
        /**
         * Reads a state that [save] wrote.
         *
         * @return The state, or null when the values are not of that form, as after an update that
         *   changed it; the screen then starts afresh.
         */
        fun restore(saved: Any?): NoteScreenState? {
            val values = saved as? List<*> ?: return null
            if (values.size != 9) return null
            return runCatching {
                NoteScreenState(
                    find = values[0] as Boolean,
                    findQuery = values[1] as String,
                    info = values[2] as Boolean,
                    dialog = (values[3] as String?)?.let { name -> NoteDialog.entries.firstOrNull { it.name == name } },
                    cameraPath = values[4] as String?,
                    savingFile = values[5] as String?,
                    selection = (values[6] as IntArray?)?.let { EditorSelection(it[0], it[1]) },
                    bodyFocused = values[7] as Boolean,
                    scrolled = values[8] as Int,
                )
            }.getOrNull()
        }
    }
}

/** The key of a note screen's state in the saved state of its note's route. */
private const val NoteScreenStateKey = "NoteScreenState"

/**
 * Returns the saved state of the screen of a note, made afresh for another note.
 *
 * It is kept under a fixed key of the saved state around the screen, which the navigation gives
 * each note's route its own of, rather than under the screen's position in the composition: a
 * rotation between a narrow and a wide window shows the note full screen or in a pane beside the
 * list, in different places of the composition, and the note keeps its cursor, focus and dialogs
 * across it. Only one note screen stands in such a saved state.
 *
 * @param id The note's identifier.
 */
@Composable
internal fun rememberNoteScreenState(id: String): NoteScreenState {
    val registry = LocalSaveableStateRegistry.current
    val state = remember(registry, id) {
        registry?.consumeRestored(NoteScreenStateKey)?.let(NoteScreenState::restore) ?: NoteScreenState()
    }
    DisposableEffect(registry, state) {
        val entry = registry?.registerProvider(NoteScreenStateKey) { state.save() }
        onDispose { entry?.unregister() }
    }
    return state
}
