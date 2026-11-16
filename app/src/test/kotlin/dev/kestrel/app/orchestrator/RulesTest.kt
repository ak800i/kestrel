package dev.kestrel.app.orchestrator

import dev.kestrel.voice.Keyword
import dev.kestrel.voice.listen.Recognition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val listening = State(mode = Mode.LISTENING)
    private val paused = State(mode = Mode.PAUSED)

    @Test
    fun theToggleStartsListeningThenPausesAndListensAgain() {
        val started = Rules.next(State(), Event.Toggle)

        assertEquals(Mode.LISTENING, started.state.mode)
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.LISTENING))), started.effects)
        assertEquals(Mode.PAUSED, Rules.next(started.state, Event.Toggle).state.mode)
        assertEquals(Mode.LISTENING, Rules.next(paused, Event.Toggle).state.mode)
    }

    @Test
    fun theNotificationPausesResumesAndTurnsOff() {
        assertEquals(Mode.PAUSED, Rules.next(listening, Event.Pause).state.mode)
        assertEquals(Step(paused), Rules.next(paused, Event.Pause))
        assertEquals(Mode.LISTENING, Rules.next(paused, Event.Resume).state.mode)

        val off = Rules.next(listening, Event.TurnOff)

        assertEquals(Mode.OFF, off.state.mode)
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.OFF)), Effect.StopService), off.effects)
    }

    @Test
    fun turningOffStopsTheSpeechAndTheTask() {
        val busy = listening.copy(work = Work.RUNNING_TASK, speeches = 1, task = 3, micBusy = true)

        val off = Rules.next(busy, Event.TurnOff)

        assertEquals(
            listOf(Effect.StopSaying, Effect.CancelTask, Effect.Show(Feedback.ModeChanged(Mode.OFF)), Effect.StopService),
            off.effects,
        )
        assertEquals(0, off.state.speeches)
        assertFalse(off.state.micBusy)
        assertEquals(Recognition.ALL, Rules.next(off.state, Event.Toggle).state.recognition)
    }

    @Test
    fun aTaskWithTheNameRuns() {
        val step = Rules.next(listening, Event.Heard("Kestrel, open the settings.", named = true))

        assertEquals(Work.RUNNING_TASK, step.state.work)
        assertEquals(1, step.state.task)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, open the settings.")), Effect.RunTask(1, "open the settings.")),
            step.effects,
        )
        assertEquals(Recognition.ALL, step.state.recognition)
    }

    @Test
    fun aTaskWhilePausedRunsAndKestrelStaysPaused() {
        assertEquals(Recognition.NAMED, paused.recognition)

        val running = Rules.next(paused, Event.Heard("Kestrel, open Viber", named = true)).state
        assertEquals(Mode.PAUSED, running.mode)
        assertEquals(Recognition.ALL, running.recognition)

        val done = Rules.next(running, Event.TaskEnded(task = 1, reply = null))
        assertEquals(Work.IDLE, done.state.work)
        assertEquals(Recognition.NAMED, done.state.recognition)
    }

    @Test
    fun theNameAloneAsksAndTheNextUtteranceIsTheTask() {
        val asked = Rules.next(paused, Event.Heard("Kestrel?", named = true))

        assertEquals(listOf(Effect.Show(Feedback.Heard("Kestrel?")), Effect.WaitForTask, Effect.Say(Reply.Yes)), asked.effects)
        assertEquals(Work.AWAITING_TASK, asked.state.work)
        assertEquals(Recognition.NONE, asked.state.recognition)

        val ready = Rules.next(asked.state, Event.SpeechEnded).state
        assertEquals(Recognition.ALL, ready.recognition)

        val task = Rules.next(ready, Event.Heard("Open the settings.", named = false))
        assertEquals(Effect.RunTask(1, "Open the settings."), task.effects.last())
        assertEquals(Work.RUNNING_TASK, task.state.work)
    }

    @Test
    fun theWaitForATaskEnds() {
        val waiting = listening.copy(work = Work.AWAITING_TASK)
        assertEquals(Work.IDLE, Rules.next(waiting, Event.WaitEnded).state.work)

        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)
        assertEquals(Step(running), Rules.next(running, Event.WaitEnded))
    }

    @Test
    fun whileATaskRunsOnlyStopAndCancelCount() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)

        assertEquals(Step(running), Rules.next(running, Event.Heard("Open Viber", named = false)))
        assertEquals(Step(running), Rules.next(running, Event.Heard("Kestrel, open Viber", named = true)))

        val stopped = Rules.next(running, Event.Heard("Stop.", named = false))
        assertEquals(Work.IDLE, stopped.state.work)
        assertEquals(listOf(Effect.Show(Feedback.Heard("Stop.")), Effect.CancelTask), stopped.effects)
    }

    @Test
    fun aTaskAfterStopWhileATaskRunsCancelsItAndRunsTheNewOne() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1, speeches = 1)

        val step = Rules.next(running, Event.Heard("Stop. Kestrel, open Viber.", named = true))

        assertEquals(
            listOf(
                Effect.Show(Feedback.Heard("Stop. Kestrel, open Viber.")),
                Effect.StopSaying,
                Effect.CancelTask,
                Effect.RunTask(2, "open Viber."),
            ),
            step.effects,
        )
        assertEquals(Work.RUNNING_TASK, step.state.work)
        assertEquals(2, step.state.task)
    }

    @Test
    fun stopListeningWhileATaskRunsEndsTheTaskAndPauses() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)

        val step = Rules.next(running, Event.Heard("Stop listening.", named = false))

        assertEquals(Mode.PAUSED, step.state.mode)
        assertEquals(Work.IDLE, step.state.work)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop listening.")), Effect.CancelTask, Effect.Show(Feedback.ModeChanged(Mode.PAUSED))),
            step.effects,
        )
    }

    @Test
    fun wakeUpResumesListeningFromThePause() {
        val step = Rules.next(paused, Event.Heard("Kestrel, wake up.", named = true))

        assertEquals(Mode.LISTENING, step.state.mode)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, wake up.")), Effect.Show(Feedback.ModeChanged(Mode.LISTENING))),
            step.effects,
        )
    }

    @Test
    fun stopListeningPausesAndStopKestrelTurnsOff() {
        assertEquals(Mode.PAUSED, Rules.next(listening, Event.Heard("Stop listening.", named = false)).state.mode)

        val off = Rules.next(listening, Event.Heard("Stop Kestrel.", named = false))

        assertEquals(Mode.OFF, off.state.mode)
        assertTrue(Effect.StopService in off.effects)
    }

    @Test
    fun otherSpeechGoesToTheCommandsOnlyWhileListening() {
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Scroll down")), Effect.RunCommand("Scroll down")),
            Rules.next(listening, Event.Heard("Scroll down", named = false)).effects,
        )
        assertEquals(Step(paused), Rules.next(paused, Event.Heard("Scroll down", named = true)))
        assertEquals(Effect.RunCommand("Cancel!"), Rules.next(listening, Event.Heard("Cancel!", named = false)).effects.last())
    }

    @Test
    fun stopWhileKestrelSpeaksEndsTheSpeechAndCancelsTheTask() {
        val speaking = listening.copy(work = Work.RUNNING_TASK, task = 1, speeches = 1)

        val step = Rules.next(speaking, Event.Spotted(Keyword.STOP))

        assertEquals(listOf(Effect.StopSaying, Effect.CancelTask), step.effects)
        assertEquals(Work.IDLE, step.state.work)
    }

    @Test
    fun theNameWhileKestrelSpeaksEndsTheSpeechAndWaitsForARequest() {
        val step = Rules.next(listening.copy(speeches = 1), Event.Spotted(Keyword.KESTREL))

        assertEquals(listOf(Effect.StopSaying, Effect.WaitForTask), step.effects)
        assertEquals(Work.AWAITING_TASK, step.state.work)
    }

    @Test
    fun keywordsCountOnlyWhileKestrelSpeaks() {
        assertEquals(Step(listening), Rules.next(listening, Event.Spotted(Keyword.STOP)))
    }

    @Test
    fun aTaskReplyIsSpokenAndTheEndOfAnOlderTaskChangesNothing() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 2)

        val done = Rules.next(running, Event.TaskEnded(task = 2, reply = "Done."))

        assertEquals(listOf(Effect.Say(Reply.Text("Done."))), done.effects)
        assertEquals(1, done.state.speeches)
        assertEquals(Step(running), Rules.next(running, Event.TaskEnded(task = 1, reply = "Late.")))
        assertEquals(Step(done.state), Rules.next(done.state, Event.TaskEnded(task = 2, reply = "Again.")))
    }

    @Test
    fun theMicrophoneFollowsTheModeAndTheScreen() {
        assertFalse(State().microphone)
        assertTrue(listening.microphone)

        val dark = Rules.next(listening.copy(work = Work.AWAITING_TASK), Event.Screen(on = false)).state

        assertFalse(dark.microphone)
        assertEquals(Mode.LISTENING, dark.mode)
        assertEquals(Work.IDLE, dark.work)
        assertTrue(Rules.next(dark, Event.Screen(on = true)).state.microphone)
    }

    @Test
    fun transcriptsThatFinishAfterTheScreenTurnedOffAreIgnored() {
        val dark = listening.copy(screenOn = false)
        val darkAndRunning = listening.copy(screenOn = false, work = Work.RUNNING_TASK, task = 1)

        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Kestrel, open the settings.", named = true)))
        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Scroll down", named = false)))
        assertEquals(Step(darkAndRunning), Rules.next(darkAndRunning, Event.Heard("Stop.", named = false)))
    }

    @Test
    fun aBusyMicrophoneIsShownOnce() {
        val busy = Rules.next(listening, Event.MicBusy(busy = true))

        assertEquals(listOf(Effect.Show(Feedback.MicBusy)), busy.effects)
        assertEquals(Step(busy.state), Rules.next(busy.state, Event.MicBusy(busy = true)))
        assertFalse(Rules.next(busy.state, Event.MicBusy(busy = false)).state.micBusy)
    }

    @Test
    fun transcriptsAreIgnoredWhileOff() {
        assertEquals(Step(State()), Rules.next(State(), Event.Heard("Kestrel, open the settings.", named = true)))
    }
}
