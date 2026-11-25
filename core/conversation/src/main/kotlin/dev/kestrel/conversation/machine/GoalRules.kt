package dev.kestrel.conversation.machine

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Interruption
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.DialogueEvent
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.GoalEnding
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.GoalState
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.conversation.model.PhoneSignal

/**
 * A goal's phases (design 6.4, 6.5 and 7 of sub-project 7). A touch while the goal acts or asks interrupts its round and
 * holds the goal (D13); a lock or a call while it acts or asks pauses the round at its gate; a limit's pause, an
 * interruption and a restart end the round and hold the goal, which resumes in a new round. Every held goal is held for
 * 30 minutes (D17) and then ends with its partial report. "Continue the goal?" is asked when the owner is back after a
 * pause and after a restart, never by itself; a yes resumes the goal, a no ends it, and no answer leaves it held. A
 * hand-over ends after 3 minutes; a typed question waits as D21 says, and 5 minutes from "Reply…".
 */
internal object GoalRules {
    const val HOLD_MILLIS = 30L * 60 * 1000
    const val HAND_OVER_MILLIS = 3L * 60 * 1000
    const val QUESTION_MILLIS = 2L * 60 * 1000
    const val CONFIRM_MILLIS = 30L * 1000
    const val REPLY_MILLIS = 5L * 60 * 1000

    /** "Stop" ends the goal in every phase with its report, and drops a command that waited for it. */
    fun stop(state: ConversationState): MachineStep {
        val goal = state.goal ?: return MachineStep(state.copy(next = null))
        if (goal.ending == GoalEnding.STOPPING) return MachineStep(state.copy(next = null))
        val stopped = state.copy(goal = goal.copy(ending = GoalEnding.STOPPING), next = null)
        val effect = if (goal.hold != null && goal.phase != GoalPhase.PAUSED) {
            ConversationEffect.EndHeldGoal(EndReason.CANCELLED)
        } else {
            ConversationEffect.CancelGoal(EndReason.CANCELLED)
        }
        return MachineStep(stopped, listOf(effect)).then(::withdrawContinue)
    }

    /** A paused round goes on: its gate opens and it looks at the screen afresh. */
    fun resumePaused(state: ConversationState, goal: GoalState, channel: InputChannel, now: Long): MachineStep = MachineStep(
        state.copy(goal = goal.copy(phase = GoalPhase.ACTING, hold = null, changedAtWall = now), timers = state.timers.copy(hold = state.timers.hold + 1)),
        listOf(ConversationEffect.ResumePausedGoal, ConversationEffect.AddLine(goal.record, LineKind.OWNER_CONTINUED, ConversationTexts.CONTINUE_WORD, channel)),
    ).then(::withdrawContinue)

    /**
     * A goal held after its round ended resumes in a new round, with [steer] as its first steering (design 7.6), for the
     * owner's [input], or for his yes on the card of "Continue the goal?" (null).
     */
    fun resume(state: ConversationState, goal: GoalState, input: OwnerInput?, steer: String?, now: Long): MachineStep {
        val cause = goal.hold?.resume ?: return MachineStep(state)
        val channel = input?.channel ?: state.replyChannel
        val command = state.command + 1
        val resumed = goal.copy(phase = GoalPhase.ACTING, hold = null, changedAtWall = now, rounds = goal.rounds + 1, command = command)
        val effects = listOfNotNull(
            ConversationEffect.AddLine(goal.record, LineKind.OWNER_CONTINUED, ConversationTexts.CONTINUE_WORD, channel).takeIf { steer == null },
            ConversationEffect.ResumeGoal(command, cause, steer?.let { ConversationEffect.Steering(it, channel) }, input),
        )
        return MachineStep(state.copy(command = command, goal = resumed, timers = state.timers.copy(hold = state.timers.hold + 1)), effects)
            .then(::withdrawContinue)
    }

    /** The goal's round ended and [hold] holds it: Kestrel says the round's [sentence] and how to go on. */
    fun held(state: ConversationState, goal: GoalState, hold: GoalHold, sentence: String?, now: Long): MachineStep {
        val number = state.timers.hold + 1
        val text = listOfNotNull(sentence, ConversationTexts.held(hold)).joinToString(" ")
        return MachineStep(
            state.copy(
                goal = goal.copy(phase = hold.phase, hold = hold, changedAtWall = now, ending = null, handOver = null),
                question = null,
                timers = state.timers.copy(hold = number),
            ),
            listOf(
                ConversationEffect.Output(ChannelOutput.Said(text)),
                ConversationEffect.AddLine(goal.record, LineKind.KESTREL_SAID, text, null),
                ConversationEffect.StartTimer(Timer.HOLD, number, HOLD_MILLIS),
            ),
        )
    }

    /** What holds a goal whose round ended with [report]; null when the goal ended for good. */
    fun holdFor(report: TaskReport): GoalHold? = when (report.end) {
        EndReason.PAUSED_AT_LIMIT -> GoalHold.LIMIT
        EndReason.INTERRUPTED -> if (report.interruption == Interruption.KESTREL_SCREEN) GoalHold.KESTREL_SCREEN else GoalHold.TOUCH
        else -> null
    }

    /** D13: a touch interrupts a goal that acts or asks, except while the owner answers on Kestrel's screen. */
    fun touched(state: ConversationState): MachineStep {
        val goal = state.goal ?: return MachineStep(state)
        if (goal.ending != null) return MachineStep(state)
        val interrupts = goal.phase == GoalPhase.ACTING || (goal.phase == GoalPhase.ASKING && state.question?.replyOpen != true)
        if (!interrupts) return MachineStep(state)
        return MachineStep(state.copy(goal = goal.copy(ending = GoalEnding.INTERRUPTING)), listOf(ConversationEffect.InterruptGoal(Interruption.TOUCH)))
    }

    fun signal(state: ConversationState, signal: PhoneSignal, now: Long): MachineStep {
        val phone = state.phone
        return when (signal) {
            PhoneSignal.LOCKED -> pause(state.copy(phone = phone.copy(locked = true)), GoalHold.LOCK, now)
            PhoneSignal.UNLOCKED -> back(state.copy(phone = phone.copy(locked = false)))
            PhoneSignal.RINGING -> pause(state.copy(phone = phone.copy(ringing = true)), GoalHold.INCOMING_CALL, now)
            PhoneSignal.CALL_ACTIVE -> pause(
                state.copy(phone = phone.copy(ringing = false, inCall = true)),
                if (phone.ringing) GoalHold.INCOMING_CALL else GoalHold.OUTGOING_CALL,
                now,
            )
            PhoneSignal.CALL_IDLE -> back(state.copy(phone = phone.copy(ringing = false, inCall = false)))
            PhoneSignal.AGENT_CALL -> agentCall(state.copy(phone = phone.copy(ringing = false, inCall = true)))
        }
    }

    fun dialogue(state: ConversationState, event: DialogueEvent): MachineStep = when (event) {
        is DialogueEvent.Asked -> asked(state, event)
        is DialogueEvent.Answered ->
            if (state.question?.number != event.number) {
                MachineStep(state)
            } else {
                MachineStep(state.copy(question = null, goal = state.goal?.let { if (it.phase == GoalPhase.ASKING) it.copy(phase = GoalPhase.ACTING) else it }))
            }
        is DialogueEvent.HandedOver -> handedOver(state, event.number)
        is DialogueEvent.HandOverDone -> {
            val goal = state.goal
            if (goal == null || goal.handOver != event.number) {
                MachineStep(state)
            } else {
                MachineStep(state.copy(goal = goal.copy(phase = if (goal.phase == GoalPhase.HANDED_OVER) GoalPhase.ACTING else goal.phase, handOver = null)))
            }
        }
    }

    /** "Reply…" on question [question]'s card: 5 minutes from the tap, and the owner's touches interrupt nothing. */
    fun replyOpened(state: ConversationState, question: Int): MachineStep {
        val open = state.question?.takeIf { it.number == question } ?: return MachineStep(state)
        val number = state.timers.question + 1
        return MachineStep(
            state.copy(question = open.copy(replyOpen = true), timers = state.timers.copy(question = number)),
            listOf(ConversationEffect.StartTimer(Timer.QUESTION, number, REPLY_MILLIS)),
        )
    }

    fun timerEnded(state: ConversationState, timer: Timer, number: Int): MachineStep = when (timer) {
        Timer.QUIET -> MachineStep(state)
        Timer.HOLD -> holdEnded(state, number)
        Timer.HAND_OVER -> {
            val goal = state.goal
            val open = goal?.handOver
            if (goal == null || open == null || goal.phase != GoalPhase.HANDED_OVER || number != state.timers.handOver) {
                MachineStep(state)
            } else {
                MachineStep(state, listOf(ConversationEffect.EndHandOver(open, HandOverEnd.EXPIRED)))
            }
        }
        Timer.QUESTION -> {
            val open = state.question
            if (open == null || number != state.timers.question) {
                MachineStep(state)
            } else {
                MachineStep(state, listOf(ConversationEffect.AnswerQuestion(open.number, ChannelAnswer.Silence)))
            }
        }
    }

    // The yes came from the owner's input (a spoken or typed yes, "Kestrel, continue") or from the card, which gives none.
    fun continueAnswered(state: ConversationState, answer: ChannelAnswer, now: Long): MachineStep {
        val input = state.continueAnswer
        val asked = state.copy(asksContinue = false, continueAnswer = null)
        val goal = asked.goal
        if (goal == null || goal.hold == null || goal.ending != null) return MachineStep(asked)
        return when (answer) {
            ChannelAnswer.Yes ->
                if (goal.phase == GoalPhase.PAUSED) {
                    resumePaused(asked, goal, input?.channel ?: state.replyChannel, now)
                } else {
                    resume(asked, goal, input, null, now)
                }
            ChannelAnswer.No -> stop(asked)
            else -> MachineStep(asked)
        }
    }

    /**
     * A goal that the end of Kestrel's process cut, in any phase (design 6.7): interrupted, held for 30 minutes from its
     * last change, and "Continue the goal?" asked; when those 30 minutes have passed, it ends with its partial report.
     * When the owner stopped Kestrel ([ownerStopped]: Stop in Android's "Active apps", a force stop or removing it from
     * Recents, after the snapshot), the goal ends at once as "stop" ends a held goal, and nothing asks (plan 7E).
     */
    fun restored(state: ConversationState, stored: GoalState, now: Long, ownerStopped: Boolean = false): MachineStep {
        val goal = GoalState(stored.record, GoalPhase.INTERRUPTED, stored.changedAtWall, GoalHold.RESTART, stored.rounds, stored.stepsBefore)
        if (ownerStopped) {
            return MachineStep(state.copy(goal = goal.copy(ending = GoalEnding.STOPPING)), listOf(ConversationEffect.EndHeldGoal(EndReason.CANCELLED)))
        }
        val left = HOLD_MILLIS - maxOf(0L, now - stored.changedAtWall)
        if (left <= 0) {
            return MachineStep(state.copy(goal = goal.copy(ending = GoalEnding.STOPPING)), listOf(ConversationEffect.EndHeldGoal(EndReason.PAUSED_TOO_LONG)))
        }
        val number = state.timers.hold + 1
        return MachineStep(state.copy(goal = goal, timers = state.timers.copy(hold = number)), listOf(ConversationEffect.StartTimer(Timer.HOLD, number, left)))
            .then(::askContinue)
    }

    fun withdrawContinue(state: ConversationState): MachineStep =
        if (!state.asksContinue) {
            MachineStep(state)
        } else {
            MachineStep(state.copy(asksContinue = false, continueAnswer = null), listOf(ConversationEffect.CancelContinueQuestion))
        }

    /**
     * Kestrel turns off or on (decision 10 of plan 7B). A spoken conversation's open question then gets D21's wait, which
     * the voice's own waits replace again once Kestrel is on; the end of the older wait changes nothing.
     */
    fun voice(state: ConversationState, on: Boolean): MachineStep {
        val changed = state.copy(voiceOn = on)
        val open = state.question
        if (open == null || open.replyOpen || state.replyChannel == InputChannel.TEXT || state.voiceOn == on) return MachineStep(changed)
        val number = state.timers.question + 1
        val timed = changed.copy(timers = changed.timers.copy(question = number))
        return if (on) MachineStep(timed) else MachineStep(timed, listOf(ConversationEffect.StartTimer(Timer.QUESTION, number, waitFor(open.kind))))
    }

    private fun askContinue(state: ConversationState): MachineStep {
        val goal = state.goal ?: return MachineStep(state)
        return MachineStep(
            state.copy(asksContinue = true, continueAnswer = null),
            listOf(ConversationEffect.AskContinueGoal, ConversationEffect.AddLine(goal.record, LineKind.KESTREL_ASKED, ConversationTexts.CONTINUE_GOAL, null)),
        )
    }

    // A lock or a call pauses a goal that acts or asks; a held goal's "Continue the goal?" waits for the owner's return.
    private fun pause(state: ConversationState, hold: GoalHold, now: Long): MachineStep {
        val goal = state.goal
        if (goal == null || goal.ending != null) return MachineStep(state)
        if (goal.phase != GoalPhase.ACTING && goal.phase != GoalPhase.ASKING) return withdrawContinue(state)
        val number = state.timers.hold + 1
        return MachineStep(
            state.copy(goal = goal.copy(phase = GoalPhase.PAUSED, hold = hold, changedAtWall = now), timers = state.timers.copy(hold = number)),
            listOf(ConversationEffect.PauseGoal, ConversationEffect.StartTimer(Timer.HOLD, number, HOLD_MILLIS)),
        )
    }

    // The owner is back (the unlock, or the end of a call with the phone unlocked): a report kept for him is given now, and
    // a paused or restored goal asks "Continue the goal?".
    private fun back(state: ConversationState): MachineStep {
        if (state.phone.away) return MachineStep(state)
        val report = state.heldReport
        val shown = if (report == null) MachineStep(state) else MachineStep(state.copy(heldReport = null), listOf(ConversationEffect.Output(report)))
        return shown.then { current ->
            val goal = current.goal
            val asks = goal != null && goal.ending == null && !current.asksContinue && (goal.phase == GoalPhase.PAUSED || goal.hold == GoalHold.RESTART)
            if (asks) askContinue(current) else MachineStep(current)
        }
    }

    // The call backstop's verdict (D13 of sub-project 5): a call that the agent started ends the goal; it is no interruption.
    private fun agentCall(state: ConversationState): MachineStep {
        val goal = state.goal ?: return MachineStep(state)
        if (goal.ending != null || (goal.phase != GoalPhase.ACTING && goal.phase != GoalPhase.ASKING)) return MachineStep(state)
        return MachineStep(
            state.copy(goal = goal.copy(ending = GoalEnding.STOPPING)),
            listOf(ConversationEffect.CancelGoal(EndReason.CANCELLED, ConversationTexts.CALL_STARTED)),
        )
    }

    // A question opened: a goal that acts now asks (the conversation's own questions leave its phase alone), and a typed
    // conversation's question waits as D21 says. Every question gets the next number, so an older wait changes nothing.
    private fun asked(state: ConversationState, event: DialogueEvent.Asked): MachineStep {
        val goal = state.goal
        val asking = if (goal != null && goal.phase == GoalPhase.ACTING && event.kind != QuestionKind.CONTINUE_GOAL) goal.copy(phase = GoalPhase.ASKING) else goal
        val number = state.timers.question + 1
        val opened = state.copy(goal = asking, question = OpenQuestion(event.number, event.kind), timers = state.timers.copy(question = number))
        if (state.replyChannel != InputChannel.TEXT && state.voiceOn) return MachineStep(opened)
        return MachineStep(opened, listOf(ConversationEffect.StartTimer(Timer.QUESTION, number, waitFor(event.kind))))
    }

    private fun waitFor(kind: QuestionKind): Long = if (kind == QuestionKind.CONFIRM) CONFIRM_MILLIS else QUESTION_MILLIS

    // A hand-over that a lock or a call met just as it opened wins, because the owner is in it and a hand-over is never
    // paused: the pause's hold and its 30 minutes end, and the round's gate opens again.
    private fun handedOver(state: ConversationState, handOver: Int): MachineStep {
        val goal = state.goal ?: return MachineStep(state)
        val number = state.timers.handOver + 1
        val paused = goal.phase == GoalPhase.PAUSED
        val timers = state.timers.copy(handOver = number, hold = if (paused) state.timers.hold + 1 else state.timers.hold)
        return MachineStep(
            state.copy(goal = goal.copy(phase = GoalPhase.HANDED_OVER, hold = null, handOver = handOver), timers = timers),
            listOfNotNull(ConversationEffect.ResumePausedGoal.takeIf { paused }, ConversationEffect.StartTimer(Timer.HAND_OVER, number, HAND_OVER_MILLIS)),
        ).then(::withdrawContinue)
    }

    private fun holdEnded(state: ConversationState, number: Int): MachineStep {
        val goal = state.goal ?: return MachineStep(state)
        if (number != state.timers.hold || goal.hold == null || goal.ending != null) return MachineStep(state)
        val effect = if (goal.phase == GoalPhase.PAUSED) {
            ConversationEffect.CancelGoal(EndReason.PAUSED_TOO_LONG)
        } else {
            ConversationEffect.EndHeldGoal(EndReason.PAUSED_TOO_LONG)
        }
        return MachineStep(state.copy(goal = goal.copy(ending = GoalEnding.STOPPING)), listOf(effect)).then(::withdrawContinue)
    }
}
