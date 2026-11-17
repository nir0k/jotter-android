package io.github.nir0k.jotter.feature.notes.trees

import io.github.nir0k.jotter.core.format.Collection
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks how the collection and tag trees are built from the library's lists. */
class TreesTest {
    /** Parent parts become nodes; parts compare case-folded and keep the spelling of their first use. */
    @Test
    fun tagTreeJoinsSpellings() {
        val tree = tagTree(listOf("work/planning", "Work/Urgent", "home"), mapOf("work" to 2, "work/urgent" to 1))
        assertEquals(listOf("work", "home"), tree.map { it.path })
        assertEquals(listOf("work/planning", "Work/Urgent"), tree[0].children.map { it.path })
        assertEquals(listOf(2, 0), tree.map { it.count })
        assertEquals("Urgent", tree[0].children[1].name)
    }

    /** Collections keep their order under each parent; one in a cycle of a damaged file is left out. */
    @Test
    fun collectionTreeKeepsOrder() {
        val time = Instant.EPOCH
        fun collection(id: String, parent: String?) = Collection(id, parent, id, created = time, updated = time)
        val tree = collectionTree(
            listOf(collection("b", null), collection("a", null), collection("a1", "a"), collection("x", "y"), collection("y", "x")),
            mapOf("a" to 3),
        )
        assertEquals(listOf("b", "a"), tree.map { it.collection.id })
        assertEquals(listOf("a1"), tree[1].children.map { it.collection.id })
        assertEquals(3, tree[1].count)
    }
}
