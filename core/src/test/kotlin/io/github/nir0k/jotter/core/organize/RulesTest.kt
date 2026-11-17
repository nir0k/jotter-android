package io.github.nir0k.jotter.core.organize

import io.github.nir0k.jotter.core.format.Collection
import io.github.nir0k.jotter.core.format.Library
import io.github.nir0k.jotter.core.format.Note
import io.github.nir0k.jotter.core.format.NoteMeta
import io.github.nir0k.jotter.core.format.Rule
import io.github.nir0k.jotter.core.format.RuleAction
import io.github.nir0k.jotter.core.format.RuleCondition
import io.github.nir0k.jotter.core.format.RuleConditionKind
import io.github.nir0k.jotter.core.format.newId
import io.github.nir0k.jotter.core.format.newNote
import io.github.nir0k.jotter.core.format.now
import io.github.nir0k.jotter.core.index.Index
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks rules: tag conditions, when a rule acts, its actions, and rules running as notes are written, with Undo and Apply to existing notes. */
@OptIn(ExperimentalPathApi::class)
class RulesTest {
    private val dir: Path = createTempDirectory()
    private val library = Library.create(dir.resolve("library"))
    private val index = Index.open(dir.resolve("index.sqlite"), String.CASE_INSENSITIVE_ORDER)
    private val work = collection("Work")
    private val ideas = collection("Ideas")

    init {
        library.changeCollections { listOf(work, ideas) }
    }

    @After
    fun cleanup() {
        index.close()
        dir.deleteRecursively()
    }

    private fun collection(name: String): Collection {
        val time = now()
        return Collection(id = newId(), parent = null, name = name, created = time, updated = time)
    }

    private fun rule(vararg conditions: RuleCondition, targets: List<String>, move: Boolean = false, stop: Boolean = false): Rule {
        val time = now()
        return Rule(
            id = newId(), name = "Rule", conditions = conditions.toList(), action = RuleAction(move, targets), stop = stop,
            created = time, updated = time,
        )
    }

    private fun tag(value: String) = RuleCondition(RuleConditionKind.TagEquals, value)

    private fun meta(tags: List<String> = emptyList(), collections: List<String> = emptyList()): NoteMeta {
        val time = now()
        return NoteMeta(id = newId(), tags = tags, collections = collections, created = time, updated = time)
    }

    /** A tag condition holds for the tag and its children without case; All and Any; a rule with an unsupported condition never matches. */
    @Test
    fun conditions() {
        val all = rule(tag("#Work"), RuleCondition(RuleConditionKind.NoTags), targets = emptyList())
        assertFalse(ruleMatches(all, listOf("work/planning")))
        val any = all.copy(matchAll = false)
        assertTrue(ruleMatches(any, listOf("WORK/planning")))
        assertTrue(ruleMatches(any, emptyList()))
        assertFalse(ruleMatches(any, listOf("workshop")))
        val title = all.copy(matchAll = false, conditions = listOf(tag("work"), RuleCondition(RuleConditionKind.TitleContains, "x")))
        assertFalse(title.isSupported)
        assertFalse(ruleMatches(title, listOf("work")))
    }

    /** A rule acts only when the note starts matching it; Stop skips the rules after it; Move replaces the collections. */
    @Test
    fun acting() {
        val existing = setOf(work.id, ideas.id)
        val rules = listOf(rule(tag("work"), targets = listOf(work.id), stop = true), rule(tag("work"), targets = listOf(ideas.id)))
        val filed = applyRules(rules, null, meta(tags = listOf("work")), existing)
        assertEquals(listOf(work.id), filed.collections)

        // Taken out of the collection by hand, the note stays out after its next change of tags.
        val edited = applyRules(rules, listOf("work"), filed.copy(collections = emptyList(), tags = listOf("work", "x")), existing)
        assertEquals(emptyList<String>(), edited.collections)

        val archived = meta(tags = listOf("work")).copy(archived = now())
        assertEquals(archived, applyRules(rules, null, archived, existing))

        val move = rule(tag("work"), targets = listOf("gone", work.id), move = true)
        assertEquals(listOf(work.id), applyRules(listOf(move), null, meta(tags = listOf("work"), collections = listOf(ideas.id)), existing).collections)
    }

    /** Writing a note runs the rules on a change of its tags and records it; Undo gives the collections back; Apply files the notes that match. */
    @Test
    fun organizer() {
        val earlier = newNote().let { it.copy(meta = it.meta.copy(tags = listOf("work"))) }
        library.writeNote(earlier)
        val rule = rule(tag("work"), targets = listOf(work.id))
        Organizer(library, index).updateRules { listOf(rule) }

        val fresh = newNote()
        val organizer = Organizer(library, index)
        val written = organizer.writeNote(fresh, null)
        assertEquals(fresh, written)
        organizer.changeTags(listOf(fresh.meta.id), listOf("Work/plans"))
        assertEquals(listOf(work.id), library.readNote(fresh.meta.id).meta.collections)
        assertEquals(1, organizer.filed.size)

        Organizer(library, index).undoRules(organizer.filed)
        assertEquals(emptyList<String>(), library.readNote(fresh.meta.id).meta.collections)

        // A save that keeps the tags does not file the note again.
        val editor = Organizer(library, index)
        val saved = library.readNote(fresh.meta.id)
        editor.writeNote(Note(saved.meta.copy(title = "Plan"), saved.body), saved.meta)
        assertTrue(editor.filed.isEmpty())

        // The notes saved before the rule existed, or taken out by hand, match it too.
        index.scan(library)
        val apply = Organizer(library, index)
        val ids = apply.notesForRule(rule)
        assertEquals(setOf(earlier.meta.id, fresh.meta.id), ids.toSet())
        assertEquals(2, apply.applyRule(rule, ids).size)
        assertEquals(listOf(work.id), library.readNote(earlier.meta.id).meta.collections)
    }
}
