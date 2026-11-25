package dev.kestrel.conversation.channel

import dev.kestrel.conversation.model.ChannelAnswer

/**
 * The conversation's own dialogue with the owner, outside a goal's run (design 5.3 of sub-project 7): Kestrel's own
 * requests and "Continue the goal?" say things and ask questions of every kind, which a pause never withdraws.
 */
interface Dialogue {
    suspend fun say(text: String)

    suspend fun question(text: String, kind: QuestionKind, spoken: String = text): ChannelAnswer
}

/** What [ConversationOwner] tells the engine about its dialogue, so that the machine knows what waits for the owner. */
sealed interface DialogueEvent {
    data class Asked(val number: Int, val kind: QuestionKind) : DialogueEvent

    data class Answered(val number: Int) : DialogueEvent

    data class HandedOver(val number: Int) : DialogueEvent

    data class HandOverDone(val number: Int) : DialogueEvent
}
