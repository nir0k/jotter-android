package io.github.nir0k.jotter.feature.notes

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import io.github.nir0k.jotter.core.search.Search
import io.github.nir0k.jotter.core.search.SearchScope
import io.github.nir0k.jotter.core.search.parseQuery
import java.time.LocalDate
import java.time.ZoneId

/**
 * DayRange is a range of whole days in local time, both ends included.
 *
 * @param first The first day.
 * @param last The last day.
 */
data class DayRange(val first: LocalDate, val last: LocalDate)

private val AllScopes = SearchScope.entries.toSet()

/**
 * NoteSearch is the query and the filters of one note list, as the person set them.
 *
 * @param scopes Where the words are looked for; every scope by default.
 * @param collections Notes in any of these collections or their descendants.
 * @param tags Notes with any of these tags or their children.
 * @param everywhere Whether the search covers all notes instead of the list's view.
 * @param includeArchived Whether archived notes are found too.
 */
data class NoteSearch(
    val query: String = "",
    val scopes: Set<SearchScope> = AllScopes,
    val created: DayRange? = null,
    val updated: DayRange? = null,
    val collections: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val withAttachments: Boolean = false,
    val withLocation: Boolean = false,
    val everywhere: Boolean = false,
    val includeArchived: Boolean = false,
) {
    /** Tells whether any filter is applied, regardless of the query: the filter toggle's dot. */
    val hasFilters: Boolean
        get() = scopes != AllScopes || created != null || updated != null || collections.isNotEmpty() ||
            tags.isNotEmpty() || withAttachments || withLocation || everywhere || includeArchived

    /** Tells whether the list shows search results rather than the view's notes. */
    val isActive: Boolean get() = hasFilters || parseQuery(query).isNotEmpty()

    /** Returns the same query without filters, for Clear filters. */
    fun withoutFilters() = NoteSearch(query = query)

    /**
     * Turns the search into the index's: day ranges become the start of their first day and the
     * start of the day after their last, in the device's time zone.
     */
    fun toSearch(zone: ZoneId = ZoneId.systemDefault()): Search = Search(
        query = query,
        scopes = scopes,
        createdFrom = created?.first?.atStartOfDay(zone)?.toInstant(),
        createdUntil = created?.last?.plusDays(1)?.atStartOfDay(zone)?.toInstant(),
        updatedFrom = updated?.first?.atStartOfDay(zone)?.toInstant(),
        updatedUntil = updated?.last?.plusDays(1)?.atStartOfDay(zone)?.toInstant(),
        collections = collections.toList(),
        tags = tags.toList(),
        withAttachments = withAttachments,
        withLocation = withLocation,
        includeArchived = includeArchived,
    )

    companion object {
        // Ranges are kept as epoch days; a missing range has none.
        private fun DayRange?.days(): List<Long> = this?.let { listOf(it.first.toEpochDay(), it.last.toEpochDay()) }.orEmpty()

        private fun List<Long>.range(): DayRange? = if (size == 2) DayRange(LocalDate.ofEpochDay(this[0]), LocalDate.ofEpochDay(this[1])) else null

        /** Keeps a search across rotation and process death in values a saved state holds. */
        val Saver: Saver<NoteSearch, Any> = listSaver(
            save = {
                listOf(
                    it.query, ArrayList(it.scopes.map(SearchScope::name)), ArrayList(it.created.days()), ArrayList(it.updated.days()),
                    ArrayList(it.collections), ArrayList(it.tags), it.withAttachments, it.withLocation, it.everywhere, it.includeArchived,
                )
            },
            restore = {
                @Suppress("UNCHECKED_CAST")
                NoteSearch(
                    query = it[0] as String,
                    scopes = (it[1] as List<String>).mapNotNull { name -> SearchScope.entries.firstOrNull { scope -> scope.name == name } }.toSet(),
                    created = (it[2] as List<Long>).range(),
                    updated = (it[3] as List<Long>).range(),
                    collections = (it[4] as List<String>).toSet(),
                    tags = (it[5] as List<String>).toSet(),
                    withAttachments = it[6] as Boolean,
                    withLocation = it[7] as Boolean,
                    everywhere = it[8] as Boolean,
                    includeArchived = it[9] as Boolean,
                )
            },
        )
    }
}
