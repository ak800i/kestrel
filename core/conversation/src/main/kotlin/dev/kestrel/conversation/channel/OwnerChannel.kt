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
     * Takes an output's place at the moment the conversation emits it, also while earlier outputs still wait to be shown;
     * what the place shows is decided at its turn, from the mode of that moment (plan 7F). A channel that judges words by
     * when they came, as the voice does against the stops of Kestrel's speech (plan 7B's decision 10; plan 7F), takes its
     * marks here; [show] is the same as taking a place and showing at once.
     */
    fun take(): OutputSlot = OutputSlot { output -> show(output) }

    /**
     * Asks [question] and returns the answer that the channel itself got, as a card's button, which acts over the app and
     * so leaves no screen of Kestrel's, with the number of the owner's tap; an answer that the owner says or types, also
     * after "Reply…", reaches the conversation as an input instead. Cancelling the call withdraws the question.
     */
    suspend fun ask(question: ChannelQuestion): CardAnswer

    /**
     * A hand-over: returns when the owner continued or cancelled on the channel, with the number of his tap on its card's
     * button, or when it ended by itself. As the hand-over's own channel ([HandOverPart.OWN]) the channel opens what the
     * hand-over opens and watches for its end by itself; as the copy of its card beside the owner's channel
     * ([HandOverPart.CARD]) it shows only the card, so that one hand-over opens its page once (plan 7F).
     */
    suspend fun handOver(handOver: HandOver, part: HandOverPart = HandOverPart.OWN): HandOverAnswer

    /** Shows Android's permission dialog for [permissions] after saying [why]. */
    suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd

    /** Returns true once no window of Kestrel's own app but its overlays is on the screen, or false after 3 s (design 10.2). */
    suspend fun leaveKestrel(): Boolean
}

/** An output's place on a channel, taken as the output was emitted ([OwnerChannel.take]): [show] says or shows it. */
fun interface OutputSlot {
    suspend fun show(output: ChannelOutput)
}

/** An output that the conversation emitted, with its channels' places ([ConversationOwner.take]): [show] shows it. */
fun interface TakenOutput {
    suspend fun show()
}

/**
 * A card's answer as the owner tapped it over the app (design 10.3 of sub-project 7): [answer], and [act], the number among
 * his inputs that `:app` took when his tap came back from the card (its `InputIds`), so that a round that the answer lets
 * act names the moment of his tap, not the moment when the engine handled the answer (design 6.5; plan 7F). Only an answer
 * that lets a round act, as a yes to "Continue the goal?", uses the number; for an answer to a goal's own question it is
 * dropped, as a spoken or typed answer's is (plan 7D's `RunningGoal.actedFor`).
 */
data class CardAnswer(val answer: ChannelAnswer, val act: Long)

/**
 * The owner's answer to a question of the conversation's own, and [tap], the number of his tap on its card when a card gave
 * it; null when the answer came as an input, which has its own number (plan 7F).
 */
data class OwnerReply(val answer: ChannelAnswer, val tap: Long?)

/**
 * How a hand-over ended on a channel (design 10.3 of sub-project 7): [end], and [act], the number among the owner's inputs
 * of his tap on the card's Continue or Cancel, which `:app` took as the tap reached Kestrel; null when the hand-over ended
 * by itself. A Continue on the card is the owner's act of its moment, as a spoken "Kestrel, continue" is (plan 7F).
 */
data class HandOverAnswer(val end: HandOverEnd, val act: Long?)

/**
 * How a channel takes part in a hand-over (plan 7F): as its own channel, the owner's latest, or as the copy of its card that
 * a spoken conversation gets on the text channel once Kestrel is off (design 10.3).
 */
enum class HandOverPart { OWN, CARD }

/** What a channel says or shows (design 11 of sub-project 7). toString prints no words. */
sealed interface ChannelOutput {
    /**
     * Kestrel's words: spoken by the voice channel, and shown on the bar and in the transcript. [typedCopy]: they are the
     * spoken copy of a typed conversation's words, with "Speak replies to typed commands" on; the voice speaks them with
     * Kestrel's own voice while Kestrel is off and with the voice session while it is on, as Kestrel is when their turn
     * comes (design 11 of sub-project 7; plan 7F). [question]: they are the spoken copy of the conversation's question with
     * this number, whose end also ends them (plan 7F).
     */
    data class Said(val text: String, val typedCopy: Boolean = false, val question: Int? = null) : ChannelOutput {
        override fun toString(): String = "Said(${text.length} chars, typedCopy=$typedCopy, question=$question)"
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
 * card shows as today; a pick's numbers run from 1 to [highest], which the card's keypad keeps to (0: no range; plan 7F).
 * toString prints no words.
 */
data class ChannelQuestion(
    val number: Int,
    val text: String,
    val kind: QuestionKind,
    val spoken: String = text,
    val choices: List<String> = emptyList(),
    val confirmation: Confirmation? = null,
    val highest: Int = 0,
) {
    override fun toString(): String =
        "ChannelQuestion(number=$number, kind=$kind, text=${text.length} chars, spoken=${spoken.length} chars, choices=${choices.size}, " +
            "confirmation=$confirmation, highest=$highest)"
}
