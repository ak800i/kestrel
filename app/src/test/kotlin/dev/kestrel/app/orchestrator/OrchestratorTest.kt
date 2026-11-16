package dev.kestrel.app.orchestrator

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
        assertFalse(Effect.WaitForTask in performed)

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
    fun anEffectThatFailsIsReportedAndTheNextOnesStillCount() = runTest {
        val listening = Effect.Show(Feedback.ModeChanged(Mode.LISTENING))
        val orchestrator = Orchestrator(
            backgroundScope,
            { effect ->
                if (effect == listening) throw IllegalStateException("The feedback bar failed")
                performed += effect
            },
            { errors += it },
        )

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(listOf("The feedback bar failed"), errors.map { it.message })
        assertEquals(Effect.RunTask(1, "open the settings."), performed.last())
    }
}
