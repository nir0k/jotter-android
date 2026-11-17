package io.github.nir0k.jotter.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.core.body.CodeBlock
import io.github.nir0k.jotter.core.highlight.CodeHighlighter
import io.github.nir0k.jotter.core.highlight.HighlightSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.nir0k.jotter.designsystem.components.IconAction
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.theme.Shapes

/** The width of the column of line numbers, in multiples of the code's size. */
private const val GutterWidth = 2.5f

/**
 * A code block: a header with the language, the title and Copy above the code, which is edited as
 * plain text and highlighted in the styles of KDE SyntaxHighlighting's theme. Line numbers are
 * drawn beside it, one per line on its first visual line; they are never text.
 */
@Composable
internal fun CodeBlockView(
    controller: NoteEditorController,
    code: CodeBlock,
    colors: NoteColors,
    textStyle: TextStyle,
    strings: EditorStrings,
    languageName: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val key = FieldKey.CodeField(code.id)
    val field = remember(key) { controller.fieldState(key) }
    DisposableEffect(key) { onDispose { controller.releaseField(key) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(controller.focusRequest) { if (controller.focusRequest == key) focus.requestFocus() }
    LaunchedEffect(field) { snapshotFlow { field.selection }.collect { controller.onFieldSelection(key, it) } }
    var focused by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // The definition of the language is read off the main thread before the code is highlighted.
    var ready by remember(code.language) { mutableIntStateOf(if (CodeHighlighter.isReady(code.language)) 1 else 0) }
    LaunchedEffect(code.language) {
        if (ready == 0 && code.language.isNotEmpty()) {
            withContext(Dispatchers.Default) { CodeHighlighter.prepare(code.language) }
            ready = 1
        }
    }
    val theme = colors.theme
    val codeStyle = textStyle.copy(fontFamily = FontFamily.Monospace, fontSize = textStyle.fontSize * 0.9f, lineHeight = TextLineHeight * 0.9f, lineHeightStyle = TextLineHeightStyle)
    val find = controller.find
    val spans = remember(code.text, code.language, theme, ready, find) {
        val highlighted = if (ready == 0) emptyList() else CodeHighlighter.highlight(code.text, code.language, theme)
        val matches = find.matches.filter { it.container == null && it.item == code.id }
        Triple(code.text, highlighted, matches.map { (it.from to it.to) to (it == find.matches.getOrNull(find.current)) })
    }
    val current by rememberUpdatedState(spans)
    val output = remember {
        OutputTransformation {
            val (text, highlighted, matches) = current
            if (text != toString()) return@OutputTransformation
            for (span in highlighted) if (span.end <= length) addStyle(spanStyleOf(span), span.start, span.end)
            val primary = rgb(colors.primary)
            for ((range, isCurrent) in matches) if (range.second <= length) addStyle(SpanStyle(background = primary.copy(alpha = if (isCurrent) 0.55f else 0.25f)), range.first, range.second)
        }
    }
    val input = remember(key) {
        InputTransformation {
            val original = originalText.toString()
            if (original == toString()) return@InputTransformation
            val (from, to, inserted) = change(original)
            val edit = controller.editCode(code.id, original, from, to, inserted, originalSelection) ?: return@InputTransformation
            if (edit.text != toString()) replace(0, length, edit.text)
            selection = TextRange(edit.selection.start.coerceIn(0, length), edit.selection.end.coerceIn(0, length))
        }
    }
    val measurer = rememberTextMeasurer()
    val editable = controller.editable
    val header = MaterialTheme.typography.labelMedium.copy(color = rgb(colors.muted), fontWeight = FontWeight.SemiBold)
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(rgb(colors.raised), Shapes.Trigger)
            .border(1.dp, rgb(colors.edge), Shapes.Trigger),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                if (code.language.isEmpty()) strings.plainText else languageName(code.language),
                style = header,
                modifier = Modifier.heightIn(min = Dimens.TouchTarget).clickable(enabled = editable) { controller.pickLanguage(code) }.padding(8.dp),
            )
            // An empty title shows its placeholder only while the block is edited.
            val showTitle = code.title.isNotEmpty() || (editable && focused)
            if (showTitle) {
                BasicText(
                    code.title.ifEmpty { strings.codeTitle },
                    style = if (code.title.isEmpty()) header.copy(fontWeight = FontWeight.Normal) else header,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clickable(enabled = editable) { controller.editTitle(code) }.padding(8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            IconAction(AppIcons.Copy, strings.copy, { controller.copyCode(code.id) })
        }
        val gutter = with(androidx.compose.ui.platform.LocalDensity.current) { (codeStyle.fontSize.toPx() * GutterWidth) }
        BasicTextField(
            state = field,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp, end = 12.dp)
                .focusRequester(focus)
                .onFocusChanged {
                    focused = it.isFocused
                    controller.onFocus(key, it.isFocused)
                }
                .onPreviewKeyEvent { event ->
                    val empty = field.text.isEmpty()
                    shortcutOf(event, empty)?.let { controller.onKey(key, it) } ?: false
                }
                .drawBehind {
                    val result = layout ?: return@drawBehind
                    // One number per line of code, on its first visual line.
                    var offset = 0
                    var number = 1
                    val text = result.layoutInput.text.text
                    while (true) {
                        val line = result.getLineForOffset(offset)
                        val label = measurer.measure(number.toString(), codeStyle.copy(color = rgb(colors.muted)))
                        drawText(label, topLeft = Offset(gutter - 8.dp.toPx() - label.size.width, result.getLineBaseline(line) - label.firstBaseline))
                        val next = text.indexOf('\n', offset)
                        if (next < 0) break
                        offset = next + 1
                        number++
                    }
                }
                .padding(start = with(androidx.compose.ui.platform.LocalDensity.current) { gutter.toDp() }),
            readOnly = !editable,
            textStyle = codeStyle,
            inputTransformation = input,
            outputTransformation = output,
            onTextLayout = { result -> layout = result() },
            cursorBrush = SolidColor(rgb(colors.primary)),
        )
    }
}

/** Returns the style of a highlighted run of code. */
private fun spanStyleOf(span: HighlightSpan): SpanStyle {
    val style = span.style
    val decorations = buildList {
        if (style.underline) add(TextDecoration.Underline)
        if (style.strike) add(TextDecoration.LineThrough)
    }
    return SpanStyle(
        color = style.color?.let(::rgb) ?: androidx.compose.ui.graphics.Color.Unspecified,
        fontWeight = if (style.bold) FontWeight.Bold else null,
        fontStyle = if (style.italic) FontStyle.Italic else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
    )
}
