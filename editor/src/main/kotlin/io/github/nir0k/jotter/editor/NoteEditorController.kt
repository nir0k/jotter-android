package io.github.nir0k.jotter.editor

import android.content.ClipData
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.TextRange
import io.github.nir0k.jotter.core.body.BlockSeparator
import io.github.nir0k.jotter.core.body.BlockStyle
import io.github.nir0k.jotter.core.body.CellContainer
import io.github.nir0k.jotter.core.body.CodeBlock
import io.github.nir0k.jotter.core.body.CodeSelection
import io.github.nir0k.jotter.core.body.Container
import io.github.nir0k.jotter.core.body.Doc
import io.github.nir0k.jotter.core.body.EditorState
import io.github.nir0k.jotter.core.body.History
import io.github.nir0k.jotter.core.body.ImageObject
import io.github.nir0k.jotter.core.body.InsertedFile
import io.github.nir0k.jotter.core.body.LineSeparator
import io.github.nir0k.jotter.core.body.ListKind
import io.github.nir0k.jotter.core.body.Mark
import io.github.nir0k.jotter.core.body.Match
import io.github.nir0k.jotter.core.body.RootContainer
import io.github.nir0k.jotter.core.body.RuleResult
import io.github.nir0k.jotter.core.body.Run
import io.github.nir0k.jotter.core.body.Selection
import io.github.nir0k.jotter.core.body.StoreRequest
import io.github.nir0k.jotter.core.body.Table
import io.github.nir0k.jotter.core.body.TableAction
import io.github.nir0k.jotter.core.body.TextRun
import io.github.nir0k.jotter.core.body.RunRange
import io.github.nir0k.jotter.core.body.formatAt
import io.github.nir0k.jotter.core.body.insertInline
import io.github.nir0k.jotter.core.body.runOf
import io.github.nir0k.jotter.core.body.TextCounts
import io.github.nir0k.jotter.core.body.TextSelection
import io.github.nir0k.jotter.core.body.backspaceAtStart
import io.github.nir0k.jotter.core.body.blockStyleOf
import io.github.nir0k.jotter.core.body.canIndent
import io.github.nir0k.jotter.core.body.canOutdent
import io.github.nir0k.jotter.core.body.codeById
import io.github.nir0k.jotter.core.body.codeIndent
import io.github.nir0k.jotter.core.body.codeNewline
import io.github.nir0k.jotter.core.body.codeOutdent
import io.github.nir0k.jotter.core.body.codeToParagraph
import io.github.nir0k.jotter.core.body.counts
import io.github.nir0k.jotter.core.body.currentBlock
import io.github.nir0k.jotter.core.body.currentCell
import io.github.nir0k.jotter.core.body.cursorFormat
import io.github.nir0k.jotter.core.body.endOf
import io.github.nir0k.jotter.core.body.enter
import io.github.nir0k.jotter.core.body.findMatches
import io.github.nir0k.jotter.core.body.globalOffset
import io.github.nir0k.jotter.core.body.goToCell
import io.github.nir0k.jotter.core.body.indent
import io.github.nir0k.jotter.core.body.insertCodeBlock
import io.github.nir0k.jotter.core.body.insertDivider
import io.github.nir0k.jotter.core.body.insertFiles
import io.github.nir0k.jotter.core.body.insertNoteLink
import io.github.nir0k.jotter.core.body.insertTable
import io.github.nir0k.jotter.core.body.inputRule
import io.github.nir0k.jotter.core.body.itemsIn
import io.github.nir0k.jotter.core.body.leaveCode
import io.github.nir0k.jotter.core.body.linkAtCursor
import io.github.nir0k.jotter.core.body.linkDialogText
import io.github.nir0k.jotter.core.body.markActive
import io.github.nir0k.jotter.core.body.noteImages
import io.github.nir0k.jotter.core.body.noteLinkTargets
import io.github.nir0k.jotter.core.body.outdent
import io.github.nir0k.jotter.core.body.parseBody
import io.github.nir0k.jotter.core.body.parsePastedHtml
import io.github.nir0k.jotter.core.body.paste
import io.github.nir0k.jotter.core.body.plainText
import io.github.nir0k.jotter.core.body.removeLink
import io.github.nir0k.jotter.core.body.moveSelectedObject
import io.github.nir0k.jotter.core.body.removeObject
import io.github.nir0k.jotter.core.body.replaceCode
import io.github.nir0k.jotter.core.body.replaceText
import io.github.nir0k.jotter.core.body.resolvePending
import io.github.nir0k.jotter.core.body.runsIn
import io.github.nir0k.jotter.core.body.selectedFragment
import io.github.nir0k.jotter.core.body.selectedObject
import io.github.nir0k.jotter.core.body.selection
import io.github.nir0k.jotter.core.body.select
import io.github.nir0k.jotter.core.body.cursor
import io.github.nir0k.jotter.core.body.selectionAt
import io.github.nir0k.jotter.core.body.serializeBody
import io.github.nir0k.jotter.core.body.setBlockStyle
import io.github.nir0k.jotter.core.body.setCodeLanguage
import io.github.nir0k.jotter.core.body.setCodeTitle
import io.github.nir0k.jotter.core.body.setColor
import io.github.nir0k.jotter.core.body.setImage
import io.github.nir0k.jotter.core.body.setLink
import io.github.nir0k.jotter.core.body.setNumbering
import io.github.nir0k.jotter.core.body.setTableWidth
import io.github.nir0k.jotter.core.body.startNumbering
import io.github.nir0k.jotter.core.body.startOf
import io.github.nir0k.jotter.core.body.tableAction
import io.github.nir0k.jotter.core.body.tableById
import io.github.nir0k.jotter.core.body.textRange
import io.github.nir0k.jotter.core.body.toggleChecked
import io.github.nir0k.jotter.core.body.toggleList
import io.github.nir0k.jotter.core.body.toggleMark
import io.github.nir0k.jotter.core.body.validSelection
import io.github.nir0k.jotter.core.body.withColumnWidths
import io.github.nir0k.jotter.core.body.withPendingObjects

/** The comment that marks HTML copied from a note, which a paste reads back in the note vocabulary. */
private const val CopyMarker = "<!--jotter:"

/**
 * NoteEditorController holds a note open in the native editor: the document and its selection,
 * the undo history, the text fields of the runs and code blocks, and find. Fields report what
 * was typed; it becomes a change of the model, and the fields show the model again. The
 * formatting bar and the screen's dialogs call its commands.
 *
 * @param noteId The note, whose attachment folder its images and chips use.
 * @param body The note's body.
 * @param language Returns the stored language of a language typed after ```.
 * @param selection Where the cursor was in the editor this one replaces, as after a rotation.
 */
class NoteEditorController(
    val noteId: String,
    body: String,
    editable: Boolean,
    private val language: (String) -> String,
    selection: EditorSelection? = null,
) : EditorCommands {
    /** The editor's state. */
    var state by mutableStateOf(initialState(parseBody(body), selection))
        private set

    /** Whether the note can be edited. */
    var editable by mutableStateOf(editable)

    /** Receives what the editor asks of the screen. */
    var host: EditorHost = object : EditorHost {}

    /** Whether one of the editor's fields has the focus. */
    var focused by mutableStateOf(false)
        internal set

    /** The field that should take the focus, after a change moved the cursor there. */
    internal var focusRequest by mutableStateOf<FieldKey?>(null)

    /** The field that has the focus. */
    internal var focusedField: FieldKey? = null

    /** The notes the body links to that no longer exist, drawn struck through. */
    var missingLinks by mutableStateOf(emptySet<String>())

    /** Find in the note. */
    var find by mutableStateOf(FindState())
        private set

    /** Whether an image or a chip is being dragged to another place. */
    internal var dragging by mutableStateOf(false)
        private set

    /** Where the dragged object would go, drawn as a line; null when no text is under the finger. */
    internal var drop by mutableStateOf<DropTarget?>(null)
        private set

    /** Where the finger dragging an object is, in the window, and [dragStart] where it picked the object up. */
    internal var dragPosition = Offset.Zero
        private set
    internal var dragStart = Offset.Zero
        private set

    private val placements = HashMap<Long, FieldPlacement>()
    private val history = History()
    private var historyVersion by mutableIntStateOf(0)
    private val fields = HashMap<FieldKey, TextFieldState>()
    private var editing: FieldKey? = null
    private var ruleUndo: EditorState? = null
    private var lastToken = 0
    private val stored = HashMap<String, InsertedFile?>()

    /** Whether the document changed since the last body was taken with [body]. */
    var dirty = false
        private set

    /** What the formatting bar shows. */
    val format: FormatState by derivedStateOf { describe() }

    /** Returns the state for a document with a selection kept from an editor before, or its start. */
    private fun initialState(doc: Doc, selection: EditorSelection?): EditorState {
        val state = EditorState.of(doc)
        return if (selection == null) state else state.copy(selection = doc.selectionAt(selection.anchor, selection.head))
    }

    /** Returns the note's body, written as the desktop writes it, and marks the changes saved. */
    fun body(): String {
        dirty = false
        return serializeBody(state.doc)
    }

    /** Returns the selection as numbers of the whole document, for the screen's saved state. */
    fun savedSelection(): EditorSelection =
        EditorSelection(state.doc.globalOffset(state.selection, head = false), state.doc.globalOffset(state.selection, head = true))

    /**
     * Shows a version of the note changed outside without unsaved changes, keeping the cursor
     * where it can; the history starts anew.
     */
    fun reload(body: String) {
        val saved = savedSelection()
        val doc = parseBody(body)
        history.clear()
        historyVersion++
        state = EditorState(doc, doc.selectionAt(saved.anchor, saved.head))
        dirty = false
        updateFind()
        syncFields()
    }

    /** Gives the field of the selection the focus, as when the editor before had it. */
    fun requestFocus() {
        focusRequest = fieldOf(state.selection)
    }

    /**
     * Puts the cursor at the end of the note and gives its field the focus, for a tap on the empty
     * room below the text. The note always ends with a paragraph (the frame rule), so the cursor
     * lands in text.
     *
     * @return Whether the cursor was placed; false in a note that cannot be edited.
     */
    fun focusEnd(): Boolean {
        if (!editable) return false
        select(endOf(state.doc.items.last()))
        focusRequest = fieldOf(state.selection)
        return true
    }

    /**
     * Puts the cursor at the start of the note and gives its field the focus, for the keyboard's
     * Next in the title.
     *
     * @return Whether the cursor was placed; false in a note that cannot be edited.
     */
    fun focusStart(): Boolean {
        if (!editable) return false
        select(startOf(state.doc))
        focusRequest = fieldOf(state.selection)
        return true
    }

    /** Returns the counts the information sheet shows. */
    fun counts(): TextCounts = state.doc.counts()

    /** Returns the notes the body links to. */
    fun linkTargets(): Set<String> = state.doc.noteLinkTargets()

    /** Returns the files of the note's images in body order. */
    fun images(): List<String> = state.doc.noteImages(noteId)

    /**
     * Makes a state the current one: a change of the document goes into the history and is
     * announced, and the fields and the focus follow.
     *
     * @param group The kind of typing the change is, which joins quick changes of the same kind.
     */
    private fun commit(next: EditorState?, group: String? = null, record: Boolean = true): Boolean {
        if (next == null) return false
        val changed = next.doc !== state.doc
        if (changed && record) {
            history.record(state, group, System.currentTimeMillis())
            historyVersion++
        }
        if (group == null) ruleUndo = null
        state = next.copy(selection = next.doc.validSelection(next.selection))
        if (changed) {
            dirty = true
            updateFind()
            host.onChanged()
        }
        syncFields()
        return true
    }

    /** Puts a selection in place without changing the document, ending a group of typing. */
    private fun select(selection: Selection) {
        if (selection == state.selection) return
        history.breakGroup()
        ruleUndo = null
        state = state.copy(selection = state.doc.validSelection(selection), stored = null)
        syncFields()
    }

    /** Returns the field that shows a selection. */
    private fun fieldOf(selection: Selection): FieldKey? = when (selection) {
        is CodeSelection -> FieldKey.CodeField(selection.code)
        is TextSelection -> state.doc.runOf(selection.container, selection.head.block)?.key?.let { FieldKey.RunField(it) }
    }

    /**
     * Makes the fields show the model: their text, the selection in the field that holds it, and
     * the focus there when it moved to another field. The field being edited is left alone, as its
     * edit answers for it.
     */
    private fun syncFields() {
        val target = fieldOf(state.selection)
        for ((key, field) in fields) {
            if (key == editing) continue
            val text = textOf(key) ?: continue
            val range = if (key == target) selectionIn(key) else null
            if (field.text.toString() != text || (range != null && field.selection != range)) {
                field.edit {
                    if (toString() != text) replace(0, length, text)
                    selection = range ?: TextRange(selection.start.coerceAtMost(length), selection.end.coerceAtMost(length))
                }
            }
        }
        if (target != null && target != focusedField && focused) focusRequest = target
    }

    /** Returns the text a field shows, or null when its run or code block is gone. */
    internal fun textOf(key: FieldKey): String? = when (key) {
        is FieldKey.CodeField -> state.doc.codeById(key.id)?.text
        is FieldKey.RunField -> runOf(key.key)?.text
    }

    /** Returns the selection of the model in a field's text, when the selection is there. */
    internal fun selectionIn(key: FieldKey): TextRange? = when (val selection = state.selection) {
        is CodeSelection -> if (key == FieldKey.CodeField(selection.code)) TextRange(selection.anchor, selection.head) else null
        is TextSelection -> {
            val range = state.textRange()
            if (range != null && key == FieldKey.RunField(range.run.key)) {
                val anchor = range.run.offsetOf(selection.anchor) ?: range.from
                val head = range.run.offsetOf(selection.head) ?: range.to
                TextRange(anchor, head)
            } else {
                null
            }
        }
    }

    /** Returns the run with a key, in the root or in a table cell. */
    internal fun runOf(key: Long): Run? {
        state.doc.runsIn(RootContainer).firstOrNull { it.key == key }?.let { return it }
        for (item in state.doc.items) {
            if (item !is Table) continue
            item.cells.forEachIndexed { r, row ->
                row.forEachIndexed { c, cell ->
                    if (cell.first().id == key) return state.doc.runsIn(CellContainer(item.id, r, c)).first()
                }
            }
        }
        return null
    }

    /** Returns the text field state of a field, made when first asked. */
    internal fun fieldState(key: FieldKey): TextFieldState = fields.getOrPut(key) {
        TextFieldState(textOf(key).orEmpty(), selectionIn(key) ?: TextRange.Zero)
    }

    /** Returns where a run's field stands, kept up to date by the field. */
    internal fun placement(key: Long): FieldPlacement = placements.getOrPut(key) { FieldPlacement() }

    /** Forgets a field that left the screen. */
    internal fun releaseField(key: FieldKey) {
        fields.remove(key)
        if (key is FieldKey.RunField) placements.remove(key.key)
        if (focusedField == key) focusedField = null
    }

    /** Takes a field's focus. */
    internal fun onFocus(key: FieldKey, hasFocus: Boolean) {
        if (hasFocus) {
            focusedField = key
            focused = true
            if (focusRequest == key) focusRequest = null
        } else if (focusedField == key) {
            focusedField = null
            focused = false
        }
    }

    /** Takes a field's selection as the model's, when the field has the focus. */
    internal fun onFieldSelection(key: FieldKey, range: TextRange) {
        if (editing != null || key != focusedField) return
        val selection = when (key) {
            is FieldKey.CodeField -> CodeSelection(key.id, range.start, range.end)
            is FieldKey.RunField -> runOf(key.key)?.let { it.select(range.start, range.end) } ?: return
        }
        if (selection != state.selection) select(selection)
    }

    /**
     * Turns what was typed in a run's field into a change of the model: Enter starts a block,
     * Backspace at the start of a block takes its list or quote first, other edits replace text,
     * and a typed character may complete a shortcut.
     *
     * @param original The field's text before the edit, which the model's run should match.
     * @param from Where the replaced text starts in [original].
     * @param to Where it ends.
     * @param inserted The new text.
     * @param before The field's selection before the edit.
     * @return The field's text and selection afterwards, or null to leave the field as it is.
     */
    internal fun editRun(key: Long, original: String, from: Int, to: Int, inserted: String, before: TextRange): FieldEdit? {
        val run = runOf(key) ?: return null
        if (run.text != original || !editable) return FieldEdit(run.text, before)
        editing = FieldKey.RunField(key)
        try {
            val base = state.copy(selection = run.select(before.start, before.end))
            when {
                inserted == "\n" -> commit(base.copy(selection = run.select(from, to)).enter(language))
                inserted.isEmpty() && to - from == 1 && original[from] == BlockSeparator && before.collapsed && before.start == to -> {
                    val undo = ruleUndo
                    if (undo != null) undo(undo) else commit(base.copy(selection = run.cursor(to)).backspaceAtStart() ?: base.replaceText(run.container, key, from, to, ""))
                }
                inserted.isEmpty() && to - from == 1 && ruleUndo != null -> undo(ruleUndo!!)
                else -> {
                    // Replacing text keeps the format of the first replaced character, as composing a word does.
                    val (index, offset) = run.locate(from)
                    val sameBlock = run.locate(to).first == index
                    val format = if (to > from && sameBlock && base.stored == null) {
                        formatAt(run.blocks[index].inline, offset + 1)
                    } else {
                        null
                    }
                    val typing = inserted.length <= 1 && to - from <= 1
                    val next = base.replaceText(run.container, key, from, to, inserted, format)
                    commit(next, if (typing) "typing" else null)
                    if (inserted.length == 1 && next != null) applyRule(inserted[0])
                }
            }
        } finally {
            editing = null
        }
        val now = runOf(key) ?: return null
        val selection = selectionIn(FieldKey.RunField(key)) ?: TextRange(before.start.coerceAtMost(now.text.length))
        if (fieldOf(state.selection) != FieldKey.RunField(key) && focused) focusRequest = fieldOf(state.selection)
        return FieldEdit(now.text, selection)
    }

    /** Applies the shortcut a typed character completes; undo right after it brings back what was typed. */
    private fun applyRule(typed: Char) {
        when (val result = state.inputRule(typed)) {
            is RuleResult.Changed -> {
                val before = state
                commit(result.state)
                ruleUndo = before
            }
            is RuleResult.NotePicker -> host.onNotePicker(result.container, result.key, result.from, result.to)
            null -> Unit
        }
    }

    /** Takes back the last shortcut, keeping what was typed. */
    private fun undo(to: EditorState) {
        ruleUndo = null
        history.undo(state)
        historyVersion++
        state = to.copy(selection = to.doc.validSelection(to.selection))
        dirty = true
        host.onChanged()
        syncFields()
    }

    /**
     * Turns what was typed in a code block into a change of its plain text: Enter keeps the
     * indentation of the line.
     *
     * @return The field's text and selection afterwards, or null to leave it.
     */
    internal fun editCode(id: Long, original: String, from: Int, to: Int, inserted: String, before: TextRange): FieldEdit? {
        val code = state.doc.codeById(id) ?: return null
        if (code.text != original || !editable) return FieldEdit(code.text, before)
        editing = FieldKey.CodeField(id)
        try {
            val base = state.copy(selection = CodeSelection(id, from, to))
            val next = if (inserted == "\n") base.codeNewline() else base.replaceCode(id, from, to, inserted)
            commit(next, if (inserted.length <= 1 && to - from <= 1) "typing" else null)
        } finally {
            editing = null
        }
        val now = state.doc.codeById(id) ?: return null
        return FieldEdit(now.text, selectionIn(FieldKey.CodeField(id)) ?: TextRange(now.text.length))
    }

    /** Runs a command on the current state; returns whether it applied. */
    private fun run(command: EditorState.() -> EditorState?): Boolean = editable && commit(state.command())

    /** Toggles an inline format. */
    override fun toggleMark(mark: Mark) {
        run { toggleMark(mark) }
    }

    /** Applies a paragraph style. */
    override fun setBlockStyle(style: BlockStyle) {
        run { setBlockStyle(style) }
    }

    /** Makes the selection a list of a kind, changes its list's kind or ends the list. */
    override fun toggleList(kind: ListKind) {
        run { toggleList(kind) }
    }

    /** Nests the selected list items a level deeper. */
    override fun indent() {
        run { indent() }
    }

    /** Moves the selected list items a level up. */
    override fun outdent() {
        run { outdent() }
    }

    /** Sets the numbering style of the list at the cursor; empty for `1.`. */
    override fun setNumbering(style: String) {
        run { setNumbering(style) }
    }

    /** Makes the item at the cursor start the numbering at a number. */
    override fun startNumbering(start: Int) {
        run { startNumbering(start) }
    }

    /** Sets the text colour or the highlight; null for Default. */
    override fun setColor(highlight: Boolean, key: String?) {
        run { setColor(highlight, key) }
    }

    /** Inserts a link or changes the one at the cursor. */
    fun setLink(text: String, href: String) = run { setLink(text, href) }

    /** Removes the link at the cursor or from the selection. */
    override fun removeLink() {
        run { removeLink() }
    }

    /** Returns what the link dialog starts with. */
    fun linkDialog(): Pair<String, String> = state.linkDialogText()

    /** Opens the note picker for the cursor, as Note link of the insert menu does. */
    override fun requestNoteLink() {
        val range = state.textRange() ?: return
        host.onNotePicker(range.run.container, range.run.key, range.from, range.from)
    }

    /** Inserts a link to a note in place of typed brackets or the selection. */
    fun insertNoteLink(container: Container, key: Long, from: Int, to: Int, id: String, title: String) =
        run { insertNoteLink(container, key, from, to, id, title) }

    /** Inserts a code block. */
    override fun insertCodeBlock() {
        run { insertCodeBlock() }
    }

    /** Inserts a divider. */
    override fun insertDivider() {
        run { insertDivider() }
    }

    /** Inserts a table of a size. */
    override fun insertTable(rows: Int, columns: Int, header: Boolean) {
        run { insertTable(rows, columns, header) }
    }

    /** Runs a command of the table menu. */
    override fun tableAction(action: TableAction) {
        run { tableAction(action) }
    }

    /** Sets a table's column widths, as a drag ends. */
    fun setColumnWidths(table: Long, widths: List<Int>) = run { withColumnWidths(table, widths) }

    /** Sets a table's width, as a drag of its edge ends. */
    fun setTableWidth(table: Long, width: Int) = run { setTableWidth(table, width) }

    /** Sets the language of a code block. */
    fun setCodeLanguage(id: Long, value: String) = run { setCodeLanguage(id, value) }

    /** Sets the title of a code block. */
    fun setCodeTitle(id: Long, value: String) = run { setCodeTitle(id, value) }

    /** Toggles the checklist item at the cursor. */
    fun toggleChecked() = run { toggleChecked() }

    /** Toggles a checklist item, as a tap on its box does. */
    internal fun toggleChecked(container: Container, block: Long) = run { toggleChecked(container, block) }

    /** Inserts stored files at the cursor. */
    fun insertFiles(files: List<InsertedFile>) = run { insertFiles(noteId, files) }

    /** Sets the selected image's width. */
    override fun setImageWidth(width: Int) {
        run { setImage(width = width) }
    }

    /** Sets the selected image's wrapping. */
    override fun setImageWrap(wrap: String) {
        run { setImage(wrap = wrap) }
    }

    /** Removes the selected image or chip. */
    override fun removeObject() {
        run { removeObject() }
    }

    /** Shows the selected image full screen. */
    override fun openSelectedImage() {
        val image = state.selectedObject() as? ImageObject ?: return
        openImage(image)
    }

    /** Shows an image full screen among the note's images; a pending one or another note's is not shown. */
    internal fun openImage(image: ImageObject) {
        if (image.pending.isNotEmpty() || image.note != noteId) return
        host.onOpenImage(image.file, images())
    }

    /** Takes back the last change. */
    override fun undo() {
        val previous = history.undo(state) ?: return
        historyVersion++
        ruleUndo = null
        state = withStored(previous).let { it.copy(selection = it.doc.validSelection(it.selection)) }
        dirty = true
        updateFind()
        host.onChanged()
        syncFields()
    }

    /** Makes the last undone change again. */
    override fun redo() {
        val next = history.redo(state) ?: return
        historyVersion++
        ruleUndo = null
        state = withStored(next).let { it.copy(selection = it.doc.validSelection(it.selection)) }
        dirty = true
        updateFind()
        host.onChanged()
        syncFields()
    }

    /** Puts files stored meanwhile in place of objects an undo or redo brings back still waiting. */
    private fun withStored(target: EditorState): EditorState {
        var doc = target.doc
        for ((token, file) in stored) doc = doc.resolvePending(token, noteId, file)
        return if (doc === target.doc) target else target.copy(doc = doc)
    }

    /**
     * Takes the screen's answer to [EditorHost.onStore]: the stored file takes the place of the
     * objects waiting for it, or they go when it could not be stored. Not an undo step of its own.
     */
    fun stored(token: String, file: InsertedFile?) {
        stored[token] = file
        val doc = state.doc.resolvePending(token, noteId, file)
        if (doc != state.doc) commit(state.copy(doc = doc), record = false)
    }

    /** Returns a new token for an object waiting to be stored. */
    private fun nextToken(): String = "#${++lastToken}"

    /**
     * Pastes content of the clipboard: HTML copied from a note reads back in the note vocabulary,
     * other HTML through the paste cleaning; pictures and other notes' files wait until the screen
     * stores them in this note's folder.
     *
     * @return Whether the paste was taken; plain text is left to the field.
     */
    internal fun pasteClip(clip: ClipData): Boolean {
        if (!editable || clip.itemCount == 0) return false
        val images = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            .filter { clip.description.hasMimeType("image/*") }
        if (images.isNotEmpty()) {
            val pieces = images.map { uri ->
                val token = nextToken()
                host.onStore(StoreRequest(token, uri.toString(), true))
                ImageObject("", "", pending = token)
            }
            val range = state.textRange() ?: return true
            commit(state.insertInlineObjects(range, pieces))
            return true
        }
        val html = clip.getItemAt(0).htmlText ?: return false
        if (state.selection is CodeSelection) return false
        val content = if (html.startsWith(CopyMarker)) parseBody(html.substringAfter("-->")) else parsePastedHtml(html)
        val (prepared, requests) = content.withPendingObjects(noteId, ::nextToken)
        commit(state.paste(prepared))
        requests.forEach(host::onStore)
        return true
    }

    /** Inserts pictures at a range, separated by spaces, for pasted pictures. */
    private fun EditorState.insertInlineObjects(range: RunRange, pieces: List<ImageObject>): EditorState? {
        val spaced = pieces.flatMapIndexed { i, piece -> if (i == 0) listOf(piece) else listOf(TextRun(" ", cursorFormat()), piece) }
        return insertInline(range, spaced)
    }

    /**
     * Returns what a copy of the selection puts on the clipboard: HTML in the note vocabulary,
     * marked as copied from a note, with its plain text; null to let the field copy its text.
     */
    internal fun copyEntry(): ClipEntry? {
        if (state.selection is CodeSelection) return null
        val fragment = state.selectedFragment() ?: return null
        val html = CopyMarker + noteId + "-->" + serializeBody(fragment)
        return ClipEntry(ClipData.newHtmlText(null, fragment.plainText(), html))
    }

    /**
     * Handles a key of a hardware keyboard, and Backspace at the start of a run, which changes no
     * text.
     *
     * @return Whether the key was taken.
     */
    internal fun onKey(key: FieldKey, shortcut: Shortcut): Boolean {
        if (shortcut == Shortcut.Find) {
            host.onFindBar()
            return true
        }
        if (shortcut == Shortcut.FindNext || shortcut == Shortcut.FindPrevious) {
            stepFind(if (shortcut == Shortcut.FindNext) 1 else -1)
            return true
        }
        if (!editable) return false
        val inCode = key is FieldKey.CodeField
        return when (shortcut) {
            Shortcut.Bold -> !inCode && run { toggleMark(Mark.Bold) }
            Shortcut.Italic -> !inCode && run { toggleMark(Mark.Italic) }
            Shortcut.Underline -> !inCode && run { toggleMark(Mark.Underline) }
            Shortcut.Strike -> !inCode && run { toggleMark(Mark.Strike) }
            Shortcut.Superscript -> !inCode && run { toggleMark(Mark.Sup) }
            Shortcut.Subscript -> !inCode && run { toggleMark(Mark.Sub) }
            Shortcut.Paragraph -> !inCode && run { setBlockStyle(BlockStyle.Paragraph) }
            Shortcut.Heading1 -> !inCode && run { setBlockStyle(BlockStyle.Heading1) }
            Shortcut.Heading2 -> !inCode && run { setBlockStyle(BlockStyle.Heading2) }
            Shortcut.Heading3 -> !inCode && run { setBlockStyle(BlockStyle.Heading3) }
            Shortcut.NumberedList -> !inCode && run { toggleList(ListKind.Number) }
            Shortcut.BulletedList -> !inCode && run { toggleList(ListKind.Bullet) }
            Shortcut.Checklist -> !inCode && run { toggleList(ListKind.Check) }
            Shortcut.Link -> {
                if (!inCode) linkDialog().let { (text, href) -> host.onLinkDialog(text, href) }
                !inCode
            }
            Shortcut.Undo -> true.also { undo() }
            Shortcut.Redo -> true.also { redo() }
            Shortcut.Tab -> when {
                inCode -> run { codeIndent() }
                state.currentBlock()?.list != null -> run { indent() } || true
                state.currentCell() != null -> run { goToCell(1) }
                else -> true
            }
            Shortcut.ShiftTab -> when {
                inCode -> run { codeOutdent() }
                state.currentBlock()?.list != null -> run { outdent() } || true
                state.currentCell() != null -> run { goToCell(-1) }
                else -> true
            }
            Shortcut.LineBreak -> !inCode && run {
                val range = textRange() ?: return@run null
                replaceText(range.run.container, range.run.key, range.from, range.to, LineSeparator.toString())
            }
            Shortcut.ControlEnter -> if (inCode) run { leaveCode() } else run { toggleChecked() }
            Shortcut.BackspaceAtStart -> when (key) {
                is FieldKey.CodeField -> run { codeToParagraph() }
                is FieldKey.RunField -> run { backspaceAtStart() }
            }
            Shortcut.Find, Shortcut.FindNext, Shortcut.FindPrevious -> true
        }
    }

    /** Copies the text of a code block, as its Copy button does. */
    internal fun copyCode(id: Long) {
        state.doc.codeById(id)?.let { host.onCopied(it.text) }
    }

    /** Opens the language picker of a code block. */
    internal fun pickLanguage(code: CodeBlock) {
        if (editable) host.onLanguagePicker(CodeRef(code.id, code.language, code.title))
    }

    /** Opens the title dialog of a code block. */
    internal fun editTitle(code: CodeBlock) {
        if (editable) host.onTitleDialog(CodeRef(code.id, code.language, code.title))
    }

    /** Selects an object of a run, as a tap on it does in an editable note. */
    internal fun selectObject(key: Long, offset: Int) {
        val run = runOf(key) ?: return
        focusRequest = FieldKey.RunField(key)
        select(run.select(offset, offset + 1))
    }

    /**
     * Starts dragging an image or a chip to another place: it is selected and stays where it is
     * until it is dropped.
     *
     * @param position Where the finger is, in the window.
     */
    internal fun startDrag(key: Long, offset: Int, position: Offset) {
        if (!editable) return
        selectObject(key, offset)
        dragStart = position
        dragPosition = position
        dragging = true
        drop = null
    }

    /** Follows the dragging finger, at a position in the window, to the text under it. */
    internal fun dragTo(position: Offset) {
        if (!dragging) return
        dragPosition = position
        drop = dropAt(position)
    }

    /**
     * Ends a drag; a drop moves the object to the place under the finger as one undo step, and a
     * cancelled drag or a place beside the object moves nothing.
     */
    internal fun endDrag(dropped: Boolean) {
        val target = drop
        dragging = false
        drop = null
        if (!dropped || target == null) return
        val place = runOf(target.key)?.cursor(target.offset) ?: return
        run { moveSelectedObject(place) }
    }

    /** Returns the place in a run's text under a position in the window, or null outside the text of every run. */
    private fun dropAt(position: Offset): DropTarget? {
        for ((key, placement) in placements) {
            val coordinates = placement.coordinates?.takeIf { it.isAttached } ?: continue
            val layout = placement.layout ?: continue
            val local = coordinates.windowToLocal(position)
            if (local.x < 0 || local.y < 0 || local.x > coordinates.size.width || local.y > coordinates.size.height) continue
            val text = runOf(key)?.text ?: continue
            if (text != layout.layoutInput.text.text.removeSuffix(EmptyBlockFiller.toString())) continue
            return DropTarget(key, layout.offsetAt(local).coerceAtMost(text.length))
        }
        return null
    }

    /** Sets the query of find and selects the first match at or after the cursor. */
    fun setFindQuery(query: String) {
        val matches = state.doc.findMatches(query)
        val cursor = state.doc.globalOffset(state.selection, head = false)
        val after = matches.indexOfFirst { matchStart(it) >= cursor }
        find = FindState(query, matches, if (matches.isEmpty()) -1 else if (after < 0) 0 else after)
        selectMatch()
    }

    /** Returns where a match starts as a number of the whole document. */
    private fun matchStart(match: Match): Int = state.doc.globalOffset(match.selection(), head = false)

    /** Moves to the next or the previous match, around the note. */
    fun stepFind(step: Int) {
        val find = find
        if (find.matches.isEmpty()) return
        this.find = find.copy(current = (find.current + step + find.matches.size) % find.matches.size)
        selectMatch()
    }

    /** Closes find and clears its highlights. */
    fun closeFind() {
        find = FindState()
        host.onFound(0, -1)
    }

    /** Finds the matches again after a change of the document. */
    private fun updateFind() {
        val find = find
        if (find.query.isEmpty()) return
        val matches = state.doc.findMatches(find.query)
        this.find = find.copy(matches = matches, current = minOf(find.current, matches.size - 1))
        host.onFound(matches.size, this.find.current)
    }

    /** Selects the current match and reports the count. */
    private fun selectMatch() {
        val find = find
        find.matches.getOrNull(find.current)?.let {
            val selection = it.selection()
            if (focused) focusRequest = fieldOf(selection)
            select(selection)
        }
        host.onFound(find.matches.size, find.current)
    }

    /** Describes the selection for the formatting bar. */
    private fun describe(): FormatState {
        historyVersion
        val state = state
        val selection = state.selection
        val code = (selection as? CodeSelection)?.let { state.doc.codeById(it.code) }
        val block = state.currentBlock()
        val cursor = state.cursorFormat()
        val cell = state.currentCell()
        val table = cell?.let { state.doc.tableById(it.table) }
        val selected = state.selectedObject()
        return FormatState(
            editable = editable,
            block = if (code != null) null else block?.let(::blockStyleOf) ?: BlockStyle.Paragraph,
            marks = if (code != null) emptySet() else Mark.entries.filter { state.markActive(it) }.toSet(),
            color = cursor.color,
            highlight = cursor.highlight,
            list = block?.list?.kind,
            numbering = block?.list?.takeIf { it.kind == ListKind.Number }?.style,
            canIndent = block?.list != null && state.canIndent(),
            canOutdent = block?.list != null && state.canOutdent(),
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            table = table != null,
            tableHeader = (table?.headerRows ?: 0) > 0,
            code = code?.let { CodeRef(it.id, it.language, it.title) },
            link = state.linkAtCursor()?.href,
            selected = selected?.let {
                SelectedObject(it is ImageObject, it.file, (it as? ImageObject)?.width ?: 0, (it as? ImageObject)?.wrap.orEmpty(), it.pending.isNotEmpty())
            },
        )
    }
}

/** The keys of a hardware keyboard the editor handles. */
internal enum class Shortcut {
    Bold, Italic, Underline, Strike, Superscript, Subscript, Paragraph, Heading1, Heading2, Heading3, NumberedList,
    BulletedList, Checklist, Link, Find, FindNext, FindPrevious, Undo, Redo, Tab, ShiftTab, LineBreak,
    ControlEnter, BackspaceAtStart,
}
