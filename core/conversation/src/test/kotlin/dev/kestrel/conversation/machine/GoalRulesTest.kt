package dev.kestrel.conversation.machine

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Interruption
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.goal.ResumeCause
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.DialogueEvent
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.local.LocalOutcome
import dev.kestrel.conversation.local.LocalRequest
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.GoalEnding
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.conversation.model.PhoneSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A goal's phases (design 6.4 and 6.5 of sub-project 7): touches, locks, calls, holds, hand-overs and the typed waits. */
class GoalRulesTest : MachineTesting() {
    private fun signal(state: ConversationState, signal: PhoneSignal): MachineStep = step(state, ConversationEvent.Signal(signal))

    @Test
    fun aTouchWhileTheGoalActsOrAsksInterruptsItAndHoldsIt() {
        // This changes spec D27 on purpose (D13): a touch no longer cancels a goal; it interrupts it and holds it.
        val interrupting = step(acting(), ConversationEvent.Touched)
        assertEquals(ConversationEffect.InterruptGoal(Interruption.TOUCH), interrupting.effect<ConversationEffect.InterruptGoal>())
        assertTrue(step(interrupting.state, ConversationEvent.Touched).effects.isEmpty())

        val held = ended(interrupting.state, TaskReport(EndReason.INTERRUPTED, interruption = Interruption.TOUCH), "I stopped. I opened Viber.")
        val said = "I stopped. I opened Viber. ${ConversationTexts.GO_ON}"
        assertEquals(GoalPhase.INTERRUPTED, held.state.goal!!.phase)
        assertEquals(listOf(ChannelOutput.Said(said)), held.outputs())
        assertEquals(ConversationEffect.AddLine(1, LineKind.KESTREL_SAID, said, null), held.effect<ConversationEffect.AddLine>())
        assertEquals(ConversationEffect.StartTimer(Timer.HOLD, 1, GoalRules.HOLD_MILLIS), held.effect<ConversationEffect.StartTimer>())
        assertTrue(step(asked(acting(), 3, QuestionKind.FREE), ConversationEvent.Touched).has<ConversationEffect.InterruptGoal>())

        val screen = ended(acting(), TaskReport(EndReason.INTERRUPTED, interruption = Interruption.KESTREL_SCREEN), "I stopped because Kestrel's own screen stayed in front.")
        assertEquals(GoalHold.KESTREL_SCREEN, screen.state.goal!!.hold)
        assertTrue((screen.outputs().single() as ChannelOutput.Said).text.endsWith(ConversationTexts.LEAVE_KESTREL))
    }

    @Test
    fun aTouchWhileTheGoalAsksClosesItsQuestionAndATypedCommandThenSteersTheResumedGoal() {
        val touched = step(asked(acting(), 3, QuestionKind.FREE), ConversationEvent.Touched)
        assertNull(touched.state.question)

        val also = typed("Send it to Mila instead")
        val waiting = step(touched.state, also)
        assertFalse(waiting.has<ConversationEffect.AnswerQuestion>())
        assertTrue(step(waiting.state, ConversationEvent.Dialogue(DialogueEvent.Answered(3))).effects.isEmpty())

        val resumed = ended(waiting.state, TaskReport(EndReason.INTERRUPTED, interruption = Interruption.TOUCH), "I stopped.")
        assertEquals(
            ConversationEffect.ResumeGoal(2, ResumeCause.INTERRUPTION, ConversationEffect.Steering("Send it to Mila instead", InputChannel.TEXT), also.input),
            resumed.effect<ConversationEffect.ResumeGoal>(),
        )
    }

    @Test
    fun touchesDuringAHandOverAReplyOrAHeldGoalInterruptNothing() {
        assertTrue(step(step(acting(), ConversationEvent.Dialogue(DialogueEvent.HandedOver(2))).state, ConversationEvent.Touched).effects.isEmpty())

        val replying = step(asked(acting(), 4, QuestionKind.FREE), ConversationEvent.ReplyOpened(4)).state
        assertTrue(step(replying, ConversationEvent.Touched).effects.isEmpty())

        val held = ended(acting(), TaskReport(EndReason.PAUSED_AT_LIMIT), "Limit.").state
        assertTrue(step(held, ConversationEvent.Touched).effects.isEmpty())
        assertTrue(step(signal(acting(), PhoneSignal.LOCKED).state, ConversationEvent.Touched).effects.isEmpty())
    }

    @Test
    fun aLockPausesTheGoalAndTheUnlockAsksContinueTheGoal() {
        val paused = signal(acting(), PhoneSignal.LOCKED)
        assertTrue(paused.has<ConversationEffect.PauseGoal>())
        assertEquals(GoalHold.LOCK, paused.state.goal!!.hold)
        assertEquals(ConversationEffect.StartTimer(Timer.HOLD, 1, GoalRules.HOLD_MILLIS), paused.effect<ConversationEffect.StartTimer>())

        val back = signal(paused.state, PhoneSignal.UNLOCKED)
        assertTrue(back.has<ConversationEffect.AskContinueGoal>())
        assertEquals(ConversationEffect.AddLine(1, LineKind.KESTREL_ASKED, ConversationTexts.CONTINUE_GOAL, null), back.effect<ConversationEffect.AddLine>())

        val asking = asked(back.state, 9, QuestionKind.CONTINUE_GOAL)
        assertEquals(GoalPhase.PAUSED, asking.goal!!.phase)
        assertEquals(ConversationEffect.AnswerQuestion(9, ChannelAnswer.Yes), step(asking, spoken(InputIntent.Continue)).effect<ConversationEffect.AnswerQuestion>())
    }

    @Test
    fun aCallPausesTheGoalAndItsEndAsksToContinue() {
        val ringing = signal(asked(acting(), 2, QuestionKind.YES_NO), PhoneSignal.RINGING)
        assertEquals(GoalHold.INCOMING_CALL, ringing.state.goal!!.hold)
        assertTrue(ringing.has<ConversationEffect.PauseGoal>())

        val active = signal(ringing.state, PhoneSignal.CALL_ACTIVE)
        assertFalse(active.has<ConversationEffect.PauseGoal>())
        assertTrue(signal(active.state, PhoneSignal.CALL_IDLE).has<ConversationEffect.AskContinueGoal>())

        assertEquals(GoalHold.OUTGOING_CALL, signal(acting(), PhoneSignal.CALL_ACTIVE).state.goal!!.hold)
        val handedOver = step(acting(), ConversationEvent.Dialogue(DialogueEvent.HandedOver(3))).state
        assertEquals(GoalPhase.HANDED_OVER, signal(handedOver, PhoneSignal.RINGING).state.goal!!.phase)
    }

    @Test
    fun yesResumesAPausedGoalNoEndsItAndSilenceKeepsItHeld() {
        val back = signal(signal(acting(), PhoneSignal.LOCKED).state, PhoneSignal.UNLOCKED).state

        val yes = step(back, ConversationEvent.ContinueAnswered(ChannelAnswer.Yes))
        assertTrue(yes.has<ConversationEffect.ResumePausedGoal>())
        assertEquals(GoalPhase.ACTING, yes.state.goal!!.phase)
        assertFalse(yes.state.asksContinue)

        assertEquals(ConversationEffect.CancelGoal(EndReason.CANCELLED), step(back, ConversationEvent.ContinueAnswered(ChannelAnswer.No)).effect<ConversationEffect.CancelGoal>())

        val silence = step(back, ConversationEvent.ContinueAnswered(ChannelAnswer.Silence))
        assertEquals(GoalPhase.PAUSED, silence.state.goal!!.phase)
        assertFalse(silence.state.asksContinue)
        assertTrue(signal(signal(silence.state, PhoneSignal.LOCKED).state, PhoneSignal.UNLOCKED).has<ConversationEffect.AskContinueGoal>())
    }

    @Test
    fun aLockDuringContinueTheGoalWithdrawsTheQuestionUntilTheNextUnlock() {
        val back = signal(signal(acting(), PhoneSignal.LOCKED).state, PhoneSignal.UNLOCKED).state

        val locked = signal(back, PhoneSignal.LOCKED)

        assertTrue(locked.has<ConversationEffect.CancelContinueQuestion>())
        assertFalse(locked.state.asksContinue)
        assertTrue(signal(locked.state, PhoneSignal.UNLOCKED).has<ConversationEffect.AskContinueGoal>())
    }

    @Test
    fun aYesThatALockOvertookLeavesTheGoalPausedAndTheUnlockAsksAgain() {
        val back = signal(signal(acting(), PhoneSignal.LOCKED).state, PhoneSignal.UNLOCKED).state
        val answering = step(asked(back, 9, QuestionKind.CONTINUE_GOAL), spoken(InputIntent.Answer(ChannelAnswer.Yes))).state

        // The owner said yes and pressed the power button: the lock comes before the answer's end.
        val locked = signal(answering, PhoneSignal.LOCKED)
        assertTrue(locked.has<ConversationEffect.CancelContinueQuestion>())
        assertNull(locked.state.question)
        val late = step(locked.state, ConversationEvent.ContinueAnswered(ChannelAnswer.Yes))

        assertFalse(late.has<ConversationEffect.ResumePausedGoal>())
        assertEquals(GoalPhase.PAUSED, checkNotNull(late.state.goal).phase)
        assertTrue(signal(late.state, PhoneSignal.UNLOCKED).has<ConversationEffect.AskContinueGoal>())
    }

    @Test
    fun aHeldGoalsReportWaitsWhileTheOwnerIsAway() {
        // A touch's interruption that a ringing call meets: the round ends held while the call rings.
        val ringing = signal(step(acting(), ConversationEvent.Touched).state, PhoneSignal.RINGING).state
        val held = ended(ringing, TaskReport(EndReason.INTERRUPTED, interruption = Interruption.TOUCH), "I stopped.")
        val said = "I stopped. ${ConversationTexts.GO_ON}"
        assertTrue(held.outputs().isEmpty())
        assertEquals(ConversationEffect.AddLine(1, LineKind.KESTREL_SAID, said, null), held.effect<ConversationEffect.AddLine>())
        assertEquals(ChannelOutput.Said(said), held.state.heldWords)

        val back = signal(held.state, PhoneSignal.CALL_IDLE)
        assertEquals(listOf(ChannelOutput.Said(said)), back.outputs())
        assertNull(back.state.heldWords)

        // When the hold's 30 minutes end while the owner is still away, only the goal's final reply waits for him.
        val expired = step(held.state, ConversationEvent.TimerEnded(Timer.HOLD, held.state.timers.hold)).state
        val final = ended(expired, TaskReport(EndReason.PAUSED_TOO_LONG), "Too long.")
        assertNull(final.state.heldWords)
        assertEquals(ChannelOutput.Report(1, "Too long."), final.state.heldReport)
    }

    @Test
    fun aHeldGoalsWordsWaitWhileThePhoneIsUnknownAndStopDropsThem() {
        // Before PhoneSignals has said whether the phone is locked, the owner is not known to be there.
        val held = ended(acting(from = starting), TaskReport(EndReason.PAUSED_AT_LIMIT), "Limit.")
        val said = "Limit. ${ConversationTexts.GO_ON}"
        assertTrue(held.outputs().isEmpty())
        assertEquals(ChannelOutput.Said(said), held.state.heldWords)
        assertEquals(listOf(ChannelOutput.Said(said)), signal(held.state, PhoneSignal.UNLOCKED).outputs())

        // "Stop" ends the held goal, so how to go on with it is no longer for the owner; only its final reply waits.
        val stopped = step(held.state, spoken(InputIntent.Stop))
        assertNull(stopped.state.heldWords)
        assertTrue(signal(stopped.state, PhoneSignal.UNLOCKED).outputs().isEmpty())
    }

    @Test
    fun aTouchWhileContinueTheGoalIsAskedChangesNothing() {
        // Design 6.5 and D13 change today's rule on purpose: a touch no longer answers "Continue the task?".
        val back = signal(signal(acting(), PhoneSignal.LOCKED).state, PhoneSignal.UNLOCKED).state
        val asking = asked(back, 6, QuestionKind.CONTINUE_GOAL)

        val touched = step(asking, ConversationEvent.Touched)

        assertTrue(touched.effects.isEmpty())
        assertEquals(asking, touched.state)
    }

    @Test
    fun aHandOverThatALockMetAsItOpenedIsNotPaused() {
        val paused = signal(acting(), PhoneSignal.LOCKED).state

        val handed = step(paused, ConversationEvent.Dialogue(DialogueEvent.HandedOver(5)))

        val goal = checkNotNull(handed.state.goal)
        assertEquals(GoalPhase.HANDED_OVER, goal.phase)
        assertNull(goal.hold)
        assertTrue(handed.has<ConversationEffect.ResumePausedGoal>())
        assertTrue(step(handed.state, ConversationEvent.TimerEnded(Timer.HOLD, paused.timers.hold)).effects.isEmpty())
        assertEquals(
            ConversationEffect.EndHandOver(5, HandOverEnd.CONTINUED),
            step(handed.state, spoken(InputIntent.Continue)).effect<ConversationEffect.EndHandOver>(),
        )
    }

    @Test
    fun thirtyMinutesEndEveryHeldGoalAndAReportWaitsForTheOwner() {
        val paused = signal(acting(), PhoneSignal.LOCKED).state
        val expired = step(paused, ConversationEvent.TimerEnded(Timer.HOLD, 1))
        assertEquals(ConversationEffect.CancelGoal(EndReason.PAUSED_TOO_LONG), expired.effect<ConversationEffect.CancelGoal>())

        val report = "I stopped the task because it was paused for a long time. I opened Viber."
        val waiting = ended(expired.state, TaskReport(EndReason.PAUSED_TOO_LONG), report)
        assertTrue(waiting.outputs().isEmpty())
        assertEquals(ChannelOutput.Report(1, report), waiting.state.heldReport)
        assertEquals(QuietClock.Held(HoldReason.REPLYING), waiting.state.quiet)

        val back = signal(waiting.state, PhoneSignal.UNLOCKED)
        assertEquals(listOf(ChannelOutput.Report(1, report)), back.outputs())
        assertNull(back.state.heldReport)

        val limited = ended(acting(), TaskReport(EndReason.PAUSED_AT_LIMIT), "Limit.").state
        assertEquals(ConversationEffect.EndHeldGoal(EndReason.PAUSED_TOO_LONG), step(limited, ConversationEvent.TimerEnded(Timer.HOLD, 1)).effect<ConversationEffect.EndHeldGoal>())
        assertTrue(step(limited, ConversationEvent.TimerEnded(Timer.HOLD, 0)).effects.isEmpty())
    }

    @Test
    fun aHandOverEndsAfterThreeMinutes() {
        val handed = step(acting(), ConversationEvent.Dialogue(DialogueEvent.HandedOver(5)))
        assertEquals(ConversationEffect.StartTimer(Timer.HAND_OVER, 1, GoalRules.HAND_OVER_MILLIS), handed.effect<ConversationEffect.StartTimer>())

        assertEquals(ConversationEffect.EndHandOver(5, HandOverEnd.EXPIRED), step(handed.state, ConversationEvent.TimerEnded(Timer.HAND_OVER, 1)).effect<ConversationEffect.EndHandOver>())
        val done = step(handed.state, ConversationEvent.Dialogue(DialogueEvent.HandOverDone(5))).state
        assertTrue(step(done, ConversationEvent.TimerEnded(Timer.HAND_OVER, 1)).effects.isEmpty())
    }

    @Test
    fun aCallThatTheAgentStartedEndsTheGoal() {
        val stopped = signal(acting(), PhoneSignal.AGENT_CALL)

        assertEquals(ConversationEffect.CancelGoal(EndReason.CANCELLED, ConversationTexts.CALL_STARTED), stopped.effect<ConversationEffect.CancelGoal>())
        assertEquals(GoalEnding.STOPPING, stopped.state.goal!!.ending)
        assertTrue(signal(idleOpen(), PhoneSignal.AGENT_CALL).effects.isEmpty())
    }

    @Test
    fun typedQuestionsWaitAsD21SaysAndReplyGivesFiveMinutes() {
        val goal = acting()
        val asking = step(goal, ConversationEvent.Dialogue(DialogueEvent.Asked(3, QuestionKind.YES_NO)))
        assertEquals(ConversationEffect.StartTimer(Timer.QUESTION, 1, GoalRules.QUESTION_MILLIS), asking.effect<ConversationEffect.StartTimer>())
        assertEquals(ConversationEffect.AnswerQuestion(3, ChannelAnswer.Silence), step(asking.state, ConversationEvent.TimerEnded(Timer.QUESTION, 1)).effect<ConversationEffect.AnswerQuestion>())

        val confirming = step(goal, ConversationEvent.Dialogue(DialogueEvent.Asked(4, QuestionKind.CONFIRM)))
        assertEquals(ConversationEffect.StartTimer(Timer.QUESTION, 1, GoalRules.CONFIRM_MILLIS), confirming.effect<ConversationEffect.StartTimer>())

        val reply = step(asking.state, ConversationEvent.ReplyOpened(3))
        assertEquals(ConversationEffect.StartTimer(Timer.QUESTION, 2, GoalRules.REPLY_MILLIS), reply.effect<ConversationEffect.StartTimer>())
        assertTrue(step(reply.state, ConversationEvent.TimerEnded(Timer.QUESTION, 1)).effects.isEmpty())

        val spokenGoal = step(idle, spoken("Turn on Wi-Fi")).state
        assertFalse(step(spokenGoal, ConversationEvent.Dialogue(DialogueEvent.Asked(5, QuestionKind.YES_NO))).has<ConversationEffect.StartTimer>())
    }

    @Test
    fun whileKestrelIsOffASpokenConversationsQuestionWaitsAsATypedOne() {
        val spokenGoal = step(idle, spoken("Turn on Wi-Fi")).state
        val off = step(spokenGoal, ConversationEvent.Voice(on = false)).state
        val confirming = step(off, ConversationEvent.Dialogue(DialogueEvent.Asked(3, QuestionKind.CONFIRM)))
        assertEquals(ConversationEffect.StartTimer(Timer.QUESTION, 1, GoalRules.CONFIRM_MILLIS), confirming.effect<ConversationEffect.StartTimer>())

        // A question that was open when Kestrel turned off gets its wait then, and loses it when Kestrel is on again.
        val open = asked(spokenGoal, 4, QuestionKind.FREE)
        val stopped = step(open, ConversationEvent.Voice(on = false))
        assertEquals(ConversationEffect.StartTimer(Timer.QUESTION, 2, GoalRules.QUESTION_MILLIS), stopped.effect<ConversationEffect.StartTimer>())
        assertEquals(ConversationEffect.AnswerQuestion(4, ChannelAnswer.Silence), step(stopped.state, ConversationEvent.TimerEnded(Timer.QUESTION, 2)).effect<ConversationEffect.AnswerQuestion>())
        val again = step(stopped.state, ConversationEvent.Voice(on = true)).state
        assertTrue(step(again, ConversationEvent.TimerEnded(Timer.QUESTION, 2)).effects.isEmpty())
    }

    @Test
    fun aGoalThatEndsForGoodEndsItsRecordAndAReplyThatWasSaidLetsTheClockRun() {
        val final = ended(acting(), TaskReport(EndReason.FINISHED), "Wi-Fi is on.")
        assertEquals(ConversationEffect.EndRecord(1, EndReason.FINISHED, "Wi-Fi is on."), final.effect<ConversationEffect.EndRecord>())
        assertEquals(listOf(ChannelOutput.Report(1, "Wi-Fi is on.")), final.outputs())

        // After the routine offer, the report was said before it, and its record keeps it.
        val offered = step(acting(), ConversationEvent.GoalEnded(1, TaskReport(EndReason.FINISHED), answer = "Wi-Fi is on.", reply = null))
        assertEquals(ConversationEffect.EndRecord(1, EndReason.FINISHED, "Wi-Fi is on."), offered.effect<ConversationEffect.EndRecord>())
        assertTrue(offered.outputs().isEmpty())
        assertTrue(offered.state.quiet is QuietClock.Running)
    }

    @Test
    fun theEventsAndEffectsPrintNoWords() {
        val marker = "Mila-7Q"
        val input = OwnerInput(1, InputChannel.TEXT, InputIntent.Command(marker), 0)
        val values = listOf(
            ConversationEvent.GoalEnded(1, TaskReport(EndReason.FINISHED, summary = marker), marker, marker),
            ConversationEvent.Switched(null, marker),
            ConversationEvent.LocalEnded(1, LocalOutcome.Reply(marker)),
            ConversationEvent.LocalEnded(1, LocalOutcome.Deleted(marker)),
            ConversationEffect.StartGoal(1, 1, input, marker),
            ConversationEffect.ResumeGoal(1, ResumeCause.LIMIT, ConversationEffect.Steering(marker, InputChannel.TEXT), input),
            ConversationEffect.SteerGoal(marker, InputChannel.TEXT),
            ConversationEffect.CancelGoal(EndReason.CANCELLED, marker),
            ConversationEffect.AnswerQuestion(1, ChannelAnswer.Text(marker)),
            ConversationEffect.Output(ChannelOutput.Said(marker)),
            ConversationEffect.Output(ChannelOutput.Shown(marker)),
            ConversationEffect.Output(ChannelOutput.Report(1, marker)),
            ConversationEffect.AddLine(1, LineKind.NOTE, marker, null),
            ConversationEffect.EndRecord(1, EndReason.FINISHED, marker),
            ConversationEffect.RunLocal(1, 1, LocalRequest.Remember(marker, marker), input),
            ConversationEffect.RunLocal(1, null, LocalRequest.ContinuePast(marker), input),
            ConversationEffect.SwitchTo(1, other, input),
            ChannelQuestion(1, marker, QuestionKind.YES_NO, marker),
            ConversationState(
                heldReport = ChannelOutput.Report(1, marker),
                heldWords = ChannelOutput.Said(marker),
                continueAnswer = input,
                local = LocalRun(1, null, input),
                next = input,
            ),
        )

        for (value in values) assertFalse(value.javaClass.simpleName, value.toString().contains(marker))
    }
}
