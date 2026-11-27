package dev.kestrel.app.goal

import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalState
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A button of the goal notification (design 10.6 of sub-project 7): [STOP] and [CANCEL] stop the goal, as "stop" does,
 * and [CONTINUE] goes on with a held goal, as "Kestrel, continue" does (design 6.4); each reaches Kestrel as a broadcast
 * with the action [broadcast], which only Kestrel's own notification can send. [OPEN] opens Kestrel's screen and gives no
 * input. A button that [resumes] what Kestrel does asks Android for the owner's unlock first, so that nobody resumes a goal
 * from the lock screen (the owner's decision of 2026-11-25); a button that stops Kestrel needs none.
 */
internal enum class GoalAction(val label: String, val intent: InputIntent?, val resumes: Boolean) {
    STOP(GoalTexts.STOP, InputIntent.Stop, resumes = false),
    OPEN(GoalTexts.OPEN, null, resumes = false),
    CONTINUE(GoalTexts.CONTINUE, InputIntent.Continue, resumes = true),
    CANCEL(GoalTexts.CANCEL, InputIntent.Stop, resumes = false),
    ;

    /** Whether the button stops the goal: Stop while it works, Cancel while it is held (design 6.4). */
    val stops: Boolean get() = intent == InputIntent.Stop

    /** The action of the broadcast that the button sends. */
    val broadcast: String get() = "dev.kestrel.goal.$name"

    companion object {
        /** The button whose broadcast has the action [action]; null for any other action, and for [OPEN]. */
        fun ofBroadcast(action: String?): GoalAction? = entries.firstOrNull { it.intent != null && it.broadcast == action }
    }
}

/** What the notification shows: a goal that works, a held goal, or the end of a goal whose final reply is said or waits. */
internal enum class NoticeKind { WORKING, HELD, CLOSING }

/** The progress bar: [done] of [total] checklist items. */
internal data class NoticeProgress(val done: Int, val total: Int)

/**
 * The goal that a notice shows, by its [conversation] and its [record] number there: one name for the goal across its
 * resumes and Kestrel's processes, which a command number is not (it belongs to one process and changes at every resume).
 * The buttons' broadcasts carry it, so that a tap counts only for the goal that its notice showed (plan 7E).
 */
internal data class NoticeGoal(val conversation: ConversationId, val record: Int) {
    /** The goal as one text, `<conversation>/<record>`, which the buttons' broadcasts carry as their data. */
    val key: String get() = "${conversation.value}/$record"

    override fun toString(): String = "NoticeGoal(record=$record)"

    companion object {
        /** The goal that [key] names; null for any other text. */
        fun ofKey(key: String?): NoticeGoal? {
            val (conversation, record) = key?.split('/')?.takeIf { it.size == 2 } ?: return null
            val number = record.toIntOrNull()?.takeIf { it > 0 } ?: return null
            return runCatching { NoticeGoal(ConversationId(conversation), number) }.getOrNull()
        }
    }
}

/**
 * The goal notification's words and buttons, apart from Android, for the [goal] that it shows (none after the goal's end).
 * toString prints no words: [text] may hold names.
 */
internal data class GoalNotice(
    val kind: NoticeKind,
    val title: String,
    val text: String?,
    val progress: NoticeProgress?,
    val actions: List<GoalAction>,
    val goal: NoticeGoal? = null,
) {
    override fun toString(): String = "GoalNotice(kind=$kind, text=${text?.length} chars, progress=$progress, actions=$actions, goal=$goal)"
}

/**
 * The goal notification for the conversation's view (design 10.6 of sub-project 7; plan 7E). While the goal works (it
 * acts, asks or has handed a step over): "Kestrel is working", the current checklist item, or "Waiting for your command
 * to finish" while its round waits behind the owner's instant command, the checklist's progress, Stop and Open. While it
 * is held: "Continue the goal?", how to go on for its hold (the sentence that Kestrel says), Cancel and then Continue, so
 * that the first button always ends the goal. Without a goal: none. Buttons need the goal's name ([NoticeGoal]), which an
 * open goal always has; a goal without it gets no buttons, because none of them could ever count.
 */
internal object GoalNotices {
    /** The notice after the goal's end while its final reply is said or waits, and of a start that came after the end. */
    val CLOSING = GoalNotice(NoticeKind.CLOSING, GoalTexts.FINISHED, null, null, emptyList())

    fun of(view: ConversationView): GoalNotice? {
        val phase = view.goal ?: return null
        val goal = view.id?.let { id -> view.goalRecord?.let { NoticeGoal(id, it) } }
        fun buttons(vararg actions: GoalAction) = if (goal == null) emptyList() else actions.toList()
        if (phase in GoalState.HELD) {
            val how = view.hold?.let(ConversationTexts::held)
            return GoalNotice(NoticeKind.HELD, ConversationTexts.CONTINUE_GOAL, how, null, buttons(GoalAction.CANCEL, GoalAction.CONTINUE), goal)
        }
        val checklist = view.checklist
        val progress = checklist?.takeIf { it.total > 0 }?.let { NoticeProgress(it.done, it.total) }
        val text = if (view.waitsBehindCommand) GoalTexts.BEHIND_COMMAND else checklist?.current
        return GoalNotice(NoticeKind.WORKING, GoalTexts.WORKING, text, progress, buttons(GoalAction.STOP, GoalAction.OPEN), goal)
    }
}

/**
 * A tap on a button of the goal notification as an input of the owner's (design 6.4 and 10.6 of sub-project 7; plan 7E).
 * The tap counts only for the [goal] that its notice showed, and only while the goal's notice [shown] offers what the
 * button does: a button that stops while the notice offers any way to stop (his tap on Stop can arrive after a touch, his
 * pull of the shade, held the goal and the notice offers Cancel instead), and Continue only while it offers Continue. A tap
 * is the owner's act of its moment, as a spoken or typed input is: its number of his inputs (`ButtonActs`, the next of
 * `InputIds`) and its time
 * are the tap's own, taken when its broadcast arrives (`GoalActionReceiver`), before any wait for the restore. So an input
 * that he gives during that wait comes after the tap, and a Continue that resumes the goal lets the round have an instant
 * command that he started before the tap, and waits for one that he started after it: plan 7D's `ScreenTurns` lets a round
 * cancel a command only when the round's input is newer than the command (`after > since`), and a resumed goal must never
 * cancel a command that the owner started after his tap. A tap that does not count leaves its number unused, which no other
 * input needs. A tap carries no words (D8), so the input keeps [channel], the channel of the owner's latest input, and
 * Kestrel's replies stay as they were (design 11).
 */
internal object GoalButtons {
    fun input(broadcast: String?, goal: NoticeGoal?, shown: GoalNotice?, channel: InputChannel, number: Long, atMillis: Long): OwnerInput? {
        val action = GoalAction.ofBroadcast(broadcast) ?: return null
        val intent = action.intent ?: return null
        if (shown == null || goal == null || shown.goal != goal) return null
        val offered = if (action.stops) shown.actions.any { it.stops } else action in shown.actions
        if (!offered) return null
        return OwnerInput(number, channel, intent, atMillis)
    }

    /** How long a tap waits for the conversation's restore: well inside the 10 s that Android gives a broadcast. */
    const val RESTORE_WAIT_MILLIS = 5_000L

    /**
     * Decides a tap once the conversation is [restored] (`ConversationEngine.awaitRestored`, then its view), against the
     * notice of the goal in that view: a tap on the held goal's notification that Kestrel posted without the service can
     * start Kestrel's process, and is then judged against the restored goal, never lost against the empty view before it.
     * A restore that does not end within [RESTORE_WAIT_MILLIS] drops the tap, and [report] gets only its kind. [number]
     * and [atMillis] are the tap's own, taken on its arrival, never after the wait (see [GoalButtons]).
     */
    suspend fun press(
        broadcast: String?,
        goal: NoticeGoal?,
        restored: suspend () -> ConversationView,
        number: Long,
        atMillis: Long,
        report: (String, Throwable) -> Unit,
    ): OwnerInput? {
        val view = withTimeoutOrNull(RESTORE_WAIT_MILLIS) { restored() }
        if (view == null) {
            report("A goal notification's button", RestoreTooSlowException())
            return null
        }
        return input(broadcast, goal, GoalNotices.of(view), view.replyChannel, number, atMillis)
    }
}

/** A tap on the goal notification waited longer than `GoalButtons.RESTORE_WAIT_MILLIS` for the restore, and was dropped. */
private class RestoreTooSlowException : Exception()
