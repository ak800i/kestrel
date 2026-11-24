package dev.kestrel.agent.goal

import kotlinx.serialization.Serializable

/**
 * A goal's checklist (design 7.2 of sub-project 7): the steps to reach the owner's goal, each with its state, as
 * `ChecklistRules` accepted them. Its texts came from the model, so they never count as the owner's words. toString prints
 * only counts.
 */
@Serializable
data class Checklist(val items: List<ChecklistItem>) {
    /** How many items are done. */
    val done: Int get() = items.count { it.state == ItemState.DONE }

    /** The items that are still to do or being done, which `finish` may not leave behind. */
    val open: List<ChecklistItem> get() = items.filter { it.state == ItemState.TODO || it.state == ItemState.DOING }

    override fun toString(): String = "Checklist(items=${items.size}, done=$done)"
}

/**
 * One item: its [id] (`c1`, `c2`, … which Kestrel gives), its [text] in its spoken form, its [state], the [step] that did
 * a done item, and [why] a skipped or failed one was given up. toString prints the id and lengths only.
 */
@Serializable
data class ChecklistItem(val id: String, val text: String, val state: ItemState, val step: Int? = null, val why: String? = null) {
    override fun toString(): String = "ChecklistItem(id=$id, text=${text.length} chars, state=$state, step=$step, why=${why?.length})"
}

/** An item's state; [wire] is its name in `update_checklist`. */
@Serializable
enum class ItemState(val wire: String) { TODO("todo"), DOING("doing"), DONE("done"), SKIPPED("skipped"), FAILED("failed") }
