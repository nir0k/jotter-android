package io.github.nir0k.jotter

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nir0k.jotter.core.format.NoteMeta
import io.github.nir0k.jotter.feature.note.NoteOutcome
import io.github.nir0k.jotter.feature.notes.NotesLoad
import io.github.nir0k.jotter.feature.notes.Reorder
import io.github.nir0k.jotter.feature.notes.SelectionKind
import io.github.nir0k.jotter.feature.notes.SelectionLibrary
import io.github.nir0k.jotter.feature.notes.rememberNoteSelection
import io.github.nir0k.jotter.feature.notes.trees.rememberTreeActions
import io.github.nir0k.jotter.designsystem.components.ConfirmDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import io.github.nir0k.jotter.data.location.currentLocation
import io.github.nir0k.jotter.data.location.hasLocationPermission
import io.github.nir0k.jotter.core.format.FavoriteRef
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.collectionSubtree
import io.github.nir0k.jotter.core.index.NoteFilter
import io.github.nir0k.jotter.core.index.NoteSort
import io.github.nir0k.jotter.core.index.Problem
import io.github.nir0k.jotter.data.library.LibraryEntry
import io.github.nir0k.jotter.data.library.LibrarySession
import io.github.nir0k.jotter.data.library.LibraryState
import io.github.nir0k.jotter.data.settings.Appearance
import io.github.nir0k.jotter.data.settings.DevicePreferences
import io.github.nir0k.jotter.data.settings.SyncSettings
import io.github.nir0k.jotter.data.sync.SyncManager
import io.github.nir0k.jotter.data.sync.SyncPhase
import io.github.nir0k.jotter.feature.sync.SetupMode
import io.github.nir0k.jotter.feature.sync.SyncSetupScreen
import io.github.nir0k.jotter.feature.sync.SyncTab
import io.github.nir0k.jotter.feature.sync.syncProblem
import io.github.nir0k.jotter.feature.note.NoteDetail
import io.github.nir0k.jotter.feature.note.NoteLeave
import io.github.nir0k.jotter.feature.note.NoteLeaveDialog
import io.github.nir0k.jotter.feature.notes.EmptyContent
import io.github.nir0k.jotter.feature.notes.LeadingRow
import io.github.nir0k.jotter.feature.notes.ListSearch
import io.github.nir0k.jotter.feature.notes.NoteSearch
import io.github.nir0k.jotter.feature.notes.SearchWindow
import io.github.nir0k.jotter.feature.notes.SyncBanner
import io.github.nir0k.jotter.feature.notes.NoteListScreen
import io.github.nir0k.jotter.feature.notes.SearchOptions
import io.github.nir0k.jotter.feature.notes.rememberLibraryLists
import io.github.nir0k.jotter.feature.notes.rememberNotes
import io.github.nir0k.jotter.feature.settings.AboutScreen
import io.github.nir0k.jotter.feature.settings.Components
import io.github.nir0k.jotter.feature.settings.LibrariesScreen
import io.github.nir0k.jotter.feature.settings.SettingsTab
import io.github.nir0k.jotter.feature.settings.LibrarySettings
import io.github.nir0k.jotter.feature.settings.LicenseTextScreen
import io.github.nir0k.jotter.feature.settings.LicensesScreen
import io.github.nir0k.jotter.feature.settings.ProblemsScreen
import io.github.nir0k.jotter.feature.settings.RuleForm
import io.github.nir0k.jotter.feature.settings.Rules
import io.github.nir0k.jotter.feature.settings.newRuleArg
import io.github.nir0k.jotter.ui.rulesFiledMessage
import io.github.nir0k.jotter.data.library.CreatedNote
import io.github.nir0k.jotter.feature.settings.SettingsScreen
import io.github.nir0k.jotter.feature.settings.ThemesScreen
import io.github.nir0k.jotter.feature.settings.FirstStartScreen
import io.github.nir0k.jotter.feature.notes.trees.CollectionNode
import io.github.nir0k.jotter.feature.notes.trees.CollectionRow
import io.github.nir0k.jotter.core.format.Collection
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.nir0k.jotter.designsystem.components.Choice
import io.github.nir0k.jotter.designsystem.components.ErrorState
import io.github.nir0k.jotter.designsystem.components.LoadingState
import io.github.nir0k.jotter.designsystem.components.MenuAction
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.AppTheme
import io.github.nir0k.jotter.designsystem.theme.ThemePalette
import io.github.nir0k.jotter.feature.notes.HomeTrees
import io.github.nir0k.jotter.feature.notes.HomeDestination
import io.github.nir0k.jotter.feature.notes.HomeScreen
import io.github.nir0k.jotter.ui.collectionChoices
import io.github.nir0k.jotter.ui.Refresh
import io.github.nir0k.jotter.data.R as DataR
import io.github.nir0k.jotter.ui.R as UiR
import androidx.compose.ui.platform.LocalConfiguration
import io.github.nir0k.jotter.data.settings.setInterfaceLanguage
import io.github.nir0k.jotter.data.settings.interfaceLanguage

/** Screen is a kind of entry of the navigation stack. */
enum class Screen { Home, AllNotes, Unfiled, Favourites, Archive, Trash, Collection, Tag, Note, Settings, Themes, About, Libraries, Problems, Licenses, License, SyncSetup, Rules, Rule }

/**
 * Route is one entry of the navigation stack: a screen and, for a collection or a tag, which one.
 *
 * @param arg The collection's identifier, the tag path or the tab of Settings; empty for other screens.
 */
data class Route(val screen: Screen, val arg: String = "") {
    /** The entry as a text, which keys its saved state and survives process death in the stack. */
    val key: String get() = "${screen.name}:$arg"
}

/** The argument of the setup screen that downloads a library rather than connecting the open one. */
private const val DownloadArg = "download"

// Screens that list notes; in a wide window a note opened from one shows beside it.
private val listScreens = setOf(Screen.Home, Screen.AllNotes, Screen.Unfiled, Screen.Favourites, Screen.Archive, Screen.Trash, Screen.Collection, Screen.Tag)

/** The window width from which a note opened from a list shows beside it. */
private val ListDetailWidth = 840.dp

// The stack survives rotation and process death as "screen:argument" texts; an argument may hold
// colons, a screen name does not.
private val stackSaver = listSaver<SnapshotStateList<Route>, String>(
    save = { stack -> stack.map { it.key } },
    restore = { saved ->
        mutableStateListOf(*saved.map { Route(Screen.valueOf(it.substringBefore(':')), it.substringAfter(':')) }.toTypedArray())
    },
)

/**
 * JotterApp applies the stored appearance and shows what the library allows: the first start
 * without a library, a loading or error state while it opens, and then the screen on top of the
 * navigation stack.
 *
 * @param preferences The device preferences, read and written by the screens.
 * @param session The library of this device.
 * @param sync Synchronisation of the open library.
 * @param initial The appearance read at start-up, worn until the stored flow delivers.
 * @param requests Requests to open the Sync tab of Settings or a note.
 */
@Composable
fun JotterApp(preferences: DevicePreferences, session: LibrarySession, sync: SyncManager, initial: Appearance, requests: StateFlow<AppRequest?>) {
    val appearance by preferences.appearance.collectAsStateWithLifecycle(initial)
    val library by session.current.collectAsStateWithLifecycle()
    val palette = ThemePalette.resolve(appearance.paletteId)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // The platform's per-application language, read again when the configuration changes, as a
    // change of language recreates the activity; the table of contents follows it.
    val language = remember(LocalConfiguration.current) { context.interfaceLanguage() }
    LaunchedEffect(language) { session.languageChanged() }
    val setLanguage: (String?) -> Unit = { tag -> context.setInterfaceLanguage(tag) }
    NotificationPermission(preferences)
    AppTheme(appearance.mode, palette) {
        when (val state = library) {
            LibraryState.Opening -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { LoadingState() }
            LibraryState.Failed -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ErrorState(stringResource(R.string.library_open_failed), session::retry)
            }
            is LibraryState.None -> {
                var downloading by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = downloading) { downloading = false }
                if (downloading) {
                    SyncSetup(sync, SetupMode.Download, current = null, onDone = {}, onBack = { downloading = false })
                } else {
                    FirstStartScreen(language, setLanguage, state.creating, state.failed, session::create, onDownload = { downloading = true })
                }
            }
            is LibraryState.Open -> Destinations(state, session, sync, appearance, palette, language, setLanguage, preferences, requests)
        }
    }
}

/**
 * Destinations shows the screen on top of the navigation stack of an open library.
 *
 * @param open The open library.
 * @param session The library of this device, for the library actions of Settings.
 * @param sync Synchronisation of the open library.
 * @param appearance The current appearance.
 * @param palette The palette in use.
 * @param language The interface language's code, or null when it follows the system.
 * @param setLanguage Chooses a language.
 * @param preferences The device preferences the screens write.
 * @param requests Requests to open the Sync tab of Settings or a note.
 */
@Composable
private fun Destinations(
    open: LibraryState.Open,
    session: LibrarySession,
    sync: SyncManager,
    appearance: Appearance,
    palette: ThemePalette,
    language: String?,
    setLanguage: (String?) -> Unit,
    preferences: DevicePreferences,
    requests: StateFlow<AppRequest?>,
) {
    val scope = rememberCoroutineScope()
    val stack = rememberSaveable(saver = stackSaver) { mutableStateListOf(Route(Screen.Home)) }
    // Each entry of the stack keeps its saved state while it is covered, so a screen comes back with
    // its search, scroll position and expanded rows; the state goes when the entry leaves the stack.
    val saved = rememberSaveableStateHolder()
    val forget: (Route) -> Unit = { route -> if (route !in stack) saved.removeState(route.key) }
    // Whatever takes an open note off the screen first asks it to save.
    val leave = remember { NoteLeave() }
    val pop: () -> Unit = { if (stack.size > 1) forget(stack.removeAt(stack.lastIndex)) }
    val back: () -> Unit = { leave.request(scope, pop) }
    val replaceTop: (Route) -> Unit = { route ->
        val left = stack[stack.lastIndex]
        stack[stack.lastIndex] = route
        forget(left)
    }
    NoteLeaveDialog(leave)
    val sort by preferences.noteSort.collectAsStateWithLifecycle(NoteSort())
    val onSort: (NoteSort) -> Unit = { scope.launch { preferences.setNoteSort(it) } }
    val addLocation by preferences.addLocation.collectAsStateWithLifecycle(false)
    val tagColors = open.library.info.tagColors
    val lists = rememberLibraryLists(open)
    val collectionChoices = remember(lists) { collectionChoices(lists.collections) }
    val tagChoices = remember(lists) { lists.tags.map { Choice(it, it) } }
    BackHandler(enabled = stack.size > 1, onBack = back)
    // In a wide window a note on top of a list shows in a pane beside it; the stack stays the same,
    // so rotating between narrow and wide keeps the open note.
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= ListDetailWidth
    val top = stack.last()
    val paneNote = top.arg.takeIf { wide && top.screen == Screen.Note && stack.size > 1 && stack[stack.lastIndex - 1].screen in listScreens }
    val route = if (paneNote != null) stack[stack.lastIndex - 1] else top
    val openNote: (String) -> Unit = { id ->
        leave.request(scope) {
            if (stack.last().screen == Screen.Note && wide) replaceTop(Route(Screen.Note, id)) else stack.add(Route(Screen.Note, id))
        }
    }
    val monokai = palette.id == ThemePalette.Monokai.id
    val resources = LocalResources.current
    val listSnackbar = remember { SnackbarHostState() }
    // A note archived, trashed or deleted from its menu closes; the list says so, with Undo.
    val noteLeft: (String, NoteOutcome, NoteMeta?) -> Unit = { id, outcome, before ->
        if (stack.last() == Route(Screen.Note, id)) pop()
        scope.launch {
            val message = resources.getString(
                when (outcome) {
                    NoteOutcome.Archived -> R.string.note_archived
                    NoteOutcome.Trashed -> R.string.note_trashed
                    NoteOutcome.Deleted -> R.string.note_deleted_permanently
                },
            )
            val undo = before?.let { resources.getString(UiR.string.undo) }
            if (listSnackbar.showSnackbar(message, actionLabel = undo) == SnackbarResult.ActionPerformed && before != null) {
                session.organize { it.restore(listOf(before)) }
            }
        }
    }
    val untitled = stringResource(DataR.string.note_untitled)
    val currentCollections by rememberUpdatedState(lists.collections)
    // What the rules did while notes were organised from a list is announced there, with Undo.
    LaunchedEffect(session) {
        session.ruleChanges.collect { changes ->
            launch {
                val message = rulesFiledMessage(resources, changes, currentCollections, untitled)
                if (listSnackbar.showSnackbar(message, actionLabel = resources.getString(UiR.string.undo)) == SnackbarResult.ActionPerformed) {
                    session.organize { it.undoRules(changes) } ?: listSnackbar.showSnackbar(resources.getString(UiR.string.organize_failed))
                }
            }
        }
    }
    // What the rules did with a new note is announced by the note's screen, which opens at once.
    var createdNote by remember { mutableStateOf<CreatedNote?>(null) }
    val noteDetail: @Composable (String, (() -> Unit)?) -> Unit = { id, onBack ->
        NoteDetail(
            open, session, id, onBack, openNote, monokai, lists.tags, lists.collections, addLocation,
            favorite = FavoriteRef(FavoriteRef.Kind.Note, id) in open.library.info.favorites,
            collectionChoices = collectionChoices,
            onLeft = { outcome, before -> noteLeft(id, outcome, before) },
            created = createdNote?.takeIf { it.id == id },
            onCreatedShown = { createdNote = null },
            leave = leave,
        )
    }
    // The note in the pane keeps the saved state of its entry, so a change of width keeps its cursor.
    val detail: (@Composable () -> Unit)? = paneNote?.let { id -> { saved.SaveableStateProvider(Route(Screen.Note, id).key) { noteDetail(id, null) } } }
    val libraryId = open.library.root.fileName.toString()
    val syncStatus by sync.status.collectAsStateWithLifecycle()
    val connected = syncStatus.settings != null
    // The banner and notifications open the Sync tab of Settings on top of where the person is.
    val openSyncTab: () -> Unit = {
        leave.request(scope) {
            val route = Route(Screen.Settings, SettingsTab.Sync.name)
            if (stack.last().screen == Screen.Settings) replaceTop(route) else stack.add(route)
        }
    }
    // A request to open the Sync tab of Settings or a note is handled once, also across rotation.
    var handledRequest by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(requests) {
        requests.collect { request ->
            if (request != null && request.serial > handledRequest) {
                handledRequest = request.serial
                request.noteId?.let(openNote) ?: openSyncTab()
            }
        }
    }
    val banner = when (syncStatus.phase) {
        SyncPhase.Failed -> syncStatus.error?.let { SyncBanner(syncProblem(it, syncStatus.hostKey).title, failed = true, openSyncTab) }
        SyncPhase.Waiting -> SyncBanner(stringResource(R.string.sync_banner_waiting), failed = false, openSyncTab)
        else -> null
    }?.takeIf { connected }
    val refresh = rememberPullRefresh(sync, listSnackbar).takeIf { connected }
    LaunchedEffect(sync) {
        sync.conflicts.collect { conflicts ->
            val result = listSnackbar.showSnackbar(
                resources.getQuantityString(R.plurals.sync_conflicts, conflicts.count, conflicts.count),
                actionLabel = conflicts.firstNote?.let { resources.getString(UiR.string.sync_conflicts_open) },
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) conflicts.firstNote?.let(openNote)
        }
    }
    // New notes open at once, and get a geotag in the background when geotags are on; a note left
    // empty is discarded when it is left.
    val createFailed = stringResource(R.string.note_create_failed)
    val context = LocalContext.current
    val createNote: (List<String>, List<String>) -> Unit = { collections, tags ->
        scope.launch {
            val created = session.createNote(collections, tags)
            if (created == null) {
                listSnackbar.showSnackbar(createFailed)
                return@launch
            }
            val id = created.id
            createdNote = created
            if (addLocation && hasLocationPermission(context)) session.locateNote(id) { currentLocation(context) }
            openNote(id)
        }
    }
    val canCreate = !open.library.isReadOnly
    val selectionLibrary = SelectionLibrary(session, open.library, open.library.info.favorites, collectionChoices, lists.tags).takeIf { canCreate }
    val trees = HomeTrees(
        lists.collectionTree, lists.tagTree, tagColors,
        { collection -> leave.request(scope) { stack.add(Route(Screen.Collection, collection.id)) } },
        { tag -> leave.request(scope) { stack.add(Route(Screen.Tag, tag)) } },
        rememberTreeActions(open, session, lists.collections, listSnackbar) { collection -> leave.request(scope) { stack.add(Route(Screen.Rule, newRuleArg(collection))) } },
    )
    // What the screens of Settings show of the library reads anew after a library action.
    var libraryBusy by remember { mutableStateOf(false) }
    val settingsSnackbar = remember { SnackbarHostState() }
    // Runs a library action, reporting a failure, or a success when it has a message.
    val act: (String, String?, suspend () -> Boolean) -> Unit = { failed, done, action ->
        scope.launch {
            libraryBusy = true
            val ok = action()
            libraryBusy = false
            (if (ok) done else failed)?.let { settingsSnackbar.showSnackbar(it) }
        }
    }
    saved.SaveableStateProvider(route.key) { when (val screen = route.screen) {
        Screen.Home -> {
            var search by rememberSaveable(stateSaver = NoteSearch.Saver) { mutableStateOf(NoteSearch()) }
            var searching by rememberSaveable { mutableStateOf(false) }
            HomeScreen(
                homeDestinations { list -> leave.request(scope) { stack.add(Route(list)) } },
                trees,
                onSearch = { searching = true },
                onSettings = { leave.request(scope) { stack.add(Route(Screen.Settings)) } },
                readOnly = open.library.isReadOnly,
                detail = detail,
                banner = banner,
                refresh = refresh,
                snackbar = listSnackbar,
                onAdd = { createNote(emptyList(), emptyList()) }.takeIf { canCreate },
            )
            // Covered by the note it opened; Back from the note returns to the results.
            if (searching && paneNote == null) {
                ListSearchWindow(
                    open, NoteFilter(), sort,
                    ListSearch(search, { search = it }, SearchOptions(collectionChoices, tagChoices, offerEverywhere = false, offerArchived = true)),
                    lists.collections, tagColors, openNote,
                ) { searching = false }
            }
        }
        // Every list has a search of its own, which it keeps while it is covered.
        Screen.AllNotes, Screen.Unfiled, Screen.Favourites, Screen.Archive, Screen.Trash, Screen.Collection, Screen.Tag -> key(route) {
            val isCollection = screen == Screen.Collection
            val isArchive = screen == Screen.Archive
            val isTrash = screen == Screen.Trash
            var search by rememberSaveable(stateSaver = NoteSearch.Saver) { mutableStateOf(NoteSearch()) }
            var searching by rememberSaveable { mutableStateOf(false) }
            val ownOnly by preferences.ownNotesOnly.collectAsStateWithLifecycle(emptySet())
            val own = isCollection && route.arg in ownOnly
            val favourites = open.library.info.favorites.filter { it.kind == FavoriteRef.Kind.Note }.map { it.id }
            val filter = when (screen) {
                Screen.Unfiled -> NoteFilter(NoteFilter.View.Unfiled)
                Screen.Favourites -> NoteFilter(NoteFilter.View.Notes, favourites)
                Screen.Archive -> NoteFilter(NoteFilter.View.Archive)
                Screen.Trash -> NoteFilter(NoteFilter.View.Trash)
                Screen.Collection -> NoteFilter(NoteFilter.View.Collections, if (own) listOf(route.arg) else collectionSubtree(lists.collections, route.arg))
                Screen.Tag -> NoteFilter(NoteFilter.View.Tag, tag = route.arg)
                else -> NoteFilter()
            }
            val query = rememberNotes(open, filter, sort, NoteSearch(), lists.collections, order = favourites.takeIf { screen == Screen.Favourites })
            // Favourite collections lead Favourites.
            val favouriteCollections = if (screen != Screen.Favourites) emptyList() else {
                val nodes = flatten(lists.collectionTree).associateBy { it.collection.id }
                open.library.info.favorites.filter { it.kind == FavoriteRef.Kind.Collection }.mapNotNull { nodes[it.id] }
            }
            var emptying by rememberSaveable { mutableStateOf(false) }
            val trashCount = (query.load as? NotesLoad.Loaded)?.notes?.size ?: 0
            if (emptying) {
                ConfirmDialog(
                    title = pluralStringResource(UiR.plurals.delete_notes_title, trashCount, trashCount),
                    text = stringResource(UiR.string.delete_notes_text),
                    confirmText = stringResource(R.string.trash_empty_action),
                    onConfirm = { scope.launch { if (session.organize { it.emptyTrash() } == null) listSnackbar.showSnackbar(resources.getString(UiR.string.organize_failed)) } },
                    onDismiss = { emptying = false },
                    destructive = true,
                )
            }
            val retention = open.library.info.trashRetentionDays
            NoteListScreen(
                title = when (screen) {
                    Screen.Unfiled -> stringResource(R.string.view_unfiled)
                    Screen.Favourites -> stringResource(R.string.view_favourites)
                    Screen.Archive -> stringResource(DataR.string.archive_title)
                    Screen.Trash -> stringResource(R.string.trash_title)
                    Screen.Collection -> lists.collections.firstOrNull { it.id == route.arg }?.name.orEmpty()
                    Screen.Tag -> route.arg
                    else -> stringResource(R.string.view_all)
                },
                load = query.load,
                sort = sort,
                // Favourites keep their own order.
                onSort = onSort.takeIf { screen != Screen.Favourites },
                onSearch = { searching = true },
                tagColors = tagColors,
                empty = when (screen) {
                    Screen.Unfiled -> EmptyContent(AppIcons.Inbox, stringResource(R.string.unfiled_empty_title), stringResource(R.string.unfiled_empty_text))
                    Screen.Favourites -> EmptyContent(AppIcons.Favorite, stringResource(R.string.favourites_empty_title), stringResource(R.string.favourites_empty_text))
                    Screen.Archive -> EmptyContent(AppIcons.Archive, stringResource(R.string.archive_empty_title), stringResource(R.string.archive_empty_text))
                    Screen.Trash -> EmptyContent(
                        AppIcons.Delete,
                        stringResource(R.string.trash_empty_title),
                        if (retention == null) stringResource(R.string.trash_empty_kept) else pluralStringResource(R.plurals.trash_empty_text, retention, retention),
                    )
                    Screen.Collection -> EmptyContent(AppIcons.Folder, stringResource(R.string.collection_empty_title), stringResource(R.string.collection_empty_text))
                    Screen.Tag -> EmptyContent(AppIcons.Tag, stringResource(R.string.tag_empty_title), stringResource(R.string.tag_empty_text))
                    else -> EmptyContent(AppIcons.AllNotes, stringResource(R.string.notes_empty_title), stringResource(R.string.notes_empty_text))
                },
                onRetry = query.retry,
                onOpen = openNote,
                onBack = back,
                // The trash's ⋮ holds Empty trash while it holds notes.
                menu = if (isTrash && trashCount > 0 && canCreate) {
                    listOf(MenuAction(stringResource(R.string.trash_empty_action), AppIcons.Delete, destructive = true) { emptying = true })
                } else {
                    emptyList()
                },
                detail = detail,
                refresh = refresh,
                banner = banner,
                snackbar = listSnackbar,
                onAdd = {
                    when (screen) {
                        Screen.Collection -> createNote(listOf(route.arg), emptyList())
                        Screen.Tag -> createNote(emptyList(), listOf(route.arg))
                        else -> createNote(emptyList(), emptyList())
                    }
                }.takeIf { canCreate && !isArchive && !isTrash },
                selection = rememberNoteSelection(
                    route.key, if (isArchive) SelectionKind.Archive else if (isTrash) SelectionKind.Trash else SelectionKind.Active, query.load, selectionLibrary, listSnackbar,
                    collection = route.arg.takeIf { isCollection },
                ),
                // A collection with subcollections can show only its own notes.
                controls = if (isCollection && lists.collections.any { it.parent == route.arg }) {
                    {
                        FilterChip(own, { scope.launch { preferences.setOwnNotesOnly(route.arg, !own) } }, { Text(stringResource(R.string.collection_own_notes)) })
                    }
                } else {
                    null
                },
                leading = favouriteCollections.map { node ->
                    LeadingRow("collection:${node.collection.id}") { trailing ->
                        CollectionRow(node, 0, null, {}, { trees.onOpenCollection(node.collection) }, trailing = trailing)
                    }
                },
                // Favourites are ordered by dragging their handles.
                reorder = Reorder { rowKey, before ->
                    fun ref(key: String) = if (key.startsWith("collection:")) FavoriteRef(FavoriteRef.Kind.Collection, key.removePrefix("collection:"))
                    else FavoriteRef(FavoriteRef.Kind.Note, key)
                    scope.launch { session.organize { it.moveFavorite(ref(rowKey), before?.let(::ref)) } ?: listSnackbar.showSnackbar(resources.getString(UiR.string.organize_failed)) }
                }.takeIf { screen == Screen.Favourites && canCreate },
            )
            // Covered by the note it opened; Back from the note returns to the results.
            if (searching && paneNote == null) {
                ListSearchWindow(
                    open, filter, sort,
                    ListSearch(
                        search, { search = it },
                        SearchOptions(collectionChoices, tagChoices, offerEverywhere = screen != Screen.AllNotes && !isArchive && !isTrash, offerArchived = !isArchive && !isTrash),
                    ),
                    lists.collections, tagColors, openNote,
                ) { searching = false }
            }
        }
        Screen.Settings -> {
            val facts = rememberLibraryFacts(open, session, libraryBusy)
            val retentionFailed = stringResource(R.string.retention_failed)
            SettingsScreen(
                initialTab = SettingsTab.entries.firstOrNull { it.name == route.arg } ?: SettingsTab.General,
                appearance = appearance,
                palette = palette,
                onMode = { scope.launch { preferences.setMode(it) } },
                language = language,
                onLanguage = setLanguage,
                onOpenThemes = { stack.add(Route(Screen.Themes)) },
                addLocation = addLocation,
                onAddLocation = { scope.launch { preferences.setAddLocation(it) } },
                onOpenAbout = { stack.add(Route(Screen.About)) },
                library = facts?.let { base ->
                    LibrarySettings(
                        base.libraries.firstOrNull { it.open }, base.notes, base.problems, base.retention, base.readOnly, localOnly = !connected, base.rules,
                        onRetention = { days -> act(retentionFailed, null) { session.setTrashRetention(days) } },
                        onLibraries = { stack.add(Route(Screen.Libraries)) },
                        onProblems = { stack.add(Route(Screen.Problems)) },
                        onRules = { stack.add(Route(Screen.Rules)) },
                    )
                },
                syncTab = {
                    SyncTab(
                        status = syncStatus,
                        onSyncNow = sync::syncNow,
                        onSetup = { stack.add(Route(Screen.SyncSetup)) },
                        onTrust = sync::trustHostKey,
                        onInterval = { minutes -> scope.launch { preferences.updateSyncSettings(libraryId) { it.copy(interval = minutes) } } },
                        onMetered = { metered -> scope.launch { preferences.updateSyncSettings(libraryId) { it.copy(metered = metered) } } },
                        onPause = { until -> scope.launch { preferences.updateSyncSettings(libraryId) { it.copy(pausedUntil = until) } } },
                        onDisconnect = { scope.launch { sync.disconnect() } },
                        onDownload = { stack.add(Route(Screen.SyncSetup, DownloadArg)) },
                        snackbar = settingsSnackbar,
                    )
                },
                snackbar = settingsSnackbar,
                onClose = back,
            )
        }
        Screen.Libraries -> {
            val facts = rememberLibraryFacts(open, session, libraryBusy)
            val rebuilt = stringResource(R.string.library_rebuilt)
            val rebuildFailed = stringResource(R.string.library_rebuild_failed)
            val switchFailed = stringResource(R.string.library_switch_failed)
            val libraryCreateFailed = stringResource(R.string.library_create_failed)
            val deleteFailed = stringResource(R.string.library_delete_failed)
            LibrariesScreen(
                libraries = facts?.libraries,
                notes = facts?.notes ?: 0,
                busy = libraryBusy,
                onSwitch = { id -> act(switchFailed, null) { session.switchTo(id) } },
                onCreate = { act(libraryCreateFailed, null) { session.createAnother() } },
                onRebuild = { act(rebuildFailed, rebuilt) { session.rebuildIndex() } },
                onDelete = {
                    act(deleteFailed, null) {
                        // The library's connection goes with it; the copy on the sync storage stays.
                        sync.forget(libraryId)
                        session.deleteOpen()
                    }
                },
                snackbar = settingsSnackbar,
                onBack = back,
            )
        }
        Screen.About -> AboutScreen(
            remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() },
            onOpenLicenses = { stack.add(Route(Screen.Licenses)) },
            onBack = back,
        )
        Screen.Problems -> {
            val problems by produceState<List<Problem>?>(null, open.index, open.revision) {
                value = withContext(Dispatchers.IO) { runCatching { open.index.problems() }.getOrDefault(emptyList()) }
            }
            val problemsSnackbar = remember { SnackbarHostState() }
            val copyCreated = stringResource(R.string.problems_copy_created)
            val copyFailed = stringResource(R.string.problems_copy_failed)
            val originalDeleted = stringResource(R.string.problems_deleted)
            val deleteFailed = stringResource(R.string.problems_delete_failed)
            ProblemsScreen(
                problems,
                readOnly = open.library.isReadOnly,
                snackbar = problemsSnackbar,
                onNormalize = { problem ->
                    val copy = session.createNormalizedCopy(problem.fileName)
                    problemsSnackbar.showSnackbar(if (copy != null) copyCreated else copyFailed)
                },
                onDelete = { problem ->
                    val deleted = session.deleteNote(problem.fileName.removeSuffix(".html"))
                    problemsSnackbar.showSnackbar(if (deleted) originalDeleted else deleteFailed)
                },
                onBack = back,
            )
        }
        Screen.Rules -> Rules(open, session, lists.collections, onEdit = { stack.add(Route(Screen.Rule, it)) }, onBack = back)
        Screen.Rule -> key(route) { RuleForm(open, session, route.arg, lists.collections, lists.tags, onDone = back, onBack = back) }
        Screen.Licenses -> LicensesScreen({ stack.add(Route(Screen.License, Components.indexOf(it).toString())) }, onBack = back)
        Screen.License -> LicenseTextScreen(Components[route.arg.toInt()], onBack = back)
        Screen.Note -> key(route) { noteDetail(route.arg, back) }
        Screen.Themes -> ThemesScreen(palette, appearance.mode, { scope.launch { preferences.setPalette(it.id) } }, onBack = back)
        Screen.SyncSetup -> key(route) {
            val download = route.arg == DownloadArg
            SyncSetup(
                sync,
                if (download) SetupMode.Download else SetupMode.Connect,
                current = if (download) null else syncStatus.settings,
                onDone = back,
                onBack = back,
            )
        }
    } }
}

/**
 * SyncSetup is the setup screen of the SFTP storage wired to synchronisation: Check and save
 * connects the open library or downloads a library.
 *
 * @param mode Whether the check connects the open library or downloads one.
 * @param current The connection being changed, or null.
 * @param onDone Leaves the screen after a successful check.
 */
@Composable
private fun SyncSetup(sync: SyncManager, mode: SetupMode, current: SyncSettings?, onDone: () -> Unit, onBack: () -> Unit) {
    val status by sync.status.collectAsStateWithLifecycle()
    var draftKey by remember { mutableStateOf(sync.draftKey) }
    SyncSetupScreen(
        mode = mode,
        current = current,
        draftKey = draftKey,
        onDraftKey = {
            draftKey = it
            sync.draftKey = it
        },
        progress = if (mode == SetupMode.Download && status.phase == SyncPhase.Running) status.done to status.total else null,
        onCheck = { settings, secret, trust ->
            if (mode == SetupMode.Download) sync.download(settings, secret, trust) else sync.connect(settings, secret, trust)
        },
        onBrowse = sync::browse,
        onDone = onDone,
        onBack = onBack,
        snackbar = remember { SnackbarHostState() },
    )
}

/** The stages of a refresh the person pulled: asked for, running, and announced when it ends. */
private enum class PullState { None, Requested, Running }

/**
 * Returns pull to refresh of the note lists: it synchronises now, and the result of a pulled run
 * is confirmed in the snackbar, a failure with Retry (design system's pull to refresh rule).
 */
@Composable
private fun rememberPullRefresh(sync: SyncManager, snackbar: SnackbarHostState): Refresh {
    val status by sync.status.collectAsStateWithLifecycle()
    var pull by rememberSaveable { mutableStateOf(PullState.None) }
    val resources = LocalResources.current
    val failure = status.error?.let { syncProblem(it, status.hostKey).title }
    LaunchedEffect(status.phase, pull) {
        when {
            pull == PullState.Requested && status.phase == SyncPhase.Running -> pull = PullState.Running
            pull == PullState.Running && status.phase != SyncPhase.Running -> {
                pull = PullState.None
                if (status.phase == SyncPhase.Idle) {
                    snackbar.showSnackbar(resources.getString(R.string.sync_refreshed))
                } else {
                    val result = snackbar.showSnackbar(failure.orEmpty(), actionLabel = resources.getString(UiR.string.sync_retry))
                    if (result == SnackbarResult.ActionPerformed) {
                        pull = PullState.Requested
                        sync.syncNow()
                    }
                }
            }
        }
    }
    return Refresh(pull != PullState.None) {
        pull = PullState.Requested
        sync.syncNow()
    }
}

/**
 * Asks for the notification permission when [DevicePreferences.askNotifications] says so: at the
 * first start, and on the next start after a background run could not report a problem.
 */
@Composable
private fun NotificationPermission(preferences: DevicePreferences) {
    val ask by preferences.askNotifications.collectAsStateWithLifecycle(false)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { preferences.setAskNotifications(false) }
    }
    LaunchedEffect(ask) {
        if (!ask) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            preferences.setAskNotifications(false)
        } else {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** What Settings shows of the open library, as read from it and its index. */
private class LibraryFacts(
    val notes: Int,
    val problems: Int,
    val retention: Int?,
    val readOnly: Boolean,
    val libraries: List<LibraryEntry>,
    val rules: Int?,
)

/**
 * Reads what Settings shows of the open library: its note count with the archive and the trash, its problems, its retention, the number of its rules and the libraries of the device.
 *
 * @return The facts, or null when the index cannot be read.
 */
private suspend fun libraryFacts(open: LibraryState.Open, session: LibrarySession): LibraryFacts? = try {
    val index = open.index
    val notes = listOf(NoteFilter.View.All, NoteFilter.View.Archive, NoteFilter.View.Trash).sumOf { index.count(NoteFilter(it)) }
    val info = open.library.info
    val rules = runCatching { open.library.readRules().size }.getOrNull()
    LibraryFacts(notes, index.problems().size, info.trashRetentionDays, open.library.isReadOnly, session.libraries(), rules)
} catch (_: FormatException) {
    null
}

/**
 * Reads what the screens of Settings show of the open library, again whenever the index changes or a
 * library action starts or ends.
 *
 * @param busy Whether a library action runs.
 * @return The facts, or null while they are read or when the index cannot be read.
 */
@Composable
private fun rememberLibraryFacts(open: LibraryState.Open, session: LibrarySession, busy: Boolean): LibraryFacts? {
    val facts by produceState<LibraryFacts?>(null, open.library, open.index, open.revision, busy) {
        value = withContext(Dispatchers.IO) { libraryFacts(open, session) }
    }
    return facts
}

/**
 * Runs the search of a list in the search window: the index is read only while something is searched.
 *
 * @param filter The list's view.
 * @param search The list's search.
 * @param collections The collection tree, for collection names and the collection filter.
 * @param onOpen Opens a note.
 * @param onDismiss Closes the window.
 */
@Composable
private fun ListSearchWindow(
    open: LibraryState.Open,
    filter: NoteFilter,
    sort: NoteSort,
    search: ListSearch,
    collections: List<Collection>,
    tagColors: Map<String, String>,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val query = if (search.search.isActive) rememberNotes(open, filter, sort, search.search, collections) else null
    SearchWindow(search, query?.load, sort, tagColors, query?.retry ?: {}, onOpen, onDismiss)
}

/** Lists the nodes of a collection tree depth first. */
private fun flatten(nodes: List<CollectionNode>): List<CollectionNode> = nodes.flatMap { listOf(it) + flatten(it.children) }

/**
 * Builds the note lists the home screen leads with, in their fixed order.
 *
 * @param open Opens a list by its screen.
 */
@Composable
fun homeDestinations(open: (Screen) -> Unit): List<HomeDestination> = listOf(
    HomeDestination(stringResource(R.string.view_all), AppIcons.AllNotes) { open(Screen.AllNotes) },
    HomeDestination(stringResource(R.string.view_unfiled), AppIcons.Inbox) { open(Screen.Unfiled) },
    HomeDestination(stringResource(R.string.view_favourites), AppIcons.Favorite) { open(Screen.Favourites) },
    HomeDestination(stringResource(DataR.string.archive_title), AppIcons.Archive) { open(Screen.Archive) },
    HomeDestination(stringResource(R.string.trash_title), AppIcons.Delete) { open(Screen.Trash) },
)
