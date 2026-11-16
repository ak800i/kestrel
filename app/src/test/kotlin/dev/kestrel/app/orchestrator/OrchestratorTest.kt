package dev.kestrel.app.orchestrator

import dev.kestrel.voice.listen.Recognition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorTest {
    private val performed = mutableListOf<Effect>()
    private val errors = mutableListOf<Exception>()

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
        assertEquals(Recognition.ALL, orchestrator.state.value.recognition)

        orchestrator.send(Event.Heard("Open the settings.", named = false))
        runCurrent()
        assertEquals(Work.IDLE, orchestrator.state.value.work)
        assertEquals(2, errors.size)
    }
}