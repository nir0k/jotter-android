package io.github.nir0k.jotter.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.nir0k.jotter.designsystem.R
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.icons.IconPickerGroups
import io.github.nir0k.jotter.designsystem.icons.PickerGroup
import io.github.nir0k.jotter.designsystem.theme.ChosenColor
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.theme.Shapes
import io.github.nir0k.jotter.designsystem.theme.TagColor

/**
 * IconPickerDialog chooses the glyph of something the reader names: a note, a collection, a room.
 * A search field (not focused, so the keyboard does not cover the grid) over the glyphs in titled
 * groups; search matches a glyph's localized name, its glyph name and its group's title, so a
 * group's title lists the whole group, and the keyboard's Done chooses the first glyph found. No
 * icon leads the first group when [allowNone]. The grid keeps its height, scrolls and opens at the
 * current glyph, which is marked. Tapping a glyph applies it at once and closes the dialog.
 *
 * @param title Localized dialog title (what the glyph is for).
 * @param current The stored glyph name, or null for none.
 * @param onSelect Receives the chosen glyph name, or null for No icon.
 * @param onDismiss Closes the dialog without a change.
 * @param groups The groups to offer; every item icon group by default.
 * @param allowNone Whether No icon is offered (notes); an item that always has a glyph does not.
 * @param tint Colour of the glyphs, such as the item's chosen colour; the text colour by default.
 */
@Composable
fun IconPickerDialog(
    title: String,
    current: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    groups: List<PickerGroup> = IconPickerGroups.All,
    allowNone: Boolean = true,
    tint: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val resources = LocalResources.current
    var query by rememberSaveable { mutableStateOf("") }
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    val shown = groups.mapNotNull { group ->
        val groupTitle = resources.getString(group.title)
        val icons = group.icons.filter { icon ->
            val text = "${resources.getString(icon.label)} ${icon.name} $groupTitle".lowercase()
            words.all { it in text }
        }
        if (icons.isEmpty()) null else Triple(group, groupTitle, icons)
    }
    // The grid opens at the current glyph: count the cells before it (No icon, then a header and the glyphs of each group).
    val initial = remember {
        var index = if (allowNone) 1 else 0
        var found = 0
        for ((_, _, icons) in shown) {
            val at = icons.indexOfFirst { it.name == current }
            if (at >= 0) { found = index + 1 + at; break }
            index += 1 + icons.size
        }
        found
    }
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = initial)
    SearchDialogWindow(onDismiss) {
        Column(Modifier.padding(Dimens.DialogPadding), verticalArrangement = Arrangement.spacedBy(Dimens.DialogFieldGap)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            SearchField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.ds_icon_search_hint), label = stringResource(R.string.ds_icon_search))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(Dimens.TouchTarget),
                state = grid,
                modifier = Modifier.fillMaxWidth().height(Dimens.ChoiceListMaxHeight * 0.75f),
            ) {
                if (allowNone && query.isBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(onClick = { onSelect(null); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.ds_icon_none), color = if (current == null) scheme.primary else scheme.onSurface, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.ds_nothing_found), color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                    }
                }
                for ((group, groupTitle, icons) in shown) {
                    item(key = "group-${group.id}", span = { GridItemSpan(maxLineSpan) }) {
                        Text(groupTitle, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    }
                    items(icons.size, key = { icons[it].name }) { index ->
                        val icon = icons[index]
                        val selected = icon.name == current
                        val label = stringResource(icon.label)
                        Box(
                            Modifier
                                .size(Dimens.TouchTarget)
                                .clip(Shapes.Trigger)
                                .background(if (selected) scheme.primaryContainer else Color.Transparent)
                                .selectable(selected, role = Role.RadioButton) { onSelect(icon.name); onDismiss() }
                                .semantics { contentDescription = label },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon.icon, null, tint = if (selected) scheme.onPrimaryContainer else tint ?: scheme.onSurface, modifier = Modifier.size(Dimens.IconToolbar))
                        }
                    }
                }
            }
        }
    }
}

/** ColourLook is how the tiles of a [ColourPickerDialog] show the result. */
enum class ColourLook {
    /** A swatch: tags and item titles. */
    Swatch,

    /** A letter in the colour: the colour of text. */
    Text,

    /** A letter on the colour: a highlight. */
    Highlight,
}

/**
 * ColourPickerDialog chooses a colour people pick for their own things — a tag, an item's title,
 * text or its highlight. Tiles show the result in the current palette: Default first, the tag
 * colours in their order, then Custom colour…, which asks for `#rrggbb` and, while a custom colour
 * is chosen, shows it. Tapping a tile applies it at once and closes the dialog.
 *
 * @param title Localized dialog title (what is coloured).
 * @param current The stored value: a tag colour key, `#rrggbb` or null for Default.
 * @param onSelect Receives the chosen value, or null for Default.
 * @param onDismiss Closes the dialog without a change.
 * @param look How the tiles show the result.
 * @param allowCustom Whether Custom colour… is offered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColourPickerDialog(
    title: String,
    current: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    look: ColourLook = ColourLook.Swatch,
    allowCustom: Boolean = true,
) {
    var custom by rememberSaveable { mutableStateOf(false) }
    if (custom) {
        CustomColourDialog(current?.takeIf(ChosenColor::isCustom), { onSelect(it); onDismiss() }, onDismiss)
        return
    }
    val scheme = MaterialTheme.colorScheme
    val names = stringArrayResource(R.array.ds_tag_color_names)
    val tiles: List<Pair<String?, String>> = listOf<Pair<String?, String>>(null to stringResource(R.string.ds_colour_default)) +
        TagColor.entries.mapIndexed { i, colour -> colour.key to names.getOrElse(i) { colour.key } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = Shapes.Dialog, color = scheme.background) {
            Column(Modifier.padding(Dimens.DialogPadding), verticalArrangement = Arrangement.spacedBy(Dimens.DialogFieldGap)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                FlowRow(maxItemsInEachRow = 6, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for ((key, name) in tiles) {
                        ColourTile(ChosenColor.resolve(key), look, key == current, name) { onSelect(key); onDismiss() }
                    }
                }
                if (allowCustom) {
                    val chosen = current?.takeIf(ChosenColor::isCustom)
                    TextButton(onClick = { custom = true }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            val swatch = ChosenColor.resolve(chosen)
                            if (swatch != null) Box(Modifier.size(Dimens.IconInline).clip(Shapes.Trigger).background(ChosenColor.text(swatch, scheme)))
                            else Icon(AppIcons.Palette, null, modifier = Modifier.size(Dimens.IconInline))
                            Text(if (chosen != null) stringResource(R.string.ds_colour_custom_named, chosen) else stringResource(R.string.ds_colour_custom))
                        }
                    }
                }
            }
        }
    }
}

// ColourTile draws one choice of the colour grid as it will look: a swatch, a letter in the
// colour or a letter on it; Default shows the ordinary look. The current choice has a primary ring.
@Composable
private fun ColourTile(colour: Color?, look: ColourLook, selected: Boolean, name: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(Dimens.TouchTarget)
            .border(BorderStroke(2.dp, if (selected) scheme.primary else Color.Transparent), Shapes.Trigger)
            .clip(Shapes.Trigger)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        val sample = stringResource(R.string.ds_colour_sample)
        when (look) {
            ColourLook.Swatch ->
                if (colour != null) Box(Modifier.size(28.dp).clip(Shapes.Trigger).background(ChosenColor.text(colour, scheme)))
                else Box(Modifier.size(28.dp).border(1.dp, scheme.outline, Shapes.Trigger), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Close, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(Dimens.IconInline))
                }
            ColourLook.Text -> Text(sample, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colour?.let { ChosenColor.text(it, scheme) } ?: scheme.onSurface)
            ColourLook.Highlight -> Text(
                sample,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
                modifier = Modifier
                    .clip(Shapes.Trigger)
                    .background(colour?.let { ChosenColor.highlight(it, scheme) } ?: Color.Transparent)
                    .border(1.dp, if (colour == null) scheme.outline else Color.Transparent, Shapes.Trigger)
                    .padding(horizontal = 6.dp),
            )
        }
    }
}

// CustomColourDialog asks for a custom colour as #rrggbb; Save is enabled once the value is valid.
@Composable
private fun CustomColourDialog(current: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current ?: "#") }
    val value = text.trim().lowercase().let { if (it.startsWith("#")) it else "#$it" }
    FormDialog(
        title = stringResource(R.string.ds_colour_custom_title),
        confirmText = stringResource(R.string.ds_save),
        onConfirm = { onConfirm(value) },
        onDismiss = onDismiss,
        confirmEnabled = ChosenColor.isCustom(value),
    ) {
        AppTextField(text, { text = it }, stringResource(R.string.ds_colour_custom_field), supporting = stringResource(R.string.ds_colour_custom_hint))
    }
}
