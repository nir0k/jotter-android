package io.github.nir0k.jotter.capture

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.nir0k.jotter.JotterApplication
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.OpenedFileLimit
import io.github.nir0k.jotter.core.format.OpenedNote
import io.github.nir0k.jotter.core.format.StoreAs
import io.github.nir0k.jotter.core.format.StoredFile
import io.github.nir0k.jotter.core.format.capturedBody
import io.github.nir0k.jotter.core.format.isMarkdownFile
import io.github.nir0k.jotter.core.format.isSafeFileName
import io.github.nir0k.jotter.core.format.isValidId
import io.github.nir0k.jotter.core.format.newId
import io.github.nir0k.jotter.core.format.openedNote
import io.github.nir0k.jotter.core.format.openedText
import io.github.nir0k.jotter.core.format.removePath
import io.github.nir0k.jotter.data.library.CreatedNote
import io.github.nir0k.jotter.data.library.LibraryState
import io.github.nir0k.jotter.data.location.currentLocation
import io.github.nir0k.jotter.data.location.hasLocationPermission
import io.github.nir0k.jotter.editor.typedLanguage
import io.github.nir0k.jotter.ui.fileAuthority
import io.github.nir0k.jotter.ui.incomingDocuments
import io.github.nir0k.jotter.ui.storeIncoming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** The action of the app shortcut and the Quick Settings tile: a new, empty note. */
const val NewNoteAction = "io.github.nir0k.jotter.action.NEW_NOTE"

/**
 * CaptureRequest is what a captured note starts with: nothing from the shortcut and the tile, what
 * another application shared, or the text file it opened in Jotter.
 *
 * @param title The shared subject, such as a web page's title.
 * @param text The shared text.
 * @param files The shared files; only `content:` addresses, so no application can hand over a
 *   file of Jotter's own private folders by its path.
 * @param opened The text file opened in Jotter, whose content becomes the note; a `content:` address.
 * @param openedType The media type the opening application gave the file, if any.
 */
data class CaptureRequest(
    val title: String = "",
    val text: String = "",
    val files: List<Uri> = emptyList(),
    val opened: Uri? = null,
    val openedType: String? = null,
)

/**
 * Reads what an intent brings: the subject, text and files it shares, from the extras or the clip,
 * or the file it opens.
 */
fun captureRequest(intent: Intent): CaptureRequest {
    if (intent.action == Intent.ACTION_VIEW) {
        val uri = intent.data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT } ?: return CaptureRequest()
        return CaptureRequest(opened = uri, openedType = intent.type)
    }
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return CaptureRequest()
    val streams = if (intent.action == Intent.ACTION_SEND) {
        listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
    } else {
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    }
    val clip = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
    return CaptureRequest(
        title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.lines()?.joinToString(" ")?.trim().orEmpty(),
        text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
        files = (streams + clip).distinct().filter { it.scheme == ContentResolver.SCHEME_CONTENT },
    )
}

/**
 * Tells whether a shared file may be copied into a note of the open library. Another application's
 * address is read with the access it granted. An address of Jotter's own `FileProvider`, which
 * Jotter can read whoever sends it, is taken only for an attachment of the open library, as when
 * one is shared from Jotter itself: another application must not make Jotter copy a file of
 * another library, which would then reach this library's sync storage.
 *
 * @param ownAuthority The authority of Jotter's own `FileProvider`.
 * @param libraryId The open library.
 */
internal fun isCapturable(uri: Uri, ownAuthority: String, libraryId: String): Boolean {
    if (uri.authority != ownAuthority) return true
    // `libraries/<library>/attachments/<note>/<file>`, as the provider names a file of the libraries.
    val parts = uri.pathSegments
    return parts.size == 5 && parts[0] == "libraries" && parts[1] == libraryId && parts[2] == "attachments" &&
        isValidId(parts[3]) && isSafeFileName(parts[4])
}

/** CaptureState is what the capture screen shows. */
sealed interface CaptureState {
    /** The library is being opened, or the shared files are copied; [total] is 0 without files. */
    data class Working(val done: Int, val total: Int) : CaptureState

    /** The note exists and opens in the editor. */
    data class Ready(val created: CreatedNote) : CaptureState

    /** This device has no library yet; nothing was created. */
    data object NoLibrary : CaptureState

    /** The library is of a newer format and cannot take notes. */
    data object ReadOnly : CaptureState

    /** The opened file does not make a note; nothing was created. */
    data class Unreadable(val problem: FileProblem) : CaptureState

    /** The library could not be opened, the opened file read or the note written. */
    data object Failed : CaptureState
}

/** Why an opened file does not make a note. */
enum class FileProblem {
    /** It is larger than [OpenedFileLimit]. */
    TooLarge,

    /** It is not text in UTF-8. */
    NotText,
}

/** What reading an opened file gave: its note, or why it makes none. */
private sealed interface OpenedFile {
    /** The note the file makes. */
    data class Read(val note: OpenedNote) : OpenedFile

    /** The file makes no note. */
    data class Problem(val problem: FileProblem) : OpenedFile
}

/** The key under which the screen keeps the created note, so a recreated screen does not create it again. */
private const val NoteKey = "note"

/**
 * CaptureModel creates the note of a capture and keeps it across rotation: it waits for the
 * library, copies the shared files into the note's folder one by one with their progress, or reads
 * the opened text file, builds the body in the desktop's form and writes the note, filed by the
 * rules. Leaving while files are copied removes what was copied.
 */
class CaptureModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    /** What the screen shows. */
    var state by mutableStateOf<CaptureState>(CaptureState.Working(0, 0))
        private set

    private var started = false

    /** Creates the note of [request] once; a recreated screen shows the note it created. */
    fun start(request: CaptureRequest) {
        if (started) return
        started = true
        saved.get<String>(NoteKey)?.let {
            state = CaptureState.Ready(CreatedNote(it, null))
            return
        }
        viewModelScope.launch { state = capture(request) }
    }

    /** Tries again after a failure. */
    fun retry(request: CaptureRequest) {
        started = false
        state = CaptureState.Working(0, 0)
        start(request)
    }

    private suspend fun capture(request: CaptureRequest): CaptureState {
        val app = getApplication<JotterApplication>()
        val open = when (val current = app.library.current.first { it !is LibraryState.Opening }) {
            is LibraryState.Open -> current
            is LibraryState.None -> return CaptureState.NoLibrary
            else -> return CaptureState.Failed
        }
        if (open.library.isReadOnly) return CaptureState.ReadOnly
        val id = newId()
        request.opened?.let { return captureOpened(app, open, id, it, request.openedType) }
        val dir = open.library.attachmentsDir(id)
        var written = false
        try {
            val stored = mutableListOf<StoredFile>()
            val failed = mutableListOf<Pair<String, ErrorCode>>()
            val libraryId = open.library.root.fileName.toString()
            val shared = request.files.filter { isCapturable(it, fileAuthority(app), libraryId) }
            withContext(Dispatchers.IO) {
                val files = incomingDocuments(app, shared)
                files.forEachIndexed { i, file ->
                    withContext(Dispatchers.Main) { state = CaptureState.Working(i, files.size) }
                    val result = storeIncoming(dir, listOf(file), StoreAs.Image)
                    stored += result.stored
                    failed += result.failed
                }
            }
            // Once the files are copied, the note is written even if the screen is left meanwhile.
            val created = withContext(NonCancellable) {
                app.library.createNote(emptyList(), emptyList(), request.title, capturedBody(request.text, stored, id), id)
            }
            if (created == null) {
                withContext(Dispatchers.IO) { runCatching { removePath(dir) } }
                return CaptureState.Failed
            }
            written = true
            return ready(app, id, created.copy(failed = failed))
        } catch (e: CancellationException) {
            // The screen was left while the files were copied, before the note was written.
            if (!written) withContext(Dispatchers.IO + NonCancellable) { runCatching { removePath(dir) } }
            throw e
        }
    }

    /**
     * Creates the note of a text file opened in Jotter: reads at most [OpenedFileLimit] bytes of it
     * as UTF-8 and builds the note, Markdown formatted (see [openedNote]).
     */
    private suspend fun captureOpened(app: JotterApplication, open: LibraryState.Open, id: String, uri: Uri, type: String?): CaptureState {
        if (!isCapturable(uri, fileAuthority(app), open.library.root.fileName.toString())) return CaptureState.Failed
        val file = try {
            withContext(Dispatchers.IO) { readOpened(app, uri, type) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The provider refused or lost the file; Retry reads it again.
            return CaptureState.Failed
        }
        val note = when (file) {
            is OpenedFile.Problem -> return CaptureState.Unreadable(file.problem)
            is OpenedFile.Read -> file.note
        }
        val created = withContext(NonCancellable) { app.library.createNote(emptyList(), emptyList(), note.title, note.body, id) }
            ?: return CaptureState.Failed
        return ready(app, id, created)
    }

    /** Remembers the created note for a recreated screen, adds its geotag when they are on and shows it. */
    private suspend fun ready(app: JotterApplication, id: String, note: CreatedNote): CaptureState {
        saved[NoteKey] = id
        if (app.preferences.addLocation.first() && hasLocationPermission(app)) app.library.locateNote(id) { currentLocation(app) }
        return CaptureState.Ready(note)
    }
}

/**
 * Reads a text file opened in Jotter and builds its note; called off the main thread.
 *
 * @param type The media type the opening application gave, otherwise the provider's.
 * @throws java.io.IOException When the file cannot be read.
 * @throws SecurityException When the provider refuses access to it.
 */
private fun readOpened(app: Application, uri: Uri, type: String?): OpenedFile {
    val file = incomingDocuments(app, listOf(uri)).single()
    val bytes = (file.open() ?: throw IOException("The provider returned no stream for $uri")).use { it.readNBytes(OpenedFileLimit + 1) }
    if (bytes.size > OpenedFileLimit) return OpenedFile.Problem(FileProblem.TooLarge)
    val text = openedText(bytes) ?: return OpenedFile.Problem(FileProblem.NotText)
    val markdown = isMarkdownFile(file.name, type ?: app.contentResolver.getType(uri))
    return OpenedFile.Read(openedNote(file.name, text, markdown, ::typedLanguage))
}
