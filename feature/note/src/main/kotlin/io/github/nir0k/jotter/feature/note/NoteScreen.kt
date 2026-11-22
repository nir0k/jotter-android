package io.github.nir0k.jotter.feature.note

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.offset
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.StoreAs
import io.github.nir0k.jotter.core.format.parseAttachmentSource
import io.github.nir0k.jotter.core.body.Container
import io.github.nir0k.jotter.core.body.InsertedFile
import io.github.nir0k.jotter.core.body.StoreRequest
import io.github.nir0k.jotter.core.format.ImageKind
import io.github.nir0k.jotter.core.format.StoredFile
import io.github.nir0k.jotter.editor.CodeLanguages
import io.github.nir0k.jotter.editor.CodeRef
import io.github.nir0k.jotter.editor.EditorHost
import io.github.nir0k.jotter.editor.EditorStrings
import io.github.nir0k.jotter.editor.NoteAssets
import io.github.nir0k.jotter.editor.NoteColors
import io.github.nir0k.jotter.editor.NoteEditor
import io.github.nir0k.jotter.editor.NoteEditorController
import io.github.nir0k.jotter.editor.NoteImages
import io.github.nir0k.jotter.editor.typedLanguage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.core.format.Collection
import io.github.nir0k.jotter.core.format.FavoriteRef
import io.github.nir0k.jotter.core.format.NoteMeta
import io.github.nir0k.jotter.core.format.now
import io.github.nir0k.jotter.ui.ExportDialog
import androidx.compose.ui.res.pluralStringResource
import io.github.nir0k.jotter.designsystem.components.Choice
import io.github.nir0k.jotter.designsystem.components.ColourPickerDialog
import io.github.nir0k.jotter.designsystem.components.ConfirmDialog
import io.github.nir0k.jotter.designsystem.components.FindBar
import io.github.nir0k.jotter.designsystem.components.IconPickerDialog
import io.github.nir0k.jotter.designsystem.components.MultiChoiceDialog
import io.github.nir0k.jotter.core.format.noteLinkTarget
import io.github.nir0k.jotter.core.index.NoteSummary
import io.github.nir0k.jotter.core.organize.RuleChange
import io.github.nir0k.jotter.data.library.CreatedNote
import io.github.nir0k.jotter.ui.rulesFiledMessage
import io.github.nir0k.jotter.data.location.currentLocation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.withContext
import io.github.nir0k.jotter.data.library.LibrarySession
import io.github.nir0k.jotter.data.library.LibraryState
import io.github.nir0k.jotter.core.format.tagColor
import io.github.nir0k.jotter.data.sync.conflictTitle
import io.github.nir0k.jotter.ui.theme.itemTextColor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.github.nir0k.jotter.designsystem.components.ErrorState
import io.github.nir0k.jotter.designsystem.components.LoadingState
import io.github.nir0k.jotter.designsystem.components.MenuAction
import io.github.nir0k.jotter.designsystem.components.StatusBanner
import io.github.nir0k.jotter.designsystem.components.Tag
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.ChosenColor
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.ui.IncomingFile
import io.github.nir0k.jotter.ui.storeIncoming
import io.github.nir0k.jotter.ui.incomingDocuments
import io.github.nir0k.jotter.ui.cameraFile
import io.github.nir0k.jotter.ui.contentUri
import io.github.nir0k.jotter.ui.storePasted
import io.github.nir0k.jotter.ui.startActivity
import io.github.nir0k.jotter.ui.shareFile
import io.github.nir0k.jotter.ui.openFile
import io.github.nir0k.jotter.ui.saveFileCopy
import io.github.nir0k.jotter.data.R as DataR
import io.github.nir0k.jotter.ui.R as UiR
import androidx.core.net.toUri

/**
 * NoteScreen is the detail screen of a note with one aspect: its top bar, a banner for a note that
 * cannot be edited, what the note shows above its body (notices and the find bar), the body, and the
 * formatting bar above the keyboard.
 *
 * @param title The note's title, or null while it loads.
 * @param banner Why the note cannot be edited, or null.
 * @param onBack Closes the screen; null in the detail pane of a wide window.
 * @param snackbar Transient messages, such as a file no application opens.
 * @param menu The note's ⋮ entries.
 * @param onTitle Receives the edited title; null for a note that cannot be edited.
 * @param titleFocus Focuses the title of a new note.
 * @param onTitleDone Moves on from the title to the body, for the keyboard's Next.
 * @param details The line of collections and tags over the top of the body, or null to hide it.
 * @param bodyScroll How far the body has scrolled, in pixels, which that line follows.
 * @param header What stands above the body.
 * @param toolbar The formatting bar, while the note is edited.
 * @param body The note's body, or its loading or error state.
 */
@Composable
fun NoteScreen(
    title: String?,
    banner: String?,
    onBack: (() -> Unit)?,
    snackbar: SnackbarHostState,
    menu: List<MenuAction> = emptyList(),
    onTitle: ((String) -> Unit)? = null,
    titleFocus: FocusRequester = remember { FocusRequester() },
    onTitleDone: () -> Unit = {},
    details: NoteDetails? = null,
    bodyScroll: () -> Int = { 0 },
    header: @Composable ColumnScope.() -> Unit = {},
    toolbar: @Composable () -> Unit = {},
    body: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = { NoteTopBar(title, onTitle, titleFocus, onBack, menu, onTitleDone) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The formatting bar rides on the keyboard; the body shrinks above both.
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            banner?.let { StatusBanner(text = it, icon = AppIcons.Lock) }
            header()
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                body(Modifier.fillMaxSize())
                details?.let { NoteDetailsBar(it, onBack != null, Modifier.offset { IntOffset(0, -bodyScroll()) }) }
            }
            toolbar()
        }
    }
}

/**
 * Returns the palette roles a note is drawn with in the editor.
 *
 * @param monokai Whether the Monokai palette is in use, whose code blocks use the Monokai theme.
 */
@Composable
fun noteColors(monokai: Boolean): NoteColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    return NoteColors(
        background = scheme.background.toArgb(),
        text = scheme.onSurface.toArgb(),
        muted = scheme.onSurfaceVariant.toArgb(),
        link = scheme.primary.toArgb(),
        raised = scheme.surfaceContainerHighest.toArgb(),
        outline = scheme.outline.toArgb(),
        edge = scheme.outlineVariant.toArgb(),
        primary = scheme.primary.toArgb(),
        onPrimary = scheme.onPrimary.toArgb(),
        dark = dark,
        codeTheme = when {
            monokai -> "monokai"
            dark -> "breeze-dark"
            else -> "breeze-light"
        },
    )
}


/** What closed a note from its own menu, for the list's message. */
enum class NoteOutcome { Archived, Trashed, Deleted }

/** The dialogs of the note's menu. */
internal enum class NoteDialog { Icon, Color, Collections, Tags, Export, Delete }

/** The most notes the note picker lists. */
private const val PickerLimit = 30

/** Tells whether the activity showing a context is being recreated, as on rotation. */
private fun Context.isChangingConfigurations(): Boolean {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.isChangingConfigurations
        current = current.baseContext
    }
    return false
}

/** Where `[[` or the cursor was when the note picker opened: the run and the brackets' offsets. */
private data class PickerTarget(val container: Container, val key: Long, val from: Int, val to: Int)

/**
 * NoteDetail opens a note of the open library in the editor: the title field, the formatting bar
 * above the keyboard, find from ⋮ or the keyboard, the dialogs of links and code blocks, the
 * pickers of images and attachments, and the full-screen viewer of the note's images. An archived
 * or trashed note and every note of a newer-format library are shown read-only, without the title
 * field. While the body is edited with the keyboard open, the title gives it its room.
 *
 * @param open The open library.
 * @param session The library's session, which writes the note.
 * @param id The note.
 * @param onBack Closes the screen; null in the detail pane of a wide window.
 * @param onOpenNote Opens a note a link of this one points to, or a conflict copy.
 * @param monokai Whether the Monokai palette is in use.
 * @param knownTags The library's tags, for the tags popup.
 * @param collections The library's collections, whose names the information sheet shows.
 * @param addLocation Whether geotags are on, which offers Add location for a note without one.
 * @param favorite Whether the note is a favourite.
 * @param collectionChoices The collections a note can join, with their paths.
 * @param leave The guard asked before the screen leaves the note, told of this note's editor.
 * @param onLeft Tells that the note was archived, trashed or deleted, which closes it; with the
 *   metadata before the change for Undo.
 * @param created The note as it was just created, whose rules and files left out are announced once.
 * @param onCreatedShown Tells that [created] was announced.
 */
@Composable
fun NoteDetail(
    open: LibraryState.Open,
    session: LibrarySession,
    id: String,
    onBack: (() -> Unit)?,
    onOpenNote: (String) -> Unit,
    monokai: Boolean,
    knownTags: List<String>,
    collections: List<Collection>,
    addLocation: Boolean,
    favorite: Boolean,
    collectionChoices: List<Choice<String>>,
    onLeft: (NoteOutcome, NoteMeta?) -> Unit,
    created: CreatedNote? = null,
    onCreatedShown: () -> Unit = {},
    leave: NoteLeave? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val editing = remember(open.library, id) { NoteEditing(session, open.library, id, context::conflictTitle) }
    DisposableEffect(editing, leave) {
        leave?.editing = editing
        onDispose { if (leave?.editing === editing) leave.editing = null }
    }
    val openNote by rememberUpdatedState(onOpenNote)
    val screenState = rememberNoteScreenState(id)
    var find by screenState::find
    var findQuery by screenState::findQuery
    var found by remember(editing) { mutableStateOf(0 to -1) }
    var link by remember(editing) { mutableStateOf<Pair<String, String>?>(null) }
    var languageFor by remember(editing) { mutableStateOf<CodeRef?>(null) }
    var titleFor by remember(editing) { mutableStateOf<CodeRef?>(null) }
    var viewer by remember(editing) { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var picker by remember(editing) { mutableStateOf<PickerTarget?>(null) }
    var info by screenState::info
    var dialog by screenState::dialog
    var cameraPath by screenState::cameraPath
    var savingFile by screenState::savingFile
    val assets = remember(open.library, id) { NoteAssets(open.library.root.toFile(), id) }
    var selection by screenState::selection
    var bodyFocused by screenState::bodyFocused
    val scroll = screenState.scroll
    val titleFocus = remember(editing) { FocusRequester() }
    val colors = noteColors(monokai)
    val strings = remember(resources) { EditorStrings.from(context) }
    val scope = rememberCoroutineScope()
    val images = remember(assets) { NoteImages(assets, scope) }
    val noApplication = stringResource(R.string.note_no_application)
    val pastedImage = stringResource(R.string.pasted_image)
    val notFound = stringResource(R.string.note_link_missing)
    val untitled = stringResource(DataR.string.note_untitled)
    // The note picker offers recent notes or matching titles, never this note.
    val linkCandidates: suspend (String) -> List<NoteSummary> = { query ->
        withContext(Dispatchers.IO) { runCatching { open.index.linkCandidates(query, id, PickerLimit) }.getOrDefault(emptyList()) }
    }

    LaunchedEffect(editing) { editing.read() }
    LaunchedEffect(editing) { editing.followLocation() }
    // A change of the library's files may be a change of this note from outside.
    LaunchedEffect(editing, open.revision) { editing.followOutsideChanges() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { session.background.launch(Dispatchers.Main) { editing.flush() } }
    LaunchedEffect(editing) {
        editing.conflicts.collect { copy ->
            val result = snackbar.showSnackbar(
                resources.getString(R.string.note_conflict_copy),
                actionLabel = resources.getString(UiR.string.sync_conflicts_open),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) openNote(copy)
        }
    }
    // What the rules did when the note was created or given other tags is announced with Undo, which
    // gives the note back its collections through the editor's own write.
    val announceFiled: suspend (RuleChange) -> Unit = { change ->
        val message = rulesFiledMessage(resources, listOf(change), collections, untitled)
        if (snackbar.showSnackbar(message, actionLabel = resources.getString(UiR.string.undo)) == SnackbarResult.ActionPerformed &&
            editing.changeMeta { it.copy(collections = change.before.collections) } == null
        ) {
            snackbar.showSnackbar(resources.getString(UiR.string.organize_failed))
        }
    }
    LaunchedEffect(editing) { editing.filed.collect { launch { announceFiled(it) } } }

    val note = (editing.load as? NoteLoad.Loaded)?.note
    val editable = editing.editable
    val loaded = note != null
    // The editor is made once the note is read; it takes the cursor of the editor before it.
    val controller = remember(editing, loaded) {
        if (loaded) NoteEditorController(id, editing.loadedBody, editable, ::typedLanguage, selection) else null
    }
    LaunchedEffect(controller, editable) { controller?.editable = editable }
    LaunchedEffect(editing, note != null) { if (note != null && editable && editing.isBlank && !bodyFocused) titleFocus.requestFocus() }

    val showMessage: (String) -> Unit = { text -> session.background.launch(Dispatchers.Main) { snackbar.showSnackbar(text) } }
    // A link to a note that no longer exists says so.
    val openLinkedNote: (String) -> Unit = { target ->
        session.background.launch(Dispatchers.Main) {
            val exists = withContext(Dispatchers.IO) { runCatching { target in open.index.existing(setOf(target)) }.getOrDefault(true) }
            if (exists) openNote(target) else snackbar.showSnackbar(notFound)
        }
    }
    val openHref: (String) -> Unit = { href ->
        val target = noteLinkTarget(href)
        if (target != null) openLinkedNote(target) else if (!startActivity(context, Intent(Intent.ACTION_VIEW, href.toUri()))) showMessage(noApplication)
    }
    val openAttachment: (String) -> Unit = { name ->
        val file = assets.attachment(name)?.file
        if (file == null || !openFile(context, file)) showMessage(noApplication)
    }
    val notAdded: (String, ErrorCode) -> Unit = { name, code ->
        showMessage(resources.getString(if (code == ErrorCode.TooLarge) R.string.file_too_large else R.string.file_not_added, name))
    }
    // Keyed on the editor only, so clearing the announcement does not cancel it.
    LaunchedEffect(editing) {
        created?.let {
            onCreatedShown()
            it.failed.forEach { (name, code) -> notAdded(name, code) }
            it.filed?.let { change -> announceFiled(change) }
        }
    }
    // Picked files are described and stored off the main thread and inserted at the cursor once
    // stored; then what they came from may go.
    val insert: (() -> List<IncomingFile>, StoreAs, () -> Unit) -> Unit = { files, storeAs, done ->
        session.background.launch(Dispatchers.IO) {
            val result = storeIncoming(open.library.attachmentsDir(id), files(), storeAs)
            done()
            launch(Dispatchers.Main) {
                if (result.stored.isNotEmpty()) controller?.insertFiles(result.stored.map(::insertedFile))
                result.failed.forEach { (name, code) -> notAdded(name, code) }
            }
        }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val photo = cameraPath?.let(::File)
        cameraPath = null
        if (photo != null && taken) {
            insert({ listOf(IncomingFile(photo.name) { photo.inputStream() }) }, StoreAs.Image) { photo.delete() }
        } else {
            photo?.delete()
        }
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) insert({ incomingDocuments(context, uris) }, StoreAs.Image) {}
    }
    val pickImageFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) insert({ incomingDocuments(context, uris) }, StoreAs.Image) {}
    }
    val pickAttachments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) insert({ incomingDocuments(context, uris) }, StoreAs.File) {}
    }
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { target ->
        val file = savingFile?.let { assets.attachment(it)?.file }
        savingFile = null
        if (target != null && file != null) {
            session.background.launch(Dispatchers.IO) {
                val saved = saveFileCopy(context, file, target)
                launch(Dispatchers.Main) { showMessage(resources.getString(if (saved) R.string.attachment_saved else R.string.attachment_save_failed)) }
            }
        }
    }
    val onInsert: (FileSource) -> Unit = { source ->
        try {
            when (source) {
                FileSource.Camera -> {
                    val photo = cameraFile(context)
                    cameraPath = photo.path
                    takePhoto.launch(contentUri(context, photo))
                }
                FileSource.Photos -> pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                FileSource.ImageFile -> pickImageFiles.launch(arrayOf("image/*"))
                FileSource.Attachment -> pickAttachments.launch(arrayOf("*/*"))
            }
        } catch (_: ActivityNotFoundException) {
            // A phone without a camera application, or with its document picker disabled.
            cameraPath = null
            showMessage(noApplication)
        }
    }
    val onFile: (FileAction, String) -> Unit = { action, name ->
        when (action) {
            FileAction.Open -> openAttachment(name)
            FileAction.Share -> assets.attachment(name)?.file?.let { if (!shareFile(context, it)) showMessage(noApplication) }
            FileAction.Save -> {
                savingFile = name
                try {
                    saveCopy.launch(name)
                } catch (_: ActivityNotFoundException) {
                    savingFile = null
                    showMessage(noApplication)
                }
            }
        }
    }
    // A paste brought a picture or another note's file; the editor holds its place until it is stored.
    val storePastedObject: (StoreRequest) -> Unit = { request ->
        session.background.launch(Dispatchers.IO) {
            val stored = try {
                storePasted(context, open.library, id, request.source, request.image, pastedImage)
            } catch (e: FormatException) {
                val name = parseAttachmentSource(request.source)?.fileName ?: pastedImage
                launch(Dispatchers.Main) { notAdded(name, e.code) }
                null
            }
            launch(Dispatchers.Main) { controller?.stored(request.token, stored?.let(::insertedFile)) }
        }
    }
    val host = object : EditorHost {
        override fun onChanged() = editing.bodyChanged()
        override fun onStore(request: StoreRequest) = storePastedObject(request)
        override fun onNotePicker(container: Container, key: Long, from: Int, to: Int) {
            picker = PickerTarget(container, key, from, to)
        }
        override fun onOpenLink(href: String) = openHref(href)
        override fun onOpenAttachment(file: String) = openAttachment(file)
        override fun onOpenImage(file: String, images: List<String>) {
            viewer = images to maxOf(0, images.indexOf(file))
        }
        override fun onLanguagePicker(code: CodeRef) {
            languageFor = code
        }
        override fun onTitleDialog(code: CodeRef) {
            titleFor = code
        }
        override fun onCopied(text: String) {
            // The system confirms a copy itself.
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, text))
        }
        override fun onLinkDialog(text: String, href: String) {
            link = text to href
        }
        override fun onFindBar() {
            find = true
        }
        override fun onFound(count: Int, current: Int) {
            found = count to current
        }
    }
    if (controller != null) {
        controller.host = host
        DisposableEffect(controller) {
            editing.attach(controller)
            if (bodyFocused) controller.requestFocus()
            // The note is saved before its editor goes; a note left empty is discarded, but not on rotation.
            onDispose { editing.close(discardEmpty = !context.isChangingConfigurations()) {} }
        }
        LaunchedEffect(controller) { snapshotFlow { controller.state.selection }.collect { selection = controller.savedSelection() } }
        LaunchedEffect(controller) { snapshotFlow { controller.focused }.collect { bodyFocused = it } }
        // Links to notes that no longer exist are drawn struck through.
        val targets = remember(controller.state.doc) { controller.linkTargets() }
        LaunchedEffect(controller, targets, open.revision) {
            if (targets.isEmpty()) {
                controller.missingLinks = emptySet()
                return@LaunchedEffect
            }
            val existing = withContext(Dispatchers.IO) { runCatching { open.index.existing(targets) }.getOrNull() } ?: return@LaunchedEffect
            controller.missingLinks = targets - existing
        }
    }

    val banner = when {
        open.library.isReadOnly -> stringResource(UiR.string.library_read_only)
        note?.meta?.trashed != null -> stringResource(R.string.note_in_trash)
        note?.meta?.archived != null -> stringResource(R.string.note_in_archive)
        else -> null
    }
    val failed = stringResource(UiR.string.organize_failed)
    val saveFailed = stringResource(R.string.note_save_failed)
    // Changes the note through its editor's write; a failure says so.
    val changeMeta: (Boolean, (NoteMeta) -> NoteMeta) -> Unit = { leaves, change ->
        session.background.launch(Dispatchers.Main) {
            val before = editing.changeMeta(change)
            if (before == null) snackbar.showSnackbar(failed)
            else if (leaves) onLeft(if (before.trashed == null && change(before).trashed != null) NoteOutcome.Trashed else NoteOutcome.Archived, before)
        }
    }
    val writable = note != null && !open.library.isReadOnly
    val trashed = note?.meta?.trashed != null
    val archived = note?.meta?.archived != null && !trashed
    val menu = if (note == null) emptyList() else listOfNotNull(
        MenuAction(stringResource(R.string.note_info), AppIcons.Info) { info = true },
        MenuAction(stringResource(if (favorite) UiR.string.note_unfavorite else UiR.string.note_favorite), if (favorite) AppIcons.FavoriteFilled else AppIcons.Favorite) {
            session.background.launch(Dispatchers.Main) {
                if (session.organize { it.setFavorite(listOf(FavoriteRef(FavoriteRef.Kind.Note, id)), !favorite) } == null) snackbar.showSnackbar(failed)
            }
        }.takeIf { writable && !trashed },
        MenuAction(stringResource(UiR.string.item_icon), AppIcons.Image) { dialog = NoteDialog.Icon }.takeIf { editable },
        MenuAction(stringResource(UiR.string.item_color), AppIcons.Palette) { dialog = NoteDialog.Color }.takeIf { editable },
        MenuAction(stringResource(R.string.note_find), AppIcons.Search) { find = true },
        MenuAction(stringResource(R.string.note_add_location), AppIcons.MyLocation) {
            session.locateNote(id) { currentLocation(context) }
        }.takeIf { addLocation && editable && note.meta.location == null },
        // What was just typed goes into the export, so the note is saved first.
        MenuAction(stringResource(UiR.string.note_export), AppIcons.Download) {
            session.background.launch(Dispatchers.Main) {
                if (editing.flush()) dialog = NoteDialog.Export else snackbar.showSnackbar(saveFailed)
            }
        }.takeIf { !trashed },
        MenuAction(stringResource(UiR.string.note_archive), AppIcons.Archive) { changeMeta(true) { it.copy(archived = now()) } }.takeIf { editable },
        MenuAction(stringResource(UiR.string.note_unarchive), AppIcons.Archive) { changeMeta(false) { it.copy(archived = null) } }.takeIf { writable && archived },
        MenuAction(stringResource(UiR.string.note_restore), AppIcons.Refresh) { changeMeta(false) { it.copy(trashed = null) } }.takeIf { writable && trashed },
        MenuAction(stringResource(UiR.string.note_trash), AppIcons.Delete, destructive = true) { changeMeta(true) { it.copy(trashed = now()) } }
            .takeIf { writable && !trashed },
        MenuAction(stringResource(UiR.string.note_delete), AppIcons.Delete, destructive = true) { dialog = NoteDialog.Delete }.takeIf { writable && trashed },
    )
    val collectionNames = remember(collections) { collections.associate { it.id to it.name } }
    val mutedTag = MaterialTheme.colorScheme.onSurfaceVariant
    val languageNames = remember { CodeLanguages.associate { it.key to it.name } }
    NoteScreen(
        title = note?.let { editing.title },
        banner = banner,
        onBack = onBack,
        snackbar = snackbar,
        menu = menu,
        onTitle = editing::changeTitle.takeIf { editable },
        titleFocus = titleFocus,
        onTitleDone = { controller?.focusStart() },
        bodyScroll = { scroll.value },
        details = note?.let {
            NoteDetails(
                it.meta.collections.mapNotNull(collectionNames::get),
                editing.tags.map { tag -> Tag(tag, ChosenColor.resolve(tagColor(open.library.info.tagColors, tag)) ?: mutedTag) },
                onCollections = { dialog = NoteDialog.Collections }.takeIf { editable },
                onTags = { dialog = NoteDialog.Tags }.takeIf { editable },
                tagsPopup = if (dialog == NoteDialog.Tags && editable) {
                    { inset -> NoteTagsPopup(inset, editing.tags, knownTags, open.library.info.tagColors, editing::changeTags) { dialog = null } }
                } else {
                    null
                },
            )
        },
        header = {
            if (editing.saveFailed) {
                StatusBanner(text = stringResource(R.string.note_save_failed), icon = AppIcons.Error, actionText = stringResource(UiR.string.sync_retry), onAction = editing::retry)
            }
            if (editing.deleted) {
                StatusBanner(text = stringResource(R.string.note_deleted), icon = AppIcons.Warning, actionText = stringResource(R.string.note_keep), onAction = editing::keep)
            }
            if (find) {
                // Find only: Replace is not offered.
                FindBar(
                    query = findQuery,
                    onQueryChange = {
                        findQuery = it
                        controller?.setFindQuery(it)
                    },
                    current = found.second + 1,
                    total = found.first,
                    onNext = { controller?.stepFind(1) },
                    onPrevious = { controller?.stepFind(-1) },
                    onClose = {
                        find = false
                        findQuery = ""
                        controller?.closeFind()
                    },
                )
            }
        },
        toolbar = {
            if (controller != null && editable && controller.focused) {
                FormattingBar(
                    controller.format,
                    controller,
                    onLinkDialog = { link = controller.linkDialog() },
                    onOpenLink = openHref,
                    onFile = onFile,
                    onInsert = onInsert,
                )
            }
        },
    ) { modifier ->
        when {
            editing.load == NoteLoad.Loading -> LoadingState(modifier)
            editing.load == NoteLoad.Failed -> ErrorState(stringResource(R.string.note_failed), { session.background.launch(Dispatchers.Main) { editing.read() } }, modifier)
            controller != null -> key(controller) {
                NoteEditor(
                    controller,
                    colors,
                    strings,
                    images,
                    languageName = { languageNames[it] ?: it },
                    modifier = modifier.background(Color(0xFF000000.toInt() or colors.background)),
                    scrollState = scroll,
                    contentPadding = PaddingValues(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, top = NoteDetailsHeight + 8.dp, bottom = 240.dp),
                )
            }
        }
    }
    link?.let { (text, href) ->
        LinkDialog(text, href, { shown, address -> controller?.setLink(shown, address) }, { link = null }, linkCandidates)
    }
    picker?.let { target ->
        NotePickerDialog(linkCandidates, { chosen ->
            controller?.insertNoteLink(target.container, target.key, target.from, target.to, chosen.id, chosen.title.ifEmpty { untitled })
        }) { picker = null }
    }
    note?.let { shown ->
        when (dialog) {
            NoteDialog.Icon -> IconPickerDialog(stringResource(UiR.string.item_icon), shown.meta.icon, { icon -> changeMeta(false) { it.copy(icon = icon) } }, { dialog = null }, tint = itemTextColor(shown.meta.color))
            NoteDialog.Color -> ColourPickerDialog(stringResource(UiR.string.item_color), shown.meta.color, { color -> changeMeta(false) { it.copy(color = color) } }, { dialog = null })
            NoteDialog.Collections -> MultiChoiceDialog(
                stringResource(R.string.note_collections),
                collectionChoices,
                shown.meta.collections.toSet(),
                { chosen ->
                    changeMeta(false) { meta ->
                        val collections = meta.collections.filter { it in chosen } + collectionChoices.map { it.key }.filter { it in chosen && it !in meta.collections }
                        meta.copy(collections = collections)
                    }
                },
                onDismiss = { dialog = null },
            )
            // The tags popup is shown by the top bar, under the line of tags.
            NoteDialog.Tags -> Unit
            NoteDialog.Export -> ExportDialog(session, open.library, listOf(id), showMessage) { dialog = null }
            NoteDialog.Delete -> ConfirmDialog(
                title = pluralStringResource(UiR.plurals.delete_notes_title, 1, 1),
                text = stringResource(UiR.string.delete_notes_text),
                confirmText = stringResource(UiR.string.delete),
                onConfirm = {
                    editing.abandon()
                    onLeft(NoteOutcome.Deleted, null)
                    session.background.launch { session.organize { it.deleteNotes(listOf(id)) } }
                },
                onDismiss = { dialog = null },
                destructive = true,
            )
            null -> Unit
        }
    }
    if (info && note != null) {
        val counts = remember(controller?.state?.doc) { controller?.counts()?.let { Triple(it.words, it.characters, it.attachments) } }
        val backlinks by produceState<List<NoteSummary>?>(null, open.revision) {
            value = withContext(Dispatchers.IO) { runCatching { open.index.backlinks(id, includeTrashed = false) }.getOrDefault(emptyList()) }
        }
        val unavailable by session.unavailableLocations.collectAsStateWithLifecycle()
        NoteInfoSheet(
            NoteInfo(
                note.meta.created, note.meta.updated, note.meta.collections.mapNotNull(collectionNames::get), editing.tags,
                note.meta.location, id in unavailable, counts, backlinks,
            ),
            title = editing.title.ifEmpty { untitled },
            onRemoveLocation = { session.background.launch(Dispatchers.Main) { editing.changeMeta { it.copy(location = null) } }; Unit }.takeIf { editable },
            onOpenNote = { target ->
                info = false
                openNote(target)
            },
            onDismiss = { info = false },
        )
    }
    languageFor?.let { block ->
        LanguageDialog(
            block.language,
            { key -> if (key.isEmpty()) strings.plainText else languageNames[key] ?: key },
            { controller?.setCodeLanguage(block.id, it) },
            { languageFor = null },
        )
    }
    titleFor?.let { block ->
        CodeTitleDialog(block.title, { controller?.setCodeTitle(block.id, it) }, { titleFor = null })
    }
    viewer?.let { (names, start) ->
        val files = remember(names) { names.mapNotNull { assets.attachment(it)?.file } }
        if (files.isEmpty()) {
            viewer = null
        } else {
            NoteImageViewer(
                files,
                start.coerceAtMost(files.size - 1),
                onClose = { viewer = null },
                onOpen = { if (!openFile(context, it)) showMessage(noApplication) },
                onShare = { if (!shareFile(context, it)) showMessage(noApplication) },
            )
        }
    }
}

/** Returns a stored file as the editor inserts it. */
private fun insertedFile(file: StoredFile): InsertedFile = InsertedFile(file.fileName, file.size, file.kind != ImageKind.None)
