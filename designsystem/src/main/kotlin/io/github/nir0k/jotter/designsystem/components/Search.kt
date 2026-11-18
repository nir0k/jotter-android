package io.github.nir0k.jotter.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.designsystem.R
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.theme.Shapes

/**
 * SearchField is the always-visible search surface of a top bar: 40 dp high (it grows with large
 * text), 10 dp radius, 18 dp glyphs, on the raised `surfaceContainerHighest` colour so it stands
 * out from the bar. Clearing removes only the query, never filters.
 *
 * @param query Current query.
 * @param onQueryChange Receives edits.
 * @param placeholder Localized hint.
 * @param label Localized name of the field for assistive technology; the hint by default.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.ds_search),
    label: String = placeholder,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val focus = LocalFocusManager.current
    Surface(shape = Shapes.SearchSurface, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier.heightIn(min = 40.dp)) {
        Row(Modifier.heightIn(min = 40.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Search, null, tint = muted, modifier = Modifier.size(Dimens.IconSecondary))
            Box(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1)
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(40.dp)) {
                    Icon(AppIcons.Close, stringResource(R.string.ds_clear_search), tint = muted, modifier = Modifier.size(Dimens.IconSecondary))
                }
            }
        }
    }
}

/**
 * FilterButton is one filter or sort control of a [FilterBar]: a chip that shows the current
 * value, is highlighted while it narrows the list, and opens a choice dialog. The label keeps to
 * one line, ending in an ellipsis when it does not fit, so a large font does not make the chip
 * grow out of its row.
 *
 * @param label Localized filter name or "Name: value".
 * @param active Whether the filter differs from its default.
 * @param onClick Opens the choice dialog.
 */
@Composable
fun FilterButton(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = active,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { Icon(AppIcons.ChevronDown, null, modifier = Modifier.size(Dimens.IconMetric)) },
        modifier = modifier,
    )
}

/** SortDirectionButton toggles ascending and descending order. */
@Composable
fun SortDirectionButton(descending: Boolean, onToggle: () -> Unit) {
    IconAction(
        if (descending) AppIcons.SortDescending else AppIcons.SortAscending,
        stringResource(if (descending) R.string.ds_sort_descending else R.string.ds_sort_ascending),
        onToggle,
        iconSize = Dimens.IconSecondary,
    )
}

/** ClearFiltersButton resets every filter (not the query); shown only while filters are active. */
@Composable
fun ClearFiltersButton(onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(stringResource(R.string.ds_clear_filters)) },
        leadingIcon = { Icon(AppIcons.Close, null, modifier = Modifier.size(Dimens.IconMetric)) },
    )
}
