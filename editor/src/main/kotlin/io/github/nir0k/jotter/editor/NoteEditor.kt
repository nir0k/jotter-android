package io.github.nir0k.jotter.editor

import android.content.ClipboardManager
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.nativeClipboardManager
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nir0k.jotter.core.body.Block
import io.github.nir0k.jotter.core.body.CodeBlock
import io.github.nir0k.jotter.core.body.ObjectCharacter
import io.github.nir0k.jotter.core.body.RootContainer
import io.github.nir0k.jotter.core.body.Table
import io.github.nir0k.jotter.core.body.runsIn
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import android.annotation.SuppressLint

/**
 * The clipboard the editor's fields copy through: a copy of a selection puts the note's HTML there with its text.
 * It is an [AndroidClipboard] because the fields' selection menu asks the platform's clipboard directly what can
 * be pasted, and any other [Clipboard] makes that query throw on a long press; Compose marks that interface for
 * tests only, so its lint warning is suppressed here.
 */
@SuppressLint("VisibleForTests")
private class EditorClipboard(private val base: Clipboard, private val controller: NoteEditorController) : AndroidClipboard {
    override val clipboardManager: ClipboardManager = base.nativeClipboardManager

    override suspend fun getClipEntry(): ClipEntry? = base.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = base.setClipEntry(clipEntry?.let { controller.copyEntry() ?: it })
}

/** The glyphs of attachment chips, as painters the decorations draw. */
@Composable
private fun rememberGlyphs(): (String) -> Painter {
    val glyphs: List<ImageVector> = listOf(
        AppIcons.File, AppIcons.FileImage, AppIcons.FileAudio, AppIcons.FileVideo, AppIcons.FilePdf, AppIcons.FileZip,
        AppIcons.FileSpreadsheet, AppIcons.FileCode, AppIcons.Document,
    )
    val painters = glyphs.associateWith { rememberVectorPainter(it) }
    return { name -> painters.getValue(fileGlyph(name)) }
}


/**
 * NoteEditor shows a note's body in the native editor: the runs of blocks as text fields, code
 * blocks and tables between them, in one scrolling column. The note's own formatting draws the
 * body; the controls of code blocks and tables follow the design system. While an image or a
 * chip is dragged near the top or the bottom edge, the column scrolls. The column fills at
 * least the window, and a tap on the room below the text puts the cursor at the end of the
 * note.
 *
 * @param images Decodes the note's images.
 * @param languageName Returns the name a stored code language is shown with.
 * @param contentPadding The room around the body, such as for what the screen draws over its top.
 */
@Composable
fun NoteEditor(
    controller: NoteEditorController,
    colors: NoteColors,
    strings: EditorStrings,
    images: NoteImages,
    languageName: (String) -> String,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(),
) {
    val textStyle = remember(colors) { bodyTextStyle(colors) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The interface language as Compose observes it, so a change of language redraws the decorations.
    val locale = LocalLocale.current.platformLocale
    val glyph = rememberGlyphs()
    val decorations = remember(colors, measurer, textStyle, images, glyph, strings, locale) {
        DecorationContext(colors, measurer, textStyle, images, glyph, strings, locale)
    }
    val glyphBox = remember(density, textStyle) { measureObjectGlyph(measurer, textStyle, density) }
    val decoded = images.version
    val base = LocalClipboard.current
    val clipboard = remember(base, controller) { EditorClipboard(base, controller) }
    val direction = LocalLayoutDirection.current
    val keyboard = LocalSoftwareKeyboardController.current
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(controller.dragging) {
        if (!controller.dragging) return@LaunchedEffect
        val edge = with(density) { 56.dp.toPx() }
        val step = with(density) { 12.dp.toPx() }
        val covered = with(density) { contentPadding.calculateTopPadding().toPx() }
        while (true) {
            withFrameNanos {}
            val bounds = viewport?.takeIf { it.isAttached }?.boundsInWindow() ?: continue
            // The column scrolls only once the finger has moved, so picking up an object near an edge does not.
            if ((controller.dragPosition - controller.dragStart).getDistance() < edge / 4) continue
            val y = controller.dragPosition.y
            val upper = bounds.top + covered + edge
            val lower = bounds.bottom - edge
            val speed = when {
                y < upper -> -step * ((upper - y) / edge).coerceAtMost(1f)
                y > lower -> step * ((y - lower) / edge).coerceAtMost(1f)
                else -> 0f
            }
            if (speed != 0f && scrollState.scrollBy(speed) != 0f) controller.dragTo(controller.dragPosition)
        }
    }
    CompositionLocalProvider(LocalClipboard provides clipboard) {
        BoxWithConstraints(modifier.onGloballyPositioned { viewport = it }) {
            val horizontal = with(density) { (contentPadding.calculateStartPadding(direction) + contentPadding.calculateEndPadding(direction)).toPx() }
            val column = (constraints.maxWidth - horizontal).coerceAtLeast(1f)
            val metricsFor = remember(density, textStyle, glyphBox, images, decoded, strings, locale) {
                val cache = HashMap<Float, ObjectMetrics>()
                val lineHeight = with(density) { textStyle.fontSize.toPx() * 1.4f }
                val metrics: (Float) -> ObjectMetrics = { width ->
                    cache.getOrPut(width) {
                        ObjectMetrics(
                            column = width,
                            glyphHeight = glyphBox.height,
                            glyphWidth = glyphBox.width,
                            glyphDescent = glyphBox.descent,
                            lineHeight = lineHeight,
                            density = density,
                            imageSize = { images.size(it.file) },
                            chipWidth = { chipWidth(it, measurer, textStyle, strings, locale, density, glyph = true) },
                        )
                    }
                }
                metrics
            }
            val doc = controller.state.doc
            val runs = doc.runsIn(RootContainer).associateBy { it.start }
            val fill = if (constraints.hasBoundedHeight) maxHeight - contentPadding.calculateTopPadding() - contentPadding.calculateBottomPadding() else 0.dp
            Column(Modifier.fillMaxWidth().verticalScroll(scrollState).padding(contentPadding).heightIn(min = fill)) {
                for ((index, item) in doc.items.withIndex()) {
                    when (item) {
                        is Block -> runs[index]?.let { run ->
                            key(run.key) {
                                RunField(controller, run, colors, textStyle, metricsFor(column), decorations, if (index == 0) strings.placeholder else "")
                            }
                        }
                        is CodeBlock -> key(item.id) { CodeBlockView(controller, item, colors, textStyle, strings, languageName) }
                        is Table -> key(item.id) { TableView(controller, item, colors, textStyle, metricsFor, decorations) }
                    }
                }
                // The room below the text takes a tap as one at the end of the note; the field there may keep
                // the focus with the keyboard hidden, so the keyboard is shown again.
                Spacer(
                    Modifier.fillMaxWidth().weight(1f).pointerInput(controller, keyboard) {
                        detectTapGestures { if (controller.focusEnd()) keyboard?.show() }
                    },
                )
            }
        }
    }
}
