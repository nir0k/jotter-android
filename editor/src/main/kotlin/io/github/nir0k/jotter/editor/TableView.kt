package io.github.nir0k.jotter.editor

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.core.body.CellContainer
import io.github.nir0k.jotter.core.body.MinColumnWidth
import io.github.nir0k.jotter.core.body.MinWidth
import io.github.nir0k.jotter.core.body.Run
import io.github.nir0k.jotter.core.body.Table
import io.github.nir0k.jotter.core.body.columnWidths
import io.github.nir0k.jotter.core.body.currentCell
import io.github.nir0k.jotter.core.body.setColumnWidths
import kotlin.math.abs
import kotlin.math.roundToInt

/** Rounds a table's or an image's width to 25, 50, 75 or 100 percent when it is within 3 of them. */
internal fun snapWidth(width: Double): Int {
    for (step in intArrayOf(25, 50, 75, 100)) if (abs(width - step) <= 3) return step
    return width.roundToInt()
}

/**
 * The width of the desktop's text column in its default window, in body font sizes: an 80 rem
 * window less the 15 rem sidebar, the 22 rem note list and the 1.5 rem side margins. Tables are
 * laid out at least this wide, so their columns get the room they have on the desktop.
 */
private const val ReferenceColumn = 40f

/**
 * A table drawn at its width with its column widths; each cell is a run of blocks. Its width is
 * a percent of the text column, but of no less than [ReferenceColumn] body sizes, so on a phone
 * a table is as wide as on the desktop and scrolls sideways. While the cursor is in it, handles
 * on the lines between columns and on its right edge change the widths by dragging: two
 * neighbouring columns keep their sum, no column gets narrower than 5%, and the table's width
 * goes from 10% to full width. Each drag is one undo step.
 */
@Composable
internal fun TableView(
    controller: NoteEditorController,
    table: Table,
    colors: NoteColors,
    textStyle: TextStyle,
    metrics: (Float) -> ObjectMetrics,
    decorations: DecorationContext,
    modifier: Modifier = Modifier,
) {
    var draggedWidths by remember(table.widths, table.columns) { mutableStateOf<List<Double>?>(null) }
    var draggedWidth by remember(table.width) { mutableStateOf<Int?>(null) }
    val widths = draggedWidths ?: columnWidths(table.widths, table.columns)
    val width = draggedWidth ?: table.width.takeIf { it > 0 } ?: 100
    val active = controller.editable && controller.focused && controller.state.currentCell()?.table == table.id
    val density = LocalDensity.current
    val line = rgb(colors.outline)
    val headerStyle = textStyle.copy(fontWeight = FontWeight.Bold)
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val wrapPx = maxOf(constraints.maxWidth.toFloat(), with(density) { textStyle.fontSize.toPx() } * ReferenceColumn)
        val tablePx = wrapPx * width / 100f
        // The handles take their drags before the scroll does, as they consume them first.
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Box(Modifier.width(with(density) { tablePx.toDp() })) {
                androidx.compose.foundation.layout.Column(Modifier.drawBehind {
                    drawRect(line, Offset.Zero, size, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }) {
                    table.cells.forEachIndexed { r, row ->
                        val header = r < table.headerRows
                        Layout(
                            content = {
                                row.forEachIndexed { c, cell ->
                                    val run = Run(CellContainer(table.id, r, c), 0, cell)
                                    val cellPx = tablePx * widths[c].toFloat() / 100f - with(density) { 16.dp.toPx() }
                                    key(cell.first().id) {
                                        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                            RunField(controller, run, colors, if (header) headerStyle else textStyle, metrics(cellPx.coerceAtLeast(1f)), decorations, "")
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().drawBehind {
                                if (header) drawRect(rgb(colors.raised), Offset.Zero, size)
                                var x = 0f
                                for (c in 0 until widths.size - 1) {
                                    x += size.width * widths[c].toFloat() / 100f
                                    drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                                }
                                if (r < table.rows - 1) drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                            },
                        ) { measurables, constraints ->
                            val total = constraints.maxWidth
                            var x = 0
                            val placed = measurables.mapIndexed { c, measurable ->
                                val cellWidth = if (c == measurables.lastIndex) total - x else (total * widths[c] / 100).roundToInt()
                                val placeable = measurable.measure(Constraints.fixedWidth(cellWidth.coerceAtLeast(0)))
                                (x to placeable).also { x += cellWidth }
                            }
                            val height = placed.maxOfOrNull { it.second.height } ?: 0
                            layout(total, height) { placed.forEach { (left, placeable) -> placeable.place(left, 0) } }
                        }
                    }
                }
                if (active) Box(Modifier.matchParentSize()) {
                    val handle = 24.dp
                    var boundary = 0f
                    for (c in 0 until widths.size - 1) {
                        boundary += tablePx * widths[c].toFloat() / 100f
                        val column = c
                        DragHandle(
                            x = boundary,
                            color = rgb(colors.primary),
                            handle = handle,
                            onDrag = { dx ->
                                val start = draggedWidths ?: columnWidths(table.widths, table.columns)
                                val pair = start[column] + start[column + 1]
                                val left = (start[column] + dx / tablePx * 100).coerceIn(MinColumnWidth.toDouble(), pair - MinColumnWidth)
                                draggedWidths = start.toMutableList().also {
                                    it[column] = left
                                    it[column + 1] = pair - left
                                }
                            },
                            onEnd = {
                                draggedWidths?.let { controller.setColumnWidths(table.id, setColumnWidths(it.size, it)) }
                                draggedWidths = null
                            },
                        )
                    }
                    DragHandle(
                        x = tablePx,
                        color = rgb(colors.primary),
                        handle = handle,
                        onDrag = { dx ->
                            val current = (draggedWidth ?: width).toDouble()
                            draggedWidth = snapWidth((current + dx / wrapPx * 100).coerceIn(MinWidth.toDouble(), 100.0))
                        },
                        onEnd = {
                            draggedWidth?.let { controller.setTableWidth(table.id, if (it >= 100) 0 else it) }
                            draggedWidth = null
                        },
                    )
                }
            }
        }
    }
}

/** A handle on a line of a table that reports horizontal drags in pixels. */
@Composable
private fun DragHandle(x: Float, color: androidx.compose.ui.graphics.Color, handle: androidx.compose.ui.unit.Dp, onDrag: (Float) -> Unit, onEnd: () -> Unit) {
    val density = LocalDensity.current
    val half = with(density) { (handle / 2).toPx() }
    Box(
        Modifier
            .offset { IntOffset((x - half).roundToInt(), 0) }
            .width(handle)
            .fillMaxHeight()
            .drawBehind { drawRect(color.copy(alpha = 0.35f), Offset(size.width / 2 - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height)) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = onEnd, onDragCancel = onEnd) { change, dx ->
                    change.consume()
                    onDrag(dx)
                }
            },
    )
}

