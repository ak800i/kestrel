package dev.kestrel.agent.goal

/**
 * How the owner hears a checklist's progress (design 7.4 of sub-project 7): "Done: 4 of 7 steps. Left: reply to Mila,
 * archive the thread." It names at most [MAX_LEFT] open items and counts the rest, because he hears it. [closed] counts
 * the closed items for the model.
 */
object ChecklistText {
    const val MAX_LEFT = 3

    fun progress(checklist: Checklist): String {
        val total = checklist.items.size
        val done = "Done: ${checklist.done} of $total ${if (total == 1) "step" else "steps"}."
        val open = checklist.open.map { it.text.trimEnd('.') }
        if (open.isEmpty()) return done
        val named = open.take(MAX_LEFT).joinToString(", ")
        val more = open.size - MAX_LEFT
        return "$done Left: ${if (more > 0) "$named and $more more" else named}."
    }

    /**
     * The closed items' counts, such as "12 done, 1 skipped, 0 failed", which a records block (design 8.3 of sub-project 7)
     * and a resumed goal's long checklist show instead of the closed items themselves.
     */
    fun closed(checklist: Checklist): String {
        fun count(state: ItemState) = checklist.items.count { it.state == state }
        return "${count(ItemState.DONE)} done, ${count(ItemState.SKIPPED)} skipped, ${count(ItemState.FAILED)} failed"
    }
}
