package dev.kestrel.agent.goal

import dev.kestrel.agent.SpokenList

/**
 * One item of an `update_checklist` call as `ToolArguments` read it, with its texts in their spoken form (design 7.2 of
 * sub-project 7). toString prints the id and lengths only.
 */
data class ChecklistDraft(val id: String?, val text: String, val state: ItemState, val step: Int? = null, val why: String? = null) {
    override fun toString(): String = "ChecklistDraft(id=$id, text=${text.length} chars, state=$state, step=$step, why=${why?.length})"
}

/**
 * The run's checklist as the rules keep it between calls (design 7.2 of sub-project 7): the [checklist], the number of
 * the next new id, `c<nextId>`, and the step of this run at which each item that the run added came in ([added]), before
 * which no step can have done that item. Step numbers start again in each run, so an item of the checklist that the run
 * started with has no entry: it came in before every step of the run. toString prints only counts.
 */
data class ChecklistState(val checklist: Checklist, val nextId: Int, val added: Map<String, Int>) {
    init {
        // A new item whose id the checklist already held would replace that item.
        require(checklist.items.all { ChecklistItem.number(it.id) < nextId }) { "nextId must come after every id of the checklist" }
    }

    override fun toString(): String = "ChecklistState(checklist=$checklist, nextId=$nextId, added=${added.size})"

    companion object {
        /** The state at a run's start, with the [checklist] that a resumed goal brings; its items came in before the run. */
        fun atStart(checklist: Checklist): ChecklistState =
            ChecklistState(checklist, (checklist.items.maxOfOrNull { ChecklistItem.number(it.id) } ?: 0) + 1, emptyMap())
    }
}

/** What [ChecklistRules.check] decided: the run's checklist to keep, or the call's fault. */
sealed interface ChecklistCheck {
    /** [sent] is the id of each item of the call, in the call's order, which the call's result names. */
    data class Saved(val state: ChecklistState, val sent: List<String>) : ChecklistCheck

    /** [reason] is one of Kestrel's fixed sentences with item ids and step numbers, never the model's text. */
    data class Refused(val reason: String) : ChecklistCheck
}

/**
 * The rules of an `update_checklist` call (design 7.2 of sub-project 7), checked by code. A call holds 1 to [MAX_ITEMS]
 * items, at most one `doing`, and every item of the current checklist that is still open (`todo` or `doing`), so that no
 * open item leaves the list without being marked `skipped`. An item that is closed (`done`, `skipped` or `failed`) may be
 * left out and stays as it is, or be sent again to change it. So [MAX_ITEMS] bounds the open items, and a long goal's
 * checklist grows past it.
 *
 * An item with an id keeps it and its place, and the id must be one of the checklist's, named once; a new item gets the
 * next id and goes at the end, in the call's order. A `done` item needs the step that did it, one of [steps]: the steps of
 * this run that acted on the screen without failing, its data steps with a result, and the steps that the owner did after
 * a hand-over; never a step that only talked, waited or looked closer. That step may not come before the step at which the
 * item came into the checklist, which is the call's own step for a new item, so no work from before an item was added
 * counts for it. An item that is done already may stay done without a step, and keeps its step; the run's checklist starts
 * as the one that a resumed goal brings, so its done items stay done. A done item's text never changes, and a `skipped` or
 * `failed` item needs `why`; only those keep one.
 */
object ChecklistRules {
    const val MAX_ITEMS = 20
    const val TEXT_LIMIT = 120

    /** Checks [drafts] against the run's [state], null before its first checklist; [step] is the number of the call's step. */
    fun check(drafts: List<ChecklistDraft>, state: ChecklistState?, steps: Set<Int>, step: Int): ChecklistCheck {
        if (drafts.isEmpty()) return ChecklistCheck.Refused("A call holds 1 to $MAX_ITEMS items.")
        // Only the open items must be sent, so a long goal's call stays within the bound by leaving out the closed ones.
        if (drafts.size > MAX_ITEMS) return ChecklistCheck.Refused("A call holds 1 to $MAX_ITEMS items; leave out items that are done, skipped or failed.")
        val named = drafts.mapNotNull { it.id }
        named.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let { return ChecklistCheck.Refused("The call names ${it.key} twice.") }
        val known = state?.checklist?.items.orEmpty().associateBy { it.id }
        named.firstOrNull { it !in known }?.let { return ChecklistCheck.Refused("$it is not in the checklist; leave id out for a new item.") }
        val missing = state?.checklist?.open.orEmpty().map { it.id }.filter { it !in named }
        if (missing.isNotEmpty()) {
            val still = if (missing.size == 1) "is" else "are"
            return ChecklistCheck.Refused(
                "The call leaves out ${listed(missing)}, which $still still open; send every open item, and mark one that is no longer needed skipped with why.",
            )
        }
        if (drafts.count { it.state == ItemState.DOING } > 1) return ChecklistCheck.Refused("At most one item may be doing.")
        var next = state?.nextId ?: 1
        val sent = drafts.map { draft ->
            val before = draft.id?.let(known::get)
            val id = draft.id ?: ChecklistItem.id(next++)
            if (before?.state == ItemState.DONE && draft.text != before.text) return ChecklistCheck.Refused("$id is done, so its text cannot change.")
            when (draft.state) {
                ItemState.DONE -> {
                    val doneBy = draft.step
                    if (doneBy != null && doneBy !in steps) {
                        return ChecklistCheck.Refused(
                            "Step $doneBy cannot mark $id done: name a step that acted on the screen without failing, a data step with a result or a step that the owner did.",
                        )
                    }
                    val since = if (before == null) step else state?.added?.get(id)
                    if (doneBy != null && since != null && doneBy < since) {
                        return ChecklistCheck.Refused("Step $doneBy cannot mark $id done: $id was added at step $since, after it.")
                    }
                    if (doneBy == null && before?.state != ItemState.DONE) return ChecklistCheck.Refused("$id is done, so it needs the step that did it.")
                    ChecklistItem(id, draft.text, ItemState.DONE, step = doneBy ?: before?.step)
                }
                ItemState.SKIPPED, ItemState.FAILED -> {
                    val why = draft.why ?: return ChecklistCheck.Refused("$id is ${draft.state.wire}, so it needs why.")
                    ChecklistItem(id, draft.text, draft.state, why = why)
                }
                ItemState.TODO, ItemState.DOING -> ChecklistItem(id, draft.text, draft.state)
            }
        }
        val changed = sent.associateBy { it.id }
        val fresh = sent.filter { it.id !in known }
        val items = state?.checklist?.items.orEmpty().map { changed[it.id] ?: it } + fresh
        val added = state?.added.orEmpty() + fresh.associate { it.id to step }
        return ChecklistCheck.Saved(ChecklistState(Checklist(items), next, added), sent.map { it.id })
    }

    /** Item ids as a sentence names them: "c4", "c4 and c5", "c4, c5 and c6". */
    fun listed(ids: List<String>): String = SpokenList.of(ids)
}