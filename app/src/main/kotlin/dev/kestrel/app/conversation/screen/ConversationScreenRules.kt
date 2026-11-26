package dev.kestrel.app.conversation.screen

import dev.kestrel.app.conversation.OwnerCard
import dev.kestrel.app.conversation.SentInput
import dev.kestrel.app.goal.ForegroundState
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputIntent

/**
 * What the screen shows for the goal: nothing (also while a continue or limit card carries the goal's buttons), Stop while
 * it works, or how to go on with Continue and Cancel while it is held.
 */
internal sealed interface GoalControls {
    data object None : GoalControls

    /** The goal works; [behindCommand] while its round waits until the owner's instant command has ended (plan 7E). */
    data class Working(val behindCommand: Boolean = false) : GoalControls

    data class Held(val how: String) : GoalControls {
        override fun toString(): String = "Held(how=${how.length} chars)"
    }
}

/**
 * The conversation screen as it is drawn (design 10.1 of sub-project 7): its transcript's [lines], the goal's controls,
 * whether "New conversation" reads "Stop and start new" ([stopAndNew]), and the open question's or hand-over's [card],
 * which the screen shows above the field while it is in front.
 */
internal data class ScreenUi(val lines: List<TranscriptLine>, val goal: GoalControls, val stopAndNew: Boolean, val card: OwnerCard?)

/**
 * The owner's act on the screen that asked for work (design 10.2): the screen leaves once that work's goal acts. The arm
 * lasts only as long as the act: once the act is [over] without a goal that acted, no later goal, such as one that the
 * owner started by voice without the screen, moves the screen.
 */
internal sealed interface Arm {
    /** Whether the goal that acts in [view] is this act's work. */
    fun moves(view: ConversationView): Boolean

    /** Whether the act ended in [view] without a goal that acted. */
    fun over(view: ConversationView): Boolean

    /**
     * A command typed after command number [after], or one spoken after the owner's microphone tap number [tap]: any
     * goal that acts is its work, because the view may show the goal before the screen hears of the input (review A2).
     * It is over once a newer command ended without a goal; a spoken one also when the listening that its tap asked for
     * ended without a command (review M1 of Task 8).
     */
    data class Command(val after: Int, val tap: Long? = null) : Arm {
        override fun moves(view: ConversationView): Boolean = view.goal == GoalPhase.ACTING

        override fun over(view: ConversationView): Boolean = view.goal == null && view.command > after && view.quiet is QuietClock.Running
    }

    /**
     * Continue, or an answer that lets the open goal go on: the goal of record [record] in conversation [id], which
     * keeps its record when it resumes. It is over once that goal is no longer the open one.
     */
    data class Goal(val id: ConversationId, val record: Int) : Arm {
        override fun moves(view: ConversationView): Boolean = view.goal == GoalPhase.ACTING && !over(view)

        override fun over(view: ConversationView): Boolean = view.goal == null || view.id != id || view.goalRecord != record
    }
}

/**
 * The screen's own state: the transcript that it shows ([records] of conversation [id], or of the one that ended at
 * [endedAtWall]), the owner's act on the screen that waits for its goal to act ([armed]), and the two steps of "Stop and
 * start new" ([newAfterStop], then [clearAtClose]). toString prints no words.
 */
internal data class ScreenState(
    val id: ConversationId? = null,
    val records: List<CommandRecord> = emptyList(),
    val continuedAtWall: Long? = null,
    val endedAtWall: Long? = null,
    val armed: Arm? = null,
    val newAfterStop: Boolean = false,
    val clearAtClose: Boolean = false,
) {
    override fun toString(): String =
        "ScreenState(open=${id != null}, records=${records.size}, ended=${endedAtWall != null}, armed=$armed, newAfterStop=$newAfterStop, clearAtClose=$clearAtClose)"
}

/** What the screen does after a step: leave the front, or give the conversation an input of its own. */
internal sealed interface ScreenEffect {
    data object Leave : ScreenEffect

    data class Submit(val intent: InputIntent) : ScreenEffect
}

internal data class ScreenStep(val state: ScreenState, val effects: List<ScreenEffect> = emptyList())

/**
 * The conversation screen's rules (design 10.1 and 10.2 of sub-project 7; plan 7F). The screen draws the conversation's
 * view; a conversation that closes keeps its transcript with the line where it ended until the next one opens, unless the
 * owner asked for a new one. After the owner's command, Continue, microphone or an answer that lets a goal go on, the
 * screen leaves the front once the goal acts and Android has answered the goal service's start, so that Android sees the
 * start while the screen is in front (design 10.6); a reply without a goal keeps the screen, and once the owner's act
 * ended without a goal that acts, no later goal moves the screen.
 */
internal object ConversationScreenRules {
    fun ui(state: ScreenState, view: ConversationView, card: OwnerCard?): ScreenUi = ScreenUi(
        lines = Transcript.of(state.records, state.continuedAtWall, state.endedAtWall),
        goal = if (carriesGoalButtons(card)) GoalControls.None else when (view.goal) {
            null -> GoalControls.None
            GoalPhase.ACTING, GoalPhase.ASKING, GoalPhase.HANDED_OVER -> GoalControls.Working(view.waitsBehindCommand)
            GoalPhase.PAUSED, GoalPhase.PAUSED_AT_LIMIT, GoalPhase.INTERRUPTED -> GoalControls.Held(view.hold?.let(ConversationTexts::held) ?: ConversationTexts.GO_ON)
        },
        stopAndNew = view.goal != null,
        card = card,
    )

    /** The newest [view], with what is known of the goal service ([foreground]), at [nowWall]. */
    fun viewed(state: ScreenState, view: ConversationView, foreground: ForegroundState, nowWall: Long): ScreenStep {
        var next = when {
            // A new conversation, or the same one: "New conversation" waits for its close only while the same one is open.
            view.id != null -> state.copy(
                id = view.id,
                records = view.records,
                continuedAtWall = view.continuedAtWall,
                endedAtWall = null,
                clearAtClose = state.clearAtClose && view.id == state.id,
            )
            state.id == null -> state
            state.clearAtClose -> ScreenState()
            else -> state.copy(id = null, endedAtWall = nowWall)
        }
        val effects = mutableListOf<ScreenEffect>()
        val armed = next.armed
        if (armed != null) {
            // The coordinator saw the goal act too, the service is in the foreground, and Android answered every start.
            if (armed.moves(view) && foreground.goal == GoalPhase.ACTING && foreground.running && foreground.settled) {
                next = next.copy(armed = null)
                effects += ScreenEffect.Leave
            } else if (armed.over(view)) {
                // The owner's act ended without a goal that works: its reply stays on the screen.
                next = next.copy(armed = null)
            }
        }
        if (next.newAfterStop && view.goal == null) {
            next = next.copy(newAfterStop = false, clearAtClose = true)
            effects += ScreenEffect.Submit(InputIntent.NewConversation)
        }
        return ScreenStep(next, effects)
    }

    /**
     * The owner's input [sent] reached the conversation from the screen. Its act is read against the view from before
     * the conversation got it, never against a newer one that may already count it (the whole-plan review's M3).
     */
    fun sent(state: ScreenState, sent: SentInput): ScreenState {
        val intent = sent.intent
        val view = sent.before
        return when {
            !leaves(intent, view) -> state
            intent is InputIntent.Command -> state.copy(armed = Arm.Command(view.command))
            else -> state.copy(
                armed = Arm.Goal(
                    checkNotNull(view.id) { "A goal belongs to an open conversation" },
                    checkNotNull(view.goalRecord) { "An open goal has a record" },
                ),
            )
        }
    }

    /** The owner's microphone tap number [tap]: the spoken command's goal moves the screen as a typed command's does. */
    fun listened(state: ScreenState, view: ConversationView, tap: Long): ScreenState =
        state.copy(armed = Arm.Command(view.command, tap))

    /** The listening that the microphone taps [taps] asked for ended without a command: the act of the armed one is over. */
    fun heardNoCommand(state: ScreenState, taps: List<Long>): ScreenState {
        val tap = (state.armed as? Arm.Command)?.tap ?: return state
        return if (tap in taps) state.copy(armed = null) else state
    }

    /** The screen left the front: what the owner asked of it there is over. */
    fun left(state: ScreenState): ScreenState = state.copy(armed = null)

    /** "New conversation", or "Stop and start new" while a goal is open (design 6.8). */
    fun newConversation(state: ScreenState, view: ConversationView): ScreenStep = when {
        view.goal != null -> ScreenStep(state.copy(newAfterStop = true), listOf(ScreenEffect.Submit(InputIntent.Stop)))
        view.id != null -> ScreenStep(state.copy(clearAtClose = true), listOf(ScreenEffect.Submit(InputIntent.NewConversation)))
        else -> ScreenStep(ScreenState())
    }

    /**
     * Whether the owner's [intent] starts or resumes work in the app that he came from: a command, Continue on a held goal,
     * and an answer that lets the goal go on.
     */
    fun leaves(intent: InputIntent, view: ConversationView): Boolean = when (intent) {
        is InputIntent.Command -> true
        InputIntent.Continue -> view.goal != null
        is InputIntent.Answer -> view.goal != null && when (view.asking) {
            QuestionKind.CONTINUE_GOAL, QuestionKind.LIMIT -> intent.answer == ChannelAnswer.Yes
            else -> true
        }
        else -> false
    }

    /**
     * Whether [card] asks whether the goal goes on (continue or limit): its buttons are then the goal's, and the goal row
     * shows none of its own, so that the screen has one Stop and one Continue (review I1 of Task 7).
     */
    private fun carriesGoalButtons(card: OwnerCard?): Boolean = when (card?.kind) {
        QuestionKind.CONTINUE_GOAL, QuestionKind.LIMIT -> true
        else -> false
    }
}
