package dev.kestrel.conversation.model

import dev.kestrel.agent.goal.SteerChannel

/** How an input came in, and so how Kestrel answers it (design 3 and 11 of sub-project 7). */
enum class InputChannel { VOICE, TEXT }

/** The agent's name for an input's channel: a spoken message or a typed one. */
fun InputChannel.toSteer(): SteerChannel = if (this == InputChannel.VOICE) SteerChannel.SPOKEN else SteerChannel.TYPED

/** The input's channel for the agent's name of it. */
fun SteerChannel.toInput(): InputChannel = if (this == SteerChannel.SPOKEN) InputChannel.VOICE else InputChannel.TEXT

/**
 * One input of the owner's (design 5.3 of sub-project 7). [id] is unique per process; [atMillis] is when it came, on the
 * wall clock; [options] are a debug hook's extras (`live`, `model`), which a release build never sets. Both channels read
 * the owner's words before they reach the conversation, so the conversation never parses speech. toString prints no words.
 */
data class OwnerInput(
    val id: Long,
    val channel: InputChannel,
    val intent: InputIntent,
    val atMillis: Long,
    val options: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "OwnerInput(id=$id, channel=$channel, intent=$intent, options=${options.keys.sorted()})"
}

/** What an input asks for (design 5.3 and 6.4 of sub-project 7). toString prints no words. */
sealed interface InputIntent {
    /** A new command, a follow-up or steering: the owner's words. */
    data class Command(val text: String) : InputIntent {
        override fun toString(): String = "Command(${text.length} chars)"
    }

    /**
     * The answer to the open question as the channel read it; [ChannelAnswer.Silence] when the voice's wait ended. The
     * voice's answers and a button on the conversation screen's copy of a card (plan 7F) name their [question], and answer
     * no other, so that an answer that comes after its question ended never answers a newer one; the typed words' answers
     * name none and answer the open question.
     */
    data class Answer(val answer: ChannelAnswer, val question: Int? = null) : InputIntent

    /** "Stop", "cancel", the screen's Stop and Cancel buttons and the notification's Stop. */
    data object Stop : InputIntent

    /** "Kestrel, continue" and the Continue buttons. */
    data object Continue : InputIntent

    data object NewConversation : InputIntent

    /** "Continue the conversation about X" (D23); [topic] is null for the newest closed conversation. */
    data class ContinuePast(val topic: String?) : InputIntent {
        override fun toString(): String = "ContinuePast(topic=${topic?.length} chars)"
    }

    /** The list's "Continue this conversation" (D23). */
    data class ContinueConversation(val id: ConversationId) : InputIntent

    data object DeleteHistory : InputIntent

    data class Remember(val key: String, val value: String) : InputIntent {
        override fun toString(): String = "Remember(key=${key.length} chars, value=${value.length} chars)"
    }

    data class Forget(val key: String) : InputIntent {
        override fun toString(): String = "Forget(key=${key.length} chars)"
    }

    data class SaveRoutine(val name: String) : InputIntent {
        override fun toString(): String = "SaveRoutine(name=${name.length} chars)"
    }
}
