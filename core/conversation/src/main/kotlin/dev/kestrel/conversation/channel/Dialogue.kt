package dev.kestrel.conversation.channel

import dev.kestrel.conversation.model.ChannelAnswer

/**
 * The conversation's own dialogue with the owner, outside a goal's run (design 5.3 of sub-project 7): Kestrel's own
 * requests say things and ask their questions, which a pause never withdraws. "Continue the goal?" is not among them: only
 * `ConversationOwner.askContinue` asks it, with the number of the machine's ask (plan 7G).
 */
interface Dialogue {
    suspend fun say(text: String)

    /**
     * Asks [text] of [kind], spoken as [spoken], with a pick's [choices], which its card shows as buttons, and its [highest]
     * number, which its card's keypad keeps to (design 10.3; 0 for no range).
     */
    suspend fun question(text: String, kind: RequestQuestion, spoken: String = text, choices: List<String> = emptyList(), highest: Int = 0): ChannelAnswer
}

/**
 * The kinds of question that Kestrel's own requests ask through [Dialogue.question]: the switch to a past conversation and
 * "Delete the history?". It has no member for "Continue the goal?", so that the compiler keeps that question to
 * `ConversationOwner.askContinue`, the one way to ask it with its ask's number (plan 7G).
 */
enum class RequestQuestion(val kind: QuestionKind) {
    SWITCH(QuestionKind.SWITCH),
    DELETE_HISTORY(QuestionKind.DELETE_HISTORY),
}

/** What [ConversationOwner] tells the engine about its dialogue, so that the machine knows what waits for the owner. */
sealed interface DialogueEvent {
    /**
     * Question [number] of [kind] is asked; [ask] is the number of the machine's ask of "Continue the goal?" that the
     * question belongs to (`AskContinueGoal`, plan 7G), and null for every other question.
     */
    data class Asked(val number: Int, val kind: QuestionKind, val ask: Int? = null) : DialogueEvent

    /**
     * Question [number] closed: with the owner's [answer], or with none (null) when it was withdrawn, cancelled or failed;
     * for the open "Continue the goal?" of the ask that runs, none is silence (plan 7G). [tap] is
     * the number of the owner's tap among his inputs when a card gave the answer, and null when an input gave it, which
     * reached the engine as its own event, or when nothing did (plan 7G): every answer reaches the engine with its source.
     */
    data class Answered(val number: Int, val answer: ChannelAnswer? = null, val tap: Long? = null) : DialogueEvent

    data class HandedOver(val number: Int) : DialogueEvent

    /**
     * Hand-over [number] ended. [act] is the number of the owner's tap among his inputs when a card's Continue or Cancel
     * ended it, and null when an input ended it, which reached the engine as its own event, or when it ended by itself
     * (plan 7G): every act that ends a hand-over reaches the engine with its number, as a question's answer does.
     */
    data class HandOverDone(val number: Int, val act: Long? = null) : DialogueEvent
}
