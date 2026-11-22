package dev.kestrel.app.orchestrator

import dev.kestrel.voice.listen.Recognition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorTest {
    private val performed = mutableListOf<Effect>()
    private val errors = mutableListOf<Exception>()

    @Test
    fun anEventWhoseCheckFailsWhenItsTurnComesIsDroppedAndTheNextEventIsHandled() = runTest {
        var valid = true
        // The first effect makes the check false, as a turn-off makes the events of a closing session stale.
        val orchestrator = Orchestrator(backgroundScope, { performed += it; valid = false }, { errors += it })

        orchestrator.send(Event.Toggle)
        // The check is true now; it runs only when the event's turn comes, after the Toggle.
        orchestrator.send(Event.Heard("Kestrel, open the maps.", named = true)) { valid }
        orchestrator.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(listOf(Effect.RunTask(1, "open the settings.")), performed.filterIsInstance<Effect.RunTask>())
    }

    @Test
    fun theStepWatcherSeesEachEventWithTheStateBeforeItAndItsStep() = runTest {
        val seen = mutableListOf<Triple<Event, State, Step>>()
        val orchestrator = Orchestrator(
            backgroundScope,
            { performed += it },
            { errors += it },
            onStep = { event, before, step -> seen += Triple(event, before, step) },
        )
        val heard = Event.Heard("Scroll down.", named = false)

        orchestrator.send(Event.Toggle)
        orchestrator.send(heard)
        runCurrent()

        assertEquals(listOf(Event.Toggle, heard), seen.map { it.first })
        assertEquals(Mode.OFF, seen[0].second.mode)
        assertEquals(Mode.LISTENING, seen[1].second.mode)
        assertTrue(seen[1].third.effects.any { it is Effect.RunCommand })
        assertEquals(seen[1].third.state, orchestrator.state.value)
        assertEquals(emptyList<Exception>(), errors)
    }

    @Test
    fun appliesTheRulesToEachEventInTurn() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(Work.RUNNING_TASK, orchestrator.state.value.work)
        assertEquals(
            listOf(
                Effect.Show(Feedback.ModeChanged(Mode.LISTENING)),
                Effect.Show(Feedback.Heard("Kestrel, open the settings.")),
                Effect.RunTask(1, "open the settings."),
            ),
            performed,
        )
        assertEquals(emptyList<Exception>(), errors)
    }

    @Test
    fun anUnansweredQuestionEndsFifteenSecondsAfterItWasSpoken() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })
        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("tap Send", named = false))
        orchestrator.send(Event.CommandAsks(1, "Which one?"))
        runCurrent()
        // The question took 5 s to say; the 15 s count from its end.
        advanceTimeBy(5_000)
        orchestrator.send(Event.SpeechEnded)
        runCurrent()

        advanceTimeBy(14_999)
        runCurrent()
        assertTrue(orchestrator.state.value.asking)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(orchestrator.state.value.asking)
        assertEquals(Effect.AnswerCommand(1, null), performed.last())
    }

    @Test
    fun aCommandThatCannotStartEndsAsFailed() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { if (it is Effect.RunCommand) error("No runner") }, { errors += it })

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("tap Send", named = false))
        runCurrent()

        assertEquals(Work.IDLE, orchestrator.state.value.work)
        assertEquals(1, errors.size)
    }

    @Test
    fun aCommandThatCannotStartShowsWhyOnTheBar() = runTest {
        val orchestrator = Orchestrator(
            backgroundScope,
            { performed += it; if (it is Effect.RunCommand) error("No runner") },
            { errors += it },
            commandFailed = "The command failed.",
        )

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("tap Send", named = false))
        runCurrent()

        assertEquals(Effect.Show(Feedback.CommandText("The command failed.")), performed.last())
    }

    @Test
    fun theWaitAfterYesEndsAfterEightSeconds() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        assertEquals(Work.AWAITING_TASK, orchestrator.state.value.work)
        // The Orchestrator runs the timer itself.
        assertFalse(performed.any { it is Effect.WaitForTask })

        advanceTimeBy(7_999)
        runCurrent()
        assertEquals(Work.AWAITING_TASK, orchestrator.state.value.work)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(Work.IDLE, orchestrator.state.value.work)
    }

    @Test
    fun aTaskInTimeEndsTheWait() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        advanceTimeBy(5_000)
        orchestrator.send(Event.Heard("Open the settings.", named = false))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(Work.RUNNING_TASK, orchestrator.state.value.work)
    }

    @Test
    fun theNameAgainRestartsTheWait() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        advanceTimeBy(5_000)
        orchestrator.send(Event.SpeechEnded)
        orchestrator.send(Event.Heard("Kestrel", named = true))
        runCurrent()

        // The first wait would have ended at 8 s; the second one ends at 13 s.
        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(Work.AWAITING_TASK, orchestrator.state.value.work)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(Work.IDLE, orchestrator.state.value.work)
    }

    @Test
    fun theWaitCountsFromTheEndOfYesAndCoversAnAnswerThatHasStarted() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        advanceTimeBy(1_000)
        orchestrator.send(Event.SpeechEnded)
        runCurrent()

        // Counted from the transcript, the wait would end at 8 s; counted from the end of "Yes?", at 9 s.
        advanceTimeBy(7_500)
        runCurrent()
        assertEquals(Work.AWAITING_TASK, orchestrator.state.value.work)

        // The owner starts to answer at 8.5 s; the wait now ends at 43.5 s.
        orchestrator.send(Event.Speaking)
        runCurrent()
        advanceTimeBy(34_999)
        runCurrent()
        assertEquals(Work.AWAITING_TASK, orchestrator.state.value.work)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(Work.IDLE, orchestrator.state.value.work)
    }

    @Test
    fun thirtySecondsWithoutSpeechPauseKestrel() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })

        orchestrator.send(Event.Options(WakeUpMode.RESUME, pauseAfterSilence = true, callCommands = false))
        orchestrator.send(Event.Toggle)
        runCurrent()
        // The Orchestrator runs the timer itself.
        assertFalse(performed.any { it is Effect.TimeSilence })
        advanceTimeBy(20_000)
        orchestrator.send(Event.Speaking)
        runCurrent()

        // Counted from the start, the pause would come at 30 s; counted from the speech, at 50 s.
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(Mode.LISTENING, orchestrator.state.value.mode)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(Mode.PAUSED, orchestrator.state.value.mode)
    }

    @Test
    fun anEffectThatFailsIsReportedAndTheNextOnesStillCount() = runTest {
        val shown = Effect.Show(Feedback.Heard("Kestrel, open the settings."))
        val orchestrator = Orchestrator(
            backgroundScope,
            { effect ->
                if (effect == shown) throw IllegalStateException("The feedback bar failed")
                performed += effect
            },
            { errors += it },
        )

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(listOf("The feedback bar failed"), errors.map { it.message })
        // RunTask comes after the failed effect in the same step.
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.LISTENING)), Effect.RunTask(1, "open the settings.")), performed)
    }

    @Test
    fun aStrayCancellationFromAnEffectIsReportedAndTheNextEventsStillCount() = runTest {
        val listening = Effect.Show(Feedback.ModeChanged(Mode.LISTENING))
        val orchestrator = Orchestrator(
            backgroundScope,
            { effect ->
                if (effect == listening) throw CancellationException("Not the Orchestrator's own")
                performed += effect
            },
            { errors += it },
        )

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(listOf("Not the Orchestrator's own"), errors.map { it.message })
        assertEquals(Effect.RunTask(1, "open the settings."), performed.last())
    }

    @Test
    fun aReplyOrTaskThatCannotStartDoesNotLeaveKestrelWaitingForIt() = runTest {
        val orchestrator = Orchestrator(
            backgroundScope,
            { effect ->
                if (effect is Effect.Say || effect is Effect.RunTask) throw IllegalStateException("No voice")
                performed += effect
            },
            { errors += it },
        )

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        // "Yes?" could not be spoken, so Kestrel waits for the task and holds the answer.
        assertEquals(Recognition.ANSWER, orchestrator.state.value.recognition)

        orchestrator.send(Event.Heard("Open the settings.", named = false))
        runCurrent()
        assertEquals(Work.IDLE, orchestrator.state.value.work)
        assertEquals(2, errors.size)
    }

    @Test
    fun aTaskQuestionEndsAfterItsWaitAndAHandOverAfterThreeMinutes() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })
        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel, pay the bill.", named = true))
        orchestrator.send(Event.TaskAsks(1, "Which account?", TaskAsk.FREE))
        orchestrator.send(Event.SpeechEnded)
        runCurrent()
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(Effect.AnswerTask(1, null), performed.last())

        orchestrator.send(Event.TaskHandsOver(1, "This is a payment. Please finish it yourself, then say 'Kestrel, continue'."))
        orchestrator.send(Event.SpeechEnded)
        runCurrent()
        advanceTimeBy(179_999)
        runCurrent()
        assertFalse(Effect.ExpireHandOver(1) in performed)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(Effect.ExpireHandOver(1), performed.last())
        assertEquals(emptyList<Exception>(), errors)
    }


    @Test
    fun aPauseEndsAfterThirtyMinutesAndTouchesAnswerOneSecondAfterTheQuestion() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })
        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Screen(on = true))
        orchestrator.send(Event.Heard("Kestrel, open Viber.", named = true))
        orchestrator.send(Event.Screen(on = false))
        orchestrator.send(Event.Screen(on = true))
        runCurrent()
        assertEquals(TaskAsk.CONTINUE, orchestrator.state.value.taskAsking)
        advanceTimeBy(999)
        runCurrent()
        assertFalse(orchestrator.state.value.touchesArmed)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(orchestrator.state.value.touchesArmed)

        orchestrator.send(Event.Screen(on = false))
        runCurrent()
        advanceTimeBy(1_800_001)
        runCurrent()
        assertEquals(Effect.ExpirePause(1), performed.last())
        assertEquals(emptyList<Exception>(), errors)
    }
}