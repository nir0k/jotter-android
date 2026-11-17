package io.github.nir0k.jotter.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.core.body.AttachmentObject
import io.github.nir0k.jotter.core.body.BlockSeparator
import io.github.nir0k.jotter.core.body.ImageObject
import io.github.nir0k.jotter.core.body.ListKind
import io.github.nir0k.jotter.core.body.MinWidth
import io.github.nir0k.jotter.core.body.Run
import io.github.nir0k.jotter.core.body.TextRun
import io.github.nir0k.jotter.core.body.isEmptyBody
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Returns the change a buffer holds against the text before it: where the replaced text starts and
 * ends in the old text, and the new text.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun TextFieldBuffer.change(original: String): Triple<Int, Int, String> {
    val current = toString()
    if (changes.changeCount == 1) {
        val range = changes.getRange(0)
        val before = changes.getOriginalRange(0)
        return Triple(before.min, before.max, current.substring(range.min, range.max))
    }
    var prefix = 0
    val shorter = minOf(original.length, current.length)
    while (prefix < shorter && original[prefix] == current[prefix]) prefix++
    var suffix = 0
    while (suffix < shorter - prefix && original[original.length - 1 - suffix] == current[current.length - 1 - suffix]) suffix++
    return Triple(prefix, original.length - suffix, current.substring(prefix, current.length - suffix))
}

/** Returns the editor's shortcut a key press of a hardware keyboard is, if any. */
internal fun shortcutOf(event: KeyEvent, atStart: Boolean): Shortcut? {
    if (event.type != KeyEventType.KeyDown) return null
    val control = event.isCtrlPressed || event.isMetaPressed
    val shift = event.isShiftPressed
    return when {
        control && shift && event.key == Key.X -> Shortcut.Strike
        control && shift && event.key == Key.Seven -> Shortcut.NumberedList
        control && shift && event.key == Key.Eight -> Shortcut.BulletedList
        control && shift && event.key == Key.Nine -> Shortcut.Checklist
        control && shift && event.key == Key.Z -> Shortcut.Redo
        control && event.key == Key.B -> Shortcut.Bold
        control && event.key == Key.I -> Shortcut.Italic
        control && event.key == Key.U -> Shortcut.Underline
        control && event.key == Key.Period -> Shortcut.Superscript
        control && event.key == Key.Comma -> Shortcut.Subscript
        control && event.key == Key.Zero -> Shortcut.Paragraph
        control && event.key == Key.One -> Shortcut.Heading1
        control && event.key == Key.Two -> Shortcut.Heading2
        control && event.key == Key.Three -> Shortcut.Heading3
        control && event.key == Key.K -> Shortcut.Link
        control && event.key == Key.F -> Shortcut.Find
        control && event.key == Key.Z -> Shortcut.Undo
        control && event.key == Key.Y -> Shortcut.Redo
        control && event.key == Key.Enter -> Shortcut.ControlEnter
        event.key == Key.F3 -> if (shift) Shortcut.FindPrevious else Shortcut.FindNext
        event.key == Key.Tab -> if (shift) Shortcut.ShiftTab else Shortcut.Tab
        shift && event.key == Key.Enter -> Shortcut.LineBreak
        event.key == Key.Backspace && atStart -> Shortcut.BackspaceAtStart
        else -> null
    }
}

/**
 * Returns an image's width in percent of its column after one of its handles is dragged: from 10
 * % to full width, taking 25, 50, 75 or 100 % within 3 of them.
 *
 * @param start The image's drawn width when the drag started, in pixels.
 * @param dx How far the handle moved outwards, in pixels; negative when it moved inwards.
 * @param column The width of the image's column in pixels.
 * @return The width in percent, 100 for full width.
 */
internal fun resizedWidth(start: Float, dx: Float, column: Float): Int =
    snapWidth(((start + dx) / column.coerceAtLeast(1f) * 100.0).coerceIn(MinWidth.toDouble(), 100.0))

/** The character shown, and only shown, after a paragraph separator that ends a field's text: a zero-width space. */
internal const val EmptyBlockFiller = '\u200B'

/**
 * A tap on a field's text: where it lifted, in the text's coordinates, when, by [System.nanoTime],
 * and the text it was on; only a cursor set on that same text answers it.
 */
private class Tap(val position: Offset, val time: Long, val text: String)

/** How long after a tap the field's move of the cursor is taken as its answer. */
private const val TapAnswerNanos = 300_000_000L

/**
 * Returns the offset of a field's text at a position, on the line the position is on. Each block is
 * a paragraph of its own that ends with the block separator, so past the end of a line the layout
 * gives the offset after the separator, which stands at the start of the next line; that offset
 * moves back before the separator.
 */
internal fun TextLayoutResult.offsetAt(position: Offset): Int {
    val offset = getOffsetForPosition(position)
    val text = layoutInput.text
    if (offset in 1..text.length && text[offset - 1] == BlockSeparator && getLineForOffset(offset) != getLineForVerticalPosition(position.y)) return offset - 1
    return offset
}

/** The selection colours of a field whose selection is an object, which draws its own outline instead. */
private val ObjectSelectionColors = TextSelectionColors(Color.Transparent, Color.Transparent)

/** What a tap on a run's text hits. */
private sealed interface Hit {
    data class Checkbox(val marker: Marker) : Hit
    data class Object(val box: ObjectBox) : Hit
    data class Link(val href: String) : Hit
}

/**
 * A run of blocks edited as one text field: its styles come from the model, what is typed goes to
 * the model, and what stands around the text is drawn from its layout. A tap on a checkbox toggles
 * it; a tap on an image or a chip selects it in an editable note and opens it in a read-only one,
 * where links open too. Dragging a handle of a selected image sets its width, shown by an outline
 * while dragging; the width is stored when the finger lifts, as one undo step. A long press on an
 * image or a chip in an editable note picks it up, and a drag moves it to the place in the text
 * under the finger, shown by a line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RunField(
    controller: NoteEditorController,
    run: Run,
    colors: NoteColors,
    textStyle: TextStyle,
    metrics: ObjectMetrics,
    decorations: DecorationContext,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val key = FieldKey.RunField(run.key)
    val field = remember(key) { controller.fieldState(key) }
    DisposableEffect(key) { onDispose { controller.releaseField(key) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(controller.focusRequest) { if (controller.focusRequest == key) focus.requestFocus() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val placement = remember(run.key) { controller.placement(run.key) }
    // The last tap on the text, which the field answers by moving the cursor.
    var lastTap by remember { mutableStateOf<Tap?>(null) }
    val tapScope = rememberCoroutineScope()

    /**
     * Returns where a cursor set at a field's offset should go instead: a tap past the end of a
     * line, which the field puts at the start of the next, goes to the end of that line. Null when
     * the cursor stays.
     */
    fun tapFix(range: TextRange): Int? {
        val tap = lastTap?.takeIf { System.nanoTime() - it.time < TapAnswerNanos && it.text == field.text.toString() } ?: return null
        val result = layout ?: return null
        if (!range.collapsed || range.start != result.getOffsetForPosition(tap.position)) return null
        return result.offsetAt(tap.position).takeIf { it != range.start }
    }

    LaunchedEffect(field) {
        snapshotFlow { field.selection }.collect { range ->
            val fixed = tapFix(range)
            if (fixed != null) {
                lastTap = null
                field.edit { selection = TextRange(fixed) }
            } else {
                controller.onFieldSelection(key, range)
            }
        }
    }
    val haptic = LocalHapticFeedback.current
    val find = controller.find
    val missing = controller.missingLinks
    val shown = remember(run.blocks, colors, metrics, find, missing, textStyle) {
        wrapImages(presentation(run, colors, metrics, find.matches, find.matches.getOrNull(find.current), missing), decorations.measurer, textStyle, metrics)
    }
    val current by rememberUpdatedState(shown)
    val output = remember { OutputTransformation { styleBuffer(current) } }
    val input = remember(key) {
        InputTransformation {
            val original = originalText.toString()
            if (original == toString()) return@InputTransformation
            val (from, to, inserted) = change(original)
            val edit = controller.editRun(key.key, original, from, to, inserted, originalSelection) ?: return@InputTransformation
            if (edit.text != toString()) replace(0, length, edit.text)
            selection = edit.selection.let { TextRange(it.start.coerceIn(0, length), it.end.coerceIn(0, length)) }
        }
    }
    val density = LocalDensity.current
    val fontPx = with(density) { textStyle.fontSize.toPx() }
    val editable = controller.editable
    val selected = controller.selectionIn(key)?.takeIf { it.length == 1 }?.min ?: -1
    val selectedBox = current.decorations.firstOrNull { it is ObjectBox && it.offset == selected } as ObjectBox?
    val resizable by rememberUpdatedState(selectedBox?.takeIf { editable && it.item is ImageObject && it.item.pending.isEmpty() })
    val sizes by rememberUpdatedState(metrics)
    var resizing by remember { mutableStateOf<Int?>(null) }
    val empty = controller.state.doc.isEmptyBody() && editable

    /**
     * Returns where the selected image's handles take touches, in the order of [corners]; empty when
     * no image can be resized. The system's selection handles stand below the image's line and keep
     * the touches there; an image text flows beside reaches below its character's line.
     */
    fun handleZones(): List<Rect> {
        val box = resizable ?: return emptyList()
        val result = layout ?: return emptyList()
        val floor = if (box.float) Float.MAX_VALUE else result.getBoundingBox(box.offset.coerceAtMost(maxOf(0, result.layoutInput.text.length - 1))).bottom
        val reach = with(density) { HandleReach.toPx() }
        return objectRect(result, box).corners().map { Rect(it.x - reach, it.y - reach, it.x + reach, minOf(it.y + reach, floor)) }
    }

    /** Returns which handle of the selected image a touch grabs: 1 for a right corner, -1 for a left one, 0 for none. */
    fun handleAt(position: Offset): Int {
        val zones = handleZones()
        val corner = zones.indices.filter { zones[it].contains(position) }.minByOrNull { (zones[it].center - position).getDistance() } ?: return 0
        return if (corner % 2 == 1) 1 else -1
    }

    /** Returns what a tap at a position hits. */
    fun hitAt(position: Offset): Hit? {
        val result = layout ?: return null
        val touch = with(density) { 12.dp.toPx() }
        for (decoration in current.decorations) {
            when (decoration) {
                is Marker -> if (editable && decoration.kind == ListKind.Check) {
                    val box = checkboxRect(result, decoration, fontPx, current)
                    if (position.x in box.left - touch..box.right + touch && position.y in box.top - touch / 2..box.bottom + touch / 2) return Hit.Checkbox(decoration)
                }
                is ObjectBox -> if (objectRect(result, decoration).contains(position)) return Hit.Object(decoration)
                else -> Unit
            }
        }
        if (!editable) {
            val offset = result.getOffsetForPosition(position)
            val (index, inBlock) = run.locate(offset)
            var pos = 0
            for (piece in run.blocks[index].inline) {
                if (piece is TextRun && piece.format.href != null && inBlock >= pos && inBlock < pos + piece.length) return Hit.Link(piece.format.href!!)
                pos += piece.length
            }
        }
        return null
    }

    /** Acts on a tap that hit something. */
    fun act(hit: Hit) {
        when (hit) {
            is Hit.Checkbox -> controller.toggleChecked(run.container, hit.marker.block)
            is Hit.Object -> when {
                editable -> controller.selectObject(run.key, hit.box.offset)
                hit.box.item is ImageObject -> controller.openImage(hit.box.item)
                hit.box.item is AttachmentObject && hit.box.item.pending.isEmpty() -> controller.host.onOpenAttachment(hit.box.item.file)
            }
            is Hit.Link -> controller.host.onOpenLink(hit.href)
        }
    }

    val selectionColors = if (selectedBox != null) ObjectSelectionColors else LocalTextSelectionColors.current
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            state = field,
            modifier = modifier
                .fillMaxWidth()
                // Wrapped images may reach below the text.
                .heightIn(min = with(density) { current.bottom.toDp() })
                .focusRequester(focus)
                .onFocusChanged { controller.onFocus(key, it.isFocused) }
                .onPreviewKeyEvent { event ->
                    val atStart = field.selection.collapsed && field.selection.start == 0
                    shortcutOf(event, atStart)?.let { controller.onKey(key, it) } ?: false
                }
                // A handle at the edge of the screen is dragged, not taken for the system's back gesture.
                .systemGestureExclusion { handleZones().getOrNull(0) ?: Rect.Zero }
                .systemGestureExclusion { handleZones().getOrNull(1) ?: Rect.Zero }
                .systemGestureExclusion { handleZones().getOrNull(2) ?: Rect.Zero }
                .systemGestureExclusion { handleZones().getOrNull(3) ?: Rect.Zero }
                .contentReceiver { content -> if (controller.pasteClip(content.clipEntry.clipData)) null else content }
                .pointerInput(run.key, editable) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val side = handleAt(down.position)
                        val image = resizable
                        val result = layout
                        if (side != 0 && image != null && result != null) {
                            down.consume()
                            val start = objectRect(result, image).width
                            try {
                                while (true) {
                                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    val dx = side * (change.position.x - down.position.x)
                                    if (!change.pressed) {
                                        val width = resizing?.let { if (it >= 100) 0 else it }
                                        if (width != null && width != (image.item as ImageObject).width) controller.setImageWidth(width)
                                        break
                                    }
                                    if (resizing != null || abs(dx) > viewConfiguration.touchSlop) resizing = resizedWidth(start, dx, sizes.column)
                                }
                            } finally {
                                resizing = null
                            }
                            return@awaitEachGesture
                        }
                        val hit = hitAt(down.position)
                        if (hit == null) {
                            // A tap on the text is left to the field, and its cursor corrected.
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                                if (!change.pressed) {
                                    lastTap = Tap(change.position, System.nanoTime(), field.text.toString())
                                    // Checked again once the field has handled this tap: a cursor already
                                    // where the tap puts it does not move, and a correction made before the
                                    // field moves it back is undone without a change to follow.
                                    tapScope.launch { tapFix(field.selection)?.let { fixed -> field.edit { selection = TextRange(fixed) } } }
                                    break
                                }
                            }
                            return@awaitEachGesture
                        }
                        down.consume()
                        if (hit is Hit.Object && editable) {
                            // A tap selects the object, a long press picks it up, and a drag before
                            // the long press is left to the scrolling.
                            val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                var up: Boolean? = null
                                while (up == null) {
                                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                                    up = when {
                                        change == null -> false
                                        !change.pressed -> true.also { change.consume() }
                                        change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> false
                                        else -> null
                                    }
                                }
                                up
                            }
                            when (tapped) {
                                true -> act(hit)
                                false -> Unit
                                null -> {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    controller.startDrag(run.key, hit.box.offset, placement.coordinates?.takeIf { it.isAttached }?.localToWindow(down.position) ?: Offset.Zero)
                                    try {
                                        while (true) {
                                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                            change.consume()
                                            placement.coordinates?.takeIf { it.isAttached }?.let { controller.dragTo(it.localToWindow(change.position)) }
                                            if (!change.pressed) {
                                                controller.endDrag(dropped = true)
                                                break
                                            }
                                        }
                                    } finally {
                                        if (controller.dragging) controller.endDrag(dropped = false)
                                    }
                                }
                            }
                            return@awaitEachGesture
                        }
                        val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                        up.consume()
                        act(hit)
                    }
                }
                .onGloballyPositioned { placement.coordinates = it }
                .drawBehind {
                    layout?.let { result ->
                        val image = resizable?.item as? ImageObject
                        val resized = resizing?.let { width -> image?.let { sizes.sizeOf(it.copy(width = if (width >= 100) 0 else width)) } }
                        drawDecorations(result, current, decorations, selected, image != null, resized?.let { Size(it.first, it.second) })
                        // The place a dragged object would go.
                        controller.drop?.takeIf { it.key == run.key && it.offset <= result.layoutInput.text.length }?.let { target ->
                            val caret = result.getCursorRect(target.offset)
                            drawLine(rgb(colors.primary), Offset(caret.left, caret.top), Offset(caret.left, caret.bottom), 2.dp.toPx())
                        }
                    }
                },
            readOnly = !editable,
            textStyle = textStyle,
            inputTransformation = input,
            outputTransformation = output,
            onTextLayout = { result ->
                layout = result()
                placement.layout = layout
            },
            cursorBrush = SolidColor(rgb(colors.primary)),
            decorator = { inner ->
                Box {
                    if (empty && field.text.isEmpty()) BasicText(placeholder, style = textStyle.copy(color = rgb(colors.outline)))
                    inner()
                }
            },
        )
    }
}

/** How far from a corner of a selected image a touch grabs its handle. */
private val HandleReach = 24.dp

/**
 * Gives a field's text the styles of its presentation, when the presentation matches its text. An
 * empty last block gets [EmptyBlockFiller] to stand on: Android starts no line after a paragraph
 * separator that ends the text, so its cursor would stay at the end of the block before.
 */
private fun TextFieldBuffer.styleBuffer(presentation: RunPresentation) {
    if (presentation.text != toString()) return
    val original = length
    val filler = original > 0 && charAt(original - 1) == BlockSeparator
    if (filler) append(EmptyBlockFiller)
    for (paragraph in presentation.paragraphs) {
        val end = if (filler && paragraph.start == original) length else paragraph.end
        if (end >= paragraph.start && end <= length) addStyle(paragraph.item, paragraph.start, end)
    }
    for (span in presentation.spans) {
        if (span.end > span.start && span.end <= length) addStyle(span.item, span.start, span.end)
    }
}
