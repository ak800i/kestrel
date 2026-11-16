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

        assertEquals(listOf(Effect.Show(Feedback.Heard("Kestrel?")), Effect.WaitForTask(), Effect.Say(Reply.Yes)), asked.effects)
        assertEquals(Work.AWAITING_TASK, asked.state.work)
        assertEquals(1, asked.state.wait)
        assertEquals(Recognition.NONE, asked.state.recognition)

        // The wait starts again when "Yes?" has been spoken.
        val spoken = Rules.next(asked.state, Event.SpeechEnded)
        assertEquals(listOf(Effect.WaitForTask()), spoken.effects)
        assertEquals(2, spoken.state.wait)
        val ready = spoken.state
        assertEquals(Recognition.ALL, ready.recognition)

        val task = Rules.next(ready, Event.Heard("Open the settings.", named = false))
        assertEquals(Effect.RunTask(1, "Open the settings."), task.effects.last())
        assertEquals(Work.RUNNING_TASK, task.state.work)
    }

    @Test
    fun theWaitForATaskEnds() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 2)
        assertEquals(Work.IDLE, Rules.next(waiting, Event.WaitEnded(2)).state.work)
        // The end of an older wait changes nothing.
        assertEquals(Step(waiting), Rules.next(waiting, Event.WaitEnded(1)))

        val running = listening.copy(work = Work.RUNNING_TASK, task = 1, wait = 2)
        assertEquals(Step(running), Rules.next(running, Event.WaitEnded(2)))
    }

    @Test
    fun theWaitCoversTheAnswerOnceTheOwnerStartsIt() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1)

        val answering = Rules.next(waiting, Event.Speaking)
        assertEquals(listOf(Effect.WaitForTask(answering = true)), answering.effects)
        assertEquals(2, answering.state.wait)

        // While "Yes?" is spoken, and without a wait, speech changes nothing.
        val speaking = waiting.copy(speeches = 1)
        assertEquals(Step(speaking), Rules.next(speaking, Event.Speaking))
        assertEquals(Step(listening), Rules.next(listening, Event.Speaking))

        // An answer without words gives the owner the full wait again.
        val nothing = Rules.next(answering.state, Event.Heard(" ... ", named = false))
        assertEquals(listOf(Effect.WaitForTask()), nothing.effects)
        assertEquals(Work.AWAITING_TASK, nothing.state.work)
        assertEquals(3, nothing.state.wait)
        assertEquals(Step(listening), Rules.next(listening, Event.Heard(" ... ", named = false)))
    }

    @Test
    fun onlyTheEndOfTheLastReplyRestartsTheWait() {
        val twoReplies = listening.copy(work = Work.AWAITING_TASK, wait = 1, speeches = 2)

        assertEquals(Step(twoReplies.copy(speeches = 1)), Rules.next(twoReplies, Event.SpeechEnded))
        assertEquals(Step(listening), Rules.next(listening, Event.SpeechEnded))
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
        // The new task drops the cancelled task's report.
        assertFalse(step.state.cancelled)
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
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop Kestrel.")), Effect.Show(Feedback.ModeChanged(Mode.OFF)), Effect.StopService),
            off.effects,
        )
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

        assertEquals(listOf(Effect.StopSaying, Effect.WaitForTask()), step.effects)
        assertEquals(Work.AWAITING_TASK, step.state.work)
        assertEquals(1, step.state.wait)
    }

    @Test
    fun keywordsWhileKestrelSpeaksInEachState() {
        // "Stop" during "Yes?" ends the speech and the wait.
        val askingYes = listening.copy(work = Work.AWAITING_TASK, speeches = 1)
        val stopped = Rules.next(askingYes, Event.Spotted(Keyword.STOP))
        assertEquals(listOf(Effect.StopSaying), stopped.effects)
        assertEquals(Work.IDLE, stopped.state.work)

        // "Kestrel" while a task runs only ends the speech; the task keeps running.
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1, speeches = 1)
        assertEquals(Step(running, listOf(Effect.StopSaying)), Rules.next(running, Event.Spotted(Keyword.KESTREL)))

        // No wait starts while the screen is off.
        val dark = listening.copy(speeches = 1, screenOn = false)
        assertEquals(Step(dark, listOf(Effect.StopSaying)), Rules.next(dark, Event.Spotted(Keyword.KESTREL)))
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
    fun transcriptsThatFinishAfterTheScreenTurnedOffOnlyStopThings() {
        val dark = listening.copy(screenOn = false)
        val darkAndRunning = listening.copy(screenOn = false, work = Work.RUNNING_TASK, task = 1)

        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Kestrel, open the settings.", named = true)))
        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Scroll down", named = false)))
        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Stop.", named = false)))
        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Kestrel?", named = true)))
        assertEquals(Step(darkAndRunning), Rules.next(darkAndRunning, Event.Heard("Stop. Kestrel, open Viber.", named = true)))

        val stopped = Rules.next(darkAndRunning, Event.Heard("Stop.", named = false))
        assertEquals(Work.IDLE, stopped.state.work)
        assertEquals(listOf(Effect.Show(Feedback.Heard("Stop.")), Effect.CancelTask), stopped.effects)
        assertEquals(Mode.PAUSED, Rules.next(darkAndRunning, Event.Heard("Stop listening.", named = false)).state.mode)
        assertEquals(Mode.PAUSED, Rules.next(dark, Event.Heard("Stop listening.", named = false)).state.mode)
        assertEquals(Mode.OFF, Rules.next(darkAndRunning, Event.Heard("Stop Kestrel.", named = false)).state.mode)
    }

    @Test
    fun aRunningTaskIsKeptWhenTheScreenTurnsOff() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)

        assertEquals(Step(running.copy(screenOn = false)), Rules.next(running, Event.Screen(on = false)))
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

    @Test
    fun theAnswerToYesMayWakeKestrelUp() {
        val step = Rules.next(paused.copy(work = Work.AWAITING_TASK), Event.Heard("Wake up.", named = false))

        assertEquals(Mode.LISTENING, step.state.mode)
        assertEquals(Work.IDLE, step.state.work)
    }

    @Test
    fun whileAwaitingATaskKestrelsOwnRequestsCount() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1)
        val pausedAndWaiting = paused.copy(work = Work.AWAITING_TASK, wait = 1)
        fun heard(state: State, text: String, named: Boolean = false) = Rules.next(state, Event.Heard(text, named))
        fun shown(text: String) = Effect.Show(Feedback.Heard(text))

        assertEquals(Effect.RunTask(1, "open the settings."), heard(waiting, "Kestrel, open the settings.", named = true).effects.last())
        assertEquals(Step(listening.copy(wait = 1), listOf(shown("Stop."))), heard(waiting, "Stop."))
        assertEquals(
            Step(paused.copy(wait = 1), listOf(shown("Stop listening."), Effect.Show(Feedback.ModeChanged(Mode.PAUSED)))),
            heard(waiting, "Stop listening."),
        )
        // A mode that Kestrel is in already is not shown again.
        assertEquals(Step(paused.copy(wait = 1), listOf(shown("Stop listening."))), heard(pausedAndWaiting, "Stop listening."))
        assertEquals(Step(listening.copy(wait = 1), listOf(shown("Wake up."))), heard(waiting, "Wake up."))
        assertEquals(Mode.LISTENING, heard(pausedAndWaiting, "Kestrel, wake up.", named = true).state.mode)
        assertEquals(
            listOf(shown("Stop Kestrel."), Effect.Show(Feedback.ModeChanged(Mode.OFF)), Effect.StopService),
            heard(waiting, "Stop Kestrel.").effects,
        )

        val again = heard(waiting, "Kestrel?", named = true)
        assertEquals(listOf(shown("Kestrel?"), Effect.WaitForTask(), Effect.Say(Reply.Yes)), again.effects)
        assertEquals(Work.AWAITING_TASK, again.state.work)
        assertEquals(2, again.state.wait)
    }

    @Test
    fun whileATaskRunsKestrelsOwnRequests() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)
        val pausedAndRunning = paused.copy(work = Work.RUNNING_TASK, task = 1)

        val off = Rules.next(running, Event.Heard("Stop Kestrel.", named = false))
        assertEquals(Mode.OFF, off.state.mode)
        assertEquals(
            listOf(
                Effect.Show(Feedback.Heard("Stop Kestrel.")),
                Effect.CancelTask,
                Effect.Show(Feedback.ModeChanged(Mode.OFF)),
                Effect.StopService,
            ),
            off.effects,
        )
        assertEquals(Step(running), Rules.next(running, Event.Heard("Kestrel.", named = true)))
        assertEquals(Step(pausedAndRunning), Rules.next(pausedAndRunning, Event.Heard("Kestrel, wake up.", named = true)))
        // Already paused: the task ends, and the mode is not shown again.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop listening.")), Effect.CancelTask),
            Rules.next(pausedAndRunning, Event.Heard("Stop listening.", named = false)).effects,
        )
    }

    @Test
    fun whileIdleAStopEndsOnlyTheSpeech() {
        val speaking = listening.copy(speeches = 1)

        assertEquals(
            Step(speaking, listOf(Effect.Show(Feedback.Heard("Kestrel, stop.")), Effect.StopSaying)),
            Rules.next(speaking, Event.Heard("Kestrel, stop.", named = true)),
        )
        assertEquals(
            Step(listening, listOf(Effect.Show(Feedback.Heard("Kestrel, stop.")))),
            Rules.next(listening, Event.Heard("Kestrel, stop.", named = true)),
        )
        assertEquals(Step(paused), Rules.next(paused, Event.Heard("Stop.", named = false)))
        // Listening already: only the transcript is shown.
        assertEquals(
            Step(listening, listOf(Effect.Show(Feedback.Heard("Kestrel, wake up.")))),
            Rules.next(listening, Event.Heard("Kestrel, wake up.", named = true)),
        )
    }

    @Test
    fun aCancelledTaskStillReportsWhatItDid() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)
        val cancelled = Rules.next(running, Event.Heard("Stop.", named = false)).state

        val report = Rules.next(cancelled, Event.TaskEnded(task = 1, reply = "Cancelled. I didn't send it."))
        assertEquals(listOf(Effect.Say(Reply.Text("Cancelled. I didn't send it."))), report.effects)
        assertEquals(Work.IDLE, report.state.work)
        assertEquals(Step(report.state), Rules.next(report.state, Event.TaskEnded(task = 1, reply = "Again.")))
        assertEquals(Step(cancelled), Rules.next(cancelled, Event.TaskEnded(task = 0, reply = "Older.")))

        // A newer wait or task, or Off, drops the report.
        val waiting = Rules.next(cancelled, Event.Heard("Kestrel?", named = true)).state
        val idleAgain = Rules.next(waiting, Event.WaitEnded(waiting.wait)).state
        assertEquals(Step(idleAgain), Rules.next(idleAgain, Event.TaskEnded(task = 1, reply = "Late.")))
        val off = Rules.next(cancelled, Event.TurnOff).state
        assertEquals(Step(off), Rules.next(off, Event.TaskEnded(task = 1, reply = "Late.")))
    }

    @Test
    fun theNotificationAndOtherEventsThatChangeNothing() {
        assertEquals(Step(listening), Rules.next(listening, Event.Resume))
        assertEquals(Step(State()), Rules.next(State(), Event.Resume))
        assertEquals(Step(State()), Rules.next(State(), Event.Pause))
        assertEquals(Step(State()), Rules.next(State(), Event.TurnOff))
        assertEquals(Step(State()), Rules.next(State(), Event.MicBusy(busy = true)))
    }

    @Test
    fun startTurnsKestrelOnOnlyWhileItIsOff() {
        val started = Rules.next(State(), Event.Start())

        assertEquals(Mode.LISTENING, started.state.mode)
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.LISTENING))), started.effects)
        assertEquals(Mode.PAUSED, Rules.next(State(), Event.Start(paused = true)).state.mode)
        // A start that comes late must not toggle anything.
        assertEquals(Step(listening), Rules.next(listening, Event.Start(paused = true)))
        assertEquals(Step(paused), Rules.next(paused, Event.Start()))
    }

    @Test
    fun kestrelTurnOffTurnsKestrelOff() {
        val off = Rules.next(paused, Event.Heard("Kestrel, turn off.", named = true))

        assertEquals(Mode.OFF, off.state.mode)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, turn off.")), Effect.Show(Feedback.ModeChanged(Mode.OFF)), Effect.StopService),
            off.effects,
        )
    }

    @Test
    fun kestrelOpenSettingsOpensThemWhileListeningOrPaused() {
        assertEquals(
            Step(listening, listOf(Effect.Show(Feedback.Heard("Kestrel, open settings.")), Effect.OpenSettings)),
            Rules.next(listening, Event.Heard("Kestrel, open settings.", named = true)),
        )
        assertEquals(
            Step(paused, listOf(Effect.Show(Feedback.Heard("Kestrel settings")), Effect.OpenSettings)),
            Rules.next(paused, Event.Heard("Kestrel settings", named = true)),
        )
        // Nothing may start while the screen is off (section 14.4).
        val dark = listening.copy(screenOn = false)
        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Kestrel, open settings.", named = true)))
    }

    @Test
    fun openSettingsEndsTheWaitForATaskAndIsIgnoredWhileATaskRuns() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1)
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)

        assertEquals(
            Step(listening.copy(wait = 1), listOf(Effect.Show(Feedback.Heard("Open settings.")), Effect.OpenSettings)),
            Rules.next(waiting, Event.Heard("Open settings.", named = false)),
        )
        assertEquals(Step(running), Rules.next(running, Event.Heard("Kestrel, open settings.", named = true)))
    }

    @Test
    fun neverMindEndsTheWaitForATask() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1)

        assertEquals(
            Step(listening.copy(wait = 1), listOf(Effect.Show(Feedback.Heard("Never mind.")))),
            Rules.next(waiting, Event.Heard("Never mind.", named = false)),
        )
    }
}
