package io.github.nir0k.jotter.feature.note

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.nir0k.jotter.core.format.mergeTags
import io.github.nir0k.jotter.core.format.normalizedTag
import io.github.nir0k.jotter.core.format.tagColor
import io.github.nir0k.jotter.core.format.tagKey
import io.github.nir0k.jotter.core.format.tagSuggestions
import io.github.nir0k.jotter.designsystem.components.CompactTag
import io.github.nir0k.jotter.designsystem.components.Tag
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.ChosenColor
import io.github.nir0k.jotter.designsystem.theme.Shapes

private val PopupWidth = 240.dp
private val PopupGap = 4.dp
private val RemoveGlyph = 10.dp
private val SuggestionsMaxHeight = 180.dp
private val SuggestionMinHeight = 36.dp

/**
 * NoteTagsPopup holds [NoteTagsEditor] under the line of a note's tags. Tapping outside it or Back
 * closes it.
 *
 * @param inset Where the popup starts, under the title.
 * @param tags The note's tags.
 * @param known The library's tags, which the suggestions come from.
 * @param tagColors Tag colour keys by tag key, from the library.
 * @param onChange Receives the note's new tags at each change.
 * @param onDismiss Closes the popup.
 */
@Composable
fun NoteTagsPopup(inset: Dp, tags: List<String>, known: List<String>, tagColors: Map<String, String>, onChange: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val offset = with(LocalDensity.current) { IntOffset(inset.roundToPx(), PopupGap.roundToPx()) }
    Popup(alignment = Alignment.TopStart, offset = offset, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        NoteTagsEditor(tags, known, tagColors, onChange)
    }
}

/**
 * NoteTagsEditor is the popup's content: the note's tags with × to remove each, then a field that
 * adds a tag with the keyboard's Done and stays ready for the next, and the matching tags of the
 * library to pick from. A typed tag the library has in another case takes the library's spelling.
 * Each change applies at once.
 *
 * @param tags The note's tags.
 * @param known The library's tags, which the suggestions come from.
 * @param tagColors Tag colour keys by tag key, from the library.
 * @param onChange Receives the note's new tags at each change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteTagsEditor(tags: List<String>, known: List<String>, tagColors: Map<String, String>, onChange: (List<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val add: (String) -> Unit = { typed ->
        val tag = normalizedTag(typed)
        if (tag.isNotEmpty()) {
            onChange(mergeTags(tags, listOf(known.firstOrNull { tagKey(it) == tagKey(tag) } ?: tag)))
            query = ""
        }
    }
    Surface(
        Modifier.width(PopupWidth),
        shape = Shapes.Trigger,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.forEach { tag ->
                        RemovableTag(Tag(tag, ChosenColor.resolve(tagColor(tagColors, tag)) ?: muted)) { onChange(tags.filterNot { tagKey(it) == tagKey(tag) }) }
                    }
                }
            }
            TagField(query, { query = it }, { add(query) }, focus)
            val suggestions = tagSuggestions(known, tags, query)
            if (suggestions.isNotEmpty()) {
                Column(Modifier.heightIn(max = SuggestionsMaxHeight).verticalScroll(rememberScrollState())) {
                    suggestions.forEach { tag ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = SuggestionMinHeight)
                                .clip(Shapes.Trigger)
                                .clickable { add(tag) }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) { CompactTag(Tag(tag, ChosenColor.resolve(tagColor(tagColors, tag)) ?: muted)) }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * A tag of the note with × at its end. The × is drawn small; Compose extends its touch area to the
 * minimum touch target where no neighbour claims the tap.
 *
 * @param onRemove Removes the tag.
 */
@Composable
private fun RemovableTag(tag: Tag, onRemove: () -> Unit) {
    Surface(shape = Shapes.Pill, color = tag.color.copy(alpha = 0.16f)) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tag.text,
                style = MaterialTheme.typography.labelSmall,
                color = tag.color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(1.dp)) {
                Icon(AppIcons.Close, stringResource(R.string.note_remove_tag, tag.text), tint = tag.color, modifier = Modifier.size(RemoveGlyph))
            }
        }
    }
}

/**
 * The field a tag is typed in, with Add tag as its placeholder.
 *
 * @param onDone Adds what is typed, from the keyboard's Done or a hardware Enter.
 * @param focus Focuses the field when the popup opens.
 */
@Composable
private fun TagField(value: String, onValue: (String) -> Unit, onDone: () -> Unit, focus: FocusRequester) {
    val style = MaterialTheme.typography.bodyMedium
    val shape = RoundedCornerShape(4.dp)
    BasicTextField(
        value,
        onValue,
        singleLine = true,
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        decorationBox = { field ->
            Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHighest, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    if (value.isEmpty()) Text(stringResource(R.string.note_add_tag), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    field()
                }
            }
        },
    )
}
