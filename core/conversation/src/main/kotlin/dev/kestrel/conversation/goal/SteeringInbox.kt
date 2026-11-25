package dev.kestrel.conversation.goal

import dev.kestrel.agent.goal.Steer

/**
 * The owner's steering of a goal (design 7.3 of sub-project 7), oldest first: the conversation adds a message while the
 * goal acts, and the run drains the inbox at the start of each turn, on its own coroutine, without blocking. [changed]
 * runs after each change, so that the conversation saves what still waits.
 */
class SteeringInbox(initial: List<Steer> = emptyList(), private val changed: () -> Unit = {}) {
    private val messages = ArrayDeque(initial)

    fun add(steer: Steer) {
        synchronized(messages) { messages.addLast(steer) }
        changed()
    }

    /** Takes every message that waits. */
    fun drain(): List<Steer> {
        val taken = synchronized(messages) { messages.toList().also { messages.clear() } }
        if (taken.isNotEmpty()) changed()
        return taken
    }

    /** The messages that wait, without taking them. */
    fun pending(): List<Steer> = synchronized(messages) { messages.toList() }

    override fun toString(): String = "SteeringInbox(${pending().size})"
}
