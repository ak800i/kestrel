package dev.kestrel.conversation

import dev.kestrel.conversation.context.RecordText
import dev.kestrel.conversation.context.SteeringMessage
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.InputChannel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the conversation says itself (design 6, 9.6, 9.7 and 10 of sub-project 7), in English, built by code and never by
 * the model. A round's own report sentence is `:app`'s (`GoalEnvironment.sentence`); a held goal's report ends with
 * [held]. Plan 7D moves the memory requests' and "delete history"'s sentences here from `AgentTexts` and `ListeningTexts`.
 */
object ConversationTexts {
    const val GOT_IT = "Got it."
    const val STOP_GOAL_FIRST = "Stop the goal first."
    const val NOTHING_TO_CONTINUE = "There's nothing to continue."
    const val ANSWER_CONTINUE_FIRST = "Answer 'Continue the goal?' first, or say cancel."
    const val FINISH_HAND_OVER_TYPED = "Finish the step first, then tap Continue, or Cancel."
    const val FINISH_HAND_OVER_SPOKEN = "Say 'Kestrel, continue' or 'cancel' first."

    /** A typed answer came after its question had ended (plan 7F). */
    const val ANSWER_ENDED = "That question had ended, so I didn't use your answer."
    const val CONTINUE_GOAL = "Continue the goal?"

    /** The owner's word in a record's line when he continued a goal. */
    const val CONTINUE_WORD = "continue"
    const val NEW_CONVERSATION = "Okay, a new conversation."
    const val CALL_STARTED = "I stopped the task because a call started right after my last action."
    const val NOTHING_CHANGED = "I didn't change anything."
    const val GO_ON = "Say 'Kestrel, continue' or tap Continue to go on, or cancel."
    const val LEAVE_KESTREL = "Leave Kestrel's screen, then tap Continue or say 'Kestrel, continue'."

    /** Android refused the goal service (plan 7E; design 10.6): why the goal stopped. */
    const val BACKGROUND_REFUSED = "Android didn't let me start working in the background."

    /**
     * How the owner goes on after a refusal (design 10.6 of sub-project 7): Continue on the conversation screen, which the
     * launcher and every notification of Kestrel's open and whose start of the goal service comes from Kestrel's screen in
     * front (plan 7F); the held goal's notification, which Kestrel posts without the service (plan 7E), has its own
     * Continue too.
     */
    const val OPEN_AND_CONTINUE = "Open Kestrel and tap Continue."
    const val NOT_FOUND = "I didn't find a conversation about that."
    const val GONE = "That conversation is no longer kept."
    const val REBUILDING_PAST = "I'm still rebuilding the index of your past conversations and can't search them yet."
    const val PAST_UNAVAILABLE = "I can't read your past conversations right now. I'll try again later."
    const val DELETE_HISTORY_QUESTION = "Delete the activity log, the network log and past conversations?"
    const val SAY_YES_OR_NO = "Say yes or no."
    const val HISTORY_DELETED = "I deleted the history."
    const val HISTORY_NOT_DELETED = "I couldn't delete the history."
    const val REMEMBERED = "I'll remember that."
    const val FORGOTTEN = "I've forgotten it."
    const val NOTHING_TO_SAVE = "There's nothing to save: no task finished in the last 10 minutes."
    const val COULD_NOT = "Something went wrong in Kestrel, so I stopped."

    private val DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)
    private val DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)

    /** A command during a hand-over: typed, the screen's buttons; spoken, the words. */
    fun finishHandOverFirst(channel: InputChannel): String = if (channel == InputChannel.TEXT) FINISH_HAND_OVER_TYPED else FINISH_HAND_OVER_SPOKEN

    /** How the owner goes on with a goal that [hold] holds (design 6.5, 10.2 and 10.6); the goal notification shows it too. */
    fun held(hold: GoalHold): String = when (hold) {
        GoalHold.KESTREL_SCREEN -> LEAVE_KESTREL
        GoalHold.BACKGROUND_REFUSED -> OPEN_AND_CONTINUE
        GoalHold.LOCK, GoalHold.INCOMING_CALL, GoalHold.OUTGOING_CALL, GoalHold.LIMIT, GoalHold.TOUCH, GoalHold.RESTART -> GO_ON
    }

    /**
     * The end of a final reply that names the steering that came too late for the goal (plan 7A's "Interfaces for 7B"),
     * with a space before it; empty when none did. It names each message in its record's form, because the final words
     * are the record's answer (review of plan 7B: C1).
     */
    fun tooLate(messages: List<SteeringMessage>): String {
        if (messages.isEmpty()) return ""
        val them = if (messages.size == 1) "it" else "them"
        return " I got ${messages.joinToString(" and ") { "'${it.recorded}'" }} too late to act on $them; say $them again as a new command."
    }

    /** What Kestrel says at once to a steering message that came after the goal had given its final words. */
    fun cameTooLate(message: SteeringMessage): String = tooLate(listOf(message)).trimStart()

    fun remember(key: String, value: String): String = "Remember that $key is $value?"

    fun forget(key: String): String = "Forget $key?"

    /** The answer to "forget" a fact that does not exist, which its record keeps: the owner's [key] in its record's form. */
    fun noFact(key: String): String = "There's no fact named ${RecordText.of(key)}."

    /** "Friday, 20 November". */
    fun day(wall: Long, zone: ZoneId): String = DAY.format(Instant.ofEpochMilli(wall).atZone(zone))

    /** "Friday, 20 November 2026". */
    fun date(wall: Long, zone: ZoneId): String = DATE.format(Instant.ofEpochMilli(wall).atZone(zone))

    fun switchQuestion(day: String, firstRequest: String): String = "Continue the conversation from $day, about '$firstRequest'?"

    fun backIn(day: String): String = "We're back in the conversation from $day."

    /** The continued conversation's note in its records block (design 9.6 and 12.4). */
    fun continuedNote(wall: Long, zone: ZoneId, testMode: Boolean): String =
        "This conversation was continued on ${date(wall, zone)}; test mode is ${if (testMode) "on" else "off"} now."
}
