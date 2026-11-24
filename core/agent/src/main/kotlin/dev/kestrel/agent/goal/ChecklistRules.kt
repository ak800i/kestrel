package dev.kestrel.agent.goal

/**
 * One item of an `update_checklist` call as `ToolArguments` read it, with its texts in their spoken form (design 7.2 of
 * sub-project 7). toString prints the id and lengths only.
 */
data class ChecklistDraft(val id: String?, val text: String, val state: ItemState, val step: Int? = null, val why: String? = null) {
    override fun toString(): String = "ChecklistDraft(id=$id, text=${text.length} chars, state=$state, step=$step, why=${why?.length})"
}

/** What [ChecklistRules.check] decided: the checklist to keep, with the number of the next new id, or the call's fault. */
sealed interface ChecklistCheck {
    /** [sent] is the id of each item of the call, in the call's order, which the call's result names. */
    data class Saved(val checklist: Checklist, val nextId: Int, val sent: List<String>) : ChecklistCheck

    /** [reason] is one of Kestrel's fixed sentences with item ids and step numbers, never the model's text. */
    data class Refused(val reason: String) : ChecklistCheck
}

/**
 * The rules of an `update_checklist` call (design 7.2 of sub-project 7), checked by code. A call holds 1 to [MAX_ITEMS]
 * items, at most one `doing`, and every item of the [current] checklist that is still open (`todo` or `doing`), so that no
 * open item leaves the list without being marked `skipped`. An item that is closed (`done`, `skipped` or `failed`) may be
 * left out and stays as it is, or be sent again to change it. So [MAX_ITEMS] bounds the open items, and a long goal's
 * checklist grows past it.
 *
 * An item with an id keeps it and its place, and the id must be one of [current]'s, named once; a new item gets the next
 * id, `c<nextId>`, and goes at the end, in the call's order. A `done` item needs the step that did it, one of [steps]:
 * the steps of this run that acted on the screen without failing, its data steps with a result, and the steps that the
 * owner did after a hand-over; never a step that only talked, waited or looked closer. An item that is done in [current]
 * may stay done without a step, and keeps its step; the run's checklist starts as the one that a resumed goal brings, so
 * its done items stay done. A done item's text never changes, and a `skipped` or `failed` item needs `why`; only those
 * keep one.
 */
object ChecklistRules {
    const val MAX_ITEMS = 20
    const val TEXT_LIMIT = 120

    fun check(drafts: List<ChecklistDraft>, current: Checklist?, steps: Set<Int>, nextId: Int): ChecklistCheck {
        if (drafts.isEmpty()) return ChecklistCheck.Refused("A call holds 1 to $MAX_ITEMS items.")
        // Only the open items must be sent, so a long goal's call stays within the bound by leaving out the closed ones.
        if (drafts.size > MAX_ITEMS) return ChecklistCheck.Refused("A call holds 1 to $MAX_ITEMS items; leave out items that are done, skipped or failed.")
        val named = drafts.mapNotNull { it.id }
        named.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let { return ChecklistCheck.Refused("The call names ${it.key} twice.") }
        val known = current?.items.orEmpty().associateBy { it.id }
        named.firstOrNull { it !in known }?.let { return ChecklistCheck.Refused("$it is not in the checklist; leave id out for a new item.") }
        val missing = current?.open.orEmpty().map { it.id }.filter { it !in named }
        if (missing.isNotEmpty()) {
            val still = if (missing.size == 1) "is" else "are"
            return ChecklistCheck.Refused(
                "The call leaves out ${listed(missing)}, which $still still open; send every open item, and mark one that is no longer needed skipped with why.",
            )
        }
        if (drafts.count { it.state == ItemState.DOING } > 1) return ChecklistCheck.Refused("At most one item may be doing.")
        var next = nextId
        val sent = drafts.map { draft ->
            val before = draft.id?.let(known::get)
            val id = draft.id ?: "c${next++}"
            if (before?.state == ItemState.DONE && draft.text != before.text) return ChecklistCheck.Refused("$id is done, so its text cannot change.")
            when (draft.state) {
                ItemState.DONE -> {
                    val step = draft.step
                    if (step != null && step !in steps) {
                        return ChecklistCheck.Refused(
                            "Step $step cannot mark $id done: name a step that acted on the screen without failing, a data step with a result or a step that the owner did.",
                        )
                    }
                    if (step == null && before?.state != ItemState.DONE) return ChecklistCheck.Refused("$id is done, so it needs the step that did it.")
                    ChecklistItem(id, draft.text, ItemState.DONE, step = step ?: before?.step)
                }
                ItemState.SKIPPED, ItemState.FAILED -> {
                    val why = draft.why ?: return ChecklistCheck.Refused("$id is ${draft.state.wire}, so it needs why.")
                    ChecklistItem(id, draft.text, draft.state, why = why)
                }
                ItemState.TODO, ItemState.DOING -> ChecklistItem(id, draft.text, draft.state)
            }
        }
        val changed = sent.associateBy { it.id }
        val items = current?.items.orEmpty().map { changed[it.id] ?: it } + sent.filter { it.id !in known }
        return ChecklistCheck.Saved(Checklist(items), next, sent.map { it.id })
    }

    /** Item ids as a sentence names them: "c4", "c4 and c5", "c4, c5 and c6". */
    fun listed(ids: List<String>): String = if (ids.size == 1) ids.single() else ids.dropLast(1).joinToString(", ") + " and " + ids.last()
}
