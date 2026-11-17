package io.github.nir0k.jotter.core.body

/**
 * The editor's undo history: whole states before each change. Typing of one kind within half a
 * second joins the change before it, so undo takes back a typed word rather than a letter; every
 * other change is a step of its own.
 *
 * @param limit The most steps kept.
 */
class History(private val limit: Int = 100) {
    private val undone = ArrayDeque<EditorState>()
    private val redone = ArrayDeque<EditorState>()
    private var lastGroup: String? = null
    private var lastTime = 0L

    /** Whether there is a change to undo. */
    val canUndo: Boolean get() = undone.isNotEmpty()

    /** Whether there is a change to redo. */
    val canRedo: Boolean get() = redone.isNotEmpty()

    /**
     * Records the state before a change.
     *
     * @param group The kind of typing the change is, which joins changes of the same kind made
     *   quickly after each other; null for a step of its own.
     * @param now The time of the change in milliseconds.
     */
    fun record(before: EditorState, group: String?, now: Long) {
        redone.clear()
        if (group != null && group == lastGroup && now - lastTime < GroupDelayMs) {
            lastTime = now
            return
        }
        undone.addLast(before)
        while (undone.size > limit) undone.removeFirst()
        lastGroup = group
        lastTime = now
    }

    /** Returns the state before the last change, keeping the current one for redo; null when there is none. */
    fun undo(current: EditorState): EditorState? {
        val state = undone.removeLastOrNull() ?: return null
        redone.addLast(current)
        lastGroup = null
        return state
    }

    /** Returns the state the last undo left, keeping the current one for undo; null when there is none. */
    fun redo(current: EditorState): EditorState? {
        val state = redone.removeLastOrNull() ?: return null
        undone.addLast(current)
        lastGroup = null
        return state
    }

    /** Ends the current group of typing, so the next change is a step of its own. */
    fun breakGroup() {
        lastGroup = null
    }

    /** Forgets every step, as when another version of the note is loaded. */
    fun clear() {
        undone.clear()
        redone.clear()
        lastGroup = null
    }

    private companion object {
        /** How quickly typing joins the change before it. */
        const val GroupDelayMs = 500L
    }
}
