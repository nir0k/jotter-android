package io.github.nir0k.jotter.feature.note

import io.github.nir0k.jotter.editor.EditorSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The note screen's saved state reads back what it wrote, and something else as nothing. */
class NoteScreenStateTest {
    /** Every field survives a save and a restore. */
    @Test
    fun savedStateReadsBack() {
        val state = NoteScreenState(
            find = true,
            findQuery = "milk",
            info = true,
            dialog = NoteDialog.Tags,
            cameraPath = "cache/camera/photo.jpg",
            savingFile = "attachments/a.pdf",
            selection = EditorSelection(3, 7),
            bodyFocused = true,
            scrolled = 120,
        )
        val restored = NoteScreenState.restore(state.save())!!
        assertEquals(true, restored.find)
        assertEquals("milk", restored.findQuery)
        assertEquals(true, restored.info)
        assertEquals(NoteDialog.Tags, restored.dialog)
        assertEquals("cache/camera/photo.jpg", restored.cameraPath)
        assertEquals("attachments/a.pdf", restored.savingFile)
        assertEquals(EditorSelection(3, 7), restored.selection)
        assertEquals(true, restored.bodyFocused)
        assertEquals(120, restored.scroll.value)
    }

    /** The defaults, nulls included, survive too. */
    @Test
    fun emptyStateReadsBack() {
        val restored = NoteScreenState.restore(NoteScreenState().save())!!
        assertFalse(restored.find)
        assertNull(restored.dialog)
        assertNull(restored.selection)
        assertFalse(restored.bodyFocused)
        assertEquals(0, restored.scroll.value)
    }

    /** Values of another form, as an older version might have saved, start the screen afresh. */
    @Test
    fun otherValuesAreNothing() {
        assertNull(NoteScreenState.restore(null))
        assertNull(NoteScreenState.restore(listOf(true, "x")))
        assertNull(NoteScreenState.restore(listOf("a", 1, 2, 3, 4, 5, 6, 7, 8)))
    }
}
