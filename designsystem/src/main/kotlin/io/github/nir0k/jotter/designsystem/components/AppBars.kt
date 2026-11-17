package io.github.nir0k.jotter.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.designsystem.R
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens

/**
 * MenuAction is one entry of an overflow menu.
 *
 * @param label Localized entry text.
 * @param icon Leading 20 dp glyph.
 * @param destructive Whether the entry deletes something; it is drawn in the error colour.
 * @param enabled Whether the entry can be chosen.
 * @param onClick Invoked after the menu closes.
 */
data class MenuAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * OverflowMenuButton is the three-dot button that opens a menu of secondary actions. Entries
 * have labels and leading icons; destructive entries come last.
 *
 * @param items The menu entries.
 * @param tint Glyph colour; white over photos.
 */
@Composable
fun OverflowMenuButton(
    items: List<MenuAction>,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    contentDescription: String = stringResource(R.string.ds_more),
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(AppIcons.More, contentDescription, tint = tint, modifier = Modifier.size(Dimens.IconMenu))
        }
        ActionMenu(open, items) { open = false }
    }
}

/**
 * ActionMenu is the dropdown of [MenuAction]s anchored to the button it is placed beside: labels
 * with leading glyphs, destructive entries in the error colour. Choosing an entry closes it first.
 *
 * @param open Whether the menu is shown.
 * @param items The menu entries.
 * @param onDismiss Closes the menu.
 */
@Composable
internal fun ActionMenu(open: Boolean, items: List<MenuAction>, onDismiss: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        items.forEach { item ->
            val color = if (item.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            DropdownMenuItem(
                text = { Text(item.label, color = color) },
                leadingIcon = { Icon(item.icon, null, tint = color, modifier = Modifier.size(Dimens.IconMenu)) },
                enabled = item.enabled,
                onClick = {
                    onDismiss()
                    item.onClick()
                },
            )
        }
    }
}

/**
 * FilterToggle is the filter icon of a top bar. It is primary while the filter panel is open and
 * carries a dot while any filter is applied, even with the panel closed.
 *
 * @param shown Whether the filter panel is open.
 * @param active Whether any filter differs from its default.
 * @param onToggle Opens or closes the panel; filters are kept either way.
 */
@Composable
fun FilterToggle(shown: Boolean, active: Boolean, onToggle: () -> Unit) {
    val description = stringResource(if (shown) R.string.ds_hide_filters else R.string.ds_show_filters) +
        if (active) ". " + stringResource(R.string.ds_filters_active) else ""
    IconButton(onClick = onToggle) {
        Box {
            Icon(
                AppIcons.Filter,
                description,
                tint = if (shown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.IconToolbar),
            )
            if (active) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(8.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/**
 * AppTopBar is the small top app bar of every screen: Back on subordinate screens, the bold
 * title, an always-visible search field when the screen is searchable, the filter toggle when
 * the screen has filters, extra actions and the overflow menu. Filters open in a [FilterBar]
 * placed directly below the bar. The bar's background runs under a camera cutout while its title
 * and actions keep clear of it, also in landscape where the cutout is at the side.
 *
 * @param title Localized screen title.
 * @param onBack Back handler; null on top-level destinations.
 * @param searchQuery The current query, or null when the screen has no search.
 * @param onSearchQueryChange Receives the typed query.
 * @param filtersShown Whether the filter panel is open; null when the screen has no filters.
 * @param filtersActive Whether any filter is applied.
 * @param onToggleFilters Opens or closes the filter panel.
 * @param menu Overflow entries; the menu is hidden when empty.
 * @param actions Extra trailing icon actions placed before the filter toggle.
 * @param titleContent Variant: content drawn in place of the title text, such as a title edited
 *   in place on a note's screen, which labels itself; [title] is then not drawn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    searchQuery: String? = null,
    onSearchQueryChange: (String) -> Unit = {},
    filtersShown: Boolean? = null,
    filtersActive: Boolean = false,
    onToggleFilters: () -> Unit = {},
    menu: List<MenuAction> = emptyList(),
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
) {
    TopAppBar(
        modifier = modifier,
        windowInsets = TopAppBarDefaults.windowInsets.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(AppIcons.Back, stringResource(R.string.ds_back), modifier = Modifier.size(Dimens.IconToolbar))
                }
            }
        },
        title = {
            if (titleContent != null) {
                titleContent()
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        fontWeight = if (onBack == null) FontWeight.Bold else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (searchQuery != null) Modifier.padding(end = 12.dp) else Modifier,
                    )
                    if (searchQuery != null) SearchField(searchQuery, onSearchQueryChange, Modifier.weight(1f))
                }
            }
        },
        actions = {
            actions()
            if (filtersShown != null) FilterToggle(filtersShown, filtersActive, onToggleFilters)
            if (menu.isNotEmpty()) OverflowMenuButton(menu, tint = MaterialTheme.colorScheme.onSurface)
        },
    )
}

/**
 * AddButton is the round floating action that creates an item on a list screen. One per
 * screen, bottom-end, only for readers who may create.
 *
 * @param contentDescription Localized name of what is created ("New trip").
 */
@Composable
fun AddButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) { Icon(AppIcons.Add, contentDescription, modifier = Modifier.size(Dimens.IconToolbar)) }
}
