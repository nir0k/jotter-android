package io.github.nir0k.jotter.feature.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.designsystem.components.CompactTag
import io.github.nir0k.jotter.designsystem.components.MenuAction
import io.github.nir0k.jotter.designsystem.components.OverflowMenuButton
import io.github.nir0k.jotter.designsystem.components.Tag
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.theme.Shapes
import io.github.nir0k.jotter.data.R as DataR

/** Where the top bar's title starts with and without Back, which the line under it follows. */
private val TitleInsetWithBack = 56.dp
private val TitleInset = 16.dp

/** The height of the line of collections and tags, which the editor leaves free above its text. */
val NoteDetailsHeight = Dimens.TouchTarget

/** The widest the collections take of the line under the title, so the tags keep room on a phone. */
private val CollectionsMaxWidth = 160.dp

/**
 * NoteDetails is the line under a note's title.
 *
 * @param collections The names of the note's collections; empty for an unfiled note.
 * @param tags The note's tags in their colours.
 * @param onCollections Chooses the collections; null for a note that cannot be edited.
 * @param onTags Opens the tags popup; null for a note that cannot be edited.
 * @param tagsPopup The tags popup while it is open, given where it starts; null otherwise.
 */
class NoteDetails(
    val collections: List<String>,
    val tags: List<Tag>,
    val onCollections: (() -> Unit)?,
    val onTags: (() -> Unit)?,
    val tagsPopup: (@Composable (Dp) -> Unit)? = null,
)

/**
 * NoteTopBar is the top bar of a note: Back, the title and ⋮. It is the design system's top bar
 * with the title as a field, which `AppTopBar` takes only as text.
 *
 * @param title The note's title, or null while it loads.
 * @param onTitle Receives the edited title; null shows the title as text.
 * @param titleFocus Focuses the title of a new note.
 * @param onBack Closes the screen; null in the detail pane of a wide window.
 * @param menu The note's ⋮ entries.
 * @param onTitleDone Moves on from the title, for the keyboard's Next and Enter: to the body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteTopBar(
    title: String?,
    onTitle: ((String) -> Unit)?,
    titleFocus: FocusRequester,
    onBack: (() -> Unit)?,
    menu: List<MenuAction>,
    onTitleDone: () -> Unit = {},
) {
    TopAppBar(
        expandedHeight = Dimens.TouchTarget,
        windowInsets = TopAppBarDefaults.windowInsets.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(AppIcons.Back, stringResource(io.github.nir0k.jotter.designsystem.R.string.ds_back), modifier = Modifier.size(Dimens.IconToolbar))
                }
            }
        },
        title = { if (title != null) NoteTitle(title, onTitle, titleFocus, onTitleDone) },
        actions = { if (menu.isNotEmpty()) OverflowMenuButton(menu, tint = MaterialTheme.colorScheme.onSurface) },
    )
}

/**
 * NoteDetailsBar is the note's collections and tags under the top bar. It stands over the top of the
 * editor, which leaves it [NoteDetailsHeight] of room, and scrolls away with the text.
 *
 * @param withBack Whether the top bar has Back, which moves the title the line starts under.
 * @param modifier What moves it as the text scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailsBar(details: NoteDetails, withBack: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier, color = TopAppBarDefaults.topAppBarColors().containerColor) {
        Column {
            val inset = if (withBack) TitleInsetWithBack else TitleInset
            NoteDetailsLine(details, inset)
            // The popup is placed from this empty box under the line.
            details.tagsPopup?.let { popup -> Box(Modifier.fillMaxWidth()) { popup(inset) } }
        }
    }
}

/**
 * The title as a field of the top bar's text style without a frame, or as text when it cannot be
 * edited; an empty one reads Untitled. The keyboard's Next, and Enter, call [onDone].
 */
@Composable
private fun NoteTitle(title: String, onTitle: ((String) -> Unit)?, titleFocus: FocusRequester, onDone: () -> Unit) {
    val untitled = stringResource(DataR.string.note_untitled)
    if (onTitle == null) {
        Text(title.ifEmpty { untitled }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val label = stringResource(R.string.note_title_field)
    val style = LocalTextStyle.current
    BasicTextField(
        title,
        onTitle,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onDone() }),
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).semantics { contentDescription = label },
        decorationBox = { field ->
            Box {
                if (title.isEmpty()) Text(untitled, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                field()
            }
        },
    )
}

/**
 * The note's collections and tags: tapping the collections opens their dialog, and tapping the
 * tags the tags popup, unless the note cannot be edited. The tag glyph leads the tags; without any
 * it is followed by the word Tags and +. The tags scroll sideways, so the bar keeps its height
 * however many there are.
 *
 * @param inset Where the line starts, under the title.
 */
@Composable
private fun NoteDetailsLine(details: NoteDetails, inset: Dp) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val collectionsLabel = stringResource(R.string.note_collections)
    val tagsLabel = stringResource(R.string.note_tags)
    Row(Modifier.fillMaxWidth().height(NoteDetailsHeight).padding(start = inset, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .widthIn(max = CollectionsMaxWidth)
                .clip(Shapes.Trigger)
                .then(details.onCollections?.let { Modifier.clickable(onClickLabel = collectionsLabel, onClick = it) } ?: Modifier)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Folder, collectionsLabel, tint = muted, modifier = Modifier.size(Dimens.IconInline))
            Text(
                details.collections.joinToString(", ").ifEmpty { stringResource(R.string.note_no_collection) },
                style = MaterialTheme.typography.labelMedium,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // A note that cannot be edited and has no tags shows nothing of them: none can be added.
        if (details.tags.isNotEmpty() || details.onTags != null) {
            Row(
                Modifier
                    .padding(start = 8.dp)
                    .weight(1f, fill = false)
                    .fillMaxHeight()
                    .clip(Shapes.Trigger)
                    .horizontalScroll(rememberScrollState())
                    .then(details.onTags?.let { Modifier.clickable(onClickLabel = tagsLabel, onClick = it) } ?: Modifier),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Tag, null, tint = muted, modifier = Modifier.size(Dimens.IconInline))
                if (details.tags.isEmpty()) {
                    Text(tagsLabel, style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1)
                    Icon(AppIcons.Add, stringResource(R.string.note_add_tag), tint = muted, modifier = Modifier.size(Dimens.IconInline))
                } else {
                    details.tags.forEach { CompactTag(it) }
                }
            }
        }
    }
}
