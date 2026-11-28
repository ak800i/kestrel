package dev.kestrel.app.orchestrator

import dev.kestrel.commands.CallTarget
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Direction
import dev.kestrel.commands.GlobalCommand
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.commands.Target
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputIntent
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

    // What the voice reads of the conversation (design 5.5 of sub-project 7): an open conversation with a goal in [phase]
    // or none.
    private fun goal(phase: GoalPhase?, state: State = listening): State = state.copy(conversation = VoiceView(open = true, goal = phase))

    private val acting = goal(GoalPhase.ACTING)

    // The voice asked the conversation's question number 4 of [kind], and has spoken it; a goal that acted asks now.
    private fun asked(kind: QuestionKind, from: State = acting, voice: Boolean = true): State {
        val phase = if (from.goal == GoalPhase.ACTING) GoalPhase.ASKING else from.goal
        val view = from.conversation.copy(goal = phase, asking = kind, question = 4)
        return Rules.next(from.copy(conversation = view), Event.ChannelAsks(4, "Shall I?", kind, voice)).state.copy(speeches = 0)
    }

    @Test
    fun theToggleStartsListeningThenPausesAndListensAgain() {
        val started = Rules.next(State(), Event.Toggle)

        assertEquals(Mode.LISTENING, started.state.mode)
        // Turning on ends what Kestrel's own voice speaks (plan 7F).
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.LISTENING)), Effect.EndOwnVoice), started.effects)
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
    fun turningOffEndsTheSpeechButNotTheGoal() {
        val busy = acting.copy(speeches = 1, micBusy = true)

        val off = Rules.next(busy, Event.TurnOff)

        // Plan 7B's decision 10: turning off changes only the mode, and the goal goes on; it ends the session's speech as a
        // switch of voice, not a stop (plan 7F).
        assertEquals(listOf(Effect.EndSpeech, Effect.Show(Feedback.ModeChanged(Mode.OFF)), Effect.StopService), off.effects)
        assertEquals(GoalPhase.ACTING, off.state.goal)
        assertEquals(0, off.state.speeches)
        assertFalse(off.state.micBusy)
        assertEquals(Recognition.ALL, Rules.next(off.state, Event.Toggle).state.recognition)
    }

    @Test
    fun aCommandWithTheNameGoesToTheConversation() {
        val heard = Event.Heard("Kestrel, open the settings.", named = true)

        val step = Rules.next(listening, heard)

        assertEquals(Work.IDLE, step.state.work)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, open the settings.")), Effect.Converse(InputIntent.Command("open the settings."), heard)),
            step.effects,
        )
        // Once its goal acts, every utterance is transcribed, so that "stop" counts without the name.
        assertEquals(Recognition.ALL, Rules.next(step.state, Event.ConversationChanged(acting.conversation)).state.recognition)
    }

    @Test
    fun aCommandWhilePausedGoesToTheConversationAndKestrelStaysPaused() {
        assertEquals(Recognition.NAMED, paused.recognition)
        val heard = Event.Heard("Kestrel, open Viber", named = true)

        val step = Rules.next(paused, heard)
        assertEquals(Mode.PAUSED, step.state.mode)
        assertEquals(Effect.Converse(InputIntent.Command("open Viber"), heard), step.effects.last())

        val acts = Rules.next(step.state, Event.ConversationChanged(acting.conversation)).state
        assertEquals(Recognition.ALL, acts.recognition)
        val done = Rules.next(acts, Event.ConversationChanged(acting.conversation.copy(goal = null)))
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
        val spoken = Rules.next(asked.state, Event.SpeechEnded(Reply.Yes, spoken = true))
        assertEquals(listOf(Effect.WaitForTask()), spoken.effects)
        assertEquals(2, spoken.state.wait)
        val ready = spoken.state
        assertEquals(Recognition.ANSWER, ready.recognition)

        val heard = Event.Heard("Open the settings.", named = false)
        val task = Rules.next(ready, heard)
        assertEquals(Effect.Converse(InputIntent.Command("Open the settings."), heard), task.effects.last())
        assertEquals(Work.IDLE, task.state.work)
    }

    @Test
    fun theAnswerToYesIsHeldLikeANamedUtterance() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1)

        assertEquals(Recognition.ANSWER, waiting.recognition)
        assertEquals(Recognition.ANSWER, paused.copy(work = Work.AWAITING_TASK, wait = 1).recognition)
        // While "Yes?" is spoken, nothing is transcribed, and while a goal works, every utterance goes out at once.
        assertEquals(Recognition.NONE, waiting.copy(speeches = 1).recognition)
        assertEquals(Recognition.ALL, goal(GoalPhase.ACTING, paused).recognition)
    }

    @Test
    fun theWaitForATaskEnds() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 2)
        assertEquals(Work.IDLE, Rules.next(waiting, Event.WaitEnded(2)).state.work)
        // The end of an older wait changes nothing.
        assertEquals(Step(waiting), Rules.next(waiting, Event.WaitEnded(1)))

        val running = acting.copy(wait = 2)
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

        assertEquals(Step(twoReplies.copy(speeches = 1)), Rules.next(twoReplies, Event.SpeechEnded(Reply.Yes, spoken = true)))
        assertEquals(Step(listening), Rules.next(listening, Event.SpeechEnded(Reply.Yes, spoken = true)))
    }

    @Test
    fun whileAGoalWorksOnlyStopAndWhatFollowsTheNameCount() {
        // Design D9 of sub-project 7: steering by voice needs "Kestrel".
        assertEquals(Step(acting), Rules.next(acting, Event.Heard("Open Viber", named = false)))
        val steering = Event.Heard("Kestrel, open Viber", named = true)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, open Viber")), Effect.Converse(InputIntent.Command("open Viber"), steering)),
            Rules.next(acting, steering).effects,
        )

        val stopped = Rules.next(acting, Event.Heard("Stop.", named = false))
        assertEquals(listOf(Effect.Show(Feedback.Heard("Stop.")), Effect.Converse(InputIntent.Stop, Event.Heard("Stop.", named = false))), stopped.effects)
    }

    @Test
    fun stopThenACommandGivesTheConversationAStopAndThenTheCommand() {
        val heard = Event.Heard("Stop. Kestrel, open Viber.", named = true)

        val step = Rules.next(acting.copy(speeches = 1), heard)

        assertEquals(
            listOf(
                Effect.Show(Feedback.Heard("Stop. Kestrel, open Viber.")),
                Effect.StopSaying,
                Effect.Converse(InputIntent.Stop, heard),
                Effect.Converse(InputIntent.Command("open Viber."), heard),
            ),
            step.effects,
        )
        assertEquals(Work.IDLE, step.state.work)
    }

    @Test
    fun stopListeningWhileAGoalWorksOnlyPauses() {
        val step = Rules.next(acting, Event.Heard("Stop listening.", named = false))

        // Plan 7B's decision 10: the mode changes, and the conversation gets no input.
        assertEquals(Mode.PAUSED, step.state.mode)
        assertEquals(GoalPhase.ACTING, step.state.goal)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop listening.")), Effect.Show(Feedback.ModeChanged(Mode.PAUSED))),
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
        assertTrue(Rules.next(listening, Event.Heard("Kestrel, sign up.", named = true)).effects.last() is Effect.Converse)
        // The same in an answer to "Yes?" while paused: the wait ends, and no task starts.
        assertEquals(
            Step(paused.copy(wait = 1), listOf(Effect.Show(Feedback.Heard("Sign up.")))),
            Rules.next(paused.copy(work = Work.AWAITING_TASK, wait = 1), Event.Heard("Sign up.", named = false)),
        )
        // After "Stop." while a goal works, the goal stops and no command follows.
        val stopped = Rules.next(goal(GoalPhase.ACTING, paused), Event.Heard("Stop. Kestrel, sign up.", named = true))
        assertEquals(listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, sign up.")), Effect.Converse(InputIntent.Stop, Event.Heard("Stop. Kestrel, sign up.", named = true))), stopped.effects)
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
        val heard = Event.Heard("Kestrel, open Viber.", named = true)
        val task = Rules.next(runningCommand, heard)
        assertEquals(
            listOf(
                Effect.CancelCommand,
                Effect.Show(Feedback.Heard("Kestrel, open Viber.")),
                Effect.Converse(InputIntent.Command("open Viber."), heard),
            ),
            task.effects,
        )
        assertEquals(Work.IDLE, task.state.work)

        val settings = Rules.next(runningCommand, Event.Heard("Kestrel, open settings.", named = true))
        assertEquals(
            listOf(Effect.CancelCommand, Effect.Show(Feedback.Heard("Kestrel, open settings.")), Effect.OpenSettings),
            settings.effects,
        )

        val pausing = Rules.next(runningCommand, Event.Heard("Stop listening.", named = false))
        assertEquals(Mode.PAUSED, pausing.state.mode)
        assertEquals(Effect.CancelCommand, pausing.effects.first())

        val assisted = Rules.next(runningCommand, Event.Assist(emptyList()))
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

        val spoken = Rules.next(asked, Event.SpeechEnded(Reply.Command(1, "Which one?"), spoken = true))
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
        assertEquals(Step(counting), Rules.next(counting, Event.Assist(emptyList())))
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
        // A command for the conversation ends it, and so does a goal that works.
        assertTrue(runs(Rules.next(ended, Event.Heard("Kestrel, what time is it?", named = true)).state))
        val worked = Rules.next(ended, Event.ConversationChanged(acting.conversation)).state
        assertTrue(runs(Rules.next(worked, Event.ConversationChanged(VoiceView())).state))
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
            Event.SpeechEnded(Reply.Yes, spoken = true), Event.ConversationChanged(acting.conversation), Event.ChannelSays(1, "Done.", arrival = 1),
            Event.ChannelShows("Got it."), Event.ChannelAsks(4, "Shall I?", QuestionKind.YES_NO), Event.ChannelHandsOver("Pay yourself."),
            Event.MicBusy(true), Event.MicBusy(false), Event.Screen(on = true),
            Event.Screen(on = false), Event.WaitEnded(1), Event.SettingsUnavailable, Event.SettingsFailed, Event.NothingHeard,
            Event.CallState(CallState.IDLE), Event.CallState(CallState.RINGING), Event.CallState(CallState.ACTIVE), Event.Assist(emptyList()),
            Event.Options(WakeUpMode.STAY_OFF, pauseAfterSilence = true, callCommands = true), Event.Silent(1),
            Event.CommandAsks(1, "Which one?"), Event.CommandSays(1, "Calling."), Event.CommandCounts(1, counting = true),
            Event.AnswerWaitEnded(1), Event.CommandEnded(2, CommandResult(Outcome.DONE)), Event.ScreenWanted(1),
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
            // Turning off ends the speech as a switch of voice, not a stop (plan 7F).
            val ends = if (event is Event.Spotted) Effect.StopSaying else Effect.EndSpeech
            val effects = Rules.next(from, event).effects
            assertEquals("$event", 1, effects.count { it == Effect.StopSaying || it == Effect.EndSpeech })
            assertTrue("$event", effects.indexOf(ends) > effects.indexOf(Effect.CancelCommand))
        }
    }

    @Test
    fun theSpottersStopWhileKestrelSpeaksEndsTheSpeechAndStopsTheGoal() {
        val step = Rules.next(acting.copy(speeches = 1), Event.Spotted(Keyword.STOP))

        // Plan 7B's decision 10.
        assertEquals(listOf(Effect.StopSaying, Effect.Converse(InputIntent.Stop)), step.effects)
        assertEquals(Work.IDLE, step.state.work)
        // Also while Kestrel speaks a hand-over's sentence.
        val handing = Rules.next(goal(GoalPhase.HANDED_OVER), Event.ChannelHandsOver("Pay yourself, then say 'Kestrel, continue'.")).state
        assertEquals(listOf(Effect.StopSaying, Effect.Converse(InputIntent.Stop)), Rules.next(handing, Event.Spotted(Keyword.STOP)).effects)
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

        // "Kestrel" while a goal works only ends the speech; the goal goes on.
        val running = acting.copy(speeches = 1)
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
    fun theConversationsWordsAreSpokenAndShownAndWhileOffOnlyShown() {
        val says = Rules.next(listening, Event.ChannelSays(3, "Done.", arrival = 1))

        assertEquals(listOf(Effect.Show(Feedback.ConversationText("Done.")), Effect.Say(Reply.Channel(3, "Done."))), says.effects)
        assertEquals(1, says.state.speeches)
        // While Kestrel is off, the words are only shown, and the voice channel goes on at once.
        assertEquals(
            listOf(Effect.Show(Feedback.ConversationText("Done.")), Effect.EndReply(3)),
            Rules.next(State(), Event.ChannelSays(3, "Done.", arrival = 1)).effects,
        )
        assertEquals(
            Step(listening, listOf(Effect.Show(Feedback.ConversationText("Got it.")))),
            Rules.next(listening, Event.ChannelShows("Got it.")),
        )
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
        val darkAndRunning = goal(GoalPhase.ACTING, dark)

        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Kestrel, open the settings.", named = true)))
        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Scroll down", named = false)))
        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Stop.", named = false)))
        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Kestrel?", named = true)))
        // Design D2 of sub-project 5: "Stop." before the name still stops the goal, and nothing starts.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, open Viber.")), Effect.Converse(InputIntent.Stop, Event.Heard("Stop. Kestrel, open Viber.", named = true))),
            Rules.next(darkAndRunning, Event.Heard("Stop. Kestrel, open Viber.", named = true)).effects,
        )

        val stopped = Rules.next(darkAndRunning, Event.Heard("Stop.", named = false))
        assertEquals(Work.IDLE, stopped.state.work)
        assertEquals(listOf(Effect.Show(Feedback.Heard("Stop.")), Effect.Converse(InputIntent.Stop, Event.Heard("Stop.", named = false))), stopped.effects)
        assertEquals(Mode.PAUSED, Rules.next(darkAndRunning, Event.Heard("Stop listening.", named = false)).state.mode)
        assertEquals(Mode.PAUSED, Rules.next(dark, Event.Heard("Stop listening.", named = false)).state.mode)
        assertEquals(Mode.OFF, Rules.next(darkAndRunning, Event.Heard("Stop Kestrel.", named = false)).state.mode)
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

        assertEquals(
            Effect.Converse(InputIntent.Command("open the settings."), Event.Heard("Kestrel, open the settings.", named = true)),
            heard(waiting, "Kestrel, open the settings.", named = true).effects.last(),
        )
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
    fun whileAGoalWorksKestrelsOwnRequests() {
        val pausedAndActing = goal(GoalPhase.ACTING, paused)

        // Plan 7B's decision 10: "Stop Kestrel" turns Kestrel off, and the goal goes on.
        val off = Rules.next(acting, Event.Heard("Stop Kestrel.", named = false))
        assertEquals(Mode.OFF, off.state.mode)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop Kestrel.")), Effect.Show(Feedback.ModeChanged(Mode.OFF)), Effect.StopService),
            off.effects,
        )
        assertEquals(Step(acting), Rules.next(acting, Event.Heard("Kestrel.", named = true)))
        assertEquals(Step(pausedAndActing), Rules.next(pausedAndActing, Event.Heard("Kestrel, wake up.", named = true)))
        // Already paused: only the transcript is shown.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop listening."))),
            Rules.next(pausedAndActing, Event.Heard("Stop listening.", named = false)).effects,
        )
        // Kestrel's own requests reach the conversation, which runs them once the goal has ended.
        val delete = Event.Heard("Kestrel, delete history", named = true)
        assertEquals(Effect.Converse(InputIntent.DeleteHistory, delete), Rules.next(acting, delete).effects.last())
        val continuing = Event.Heard("Kestrel, continue", named = true)
        assertEquals(Effect.Converse(InputIntent.Continue, continuing), Rules.next(acting, continuing).effects.last())
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
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.LISTENING)), Effect.EndOwnVoice), started.effects)
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

    // Fix-7d-n1 review, nit 1: a failed start of the settings shows its text through the Rules, as every other text does,
    // so it ends the follow-up window like any text that takes the bar.
    @Test
    fun settingsThatFailedToOpenShowWhyAndEndTheFollowUpWindow() {
        assertEquals(listOf(Effect.Show(Feedback.SettingsFailed)), Rules.next(listening, Event.SettingsFailed).effects)
        val window = replied()
        assertEquals(Work.FOLLOWING_UP, window.work)
        assertEquals(Work.IDLE, Rules.next(window, Event.SettingsFailed).state.work)
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
    fun openSettingsEndsTheWaitForACommandAndIsIgnoredWhileAGoalWorks() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1)
        val running = acting

        assertEquals(
            Step(listening.copy(wait = 1), listOf(Effect.Show(Feedback.Heard("Open settings.")), Effect.OpenSettings)),
            Rules.next(waiting, Event.Heard("Open settings.", named = false)),
        )
        assertEquals(Step(running), Rules.next(running, Event.Heard("Kestrel, open settings.", named = true)))
    }

    @Test
    fun stopBeforeOpenSettingsStopsTheGoal() {
        val step = Rules.next(acting, Event.Heard("Stop. Kestrel, open settings.", named = true))

        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, open settings.")), Effect.Converse(InputIntent.Stop, Event.Heard("Stop. Kestrel, open settings.", named = true)), Effect.OpenSettings),
            step.effects,
        )
        assertEquals(Work.IDLE, step.state.work)
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
    fun aCallThatRingsStopsTheSpeech() {
        val busy = acting.copy(speeches = 1)

        val ringing = Rules.next(busy, Event.CallState(CallState.RINGING))

        // The conversation pauses the goal itself, with listening on or off (design 6.5 of sub-project 7).
        assertEquals(listOf(Effect.StopSaying, Effect.Show(Feedback.ModeChanged(Mode.RINGING))), ringing.effects)
        assertEquals(Work.IDLE, ringing.state.work)
        assertEquals(GoalPhase.ACTING, ringing.state.goal)
        // A wait for a command ends too, and nothing comes back after the call (section 14.4).
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
        val step = Rules.next(paused, Event.Assist(emptyList()))
        assertEquals(expected, step.effects)
        assertEquals(Mode.LISTENING, step.state.mode)
        assertEquals(Work.AWAITING_TASK, step.state.work)
        // While Kestrel listens, it only asks.
        assertEquals(expected.drop(1), Rules.next(listening, Event.Assist(emptyList())).effects)
        // Listening turns Kestrel on first and sends the Assist once the session is open, so one that comes while
        // Kestrel is off was queued behind a turn-off, and changes nothing.
        assertEquals(Step(State()), Rules.next(State(), Event.Assist(emptyList())))
        // Section 8.5: on the lock screen Kestrel does not record, so it only starts listening and asks nothing.
        val locked = paused.copy(screenOn = false)
        assertEquals(
            Step(locked.copy(mode = Mode.LISTENING), listOf(Effect.Show(Feedback.ModeChanged(Mode.LISTENING)))),
            Rules.next(locked, Event.Assist(emptyList())),
        )
        assertEquals(
            Step(
                locked.copy(mode = Mode.LISTENING, speeches = 1),
                listOf(Effect.StopSaying, Effect.Show(Feedback.ModeChanged(Mode.LISTENING))),
            ),
            Rules.next(locked.copy(speeches = 1), Event.Assist(emptyList())),
        )
    }

    @Test
    fun theAssistantGestureOverKestrelsVoiceOrWhileAGoalWorksActsLikeTheName() {
        // As after an interruption with "Kestrel": Kestrel stops speaking and waits for the task.
        assertEquals(
            listOf(Effect.StopSaying, Effect.Show(Feedback.AwaitingTask), Effect.WaitForTask()),
            Rules.next(listening.copy(speeches = 1), Event.Assist(emptyList())).effects,
        )
        val running = goal(GoalPhase.ACTING, paused).copy(speeches = 1)
        assertEquals(Step(running, listOf(Effect.StopSaying)), Rules.next(running, Event.Assist(emptyList())))
        val silent = running.copy(speeches = 0)
        assertEquals(Step(silent), Rules.next(silent, Event.Assist(emptyList())))
    }

    @Test
    fun theAssistantGestureWhilePausedOverKestrelsVoiceListensStopsAndWaitsForATask() {
        val step = Rules.next(paused.copy(speeches = 1), Event.Assist(emptyList()))

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

        assertEquals(Step(ringing), Rules.next(ringing, Event.Assist(emptyList())))
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
        val gestured = Rules.next(stayOff.copy(mode = Mode.PAUSED, screenOn = false, screenSeen = true), Event.Assist(emptyList())).state
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
        assertEquals(listOf(Effect.TimeSilence), Rules.next(replying, Event.SpeechEnded(Reply.Yes, spoken = true)).effects)
        // Without the setting, nothing times the silence.
        assertEquals(emptyList<Effect>(), Rules.next(listening, Event.Speaking).effects)
        assertEquals(Step(listening.copy(silence = 2)), Rules.next(listening.copy(silence = 2), Event.Silent(2)))
    }

    @Test
    fun theSilenceTimerStartsAgainWhileKestrelIsBusyOrDoesNotRecord() {
        val timed = listening.copy(pauseAfterSilence = true, silence = 1)

        for (busy in listOf(timed.copy(speeches = 1), goal(GoalPhase.ACTING, timed), timed.copy(screenOn = false))) {
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

    // Design 11 of sub-project 7: the goal's hand-over as the voice says it.
    private val handOverText = "Please enter your password, then say 'Kestrel, continue'."

    // A goal's hand-over whose sentence has been spoken.
    private fun handedOver(from: State = goal(GoalPhase.HANDED_OVER)): State =
        Rules.next(Rules.next(from, Event.ChannelHandsOver(handOverText)).state, Event.SpeechEnded(Reply.Text(handOverText, kept = true), spoken = true)).state

    private fun heard(state: State, text: String, named: Boolean = false) = Rules.next(state, Event.Heard(text, named))

    @Test
    fun theGoalComesFromTheConversationsView() {
        val sent = heard(listening, "Kestrel, open Viber.", named = true).state
        assertEquals(null, sent.goal)

        val acts = Rules.next(sent, Event.ConversationChanged(acting.conversation)).state
        assertTrue(acts.goalWorks)
        val ended = Rules.next(acts, Event.ConversationChanged(acting.conversation.copy(goal = null))).state
        assertFalse(ended.goalWorks)
        assertEquals(null, ended.goal)
    }

    @Test
    fun theConversationsQuestionIsSpokenKeptOnTheBarAndItsWaitEndsInSilence() {
        val text = "I'm not getting anywhere. Should I keep trying?"
        val step = Rules.next(acting, Event.ChannelAsks(4, text, QuestionKind.YES_NO))

        assertEquals(
            listOf(Effect.Show(Feedback.ConversationText(text, keep = true)), Effect.Say(Reply.Question(4, text)), Effect.WaitForAnswer()),
            step.effects,
        )
        assertEquals(VoiceQuestion(4, QuestionKind.YES_NO), step.state.voiceQuestion)
        // The wait starts again when the question has been spoken, and grows once the owner speaks.
        val spoken = Rules.next(step.state, Event.SpeechEnded(Reply.Question(4, text), spoken = true))
        assertEquals(listOf(Effect.WaitForAnswer()), spoken.effects)
        assertEquals(Recognition.ALL, spoken.state.recognition)
        assertEquals(listOf(Effect.WaitForAnswer(answering = true)), Rules.next(spoken.state, Event.Speaking).effects)
        // Its end is the answer Silence; an older wait changes nothing.
        val silent = Rules.next(spoken.state, Event.AnswerWaitEnded(spoken.state.wait))
        assertEquals(listOf(Effect.Converse(InputIntent.Answer(ChannelAnswer.Silence, question = 4))), silent.effects)
        assertEquals(null, silent.state.voiceQuestion)
        assertEquals(Step(spoken.state), Rules.next(spoken.state, Event.AnswerWaitEnded(spoken.state.wait - 1)))
        // An answer on its card, or the conversation's withdrawal, ends the wait; an older question's end changes nothing.
        assertEquals(Step(spoken.state), Rules.next(spoken.state, Event.ChannelWithdraws(3)))
        assertEquals(null, Rules.next(spoken.state, Event.ChannelWithdraws(4)).state.voiceQuestion)
    }

    // Plan 7F: a question's end, however the owner answered it, stops Kestrel's speech of that question alone: the one
    // that the voice asks, and a typed question's spoken copy with Kestrel on.
    @Test
    fun aQuestionsEndStopsOnlyItsOwnSpeech() {
        val text = "Which one?"
        val asked = Rules.next(acting, Event.ChannelAsks(4, text, QuestionKind.FREE)).state
        val answered = Rules.next(asked, Event.ChannelWithdraws(4))
        assertEquals(listOf(Effect.StopQuestion(4)), answered.effects)
        assertEquals(null, answered.state.voiceQuestion)

        val copy = Rules.next(listening, Event.ChannelSays(3, text, arrival = 1, question = 5))
        assertEquals(Effect.Say(Reply.Channel(3, text, question = 5)), copy.effects.last())
        val later = Rules.next(copy.state, Event.ChannelSays(4, "Done.", arrival = 1)).state
        // Only the question's own speech stops: no StopSaying, which would end "Done." too.
        assertEquals(listOf(Effect.StopQuestion(5)), Rules.next(later, Event.ChannelWithdraws(5)).effects)
    }

    // Plan 7F: every stop of Kestrel's speech, and the owner's Stop also while Kestrel does not speak, keeps the voice
    // channel's words that came before it unspoken, whenever they are said, and the channel goes on at once; words that
    // came after the stop, in the Orchestrator's order, are spoken.
    @Test
    fun aChannelsWordsThatCameBeforeAStopAreNotSpoken() {
        val came = Rules.next(listening, Event.ChannelCame(1)).state
        val stopped = Rules.next(came, Event.StopSpeaking).state
        val after = Rules.next(stopped, Event.ChannelCame(2)).state

        assertEquals(Step(after, listOf(Effect.EndReply(3))), Rules.next(after, Event.ChannelSays(3, "Done.", arrival = 1)))
        assertEquals(Effect.Say(Reply.Channel(4, "Sent.")), Rules.next(after, Event.ChannelSays(4, "Sent.", arrival = 2)).effects.last())

        // A step that stops Kestrel's speech, as a spoken "stop", keeps the words that came before it unspoken too.
        val speaking = Rules.next(after, Event.ChannelSays(4, "Sent.", arrival = 2)).state
        val spotted = Rules.next(Rules.next(speaking, Event.ChannelCame(3)).state, Event.Spotted(Keyword.STOP)).state
        assertEquals(listOf(Effect.EndReply(5)), Rules.next(spotted, Event.ChannelSays(5, "Next.", arrival = 3)).effects)
    }

    // Plan 7F: turning Kestrel on or off is a switch of voice, never the owner's Stop. Turning off ends the session's speech
    // and drops nothing: the words that wait are shown, as Kestrel is off. Turning on ends what Kestrel's own voice speaks.
    // A real Stop still drops what waits.
    @Test
    fun turningKestrelOnOrOffSwitchesTheVoiceAndDropsNothing() {
        val came = Rules.next(listening, Event.ChannelCame(1)).state
        val speaking = Rules.next(came, Event.ChannelSays(1, "Working.", arrival = 1)).state
        val waiting = Rules.next(speaking, Event.ChannelCame(2)).state

        val off = Rules.next(waiting, Event.TurnOff)
        assertTrue(Effect.EndSpeech in off.effects)
        assertFalse(Effect.StopSaying in off.effects)
        assertEquals(
            listOf(Effect.Show(Feedback.ConversationText("Done.", finalReply = 3)), Effect.EndReply(3), Effect.NoFollowUp(3)),
            Rules.next(off.state, Event.ChannelSays(3, "Done.", arrival = 2, final = true)).effects,
        )

        val on = Rules.next(off.state, Event.Toggle)
        assertEquals(Effect.EndOwnVoice, on.effects.last())
        assertTrue(Effect.Say(Reply.Channel(4, "Sent.")) in Rules.next(on.state, Event.ChannelSays(4, "Sent.", arrival = 2)).effects)

        val stopped = Rules.next(Rules.next(on.state, Event.ChannelCame(3)).state, Event.StopSpeaking).state
        assertEquals(listOf(Effect.EndReply(5)), Rules.next(stopped, Event.ChannelSays(5, "Next.", arrival = 3)).effects)
    }

    // The voice's answer names the question that the voice read it for, so that an answer that comes after that question
    // ended never answers a newer one (whole-plan review of plan 7D, M1).
    @Test
    fun theVoicesAnswerNamesItsQuestion() {
        val question = asked(QuestionKind.YES_NO)

        assertEquals(InputIntent.Answer(ChannelAnswer.Yes, question = 4), (heard(question, "Yes.").effects.last() as Effect.Converse).intent)
        assertEquals(
            listOf(Effect.Converse(InputIntent.Answer(ChannelAnswer.Silence, question = 4))),
            Rules.next(question, Event.AnswerWaitEnded(question.wait)).effects,
        )
        assertTrue(Effect.Converse(InputIntent.Answer(ChannelAnswer.Interrupted, question = 4)) in heard(ownQuestion(), "Stop listening").effects)
    }

    @Test
    fun theVoiceReadsTheAnswerOfTheQuestionsKind() {
        val question = asked(QuestionKind.YES_NO)

        for (yes in listOf("Yes.", "Yes, please.", "Continue", "Go on.")) {
            val step = heard(question, yes)
            assertEquals(yes, Effect.Converse(InputIntent.Answer(ChannelAnswer.Yes, question = 4), Event.Heard(yes, named = false)), step.effects.last())
            assertEquals(null, step.state.openQuestion)
        }
        assertEquals(InputIntent.Answer(ChannelAnswer.Yes, question = 4), (heard(question, "Kestrel, continue.", named = true).effects.last() as Effect.Converse).intent)
        assertEquals(InputIntent.Answer(ChannelAnswer.No, question = 4), (heard(question, "No.").effects.last() as Effect.Converse).intent)
        // Other speech is only shown, and the question goes on.
        assertEquals(Step(question, listOf(Effect.Show(Feedback.Heard("Open Viber")))), heard(question, "Open Viber"))
    }

    @Test
    fun whileOnlyTheCardMayAnswerSpokenAnswersDoNotCount() {
        val question = asked(QuestionKind.CONFIRM, voice = false)

        assertEquals(Step(question, listOf(Effect.Show(Feedback.Heard("Confirm.")))), heard(question, "Confirm."))
        assertTrue(Effect.Converse(InputIntent.Stop, Event.Heard("Cancel.", named = false)) in heard(question, "Cancel.").effects)
        assertEquals(
            listOf(Effect.Converse(InputIntent.Answer(ChannelAnswer.Silence, question = 4))),
            Rules.next(question, Event.AnswerWaitEnded(question.wait)).effects,
        )
    }

    @Test
    fun aHandOverKeepsItsSentenceOnTheBar() {
        val text = "This is a payment. Please finish it yourself, then say 'Kestrel, continue'."
        val step = Rules.next(goal(GoalPhase.HANDED_OVER), Event.ChannelHandsOver(text))

        assertEquals(listOf(Effect.Show(Feedback.ConversationText(text, keep = true)), Effect.Say(Reply.Text(text, kept = true))), step.effects)
        assertEquals(Work.IDLE, step.state.work)
        // While Kestrel is off, it is only shown; the conversation runs the hand-over's 3 minutes.
        assertEquals(listOf(Effect.Show(Feedback.ConversationText(text, keep = true))), Rules.next(State(), Event.ChannelHandsOver(text)).effects)
    }

    @Test
    fun duringAHandOverTheInstantCommandsWorkAlsoInThePausedMode() {
        for (from in listOf(goal(GoalPhase.HANDED_OVER), goal(GoalPhase.HANDED_OVER, paused))) {
            val handed = handedOver(from)
            assertEquals(Recognition.ALL, handed.recognition)
            val command = heard(handed, "Scroll down")
            assertEquals(Work.RUNNING_COMMAND, command.state.work)
            assertTrue(command.effects.last() is Effect.RunCommand)
            // The command's end leaves the hand-over open.
            val ended = Rules.next(command.state, Event.CommandEnded(command.state.command, CommandResult(Outcome.DONE)))
            assertEquals(Work.IDLE, ended.state.work)
            assertEquals(GoalPhase.HANDED_OVER, ended.state.goal)
        }
    }

    @Test
    fun kestrelContinueDuringACommandOfAHandOverLeavesTheCommandAndContinues() {
        val command = heard(handedOver(), "Scroll down").state
        val continuing = Event.Heard("Kestrel, continue", named = true)

        val continued = Rules.next(command, continuing)

        assertEquals(
            listOf(Effect.CancelCommand, Effect.Show(Feedback.Heard("Kestrel, continue")), Effect.Converse(InputIntent.Continue, continuing)),
            continued.effects,
        )
        assertEquals(Work.IDLE, continued.state.work)
    }

    // Design 6.5 of sub-project 7: a goal that the lock paused, and "Continue the goal?" after the unlock, spoken.
    private val pausedGoal = goal(GoalPhase.PAUSED).copy(screenOn = false)

    private fun continueAsked(): State = asked(QuestionKind.CONTINUE_GOAL, goal(GoalPhase.PAUSED))

    @Test
    fun kestrelOverContinueTheGoalLeavesTheQuestionOpen() {
        // The spotter hears "Kestrel" while Kestrel still speaks "Continue the goal?".
        val paused = goal(GoalPhase.PAUSED)
        val view = paused.conversation.copy(asking = QuestionKind.CONTINUE_GOAL, question = 4)
        val speaking = Rules.next(paused.copy(conversation = view), Event.ChannelAsks(4, "Continue the goal?", QuestionKind.CONTINUE_GOAL)).state

        val spotted = Rules.next(speaking, Event.Spotted(Keyword.KESTREL)).state
        assertEquals(VoiceQuestion(4, QuestionKind.CONTINUE_GOAL), spotted.openQuestion)
        assertEquals(Work.IDLE, spotted.work)
        val yes = heard(Rules.next(spotted, Event.SpeechEnded(Reply.Question(4, "Continue the goal?"), spoken = true)).state, "Kestrel, continue.", named = true)
        assertEquals(InputIntent.Answer(ChannelAnswer.Yes, question = 4), (yes.effects.last() as Effect.Converse).intent)
    }

    @Test
    fun aPausedGoalLetsTheInstantCommandsRunAndTakesACommandAsItsAnswer() {
        val asked = continueAsked()
        val replacing = Event.Heard("Stop. Kestrel, open Viber.", named = true)
        assertEquals(
            listOf(
                Effect.Show(Feedback.Heard("Stop. Kestrel, open Viber.")),
                Effect.Converse(InputIntent.Stop, replacing),
                Effect.Converse(InputIntent.Command("open Viber."), replacing),
            ),
            Rules.next(asked, replacing).effects,
        )
        // A command after the name answers "Continue the goal?" as steering (design 6.5 of sub-project 7).
        val steering = Event.Heard("Kestrel, open Viber.", named = true)
        assertEquals(Effect.Converse(InputIntent.Command("open Viber."), steering), Rules.next(asked, steering).effects.last())
        // Without the question, as during a call with the phone unlocked, an instant command runs.
        assertTrue(heard(goal(GoalPhase.PAUSED), "Scroll down").effects.last() is Effect.RunCommand)
    }

    @Test
    fun stayOffAfterAnUnlockWaitsUntilTheGoalAndItsFinalReplyHaveEnded() {
        val locked = goal(GoalPhase.PAUSED, listening.copy(onWakeUp = WakeUpMode.STAY_OFF, screenOn = false, screenSeen = true))

        val unlocked = Rules.next(locked, Event.Screen(on = true))
        assertEquals(Mode.LISTENING, unlocked.state.mode)
        assertTrue(unlocked.state.offAfterGoal)

        // The goal ends, and the conversation holds its quiet clock while its final reply is spoken.
        val open = VoiceView(open = true)
        val replying = Rules.next(unlocked.state, Event.ConversationChanged(open.copy(replying = true))).state
        assertEquals(Mode.LISTENING, replying.mode)
        val says = Rules.next(replying, Event.ChannelSays(1, "Cancelled. I didn't change anything.", arrival = 1)).state
        val spoken = Rules.next(says, Event.SpeechEnded(Reply.Channel(1, "Cancelled. I didn't change anything."), spoken = true)).state
        assertEquals(Mode.LISTENING, spoken.mode)
        val off = Rules.next(spoken, Event.ConversationChanged(open))
        assertEquals(Mode.OFF, off.state.mode)
        assertTrue(Effect.StopService in off.effects)

        // Without a goal or a reply, Kestrel turns off at once.
        val idle = listening.copy(onWakeUp = WakeUpMode.STAY_OFF, screenOn = false, screenSeen = true)
        assertEquals(Mode.OFF, Rules.next(idle, Event.Screen(on = true)).state.mode)
    }

    @Test
    fun theAssistantGestureAndLateAnswersCountAPausedGoalAsAWorkingOne() {
        val paused = goal(GoalPhase.PAUSED).copy(speeches = 1)

        assertEquals(Step(paused, listOf(Effect.StopSaying)), Rules.next(paused, Event.Assist(emptyList())))
        assertFalse(Rules.next(paused.copy(questionEnded = true), Event.MicBusy(busy = false)).state.questionEnded)
    }

    @Test
    fun deleteHistoryGoesToTheConversationAndItsQuestionTakesASpokenYes() {
        val delete = Event.Heard("Kestrel, delete the history.", named = true)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, delete the history.")), Effect.Converse(InputIntent.DeleteHistory, delete)),
            Rules.next(listening, delete).effects,
        )
        // Its question is the conversation's own, which the voice answers as any yes-or-no question.
        val question = asked(QuestionKind.YES_NO, listening.copy(conversation = VoiceView(open = true)))
        assertEquals(InputIntent.Answer(ChannelAnswer.Yes, question = 4), (heard(question, "Yes.").effects.last() as Effect.Converse).intent)
        assertEquals(listOf(Effect.Show(Feedback.Heard("Go on"))), heard(question, "Go on").effects.dropLast(1))
        // While a goal works or waits, the conversation decides what it means.
        val clear = Event.Heard("Kestrel, clear history", named = true)
        assertEquals(Effect.Converse(InputIntent.DeleteHistory, clear), Rules.next(acting, clear).effects.last())
        assertEquals(Effect.Converse(InputIntent.DeleteHistory, clear), Rules.next(handedOver(), clear).effects.last())
    }

    @Test
    fun rememberForgetAndSaveThatAsGoToTheConversation() {
        fun intent(state: State, text: String) = (heard(state, text, named = true).effects.last() as Effect.Converse).intent

        assertEquals(InputIntent.Remember("the gate code", "blue"), intent(listening, "Kestrel, remember that the gate code is blue."))
        assertEquals(InputIntent.Forget("the gate code"), intent(listening, "Kestrel, forget the gate code"))
        assertEquals(InputIntent.SaveRoutine("test routine"), intent(listening, "Kestrel, save that as test routine."))
        // A form that the parser does not take is a command.
        assertEquals(InputIntent.Command("remember to buy milk"), intent(listening, "Kestrel, remember to buy milk"))
        // During a hand-over too: the conversation decides when it runs.
        assertEquals(InputIntent.Forget("the gate code"), intent(handedOver(), "Kestrel, forget the gate code"))
    }

    @Test
    fun theWaitForAnAnswerDoesNotEndWhileKestrelStillSpeaksTheQuestion() {
        // A confirmation may take longer to say than the wait to start the answer (design D14 of sub-project 5); "Continue
        // the goal?" and Kestrel's own questions wait in the same way.
        for (kind in listOf(QuestionKind.CONFIRM, QuestionKind.CONTINUE_GOAL, QuestionKind.YES_NO)) {
            val question = Rules.next(acting, Event.ChannelAsks(4, "Shall I send it?", kind)).state
            assertTrue(question.speaking)
            assertEquals(Step(question), Rules.next(question, Event.AnswerWaitEnded(question.wait)))
            // The whole wait starts again once the question has been spoken.
            val spoken = Rules.next(question, Event.SpeechEnded(Reply.Question(4, "Shall I send it?"), spoken = true))
            assertEquals(listOf(Effect.WaitForAnswer()), spoken.effects)
            assertEquals(
                listOf(Effect.Converse(InputIntent.Answer(ChannelAnswer.Silence, question = 4))),
                Rules.next(spoken.state, Event.AnswerWaitEnded(spoken.state.wait)).effects,
            )
        }
    }

    // The conversation may ask while an instant command runs, as "Remember …?" while a "scroll down" said after the request
    // still runs: its answer wait goes on and ends in Silence, also when the command asks too, and the command goes on
    // (whole-plan review of plan 7D, M2).
    @Test
    fun theConversationsAnswerWaitEndsWhileAnInstantCommandRuns() {
        val asked = Rules.next(runningCommand, Event.ChannelAsks(4, "Remember that the gate code is blue?", QuestionKind.YES_NO)).state
        assertEquals(Work.RUNNING_COMMAND, asked.work)
        val spoken = Rules.next(asked, Event.SpeechEnded(Reply.Question(4, "Remember that the gate code is blue?"), spoken = true))
        assertEquals(listOf(Effect.WaitForAnswer()), spoken.effects)
        assertEquals(listOf(Effect.WaitForAnswer(answering = true)), Rules.next(spoken.state, Event.Speaking).effects)
        assertEquals(listOf(Effect.WaitForAnswer()), Rules.next(spoken.state, Event.NothingHeard).effects)

        val silent = Rules.next(spoken.state, Event.AnswerWaitEnded(spoken.state.wait))
        assertEquals(listOf(Effect.Converse(InputIntent.Answer(ChannelAnswer.Silence, question = 4))), silent.effects)
        assertEquals(Work.RUNNING_COMMAND, silent.state.work)
        assertEquals(null, silent.state.voiceQuestion)
        assertEquals(Step(spoken.state), Rules.next(spoken.state, Event.AnswerWaitEnded(spoken.state.wait - 1)))

        // The command's own question and the conversation's share the latest wait, and its end ends both; the command's
        // question then still guards against a late answer (section 9.4).
        val both = Rules.next(Rules.next(spoken.state, Event.CommandAsks(1, "Which one?")).state, Event.SpeechEnded(Reply.Command(1, "Which one?"), spoken = true)).state
        val ended = Rules.next(both, Event.AnswerWaitEnded(both.wait))
        assertEquals(
            listOf(
                Effect.Converse(InputIntent.Answer(ChannelAnswer.Silence, question = 4)),
                Effect.AnswerCommand(1, null),
                Effect.TimeLateAnswers,
            ),
            ended.effects,
        )
        assertTrue(ended.state.questionEnded)
    }

    // The controller's decision of 2026-11-25: an answer needs no screen, so while an instant command runs without a
    // question of its own, the owner's answer to the conversation's open question reaches it with its number, and the
    // command goes on, also for "Continue the goal?". While the command asks, its own question takes the words.
    @Test
    fun anAnswerToTheConversationsQuestionWhileAnInstantCommandRunsLeavesTheCommandRunning() {
        fun asking(kind: QuestionKind, from: State = runningCommand, voice: Boolean = true): State {
            val view = from.conversation.copy(open = true, asking = kind, question = 4)
            val asked = Rules.next(from.copy(conversation = view), Event.ChannelAsks(4, "Remember that?", kind, voice)).state
            return Rules.next(asked, Event.SpeechEnded(Reply.Question(4, "Remember that?"), spoken = true)).state
        }
        val question = asking(QuestionKind.YES_NO)

        for ((words, answer) in listOf("Yes." to ChannelAnswer.Yes, "No, thanks." to ChannelAnswer.No)) {
            val step = heard(question, words)
            assertEquals(
                words,
                listOf(Effect.Show(Feedback.Heard(words)), Effect.Converse(InputIntent.Answer(answer, question = 4), Event.Heard(words, named = false))),
                step.effects,
            )
            assertEquals(Work.RUNNING_COMMAND, step.state.work)
            assertEquals(null, step.state.openQuestion)
        }
        // Words that are no answer keep today's path, and so do the answers that only the card may give.
        assertTrue(heard(question, "Scroll up").effects.none { it is Effect.Converse || it == Effect.CancelCommand })
        assertTrue(heard(asking(QuestionKind.YES_NO, voice = false), "Yes.").effects.none { it is Effect.Converse })
        // While the command asks, its own question takes the words.
        val both = Rules.next(question, Event.CommandAsks(1, "Which one?")).state.copy(speeches = 0)
        assertTrue(heard(both, "Yes.").effects.last() is Effect.AnswerCommand)

        // A paused goal's "Continue the goal?" is answered as any question, and the command goes on: the resumed round
        // takes the screen's turn itself (ScreenWanted; whole-plan re-review of plan 7D, N1).
        val paused = asking(QuestionKind.CONTINUE_GOAL, runningCommand.copy(conversation = VoiceView(open = true, goal = GoalPhase.PAUSED)))
        assertEquals(Work.RUNNING_COMMAND, heard(paused, "No.").state.work)
        val resumed = heard(paused, "Yes.")
        assertEquals(Work.RUNNING_COMMAND, resumed.state.work)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Yes.")), Effect.Converse(InputIntent.Answer(ChannelAnswer.Yes, question = 4), Event.Heard("Yes.", named = false))),
            resumed.effects,
        )
    }

    // Whole-plan re-review of plan 7D, N1: a goal's round that needs the screen while an instant command holds it asks for
    // it (ScreenTurns), and the conversation's work goes first: the command is cancelled, as for "stop", and the bar says
    // so. A request for an older command, or while none runs, changes nothing.
    // Re-review round 4, R4-1: a command that a round's turn kept from starting ends at once, and the bar says that Kestrel
    // is busy with the goal; so does the settings' start. An older command's report, or one while Kestrel is off, changes
    // nothing.
    @Test
    fun aCommandThatTheRoundsTurnKeptFromStartingEndsAsBusy() {
        val held = Rules.next(runningCommand, Event.ScreenHeld(1))
        assertEquals(listOf(Effect.Show(Feedback.GoalBusy)), held.effects)
        assertEquals(Work.IDLE, held.state.work)
        assertEquals(null, held.state.running)

        assertEquals(Step(runningCommand), Rules.next(runningCommand, Event.ScreenHeld(0)))
        assertEquals(listOf(Effect.Show(Feedback.GoalBusy)), Rules.next(listening, Event.ScreenHeld(null)).effects)
        assertEquals(Step(State()), Rules.next(State(), Event.ScreenHeld(null)))
    }

    @Test
    fun aRoundThatWantsTheScreenCancelsTheRunningCommand() {
        val wanted = Rules.next(runningCommand, Event.ScreenWanted(1))
        assertEquals(listOf(Effect.CancelCommand, Effect.Show(Feedback.CommandCancelled)), wanted.effects)
        assertEquals(Work.IDLE, wanted.state.work)

        assertEquals(Step(runningCommand), Rules.next(runningCommand, Event.ScreenWanted(0)))
        assertEquals(Step(listening), Rules.next(listening, Event.ScreenWanted(1)))
    }

    @Test
    fun aStopBeforeKestrelsOwnRequestsGivesTheStopFirst() {
        val forget = Event.Heard("Stop. Kestrel, forget the gate code.", named = true)
        assertEquals(
            listOf(
                Effect.Show(Feedback.Heard("Stop. Kestrel, forget the gate code.")),
                Effect.Converse(InputIntent.Stop, forget),
                Effect.Converse(InputIntent.Forget("the gate code"), forget),
            ),
            Rules.next(acting, forget).effects,
        )
        // In every phase: while the goal asks, while it is paused, during "Continue the goal?" and during a hand-over.
        val remember = Event.Heard("Stop. Kestrel, remember that the gate code is blue.", named = true)
        for (state in listOf(asked(QuestionKind.CONFIRM), goal(GoalPhase.PAUSED), continueAsked(), handedOver())) {
            val step = Rules.next(state, remember)
            assertTrue("$state", Effect.Converse(InputIntent.Stop, remember) in step.effects)
            assertEquals("$state", Effect.Converse(InputIntent.Remember("the gate code", "blue"), remember), step.effects.last())
        }
        // "Stop. Kestrel, open settings." stops the goal and opens them.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, open settings.")), Effect.Converse(InputIntent.Stop, Event.Heard("Stop. Kestrel, open settings.", named = true)), Effect.OpenSettings),
            heard(continueAsked(), "Stop. Kestrel, open settings.", named = true).effects,
        )
        // With the screen off nothing may start, but the stop still stops the goal.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, forget the gate code")), Effect.Converse(InputIntent.Stop, Event.Heard("Stop. Kestrel, forget the gate code", named = true))),
            heard(pausedGoal, "Stop. Kestrel, forget the gate code", named = true).effects,
        )
    }

    // Design D2 of sub-project 5: "Stop. Kestrel, …" stops Kestrel's own request too, while it asks or while it runs
    // without a question, and the command after it reaches the conversation, so that neither the stop nor the command is
    // lost (review of plan 7D's Task 3, M1). Without a goal or a request of Kestrel's own, the words stay one command.
    @Test
    fun aStopBeforeACommandStopsKestrelsOwnRequestFirst() {
        val command = Event.Heard("Stop. Kestrel, open Viber.", named = true)
        val running = listening.copy(conversation = VoiceView(open = true, ownRequest = true))
        for (state in listOf(ownQuestion(), running)) {
            assertEquals(
                "$state",
                listOf(Effect.Converse(InputIntent.Stop, command), Effect.Converse(InputIntent.Command("open Viber."), command)),
                Rules.next(state, command).effects.filterIsInstance<Effect.Converse>(),
            )
        }
        assertEquals(
            listOf(Effect.Converse(InputIntent.Command("open Viber."), command)),
            Rules.next(listening.copy(conversation = VoiceView(open = true)), command).effects.filterIsInstance<Effect.Converse>(),
        )
    }

    // Design 6.4 of sub-project 7: "stop" cancels Kestrel's own request that runs without a question, in every form in
    // which it stops a goal, so a plain "stop" names no button then (whole-plan review of plan 7D, I1).
    @Test
    fun aStopInEveryFormStopsKestrelsOwnRequestThatRunsWithoutAQuestion() {
        val running = listening.copy(conversation = VoiceView(open = true, ownRequest = true))
        fun stop(text: String, named: Boolean = false) = listOf(Effect.Converse(InputIntent.Stop, Event.Heard(text, named)))

        for ((text, named) in listOf("Stop." to false, "Cancel" to false, "Kestrel, stop." to true, "Kestrel, cancel" to true)) {
            val step = heard(running, text, named)
            assertEquals(text, stop(text, named), step.effects.filter { it is Effect.Converse || it is Effect.RunCommand })
            assertEquals(text, Work.IDLE, step.state.work)
        }
        val waiting = heard(running, "Kestrel", named = true).state
        assertEquals(Work.AWAITING_TASK, waiting.work)
        assertEquals(stop("Stop."), heard(waiting, "Stop.").effects.filterIsInstance<Effect.Converse>())
        // "Never mind" after "Yes?" only ends the wait.
        assertTrue(heard(waiting, "Never mind.").effects.none { it is Effect.Converse })
        assertEquals(listOf(Effect.StopSaying, Effect.Converse(InputIntent.Stop)), Rules.next(running.copy(speeches = 1), Event.Spotted(Keyword.STOP)).effects)
        // Paused, Kestrel hears a "stop" without the name while its own request runs, as while a goal works.
        assertEquals(Recognition.ALL, paused.copy(conversation = running.conversation).recognition)
        // Without anything to stop, a plain "stop" may name a button, and "Kestrel, stop" only ends Kestrel's speech.
        assertTrue(heard(listening.copy(conversation = VoiceView(open = true)), "Stop.").effects.none { it is Effect.Converse })
        assertEquals(listOf(Effect.Show(Feedback.Heard("Kestrel, stop."))), heard(listening, "Kestrel, stop.", named = true).effects)
    }

    // Kestrel's own question, "Delete the history?", spoken by the voice (design 11 of sub-project 7).
    private fun ownQuestion(): State {
        val view = VoiceView(open = true, asking = QuestionKind.DELETE_HISTORY, question = 4)
        return Rules.next(listening.copy(conversation = view), Event.ChannelAsks(4, "Delete the history?", QuestionKind.DELETE_HISTORY)).state
    }

    @Test
    fun duringKestrelsOwnQuestionTurningOffPausingAndStoppingKeepTheirMeaning() {
        val speaking = ownQuestion()
        val asked = Rules.next(speaking, Event.SpeechEnded(Reply.Question(4, "Delete the history?"), spoken = true)).state
        val withdrawn = Effect.Converse(InputIntent.Answer(ChannelAnswer.Interrupted, question = 4))

        val off = heard(asked, "Stop Kestrel")
        assertEquals(Mode.OFF, off.state.mode)
        assertTrue(withdrawn in off.effects)
        val paused = heard(asked, "Stop listening")
        assertEquals(Mode.PAUSED, paused.state.mode)
        assertTrue(withdrawn in paused.effects)
        // "Stop" ends the question and Kestrel's voice, and so does the spotter's "stop" over the question.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop.")), Effect.StopSaying, Effect.Converse(InputIntent.Stop, Event.Heard("Stop.", named = false))),
            heard(speaking, "Stop.").effects,
        )
        assertEquals(listOf(Effect.StopSaying, Effect.Converse(InputIntent.Stop)), Rules.next(speaking, Event.Spotted(Keyword.STOP)).effects)
    }

    @Test
    fun kestrelOrTheAssistantGestureDuringKestrelsOwnQuestionWithdrawsItAndWaitsForACommand() {
        val speaking = ownQuestion()
        val asked = Rules.next(speaking, Event.SpeechEnded(Reply.Question(4, "Delete the history?"), spoken = true)).state

        val waits = listOf(
            Rules.next(speaking, Event.Spotted(Keyword.KESTREL)),
            Rules.next(speaking, Event.Assist(emptyList())),
            Rules.next(asked, Event.Assist(emptyList())),
            heard(asked, "Kestrel", named = true),
        )
        for (wait in waits) {
            assertEquals(Work.AWAITING_TASK, wait.state.work)
            assertTrue(Effect.Converse(InputIntent.Answer(ChannelAnswer.Interrupted, question = 4)) in wait.effects)
            // The command that the owner then says reaches the conversation.
            val command = Event.Heard("Open Viber.", named = false)
            assertEquals(Effect.Converse(InputIntent.Command("Open Viber."), command), Rules.next(wait.state, command).effects.last())
        }
    }

    @Test
    fun theConversationsEventsAndEffectsPrintNoWords() {
        val printed = listOf(
            Event.ChannelSays(1, "secret", arrival = 1),
            Event.ChannelShows("secret"),
            Event.ChannelAsks(4, "secret", QuestionKind.FREE),
            Event.ChannelHandsOver("secret"),
            Effect.Converse(InputIntent.Command("secret"), Event.Heard("secret", named = false)),
            Reply.Channel(1, "secret"),
            Feedback.ConversationText("secret"),
            Request.Forget("secret"),
            asked(QuestionKind.FREE),
        ).joinToString()

        assertFalse(printed, printed.contains("secret"))
    }

    // Design 6.6 of sub-project 7: an open conversation, and a final reply that Kestrel has finished speaking.
    private val open = listening.copy(conversation = VoiceView(open = true))

    // The final reply that the follow-up tests say, as Kestrel speaks it.
    private val done = Reply.Channel(5, "Done.")

    private fun replied(from: State = open): State =
        Rules.next(Rules.next(from, Event.ChannelSays(5, "Done.", arrival = 1, final = true)).state, Event.SpeechEnded(done, spoken = true)).state

    @Test
    fun aSpokenFinalReplyOpensTheFollowUpWindowWhereAnUtteranceIsACommand() {
        val said = Rules.next(open, Event.ChannelSays(5, "Done.", arrival = 1, final = true)).state
        val ended = Rules.next(said, Event.SpeechEnded(done, spoken = true))

        assertEquals(listOf(Effect.TimeFollowUp(), Effect.Show(Feedback.FollowUp(8, reply = 5))), ended.effects)
        assertEquals(Work.FOLLOWING_UP, ended.state.work)
        assertEquals(Recognition.ALL, ended.state.recognition)
        assertEquals(Recognition.ALL, replied(paused.copy(conversation = open.conversation)).recognition)
        // Words without the name are a command; the instant commands' parser is not asked.
        val heard = Event.Heard("Scroll down", named = false)
        val step = Rules.next(ended.state, heard)
        assertEquals(listOf(Effect.Show(Feedback.Heard("Scroll down")), Effect.Converse(InputIntent.Command("Scroll down"), heard)), step.effects)
        assertEquals(Work.IDLE, step.state.work)
        // Kestrel's own requests keep their meaning, and an utterance that started inside the window gets the time to end.
        assertEquals(Mode.PAUSED, Rules.next(ended.state, Event.Heard("Stop listening.", named = false)).state.mode)
        assertEquals(listOf(Effect.TimeFollowUp(answering = true)), Rules.next(ended.state, Event.Speaking).effects)
    }

    @Test
    fun theFollowUpWindowEndsAtItsTimerTheNextReplyTheScreenACallOrTheConversationsEnd() {
        val window = replied()

        assertEquals(Work.IDLE, Rules.next(window, Event.FollowUpEnded(window.followUp)).state.work)
        assertEquals(Step(window), Rules.next(window, Event.FollowUpEnded(window.followUp - 1)))
        assertEquals(Work.IDLE, Rules.next(window, Event.ChannelSays(6, "Got it.", arrival = 1)).state.work)
        assertEquals(Work.IDLE, Rules.next(window, Event.Screen(on = false)).state.work)
        assertEquals(Work.IDLE, Rules.next(window, Event.CallState(CallState.RINGING)).state.work)
        assertEquals(Work.IDLE, Rules.next(window, Event.ConversationChanged(VoiceView())).state.work)
        assertEquals(Work.IDLE, Rules.next(window, Event.NothingHeard).state.work)
        assertEquals(Mode.OFF, Rules.next(window, Event.TurnOff).state.mode)
    }

    // Re-check of plan 7D's Task 4, N1: the window holds only while the bar counts its seconds down and Kestrel can hear
    // the owner in it, so whatever else takes the bar, or blocks the microphone, ends it, and no utterance without the
    // name becomes a command while the bar says something else.
    @Test
    fun theFollowUpWindowEndsWhenAnotherTextTakesTheBarOrTheMicrophoneIsBlocked() {
        val window = replied()
        val pausedWindow = replied(paused.copy(conversation = open.conversation))
        assertEquals(Work.FOLLOWING_UP, pausedWindow.work)

        val blocked = Rules.next(window, Event.MicBusy(true))
        assertEquals(listOf(Effect.Show(Feedback.MicBusy)), blocked.effects)
        assertEquals(Work.IDLE, blocked.state.work)
        for ((from, event) in listOf(window to Event.Pause, window to Event.Toggle, pausedWindow to Event.Resume, pausedWindow to Event.Toggle)) {
            val switched = Rules.next(from, event)
            assertTrue(event.toString(), switched.effects.any { it is Effect.Show && it.feedback is Feedback.ModeChanged })
            assertEquals(event.toString(), Work.IDLE, switched.state.work)
        }
        assertEquals(Recognition.NAMED, Rules.next(window, Event.Pause).state.recognition)
        assertEquals(Work.IDLE, Rules.next(window, Event.ChannelShows("Cancelled.")).state.work)
        // What takes nothing from the bar keeps the window.
        assertEquals(Work.FOLLOWING_UP, Rules.next(window, Event.MicBusy(false)).state.work)
        assertEquals(Work.FOLLOWING_UP, Rules.next(window, Event.FollowUpLength(5_000)).state.work)
        // While the microphone is blocked, no window opens: Kestrel could not hear a command in it.
        assertEquals(Work.IDLE, replied(open.copy(micBusy = true)).work)
    }

    // Design D9 and 6.6 of sub-project 7: steering a goal by voice always needs the name, so no window opens or stays open
    // while a goal is open, also when the goal started while a final reply was spoken (whole-plan review of plan 7D, I2).
    @Test
    fun theFollowUpWindowNeverOpensOrStaysOpenWhileAGoalIsOpen() {
        val started = Rules.next(replied(), Event.ConversationChanged(VoiceView(open = true, goal = GoalPhase.ACTING)))
        assertEquals(Work.IDLE, started.state.work)
        assertTrue(heard(started.state, "Scroll down").effects.none { it is Effect.Converse })
        for (phase in GoalPhase.entries) assertEquals("$phase", Work.IDLE, replied(goal(phase)).work)
    }

    @Test
    fun noWindowOpensAfterWordsInTheMiddleOfAGoalAnUnspokenReplyOrWithoutItsLength() {
        // Kestrel's words in the middle of a goal need the name again (D9 of sub-project 7).
        assertEquals(Work.IDLE, Rules.next(Rules.next(open, Event.ChannelSays(5, "Opening Viber.", arrival = 1)).state, Event.SpeechEnded(Reply.Channel(5, "Opening Viber."), spoken = true)).state.work)
        // A final reply that was not spoken to its end, because the owner or a call stopped it, Android refused or
        // interrupted it, or no voice session could speak it: the owner did not hear it, and its text stays on the bar.
        val said = Rules.next(open, Event.ChannelSays(5, "Done.", arrival = 1, final = true)).state
        val unspoken = Rules.next(said, Event.SpeechEnded(done, spoken = false))
        assertEquals(Work.IDLE, unspoken.state.work)
        assertTrue(unspoken.effects.none { it is Effect.Show })
        // Also when another reply that was spoken to its end ends after it.
        val other = Rules.next(said.copy(speeches = 2), Event.SpeechEnded(done, spoken = false)).state
        assertEquals(Work.IDLE, Rules.next(other, Event.SpeechEnded(Reply.Yes, spoken = true)).state.work)
        // A final reply that Kestrel only showed while off.
        assertEquals(
            listOf(Effect.Show(Feedback.ConversationText("Done.", finalReply = 5)), Effect.EndReply(5), Effect.NoFollowUp(5)),
            Rules.next(open.copy(mode = Mode.OFF), Event.ChannelSays(5, "Done.", arrival = 1, final = true)).effects,
        )
        // Without its length, or with the screen off.
        assertEquals(Work.IDLE, replied(Rules.next(open, Event.FollowUpLength(0)).state).work)
        assertEquals(Work.IDLE, replied(open.copy(screenOn = false)).work)
    }

    // Review A-R2 of plan 7F: a final reply's text holds the bar for its window's countdown, so the Rules say when no window
    // follows it, and only then: it was not spoken to its end, Kestrel turned off, a newer reply came, or no window could
    // open.
    @Test
    fun aFinalReplyThatGetsNoFollowUpWindowIsLetGo() {
        val said = Rules.next(open, Event.ChannelSays(5, "Done.", arrival = 1, final = true)).state
        val noFollowUp = Effect.NoFollowUp(5)

        assertTrue(noFollowUp in Rules.next(said, Event.SpeechEnded(done, spoken = false)).effects)
        assertTrue(noFollowUp in Rules.next(said, Event.TurnOff).effects)
        assertTrue(noFollowUp in Rules.next(said, Event.ChannelSays(6, "Got it.", arrival = 1, final = true)).effects)
        val withoutLength = Rules.next(Rules.next(open, Event.FollowUpLength(0)).state, Event.ChannelSays(5, "Done.", arrival = 1, final = true)).state
        assertTrue(noFollowUp in Rules.next(withoutLength, Event.SpeechEnded(done, spoken = true)).effects)
        // A window that opens takes the reply's text over, and words that are no final reply hold nothing.
        assertTrue(Rules.next(said, Event.SpeechEnded(done, spoken = true)).effects.none { it is Effect.NoFollowUp })
        val words = Rules.next(open, Event.ChannelSays(4, "Opening Viber.", arrival = 1)).state
        assertTrue(Rules.next(words, Event.SpeechEnded(Reply.Channel(4, "Opening Viber."), spoken = true)).effects.none { it is Effect.NoFollowUp })
    }

    // Plan 7G's continue-past task: the owner answers the switch question while Kestrel still speaks it, the conversation
    // withdraws the question, whose speech stops, and the switch's final reply comes. The two speeches' ends come from
    // different threads, in either order, so only the final reply's own end decides the window (design 6.6 of
    // sub-project 7).
    private val switchQuestion = Reply.Question(3, "Switch to the conversation about the gate?")
    private val switched = Reply.Channel(8, "Switched.")
    private val switchedSays = Event.ChannelSays(8, "Switched.", arrival = 8, final = true)

    private fun switchAnswered(): State {
        val view = VoiceView(open = true, asking = QuestionKind.SWITCH, question = 3)
        val asking = Rules.next(listening.copy(conversation = view), Event.ChannelAsks(3, switchQuestion.text, QuestionKind.SWITCH)).state
        val answered = heard(asking, "Yes.")
        assertEquals(Effect.Converse(InputIntent.Answer(ChannelAnswer.Yes, question = 3), Event.Heard("Yes.", named = false)), answered.effects.last())
        return replay(answered.state, Event.ChannelWithdraws(3))
    }

    private fun replay(from: State, vararg events: Event): State = events.fold(from) { state, event -> Rules.next(state, event).state }

    @Test
    fun theFinalReplysOwnEndOpensTheWindowWhicheverSpeechEndsFirst() {
        val opened = Event.ConversationChanged(VoiceView(open = true))
        val questionEnds = Event.SpeechEnded(switchQuestion, spoken = false)
        val replyEnds = Event.SpeechEnded(switched, spoken = true)

        // The withdrawn question's speech ends before the final reply comes.
        val questionFirst = replay(switchAnswered(), questionEnds, Event.ChannelCame(8), opened, switchedSays, replyEnds)
        assertEquals(Work.FOLLOWING_UP, questionFirst.work)
        // The device's order: the final reply comes while the question's speech still counts, and that speech's end,
        // which was not spoken, comes after it. It is not the final reply's end, so it leaves the reply's window alone.
        val replyFirst = replay(switchAnswered(), Event.ChannelCame(8), switchedSays, opened, questionEnds, replyEnds)
        assertEquals(Work.FOLLOWING_UP, replyFirst.work)
        // The window waits until Kestrel has finished speaking, also when the question's end comes last.
        val spokenFirst = replay(switchAnswered(), Event.ChannelCame(8), switchedSays, opened, replyEnds)
        assertEquals(Work.IDLE, spokenFirst.work)
        assertEquals(FinalReply(8, spoken = true), spokenFirst.finalReply)
        val questionLast = Rules.next(spokenFirst, questionEnds)
        assertEquals(Work.FOLLOWING_UP, questionLast.state.work)
        assertEquals(Effect.Show(Feedback.FollowUp(8, reply = 8)), questionLast.effects.last())
    }

    @Test
    fun theFinalReplysOwnUnspokenEndOpensNoWindowWhicheverSpeechEndsFirst() {
        val says = replay(switchAnswered(), Event.ChannelCame(8), switchedSays, Event.ConversationChanged(VoiceView(open = true)))
        val questionEnds = Event.SpeechEnded(switchQuestion, spoken = false)
        val replyStopped = Event.SpeechEnded(switched, spoken = false)

        for ((first, second) in listOf(questionEnds to replyStopped, replyStopped to questionEnds)) {
            val firstEnded = Rules.next(says, first)
            val secondEnded = Rules.next(firstEnded.state, second)
            assertEquals("$first", Work.IDLE, secondEnded.state.work)
            assertEquals("$first", null, secondEnded.state.finalReply)
            assertEquals("$first", 1, (firstEnded.effects + secondEnded.effects).count { it == Effect.NoFollowUp(8) })
        }
    }

    // Review of the speech-identity fix, finding 1: the end that leaves no speech settles a spoken final reply also when it
    // starts a wait, where no window can open, so its text is released.
    @Test
    fun aSpokenFinalReplyIsReleasedWhenTheLastEndStartsAWait() {
        val spoken = replay(switchAnswered(), Event.ChannelCame(8), switchedSays, Event.ConversationChanged(VoiceView(open = true)), Event.SpeechEnded(switched, spoken = true))
        val spotted = Rules.next(spoken, Event.Spotted(Keyword.KESTREL))
        assertEquals(Work.AWAITING_TASK, spotted.state.work)
        val ended = Rules.next(spotted.state, Event.SpeechEnded(switchQuestion, spoken = false))

        assertEquals(Work.AWAITING_TASK, ended.state.work)
        assertEquals(listOf(Effect.WaitForTask()), ended.effects.filterIsInstance<Effect.WaitForTask>())
        assertEquals(null, ended.state.finalReply)
        assertEquals(1, (spotted.effects + ended.effects).count { it == Effect.NoFollowUp(8) })
        assertTrue(ended.effects.none { it is Effect.Show && it.feedback is Feedback.FollowUp })
    }

    // Review of 1b762e1d: the window's own condition declines while the conversation's question waits for its answer, so a
    // spoken final reply whose last end starts that wait is released, and no window opens beside the question.
    @Test
    fun noWindowOpensBesideAVoiceQuestionThatWaitsForItsAnswer() {
        val question = Reply.Question(4, "Shall I keep it?")
        val asked = Rules.next(open, Event.ChannelAsks(4, question.text, QuestionKind.YES_NO)).state
        val said = Rules.next(asked, Event.ChannelSays(5, "Done.", arrival = 1, final = true)).state
        val spoken = Rules.next(said, Event.SpeechEnded(done, spoken = true)).state
        assertEquals(FinalReply(5, spoken = true), spoken.finalReply)

        val ended = Rules.next(spoken, Event.SpeechEnded(question, spoken = true))
        assertEquals(VoiceQuestion(4, QuestionKind.YES_NO), ended.state.voiceQuestion)
        assertEquals(Work.IDLE, ended.state.work)
        assertEquals(null, ended.state.finalReply)
        assertTrue(Effect.WaitForAnswer() in ended.effects)
        assertTrue(Effect.NoFollowUp(5) in ended.effects)
        assertTrue(ended.effects.none { it is Effect.Show && it.feedback is Feedback.FollowUp })
    }

    // Plan 7F: the owner's Stop that does not come by voice is the same stop as a spoken one for Kestrel's speech.
    @Test
    fun theOwnersStopWithoutHisVoiceStopsKestrelsSpeech() {
        val said = Rules.next(open, Event.ChannelSays(5, "Done.", arrival = 1, final = true)).state

        assertEquals(listOf(Effect.StopSaying), Rules.next(said, Event.StopSpeaking).effects)
        assertEquals(Step(open), Rules.next(open, Event.StopSpeaking))
    }

    @Test
    fun turnOffAloneInTheFollowUpWindowTurnsKestrelOffAndReachesNoConversation() {
        val step = Rules.next(replied(), Event.Heard("Turn off.", named = false))

        assertEquals(Mode.OFF, step.state.mode)
        assertTrue(step.effects.none { it is Effect.Converse })
    }

    @Test
    fun aNewConversationAndContinuingAPastOneGoToTheConversation() {
        val new = Event.Heard("Kestrel, new conversation", named = true)
        assertEquals(Effect.Converse(InputIntent.NewConversation, new), Rules.next(listening, new).effects.last())
        val past = Event.Heard("Kestrel, continue the conversation about dinner", named = true)
        assertEquals(Effect.Converse(InputIntent.ContinuePast("dinner"), past), Rules.next(listening, past).effects.last())
        // While a goal works too: the conversation says that the goal must stop first (design 6.8 of sub-project 7).
        assertEquals(Effect.Converse(InputIntent.NewConversation, new), Rules.next(acting, new).effects.last())
        // In the follow-up window, without the name.
        val again = Event.Heard("New conversation.", named = false)
        assertEquals(Effect.Converse(InputIntent.NewConversation, again), Rules.next(replied(), again).effects.last())
    }
}
