package io.github.nir0k.jotter.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.designsystem.R
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens

/**
 * FindBar finds and replaces text in a long document such as a note. It opens under the top bar
 * with the find field; the count shows "3 of 12" or "No matches" in the error colour, and the
 * arrows step through the matches. Match case ("Aa") is an optional toggle, shown only when the
 * product handles it; whole words and regular expressions are not offered. The chevron shows the replace row (Replace changes the current
 * match and steps on, Replace all changes every match in one undoable step). Close returns to the
 * text with the current match selected. The product finds the matches; the bar only reports input.
 *
 * @param query The text to find.
 * @param onQueryChange Receives edits.
 * @param current Index of the current match from 1, or 0.
 * @param total Number of matches.
 * @param onNext Steps to the next match.
 * @param onPrevious Steps to the previous match.
 * @param onClose Closes the bar.
 * @param matchCase Whether the search tells upper and lower case apart.
 * @param onMatchCaseChange Receives the new state of the Match case toggle, or null to show no
 *   toggle for a product that always ignores case.
 * @param replacement The replacement text, or null when the document cannot be edited.
 * @param onReplacementChange Receives edits of the replacement.
 * @param replaceShown Whether the replace row is shown.
 * @param onToggleReplace Shows or hides the replace row.
 * @param onReplace Replaces the current match.
 * @param onReplaceAll Replaces every match.
 */
@Composable
fun FindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    current: Int,
    total: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    matchCase: Boolean = false,
    onMatchCaseChange: ((Boolean) -> Unit)? = null,
    replacement: String? = null,
    onReplacementChange: (String) -> Unit = {},
    replaceShown: Boolean = false,
    onToggleReplace: () -> Unit = {},
    onReplace: () -> Unit = {},
    onReplaceAll: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), color = scheme.surface) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (replacement != null) {
                    IconAction(
                        if (replaceShown) AppIcons.ChevronDown else AppIcons.ChevronRight,
                        stringResource(if (replaceShown) R.string.ds_hide_replace else R.string.ds_show_replace),
                        onToggleReplace,
                        iconSize = Dimens.IconChevron,
                    )
                }
                SearchField(query, onQueryChange, Modifier.weight(1f), placeholder = stringResource(R.string.ds_find))
                if (query.isNotEmpty()) {
                    Text(
                        if (total > 0) stringResource(R.string.ds_match_count, current, total) else stringResource(R.string.ds_no_matches),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (total > 0) scheme.onSurfaceVariant else scheme.error,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                onMatchCaseChange?.let { MatchCaseToggle(matchCase, it) }
                IconAction(AppIcons.ChevronUp, stringResource(R.string.ds_find_previous), onPrevious, enabled = total > 0, iconSize = Dimens.IconSecondary)
                IconAction(AppIcons.ChevronDown, stringResource(R.string.ds_find_next), onNext, enabled = total > 0, iconSize = Dimens.IconSecondary)
                IconAction(AppIcons.Close, stringResource(R.string.ds_close), onClose, iconSize = Dimens.IconSecondary)
            }
            if (replacement != null && replaceShown) {
                Row(Modifier.padding(start = Dimens.TouchTarget), verticalAlignment = Alignment.CenterVertically) {
                    SearchField(replacement, onReplacementChange, Modifier.weight(1f), placeholder = stringResource(R.string.ds_replace_with))
                    TextButton(onClick = onReplace, enabled = total > 0) { Text(stringResource(R.string.ds_replace)) }
                    TextButton(onClick = onReplaceAll, enabled = total > 0) { Text(stringResource(R.string.ds_replace_all)) }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = scheme.outlineVariant)
        }
    }
}

// MatchCaseToggle is the "Aa" toggle of the find bar, on the primary container while on, like the
// toggles of the formatting bar; it is announced as "Match case" with its state.
@Composable
private fun MatchCaseToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.ds_match_case)
    IconToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = IconButtonDefaults.iconToggleButtonColors(
            contentColor = scheme.onSurfaceVariant,
            checkedContainerColor = scheme.primaryContainer,
            checkedContentColor = scheme.onPrimaryContainer,
        ),
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Text("Aa", style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics { })
    }
}
