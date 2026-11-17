package io.github.nir0k.jotter.feature.note

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.core.body.BlockStyle
import io.github.nir0k.jotter.core.body.ListKind
import io.github.nir0k.jotter.core.body.Mark
import io.github.nir0k.jotter.core.body.TableAction
import io.github.nir0k.jotter.editor.EditorCommands
import io.github.nir0k.jotter.editor.FormatState
import io.github.nir0k.jotter.designsystem.components.AppTextField
import io.github.nir0k.jotter.designsystem.components.Choice
import io.github.nir0k.jotter.designsystem.components.ChoiceDialog
import io.github.nir0k.jotter.designsystem.components.ColourLook
import io.github.nir0k.jotter.designsystem.components.ColourPickerDialog
import io.github.nir0k.jotter.designsystem.components.FormDialog
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.theme.Shapes
import androidx.compose.runtime.mutableIntStateOf

/** The dialogs the formatting bar opens. */
private enum class BarDialog { Paragraph, Numbering, StartAt, TextColor, Highlight, Table, ImageWidth, ImageWrap }

/** Where the insert menu takes files from. */
enum class FileSource { Camera, Photos, ImageFile, Attachment }

/** What is done with the selected attachment chip's file. */
enum class FileAction { Open, Share, Save }

/**
 * A button of the formatting bar: its glyph in a full touch target; a long press explains it. A
 * toggle is drawn in the primary colour on a tinted ground while it is on.
 *
 * @param icon The glyph.
 * @param label What the button does, shown on a long press and read by TalkBack.
 * @param active Whether the format is on, for a toggle; null for an action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean? = null, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        if (active == null) {
            IconButton(onClick = onClick, enabled = enabled) {
                Icon(icon, label, tint = if (enabled) scheme.onSurfaceVariant else scheme.outline, modifier = Modifier.size(Dimens.IconMenu))
            }
        } else {
            IconToggleButton(
                checked = active,
                onCheckedChange = { onClick() },
                enabled = enabled,
                colors = IconButtonDefaults.iconToggleButtonColors(
                    contentColor = scheme.onSurfaceVariant,
                    checkedContentColor = scheme.primary,
                    checkedContainerColor = scheme.primary.copy(alpha = 0.12f),
                ),
            ) {
                Icon(icon, label, modifier = Modifier.size(Dimens.IconMenu))
            }
        }
    }
}

/** A thin line between the groups of the bar. */
@Composable
private fun GroupLine() {
    VerticalDivider(Modifier.padding(vertical = 12.dp).size(width = 1.dp, height = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * FormattingBar sits above the keyboard while the note is edited: a scrollable row of groups —
 * paragraph style; inline formats; text colour and highlight; lists with their numbering and
 * indentation; insert; undo and redo. While the cursor is in a link or a table, or an image or an
 * attachment chip is selected, their actions lead the row.
 *
 * @param state What the selection is in.
 * @param commands The editor's commands.
 * @param onLinkDialog Opens the link dialog for the selection or the link at the cursor.
 * @param onOpenLink Opens the link at the cursor.
 * @param onFile Opens, shares or saves the selected attachment's file.
 * @param onInsert Picks files to insert from a source.
 */
@Composable
fun FormattingBar(
    state: FormatState,
    commands: EditorCommands,
    onLinkDialog: () -> Unit,
    onOpenLink: (String) -> Unit,
    onFile: (FileAction, String) -> Unit,
    onInsert: (FileSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialog by rememberSaveable { mutableStateOf<BarDialog?>(null) }
    val inCode = state.code != null
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                state.link?.let { href ->
                    BarButton(AppIcons.OpenExternal, stringResource(R.string.link_open), { onOpenLink(href) })
                    BarButton(AppIcons.Edit, stringResource(R.string.link_edit), onLinkDialog)
                    BarButton(AppIcons.Close, stringResource(R.string.link_remove), commands::removeLink)
                    GroupLine()
                }
                state.selected?.takeIf { !it.pending }?.let { selected ->
                    if (selected.image) {
                        BarButton(AppIcons.Show, stringResource(R.string.image_open), commands::openSelectedImage)
                        BarButton(AppIcons.Image, stringResource(R.string.image_size), { dialog = BarDialog.ImageWidth })
                        BarButton(wrapIcon(selected.wrap), stringResource(R.string.image_wrap), { dialog = BarDialog.ImageWrap })
                    } else {
                        BarButton(AppIcons.OpenExternal, stringResource(R.string.attachment_open), { onFile(FileAction.Open, selected.file) })
                        BarButton(AppIcons.Share, stringResource(R.string.attachment_share), { onFile(FileAction.Share, selected.file) })
                        BarButton(AppIcons.Download, stringResource(R.string.attachment_save), { onFile(FileAction.Save, selected.file) })
                    }
                    BarButton(AppIcons.Delete, stringResource(if (selected.image) R.string.image_remove else R.string.attachment_remove), commands::removeObject)
                    GroupLine()
                }
                if (state.table) {
                    TableMenu(state.tableHeader, commands)
                    GroupLine()
                }
                BarButton(paragraphIcon(state.block), stringResource(R.string.format_paragraph_style), { dialog = BarDialog.Paragraph }, enabled = !inCode)
                GroupLine()
                for ((mark, icon, label) in listOf(
                    Triple(Mark.Bold, AppIcons.Bold, R.string.format_bold),
                    Triple(Mark.Italic, AppIcons.Italic, R.string.format_italic),
                    Triple(Mark.Underline, AppIcons.Underline, R.string.format_underline),
                    Triple(Mark.Strike, AppIcons.Strikethrough, R.string.format_strike),
                    Triple(Mark.Sup, AppIcons.Superscript, R.string.format_superscript),
                    Triple(Mark.Sub, AppIcons.Subscript, R.string.format_subscript),
                    Triple(Mark.Code, AppIcons.InlineCode, R.string.format_inline_code),
                )) {
                    BarButton(icon, stringResource(label), { commands.toggleMark(mark) }, active = mark in state.marks, enabled = !inCode)
                }
                GroupLine()
                BarButton(AppIcons.TextColor, stringResource(R.string.format_text_color), { dialog = BarDialog.TextColor }, enabled = !inCode)
                BarButton(AppIcons.Highlight, stringResource(R.string.format_highlight), { dialog = BarDialog.Highlight }, enabled = !inCode)
                GroupLine()
                BarButton(AppIcons.BulletedList, stringResource(R.string.format_bulleted), { commands.toggleList(ListKind.Bullet) }, active = state.list == ListKind.Bullet, enabled = !inCode)
                BarButton(AppIcons.NumberedList, stringResource(R.string.format_numbered), { commands.toggleList(ListKind.Number) }, active = state.list == ListKind.Number, enabled = !inCode)
                if (state.list == ListKind.Number) BarButton(AppIcons.ChevronDown, stringResource(R.string.format_numbering), { dialog = BarDialog.Numbering })
                BarButton(AppIcons.Checklist, stringResource(R.string.format_checklist), { commands.toggleList(ListKind.Check) }, active = state.list == ListKind.Check, enabled = !inCode)
                BarButton(AppIcons.Outdent, stringResource(R.string.format_outdent), commands::outdent, enabled = state.canOutdent)
                BarButton(AppIcons.Indent, stringResource(R.string.format_indent), commands::indent, enabled = state.canIndent)
                GroupLine()
                InsertMenu(commands, onLinkDialog, onTable = { dialog = BarDialog.Table }, onInsert = onInsert, enabled = !inCode)
                GroupLine()
                BarButton(AppIcons.Undo, stringResource(R.string.format_undo), commands::undo, enabled = state.canUndo)
                BarButton(AppIcons.Redo, stringResource(R.string.format_redo), commands::redo, enabled = state.canRedo)
            }
        }
    }
    when (dialog) {
        BarDialog.Paragraph -> ParagraphDialog(state.block ?: BlockStyle.Paragraph, commands::setBlockStyle, { dialog = null })
        BarDialog.Numbering -> NumberingDialog(
            state.numbering.orEmpty(),
            onStyle = commands::setNumbering,
            onRestart = { commands.startNumbering(1) },
            onStartAt = { dialog = BarDialog.StartAt },
            onDismiss = { if (dialog == BarDialog.Numbering) dialog = null },
        )
        BarDialog.StartAt -> StartAtDialog(commands::startNumbering, { dialog = null })
        BarDialog.TextColor -> ColourPickerDialog(stringResource(R.string.format_text_color), state.color, { commands.setColor(false, it) }, { dialog = null }, look = ColourLook.Text)
        BarDialog.Highlight -> ColourPickerDialog(stringResource(R.string.format_highlight), state.highlight, { commands.setColor(true, it) }, { dialog = null }, look = ColourLook.Highlight)
        BarDialog.Table -> TableDialog(commands::insertTable, { dialog = null })
        BarDialog.ImageWidth -> ImageWidthDialog(state.selected?.width, commands::setImageWidth, { dialog = null })
        BarDialog.ImageWrap -> ImageWrapDialog(state.selected?.wrap, commands::setImageWrap, { dialog = null })
        null -> Unit
    }
}

/** Returns the glyph of a paragraph style, which the paragraph style button shows. */
private fun paragraphIcon(block: BlockStyle?): ImageVector = when (block) {
    BlockStyle.Heading1 -> AppIcons.Heading1
    BlockStyle.Heading2 -> AppIcons.Heading2
    BlockStyle.Heading3 -> AppIcons.Heading3
    BlockStyle.Quote -> AppIcons.Quote
    null -> AppIcons.Code
    BlockStyle.Paragraph -> AppIcons.Paragraph
}

/** Returns the glyph of an image's wrapping, which the wrap button shows. */
private fun wrapIcon(wrap: String): ImageVector = when (wrap) {
    "square" -> AppIcons.WrapSquare
    "top-bottom" -> AppIcons.WrapTopBottom
    else -> AppIcons.WrapInline
}

/**
 * Chooses the selected image's width: Small, Medium, Large or Full; a width set on the desktop
 * between them marks none.
 *
 * @param current The image's width in percent; 0 for full width.
 */
@Composable
private fun ImageWidthDialog(current: Int?, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(
        Choice(25, stringResource(R.string.image_small)),
        Choice(50, stringResource(R.string.image_medium)),
        Choice(75, stringResource(R.string.image_large)),
        Choice(0, stringResource(R.string.image_full)),
    )
    ChoiceDialog(stringResource(R.string.image_size), options, current, onSelect, onDismiss = onDismiss)
}

/**
 * Chooses how text flows around the selected image: in line with text, square or top and bottom,
 * each with its glyph.
 *
 * @param current The image's wrapping; empty for in line with text.
 */
@Composable
private fun ImageWrapDialog(current: String?, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(
        Choice("", stringResource(R.string.image_wrap_inline), AppIcons.WrapInline),
        Choice("square", stringResource(R.string.image_wrap_square), AppIcons.WrapSquare),
        Choice("top-bottom", stringResource(R.string.image_wrap_top_bottom), AppIcons.WrapTopBottom),
    )
    ChoiceDialog(stringResource(R.string.image_wrap), options, current, onSelect, onDismiss = onDismiss)
}

/** Chooses the paragraph style: Paragraph, Heading 1 to 3 or Quote. */
@Composable
private fun ParagraphDialog(current: BlockStyle, onSelect: (BlockStyle) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(
        Choice(BlockStyle.Paragraph, stringResource(R.string.format_paragraph), AppIcons.Paragraph),
        Choice(BlockStyle.Heading1, stringResource(R.string.format_heading1), AppIcons.Heading1),
        Choice(BlockStyle.Heading2, stringResource(R.string.format_heading2), AppIcons.Heading2),
        Choice(BlockStyle.Heading3, stringResource(R.string.format_heading3), AppIcons.Heading3),
        Choice(BlockStyle.Quote, stringResource(R.string.format_quote), AppIcons.Quote),
    )
    ChoiceDialog(stringResource(R.string.format_paragraph_style), options, current, onSelect, onDismiss = onDismiss)
}

/** The keys of the numbering dialog's two actions beside the styles. */
private const val Restart = "#restart"
private const val StartAt = "#start"

/**
 * Chooses the numbering style of the numbered list at the cursor: `1.`, `A.`, `I.` or Multilevel;
 * Restart numbering and Start at… follow the styles.
 */
@Composable
private fun NumberingDialog(current: String, onStyle: (String) -> Unit, onRestart: () -> Unit, onStartAt: () -> Unit, onDismiss: () -> Unit) {
    val options = listOf(
        Choice("", "1."),
        Choice("upper-alpha", "A."),
        Choice("upper-roman", "I."),
        Choice("multilevel", stringResource(R.string.numbering_multilevel)),
        Choice(Restart, stringResource(R.string.numbering_restart)),
        Choice(StartAt, stringResource(R.string.numbering_start_at)),
    )
    ChoiceDialog(stringResource(R.string.format_numbering), options, current, { key ->
        when (key) {
            Restart -> onRestart()
            StartAt -> onStartAt()
            else -> onStyle(key)
        }
    }, onDismiss = onDismiss)
}

/** Asks for the number the list starts at from the item at the cursor. */
@Composable
private fun StartAtDialog(onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("1") }
    val number = text.trim().toIntOrNull()?.takeIf { it in 1..99999 }
    FormDialog(
        title = stringResource(R.string.numbering_start_at_title),
        confirmText = stringResource(R.string.numbering_apply),
        onConfirm = {
            number?.let(onConfirm)
            onDismiss()
        },
        onDismiss = onDismiss,
        confirmEnabled = number != null,
    ) {
        AppTextField(text, { text = it }, stringResource(R.string.numbering_first), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
}

/**
 * Inserts a table of a size picked from a grid up to 6 × 6, with a header row by
 * default.
 */
@Composable
private fun TableDialog(onInsert: (rows: Int, columns: Int, header: Boolean) -> Unit, onDismiss: () -> Unit) {
    var rows by rememberSaveable { mutableIntStateOf(3) }
    var columns by rememberSaveable { mutableIntStateOf(3) }
    var header by rememberSaveable { mutableStateOf(true) }
    val scheme = MaterialTheme.colorScheme
    FormDialog(
        title = stringResource(R.string.table_insert_title),
        confirmText = stringResource(R.string.table_insert),
        onConfirm = {
            onInsert(rows, columns, header)
            onDismiss()
        },
        onDismiss = onDismiss,
    ) {
        Text(stringResource(R.string.table_size, rows, columns), style = MaterialTheme.typography.bodyLarge)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in 1..6) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (column in 1..6) {
                        val chosen = row <= rows && column <= columns
                        Box(
                            Modifier
                                .size(36.dp)
                                .background(if (chosen) scheme.primary.copy(alpha = 0.25f) else scheme.surfaceContainerHighest, Shapes.Trigger)
                                .border(1.dp, if (chosen) scheme.primary else scheme.outlineVariant, Shapes.Trigger)
                                .semantics { contentDescription = "$row × $column" }
                                .clickable {
                                    rows = row
                                    columns = column
                                },
                        )
                    }
                }
            }
        }
        Row(Modifier.clickable { header = !header }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = header, onCheckedChange = { header = it })
            Text(stringResource(R.string.table_header_row))
        }
    }
}

/** The Insert button and its menu: link, note link, photo from the camera or the gallery, image from a file, attachment, table, code block and divider. */
@Composable
private fun InsertMenu(commands: EditorCommands, onLinkDialog: () -> Unit, onTable: () -> Unit, onInsert: (FileSource) -> Unit, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarButton(AppIcons.Add, stringResource(R.string.format_insert), { open = true }, enabled = enabled)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuEntry(AppIcons.Link, stringResource(R.string.insert_link)) { open = false; onLinkDialog() }
            MenuEntry(AppIcons.Note, stringResource(R.string.insert_note_link)) { open = false; commands.requestNoteLink() }
            MenuEntry(AppIcons.Camera, stringResource(R.string.insert_camera)) { open = false; onInsert(FileSource.Camera) }
            MenuEntry(AppIcons.Images, stringResource(R.string.insert_photos)) { open = false; onInsert(FileSource.Photos) }
            MenuEntry(AppIcons.FileImage, stringResource(R.string.insert_image_file)) { open = false; onInsert(FileSource.ImageFile) }
            MenuEntry(AppIcons.Attach, stringResource(R.string.insert_attachment)) { open = false; onInsert(FileSource.Attachment) }
            MenuEntry(AppIcons.TableView, stringResource(R.string.insert_table)) { open = false; onTable() }
            MenuEntry(AppIcons.Code, stringResource(R.string.insert_code_block)) { open = false; commands.insertCodeBlock() }
            MenuEntry(AppIcons.Divider, stringResource(R.string.insert_divider)) { open = false; commands.insertDivider() }
        }
    }
}

/**
 * The table's button and menu: rows and columns, the header row, equal column widths, full width
 * and deleting the table; each change is one undo step.
 */
@Composable
private fun TableMenu(header: Boolean, commands: EditorCommands) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarButton(AppIcons.TableView, stringResource(R.string.table_menu), { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val action: (TableAction) -> Unit = { name ->
                open = false
                commands.tableAction(name)
            }
            MenuEntry(AppIcons.ArrowUp, stringResource(R.string.table_row_above)) { action(TableAction.AddRowBefore) }
            MenuEntry(AppIcons.ArrowDown, stringResource(R.string.table_row_below)) { action(TableAction.AddRowAfter) }
            MenuEntry(AppIcons.ChevronLeft, stringResource(R.string.table_column_left)) { action(TableAction.AddColumnBefore) }
            MenuEntry(AppIcons.ChevronRight, stringResource(R.string.table_column_right)) { action(TableAction.AddColumnAfter) }
            MenuEntry(AppIcons.Check.takeIf { header } ?: AppIcons.TableView, stringResource(R.string.table_header_row)) { action(TableAction.ToggleHeaderRow) }
            MenuEntry(AppIcons.GridView, stringResource(R.string.table_equal_columns)) { action(TableAction.EqualColumns) }
            MenuEntry(AppIcons.ListView, stringResource(R.string.table_full_width)) { action(TableAction.FullWidth) }
            MenuEntry(AppIcons.Delete, stringResource(R.string.table_delete_row), destructive = true) { action(TableAction.DeleteRow) }
            MenuEntry(AppIcons.Delete, stringResource(R.string.table_delete_column), destructive = true) { action(TableAction.DeleteColumn) }
            MenuEntry(AppIcons.Delete, stringResource(R.string.table_delete), destructive = true) { action(TableAction.DeleteTable) }
        }
    }
}

/** An entry of the bar's menus, drawn as the design system's menu entries are. */
@Composable
private fun MenuEntry(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(label, color = color) },
        leadingIcon = { Icon(icon, null, tint = color, modifier = Modifier.size(Dimens.IconMenu)) },
        onClick = onClick,
    )
}
