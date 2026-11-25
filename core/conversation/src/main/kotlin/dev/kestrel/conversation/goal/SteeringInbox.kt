package dev.kestrel.conversation.goal

import dev.kestrel.agent.goal.Steer

/**
 * The owner's steering of a goal (design 7.3 of sub-project 7), oldest first: the conversation adds a message while the
 * goal acts, and the run drains the inbox at the start of each turn, on its own coroutine, without blocking. Once the goal
 * has given its final words, which name what still waited, the inbox is [close]d and takes no later message, so that the
 * conversation can tell the owner at once that it came too late (review of plan 7B's Task 10: I3). [changed] runs after
 * each change, so that the conversation saves what still waits.
 */
class SteeringInbox(initial: List<Steer> = emptyList(), private val changed: () -> Unit = {}) {
    private val messages = ArrayDeque(initial)
    private var closed = false

    /** Adds [steer] for the goal's next turn; false, and nothing is added, once the inbox is closed. */
    fun add(steer: Steer): Boolean {
        val added = synchronized(messages) { if (closed) false else messages.add(steer) }
        if (added) changed()
        return added
    }

    /** Takes every message that waits. */
    fun drain(): List<Steer> {
        val taken = synchronized(messages) { messages.toList().also { messages.clear() } }
        if (taken.isNotEmpty()) changed()
        return taken
    }

    /** Takes every message that waits, for the goal's final words, and refuses every later one. */
    fun close(): List<Steer> {
        val taken = synchronized(messages) {
            closed = true
            messages.toList().also { messages.clear() }
        }
        if (taken.isNotEmpty()) changed()
        return taken
    }

    /** The messages that wait, without taking them. */
    fun pending(): List<Steer> = synchronized(messages) { messages.toList() }

    override fun toString(): String = "SteeringInbox(${pending().size})"
}
