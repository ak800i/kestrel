package dev.kestrel.commands.edit

/**
 * Kestrel's own history of its edits, for "undo" and "redo" (design spec, section 9.7): for each field, the state before
 * and after each edit, one entry per command. It keeps at most [maxEdits] edits for each of the last [maxFields] fields.
 * "Undo" puts back the state before the last edit, and "redo" the state after it, but only while the field still shows
 * the text that Kestrel left; otherwise the field's history is dropped, because the app or the owner changed the text.
 * A field's history also goes once its last edit is more than [MAX_AGE_MILLIS] old by [clock], because a key names a
 * position, so the empty message field of another conversation in the same app could otherwise take an old text back.
 * Edits of password fields are never recorded.
 */
class EditHistory(
    private val maxEdits: Int = 50,
    private val maxFields: Int = 20,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Field(var lastEdit: Long) {
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
        val edits = live(field) ?: Field(clock()).also { fields[field] = it }
        edits.lastEdit = clock()
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
    fun canUndo(field: String): Boolean = (live(field)?.applied ?: 0) > 0

    @Synchronized
    fun canRedo(field: String): Boolean = live(field)?.let { it.applied < it.edits.size } ?: false

    /**
     * The state before the last edit of [field], or null when there is none or when [current] does not show the text
     * after that edit; then the field's history is dropped.
     */
    @Synchronized
    fun undo(field: String, current: TextState): TextState? =
        peek(field, current, redo = false)?.also { step(field, redo = false) }

    /** The state after the edit that "undo" took back last, in the same way as [undo]. */
    @Synchronized
    fun redo(field: String, current: TextState): TextState? =
        peek(field, current, redo = true)?.also { step(field, redo = true) }

    /**
     * What [undo], or [redo] when [redo] is true, would put back, without moving the history, so that an edit that the
     * field did not take, or that was cancelled, changes nothing; [step] moves it once the field shows the state. Null
     * in the same cases as [undo] and [redo], which drop the field's history in the same way.
     */
    @Synchronized
    fun peek(field: String, current: TextState, redo: Boolean): TextState? {
        val edits = live(field) ?: return null
        if (if (redo) edits.applied == edits.edits.size else edits.applied == 0) return null
        val edit = edits.edits[if (redo) edits.applied else edits.applied - 1]
        if (current.text != (if (redo) edit.before else edit.after).text) {
            fields.remove(field)
            return null
        }
        return if (redo) edit.after else edit.before
    }

    /** Moves [field]'s history one edit back, or forward when [redo] is true, after the field took what [peek] gave. */
    @Synchronized
    fun step(field: String, redo: Boolean) {
        val edits = live(field) ?: return
        edits.lastEdit = clock()
        if (redo && edits.applied < edits.edits.size) edits.applied++
        if (!redo && edits.applied > 0) edits.applied--
    }

    /** The history of [field], or null when it has none or its last edit is too old; then it is dropped. */
    private fun live(field: String): Field? {
        val edits = fields[field] ?: return null
        if (clock() - edits.lastEdit <= MAX_AGE_MILLIS) return edits
        fields.remove(field)
        return null
    }

    override fun toString() = "EditHistory(${fields.size} fields)"

    companion object {
        /** How long a field's history lasts after Kestrel's last edit or undo in it. */
        const val MAX_AGE_MILLIS = 5 * 60_000L
    }
}
