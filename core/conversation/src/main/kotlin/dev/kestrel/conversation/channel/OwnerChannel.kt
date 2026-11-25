package dev.kestrel.conversation.channel

import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.HandOver

/**
 * One way to the owner (design 5.3 and 11 of sub-project 7): `:app`'s `VoiceChannel` (plan 7D) speaks and listens through
 * the Orchestrator, its `TextChannel` (plan 7F) writes into the transcript and the bar and shows the question cards.
 */
interface OwnerChannel {
    val kind: InputChannel

    /** Says or shows [output]; returns when it has been spoken or shown, or refused. */
    suspend fun show(output: ChannelOutput)

    /**
     * Asks [question] and returns the answer that the channel itself got, as a card's button; an answer that the owner
     * says or types reaches the conversation as an input instead. Cancelling the call withdraws the question.
     */
    suspend fun ask(question: ChannelQuestion): ChannelAnswer

    /** A hand-over: returns when the owner continued or cancelled on the channel, or when it ended by itself. */
    suspend fun handOver(handOver: HandOver): HandOverEnd

    /** Shows Android's permission dialog for [permissions] after saying [why]. */
    suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd

    /** Returns true once no window of Kestrel's own app but its overlays is on the screen, or false after 3 s (design 10.2). */
    suspend fun leaveKestrel(): Boolean
}

/** What a channel says or shows (design 11 of sub-project 7). toString prints no words. */
sealed interface ChannelOutput {
    /** Kestrel's words: spoken by the voice channel, and shown on the bar and in the transcript. */
    data class Said(val text: String) : ChannelOutput {
        override fun toString(): String = "Said(${text.length} chars)"
    }

    /** Shown on the bar and in the transcript, never spoken: "Got it." for steering, a replaced goal's report. */
    data class Shown(val text: String) : ChannelOutput {
        override fun toString(): String = "Shown(${text.length} chars)"
    }

    /** The final reply of [command]; a spoken one opens the follow-up window (design 6.6). */
    data class Report(val command: Int, val text: String) : ChannelOutput {
        override fun toString(): String = "Report(command=$command, ${text.length} chars)"
    }
}

/**
 * A question's kind, which chooses the card's buttons, the waits and the spoken answers that count (design 10.3 and 10.4
 * of sub-project 7). [DELETE_HISTORY] is "Delete the activity log, the network log and past conversations?", which only
 * "yes" answers (plan 7D).
 */
enum class QuestionKind { YES_NO, CONFIRM, FREE, PICK, SCREENSHOT, CONTINUE_GOAL, LIMIT, SWITCH, DELETE_HISTORY }

/**
 * A question to the owner: [number] names it, as "Reply…" does (design 12.2); [text] is the card's and the transcript's
 * form and [spoken] the voice's; [choices] are a pick's at most 4 choices; [confirmation] is a confirmation's, which the
 * card shows as today. toString prints no words.
 */
data class ChannelQuestion(
    val number: Int,
    val text: String,
    val kind: QuestionKind,
    val spoken: String = text,
    val choices: List<String> = emptyList(),
    val confirmation: Confirmation? = null,
) {
    override fun toString(): String =
        "ChannelQuestion(number=$number, kind=$kind, text=${text.length} chars, spoken=${spoken.length} chars, choices=${choices.size}, confirmation=$confirmation)"
}
