package io.github.nir0k.jotter.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import io.github.nir0k.jotter.core.format.Collection
import io.github.nir0k.jotter.core.organize.RuleChange
import io.github.nir0k.jotter.designsystem.components.Choice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Small pieces that screens of several features share: dates as the interface shows them, the
// collection choices of pickers, pull to refresh and the message announcing what rules filed.

/** Formats a date and time in a language and the device's time zone. */
fun dateTimeText(time: Instant, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(time.atZone(ZoneId.systemDefault()))

/** Formats a date and time in the interface language and the device's time zone. */
@Composable
fun dateTimeText(time: Instant): String = dateTimeText(time, LocalConfiguration.current.locales[0])

/**
 * Lists the collection tree depth first, parents before their children, each labelled with the
 * names of its ancestors ("Work / Projects"), so collections of the same name are told apart.
 */
fun collectionChoices(collections: List<Collection>): List<Choice<String>> {
    val children = collections.groupBy { it.parent }
    val choices = mutableListOf<Choice<String>>()
    val seen = mutableSetOf<String>()
    fun walk(parent: String?, path: String) {
        for (collection in children[parent].orEmpty()) {
            if (!seen.add(collection.id)) continue
            val label = if (path.isEmpty()) collection.name else "$path / ${collection.name}"
            choices += Choice(collection.id, label)
            walk(collection.id, label)
        }
    }
    walk(null, "")
    return choices
}

/**
 * Refresh is pull to refresh of a note list, which synchronises the library.
 *
 * @param refreshing Whether a run is going on.
 * @param onRefresh Starts a run.
 */
class Refresh(val refreshing: Boolean, val onRefresh: () -> Unit)

/**
 * Says what the rules did, naming the notes' new collections, as the message with Undo shows
 * it.
 *
 * @param changes What the rules changed.
 * @param collections The collection tree, for the names.
 * @param untitled The name of a note without a title.
 */
fun rulesFiledMessage(resources: Resources, changes: List<RuleChange>, collections: List<Collection>, untitled: String): String {
    // A note moved into a collection it was already in names that collection.
    val ids = changes.flatMap { change -> change.added.ifEmpty { change.after.collections } }.distinct()
    val names = ids.mapNotNull { id -> collections.firstOrNull { it.id == id }?.name }.joinToString(", ")
    val single = changes.singleOrNull()
    return if (single != null) resources.getString(R.string.rules_filed_one, single.after.title.ifEmpty { untitled }, names)
    else resources.getQuantityString(R.plurals.rules_filed, changes.size, changes.size, names)
}
