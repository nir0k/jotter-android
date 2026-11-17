package io.github.nir0k.jotter.editor

import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import io.github.nir0k.jotter.core.body.BlockStyle
import io.github.nir0k.jotter.core.body.Container
import io.github.nir0k.jotter.core.body.ListKind
import io.github.nir0k.jotter.core.body.Mark
import io.github.nir0k.jotter.core.body.Match
import io.github.nir0k.jotter.core.body.StoreRequest

/** Where a dragged object would be dropped: an offset in the text of a run's field. */
internal data class DropTarget(val key: Long, val offset: Int)

/** Where a run's field stands in the window and how its text is laid out, to find the text under a dragged object. */
internal class FieldPlacement {
    var coordinates: LayoutCoordinates? = null
    var layout: TextLayoutResult? = null
}

/**
 * What the formatting bar shows for the selection.
 *
 * @param block The paragraph style of the block at the selection's start; null in a code block.
 * @param marks The inline formats on for the selection.
 * @param list The kind of the list the cursor is in.
 * @param numbering The numbering style of a numbered list; empty for `1.`.
 * @param table Whether the cursor is in a table, and [tableHeader] whether its first row is a header row.
 * @param code The code block the cursor is in.
 * @param link The address of the link at the cursor.
 * @param selected The selected image or attachment chip.
 */
data class FormatState(
    val editable: Boolean = false,
    val block: BlockStyle? = BlockStyle.Paragraph,
    val marks: Set<Mark> = emptySet(),
    val color: String? = null,
    val highlight: String? = null,
    val list: ListKind? = null,
    val numbering: String? = null,
    val canIndent: Boolean = false,
    val canOutdent: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val table: Boolean = false,
    val tableHeader: Boolean = false,
    val code: CodeRef? = null,
    val link: String? = null,
    val selected: SelectedObject? = null,
)

/**
 * An image or an attachment chip selected in the editor, whose actions lead the formatting bar.
 *
 * @param width An image's width in percent of its column; 0 for full width.
 * @param wrap An image's wrapping: empty for in line with text, `square` or `top-bottom`.
 * @param pending Whether it is a pasted object still being stored, which has no file yet.
 */
data class SelectedObject(val image: Boolean, val file: String, val width: Int, val wrap: String, val pending: Boolean)

/** A code block of the note, as its language and title pickers change it. */
data class CodeRef(val id: Long, val language: String, val title: String)

/**
 * The selection as two numbers of the whole document, which a rotation keeps.
 *
 * @param anchor Where the selection starts.
 * @param head Where the cursor is.
 */
data class EditorSelection(val anchor: Int, val head: Int)

/** The matches of find in the note and the current one (-1 for none). */
data class FindState(val query: String = "", val matches: List<Match> = emptyList(), val current: Int = -1)

/** Identifies a text field of the editor: a run of blocks by its key, or a code block. */
sealed interface FieldKey {
    /** A run of blocks, by the identifier of its first block. */
    data class RunField(val key: Long) : FieldKey

    /** A code block. */
    data class CodeField(val id: Long) : FieldKey
}

/** What the editor asks of the screen. */
interface EditorHost {
    /** The note changed; the screen saves it a moment after typing stops. */
    fun onChanged() {}

    /** A pasted object is to be stored in the note's folder; the answer goes to [NoteEditorController.stored]. */
    fun onStore(request: StoreRequest) {}

    /** `[[` was typed: the note picker opens; a choice goes to [NoteEditorController.insertNoteLink]. */
    fun onNotePicker(container: Container, key: Long, from: Int, to: Int) {}

    /** A link was tapped in a read-only note. */
    fun onOpenLink(href: String) {}

    /** An attachment chip was tapped in a read-only note. */
    fun onOpenAttachment(file: String) {}

    /** An image is to be shown full screen among the note's images. */
    fun onOpenImage(file: String, images: List<String>) {}

    /** The language of a code block is to be chosen. */
    fun onLanguagePicker(code: CodeRef) {}

    /** The title of a code block is to be edited. */
    fun onTitleDialog(code: CodeRef) {}

    /** The text of a code block was copied. */
    fun onCopied(text: String) {}

    /** The link dialog is to open with a text and an address, from the keyboard's shortcut. */
    fun onLinkDialog(text: String, href: String) {}

    /** Find is to open from the keyboard's shortcut. */
    fun onFindBar() {}

    /** The find bar's count changed. */
    fun onFound(count: Int, current: Int) {}
}

/** What a field's text and selection become after an edit. */
internal class FieldEdit(val text: String, val selection: TextRange)
