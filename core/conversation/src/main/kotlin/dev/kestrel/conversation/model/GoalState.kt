package dev.kestrel.conversation.model

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Interruption
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.goal.ResumeCause
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** A goal's phase (design 5.3 of sub-project 7); the last three are held (D17). */
enum class GoalPhase { ACTING, ASKING, HANDED_OVER, PAUSED, PAUSED_AT_LIMIT, INTERRUPTED }

/**
 * Why a goal is held (D13 and D17 of sub-project 7), with the phase that it gives and how it resumes. A lock or a call
 * pauses the round, which waits at its gate and goes on with a fresh look ([resume] is null); a limit, a touch, Kestrel's
 * own screen or a restart ended the round, and the goal resumes in a new run with [resume] (design 7.6).
 */
enum class GoalHold(val phase: GoalPhase, val resume: ResumeCause?) {
    LOCK(GoalPhase.PAUSED, null),
    INCOMING_CALL(GoalPhase.PAUSED, null),
    OUTGOING_CALL(GoalPhase.PAUSED, null),
    LIMIT(GoalPhase.PAUSED_AT_LIMIT, ResumeCause.LIMIT),
    TOUCH(GoalPhase.INTERRUPTED, ResumeCause.INTERRUPTION),
    KESTREL_SCREEN(GoalPhase.INTERRUPTED, ResumeCause.INTERRUPTION),
    RESTART(GoalPhase.INTERRUPTED, ResumeCause.RESTART),
    ;

    companion object {
        /**
         * What holds a goal whose round ended with [report]: a pause at a limit or an interruption (design 7.4 and D13 of
         * sub-project 7); null when the round's end ends the goal. The one rule, which the goal runner reads for the round's
         * final words and the machine for the goal's phase; the machine alone decides whether the goal stays held.
         */
        fun of(report: TaskReport): GoalHold? = when (report.end) {
            EndReason.PAUSED_AT_LIMIT -> LIMIT
            EndReason.INTERRUPTED -> if (report.interruption == Interruption.KESTREL_SCREEN) KESTREL_SCREEN else TOUCH
            else -> null
        }
    }
}

/** How a goal whose round still runs is ending: "stop" ends it for good, a touch interrupts it (D13). */
enum class GoalEnding { STOPPING, INTERRUPTING }

/**
 * Why a goal that is [GoalEnding.STOPPING] ends: [reason], and Kestrel's [sentence] that replaces the report's reason, as
 * the call backstop's does. The goal keeps the first stop until it has ended, also when its round ends held meanwhile (a
 * limit's pause or a touch that the stop overtook), so that the end keeps the stop's exact reason and its sentence.
 */
data class GoalStop(val reason: EndReason, val sentence: String? = null) {
    override fun toString(): String = "GoalStop(reason=$reason, sentence=${sentence?.length})"
}

/**
 * The open goal (design 6.1 and 7.7 of sub-project 7): the [record] of the command that started it, its [phase], why it
 * is held, when it last changed on the wall clock (a restored goal is held for 30 minutes from then, design 6.7), how
 * many rounds it has had and how many steps its ended rounds took ([stepsBefore], which the bar's step adds to the running
 * round's, design 10.5). [command] (the number of the input that started or last resumed it), [ending], [stop] (exactly
 * while it is stopping) and [handOver] (the open hand-over's number) belong to this process and are not stored.
 */
@Serializable
data class GoalState(
    val record: Int,
    val phase: GoalPhase,
    val changedAtWall: Long,
    val hold: GoalHold? = null,
    val rounds: Int = 1,
    val stepsBefore: Int = 0,
    @Transient val command: Int = 0,
    @Transient val ending: GoalEnding? = null,
    @Transient val handOver: Int? = null,
    @Transient val stop: GoalStop? = null,
) {
    init {
        require(hold?.phase == phase || (hold == null && phase !in HELD)) { "A goal is held exactly in a held phase, by its hold" }
        require((ending == GoalEnding.STOPPING) == (stop != null)) { "A goal that is stopping keeps its stop, and only then" }
    }

    companion object {
        val HELD: Set<GoalPhase> = setOf(GoalPhase.PAUSED, GoalPhase.PAUSED_AT_LIMIT, GoalPhase.INTERRUPTED)
    }
}
