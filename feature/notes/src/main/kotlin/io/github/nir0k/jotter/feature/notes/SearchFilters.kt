package io.github.nir0k.jotter.feature.notes

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import io.github.nir0k.jotter.core.search.SearchScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import io.github.nir0k.jotter.designsystem.components.Choice
import io.github.nir0k.jotter.designsystem.components.ClearFiltersButton
import io.github.nir0k.jotter.designsystem.components.FilterButton
import io.github.nir0k.jotter.designsystem.components.MultiChoiceDialog
import io.github.nir0k.jotter.designsystem.components.multiChoiceLabel
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.R as DesignR

/**
 * SearchOptions are what a list's search window offers beyond the query.
 *
 * @param collections The collections, each labelled with its path in the tree.
 * @param tags The tags of the library's active notes.
 * @param offerEverywhere Whether the list is narrower than all notes, so Search everywhere applies.
 * @param offerArchived Whether the list leaves archived notes out, so Include archived applies.
 */
class SearchOptions(
    val collections: List<Choice<String>>,
    val tags: List<Choice<String>>,
    val offerEverywhere: Boolean,
    val offerArchived: Boolean,
)


/** The dialog of the filters that is open. */
private enum class FilterDialog { Scopes, Created, Updated, Collections, Tags }

/** Formats a day range for its chip: short dates, with the year when it is not the current one. */
@Composable
private fun rangeLabel(range: DayRange): String {
    val locale = LocalConfiguration.current.locales[0]
    val thisYear = range.first.year == LocalDate.now().year && range.last.year == range.first.year
    val format = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (thisYear) "dMMM" else "dMMMy"), locale)
    return if (range.first == range.last) format.format(range.first) else "${format.format(range.first)} – ${format.format(range.last)}"
}

/**
 * SearchFilters are the search filters of the search window: Search in, Created, Updated, Collections, Tags, the on/off filters and Clear while a filter is applied. The
 * on/off filters are Material filter chips without a chevron, as the design system's chips open a dialog.
 *
 * @param search The list's search.
 * @param onSearch Receives a changed search.
 * @param options What the panel offers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchFilters(search: NoteSearch, onSearch: (NoteSearch) -> Unit, options: SearchOptions) {
    var open by remember { mutableStateOf<FilterDialog?>(null) }
    val scopes = listOf(
        Choice(SearchScope.Title, stringResource(R.string.scope_title)),
        Choice(SearchScope.Text, stringResource(R.string.scope_text)),
        Choice(SearchScope.Tags, stringResource(R.string.scope_tags)),
        Choice(SearchScope.Collections, stringResource(R.string.scope_collections)),
    )
    // Every scope ticked is the default, shown as the filter being off.
    val scopesShown = if (search.scopes.size == scopes.size) emptySet() else search.scopes
    FilterButton(multiChoiceLabel(stringResource(R.string.filter_scopes), scopes, scopesShown), scopesShown.isNotEmpty(), { open = FilterDialog.Scopes })
    val createdName = stringResource(R.string.filter_created)
    FilterButton(search.created?.let { "$createdName: ${rangeLabel(it)}" } ?: createdName, search.created != null, { open = FilterDialog.Created })
    val updatedName = stringResource(R.string.filter_updated)
    FilterButton(search.updated?.let { "$updatedName: ${rangeLabel(it)}" } ?: updatedName, search.updated != null, { open = FilterDialog.Updated })
    if (options.collections.isNotEmpty()) {
        FilterButton(
            multiChoiceLabel(stringResource(R.string.filter_collections), options.collections, search.collections),
            search.collections.isNotEmpty(),
            { open = FilterDialog.Collections },
        )
    }
    if (options.tags.isNotEmpty()) {
        FilterButton(multiChoiceLabel(stringResource(R.string.filter_tags), options.tags, search.tags), search.tags.isNotEmpty(), { open = FilterDialog.Tags })
    }
    FilterChip(search.withAttachments, { onSearch(search.copy(withAttachments = !search.withAttachments)) }, { Text(stringResource(R.string.filter_attachments)) })
    FilterChip(search.withLocation, { onSearch(search.copy(withLocation = !search.withLocation)) }, { Text(stringResource(R.string.filter_location)) })
    if (options.offerEverywhere) {
        FilterChip(search.everywhere, { onSearch(search.copy(everywhere = !search.everywhere)) }, { Text(stringResource(R.string.filter_everywhere)) })
    }
    if (options.offerArchived) {
        FilterChip(search.includeArchived, { onSearch(search.copy(includeArchived = !search.includeArchived)) }, { Text(stringResource(R.string.filter_archived)) })
    }
    if (search.hasFilters) ClearFiltersButton { onSearch(search.withoutFilters()) }

    val close = { open = null }
    when (open) {
        FilterDialog.Scopes -> MultiChoiceDialog(stringResource(R.string.filter_scopes), scopes, scopesShown, {
            onSearch(search.copy(scopes = it.ifEmpty { scopes.map(Choice<SearchScope>::key).toSet() }))
        }, onDismiss = close)
        FilterDialog.Created -> DayRangeDialog(createdName, search.created, { onSearch(search.copy(created = it)) }, close)
        FilterDialog.Updated -> DayRangeDialog(updatedName, search.updated, { onSearch(search.copy(updated = it)) }, close)
        FilterDialog.Collections ->
            MultiChoiceDialog(stringResource(R.string.filter_collections), options.collections, search.collections, { onSearch(search.copy(collections = it)) }, onDismiss = close)
        FilterDialog.Tags -> MultiChoiceDialog(stringResource(R.string.filter_tags), options.tags, search.tags, { onSearch(search.copy(tags = it)) }, onDismiss = close)
        null -> Unit
    }
}

/**
 * DayRangeDialog chooses a range of days with Material's date range picker, as the design system
 * has none: Clear removes the filter, Apply needs both ends.
 *
 * @param title The filter's name.
 * @param range The current range, or null.
 * @param onApply Receives the new range, null when cleared.
 * @param onDismiss Closes the dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayRangeDialog(title: String, range: DayRange?, onApply: (DayRange?) -> Unit, onDismiss: () -> Unit) {
    // The picker counts days as UTC midnights.
    fun LocalDate.millis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun Long.day() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
    val state = rememberDateRangePickerState(range?.first?.millis(), range?.last?.millis())
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                if (start != null) onApply(DayRange(start.day(), (end ?: start).day()))
            }, enabled = start != null) { Text(stringResource(DesignR.string.ds_apply)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    onDismiss()
                    onApply(null)
                }, enabled = range != null) { Text(stringResource(DesignR.string.ds_clear_filters)) }
                TextButton(onClick = onDismiss) { Text(stringResource(DesignR.string.ds_cancel)) }
            }
        },
    ) {
        DateRangePicker(state, title = { Text(title, Modifier.padding(start = Dimens.DialogPadding, top = Dimens.DialogPadding)) })
    }
}
