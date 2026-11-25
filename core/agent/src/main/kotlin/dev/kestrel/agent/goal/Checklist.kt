package dev.kestrel.agent.goal

import kotlinx.serialization.Serializable

/**
 * A goal's checklist (design 7.2 of sub-project 7): the steps to reach the owner's goal, each with its state, as
 * `ChecklistRules` accepted them. Its texts came from the model, so they never count as the owner's words. Each id is in it
 * once, which a stored checklist is checked for as it is read. toString prints only counts.
 */
@Serializable
data class Checklist(val items: List<ChecklistItem>) {
    init {
        require(items.distinctBy { it.id }.size == items.size) { "A checklist holds each id once" }
    }

    /** How many items are done. */
    val done: Int get() = items.count { it.state == ItemState.DONE }

    /** The items that are still to do or being done, which `finish` may not leave behind. */
    val open: List<ChecklistItem> get() = items.filter { it.state == ItemState.TODO || it.state == ItemState.DOING }

    override fun toString(): String = "Checklist(items=${items.size}, done=$done)"
}

/**
 * One item: its [id] (`c1`, `c2`, … which Kestrel gives), its [text] in its spoken form, its [state], the [step] that did
 * a done item, and [why] a skipped or failed one was given up. An id of any other form is refused when the item is made,
 * also when a stored checklist is read, so that no run starts with one. toString prints the id and lengths only.
 */
@Serializable
data class ChecklistItem(val id: String, val text: String, val state: ItemState, val step: Int? = null, val why: String? = null) {
    init {
        require(isId(id)) { "A checklist item's id is c and a positive number" }
    }

    override fun toString(): String = "ChecklistItem(id=$id, text=${text.length} chars, state=$state, step=$step, why=${why?.length})"

    companion object {
        /**
         * The largest number of an id. A goal's checklist has no other limit (design 7.2 of sub-project 7), but every id
         * must leave room for the next one, which `ChecklistState.nextId` holds as an Int.
         */
        const val MAX_NUMBER = Int.MAX_VALUE - 1

        // c and a positive number without leading zeros.
        private val ID = Regex("c[1-9][0-9]*")

        /** Whether [id] has the form of Kestrel's ids, with a number of at most [MAX_NUMBER]. */
        fun isId(id: String): Boolean = ID.matches(id) && id.substring(1).toIntOrNull()?.let { it <= MAX_NUMBER } == true

        /** The id with [number]. */
        fun id(number: Int): String = "c$number"

        /** The number of [id], which [isId] accepts. */
        fun number(id: String): Int = id.substring(1).toInt()
    }
}

/** An item's state; [wire] is its name in `update_checklist`. */
@Serializable
enum class ItemState(val wire: String) { TODO("todo"), DOING("doing"), DONE("done"), SKIPPED("skipped"), FAILED("failed") }
