package io.github.nir0k.jotter

import android.app.Application
import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.nir0k.jotter.core.index.NoteSort
import io.github.nir0k.jotter.core.index.NoteSummary
import io.github.nir0k.jotter.data.settings.Appearance
import io.github.nir0k.jotter.data.export.ExportFormat
import io.github.nir0k.jotter.ui.ExportDialogStep
import io.github.nir0k.jotter.ui.ExportForm
import io.github.nir0k.jotter.feature.notes.HomeScreen
import io.github.nir0k.jotter.feature.notes.HomeTrees
import io.github.nir0k.jotter.core.search.TextSpan
import io.github.nir0k.jotter.feature.notes.DayRange
import io.github.nir0k.jotter.feature.notes.EmptyContent
import io.github.nir0k.jotter.feature.notes.LeadingRow
import io.github.nir0k.jotter.feature.notes.ListSearch
import io.github.nir0k.jotter.feature.notes.SearchWindow
import io.github.nir0k.jotter.feature.notes.NoteSearch
import io.github.nir0k.jotter.feature.notes.SearchOptions
import io.github.nir0k.jotter.data.library.LibraryEntry
import io.github.nir0k.jotter.feature.settings.AboutScreen
import io.github.nir0k.jotter.feature.settings.LibrariesScreen
import io.github.nir0k.jotter.feature.settings.LibrarySettings
import io.github.nir0k.jotter.feature.settings.SettingsTab
import io.github.nir0k.jotter.feature.settings.LicensesScreen
import io.github.nir0k.jotter.feature.settings.ProblemsScreen
import io.github.nir0k.jotter.core.index.Problem
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.feature.note.NoteScreen
import io.github.nir0k.jotter.feature.note.FormattingBar
import io.github.nir0k.jotter.feature.note.NoteDetails
import io.github.nir0k.jotter.feature.note.NoteTagsEditor
import io.github.nir0k.jotter.editor.FormatState
import io.github.nir0k.jotter.editor.SelectedObject
import io.github.nir0k.jotter.editor.CodeLanguages
import io.github.nir0k.jotter.editor.EditorCommands
import io.github.nir0k.jotter.editor.EditorStrings
import io.github.nir0k.jotter.editor.NoteEditor
import io.github.nir0k.jotter.editor.NoteEditorController
import io.github.nir0k.jotter.editor.NoteImages
import io.github.nir0k.jotter.core.body.ListKind
import io.github.nir0k.jotter.core.body.Mark
import io.github.nir0k.jotter.core.highlight.CodeHighlighter
import io.github.nir0k.jotter.feature.note.NoteDetailsHeight
import io.github.nir0k.jotter.feature.note.noteColors
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.data.export.PageSize
import androidx.compose.runtime.rememberCoroutineScope
import io.github.nir0k.jotter.feature.note.NoteImageViewer
import io.github.nir0k.jotter.ui.DeleteCollectionDialog
import io.github.nir0k.jotter.ui.MoveCollectionDialog
import io.github.nir0k.jotter.feature.notes.NoteSelection
import io.github.nir0k.jotter.feature.notes.Reorder
import io.github.nir0k.jotter.feature.notes.trees.TreeActions
import io.github.nir0k.jotter.core.organize.Organizer
import io.github.nir0k.jotter.designsystem.components.Tag
import io.github.nir0k.jotter.designsystem.components.ColourLook
import io.github.nir0k.jotter.designsystem.components.ColourPickerDialog
import io.github.nir0k.jotter.designsystem.components.IconPickerDialog
import io.github.nir0k.jotter.designsystem.components.FindBar
import androidx.compose.foundation.layout.ColumnScope
import io.github.nir0k.jotter.designsystem.components.MenuAction
import io.github.nir0k.jotter.designsystem.components.SelectionBar
import io.github.nir0k.jotter.feature.note.NoteInfo
import io.github.nir0k.jotter.feature.note.NoteInfoContent
import io.github.nir0k.jotter.core.format.GeoLocation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import java.io.File
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import io.github.nir0k.jotter.feature.notes.trees.CollectionRow
import io.github.nir0k.jotter.feature.notes.trees.collectionTree
import io.github.nir0k.jotter.feature.notes.trees.tagTree
import io.github.nir0k.jotter.core.format.Collection
import io.github.nir0k.jotter.feature.notes.NoteListScreen
import io.github.nir0k.jotter.feature.notes.NotesLoad
import io.github.nir0k.jotter.feature.settings.SettingsScreen
import io.github.nir0k.jotter.feature.settings.RuleFormScreen
import io.github.nir0k.jotter.capture.CaptureState
import io.github.nir0k.jotter.capture.CaptureStatus
import io.github.nir0k.jotter.feature.settings.RulesScreen
import io.github.nir0k.jotter.core.format.RuleAction
import io.github.nir0k.jotter.core.format.RuleCondition
import io.github.nir0k.jotter.core.format.RuleConditionKind
import io.github.nir0k.jotter.feature.settings.ThemesScreen
import io.github.nir0k.jotter.feature.settings.FirstStartScreen
import io.github.nir0k.jotter.designsystem.theme.AppTheme
import io.github.nir0k.jotter.designsystem.theme.ThemeMode
import io.github.nir0k.jotter.designsystem.theme.ThemePalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import io.github.nir0k.jotter.designsystem.components.Choice
import java.util.Locale
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.core.sftp.HostKey
import io.github.nir0k.jotter.core.sftp.SftpConnection
import io.github.nir0k.jotter.core.sftp.SignInMethod
import io.github.nir0k.jotter.core.sync.SyncRun
import io.github.nir0k.jotter.data.settings.MeteredSync
import io.github.nir0k.jotter.data.settings.PausedUntilResumed
import io.github.nir0k.jotter.data.settings.SyncSettings
import io.github.nir0k.jotter.data.sync.SyncPhase
import io.github.nir0k.jotter.data.sync.SyncStatus
import io.github.nir0k.jotter.feature.notes.SyncBanner
import io.github.nir0k.jotter.feature.sync.SetupMode
import io.github.nir0k.jotter.feature.sync.SyncSetupScreen
import io.github.nir0k.jotter.feature.sync.SyncTab
import io.github.nir0k.jotter.feature.note.R as NoteR
import io.github.nir0k.jotter.feature.sync.R as SyncR
import io.github.nir0k.jotter.ui.R as UiR

/**
 * ScreenshotTest renders the application's screens into `app/screenshots/`. Run
 * `./gradlew :app:recordRoborazziDebug` to refresh the images and `verifyRoborazziDebug` to
 * compare against them.
 *
 * Every screen is drawn in light English, dark Russian, at 130% font scale and in Monokai.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain application: the screens need no library or synchronisation started.
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xhdpi", application = Application::class)
class ScreenshotTest(private val variant: Variant) {
    // The note's code block is highlighted at once, rather than once its definition is read in the background.
    init {
        CodeHighlighter.prepare("kotlin")
    }


    /** Variant is one rendering configuration of the screenshots. */
    enum class Variant(val suffix: String, val palette: ThemePalette, val dark: Boolean, val language: String, val fontScale: Float) {
        Light("light", ThemePalette.Warm, false, "en", 1f),
        DarkRussian("dark-ru", ThemePalette.Warm, true, "ru", 1f),
        LargeText("large", ThemePalette.Warm, false, "en", 1.3f),
        Monokai("monokai", ThemePalette.Monokai, true, "en", 1f),
    }

    companion object {
        /** Lists the variants the runner instantiates the class with. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = Variant.entries.map { arrayOf(it) }
    }

    @get:Rule
    val compose = createComposeRule()

    private val mode get() = if (variant.dark) ThemeMode.Dark else ThemeMode.Light

    // localized builds a context with the variant's language, font scale and night mode.
    private fun localized(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(variant.language))
            fontScale = variant.fontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (variant.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return base.createConfigurationContext(config)
    }

    // shot renders content in the variant and captures it under the screen's name, once ready
    // tells that what loads in the background is shown.
    private fun shot(name: String, ready: (() -> Boolean)? = null, content: @Composable () -> Unit) {
        val context = localized()
        compose.setContent {
            val density = LocalDensity.current
            // The localized context is no activity, so the activity's result registry is passed on.
            val results = LocalActivityResultRegistryOwner.current!!
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides results,
                LocalContext provides context,
                LocalResources provides context.resources,
                LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(density.density, variant.fontScale),
            ) {
                AppTheme(mode, variant.palette) { content() }
            }
        }
        compose.waitForIdle()
        ready?.let { compose.waitUntil(10_000, it) }
        compose.onRoot().captureRoboImage("screenshots/$name-${variant.suffix}.png")
    }

    @Test fun firstStart() = shot("first-start") { FirstStartScreen(null, {}, creating = false, failed = false, onCreate = {}, onDownload = {}) }

    @Test fun firstStartFailed() = shot("first-start-failed") { FirstStartScreen("ru", {}, creating = false, failed = true, onCreate = {}, onDownload = {}) }

    // Notes as the shared fixtures hold them, so the rows show icons, colours, tags and an untitled note.
    private val time = Instant.parse("2026-10-23T11:02:31Z")
    private fun note(title: String, snippet: String, icon: String? = null, color: String? = null, tags: List<String> = emptyList()) =
        NoteSummary(id = title, title = title, icon = icon, color = color, created = time, updated = time, tags = tags, snippet = snippet)
    private val notes = listOf(
        note("Текст и форматирование", "Heading one Plain paragraph with bold, italic, underline, strikethrough, x2, H2O and inline code.", "Calendar", "blue", listOf("work/planning", "Reading list")),
        note("Lists", "Bullet one Nested bullet Bullet two Starts at three Four Done To do Alpha Roman Multilevel", "Checklist", tags = listOf("work")),
        note("Code blocks", "Go with a title: package main func main() { if 1 < 2 { println(\"<ok> & done\") } }", "Code", "graphite", listOf("work/urgent")),
        note("", ""),
        note("План </script><b>«недели»</b> & \"quotes\"", "Metadata edge cases: an identifier of UUID version 4, unknown fields, an unknown icon and a custom colour.", "Unicorn", "#ff8800", listOf("личное", "work/urgent", "Reading list")),
    )
    private val tagColors = mapOf("work" to "sky", "work/urgent" to "red", "личное" to "#00aa55", "reading list" to "ultraviolet")
    private val options = SearchOptions(listOf(Choice("c1", "Работа"), Choice("c2", "Работа / Projects")), listOf(Choice("work", "work")), true, true)

    // The collection tree of the shared fixtures and a tag tree of their tags.
    private fun collection(id: String, parent: String?, name: String, icon: String, color: String?) =
        Collection(id, parent, name, icon, color, time, time)
    private val collectionList = listOf(
        collection("c1", null, "Работа", "Briefcase", "blue"),
        collection("c2", "c1", "Projects", "Folder", null),
        collection("c3", "c2", "Jotter", "Code", "#ff8800"),
        collection("c4", null, "Personal", "Heart", "ultraviolet"),
        collection("c5", null, "Reading", "Book", null),
    )
    private val collections = collectionTree(
        collectionList,
        mapOf("c1" to 4, "c2" to 2, "c3" to 1, "c4" to 2, "c5" to 2),
    )
    private val tags = tagTree(
        listOf("Reading list", "work", "work/planning", "work/urgent", "личное"),
        mapOf("reading list" to 2, "work" to 4, "work/planning" to 1, "work/urgent" to 2, "личное" to 1),
    )
    private val trees = HomeTrees(collections, tags, tagColors, {}, {})
    // The menus of an editable library show their ⋮ buttons and the Add button of Collections.
    private val editableTrees = HomeTrees(
        collections, tags, tagColors, {}, {},
        TreeActions({ listOf(MenuAction("Rename", AppIcons.Edit) {}) }, { listOf(MenuAction("Rename", AppIcons.Edit) {}) }, {}),
    )

    // The application opens here: the lists, the collections and the folded tags.
    @Test fun home() = shot("home") { HomeScreen(homeDestinations {}, editableTrees, {}, {}, onAdd = {}) }

    // A library of a newer format with nothing in it yet.
    @Test fun homeEmpty() = shot("home-empty") { HomeScreen(homeDestinations {}, HomeTrees(emptyList(), emptyList(), tagColors, {}, {}), {}, {}, readOnly = true, initialTagsShown = true) }

    // Tall enough to show the trees unfolded.
    @Config(qualifiers = "w400dp-h1100dp-xhdpi")
    @Test fun homeExpanded() = shot("home-expanded") {
        HomeScreen(homeDestinations {}, editableTrees, {}, {}, initialTagsShown = true, initialExpanded = setOf("c1", "c2", "work"), onAdd = {})
    }

    @Test fun homeSyncFailed() = shot("home-sync-failed") {
        HomeScreen(homeDestinations {}, trees, {}, {}, banner = SyncBanner(stringResource(SyncR.string.sync_error_network_title), failed = true) {})
    }

    // A list of notes: Back, the title, the search button and the order.
    @Composable
    private fun SampleList(load: NotesLoad, leading: List<LeadingRow> = emptyList(), reorder: Reorder? = null, selection: NoteSelection? = null, detail: (@Composable () -> Unit)? = null, onSort: ((NoteSort) -> Unit)? = {}) =
        NoteListScreen(
            stringResource(R.string.view_all), load, NoteSort(), onSort, {}, tagColors, EmptyContent(AppIcons.AllNotes, "", ""), {}, {}, {},
            detail = detail, onAdd = {}, selection = selection, leading = leading, reorder = reorder,
        )

    @Test fun notes() = shot("notes") { SampleList(NotesLoad.Loaded(notes)) }

    // The search window opened with nothing typed yet.
    @Test fun searchWindow() = shot("search-window") {
        SampleList(NotesLoad.Loaded(notes))
        SearchWindow(ListSearch(NoteSearch(), {}, options), null, NoteSort(), tagColors, {}, {}, {})
    }

    // A search with matches in bold and every filter on its own line where it does not fit, two applied.
    @Test fun search() = shot("search") {
        val fragment = "…paragraph with bold, italic, underline and inline code. Bold again."
        val found = notes[0].copy(
            snippet = fragment,
            matches = listOf(TextSpan(fragment.indexOf("bold"), 4), TextSpan(fragment.indexOf("Bold"), 4)),
        )
        val search = NoteSearch(query = "bold", withAttachments = true, created = DayRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 23)))
        SampleList(NotesLoad.Loaded(notes))
        SearchWindow(ListSearch(search, {}, options), NotesLoad.Loaded(listOf(found)), NoteSort(), tagColors, {}, {}, {}, initialFiltersShown = true)
    }

    @Test fun noMatches() = shot("no-matches") {
        SampleList(NotesLoad.Loaded(notes))
        SearchWindow(ListSearch(NoteSearch(query = "zebra"), {}, options), NotesLoad.Loaded(emptyList()), NoteSort(), tagColors, {}, {}, {})
    }

    // Favourites in an editable library carry their drag handles and keep their own order.
    @Test fun favourites() = shot("favourites") {
        SampleList(
            NotesLoad.Loaded(notes.take(2)),
            leading = listOf(collections[0], collections[2]).map { node ->
                LeadingRow("collection:${node.collection.id}") { trailing -> CollectionRow(node, 0, null, {}, {}, trailing = trailing) }
            },
            reorder = Reorder { _, _ -> },
            onSort = null,
        )
    }

    // Two notes selected: the selection bar replaces the Add button.
    @Test fun selection() = shot("selection") {
        val selected = setOf(notes[0].id, notes[2].id)
        SampleList(
            NotesLoad.Loaded(notes),
            selection = NoteSelection(selected, {}) {
                SelectionBar(selected.size, allFavorite = false, allPrivate = false, {}, {}, {}, null, listOf(MenuAction("Archive", AppIcons.Archive) {}))
            },
        )
    }

    @Test fun exportChoosing() = shot("export-choosing") { ExportForm(1, ExportDialogStep.Choosing, ExportFormat.Pdf, PageSize.A4, null, {}, {}, {}, {}, {}) }

    @Test fun exportRunning() = shot("export-running") { ExportForm(5, ExportDialogStep.Running(2, 5), ExportFormat.Jpg, PageSize.A4, null, {}, {}, {}, {}, {}) }

    @Test fun exportReady() = shot("export-ready") { ExportForm(5, ExportDialogStep.Ready("Notes.zip"), ExportFormat.Html, PageSize.A4, null, {}, {}, {}, {}, {}) }

    @Test fun collectionMove() = shot("collection-move") {
        val list = listOf(
            collection("c1", null, "Работа", "Briefcase", "blue"), collection("c2", "c1", "Projects", "Folder", null),
            collection("c4", null, "Personal", "Heart", "ultraviolet"), collection("c5", null, "Reading", "Book", null),
        )
        MoveCollectionDialog(list, list[3], { _, _ -> }, {})
    }

    @Test fun collectionDelete() = shot("collection-delete") {
        DeleteCollectionDialog("Работа", Organizer.CollectionDeletion(collections = 2, notes = 4), {}, {})
    }

    @Test fun collection() = shot("collection") {
        NoteListScreen(
            "Работа", NotesLoad.Loaded(notes.take(3)), NoteSort(), {}, {}, tagColors,
            EmptyContent(AppIcons.Folder, "", ""), {}, {}, {},
        )
    }

    // The body shows the note vocabulary: a heading, inline formats, lists, a quote, code and a table.
    @Composable
    private fun SampleNote(
        onBack: (() -> Unit)?,
        format: FormatState? = null,
        tagged: Boolean = true,
        body: String = SampleBody,
        header: @Composable ColumnScope.() -> Unit = {},
    ) = NoteScreen(
        "Lists",
        if (format != null) null else stringResource(NoteR.string.note_in_archive),
        onBack,
        remember { SnackbarHostState() },
        onTitle = ({ _: String -> }).takeIf { format != null },
        details = NoteDetails(
            listOf("Work"),
            if (tagged) listOf(Tag("work", MaterialTheme.colorScheme.primary), Tag("ideas", MaterialTheme.colorScheme.onSurfaceVariant)) else emptyList(),
            {}.takeIf { format != null },
            {}.takeIf { format != null },
        ),
        header = header,
        toolbar = {
            if (format != null) FormattingBar(format, object : EditorCommands {}, {}, {}, { _, _ -> }, {})
        },
    ) { modifier ->
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val controller = remember { NoteEditorController(SampleNoteId, body, editable = format != null, { it }) }
        NoteEditor(
            controller,
            noteColors(monokai = false),
            EditorStrings.from(context),
            remember { NoteImages(null, scope) },
            { key -> CodeLanguages.firstOrNull { it.key == key }?.name ?: key },
            modifier = modifier,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = NoteDetailsHeight + 8.dp),
        )
    }

    @Test fun note() = shot("note") { SampleNote(onBack = {}) }

    // Without tags the line offers them: the glyph, the word and +.
    @Test fun noteNoTags() = shot("note-no-tags") {
        SampleNote(onBack = {}, FormatState(editable = true, canUndo = true), tagged = false)
    }

    // The popup's content: the note's tags with ×, the field and the library's other tags.
    @Test fun noteTags() = shot("note-tags") {
        NoteTagsEditor(listOf("work", "ideas"), listOf("home", "ideas", "project", "work"), tagColors, {})
    }

    @Test fun noteEditing() = shot("note-editing") {
        SampleNote(onBack = {}, FormatState(editable = true, marks = setOf(Mark.Bold), list = ListKind.Number, canIndent = true, canUndo = true))
    }

    // An image selected in the editor puts its actions at the start of the formatting bar.
    @Test fun noteImage() = shot("note-image") {
        SampleNote(onBack = {}, FormatState(editable = true, canUndo = true, selected = SelectedObject(image = true, "photo.jpg", 50, "square", pending = false)))
    }

    // Find opens under the top bar with the count of matches; Replace is not offered.
    @Test fun noteFind() = shot("note-find") {
        SampleNote(onBack = {}, FormatState(editable = true, canUndo = true), header = { FindBar("list", {}, current = 2, total = 5, onNext = {}, onPrevious = {}, onClose = {}) })
    }

    // The design system's icon picker is not drawn here: under Robolectric its search field in a
    // dialog of the platform's width never lets Compose become idle, while the device draws it once.
    // The design system's own screenshots show it.

    @Test fun colourPicker() = shot("colour-picker") { ColourPickerDialog(stringResource(UiR.string.item_color), "blue", {}, {}) }

    @Test fun iconPicker() = shot("icon-picker") { IconPickerDialog(stringResource(UiR.string.item_icon), "Book", {}, {}) }

    @Test fun highlightPicker() = shot("highlight-picker") {
        ColourPickerDialog(stringResource(NoteR.string.format_highlight), "yellow", {}, {}, look = ColourLook.Highlight)
    }

    // An image wrapped square stands at the left; text and a list item flow beside it and continue below.
    @Test fun noteWrap() = shot("note-wrap") { SampleNote(onBack = {}, body = WrapBody) }

    // The sheet's content; the sheet itself is Material's.
    @Config(qualifiers = "w400dp-h1100dp-xhdpi")
    @Test fun noteInfo() = shot("note-info") {
        Surface(color = MaterialTheme.colorScheme.background) {
            NoteInfoContent(
                NoteInfo(
                    time, time.plusSeconds(3_600), listOf("Work"), listOf("work", "ideas"),
                    GeoLocation(55.75583, 37.6173, 35.0, time), locationUnavailable = false, counts = Triple(1240, 7315, 3),
                    backlinks = listOf(note("Weekly plan", ""), note("Reading list", "")),
                ),
                "Lists",
                onRemoveLocation = {},
                onOpenNote = {},
            )
        }
    }

    // The viewer decodes off the main thread; the screenshot waits until the image shows.
    @Test fun imageViewer() {
        val photo = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "photo.png")
        val bitmap = Bitmap.createBitmap(640, 420, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(0xFF7FB3D5.toInt())
            drawRect(0f, 280f, 640f, 420f, Paint().apply { color = 0xFF52875A.toInt() })
            drawCircle(480f, 110f, 60f, Paint().apply { color = 0xFFF4D03F.toInt() })
        }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot("image-viewer", ready = { compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).fetchSemanticsNodes().isEmpty() }) {
            NoteImageViewer(listOf(photo, photo), 0, {}, {}, {})
        }
    }

    @Config(qualifiers = "w900dp-h500dp-xhdpi")
    @Test fun noteWide() = shot("note-wide") {
        SampleList(NotesLoad.Loaded(notes), detail = { SampleNote(onBack = null) })
    }

    @Test fun trash() = shot("trash") {
        NoteListScreen(
            stringResource(R.string.trash_title), NotesLoad.Loaded(emptyList()), NoteSort(), {}, {}, tagColors,
            EmptyContent(AppIcons.Delete, stringResource(R.string.trash_empty_title), pluralStringResource(R.plurals.trash_empty_text, 30, 30)),
            {}, {}, {},
        )
    }

    // Settings as a window with its tabs; the Sync tab is drawn by its own tests.
    private val libraries = listOf(LibraryEntry("a", "notes", time, open = true), LibraryEntry("b", null, time.plusSeconds(86_400), open = false))
    @Composable
    private fun SampleSettings(tab: SettingsTab, syncTab: @Composable () -> Unit = {}) = SettingsScreen(
        tab, Appearance(variant.palette.id, mode), variant.palette, {}, null, {}, {}, addLocation = false, onAddLocation = {}, onOpenAbout = {},
        library = LibrarySettings(libraries[0], 8, 7, 30, readOnly = false, localOnly = true, 3, {}, {}, {}, {}),
        syncTab = syncTab, snackbar = remember { SnackbarHostState() }, onClose = {},
    )

    @Test fun settings() = shot("settings") { SampleSettings(SettingsTab.General) }

    @Test fun settingsLibrary() = shot("settings-library") { SampleSettings(SettingsTab.Library) }

    @Test fun libraries() = shot("libraries") { LibrariesScreen(libraries, 8, busy = false, {}, {}, {}, {}, remember { SnackbarHostState() }, {}) }

    @Test fun about() = shot("about") { AboutScreen("0.5.0", {}, {}) }

    // Rules in their order: one filing by tags, a switched-off one filing into a deleted collection,
    // and one of the desktop with a regular expression, which this client lists but does not run.
    private fun sampleRule(id: String, name: String, vararg conditions: RuleCondition, action: RuleAction, enabled: Boolean = true, stop: Boolean = false) =
        io.github.nir0k.jotter.core.format.Rule(id, name, enabled, conditions = conditions.toList(), action = action, stop = stop, created = time, updated = time)
    private val sampleRules = listOf(
        sampleRule(
            "r1", "Work notes", RuleCondition(RuleConditionKind.TagEquals, "work"), RuleCondition(RuleConditionKind.TagEquals, "work/urgent"),
            action = RuleAction(false, listOf("c1", "c2")),
        ),
        sampleRule("r2", "Untagged inbox", RuleCondition(RuleConditionKind.NoTags), action = RuleAction(true, listOf("gone")), enabled = false, stop = true),
        sampleRule("r3", "Dated reading", RuleCondition(RuleConditionKind.TitleMatches, "^\\d{4}"), action = RuleAction(true, listOf("c5"))),
    )

    @Test fun rules() = shot("rules") {
        RulesScreen(sampleRules, false, collectionList, readOnly = false, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {}, remember { SnackbarHostState() }, {})
    }

    // Tall enough to show the whole form.
    @Config(qualifiers = "w400dp-h1200dp-xhdpi")
    @Test fun rule() = shot("rule") {
        RuleFormScreen(sampleRules[0].copy(matchAll = false), isNew = false, collectionList, listOf("work", "work/urgent"), {}, remember { SnackbarHostState() }, {})
    }

    @Test fun captureCopying() = shot("capture-copying") { CaptureStatus(CaptureState.Working(2, 5), {}, {}, {}) }

    @Test fun captureNoLibrary() = shot("capture-no-library") { CaptureStatus(CaptureState.NoLibrary, {}, {}, {}) }

    @Test fun problems() = shot("problems") {
        ProblemsScreen(
            listOf(
                Problem("01a1872b-97cd-73ba-8974-249966c9e979.html", ErrorCode.Malformed, "no metadata block"),
                Problem("01a1872b-97d5-709c-af25-5f9417fc20f6.html", ErrorCode.NewerFormat, ""),
                Problem("01a1a316-0000-7000-8000-000000000001.html", ErrorCode.Malformed, "unsupported body: element video"),
            ),
            readOnly = false,
            snackbar = remember { SnackbarHostState() },
            onNormalize = {},
            onDelete = {},
            onBack = {},
        )
    }

    @Test fun licenses() = shot("licenses") { LicensesScreen({}, {}) }

    @Test fun themes() = shot("themes") { ThemesScreen(variant.palette, mode, {}, {}) }

    // A library connected with a key generated on the phone, with a log of the recent runs.
    private val connection = SftpConnection("notes.example.org", 22, "anna", "jotter/library", SignInMethod.Key)
    private val publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMAG7ofcYv3Vv3uWI60JcdpWwPvjCNXf7TXqWwWA0B6U jotter-android"
    private val syncSettings = SyncSettings(connection, publicKey, interval = 15, lastSynced = time)
    private val log = listOf(
        SyncRun(time, uploaded = 2, downloaded = 5, merged = 1, conflicts = 1),
        SyncRun(time.minusSeconds(900)),
        SyncRun(time.minusSeconds(1800), error = ErrorCode.Network),
    )
    private val changedKey = HostKey(
        HostKey.State.Changed, "ssh-ed25519", "SHA256:2BYB+4mVfYoNn2oGt3zaFqbxCCN+JC0wVZVx6Gk8NhU", "SHA256:Lx0QG+9n8U2cYMqWo2zqPZOy1bKqXUGgzI4yDnJPg3o",
    )

    @Composable
    private fun SampleSync(status: SyncStatus, trusting: Boolean = false) = SampleSettings(SettingsTab.Sync) {
        SyncTab(status, {}, {}, {}, {}, {}, {}, {}, {}, remember { SnackbarHostState() }, initialTrusting = trusting)
    }

    @Test fun syncNotConnected() = shot("sync-not-connected") { SampleSync(SyncStatus(libraryId = "a")) }

    @Config(qualifiers = "w400dp-h1300dp-xhdpi")
    @Test fun sync() = shot("sync") { SampleSync(SyncStatus("a", syncSettings, SyncPhase.Idle, log = log)) }

    // Paused until resumed, with attachments the last run on a mobile network left for Wi-Fi.
    @Config(qualifiers = "w400dp-h1500dp-xhdpi")
    @Test fun syncPaused() = shot("sync-paused") {
        SampleSync(SyncStatus("a", syncSettings.copy(metered = MeteredSync.TextOnly, pausedUntil = PausedUntilResumed), SyncPhase.Idle, deferredAttachments = 3, log = log))
    }

    @Config(qualifiers = "w400dp-h1300dp-xhdpi")
    @Test fun syncHostKeyChanged() = shot("sync-host-key-changed") {
        SampleSync(SyncStatus("a", syncSettings.copy(publicKey = null, connection = connection.copy(signIn = SignInMethod.Password)), SyncPhase.Failed, error = ErrorCode.HostKey, hostKey = changedKey, log = log))
    }

    @Test fun syncTrust() = shot("sync-trust") {
        SampleSync(SyncStatus("a", syncSettings, SyncPhase.Failed, error = ErrorCode.HostKey, hostKey = changedKey), trusting = true)
    }

    @Composable
    private fun SampleSetup(mode: SetupMode, current: SyncSettings?, error: ErrorCode? = null) = SyncSetupScreen(
        mode, current, null, {}, null, { _, _, _ -> }, { _, _, _ -> error("no server") }, {}, {}, remember { SnackbarHostState() }, initialError = error,
    )

    @Config(qualifiers = "w400dp-h1100dp-xhdpi")
    @Test fun syncSetup() = shot("sync-setup") { SampleSetup(SetupMode.Connect, null) }

    @Config(qualifiers = "w400dp-h1300dp-xhdpi")
    @Test fun syncSetupFailed() = shot("sync-setup-failed") {
        SampleSetup(SetupMode.Connect, syncSettings.copy(connection = connection.copy(signIn = SignInMethod.Password), publicKey = null), ErrorCode.SignIn)
    }

    @Config(qualifiers = "w400dp-h1100dp-xhdpi")
    @Test fun syncDownload() = shot("sync-download") { SampleSetup(SetupMode.Download, null) }
}

/** The note of the note screen's screenshots. */
private const val SampleNoteId = "01a1872b-97c3-75de-bada-1e2d73552a21"

// The image is not decoded in the test, so its place is drawn at the size of a picture still loading.
private const val WrapBody = "<p><img src=\"../attachments/$SampleNoteId/photo.jpg\" data-width=\"50\" data-wrap=\"square\">" +
    "Text beside the photo.</p>\n" +
    "<ul>\n<li>A list item beside it</li>\n</ul>\n<p>This paragraph starts beside the photo and continues below it across the full width of the column.</p>"

/** A body with every kind of block, as the note screen's screenshots show it. */
private const val SampleBody = "<h2>Shopping</h2>\n<p>Plain, <strong>bold</strong>, <em>italic</em>, <code>code</code> and <a href=\"https://example.com\">a link</a>.</p>\n" +
    "<ul class=\"checklist\">\n<li data-checked=\"true\">Milk</li>\n<li data-checked=\"false\">Bread</li>\n</ul>\n" +
    "<ol>\n<li>First\n<ul>\n<li>Nested</li>\n</ul>\n</li>\n<li>Second</li>\n</ol>\n<blockquote>\n<p>A quote.</p>\n</blockquote>\n" +
    "<pre data-language=\"kotlin\"><code>fun main() {\n    println(\"Hi\")\n}</code></pre>\n" +
    "<table>\n<thead>\n<tr><th><p>Item</p></th><th><p>Count</p></th></tr>\n</thead>\n<tbody>\n<tr><td><p>Eggs</p></td><td><p>12</p></td></tr>\n</tbody>\n</table>"
