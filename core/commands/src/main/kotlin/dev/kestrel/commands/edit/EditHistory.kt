package dev.kestrel.commands.edit

/**
 * Kestrel's own history of its edits, for "undo" and "redo" (design spec, section 9.7): for each field, the state before
 * and after each edit, one entry per command. It keeps at most [maxEdits] edits for each of the last [maxFields] fields.
 * "Undo" puts back the state before the last edit, and "redo" the state after it, but only while the field still shows
 * the text that Kestrel left; otherwise the field's history is dropped, because the app or the owner changed the text.
 * Edits of password fields are never recorded.
 */
class EditHistory(private val maxEdits: Int = 50, private val maxFields: Int = 20) {
    private class Field {
        val edits = ArrayList<Edit>()

        /** How many of [edits] are applied; the ones after it can be redone. */
        var applied = 0
    }

    private class Edit(val before: TextState, val after: TextState)

    // In order of use, so that the field used longest ago goes first.
    private val fields = LinkedHashMap<String, Field>(16, 0.75f, true)

    /** Records an edit of [field] (its app and key) from [before] to [after]. It drops the edits that "redo" could restore. */
    @Synchronized
    fun record(field: String, before: TextState, after: TextState) {
        val edits = fields.getOrPut(field) { Field() }
        while (edits.edits.size > edits.applied) edits.edits.removeAt(edits.edits.lastIndex)
        edits.edits += Edit(before, after)
        edits.applied++
        if (edits.edits.size > maxEdits) {
            edits.edits.removeAt(0)
            edits.applied--
        }
        while (fields.size > maxFields) fields.remove(fields.keys.first())
    }

    @Synchronized
    fun canUndo(field: String): Boolean = (fields[field]?.applied ?: 0) > 0

    @Synchronized
    fun canRedo(field: String): Boolean = fields[field]?.let { it.applied < it.edits.size } ?: false

    /**
     * The state before the last edit of [field], or null when there is none or when [current] does not show the text
     * after that edit; then the field's history is dropped.
     */
    @Synchronized
    fun undo(field: String, current: TextState): TextState? {
        val edits = fields[field] ?: return null
        if (edits.applied == 0) return null
        val edit = edits.edits[edits.applied - 1]
        if (current.text != edit.after.text) {
            fields.remove(field)
            return null
        }
        edits.applied--
        return edit.before
    }

    /** The state after the edit that "undo" took back last, in the same way as [undo]. */
    @Synchronized
    fun redo(field: String, current: TextState): TextState? {
        val edits = fields[field] ?: return null
        if (edits.applied == edits.edits.size) return null
        val edit = edits.edits[edits.applied]
        if (current.text != edit.before.text) {
            fields.remove(field)
            return null
        }
        edits.applied++
        return edit.after
    }

    override fun toString() = "EditHistory(${fields.size} fields)"
}
