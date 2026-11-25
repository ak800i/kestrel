package dev.kestrel.conversation.machine

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Interruption
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.goal.ResumeCause
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.DialogueEvent
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.local.LocalOutcome
import dev.kestrel.conversation.local.LocalRequest
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationSettings
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.GoalState
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.PhoneSignal
import dev.kestrel.conversation.model.ProcessEnd
import dev.kestrel.conversation.model.RoundEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an input does in each situation (design 6.4 of sub-project 7), the conversation's open and close, restore and switch. */
class ConversationMachineTest : MachineTesting() {
    @Test
    fun aCommandOpensAConversationWithTheCurrentTestModeAndStartsAGoal() {
        val first = step(idle.copy(safetyTestMode = true), typed("Turn on Wi-Fi"))

        assertEquals(id, first.state.id)
        assertTrue(first.state.testMode)
        assertEquals(1, first.state.commands)
        val start = first.effect<ConversationEffect.StartGoal>()
        assertEquals(1, start.command)
        assertEquals(1, start.record)
        assertEquals("Turn on Wi-Fi", start.text)
        assertEquals(GoalPhase.ACTING, first.state.goal!!.phase)
        assertTrue(first.has<ConversationEffect.Persist>())

        val second = step(step(ended(first.state).state, ConversationEvent.ReplyShown(1)).state, spoken("And Bluetooth"))
        assertEquals(2, second.effect<ConversationEffect.StartGoal>().record)
        assertEquals(id, second.state.id)
    }

    @Test
    fun stopAndContinueWithoutAGoal() {
        assertTrue(step(idle, typed(InputIntent.Stop)).effects.isEmpty())
        val nothing = step(idle, typed(InputIntent.Continue))
        assertEquals(listOf(ChannelOutput.Said(ConversationTexts.NOTHING_TO_CONTINUE)), nothing.outputs())
        assertNull(nothing.state.id)

        val open = idleOpen()
        assertTrue(step(open, typed(InputIntent.Stop)).effects.isEmpty())
        assertEquals(listOf(ChannelOutput.Said(ConversationTexts.NOTHING_TO_CONTINUE)), step(open, typed(InputIntent.Continue)).outputs())
    }

    @Test
    fun aCommandWhileTheGoalActsSteersIt() {
        val steered = step(acting(), spoken("Make it 7:30 instead"))

        assertEquals(ConversationEffect.SteerGoal("Make it 7:30 instead", InputChannel.VOICE), steered.effect<ConversationEffect.SteerGoal>())
        // "Got it." is the engine's, once the goal's inbox took the message (review of Task 10: I3).
        assertTrue(steered.outputs().isEmpty())
        assertFalse(steered.has<ConversationEffect.StartGoal>())
    }

    // "Stop and start new" (design 6.8): the step that ends the goal records its end before it closes the conversation.
    @Test
    fun aStopAndANewConversationRecordTheGoalsEndBeforeTheClose() {
        val stopping = step(step(acting(), typed(InputIntent.Stop)).state, typed(InputIntent.NewConversation)).state

        val ended = ended(stopping, TaskReport(EndReason.CANCELLED), "Cancelled.")

        val end = ended.effects.indexOf(ConversationEffect.EndRecord(id, 1, EndReason.CANCELLED, "Cancelled."))
        assertTrue(end >= 0 && end < ended.effects.indexOf(ConversationEffect.Close(id)))
        assertNull(ended.state.id)
    }

    @Test
    fun aCommandWhileTheGoalAsksAnswersIt() {
        val asking = asked(acting(), 4, QuestionKind.FREE)
        assertEquals(GoalPhase.ASKING, asking.goal!!.phase)

        assertEquals(ConversationEffect.AnswerQuestion(4, ChannelAnswer.Text("Mila"), leftScreen = true), step(asking, typed("Mila")).effect<ConversationEffect.AnswerQuestion>())
        assertEquals(ConversationEffect.AnswerQuestion(4, ChannelAnswer.Yes, leftScreen = true), step(asking, typed(InputIntent.Answer(ChannelAnswer.Yes))).effect<ConversationEffect.AnswerQuestion>())
        assertEquals(
            ConversationEffect.AnswerQuestion(4, ChannelAnswer.Silence),
            step(asking, spoken(InputIntent.Answer(ChannelAnswer.Silence))).effect<ConversationEffect.AnswerQuestion>(),
        )
        assertTrue(step(asking, typed(InputIntent.Continue)).effects.isEmpty())

        val answered = step(asking, ConversationEvent.Dialogue(DialogueEvent.Answered(4))).state
        assertEquals(GoalPhase.ACTING, answered.goal!!.phase)
        assertNull(answered.question)
    }

    // Plan 7F: a button on the screen's copy of a card names its question, so that a tap that came late answers no other.
    @Test
    fun anAnswerThatNamesAnotherQuestionAnswersNothing() {
        val asking = asked(acting(), 4, QuestionKind.YES_NO)

        assertEquals(
            ConversationEffect.AnswerQuestion(4, ChannelAnswer.Yes, leftScreen = true),
            step(asking, typed(InputIntent.Answer(ChannelAnswer.Yes, question = 4))).effect<ConversationEffect.AnswerQuestion>(),
        )
        assertFalse(step(asking, typed(InputIntent.Answer(ChannelAnswer.Yes, question = 3))).has<ConversationEffect.AnswerQuestion>())
    }

    @Test
    fun duringAHandOverACommandWaitsContinueEndsItAndStopEndsTheGoal() {
        val handed = step(acting(), ConversationEvent.Dialogue(DialogueEvent.HandedOver(7))).state
        assertEquals(GoalPhase.HANDED_OVER, handed.goal!!.phase)

        assertEquals(listOf(ChannelOutput.Said(ConversationTexts.FINISH_HAND_OVER_TYPED)), step(handed, typed("Send it")).outputs())
        assertEquals(listOf(ChannelOutput.Said(ConversationTexts.FINISH_HAND_OVER_SPOKEN)), step(handed, spoken("Send it")).outputs())
        val continued = step(handed, spoken(InputIntent.Continue))
        assertEquals(ConversationEffect.EndHandOver(7, HandOverEnd.CONTINUED), continued.effect<ConversationEffect.EndHandOver>())
        assertEquals(
            ConversationEffect.AddLine(id, 1, LineKind.OWNER_CONTINUED, ConversationTexts.CONTINUE_WORD, InputChannel.VOICE),
            continued.effect<ConversationEffect.AddLine>(),
        )
        assertEquals(GoalPhase.ACTING, step(continued.state, ConversationEvent.Dialogue(DialogueEvent.HandOverDone(7))).state.goal!!.phase)
        assertEquals(ConversationEffect.CancelGoal(EndReason.CANCELLED), step(handed, typed(InputIntent.Stop)).effect<ConversationEffect.CancelGoal>())
    }

    @Test
    fun whilePausedACommandIsToldToAnswerFirstAndContinueResumesWithAFreshLook() {
        val back = step(step(acting(), ConversationEvent.Signal(PhoneSignal.LOCKED)).state, ConversationEvent.Signal(PhoneSignal.UNLOCKED)).state

        assertEquals(listOf(ChannelOutput.Said(ConversationTexts.ANSWER_CONTINUE_FIRST)), step(back, typed("Send it")).outputs())
        val resumed = step(back, spoken(InputIntent.Continue))
        assertTrue(resumed.has<ConversationEffect.ResumePausedGoal>())
        assertTrue(resumed.has<ConversationEffect.CancelContinueQuestion>())
        val goal = checkNotNull(resumed.state.goal)
        assertEquals(GoalPhase.ACTING, goal.phase)
        assertNull(goal.hold)
    }

    @Test
    fun aGoalHeldAtALimitOrInterruptedResumesInANewRound() {
        val limited = ended(acting(), TaskReport(EndReason.PAUSED_AT_LIMIT, steps = 500), "I stopped at the limit because you didn't answer.").state
        val held = checkNotNull(limited.goal)
        assertEquals(GoalPhase.PAUSED_AT_LIMIT, held.phase)
        assertEquals(500, held.stepsBefore)

        val go = typed(InputIntent.Continue)
        val byContinue = step(limited, go)
        assertEquals(ConversationEffect.ResumeGoal(2, ResumeCause.LIMIT, null, go.input), byContinue.effect<ConversationEffect.ResumeGoal>())
        val resumed = checkNotNull(byContinue.state.goal)
        assertEquals(2, resumed.rounds)
        assertEquals(2, resumed.command)
        assertEquals(500, resumed.stepsBefore)

        val interrupted = ended(acting(), TaskReport(EndReason.INTERRUPTED, interruption = Interruption.TOUCH), "I stopped.").state
        val also = typed("Also send it to Mila")
        val byCommand = step(interrupted, also)
        assertEquals(
            ConversationEffect.ResumeGoal(2, ResumeCause.INTERRUPTION, ConversationEffect.Steering("Also send it to Mila", InputChannel.TEXT), also.input),
            byCommand.effect<ConversationEffect.ResumeGoal>(),
        )
        assertFalse(byCommand.has<ConversationEffect.AddLine>())
    }

    @Test
    fun stopEndsTheGoalInEveryPhaseWithItsReport() {
        val cancel = ConversationEffect.CancelGoal(EndReason.CANCELLED)
        assertEquals(cancel, step(acting(), typed(InputIntent.Stop)).effect<ConversationEffect.CancelGoal>())
        assertEquals(cancel, step(asked(acting(), 2, QuestionKind.FREE), typed(InputIntent.Stop)).effect<ConversationEffect.CancelGoal>())
        assertEquals(cancel, step(step(acting(), ConversationEvent.Signal(PhoneSignal.LOCKED)).state, spoken(InputIntent.Stop)).effect<ConversationEffect.CancelGoal>())

        val held = ended(acting(), TaskReport(EndReason.PAUSED_AT_LIMIT), "Limit.").state
        val stopped = step(held, typed(InputIntent.Stop))
        assertEquals(ConversationEffect.EndHeldGoal(EndReason.CANCELLED), stopped.effect<ConversationEffect.EndHeldGoal>())

        val report = ended(stopped.state, TaskReport(EndReason.CANCELLED), "Cancelled. I opened Viber.")
        assertEquals(listOf(ChannelOutput.Report(1, "Cancelled. I opened Viber.")), report.outputs())
        assertEquals(ConversationEffect.EndRecord(id, 1, EndReason.CANCELLED, "Cancelled. I opened Viber."), report.effect<ConversationEffect.EndRecord>())
        assertNull(report.state.goal)
    }

    @Test
    fun stopWhileTheGoalAsksClosesItsQuestionSoThatTheNextCommandWaitsAndRuns() {
        // "Stop. Kestrel, turn on Bluetooth" while the goal asks: the command is no answer to the question that "stop" ended.
        val stopped = step(asked(acting(), 4, QuestionKind.FREE), spoken(InputIntent.Stop))
        assertEquals(ConversationEffect.CancelGoal(EndReason.CANCELLED), stopped.effect<ConversationEffect.CancelGoal>())
        assertNull(stopped.state.question)

        val command = spoken("Turn on Bluetooth")
        val waiting = step(stopped.state, command)
        assertFalse(waiting.has<ConversationEffect.AnswerQuestion>())
        assertEquals(listOf(command.input), waiting.state.waiting)

        val late = step(waiting.state, ConversationEvent.Dialogue(DialogueEvent.Answered(4)))
        assertTrue(late.effects.isEmpty())
        assertEquals(waiting.state, late.state)

        assertEquals("Turn on Bluetooth", ended(late.state, TaskReport(EndReason.CANCELLED), "Cancelled.").effect<ConversationEffect.StartGoal>().text)
    }

    @Test
    fun newConversationAndContinuingAreRefusedWhileAGoalIsOpen() {
        val goal = acting()
        val own = listOf(
            InputIntent.NewConversation,
            InputIntent.ContinuePast("the trip"),
            InputIntent.ContinueConversation(other),
            InputIntent.Remember("the gate code", "4321"),
            InputIntent.Forget("the gate code"),
            InputIntent.SaveRoutine("evening"),
        )

        for (intent in own) assertEquals(intent.toString(), listOf(ChannelOutput.Said(ConversationTexts.STOP_GOAL_FIRST)), step(goal, typed(intent)).outputs())
        assertTrue(step(goal, typed(InputIntent.DeleteHistory)).effects.isEmpty())
    }

    @Test
    fun theEndOfAnOlderCommandsGoalChangesNothing() {
        val second = step(idleOpen(), typed("Turn on Bluetooth")).state
        assertEquals(2, second.goal!!.command)

        val late = step(second, ConversationEvent.GoalEnded(RoundEnd(1, TaskReport(EndReason.CANCELLED), "Cancelled.", "Cancelled.")))

        assertTrue(late.effects.isEmpty())
        assertEquals(second, late.state)
    }

    @Test
    fun newConversationClosesTheOpenOne() {
        val fresh = step(idleOpen(), typed(InputIntent.NewConversation))

        assertEquals(ConversationEffect.Close(id), fresh.effect<ConversationEffect.Close>())
        assertNull(fresh.state.id)
        assertEquals(listOf(ChannelOutput.Report(2, ConversationTexts.NEW_CONVERSATION)), fresh.outputs())
        assertEquals(listOf(ChannelOutput.Report(1, ConversationTexts.NEW_CONVERSATION)), step(idle, spoken(InputIntent.NewConversation)).outputs())
    }

    @Test
    fun stopThenACommandRunsTheNewOneAfterTheOldOneEnds() {
        val stopped = step(acting(), spoken(InputIntent.Stop)).state
        val waiting = step(stopped, spoken("Turn on Bluetooth"))
        assertFalse(waiting.has<ConversationEffect.StartGoal>())

        val next = ended(waiting.state, TaskReport(EndReason.CANCELLED), "Cancelled. I opened Settings.")

        assertEquals(listOf(ChannelOutput.Shown("Cancelled. I opened Settings.")), next.outputs())
        val start = next.effect<ConversationEffect.StartGoal>()
        assertEquals(2, start.record)
        assertEquals("Turn on Bluetooth", start.text)
    }

    @Test
    fun stopThenKestrelsOwnRequestRunsItAfterTheGoalEnds() {
        val stopped = step(acting(), spoken(InputIntent.Stop)).state

        val forgetting = step(stopped, spoken(InputIntent.Forget("the gate code")))
        assertTrue(forgetting.effects.none { it is ConversationEffect.Output || it is ConversationEffect.RunLocal })
        val forgot = ended(forgetting.state, TaskReport(EndReason.CANCELLED), "Cancelled.")
        assertEquals(LocalRequest.Forget("the gate code"), forgot.effect<ConversationEffect.RunLocal>().request)

        val deleting = step(stopped, spoken(InputIntent.DeleteHistory))
        assertEquals(LocalRequest.DeleteHistory, ended(deleting.state, TaskReport(EndReason.CANCELLED), "Cancelled.").effect<ConversationEffect.RunLocal>().request)
    }

    @Test
    fun deleteHistoryRunsWithoutAGoalAndClosesTheConversationAfterYes() {
        val deleting = step(idleOpen(), typed(InputIntent.DeleteHistory))
        val run = deleting.effect<ConversationEffect.RunLocal>()
        assertEquals(LocalRequest.DeleteHistory, run.request)
        assertNull(run.record)
        assertTrue(step(deleting.state, typed(InputIntent.Stop)).has<ConversationEffect.CancelLocal>())

        val deleted = step(deleting.state, ConversationEvent.LocalEnded(run.command, LocalOutcome.Deleted(ConversationTexts.HISTORY_DELETED)))

        assertEquals(ConversationEffect.Close(id, keep = false), deleted.effect<ConversationEffect.Close>())
        assertNull(deleted.state.id)
        assertEquals(listOf(ChannelOutput.Report(run.command, ConversationTexts.HISTORY_DELETED)), deleted.outputs())
    }

    @Test
    fun aMemoryRequestOpensAConversationAndGetsARecord() {
        val remembering = step(idle, spoken(InputIntent.Remember("the gate code", "4321")))
        val run = remembering.effect<ConversationEffect.RunLocal>()
        assertEquals(id, remembering.state.id)
        assertEquals(1, run.record)
        assertEquals(LocalRequest.Remember("the gate code", "4321"), run.request)

        val waiting = step(remembering.state, spoken("Turn on Wi-Fi"))
        assertFalse(waiting.has<ConversationEffect.StartGoal>())

        val done = step(waiting.state, ConversationEvent.LocalEnded(run.command, LocalOutcome.Reply(ConversationTexts.REMEMBERED)))
        assertEquals(ConversationEffect.EndRecord(id, 1, EndReason.FINISHED, ConversationTexts.REMEMBERED), done.effect<ConversationEffect.EndRecord>())
        assertEquals(listOf(ChannelOutput.Report(run.command, ConversationTexts.REMEMBERED)), done.outputs())
        assertEquals(2, done.effect<ConversationEffect.StartGoal>().record)
    }

    @Test
    fun aMemoryRequestThatStopCancelledEndsItsRecordAsCancelled() {
        val remembering = step(idleOpen(), spoken(InputIntent.Remember("the gate code", "4321")))
        val run = remembering.effect<ConversationEffect.RunLocal>()
        val stopped = step(remembering.state, spoken(InputIntent.Stop))
        assertTrue(stopped.has<ConversationEffect.CancelLocal>())

        val cancelled = step(stopped.state, ConversationEvent.LocalEnded(run.command, LocalOutcome.Quiet))
        assertEquals(ConversationEffect.EndRecord(id, 2, EndReason.CANCELLED, null), cancelled.effect<ConversationEffect.EndRecord>())
        assertTrue(cancelled.outputs().isEmpty())

        // A reply that the request gave before the stop reached it is its own end: it had finished.
        val replied = step(stopped.state, ConversationEvent.LocalEnded(run.command, LocalOutcome.Reply(ConversationTexts.REMEMBERED)))
        assertEquals(ConversationEffect.EndRecord(id, 2, EndReason.FINISHED, ConversationTexts.REMEMBERED), replied.effect<ConversationEffect.EndRecord>())
    }

    // "Stop. Kestrel, …" while Kestrel's own request asks (design 6.4; review of plan 7D's Task 3, M1): the stop cancels
    // the request and withdraws its question at once, so the command after it answers nothing and runs once the request
    // has ended.
    @Test
    fun stopThenACommandWhileKestrelsOwnRequestAsksRunsTheCommandAfterTheRequest() {
        val deleting = step(idleOpen(), spoken(InputIntent.DeleteHistory))
        val run = deleting.effect<ConversationEffect.RunLocal>()
        val asking = asked(deleting.state, 7, QuestionKind.DELETE_HISTORY)

        val stopped = step(asking, spoken(InputIntent.Stop))
        assertTrue(stopped.has<ConversationEffect.CancelLocal>())
        assertNull(stopped.state.question)

        val commanded = step(stopped.state, spoken("Open Viber"))
        assertTrue(commanded.effects.none { it is ConversationEffect.AnswerQuestion || it is ConversationEffect.StartGoal })

        val ended = step(commanded.state, ConversationEvent.LocalEnded(run.command, LocalOutcome.Quiet))
        assertEquals("Open Viber", ended.effect<ConversationEffect.StartGoal>().text)
    }

    // A change of test mode waits for a running memory request, as it waits for a goal: the conversation closes once the
    // request's end is recorded, and a command that waited runs in a new conversation under the new test mode.
    @Test
    fun aChangeOfTestModeWaitsForARunningMemoryRequest() {
        val remembering = step(idleOpen(), spoken(InputIntent.Remember("the gate code", "4321")))
        val run = remembering.effect<ConversationEffect.RunLocal>()
        val changed = step(remembering.state, ConversationEvent.SettingsChanged(ConversationSettings(), testMode = true))
        assertFalse(changed.has<ConversationEffect.Close>())
        assertEquals(id, changed.state.id)
        val waiting = step(changed.state, spoken("Turn on Wi-Fi")).state

        val done = step(waiting, ConversationEvent.LocalEnded(run.command, LocalOutcome.Reply(ConversationTexts.REMEMBERED)))

        val end = done.effects.indexOf(ConversationEffect.EndRecord(id, 2, EndReason.FINISHED, ConversationTexts.REMEMBERED))
        assertTrue(end >= 0 && end < done.effects.indexOf(ConversationEffect.Close(id)))
        assertEquals(1, done.effect<ConversationEffect.StartGoal>().record)
        assertTrue(done.state.testMode)
    }

    // A test mode that changes back to the conversation's while its work runs closes nothing: the work ends as it would
    // have, also a goal that the change stopped. A further change of a setting while the close waits keeps what waits.
    @Test
    fun aTestModeChangedBackWhileWorkRunsKeepsTheConversation() {
        val remembering = step(idleOpen(), spoken(InputIntent.Remember("the gate code", "4321")))
        val run = remembering.effect<ConversationEffect.RunLocal>()
        val changed = step(remembering.state, ConversationEvent.SettingsChanged(ConversationSettings(), testMode = true)).state
        val back = step(changed, ConversationEvent.SettingsChanged(ConversationSettings(), testMode = false)).state
        assertFalse(back.closeAfterWork)
        val done = step(back, ConversationEvent.LocalEnded(run.command, LocalOutcome.Reply(ConversationTexts.REMEMBERED)))
        assertFalse(done.has<ConversationEffect.Close>())
        assertEquals(id, done.state.id)

        val stopping = step(acting(), ConversationEvent.SettingsChanged(ConversationSettings(), testMode = true)).state
        val ended = ended(step(stopping, ConversationEvent.SettingsChanged(ConversationSettings(), testMode = false)).state, TaskReport(EndReason.CANCELLED), "Cancelled.")
        assertFalse(ended.has<ConversationEffect.Close>())
        assertEquals(id, ended.state.id)
        assertEquals(listOf(ChannelOutput.Report(1, "Cancelled.")), ended.outputs())

        val waiting = step(stopping, spoken("Turn on Bluetooth")).state
        val quieter = step(waiting, ConversationEvent.SettingsChanged(ConversationSettings(quietSeconds = 120), testMode = true))
        assertTrue(quieter.effects.isEmpty())
        assertEquals(waiting.waiting, quieter.state.waiting)
    }

    // "New conversation" numbers a command, and its end names the input, for the hooks' outcome (review of plan 7B: M2).
    @Test
    fun aNewConversationEndsItsCommandWithItsInput() {
        val asked = typed(InputIntent.NewConversation)

        val renewed = step(idleOpen(), asked)

        assertEquals(
            ConversationEffect.EndLocal(renewed.state.command, EndReason.FINISHED, ConversationTexts.NEW_CONVERSATION, asked.input),
            renewed.effect<ConversationEffect.EndLocal>(),
        )
    }

    // A "stop" after the owner's yes to a switch, or while the store switches, cancels the switch: the conversation that was
    // open stays as it was, and a switch that the store made is undone (review of plan 7B: M3).
    @Test
    fun aStopCancelsASwitchAfterTheYesAndWhileItRuns() {
        val open = idleOpen()
        val asking = step(open, spoken(InputIntent.ContinuePast("the trip")))
        val run = asking.effect<ConversationEffect.RunLocal>()
        val stopped = step(asking.state, spoken(InputIntent.Stop)).state

        val afterYes = step(stopped, ConversationEvent.LocalEnded(run.command, LocalOutcome.Switch(other)))

        assertFalse(afterYes.has<ConversationEffect.SwitchTo>())
        assertEquals(EndReason.CANCELLED, afterYes.effect<ConversationEffect.EndLocal>().end)
        assertEquals(id, afterYes.state.id)
        assertNull(afterYes.state.local)

        val switching = step(open, typed(InputIntent.ContinueConversation(other)))
        val during = step(switching.state, typed(InputIntent.Stop))
        assertTrue(during.has<ConversationEffect.CancelLocal>())
        val past = ConversationSnapshot(other, 5_000, now, open = true, testMode = false)
        val switched = step(during.state, ConversationEvent.Switched(past, "We're back in the conversation from Friday, 20 November."))

        assertTrue(switched.has<ConversationEffect.UndoSwitch>())
        assertFalse(switched.has<ConversationEffect.OpenSwitched>())
        assertEquals(EndReason.CANCELLED, switched.effect<ConversationEffect.EndLocal>().end)
        assertEquals(id, switched.state.id)
        assertTrue(switched.outputs().isEmpty())
    }

    // Commands that wait are kept in order, and each runs in its turn (review of plan 7B: M4).
    @Test
    fun everyCommandThatWaitsRunsInOrder() {
        val remembering = step(idleOpen(), spoken(InputIntent.Remember("the gate code", "4321")))
        val run = remembering.effect<ConversationEffect.RunLocal>()
        val two = step(step(remembering.state, spoken("Turn on Wi-Fi")).state, spoken("Make it the guest network")).state
        assertEquals(2, two.waiting.size)

        val done = step(two, ConversationEvent.LocalEnded(run.command, LocalOutcome.Reply(ConversationTexts.REMEMBERED)))

        assertEquals("Turn on Wi-Fi", done.effect<ConversationEffect.StartGoal>().text)
        assertEquals(ConversationEffect.SteerGoal("Make it the guest network", InputChannel.VOICE), done.effect<ConversationEffect.SteerGoal>())
        assertTrue(done.state.waiting.isEmpty())
    }

    // Final replies that wait for the owner are all given when he is back, oldest first.
    @Test
    fun everyFinalReplyThatWaitsIsGivenInOrder() {
        val first = ended(step(acting(), ConversationEvent.Signal(PhoneSignal.LOCKED)).state).state
        val second = ended(step(first, typed("Turn on Bluetooth")).state, sentence = "Bluetooth is on.").state
        assertEquals(2, second.heldReports.size)

        val back = step(second, ConversationEvent.Signal(PhoneSignal.UNLOCKED))

        assertEquals(listOf(ChannelOutput.Report(1, "Done."), ChannelOutput.Report(2, "Bluetooth is on.")), back.outputs())
        assertTrue(back.state.heldReports.isEmpty())
    }

    // A memory request's record names its own conversation, so that its end never needs an open one.
    @Test
    fun aMemoryRequestEndsAlsoWithoutAnOpenConversation() {
        val remembering = step(idleOpen(), spoken(InputIntent.Remember("the gate code", "4321")))
        val run = remembering.effect<ConversationEffect.RunLocal>()

        val done = step(remembering.state.copy(id = null), ConversationEvent.LocalEnded(run.command, LocalOutcome.Reply(ConversationTexts.REMEMBERED)))

        assertNull(done.state.local)
        assertEquals(ConversationEffect.EndRecord(id, 2, EndReason.FINISHED, ConversationTexts.REMEMBERED), done.effect<ConversationEffect.EndRecord>())
    }

    @Test
    fun continuingAPastConversationFromNoneAndFromAnOpenOne() {
        val about = spoken(InputIntent.ContinuePast("the trip"))
        val asking = step(idle, about)
        val run = asking.effect<ConversationEffect.RunLocal>()
        assertEquals(LocalRequest.ContinuePast("the trip"), run.request)
        assertEquals(about.input, run.input)
        assertNull(asking.state.id)

        val switching = step(asking.state, ConversationEvent.LocalEnded(run.command, LocalOutcome.Switch(other)))
        assertEquals(ConversationEffect.SwitchTo(run.command, other, about.input), switching.effect<ConversationEffect.SwitchTo>())
        val past = ConversationSnapshot(
            other, 5_000, now, open = true, testMode = true,
            records = listOf(CommandRecord(1, 5_000, InputChannel.TEXT, "Plan the trip")), continuedAtWall = now,
        )
        val back = "We're back in the conversation from Friday, 20 November."
        val switched = step(switching.state, ConversationEvent.Switched(past, back))

        assertEquals(other, switched.state.id)
        assertFalse(switched.state.testMode)
        assertEquals(now, switched.state.continuedAtWall)
        assertEquals(1, switched.state.commands)
        assertEquals(listOf(ChannelOutput.Report(run.command, back)), switched.outputs())

        val chosen = typed(InputIntent.ContinueConversation(other))
        val fromOpen = step(idleOpen(), chosen)
        assertEquals(ConversationEffect.SwitchTo(2, other, chosen.input), fromOpen.effect<ConversationEffect.SwitchTo>())
        assertFalse(fromOpen.has<ConversationEffect.Close>())
    }

    @Test
    fun aConversationThatIsGoneCannotBeContinued() {
        val switching = step(idle, typed(InputIntent.ContinueConversation(other)))

        val gone = step(switching.state, ConversationEvent.Switched(null, ConversationTexts.GONE))

        assertNull(gone.state.id)
        assertEquals(listOf(ChannelOutput.Report(1, ConversationTexts.GONE)), gone.outputs())
    }

    @Test
    fun aChangeOfTestModeClosesTheConversationAfterEndingItsGoal() {
        val closed = step(idleOpen(), ConversationEvent.SettingsChanged(ConversationSettings(), testMode = true))
        assertEquals(ConversationEffect.Close(id), closed.effect<ConversationEffect.Close>())

        val stopping = step(acting(), ConversationEvent.SettingsChanged(ConversationSettings(), testMode = true))
        assertEquals(ConversationEffect.CancelGoal(EndReason.CANCELLED), stopping.effect<ConversationEffect.CancelGoal>())
        assertTrue(stopping.state.closeAfterWork)

        val final = ended(stopping.state, TaskReport(EndReason.CANCELLED), "Cancelled.")
        assertEquals(listOf(ChannelOutput.Report(1, "Cancelled.")), final.outputs())
        assertEquals(ConversationEffect.Close(id), final.effect<ConversationEffect.Close>())
        assertNull(final.state.id)
        assertTrue(step(final.state, typed("Turn on Wi-Fi")).state.testMode)
    }

    @Test
    fun aCommandThatWaitedForTheTestModesStopRunsInANewConversation() {
        val stopping = step(acting(), ConversationEvent.SettingsChanged(ConversationSettings(), testMode = true)).state
        val waiting = step(stopping, spoken("Turn on Bluetooth")).state

        val next = ended(waiting, TaskReport(EndReason.CANCELLED), "Cancelled.")

        assertTrue(next.has<ConversationEffect.Close>())
        val start = next.effect<ConversationEffect.StartGoal>()
        assertEquals("Turn on Bluetooth", start.text)
        assertEquals(1, start.record)
        assertTrue(next.state.testMode)
        assertTrue(next.state.waiting.isEmpty())
    }

    @Test
    fun aRestoreWithoutAGoalKeepsTheConversationOpenOnlyWithinTheQuietTime() {
        val snapshot = ConversationSnapshot(
            id, now - 120_000, now - 40_000, open = true, testMode = true,
            records = listOf(CommandRecord(1, now - 120_000, InputChannel.VOICE, "Turn on Wi-Fi")), quietSinceWall = now - 40_000,
        )

        // The Safety page's test mode is on, as the conversation's was (design 12.4).
        val testing = idle.copy(safetyTestMode = true)
        val open = step(testing, ConversationEvent.Restored(snapshot))
        assertEquals(id, open.state.id)
        assertEquals(1, open.state.commands)
        assertTrue(open.state.testMode)
        assertEquals(QuietClock.Running(1, now - 40_000), open.state.quiet)
        assertEquals(ConversationEffect.StartTimer(Timer.QUIET, 1, 20_000), open.effect<ConversationEffect.StartTimer>())

        val late = step(testing, ConversationEvent.Restored(snapshot.copy(quietSinceWall = now - 61_000)))
        assertEquals(ConversationEffect.Close(id), late.effect<ConversationEffect.Close>())
        assertNull(late.state.id)
        assertTrue(step(testing, ConversationEvent.Restored(null)).effects.isEmpty())
    }

    @Test
    fun aRestoreWithAGoalHoldsItAsInterruptedAndAsksToContinue() {
        val snapshot = ConversationSnapshot(
            id, now - 600_000, now - 60_000, open = true, testMode = false,
            records = listOf(CommandRecord(1, now - 600_000, InputChannel.TEXT, "Archive the newsletters")),
            goal = GoalState(1, GoalPhase.ACTING, now - 60_000, stepsBefore = 7),
        )

        val held = step(idle, ConversationEvent.Restored(snapshot))
        val goal = checkNotNull(held.state.goal)
        assertEquals(GoalPhase.INTERRUPTED, goal.phase)
        assertEquals(GoalHold.RESTART, goal.hold)
        assertEquals(7, goal.stepsBefore)
        assertEquals(ConversationEffect.StartTimer(Timer.HOLD, 1, GoalRules.HOLD_MILLIS - 60_000), held.effect<ConversationEffect.StartTimer>())
        assertTrue(held.has<ConversationEffect.AskContinueGoal>())

        val yes = step(held.state, ConversationEvent.ContinueAnswered(ChannelAnswer.Yes))
        assertEquals(ConversationEffect.ResumeGoal(1, ResumeCause.RESTART, null, null), yes.effect<ConversationEffect.ResumeGoal>())

        // A yes that the owner said or typed resumes the goal for his input; only the card's yes has none.
        val said = spoken(InputIntent.Answer(ChannelAnswer.Yes))
        val answering = step(asked(held.state, 2, QuestionKind.CONTINUE_GOAL), said)
        assertEquals(ConversationEffect.AnswerQuestion(2, ChannelAnswer.Yes), answering.effect<ConversationEffect.AnswerQuestion>())
        val resumed = step(answering.state, ConversationEvent.ContinueAnswered(ChannelAnswer.Yes))
        assertEquals(ConversationEffect.ResumeGoal(1, ResumeCause.RESTART, null, said.input), resumed.effect<ConversationEffect.ResumeGoal>())
        assertNull(resumed.state.continueAnswer)
    }

    @Test
    fun aRestoredGoalIsHeldFromWhenItStoppedWorking() {
        // The goal worked from 40 minutes ago until the process ended; Kestrel last saved it 1 minute ago.
        val working = ConversationSnapshot(
            id, now - 3_600_000, now - 60_000, open = true, testMode = false,
            records = listOf(CommandRecord(1, now - 3_600_000, InputChannel.TEXT, "Archive the newsletters")),
            goal = GoalState(1, GoalPhase.ACTING, now - 2_400_000),
        )

        val held = step(idle, ConversationEvent.Restored(working))
        val goal = checkNotNull(held.state.goal)
        assertEquals(GoalPhase.INTERRUPTED, goal.phase)
        assertEquals(now - 60_000, goal.changedAtWall)
        assertEquals(ConversationEffect.StartTimer(Timer.HOLD, 1, GoalRules.HOLD_MILLIS - 60_000), held.effect<ConversationEffect.StartTimer>())
        assertTrue(held.has<ConversationEffect.AskContinueGoal>())
        assertTrue(held.has<ConversationEffect.Persist>())

        // A goal that was held already is held from when its hold began, here 40 minutes ago.
        val paused = working.copy(goal = GoalState(1, GoalPhase.PAUSED, now - 2_400_000, GoalHold.LOCK))
        val late = step(idle, ConversationEvent.Restored(paused))
        assertEquals(ConversationEffect.EndHeldGoal(EndReason.PAUSED_TOO_LONG), late.effect<ConversationEffect.EndHeldGoal>())
        assertFalse(late.has<ConversationEffect.AskContinueGoal>())
    }

    @Test
    fun aRestoreAsksContinueTheGoalOnlyOnceTheOwnerIsThere() {
        val snapshot = ConversationSnapshot(
            id, now - 600_000, now - 60_000, open = true, testMode = false,
            records = listOf(CommandRecord(1, now - 600_000, InputChannel.TEXT, "Archive the newsletters")),
            goal = GoalState(1, GoalPhase.ACTING, now - 60_000),
        )

        // Before PhoneSignals has said whether the phone is locked, the goal is held and nothing asks yet.
        val unknown = step(starting, ConversationEvent.Restored(snapshot))
        assertTrue(unknown.has<ConversationEffect.StartTimer>())
        assertFalse(unknown.has<ConversationEffect.AskContinueGoal>())
        assertTrue(step(unknown.state, ConversationEvent.Signal(PhoneSignal.UNLOCKED)).has<ConversationEffect.AskContinueGoal>())

        val locked = step(step(starting, ConversationEvent.Signal(PhoneSignal.LOCKED)).state, ConversationEvent.Restored(snapshot))
        assertFalse(locked.has<ConversationEffect.AskContinueGoal>())
        assertTrue(step(locked.state, ConversationEvent.Signal(PhoneSignal.UNLOCKED)).has<ConversationEffect.AskContinueGoal>())
    }

    @Test
    fun aRestoreUnderAnotherTestModeClosesTheConversationAfterItsGoal() {
        // The engine gives the Safety page's test mode (`SettingsChanged`) before it restores; here it is off (design 12.4).
        val snapshot = ConversationSnapshot(
            id, now - 600_000, now - 10_000, open = true, testMode = true,
            records = listOf(CommandRecord(1, now - 600_000, InputChannel.TEXT, "Turn on Wi-Fi")), quietSinceWall = now - 10_000,
        )

        val closed = step(idle, ConversationEvent.Restored(snapshot))
        assertEquals(ConversationEffect.Close(id), closed.effect<ConversationEffect.Close>())
        assertNull(closed.state.id)
        assertFalse(closed.state.testMode)

        val ending = step(idle, ConversationEvent.Restored(snapshot.copy(goal = GoalState(1, GoalPhase.ACTING, now - 10_000))))
        assertEquals(ConversationEffect.EndHeldGoal(EndReason.CANCELLED), ending.effect<ConversationEffect.EndHeldGoal>())
        assertFalse(ending.has<ConversationEffect.AskContinueGoal>())
        assertTrue(ending.state.closeAfterWork)

        val final = ended(ending.state, TaskReport(EndReason.CANCELLED), "Cancelled.")
        assertEquals("Cancelled.", (final.outputs().single() as ChannelOutput.Report).text)
        assertEquals(ConversationEffect.Close(id), final.effect<ConversationEffect.Close>())
        assertFalse(final.state.testMode)
    }

    @Test
    fun aReportThatTheRestoreCausesWaitsUntilTheOwnerIsThere() {
        // Kestrel starts on a locked phone whose state PhoneSignals has not yet given.
        val snapshot = ConversationSnapshot(
            id, now - 3_600_000, now - 60_000, open = true, testMode = false,
            records = listOf(CommandRecord(1, now - 3_600_000, InputChannel.TEXT, "Archive the newsletters")),
            goal = GoalState(1, GoalPhase.PAUSED, now - 2_400_000, GoalHold.LOCK),
        )
        val late = step(starting, ConversationEvent.Restored(snapshot))
        assertEquals(ConversationEffect.EndHeldGoal(EndReason.PAUSED_TOO_LONG), late.effect<ConversationEffect.EndHeldGoal>())

        val report = "I stopped the task because it was paused for a long time."
        val kept = ended(late.state, TaskReport(EndReason.PAUSED_TOO_LONG), report)
        assertTrue(kept.outputs().isEmpty())
        assertTrue(step(kept.state, ConversationEvent.Signal(PhoneSignal.LOCKED)).outputs().isEmpty())
        assertEquals(listOf(ChannelOutput.Report(0, report)), step(kept.state, ConversationEvent.Signal(PhoneSignal.UNLOCKED)).outputs())

        // A goal that a change of test mode ends: the conversation closes at once, and the report waits.
        val other = step(starting, ConversationEvent.Restored(snapshot.copy(testMode = true, goal = GoalState(1, GoalPhase.ACTING, now - 60_000)))).state
        val closed = ended(other, TaskReport(EndReason.CANCELLED), "Cancelled.")
        assertTrue(closed.outputs().isEmpty())
        assertEquals(ConversationEffect.Close(id), closed.effect<ConversationEffect.Close>())
        assertEquals(listOf(ChannelOutput.Report(0, "Cancelled.")), step(closed.state, ConversationEvent.Signal(PhoneSignal.UNLOCKED)).outputs())
    }

    @Test
    fun theOwnersStopOfKestrelEndsTheRestoredGoalAsStopDoesAndAsksNothing() {
        val snapshot = ConversationSnapshot(
            id, now - 600_000, now - 60_000, open = true, testMode = false,
            records = listOf(CommandRecord(1, now - 600_000, InputChannel.TEXT, "Archive the newsletters")),
            goal = GoalState(1, GoalPhase.ACTING, now - 60_000),
        )

        // Plan 7E: the owner's stop in Android's "Active apps" after the snapshot ends the goal as "stop" does.
        val stopped = step(idle, ConversationEvent.Restored(snapshot, ProcessEnd.OwnerStopped(now - 30_000)))
        assertEquals(ConversationEffect.EndHeldGoal(EndReason.CANCELLED), stopped.effect<ConversationEffect.EndHeldGoal>())
        assertFalse(stopped.has<ConversationEffect.AskContinueGoal>())
        assertFalse(stopped.has<ConversationEffect.StartTimer>())

        // A stop before the snapshot ended an older process, and any other end holds the goal and asks.
        assertTrue(step(idle, ConversationEvent.Restored(snapshot, ProcessEnd.OwnerStopped(now - 90_000))).has<ConversationEffect.AskContinueGoal>())
        assertTrue(step(idle, ConversationEvent.Restored(snapshot, ProcessEnd.Other)).has<ConversationEffect.AskContinueGoal>())
    }

    @Test
    fun theLatestInputChoosesTheReplyChannel() {
        val goal = acting()

        assertEquals(InputChannel.VOICE, step(goal, spoken("Make it 8")).state.replyChannel)
        assertEquals(InputChannel.TEXT, step(step(goal, spoken("Make it 8")).state, typed("No, 9")).state.replyChannel)
    }
}
