package io.github.nir0k.jotter.editor

import io.github.nir0k.jotter.core.body.BlockStyle
import io.github.nir0k.jotter.core.body.ListKind
import io.github.nir0k.jotter.core.body.Mark
import io.github.nir0k.jotter.core.body.TableAction

/**
 * The commands of the formatting bar: the editor gives them, and a screenshot of the bar takes the
 * defaults, which do nothing.
 */
interface EditorCommands {
    /** Toggles an inline format. */
    fun toggleMark(mark: Mark) {}
    /** Applies a paragraph style. */
    fun setBlockStyle(style: BlockStyle) {}
    /** Makes, changes or ends a list. */
    fun toggleList(kind: ListKind) {}
    /** Nests the selected list items a level deeper. */
    fun indent() {}
    /** Moves the selected list items a level up. */
    fun outdent() {}
    /** Sets the numbering style of the list at the cursor. */
    fun setNumbering(style: String) {}
    /** Starts the numbering at a number from the item at the cursor. */
    fun startNumbering(start: Int) {}
    /** Sets the text colour or the highlight. */
    fun setColor(highlight: Boolean, key: String?) {}
    /** Removes the link at the cursor. */
    fun removeLink() {}
    /** Opens the note picker for a link at the cursor. */
    fun requestNoteLink() {}
    /** Inserts a code block. */
    fun insertCodeBlock() {}
    /** Inserts a divider. */
    fun insertDivider() {}
    /** Inserts a table. */
    fun insertTable(rows: Int, columns: Int, header: Boolean) {}
    /** Runs a command of the table menu. */
    fun tableAction(action: TableAction) {}
    /** Sets the selected image's width. */
    fun setImageWidth(width: Int) {}
    /** Sets the selected image's wrapping. */
    fun setImageWrap(wrap: String) {}
    /** Removes the selected image or chip. */
    fun removeObject() {}
    /** Shows the selected image full screen. */
    fun openSelectedImage() {}
    /** Takes back the last change. */
    fun undo() {}
    /** Makes the last undone change again. */
    fun redo() {}
}
