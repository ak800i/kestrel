package dev.kestrel.conversation.channel

import dev.kestrel.conversation.model.ChannelAnswer

/**
 * The conversation's own dialogue with the owner, outside a goal's run (design 5.3 of sub-project 7): Kestrel's own
 * requests and "Continue the goal?" say things and ask questions of every kind, which a pause never withdraws.
 */
interface Dialogue {
    suspend fun say(text: String)

    /**
     * Asks [text] of [kind], spoken as [spoken], with a pick's [choices], which its card shows as buttons, and its [highest]
     * number, which its card's keypad keeps to (design 10.3; 0 for no range).
     */
    suspend fun question(text: String, kind: QuestionKind, spoken: String = text, choices: List<String> = emptyList(), highest: Int = 0): ChannelAnswer
}

/** What [ConversationOwner] tells the engine about its dialogue, so that the machine knows what waits for the owner. */
sealed interface DialogueEvent {
    data class Asked(val number: Int, val kind: QuestionKind) : DialogueEvent

    /**
     * Question [number] closed: with the owner's [answer], or with none (null) when it was withdrawn or cancelled. [tap] is
     * the number of the owner's tap among his inputs when a card gave the answer, and null when an input gave it, which
     * reached the engine as its own event, or when nothing did (plan 7G): every answer reaches the engine with its source.
     */
    data class Answered(val number: Int, val answer: ChannelAnswer? = null, val tap: Long? = null) : DialogueEvent

    data class HandedOver(val number: Int) : DialogueEvent

    data class HandOverDone(val number: Int) : DialogueEvent
}
