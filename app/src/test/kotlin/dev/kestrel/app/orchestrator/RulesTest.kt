package dev.kestrel.app.orchestrator

import dev.kestrel.commands.CallTarget
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Direction
import dev.kestrel.commands.GlobalCommand
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.commands.Target
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.listen.Recognition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val listening = State(mode = Mode.LISTENING)
    private val paused = State(mode = Mode.PAUSED)
    private val runningCommand =
        listening.copy(work = Work.RUNNING_COMMAND, command = 1, running = Command.Tap(Target.Label("Send")), heard = "tap Send")

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

        // The bar shows "Yes?" at the start of the wait for the task (section 8.9).
        assertEquals(
            listOf(
                Effect.Show(Feedback.Heard("Kestrel?")),
                Effect.Show(Feedback.AwaitingTask),
                Effect.WaitForTask(),
                Effect.Say(Reply.Yes),
            ),
            asked.effects,
        )
        assertEquals(Work.AWAITING_TASK, asked.state.work)
        assertEquals(1, asked.state.wait)
        assertEquals(Recognition.NONE, asked.state.recognition)

        // The wait starts again when "Yes?" has been spoken.
        val spoken = Rules.next(asked.state, Event.SpeechEnded)
        assertEquals(listOf(Effect.WaitForTask()), spoken.effects)
        assertEquals(2, spoken.state.wait)
        val ready = spoken.state
        assertEquals(Recognition.ANSWER, ready.recognition)

        val task = Rules.next(ready, Event.Heard("Open the settings.", named = false))
        assertEquals(Effect.RunTask(1, "Open the settings."), task.effects.last())
        assertEquals(Work.RUNNING_TASK, task.state.work)
    }

    @Test
    fun theAnswerToYesIsHeldLikeANamedUtterance() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1)

        assertEquals(Recognition.ANSWER, waiting.recognition)
        assertEquals(Recognition.ANSWER, paused.copy(work = Work.AWAITING_TASK, wait = 1).recognition)
        // While "Yes?" is spoken, nothing is transcribed, and while a task runs, every utterance goes out at once.
        assertEquals(Recognition.NONE, waiting.copy(speeches = 1).recognition)
        assertEquals(Recognition.ALL, listening.copy(work = Work.RUNNING_TASK, task = 1).recognition)
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
        // Outside a wait, a transcript without words is only counted (section 14.6).
        assertEquals(Step(listening.copy(emptyTranscripts = 1)), Rules.next(listening, Event.Heard(" ... ", named = false)))
    }

    @Test
    fun anEmptyAnswerToYesCountsTowardsTheHint() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1, emptyTranscripts = 2)

        val step = Rules.next(waiting, Event.Heard("", named = false))

        assertEquals(listOf(Effect.Show(Feedback.NoWords), Effect.WaitForTask()), step.effects)
        assertEquals(3, step.state.emptyTranscripts)
        assertEquals(Work.AWAITING_TASK, step.state.work)
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
    fun aNearMissOfWakeUpWhilePausedStartsNothing() {
        // Plan 4E: one wrong word still wakes Kestrel up; two start nothing while it is paused, and a task while it listens.
        assertEquals(Mode.LISTENING, Rules.next(paused, Event.Heard("Kestrel, make up.", named = true)).state.mode)
        assertEquals(
            Step(paused, listOf(Effect.Show(Feedback.Heard("Kestrel, sign up.")))),
            Rules.next(paused, Event.Heard("Kestrel, sign up.", named = true)),
        )
        assertEquals(Work.RUNNING_TASK, Rules.next(listening, Event.Heard("Kestrel, sign up.", named = true)).state.work)
        // The same in an answer to "Yes?" while paused: the wait ends, and no task starts.
        assertEquals(
            Step(paused.copy(wait = 1), listOf(Effect.Show(Feedback.Heard("Sign up.")))),
            Rules.next(paused.copy(work = Work.AWAITING_TASK, wait = 1), Event.Heard("Sign up.", named = false)),
        )
        // After "Stop." while a task runs, the task stops and no new one starts.
        val stopped = Rules.next(paused.copy(work = Work.RUNNING_TASK, task = 1), Event.Heard("Stop. Kestrel, sign up.", named = true))
        assertEquals(Work.IDLE, stopped.state.work)
        assertEquals(1, stopped.state.task)
        // "Stop Kestrel" with a name further away turns Kestrel off when the spotter heard "stop" at its start.
        assertEquals(Mode.OFF, Rules.next(listening, Event.Heard("Stop Castral.", named = false, stopSpotted = true)).state.mode)
        assertEquals(Mode.LISTENING, Rules.next(listening, Event.Heard("Stop Castral.", named = false)).state.mode)
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
    fun otherSpeechRunsAnInstantCommandOnlyWhileListening() {
        val step = Rules.next(listening, Event.Heard("Scroll down", named = false))

        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Scroll down")), Effect.RunCommand(1, Command.Scroll(Direction.DOWN, null), "Scroll down")),
            step.effects,
        )
        assertEquals(Work.RUNNING_COMMAND, step.state.work)
        assertEquals(1, step.state.command)
        assertEquals(Recognition.ALL, step.state.recognition)
        assertEquals(Step(paused), Rules.next(paused, Event.Heard("Scroll down", named = true)))
        // Plain "cancel" while nothing runs may name a button (section 9.5).
        assertEquals(
            Effect.RunCommand(1, Command.Bare("Cancel!", null), "Cancel!"),
            Rules.next(listening, Event.Heard("Cancel!", named = false)).effects.last(),
        )
        val dark = listening.copy(screenOn = false)
        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Scroll down", named = false)))
    }

    @Test
    fun aCommandEndsWithItsResultAndARepeatableOneBecomesTheLastCommand() {
        val started = Rules.next(listening, Event.Heard("tap Send", named = false)).state

        val ended = Rules.next(started, Event.CommandEnded(1, CommandResult(Outcome.DONE, show = "It's already on", repeatable = true)))

        assertEquals(Work.IDLE, ended.state.work)
        assertEquals(null, ended.state.running)
        assertEquals(Command.Tap(Target.Label("Send")), ended.state.lastCommand)
        assertEquals("tap Send", ended.state.lastHeard)
        assertEquals(listOf(Effect.Show(Feedback.CommandText("It's already on"))), ended.effects)
        // An older command's end, and an end after the work left the command, change nothing.
        assertEquals(Step(started), Rules.next(started, Event.CommandEnded(0, CommandResult(Outcome.DONE))))
        assertEquals(Step(ended.state), Rules.next(ended.state, Event.CommandEnded(1, CommandResult(Outcome.DONE))))
        // A spoken result of a command that is not repeatable, and a cancel, which the bar shows.
        val said = Rules.next(started, Event.CommandEnded(1, CommandResult(Outcome.FAILED, say = "I can't find Send.")))
        assertEquals(listOf(Effect.Say(Reply.Text("I can't find Send."))), said.effects)
        assertEquals(1, said.state.speeches)
        assertEquals(null, said.state.lastCommand)
        assertEquals(
            listOf(Effect.Show(Feedback.CommandCancelled)),
            Rules.next(started, Event.CommandEnded(1, CommandResult(Outcome.CANCELLED))).effects,
        )
        // A touch that cancels a question while Kestrel still says it stops the question too.
        assertEquals(
            listOf(Effect.Show(Feedback.CommandCancelled), Effect.StopSaying),
            Rules.next(started.copy(speeches = 1), Event.CommandEnded(1, CommandResult(Outcome.CANCELLED))).effects,
        )
    }

    @Test
    fun againRepeatsTheCommandThatActedWithItsTranscript() {
        val started = Rules.next(listening, Event.Heard("tap Delete", named = false)).state
        // The executor fell back to the bare label, and that command acted.
        val acted = CommandResult(Outcome.DONE, repeatable = true, acted = Command.Bare("Delete", null))
        val ended = Rules.next(started, Event.CommandEnded(1, acted)).state

        val again = Rules.next(ended, Event.Heard("Again.", named = false))

        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Again.")), Effect.RunCommand(2, Command.Bare("Delete", null), "tap Delete")),
            again.effects,
        )
        assertEquals(Command.Bare("Delete", null), again.state.running)
        // Off forgets it, so that "again" never repeats a command of an earlier run.
        assertEquals(null, Rules.next(ended, Event.TurnOff).state.lastCommand)
        // Without a repeatable command, the bar says so.
        assertEquals(
            Step(listening, listOf(Effect.Show(Feedback.Heard("again")), Effect.Show(Feedback.NothingToRepeat))),
            Rules.next(listening, Event.Heard("again", named = false)),
        )
    }

    @Test
    fun stopCancelAndSpeechThatStartsWithThemCancelACommand() {
        for (said in listOf("Stop.", "cancel", "Kestrel, stop.", "Cancel it, Kestrel.", "Stop scrolling", "Okay, stop scrolling")) {
            val step = Rules.next(runningCommand, Event.Heard(said, named = false))
            assertEquals(said, Work.IDLE, step.state.work)
            assertEquals(said, listOf(Effect.Show(Feedback.Heard(said)), Effect.CancelCommand, Effect.Show(Feedback.CommandCancelled)), step.effects)
        }
        // A question or an announcement in progress stops too.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop.")), Effect.CancelCommand, Effect.StopSaying, Effect.Show(Feedback.CommandCancelled)),
            Rules.next(runningCommand.copy(speeches = 1), Event.Heard("Stop.", named = false)).effects,
        )
    }

    @Test
    fun kestrelsOwnRequestsCancelTheCommandFirstAndThenAct() {
        val task = Rules.next(runningCommand, Event.Heard("Kestrel, open Viber.", named = true))
        assertEquals(
            listOf(Effect.CancelCommand, Effect.Show(Feedback.Heard("Kestrel, open Viber.")), Effect.RunTask(1, "open Viber.")),
            task.effects,
        )
        assertEquals(Work.RUNNING_TASK, task.state.work)

        val settings = Rules.next(runningCommand, Event.Heard("Kestrel, open settings.", named = true))
        assertEquals(
            listOf(Effect.CancelCommand, Effect.Show(Feedback.Heard("Kestrel, open settings.")), Effect.OpenSettings),
            settings.effects,
        )

        val pausing = Rules.next(runningCommand, Event.Heard("Stop listening.", named = false))
        assertEquals(Mode.PAUSED, pausing.state.mode)
        assertEquals(Effect.CancelCommand, pausing.effects.first())

        val assisted = Rules.next(runningCommand, Event.Assist)
        assertEquals(Effect.CancelCommand, assisted.effects.first())
        assertEquals(Work.AWAITING_TASK, assisted.state.work)
    }

    @Test
    fun aCommandThatArrivesWhileAnotherRunsIsDropped() {
        assertEquals(
            Step(runningCommand, listOf(Effect.Show(Feedback.Heard("Scroll down")), Effect.Show(Feedback.CommandBusy))),
            Rules.next(runningCommand, Event.Heard("Scroll down", named = false)),
        )
        assertEquals(Effect.Show(Feedback.CommandBusy), Rules.next(runningCommand, Event.Heard("Again", named = false)).effects.last())
        // Bare speech may be conversation, so the bar does not say so, except while dictation may run.
        assertEquals(
            Step(runningCommand, listOf(Effect.Show(Feedback.Heard("Hello there")))),
            Rules.next(runningCommand, Event.Heard("Hello there", named = false)),
        )
        val dictating = runningCommand.copy(running = Command.Bare("Hello", null))
        assertEquals(Effect.Show(Feedback.CommandBusy), Rules.next(dictating, Event.Heard("Hello there", named = false)).effects.last())
    }

    @Test
    fun aQuestionIsSpokenKeptOnTheBarAndTheNextTranscriptAnswersIt() {
        val asked = Rules.next(runningCommand, Event.CommandAsks(1, "Which one?"))

        assertTrue(asked.state.asking)
        assertEquals(
            listOf(Effect.Show(Feedback.CommandText("Which one?", keep = true)), Effect.Say(Reply.Command(1, "Which one?")), Effect.WaitForAnswer()),
            asked.effects,
        )
        val answered = Rules.next(asked.state, Event.Heard("To.", named = false))
        assertFalse(answered.state.asking)
        assertEquals(Work.RUNNING_COMMAND, answered.state.work)
        assertEquals(listOf(Effect.Show(Feedback.Heard("To.")), Effect.AnswerCommand(1, "To.")), answered.effects)
        // A question of an older command changes nothing.
        assertEquals(Step(runningCommand), Rules.next(runningCommand, Event.CommandAsks(0, "Which one?")))
    }

    @Test
    fun theWaitForAnAnswerStartsAgainWhenTheQuestionWasSpokenAndGrowsWhenTheOwnerAnswers() {
        val asked = Rules.next(runningCommand, Event.CommandAsks(1, "Which one?")).state

        val spoken = Rules.next(asked, Event.SpeechEnded)
        assertEquals(listOf(Effect.WaitForAnswer()), spoken.effects)
        assertEquals(2, spoken.state.wait)
        val answering = Rules.next(spoken.state, Event.Speaking)
        assertEquals(listOf(Effect.WaitForAnswer(answering = true)), answering.effects)
        // The end of an older wait changes nothing; the end of the latest one ends the question without an answer.
        assertEquals(Step(answering.state), Rules.next(answering.state, Event.AnswerWaitEnded(2)))
        val ended = Rules.next(answering.state, Event.AnswerWaitEnded(3))
        assertFalse(ended.state.asking)
        assertEquals(listOf(Effect.AnswerCommand(1, null), Effect.TimeLateAnswers), ended.effects)
        // An utterance without a transcript gives the owner the whole wait again.
        assertEquals(listOf(Effect.WaitForAnswer()), Rules.next(answering.state, Event.NothingHeard).effects)
    }

    @Test
    fun aCountdownHoldsWhileTheOwnerSpeaksAndOnlyCancelStopNoOrAModeChangeEndIt() {
        val counting = Rules.next(runningCommand, Event.CommandCounts(1, counting = true)).state
        assertTrue(counting.counting)
        assertEquals(listOf(Effect.HoldCommand(1, hold = true)), Rules.next(counting, Event.Speaking).effects)
        // Anything else lets the countdown go on: other speech, Kestrel's other requests, and an utterance without words.
        for (said in listOf("What time is it?", "Kestrel, open settings.", "Kestrel", "tap Save", "I have no idea.")) {
            val step = Rules.next(counting, Event.Heard(said, named = false))
            assertEquals(said, listOf(Effect.Show(Feedback.Heard(said)), Effect.HoldCommand(1, hold = false)), step.effects)
            assertEquals(said, counting, step.state)
        }
        assertEquals(listOf(Effect.HoldCommand(1, hold = false)), Rules.next(counting, Event.NothingHeard).effects)
        assertEquals(Step(counting), Rules.next(counting, Event.Assist))
        // "Cancel", "stop" and "no", with or without the name, cancel it.
        for (said in listOf(
            "Cancel.", "Stop", "No.", "No, no.", "Kestrel, no.", "Stop that.", "Cancel it, Kestrel.", "Hey Kestrel, no.",
            "No, Castrel.", "Oh no!", "No, no, wait.", "No, not her.", "No, I said Anja.", "Thank you, no.",
        )) {
            val step = Rules.next(counting, Event.Heard(said, named = false))
            assertEquals(said, Effect.CancelCommand, step.effects[1])
            assertFalse(said, step.state.counting)
        }
        // A change of mode cancels it and then acts.
        val pausing = Rules.next(counting, Event.Heard("Stop listening.", named = false))
        assertEquals(Mode.PAUSED, pausing.state.mode)
        assertEquals(Effect.CancelCommand, pausing.effects.first())
        assertEquals(Mode.OFF, Rules.next(counting, Event.Heard("Kestrel, turn off.", named = false)).state.mode)
        assertFalse(Rules.next(counting, Event.CommandCounts(1, counting = false)).state.counting)
    }

    @Test
    fun theSpottersStopOrCancelEndsACountdownAtOnceAndACommandWhileKestrelSpeaks() {
        val counting = runningCommand.copy(counting = true)
        assertEquals(
            Step(listening.copy(command = 1, spottedStop = true), listOf(Effect.CancelCommand, Effect.Show(Feedback.CommandCancelled))),
            Rules.next(counting, Event.Spotted(Keyword.CANCEL)),
        )
        // While Kestrel is silent and no countdown runs, the transcript decides.
        assertEquals(Step(runningCommand), Rules.next(runningCommand, Event.Spotted(Keyword.STOP)))
        val speaking = runningCommand.copy(speeches = 1)
        assertEquals(
            listOf(Effect.CancelCommand, Effect.StopSaying, Effect.Show(Feedback.CommandCancelled)),
            Rules.next(speaking, Event.Spotted(Keyword.STOP)).effects,
        )
        // "Kestrel" over Kestrel's voice cancels the command and waits for a task, but not during a countdown.
        val named = Rules.next(speaking, Event.Spotted(Keyword.KESTREL))
        assertEquals(Effect.CancelCommand, named.effects.first())
        assertEquals(Work.AWAITING_TASK, named.state.work)
        assertEquals(Step(counting.copy(speeches = 1)), Rules.next(counting.copy(speeches = 1), Event.Spotted(Keyword.KESTREL)))
    }

    @Test
    fun theTranscriptOfTheSpottersCancelNamesNoButton() {
        val cancelled = Rules.next(runningCommand.copy(counting = true), Event.Spotted(Keyword.CANCEL)).state

        // The same word's transcript comes once the command has ended: it is only shown.
        val heard = Rules.next(cancelled, Event.Heard("Cancel.", named = false))
        assertEquals(Step(cancelled.copy(spottedStop = false), listOf(Effect.Show(Feedback.Heard("Cancel.")))), heard)
        // A later "cancel" may name a button again.
        assertEquals(
            Effect.RunCommand(2, Command.Bare("Cancel.", null), "Cancel."),
            Rules.next(heard.state, Event.Heard("Cancel.", named = false)).effects.last(),
        )
        // Another transcript runs as usual.
        assertEquals(
            Effect.RunCommand(2, Command.Scroll(Direction.DOWN, null), "Scroll down"),
            Rules.next(cancelled, Event.Heard("Scroll down", named = false)).effects.last(),
        )
    }

    @Test
    fun theTranscriptOfTheSpottersStopOfAReplyNamesNoButton() {
        // A command's spoken result, after the command has ended: the spotter's "stop" only stops the reply.
        for (keyword in listOf(Keyword.STOP, Keyword.CANCEL)) {
            val stopped = Rules.next(listening.copy(speeches = 1), Event.Spotted(keyword))
            assertEquals("$keyword", listOf(Effect.StopSaying), stopped.effects)

            // The rest of the same utterance, such as the second word of "stop, stop", taps no "Stop" button.
            val heard = Rules.next(stopped.state, Event.Heard("Stop.", named = false))
            assertEquals("$keyword", listOf(Effect.Show(Feedback.Heard("Stop."))), heard.effects)
            assertFalse("$keyword", heard.state.spottedStop)
        }
    }

    @Test
    fun aNumberRightAfterAQuestionEndedWithoutAnAnswerIsOnlyShown() {
        val asked = Rules.next(runningCommand, Event.CommandAsks(1, "Which one?")).state
        val unanswered = Rules.next(asked, Event.AnswerWaitEnded(asked.wait)).state
        val ended = Rules.next(unanswered, Event.CommandEnded(1, CommandResult(Outcome.CANCELLED))).state

        // An answer that the owner began just before the wait ran out arrives as a new utterance: it acts on nothing.
        for (late in listOf("2", "Number 2.", "Tap 2.", "Long press 2.")) {
            val step = Rules.next(ended, Event.Heard(late, named = false))
            assertEquals(late, listOf(Effect.Show(Feedback.Heard(late)), Effect.Show(Feedback.QuestionEnded)), step.effects)
            assertEquals(late, Work.IDLE, step.state.work)
        }
        // Only the first transcript after the question: a number acts again after it, and another command acts at once.
        val shown = Rules.next(ended, Event.Heard("2", named = false)).state
        assertEquals(Effect.RunCommand(2, Command.Bare("2", 2), "2"), Rules.next(shown, Event.Heard("2", named = false)).effects.last())
        val scrolled = Rules.next(ended, Event.Heard("Scroll down", named = false))
        assertEquals(Effect.RunCommand(2, Command.Scroll(Direction.DOWN, null), "Scroll down"), scrolled.effects.last())
        val afterScroll = Rules.next(scrolled.state, Event.CommandEnded(2, CommandResult(Outcome.DONE))).state
        assertEquals(Effect.RunCommand(3, Command.Bare("2", 2), "2"), Rules.next(afterScroll, Event.Heard("2", named = false)).effects.last())
        // An answered question leaves nothing behind.
        val answered = Rules.next(Rules.next(asked, Event.Heard("3", named = false)).state, Event.CommandEnded(1, CommandResult(Outcome.DONE)))
        assertEquals(Effect.RunCommand(2, Command.Bare("2", 2), "2"), Rules.next(answered.state, Event.Heard("2", named = false)).effects.last())
    }

    @Test
    fun aPauseATaskTheScreenTurningOffOrTenSecondsEndTheTimeForALateAnswer() {
        val asked = Rules.next(runningCommand, Event.CommandAsks(1, "Which one?")).state
        val unanswered = Rules.next(asked, Event.AnswerWaitEnded(asked.wait)).state
        val ended = Rules.next(unanswered, Event.CommandEnded(1, CommandResult(Outcome.CANCELLED))).state
        fun runs(state: State) = Rules.next(state, Event.Heard("2", named = false)).effects.last() is Effect.RunCommand
        check(!runs(ended))

        // The timer of the latest question ends it; an older timer changes nothing.
        assertTrue(runs(Rules.next(ended, Event.LateAnswersEnded(1)).state))
        assertFalse(runs(Rules.next(ended, Event.LateAnswersEnded(0)).state))
        assertTrue(runs(Rules.next(Rules.next(ended, Event.Pause).state, Event.Resume).state))
        assertTrue(runs(Rules.next(Rules.next(ended, Event.Screen(on = false)).state, Event.Screen(on = true)).state))
        val task = Rules.next(ended, Event.Heard("Kestrel, what time is it?", named = true)).state
        assertEquals(Work.RUNNING_TASK, task.work)
        assertTrue(runs(Rules.next(task, Event.TaskEnded(task.task, reply = null)).state))
        val waiting = Rules.next(ended, Event.Heard("Kestrel.", named = true)).state
        assertEquals(Work.AWAITING_TASK, waiting.work)
        assertTrue(runs(Rules.next(waiting, Event.WaitEnded(waiting.wait)).state))
    }

    @Test
    fun theStartOfTheUtteranceGoesWithItsCommand() {
        val step = Rules.next(listening, Event.Heard("Tap 5.", named = false, spokeAt = 1_234L))

        assertEquals(Effect.RunCommand(1, Command.Tap(Target.Number(5)), "Tap 5.", spokeAt = 1_234L), step.effects.last())
    }

    @Test
    fun aChangeOfModeOrTheScreenTurningOffCancelsTheCommand() {
        for (event in listOf(Event.Toggle, Event.Pause, Event.TurnOff, Event.Screen(on = false), Event.CallState(CallState.RINGING))) {
            val step = Rules.next(runningCommand, event)
            assertEquals("$event", Effect.CancelCommand, step.effects.first())
            assertEquals("$event", Work.IDLE, step.state.work)
            assertEquals("$event", null, step.state.running)
        }
        assertEquals(Mode.PAUSED, Rules.next(runningCommand, Event.Toggle).state.mode)
    }

    @Test
    fun lockingTheScreenAndTheCallThatACommandPlacedLeaveItRunning() {
        val locking = runningCommand.copy(running = PhoneCommand.Global(GlobalCommand.LOCK_SCREEN))
        val dark = Rules.next(locking, Event.Screen(on = false))
        assertEquals(Step(locking.copy(screenOn = false)), dark)
        assertEquals(Work.IDLE, Rules.next(dark.state, Event.CommandEnded(1, CommandResult(Outcome.DONE))).state.work)

        val calling = runningCommand.copy(running = PhoneCommand.Call(CallTarget.Number("0641234567")))
        val active = Rules.next(calling, Event.CallState(CallState.ACTIVE))
        assertEquals(Mode.IN_CALL, active.state.mode)
        assertEquals(Work.RUNNING_COMMAND, active.state.work)
        assertFalse(Effect.CancelCommand in active.effects)
        assertEquals(Work.IDLE, Rules.next(active.state, Event.CommandEnded(1, CommandResult(Outcome.DONE))).state.work)
    }

    @Test
    fun everyEventKeepsARunningCommandOrCancelsIt() {
        val events = listOf(
            Event.Toggle, Event.Start(), Event.Pause, Event.Resume, Event.TurnOff, Event.Heard("Scroll down", named = false),
            Event.Heard("Stop.", named = false), Event.Heard("", named = false), Event.Heard("Kestrel", named = true),
            Event.Speaking, Event.Spotted(Keyword.STOP), Event.Spotted(Keyword.CANCEL), Event.Spotted(Keyword.KESTREL),
            Event.SpeechEnded, Event.TaskEnded(1, "Done."), Event.MicBusy(true), Event.MicBusy(false), Event.Screen(on = true),
            Event.Screen(on = false), Event.WaitEnded(1), Event.SettingsUnavailable, Event.NothingHeard,
            Event.CallState(CallState.IDLE), Event.CallState(CallState.RINGING), Event.CallState(CallState.ACTIVE), Event.Assist,
            Event.Options(WakeUpMode.STAY_OFF, pauseAfterSilence = true, callCommands = true), Event.Silent(1),
            Event.CommandAsks(1, "Which one?"), Event.CommandSays(1, "Calling."), Event.CommandCounts(1, counting = true),
            Event.AnswerWaitEnded(1), Event.CommandEnded(2, CommandResult(Outcome.DONE)),
        )
        val starts = listOf(
            runningCommand,
            runningCommand.copy(speeches = 1),
            runningCommand.copy(asking = true, wait = 1),
            runningCommand.copy(counting = true),
        )
        for (from in starts) {
            for (event in events) {
                val step = Rules.next(from, event)
                val goesOn = step.state.work == Work.RUNNING_COMMAND && step.state.command == 1 && step.state.running == from.running
                assertTrue("$event from $from", goesOn || Effect.CancelCommand in step.effects)
            }
        }
    }

    @Test
    fun anAnswerThatWasNoAnswerIsReadAsANewUtterance() {
        val ended = Rules.next(runningCommand, Event.CommandEnded(1, CommandResult(Outcome.CANCELLED, passOn = "Scroll down")))

        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Scroll down")), Effect.RunCommand(2, Command.Scroll(Direction.DOWN, null), "Scroll down")),
            ended.effects,
        )
        assertEquals(2, ended.state.command)
    }

    @Test
    fun theStatePrintsNoWords() {
        val printed = runningCommand.copy(lastCommand = Command.Bare("secret words", null), lastHeard = "secret words").toString()

        assertFalse(printed, printed.contains("secret") || printed.contains("Send"))
        assertEquals("AnswerCommand(1, no answer)", Effect.AnswerCommand(1, null).toString())
        assertEquals("AnswerCommand(1, 3 characters)", Effect.AnswerCommand(1, "Two").toString())
    }

    @Test
    fun leavingACommandWhileKestrelSpeaksStopsTheReplyOnce() {
        val speaking = runningCommand.copy(speeches = 1)

        // "Kestrel, turn off" while a question is spoken leaves the command, then turns Kestrel off.
        val asking = speaking.copy(asking = true, wait = 1)
        for ((from, event) in listOf(
            speaking to Event.TurnOff,
            speaking to Event.Spotted(Keyword.KESTREL),
            asking to Event.Heard("Kestrel, turn off.", named = true),
        )) {
            val effects = Rules.next(from, event).effects
            assertEquals("$event", 1, effects.count { it == Effect.StopSaying })
            assertTrue("$event", effects.indexOf(Effect.StopSaying) > effects.indexOf(Effect.CancelCommand))
        }
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

        assertEquals(listOf(Effect.StopSaying, Effect.Show(Feedback.AwaitingTask), Effect.WaitForTask()), step.effects)
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
        assertEquals(
            listOf(shown("Kestrel?"), Effect.Show(Feedback.AwaitingTask), Effect.WaitForTask(), Effect.Say(Reply.Yes)),
            again.effects,
        )
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
    fun kestrelSaysWhyItCannotOpenItsSettings() {
        val step = Rules.next(listening, Event.SettingsUnavailable)

        assertEquals(listOf(Effect.Say(Reply.SettingsNeedService)), step.effects)
        assertEquals(1, step.state.speeches)
        // A late report changes nothing once Kestrel is off.
        assertEquals(Step(State()), Rules.next(State(), Event.SettingsUnavailable))
    }

    @Test
    fun kestrelSaysThatAndroidKeptItsSettingsBack() {
        val step = Rules.next(listening, Event.SettingsBlocked)

        assertEquals(listOf(Effect.Say(Reply.SettingsBlocked)), step.effects)
        assertEquals(1, step.state.speeches)
        assertEquals(Step(State()), Rules.next(State(), Event.SettingsBlocked))
        // A ringing call keeps the verdict back, as it keeps back every other speech.
        val ringing = Rules.next(listening, Event.CallState(CallState.RINGING)).state
        assertEquals(Step(ringing), Rules.next(ringing, Event.SettingsBlocked))
    }

    @Test
    fun threeEmptyTranscriptsInARowShowTheHintOncePerSession() {
        var state = listening
        val effects = (1..6).map { Rules.next(state, Event.Heard("", named = false)).also { state = it.state }.effects }

        val hint = listOf(Effect.Show(Feedback.NoWords))
        assertEquals(listOf<List<Effect>>(emptyList(), emptyList(), hint, emptyList(), emptyList(), emptyList()), effects)

        // A new voice session, after Kestrel turned off and on, may show it again.
        state = Rules.next(Rules.next(state, Event.TurnOff).state, Event.Toggle).state
        val again = (1..3).map { Rules.next(state, Event.Heard("", named = false)).also { state = it.state }.effects }
        assertEquals(listOf<List<Effect>>(emptyList(), emptyList(), hint), again)
    }

    @Test
    fun aTranscriptWithWordsStartsTheCountAgain() {
        var state = listening
        for (text in listOf("", "", "Scroll down", "", "")) state = Rules.next(state, Event.Heard(text, named = false)).state

        assertEquals(2, state.emptyTranscripts)
        assertEquals(listOf(Effect.Show(Feedback.NoWords)), Rules.next(state, Event.Heard("", named = false)).effects)
    }

    @Test
    fun anUtteranceWithoutATranscriptRestartsTheWaitButIsNotCounted() {
        // The pipeline dropped the utterance, or transcribing it failed.
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1, emptyTranscripts = 2)

        val step = Rules.next(waiting, Event.NothingHeard)

        assertEquals(listOf(Effect.WaitForTask()), step.effects)
        assertEquals(2, step.state.wait)
        assertEquals(2, step.state.emptyTranscripts)
        assertEquals(Step(listening), Rules.next(listening, Event.NothingHeard))
        assertEquals(Step(State()), Rules.next(State(), Event.NothingHeard))
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
    fun stopBeforeOpenSettingsCancelsTheRunningTask() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)

        val step = Rules.next(running, Event.Heard("Stop. Kestrel, open settings.", named = true))

        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, open settings.")), Effect.CancelTask, Effect.OpenSettings),
            step.effects,
        )
        assertEquals(Work.IDLE, step.state.work)
        assertTrue(step.state.cancelled)
    }

    @Test
    fun aCallRingsWhileKestrelListensOrIsPausedAndTheModeComesBackAfterIt() {
        for (before in listOf(listening, paused)) {
            val ringing = Rules.next(before, Event.CallState(CallState.RINGING))
            assertEquals(Mode.RINGING, ringing.state.mode)
            assertEquals(before.mode, ringing.state.beforeCall)
            assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.RINGING))), ringing.effects)

            val answered = Rules.next(ringing.state, Event.CallState(CallState.ACTIVE))
            assertEquals(Mode.IN_CALL, answered.state.mode)
            assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.IN_CALL))), answered.effects)

            val ended = Rules.next(answered.state, Event.CallState(CallState.IDLE))
            assertEquals(Step(before, listOf(Effect.Show(Feedback.ModeChanged(before.mode)))), ended)
        }
    }

    @Test
    fun aCallThatRingsStopsTheSpeechAndCancelsTheTask() {
        val busy = listening.copy(work = Work.RUNNING_TASK, task = 2, speeches = 1)

        val ringing = Rules.next(busy, Event.CallState(CallState.RINGING))

        assertEquals(
            listOf(Effect.StopSaying, Effect.CancelTask, Effect.Show(Feedback.ModeChanged(Mode.RINGING))),
            ringing.effects,
        )
        assertEquals(Work.IDLE, ringing.state.work)
        assertTrue(ringing.state.cancelled)
        // A wait for a task ends too, and nothing comes back after the call (section 14.4).
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1)
        assertEquals(Work.IDLE, Rules.next(waiting, Event.CallState(CallState.RINGING)).state.work)
    }

    @Test
    fun aCallThatIsActiveWithoutRingingIsInCall() {
        // An outgoing call, or one that was active when Kestrel started.
        val active = Rules.next(paused, Event.CallState(CallState.ACTIVE))

        assertEquals(Mode.IN_CALL, active.state.mode)
        assertEquals(Mode.PAUSED, active.state.beforeCall)
        assertEquals(Mode.PAUSED, Rules.next(active.state, Event.CallState(CallState.IDLE)).state.mode)
        // A second report of a call changes nothing.
        assertEquals(Step(active.state), Rules.next(active.state, Event.CallState(CallState.ACTIVE)))
        assertEquals(Step(active.state), Rules.next(active.state, Event.CallState(CallState.RINGING)))
    }

    @Test
    fun callsChangeNothingWhileKestrelIsOffOrWithoutACall() {
        for (call in CallState.entries) {
            assertEquals(Step(State()), Rules.next(State(), Event.CallState(call)))
        }
        assertEquals(Step(listening), Rules.next(listening, Event.CallState(CallState.IDLE)))
    }

    @Test
    fun whileACallRingsKestrelRecordsAlsoOnTheLockScreenButNotDuringTheCall() {
        val locked = listening.copy(screenOn = false)
        val ringing = Rules.next(locked, Event.CallState(CallState.RINGING)).state

        assertFalse(locked.microphone)
        // Section 8.5: the one exception to the rule that Kestrel records only while the phone is unlocked. The call
        // commands come without the name.
        assertTrue(ringing.microphone)
        assertEquals(Recognition.ALL, ringing.recognition)
        val inCall = Rules.next(ringing.copy(screenOn = true), Event.CallState(CallState.ACTIVE)).state
        assertFalse(inCall.microphone)
        assertEquals(Recognition.NONE, inCall.recognition)
    }

    @Test
    fun duringACallTheTogglePauseAndResumeChooseTheModeAfterIt() {
        val ringing = Rules.next(listening, Event.CallState(CallState.RINGING)).state

        val chosen = Rules.next(ringing, Event.Pause)
        assertEquals(Mode.RINGING, chosen.state.mode)
        assertEquals(Mode.PAUSED, chosen.state.beforeCall)
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.PAUSED))), chosen.effects)
        assertEquals(Step(chosen.state), Rules.next(chosen.state, Event.Pause))
        assertEquals(Mode.LISTENING, Rules.next(chosen.state, Event.Toggle).state.beforeCall)
        assertEquals(Mode.LISTENING, Rules.next(chosen.state, Event.Resume).state.beforeCall)
        assertEquals(Mode.PAUSED, Rules.next(chosen.state, Event.CallState(CallState.IDLE)).state.mode)
    }

    @Test
    fun transcriptsAndKeywordsOtherThanTheCallCommandsAreIgnoredDuringACall() {
        val ringing = Rules.next(listening, Event.CallState(CallState.RINGING)).state

        assertEquals(Step(ringing), Rules.next(ringing, Event.Heard("Kestrel, open settings.", named = true)))
        assertEquals(Step(ringing), Rules.next(ringing, Event.Heard("Kestrel", named = true)))
        val speaking = ringing.copy(speeches = 1)
        assertEquals(Step(speaking), Rules.next(speaking, Event.Spotted(Keyword.KESTREL)))
        // Transcripts without words do not count towards the hint during a call (section 14.6).
        val counting = ringing.copy(emptyTranscripts = 2)
        assertEquals(Step(counting), Rules.next(counting, Event.Heard("", named = false)))
        assertEquals(Step(counting), Rules.next(counting, Event.NothingHeard))
    }

    @Test
    fun turningOffDuringACallForgetsTheCall() {
        val inCall = Rules.next(listening, Event.CallState(CallState.ACTIVE)).state

        val off = Rules.next(inCall, Event.TurnOff)

        assertEquals(State(), off.state)
        // The end of the call changes nothing afterwards.
        assertEquals(Step(State()), Rules.next(off.state, Event.CallState(CallState.IDLE)))
    }

    @Test
    fun whileACallRingsOnlyTheCallCommandsCountAlsoOnTheLockScreen() {
        val ringing = Rules.next(listening.copy(screenOn = false), Event.CallState(CallState.RINGING)).state

        assertEquals(
            Step(ringing, listOf(Effect.Show(Feedback.Heard("Answer.")), Effect.CallCommand(Request.Answer(speaker = false)))),
            Rules.next(ringing, Event.Heard("Answer.", named = false)),
        )
        assertEquals(
            Effect.CallCommand(Request.Answer(speaker = true)),
            Rules.next(ringing, Event.Heard("Kestrel, answer on speaker, please.", named = true)).effects.last(),
        )
        assertEquals(Effect.CallCommand(Request.Decline), Rules.next(ringing, Event.Heard("Decline, Kestrel.", named = false)).effects.last())
        assertEquals(Step(ringing), Rules.next(ringing, Event.Heard("Hang up.", named = false)))
    }

    @Test
    fun duringACallItsCommandsCountOnlyWithTheSetting() {
        val inCall = Rules.next(listening, Event.CallState(CallState.ACTIVE)).state

        assertEquals(Step(inCall), Rules.next(inCall, Event.Heard("Kestrel, hang up.", named = true)))
        val commands = inCall.copy(callCommands = true)
        assertEquals(
            Effect.CallCommand(Request.HangUp),
            Rules.next(commands, Event.Heard("Kestrel, hang up.", named = true)).effects.last(),
        )
        assertEquals(Effect.CallCommand(Request.Mute), Rules.next(commands, Event.Heard("Kestrel, mute.", named = true)).effects.last())
        assertEquals(
            Effect.CallCommand(Request.Speaker),
            Rules.next(commands, Event.Heard("Kestrel, speaker, please.", named = true)).effects.last(),
        )
        assertEquals(Step(commands), Rules.next(commands, Event.Heard("Kestrel, answer.", named = true)))
        // The caller's voice can come through the speaker, so a command without the name first is ignored.
        assertEquals(Step(commands), Rules.next(commands, Event.Heard("Hang up.", named = false)))
        assertEquals(Step(commands), Rules.next(commands, Event.Heard("Mute.", named = false)))
        // With the setting, Kestrel records during the call while the phone is unlocked; the other person hears the
        // commands too (section 8.7). It transcribes only the utterances at whose start the keyword spotter heard
        // "Kestrel", so never the call's conversation.
        assertTrue(commands.microphone)
        assertEquals(Recognition.NAMED, commands.recognition)
        assertFalse(commands.copy(screenOn = false).microphone)
    }

    @Test
    fun theAssistantGestureStartsListeningAndWaitsForATask() {
        val expected = listOf(
            Effect.Show(Feedback.ModeChanged(Mode.LISTENING)),
            Effect.Show(Feedback.AwaitingTask),
            Effect.WaitForTask(),
            Effect.Say(Reply.Yes),
        )
        val step = Rules.next(paused, Event.Assist)
        assertEquals(expected, step.effects)
        assertEquals(Mode.LISTENING, step.state.mode)
        assertEquals(Work.AWAITING_TASK, step.state.work)
        // While Kestrel listens, it only asks.
        assertEquals(expected.drop(1), Rules.next(listening, Event.Assist).effects)
        // Listening turns Kestrel on first and sends the Assist once the session is open, so one that comes while
        // Kestrel is off was queued behind a turn-off, and changes nothing.
        assertEquals(Step(State()), Rules.next(State(), Event.Assist))
        // Section 8.5: on the lock screen Kestrel does not record, so it only starts listening and asks nothing.
        val locked = paused.copy(screenOn = false)
        assertEquals(
            Step(locked.copy(mode = Mode.LISTENING), listOf(Effect.Show(Feedback.ModeChanged(Mode.LISTENING)))),
            Rules.next(locked, Event.Assist),
        )
        assertEquals(
            Step(
                locked.copy(mode = Mode.LISTENING, speeches = 1),
                listOf(Effect.StopSaying, Effect.Show(Feedback.ModeChanged(Mode.LISTENING))),
            ),
            Rules.next(locked.copy(speeches = 1), Event.Assist),
        )
    }

    @Test
    fun theAssistantGestureOverKestrelsVoiceOrWhileATaskRunsActsLikeTheName() {
        // As after an interruption with "Kestrel": Kestrel stops speaking and waits for the task.
        assertEquals(
            listOf(Effect.StopSaying, Effect.Show(Feedback.AwaitingTask), Effect.WaitForTask()),
            Rules.next(listening.copy(speeches = 1), Event.Assist).effects,
        )
        val running = paused.copy(work = Work.RUNNING_TASK, task = 1, speeches = 1)
        assertEquals(Step(running, listOf(Effect.StopSaying)), Rules.next(running, Event.Assist))
        val silent = running.copy(speeches = 0)
        assertEquals(Step(silent), Rules.next(silent, Event.Assist))
    }

    @Test
    fun theAssistantGestureWhilePausedOverKestrelsVoiceListensStopsAndWaitsForATask() {
        val step = Rules.next(paused.copy(speeches = 1), Event.Assist)

        assertEquals(
            listOf(
                Effect.Show(Feedback.ModeChanged(Mode.LISTENING)),
                Effect.StopSaying,
                Effect.Show(Feedback.AwaitingTask),
                Effect.WaitForTask(),
            ),
            step.effects,
        )
        assertEquals(Mode.LISTENING, step.state.mode)
        assertEquals(Work.AWAITING_TASK, step.state.work)
    }

    @Test
    fun theAssistantGestureChangesNothingDuringACall() {
        val ringing = Rules.next(listening, Event.CallState(CallState.RINGING)).state

        assertEquals(Step(ringing), Rules.next(ringing, Event.Assist))
    }

    @Test
    fun afterAnUnlockKestrelFollowsTheWakeUpSetting() {
        fun unlocked(state: State, wakeUp: WakeUpMode): Step {
            val chosen = Rules.next(state, Event.Options(wakeUp, pauseAfterSilence = false, callCommands = false)).state
            // The running service has reported the screen on and unlocked before, in this run.
            return Rules.next(chosen.copy(screenOn = false, screenSeen = true), Event.Screen(on = true))
        }

        assertEquals(Mode.PAUSED, unlocked(paused, WakeUpMode.RESUME).state.mode)
        val listened = unlocked(paused, WakeUpMode.LISTEN)
        assertEquals(Mode.LISTENING, listened.state.mode)
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.LISTENING))), listened.effects)
        val off = unlocked(listening, WakeUpMode.STAY_OFF)
        assertEquals(Mode.OFF, off.state.mode)
        assertEquals(Effect.StopService, off.effects.last())
        // Only a change from off to on counts, and a call keeps its mode.
        val stayOff = Rules.next(listening, Event.Options(WakeUpMode.STAY_OFF, pauseAfterSilence = false, callCommands = false)).state
        assertEquals(Step(stayOff.copy(screenSeen = true)), Rules.next(stayOff, Event.Screen(on = true)))
        val ringing = stayOff.copy(mode = Mode.RINGING, beforeCall = Mode.LISTENING, screenOn = false)
        assertEquals(Mode.RINGING, Rules.next(ringing, Event.Screen(on = true)).state.mode)
        // A turn-off forgets the reports, and an unlock after a start on the lock screen is no wake-up.
        assertFalse(Rules.next(listening.copy(screenOn = false, screenSeen = true), Event.TurnOff).state.screenSeen)
        val startedLocked = Rules.next(stayOff, Event.Screen(on = false)).state
        assertEquals(Mode.LISTENING, Rules.next(startedLocked, Event.Screen(on = true)).state.mode)
        // A report of the screen on, then a lock and an unlock: that unlock is a wake-up.
        val used = Rules.next(Rules.next(stayOff, Event.Screen(on = true)).state, Event.Screen(on = false)).state
        assertEquals(Mode.OFF, Rules.next(used, Event.Screen(on = true)).state.mode)
        // The assistant gesture on the lock screen asks the owner to speak after the unlock, so that unlock keeps Kestrel
        // on.
        val gestured = Rules.next(stayOff.copy(mode = Mode.PAUSED, screenOn = false, screenSeen = true), Event.Assist).state
        assertEquals(Mode.LISTENING, Rules.next(gestured, Event.Screen(on = true)).state.mode)
    }

    @Test
    fun thirtySecondsWithoutSpeechPauseKestrelWhenTheSettingIsOn() {
        // The timer starts when the setting goes on while Kestrel listens, and again when the owner starts to speak.
        val on = Rules.next(listening, Event.Options(WakeUpMode.RESUME, pauseAfterSilence = true, callCommands = false))
        assertEquals(listOf(Effect.TimeSilence), on.effects)
        assertEquals(1, on.state.silence)
        val spoke = Rules.next(on.state, Event.Speaking)
        assertEquals(listOf(Effect.TimeSilence), spoke.effects)
        assertEquals(2, spoke.state.silence)
        // The end of an utterance starts it again, so that the time counts from the end of the owner's speech.
        assertEquals(listOf(Effect.TimeSilence), Rules.next(spoke.state, Event.NothingHeard).effects)
        assertEquals(listOf(Effect.TimeSilence), Rules.next(spoke.state, Event.Heard("", named = false)).effects)

        // The end of an older timer changes nothing; the end of the latest one pauses Kestrel.
        assertEquals(Step(spoke.state), Rules.next(spoke.state, Event.Silent(1)))
        val silent = Rules.next(spoke.state, Event.Silent(2))
        assertEquals(Mode.PAUSED, silent.state.mode)
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.PAUSED))), silent.effects)

        // Listening again starts the timer again.
        assertEquals(
            listOf(Effect.Show(Feedback.ModeChanged(Mode.LISTENING)), Effect.TimeSilence),
            Rules.next(silent.state, Event.Toggle).effects,
        )
        // The owner gets the whole 30 s again when Kestrel can hear again: after an unlock, and after a reply.
        val dark = listening.copy(pauseAfterSilence = true, silence = 3, screenOn = false)
        assertEquals(listOf(Effect.TimeSilence), Rules.next(dark, Event.Screen(on = true)).effects)
        val replying = listening.copy(pauseAfterSilence = true, silence = 3, speeches = 1)
        assertEquals(listOf(Effect.TimeSilence), Rules.next(replying, Event.SpeechEnded).effects)
        // Without the setting, nothing times the silence.
        assertEquals(emptyList<Effect>(), Rules.next(listening, Event.Speaking).effects)
        assertEquals(Step(listening.copy(silence = 2)), Rules.next(listening.copy(silence = 2), Event.Silent(2)))
    }

    @Test
    fun theSilenceTimerStartsAgainWhileKestrelIsBusyOrDoesNotRecord() {
        val timed = listening.copy(pauseAfterSilence = true, silence = 1)

        for (busy in listOf(timed.copy(speeches = 1), timed.copy(work = Work.RUNNING_TASK, task = 1), timed.copy(screenOn = false))) {
            assertEquals(Step(busy.copy(silence = 2), listOf(Effect.TimeSilence)), Rules.next(busy, Event.Silent(1)))
        }
    }

    @Test
    fun theSilenceTimerDoesNotCountWhileTheMicrophoneIsBlocked() {
        val blocked = listening.copy(pauseAfterSilence = true, silence = 1, micBusy = true)

        // Kestrel does not pause after 30 s while Android blocks its microphone.
        assertEquals(Step(blocked.copy(silence = 2), listOf(Effect.TimeSilence)), Rules.next(blocked, Event.Silent(1)))
        // The timer starts again when the microphone comes back.
        assertEquals(
            Step(blocked.copy(silence = 2, micBusy = false), listOf(Effect.TimeSilence)),
            Rules.next(blocked, Event.MicBusy(busy = false)),
        )
    }

    @Test
    fun neverMindEndsTheWaitForATask() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1)

        assertEquals(
            Step(listening.copy(wait = 1), listOf(Effect.Show(Feedback.Heard("Never mind.")))),
            Rules.next(waiting, Event.Heard("Never mind.", named = false)),
        )
    }

    // Design D2 of sub-project 5: the task's own state, its questions, touches and hand-overs.
    private val runningTask = listening.copy(work = Work.RUNNING_TASK, task = 1, taskPhase = TaskPhase.RUNNING)

    private fun asking(kind: TaskAsk, voice: Boolean = true): State =
        Rules.next(runningTask, Event.TaskAsks(1, "Shall I?", kind, voice)).state

    private val handOverText = "Please enter your password, then say 'Kestrel, continue'."

    // A hand-over whose sentence has been spoken.
    private fun handedOver(from: State = runningTask): State =
        Rules.next(Rules.next(from, Event.TaskHandsOver(1, handOverText)).state, Event.SpeechEnded).state

    private fun heard(state: State, text: String, named: Boolean = false) = Rules.next(state, Event.Heard(text, named))

    @Test
    fun aTaskStartsInItsRunningPhaseAndEndsWithoutOne() {
        val started = heard(listening, "Kestrel, open Viber.", named = true).state
        assertEquals(TaskPhase.RUNNING, started.taskPhase)
        assertTrue(started.taskExists)

        val ended = Rules.next(started, Event.TaskEnded(1, "Done."))
        assertEquals(null, ended.state.taskPhase)
        assertFalse(ended.state.taskExists)
    }

    @Test
    fun aTaskSaysItsTextAndShowsIt() {
        val step = Rules.next(runningTask, Event.TaskSays(1, "Android gave Kestrel only the photos that you selected."))

        assertEquals(
            listOf(
                Effect.Show(Feedback.TaskText("Android gave Kestrel only the photos that you selected.")),
                Effect.Say(Reply.Text("Android gave Kestrel only the photos that you selected.")),
            ),
            step.effects,
        )
        // An older task says nothing.
        assertEquals(Step(runningTask), Rules.next(runningTask, Event.TaskSays(0, "Late.")))
    }

    @Test
    fun aTaskQuestionIsSpokenKeptOnTheBarAndWaitsForItsAnswer() {
        val step = Rules.next(runningTask, Event.TaskAsks(1, "I'm not getting anywhere. Should I keep trying?", TaskAsk.YES_NO))

        assertEquals(
            listOf(
                Effect.Show(Feedback.TaskText("I'm not getting anywhere. Should I keep trying?", keep = true)),
                Effect.Say(Reply.Text("I'm not getting anywhere. Should I keep trying?")),
                Effect.WaitForAnswer(),
            ),
            step.effects,
        )
        assertEquals(TaskAsk.YES_NO, step.state.taskAsking)
        assertEquals(Work.RUNNING_TASK, step.state.work)
        // The wait starts again when the question has been spoken, and grows once the owner speaks.
        val spoken = Rules.next(step.state, Event.SpeechEnded)
        assertEquals(listOf(Effect.WaitForAnswer()), spoken.effects)
        assertEquals(Recognition.ALL, spoken.state.recognition)
        assertEquals(listOf(Effect.WaitForAnswer(answering = true)), Rules.next(spoken.state, Event.Speaking).effects)
        // A second question while one is open is ignored: questions come one at a time.
        assertEquals(Step(step.state), Rules.next(step.state, Event.TaskAsks(1, "Another?", TaskAsk.FREE)))
    }

    @Test
    fun aYesOrNoQuestionTakesOnlyItsAnswers() {
        val question = asking(TaskAsk.YES_NO)

        for (yes in listOf("Yes.", "Yes, please.", "Continue", "Go on.")) {
            val step = heard(question, yes)
            assertEquals(yes, Effect.AnswerTask(1, TaskAnswers.YES), step.effects.last())
            assertEquals(null, step.state.taskAsking)
        }
        assertEquals(Effect.AnswerTask(1, TaskAnswers.YES), heard(question, "Kestrel, continue.", named = true).effects.last())
        assertEquals(Effect.AnswerTask(1, TaskAnswers.NO), heard(question, "No.").effects.last())
        // Other speech is only shown, and the question goes on.
        assertEquals(Step(question, listOf(Effect.Show(Feedback.Heard("Open Viber")))), heard(question, "Open Viber"))
    }

    @Test
    fun aConfirmationGoesAheadOnConfirmYesOrSendIt() {
        val question = asking(TaskAsk.CONFIRM)

        for (yes in listOf("Confirm.", "Yes", "Send it!", "Kestrel, confirm")) {
            assertEquals(yes, Effect.AnswerTask(1, TaskAnswers.YES), heard(question, yes, named = yes.startsWith("Kestrel")).effects.last())
        }
        assertEquals(Effect.AnswerTask(1, TaskAnswers.NO), heard(question, "No").effects.last())
        assertEquals(listOf(Effect.Show(Feedback.Heard("Go on."))), heard(question, "Go on.").effects)
    }

    @Test
    fun theSilenceAfterATaskQuestionIsNoAnswer() {
        val question = asking(TaskAsk.CONFIRM)

        val ended = Rules.next(question, Event.AnswerWaitEnded(question.wait))

        assertEquals(listOf(Effect.AnswerTask(1, null)), ended.effects)
        assertEquals(null, ended.state.taskAsking)
        // The end of an older wait changes nothing.
        assertEquals(Step(question), Rules.next(question, Event.AnswerWaitEnded(question.wait - 1)))
    }

    @Test
    fun stopAndCancelCancelTheTaskInEveryKindOfQuestion() {
        for (kind in TaskAsk.entries) {
            for (text in listOf("Stop.", "Cancel", "Okay, cancel that")) {
                val step = heard(asking(kind), text)
                assertTrue("$kind $text", Effect.CancelTask in step.effects)
                assertEquals("$kind $text", null, step.state.taskPhase)
                assertEquals("$kind $text", null, step.state.taskAsking)
            }
        }
    }

    @Test
    fun aFreeQuestionTakesTheNextTranscriptAndANumberQuestionANumber() {
        assertEquals(Effect.AnswerTask(1, "The blue one."), heard(asking(TaskAsk.FREE), "The blue one.").effects.last())
        assertEquals(Effect.AnswerTask(1, "12"), heard(asking(TaskAsk.PICK_NUMBER), "12").effects.last())
        val number = asking(TaskAsk.PICK_NUMBER)
        assertEquals(Step(number, listOf(Effect.Show(Feedback.Heard("the red one")))), heard(number, "the red one"))
    }

    @Test
    fun whileOnlyTheCardMayAnswerSpokenAnswersDoNotCount() {
        val question = asking(TaskAsk.CONFIRM, voice = false)

        assertEquals(Step(question, listOf(Effect.Show(Feedback.Heard("Confirm.")))), heard(question, "Confirm."))
        assertTrue(Effect.CancelTask in heard(question, "Cancel.").effects)
        assertEquals(listOf(Effect.AnswerTask(1, null)), Rules.next(question, Event.AnswerWaitEnded(question.wait)).effects)
        // The card answered: the agent says that it acts again.
        assertEquals(null, Rules.next(question, Event.TaskBusy(1)).state.taskAsking)
    }

    @Test
    fun theOwnersTouchCancelsARunningTaskButNotAHandOver() {
        assertEquals(listOf(Effect.CancelTask), Rules.next(runningTask, Event.OwnerTouched).effects)
        assertTrue(Effect.CancelTask in Rules.next(asking(TaskAsk.CONFIRM), Event.OwnerTouched).effects)
        val handed = handedOver()
        assertEquals(Step(handed), Rules.next(handed, Event.OwnerTouched))
        assertEquals(Step(listening), Rules.next(listening, Event.OwnerTouched))
    }

    @Test
    fun aHandOverKeepsItsSentenceOnTheBarAndStartsItsThreeMinutes() {
        val step = Rules.next(runningTask, Event.TaskHandsOver(1, "This is a payment. Please finish it yourself, then say 'Kestrel, continue'."))

        assertEquals(
            listOf(
                Effect.Show(Feedback.TaskText("This is a payment. Please finish it yourself, then say 'Kestrel, continue'.", keep = true)),
                Effect.Say(Reply.Text("This is a payment. Please finish it yourself, then say 'Kestrel, continue'.")),
                Effect.TimeHandOver,
            ),
            step.effects,
        )
        assertEquals(TaskPhase.HANDED_OVER, step.state.taskPhase)
        assertEquals(Work.IDLE, step.state.work)
        assertEquals(1, step.state.handOver)
        assertTrue(step.state.taskExists)
    }

    @Test
    fun duringAHandOverTheInstantCommandsWorkAlsoInThePausedMode() {
        for (from in listOf(runningTask, runningTask.copy(mode = Mode.PAUSED))) {
            val handed = handedOver(from)
            assertEquals(Recognition.ALL, handed.recognition)
            val command = heard(handed, "Scroll down")
            assertEquals(Work.RUNNING_COMMAND, command.state.work)
            assertTrue(command.effects.last() is Effect.RunCommand)
            // The command's end leaves the hand-over open.
            val ended = Rules.next(command.state, Event.CommandEnded(command.state.command, CommandResult(Outcome.DONE)))
            assertEquals(Work.IDLE, ended.state.work)
            assertEquals(TaskPhase.HANDED_OVER, ended.state.taskPhase)
        }
    }

    @Test
    fun kestrelContinueEndsAHandOverAndCancelEndsTheTask() {
        val handed = handedOver()

        val continued = heard(handed, "Kestrel, continue.", named = true)
        assertEquals(listOf(Effect.Show(Feedback.Heard("Kestrel, continue.")), Effect.ContinueTask(1)), continued.effects)
        assertEquals(TaskPhase.RUNNING, continued.state.taskPhase)
        assertEquals(Work.RUNNING_TASK, continued.state.work)

        for (text in listOf("Cancel.", "Stop", "Kestrel, cancel")) {
            val cancelled = heard(handed, text, named = text.startsWith("Kestrel"))
            assertTrue(text, Effect.CancelTask in cancelled.effects)
            assertEquals(text, null, cancelled.state.taskPhase)
            assertTrue(text, cancelled.state.cancelled)
        }
        // "Kestrel" and then "never mind" ends only the wait for a task; "Kestrel" and then "stop" ends the task.
        val awaiting = heard(handed, "Kestrel", named = true).state
        assertFalse(Effect.CancelTask in heard(awaiting, "Never mind.").effects)
        assertTrue(Effect.CancelTask in heard(awaiting, "Stop.").effects)
        assertTrue(Effect.CancelTask in heard(awaiting, "Kestrel, cancel.", named = true).effects)
    }

    @Test
    fun kestrelContinueDuringACommandOfAHandOverLeavesTheCommandAndContinues() {
        val command = heard(handedOver(), "Scroll down").state

        val continued = heard(command, "Kestrel, continue", named = true)

        assertEquals(listOf(Effect.CancelCommand, Effect.Show(Feedback.Heard("Kestrel, continue")), Effect.ContinueTask(1)), continued.effects)
        assertEquals(Work.RUNNING_TASK, continued.state.work)
    }

    @Test
    fun aNewTaskDuringAHandOverWaitsAndStopFirstReplacesIt() {
        val handed = handedOver()

        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, open Viber.")), Effect.Show(Feedback.FinishHandOverFirst)),
            heard(handed, "Kestrel, open Viber.", named = true).effects,
        )
        val replaced = heard(handed, "Stop. Kestrel, open Viber.", named = true)
        assertEquals(listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, open Viber.")), Effect.CancelTask, Effect.RunTask(2, "open Viber.")), replaced.effects)
        assertEquals(TaskPhase.RUNNING, replaced.state.taskPhase)
        // After "Kestrel" on its own, "Yes?" takes "continue" for the hand-over, and refuses a new task.
        val awaiting = heard(handed, "Kestrel", named = true).state
        assertEquals(Effect.ContinueTask(1), heard(awaiting, "Continue.").effects.last())
        assertEquals(Effect.Show(Feedback.FinishHandOverFirst), heard(awaiting, "Open Viber.").effects.last())
    }

    @Test
    fun kestrelContinueWithoutAHandOverSaysSo() {
        val step = heard(listening, "Kestrel, continue, please.", named = true)

        assertEquals(listOf(Effect.Show(Feedback.Heard("Kestrel, continue, please.")), Effect.Say(Reply.NoTaskToContinue)), step.effects)
        // While the task runs, it is ignored.
        assertEquals(Step(runningTask), heard(runningTask, "Kestrel, continue", named = true))
    }

    @Test
    fun aHandOverThatLastsThreeMinutesEndsTheTaskWithItsReport() {
        val handed = handedOver()

        assertEquals(listOf(Effect.ExpireHandOver(1)), Rules.next(handed, Event.HandOverEnded(1)).effects)
        // The timer of an older hand-over changes nothing.
        assertEquals(Step(handed), Rules.next(handed, Event.HandOverEnded(0)))
        // The task's report comes in every phase, also while a command runs.
        val report = Rules.next(handed, Event.TaskEnded(1, "I stopped because the hand-over took too long."))
        assertEquals(null, report.state.taskPhase)
        assertEquals(Effect.Say(Reply.Text("I stopped because the hand-over took too long.")), report.effects.last())
        val command = heard(handed, "Scroll down").state
        val late = Rules.next(command, Event.TaskEnded(1, "Report."))
        assertEquals(Work.RUNNING_COMMAND, late.state.work)
        assertEquals(null, late.state.taskPhase)
        assertEquals(Effect.Say(Reply.Text("Report.")), late.effects.last())
    }

    @Test
    fun aHandOverThatEndedByItselfRunsTheTaskAgain() {
        val busy = Rules.next(handedOver(), Event.TaskBusy(1))

        assertEquals(TaskPhase.RUNNING, busy.state.taskPhase)
        assertEquals(Work.RUNNING_TASK, busy.state.work)
        assertEquals(Step(runningTask), Rules.next(runningTask, Event.TaskBusy(0)))
    }

    @Test
    fun theSpottersStopCancelsATaskDuringItsHandOverSentence() {
        val speaking = Rules.next(runningTask, Event.TaskHandsOver(1, handOverText)).state

        val step = Rules.next(speaking, Event.Spotted(Keyword.STOP))

        assertEquals(listOf(Effect.StopSaying, Effect.CancelTask), step.effects)
        assertEquals(null, step.state.taskPhase)
    }

    @Test
    fun turningOffEndsATaskInEveryPhase() {
        for (state in listOf(runningTask, asking(TaskAsk.FREE), handedOver())) {
            val off = Rules.next(state, Event.TurnOff)
            assertTrue(Effect.CancelTask in off.effects)
            assertEquals(null, off.state.taskPhase)
            assertEquals(null, off.state.taskAsking)
        }
    }

    @Test
    fun theTaskEventsAndEffectsPrintNoWords() {
        val printed = listOf(
            Event.TaskSays(1, "secret"),
            Event.TaskAsks(1, "secret", TaskAsk.FREE),
            Event.TaskHandsOver(1, "secret"),
            Effect.AnswerTask(1, "secret"),
            Feedback.TaskText("secret"),
        ).joinToString()

        assertFalse(printed, printed.contains("secret"))
    }
}
