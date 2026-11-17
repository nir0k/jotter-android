package io.github.nir0k.jotter.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.nir0k.jotter.designsystem.R
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.theme.Shapes

/**
 * Choice is one option of a choice dialog, chip group or trigger.
 *
 * @param key Stable identity that is stored and sent to the server.
 * @param label Localized option text.
 * @param icon Optional 20 dp glyph shown in the dialog and the trigger.
 */
data class Choice<K>(val key: K, val label: String, val icon: ImageVector? = null)

/**
 * ChoiceDialog is the modal list used for every choice that does not fit a chip group:
 * categories, types, long lists. Tapping an option closes the dialog and commits its key;
 * dismissing keeps the previous value.
 *
 * Variant: a long list the reader looks things up in (tags, languages, notes to link) passes
 * [searchHint], which puts a search field under the title; the dialog then spans the screen like
 * a form dialog and stays above the keyboard. It filters the options by their labels itself; a
 * product that searches on its own (in a database) also passes [onQueryChange] and supplies the
 * matching options.
 *
 * @param title Localized dialog title (the field's name).
 * @param options The options in display order.
 * @param selected The current key, highlighted in the primary colour.
 * @param onSelect Receives the chosen key after the dialog closes.
 * @param searchHint Localized hint of the search field, or null for a dialog without search.
 * @param onQueryChange Receives the typed query when the product filters [options] itself.
 * @param onDismiss Closes the dialog without a change.
 */
@Composable
fun <K> ChoiceDialog(
    title: String,
    options: List<Choice<K>>,
    selected: K?,
    onSelect: (K) -> Unit,
    searchHint: String? = null,
    onQueryChange: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = matchingChoices(options, query, onQueryChange != null)
    ChoiceWindow(searchable = searchHint != null, onDismiss) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (searchHint != null) {
                ChoiceSearch(query, searchHint, nothingFound = shown.isEmpty()) {
                    query = it
                    onQueryChange?.invoke(it)
                }
            }
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = Dimens.ChoiceListMaxHeight)) {
                items(shown, key = { it.key.toString() }) { option ->
                    ChoiceRowItem(option, option.key == selected) {
                        onDismiss()
                        onSelect(option.key)
                    }
                }
            }
        }
    }
}

// ChoiceRowItem renders one dialog option; the current one is drawn in the primary colour.
@Composable
private fun <K> ChoiceRowItem(option: Choice<K>, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        option.icon?.let {
            Icon(it, null, tint = if (selected) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(Dimens.IconMenu))
        }
        Text(
            option.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) scheme.primary else scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(AppIcons.Check, null, tint = scheme.primary, modifier = Modifier.size(Dimens.IconSecondary))
    }
}

/**
 * MultiChoiceDialog is the modal list for a filter that takes several values at once (groups,
 * accounts, activity types). Options are checkboxes; the change is committed with Apply, so the
 * list behind the dialog reloads once. Clear unticks every option: an empty selection means the
 * filter is off and every item is shown.
 *
 * Variant: with [searchHint] a search field under the title narrows the options, as in
 * [ChoiceDialog]; ticked options stay ticked while they are hidden by the query.
 *
 * @param title Localized dialog title (the filter's name).
 * @param options The options in display order.
 * @param selected The keys chosen now; empty when the filter is off.
 * @param onApply Receives the new selection after the dialog closes.
 * @param searchHint Localized hint of the search field, or null for a dialog without search.
 * @param onQueryChange Receives the typed query when the product filters [options] itself.
 * @param onDismiss Closes the dialog without a change.
 */
@Composable
fun <K> MultiChoiceDialog(
    title: String,
    options: List<Choice<K>>,
    selected: Set<K>,
    onApply: (Set<K>) -> Unit,
    searchHint: String? = null,
    onQueryChange: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(selected) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = matchingChoices(options, query, onQueryChange != null)
    ChoiceWindow(searchable = searchHint != null, onDismiss) {
        Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (searchHint != null) {
                ChoiceSearch(query, searchHint, nothingFound = shown.isEmpty()) {
                    query = it
                    onQueryChange?.invoke(it)
                }
            }
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = Dimens.ChoiceListMaxHeight)) {
                items(shown, key = { it.key.toString() }) { option ->
                    val checked = option.key in draft
                    MultiChoiceRowItem(option, checked) { draft = if (checked) draft - option.key else draft + option.key }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { draft = emptySet() }, enabled = draft.isNotEmpty()) { Text(stringResource(R.string.ds_clear_filters)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ds_cancel)) }
                TextButton(onClick = {
                    onDismiss()
                    onApply(draft)
                }) { Text(stringResource(R.string.ds_apply)) }
            }
        }
    }
}

// ChoiceWindow is the window of a choice dialog: the platform's dialog width, or for a searchable
// dialog the window of a dialog with a search field.
@Composable
private fun ChoiceWindow(searchable: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (searchable) {
        SearchDialogWindow(onDismiss, content)
        return
    }
    Dialog(onDismissRequest = onDismiss) { Surface(shape = Shapes.Dialog, color = MaterialTheme.colorScheme.background, content = content) }
}

/**
 * SearchDialogWindow is the window of a dialog with a search field (a searchable choice, the icon
 * picker): across the screen within the dialog margin, as a form dialog, and kept above the
 * keyboard the field opens, so the results stay in sight while the reader types.
 *
 * @param onDismiss Closes the dialog.
 * @param content The dialog's content on its surface.
 */
@Composable
internal fun SearchDialogWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(Dimens.DialogMargin).imePadding(), shape = Shapes.Dialog, color = MaterialTheme.colorScheme.background, content = content)
    }
}

/**
 * matchingChoices returns the options of a searchable choice dialog that contain every word of
 * the query in their labels, ignoring case; all of them for a blank query, or when the product
 * filters the options itself ([external]).
 */
internal fun <K> matchingChoices(options: List<Choice<K>>, query: String, external: Boolean): List<Choice<K>> {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    if (external || words.isEmpty()) return options
    return options.filter { option -> option.label.lowercase().let { label -> words.all { it in label } } }
}

// ChoiceSearch is the search field of a searchable choice dialog, with "Nothing found" under it
// when the query matches no option.
@Composable
private fun ChoiceSearch(query: String, hint: String, nothingFound: Boolean, onQueryChange: (String) -> Unit) {
    SearchField(query, onQueryChange, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), placeholder = hint)
    if (nothingFound && query.isNotBlank()) {
        Text(
            stringResource(R.string.ds_nothing_found),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

// MultiChoiceRowItem renders one option of a multiple choice: a checkbox, the optional glyph and
// the label; the whole row toggles the option.
@Composable
private fun <K> MultiChoiceRowItem(option: Choice<K>, checked: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(start = 8.dp, end = 20.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.size(Dimens.TouchTarget))
        option.icon?.let { Icon(it, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(Dimens.IconMenu)) }
        Text(option.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/**
 * multiChoiceLabel is the text of a [FilterButton] over a multiple choice: the filter's name while
 * it is off, the option itself when one is chosen, and "Name: 3" for more.
 *
 * @param name Localized filter name.
 * @param options The options of the filter.
 * @param selected The chosen keys.
 */
@Composable
fun <K> multiChoiceLabel(name: String, options: List<Choice<K>>, selected: Set<K>): String = when (selected.size) {
    0 -> name
    1 -> options.firstOrNull { it.key in selected }?.label ?: name
    else -> stringResource(R.string.ds_filter_count, name, selected.size)
}

/**
 * CategoryTrigger is the compact "category ⌄" control inside a card: leading glyph, current
 * label and a chevron. It opens a [ChoiceDialog] and commits immediately.
 *
 * @param title Dialog title.
 * @param options The options.
 * @param selected The current key.
 * @param onSelect Receives the new key.
 * @param placeholder Text shown when nothing is chosen.
 * @param icon Leading glyph used when the chosen option has none (a globe for the language).
 */
@Composable
fun <K> CategoryTrigger(
    title: String,
    options: List<Choice<K>>,
    selected: K?,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    var open by remember { mutableStateOf(false) }
    val chosen = options.firstOrNull { it.key == selected }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .clip(Shapes.Trigger)
            .clickable(enabled = enabled, role = Role.DropdownList, onClickLabel = title) { open = true }
            .heightIn(min = 32.dp)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        (chosen?.icon ?: icon)?.let { Icon(it, null, tint = tint, modifier = Modifier.size(Dimens.IconInline)) }
        Text(chosen?.label ?: placeholder, style = MaterialTheme.typography.bodySmall, color = tint)
        Icon(AppIcons.ChevronDown, null, tint = tint, modifier = Modifier.size(Dimens.IconChevron))
    }
    if (open) ChoiceDialog(title, options, selected, onSelect) { open = false }
}

/**
 * PickerField looks like a read-only outlined field and opens a picker (choice dialog, date or
 * time picker) supplied by the caller. Use it inside forms where other fields are outlined too.
 *
 * @param label Field label.
 * @param value Current value text; empty shows [placeholder].
 * @param onClick Opens the picker.
 * @param leadingIcon Optional leading glyph.
 */
@Composable
fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value.ifEmpty { placeholder },
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            singleLine = true,
            enabled = false,
            shape = Shapes.Field,
            leadingIcon = leadingIcon?.let { { Icon(it, null, modifier = Modifier.size(Dimens.IconMenu)) } },
            trailingIcon = { Icon(AppIcons.ChevronDown, null, modifier = Modifier.size(Dimens.IconInline)) },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = if (value.isEmpty()) scheme.onSurfaceVariant else scheme.onSurface,
                disabledBorderColor = scheme.outline,
                disabledLabelColor = scheme.onSurfaceVariant,
                disabledLeadingIconColor = scheme.onSurfaceVariant,
                disabledTrailingIconColor = scheme.onSurfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clip(Shapes.Field).clickable(role = Role.DropdownList, onClick = onClick))
    }
}

/**
 * SegmentedChoice is the single choice among two to four short options shown at once (units,
 * date format, light/dark): Material segmented buttons that apply immediately. Use it instead
 * of separate buttons that each pick a value; for a settings row such as the language, or with
 * more options or long labels, use [ChoiceRow] or [CategoryTrigger].
 *
 * @param label Localized name of the setting above the buttons; null when a surrounding row names it.
 * @param options Two to four options with short labels; an option's icon replaces the selection check.
 * @param selected The current key.
 * @param onSelect Receives the new key.
 * @param enabled Whether the choice can be changed.
 * @param fillWidth Whether the buttons span the available width; false keeps them compact (toolbars).
 */
@Composable
fun <K> SegmentedChoice(
    label: String?,
    options: List<Choice<K>>,
    selected: K,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillWidth: Boolean = true,
) {
    Column(if (fillWidth) modifier.fillMaxWidth() else modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
        SingleChoiceSegmentedButtonRow(if (fillWidth) Modifier.fillMaxWidth() else Modifier) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option.key == selected,
                    onClick = { onSelect(option.key) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    icon = {
                        val icon = option.icon
                        if (icon != null) Icon(icon, null, modifier = Modifier.size(Dimens.IconInline))
                        else SegmentedButtonDefaults.Icon(option.key == selected)
                    },
                ) { Text(option.label, maxLines = 1) }
            }
        }
    }
}
