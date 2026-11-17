package io.github.nir0k.jotter.core.organize

import io.github.nir0k.jotter.core.format.NoteMeta
import io.github.nir0k.jotter.core.format.Rule
import io.github.nir0k.jotter.core.format.RuleConditionKind
import io.github.nir0k.jotter.core.format.isTagWithin
import io.github.nir0k.jotter.core.format.normalizedTag
import io.github.nir0k.jotter.core.format.tagKey

/** The conditions Jotter for Android carries out: rules file notes by their tags only. */
private val SupportedConditions = setOf(RuleConditionKind.TagEquals, RuleConditionKind.NoTags)

/**
 * Tells whether this client carries out a rule. A rule with conditions on the title or with
 * regular expressions, as the desktop writes them, is listed but never run.
 */
val Rule.isSupported: Boolean get() = conditions.all { it.kind in SupportedConditions }

/**
 * Tells whether a note with [tags] matches a rule's conditions: All fails on the first miss, Any
 * holds on the first hit. A tag condition holds for the tag and its children, compared without
 * case; a rule without conditions or with unsupported ones never matches.
 */
fun ruleMatches(rule: Rule, tags: List<String>): Boolean {
    if (rule.conditions.isEmpty() || !rule.isSupported) return false
    val keys = tags.map(::tagKey)
    val hits = rule.conditions.asSequence().map { condition ->
        if (condition.kind == RuleConditionKind.NoTags) {
            tags.isEmpty()
        } else {
            val wanted = normalizedTag(condition.value)
            wanted.isNotEmpty() && keys.any { isTagWithin(it, wanted) }
        }
    }
    return if (rule.matchAll) hits.all { it } else hits.any { it }
}

/**
 * Carries out a rule's action on a note: Add adds the note to the collections, keeping its
 * others; Move leaves only the first. Targets that no longer exist are skipped.
 *
 * @param collections The identifiers of the existing collections.
 * @return The note's new metadata, the same when no target exists.
 */
fun fileByRule(rule: Rule, meta: NoteMeta, collections: Set<String>): NoteMeta {
    val targets = rule.action.collections.filter { it in collections }
    if (targets.isEmpty()) return meta
    val filed = if (rule.action.move) listOf(targets.first()) else meta.collections + targets.filter { it !in meta.collections }.distinct()
    return meta.copy(collections = filed)
}

/**
 * Runs the enabled rules in their order on a note that was created or whose tags changed. A rule
 * acts only when the note starts matching it, so a note taken out of a collection by hand stays
 * out after its next edit; a matching rule with Stop ends the run. Archived and trashed notes
 * are left alone.
 *
 * @param before The note's tags before the change, or null for a new note.
 * @param meta The note after the change.
 * @param collections The identifiers of the existing collections.
 * @return The note's metadata with what the rules changed.
 */
fun applyRules(rules: List<Rule>, before: List<String>?, meta: NoteMeta, collections: Set<String>): NoteMeta {
    if (meta.archived != null || meta.trashed != null) return meta
    var result = meta
    for (rule in rules) {
        if (!rule.enabled || !ruleMatches(rule, meta.tags)) continue
        if (before == null || !ruleMatches(rule, before)) result = fileByRule(rule, result, collections)
        if (rule.stop) break
    }
    return result
}

/**
 * A change of a note's collections made by rules, kept for the message naming the collections
 * and for Undo, which gives the note back its collections.
 *
 * @param before The note's metadata before the rules ran.
 * @param after The note's metadata as the rules left it.
 */
data class RuleChange(val before: NoteMeta, val after: NoteMeta) {
    /** The collections the rules added the note to. */
    val added: List<String> get() = after.collections.filter { it !in before.collections }

    companion object {
        /** Returns the change rules made to a note, or null when its collections stayed. */
        fun of(before: NoteMeta, after: NoteMeta): RuleChange? = RuleChange(before, after).takeIf { before.collections != after.collections }
    }
}
