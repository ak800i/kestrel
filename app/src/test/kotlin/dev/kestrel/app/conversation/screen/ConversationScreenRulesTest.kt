package dev.kestrel.app.conversation.screen

import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.conversation.OwnerCard
import dev.kestrel.app.goal.ForegroundState
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.HoldReason
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the conversation screen shows and does (design 10.1 and 10.2 of sub-project 7; plan 7F). */
class ConversationScreenRulesTest {
    private val id = ConversationId("0".repeat(32))
    private val other = ConversationId("1".repeat(32))
    private val none = ForegroundState()
    private val inForeground = ForegroundState(GoalPhase.ACTING, running = true, settled = true)

    @Test
    fun aTypedCommandLeavesTheScreenOnlyOnceAndroidAnsweredTheServicesStart() {
        val idle = ConversationView(id = id, command = 1, quiet = QuietClock.Running(1, 0))
        val armed = ConversationScreenRules.sent(ScreenState(), InputIntent.Command("Open Viber"), idle)
        val acting = idle.copy(command = 2, goal = GoalPhase.ACTING, quiet = QuietClock.Held(HoldReason.GOAL))

        // The coordinator has not seen the goal yet, or Android has not answered its start: the screen stays in front.
        assertEquals(emptyList<ScreenEffect>(), ConversationScreenRules.viewed(armed, acting, none, nowWall = 0).effects)
        val starting = ForegroundState(GoalPhase.ACTING, running = false, settled = false)
        assertEquals(emptyList<ScreenEffect>(), ConversationScreenRules.viewed(armed, acting, starting, nowWall = 0).effects)

        val started = ConversationScreenRules.viewed(armed, acting, inForeground, nowWall = 0)
        assertEquals(listOf(ScreenEffect.Leave), started.effects)
        // Once only: the goal's next view moves nothing.
        assertEquals(emptyList<ScreenEffect>(), ConversationScreenRules.viewed(started.state, acting.copy(step = 2), inForeground, nowWall = 0).effects)
    }

    @Test
    fun aRequestWithoutAGoalKeepsTheScreenAndTheOwnersActionEndsWithItsReply() {
        val idle = ConversationView(id = id, command = 1, quiet = QuietClock.Running(1, 0))
        // "Remember that …" is Kestrel's own request: the screen stays and shows its reply.
        assertEquals(ScreenState(), ConversationScreenRules.sent(ScreenState(), InputIntent.Remember("door code", "4521"), idle))

        // A command that starts no goal, as without a provider profile: its reply ends the wait for work.
        val armed = ConversationScreenRules.sent(ScreenState(), InputIntent.Command("Open Viber"), idle)
        val replied = ConversationScreenRules.viewed(armed, idle.copy(command = 2, quiet = QuietClock.Running(2, 0)), none, nowWall = 0).state
        assertNull(replied.armed)
        // A later goal that a spoken command started while the screen is in front moves nothing.
        val later = ConversationScreenRules.viewed(replied, idle.copy(command = 3, goal = GoalPhase.ACTING), inForeground, nowWall = 0)
        assertEquals(emptyList<ScreenEffect>(), later.effects)
        // The microphone waits for the spoken command's goal as a typed command does.
        val listening = ConversationScreenRules.listened(replied, idle.copy(command = 3))
        assertEquals(listOf(ScreenEffect.Leave), ConversationScreenRules.viewed(listening, idle.copy(command = 4, goal = GoalPhase.ACTING), inForeground, nowWall = 0).effects)
        assertNull(ConversationScreenRules.left(listening).armed)
    }

    // Review M1: the owner's act on the screen ends with it, so that a goal that he then starts by voice moves nothing.
    @Test
    fun anAnswerArmsOnlyForItsOwnGoalAndEndsWhenThatGoalEnds() {
        val asking = ConversationView(id = id, command = 1, goal = GoalPhase.ASKING, asking = QuestionKind.FREE, goalRecord = 1)
        val answered = ConversationScreenRules.sent(ScreenState(), InputIntent.Answer(ChannelAnswer.Text("Mila")), asking)
        val voiceGoal = ConversationView(id = id, command = 2, goal = GoalPhase.ACTING, goalRecord = 2)

        // The answered goal ends before a view of it acting meets a settled service: the answer's act is over.
        val ended = ConversationScreenRules.viewed(answered, ConversationView(id = id, command = 1, quiet = QuietClock.Running(1, 0)), none, nowWall = 0).state
        assertNull(ended.armed)
        assertEquals(emptyList<ScreenEffect>(), ConversationScreenRules.viewed(ended, voiceGoal, inForeground, nowWall = 0).effects)
        // The same when the views between them were conflated: another goal acting is not the answer's work.
        assertEquals(emptyList<ScreenEffect>(), ConversationScreenRules.viewed(answered, voiceGoal, inForeground, nowWall = 0).effects)
        // The answered goal going on moves the screen.
        assertEquals(listOf(ScreenEffect.Leave), ConversationScreenRules.viewed(answered, asking.copy(goal = GoalPhase.ACTING), inForeground, nowWall = 0).effects)
    }

    @Test
    fun aTapOnTheMicrophoneThatHearsNoCommandEndsTheTapsAct() {
        val idle = ConversationView(id = id, command = 1, quiet = QuietClock.Running(1, 0))
        val voiceGoal = idle.copy(command = 2, goal = GoalPhase.ACTING, goalRecord = 2)

        val unheard = ConversationScreenRules.heardNoCommand(ConversationScreenRules.listened(ScreenState(), idle))
        assertNull(unheard.armed)
        assertEquals(emptyList<ScreenEffect>(), ConversationScreenRules.viewed(unheard, voiceGoal, inForeground, nowWall = 0).effects)
        // A listening without a command ends no typed command's wait.
        val typed = ConversationScreenRules.sent(ScreenState(), InputIntent.Command("Open Viber"), idle)
        assertEquals(typed, ConversationScreenRules.heardNoCommand(typed))
    }

    @Test
    fun theGoalsControlsFollowItsPhaseAndAHeldGoalSaysHowToGoOn() {
        fun goal(view: ConversationView) = ConversationScreenRules.ui(ScreenState(), view, null).goal

        assertEquals(GoalControls.None, goal(ConversationView(id = id)))
        assertEquals(GoalControls.Working(), goal(ConversationView(id = id, goal = GoalPhase.ASKING)))
        assertEquals(GoalControls.Working(), goal(ConversationView(id = id, goal = GoalPhase.HANDED_OVER)))
        // Plan 7E: while the round waits behind the owner's instant command, the screen says so where the goal shows.
        assertEquals(GoalControls.Working(behindCommand = true), goal(ConversationView(id = id, goal = GoalPhase.ACTING, waitsBehindCommand = true)))
        assertEquals(GoalControls.Held(ConversationTexts.LEAVE_KESTREL), goal(ConversationView(id = id, goal = GoalPhase.INTERRUPTED, hold = GoalHold.KESTREL_SCREEN)))
        assertEquals(GoalControls.Held(ConversationTexts.held(GoalHold.BACKGROUND_REFUSED)), goal(ConversationView(id = id, goal = GoalPhase.INTERRUPTED, hold = GoalHold.BACKGROUND_REFUSED)))
        assertEquals(GoalControls.Held(ConversationTexts.GO_ON), goal(ConversationView(id = id, goal = GoalPhase.PAUSED_AT_LIMIT, hold = GoalHold.LIMIT)))
        assertFalse(ConversationScreenRules.ui(ScreenState(), ConversationView(id = id), null).stopAndNew)
        assertTrue(ConversationScreenRules.ui(ScreenState(), ConversationView(id = id, goal = GoalPhase.ACTING), null).stopAndNew)
    }

    // Review I1 of Task 7: the open continue or limit card carries the goal's buttons, so the goal row shows none of its own.
    @Test
    fun aContinueOrLimitCardReplacesTheGoalsButtons() {
        val going = ConversationView(id = id, goal = GoalPhase.ACTING, asking = QuestionKind.CONTINUE_GOAL)
        val goOn = OwnerCard(5, "Continue the goal?", listOf(CardChoice.Stop, CardChoice.Continue), question = 5, kind = QuestionKind.CONTINUE_GOAL)
        assertEquals(GoalControls.None, ConversationScreenRules.ui(ScreenState(), going, goOn).goal)
        val atLimit = ConversationView(id = id, goal = GoalPhase.PAUSED_AT_LIMIT, hold = GoalHold.LIMIT, asking = QuestionKind.LIMIT)
        val limit = goOn.copy(key = 6, question = 6, kind = QuestionKind.LIMIT)
        assertEquals(GoalControls.None, ConversationScreenRules.ui(ScreenState(), atLimit, limit).goal)

        // Another question's card leaves the goal's buttons where they are.
        val free = OwnerCard(7, "Which Mila?", listOf(CardChoice.Reply), question = 7, kind = QuestionKind.FREE)
        assertEquals(GoalControls.Working(), ConversationScreenRules.ui(ScreenState(), going.copy(goal = GoalPhase.ASKING, asking = QuestionKind.FREE), free).goal)
    }

    @Test
    fun aClosedConversationKeepsItsTranscriptWithItsEndUntilANewOneOpens() {
        val record = CommandRecord(1, 1_000, InputChannel.TEXT, "Open Viber", answer = "Done.")
        var state = ConversationScreenRules.viewed(ScreenState(), ConversationView(id = id, command = 1, records = listOf(record)), none, nowWall = 1_000).state
        state = ConversationScreenRules.viewed(state, ConversationView(command = 1), none, nowWall = 61_000).state

        val lines = ConversationScreenRules.ui(state, ConversationView(command = 1), null).lines
        assertEquals(TranscriptLine.Owner(OwnerLine.COMMAND, "Open Viber", InputChannel.TEXT, 1_000), lines.first())
        assertEquals(TranscriptLine.Ended(61_000), lines.last())

        val next = CommandRecord(1, 70_000, InputChannel.VOICE, "Call Ana")
        state = ConversationScreenRules.viewed(state, ConversationView(id = other, command = 2, records = listOf(next)), none, nowWall = 70_000).state
        assertEquals(
            listOf<TranscriptLine>(TranscriptLine.Owner(OwnerLine.COMMAND, "Call Ana", InputChannel.VOICE, 70_000)),
            ConversationScreenRules.ui(state, ConversationView(), null).lines,
        )
    }

    @Test
    fun newConversationStopsAGoalFirstAndThenStartsAfreshOnAClearScreen() {
        val record = CommandRecord(1, 1_000, InputChannel.TEXT, "Open Viber")
        val working = ConversationView(id = id, command = 1, goal = GoalPhase.ACTING, records = listOf(record))
        val shown = ConversationScreenRules.viewed(ScreenState(), working, inForeground, nowWall = 0).state

        val stop = ConversationScreenRules.newConversation(shown, working)
        assertEquals(listOf(ScreenEffect.Submit(InputIntent.Stop)), stop.effects)
        val ended = ConversationScreenRules.viewed(stop.state, working.copy(goal = null), none, nowWall = 0)
        assertEquals(listOf(ScreenEffect.Submit(InputIntent.NewConversation)), ended.effects)
        val closed = ConversationScreenRules.viewed(ended.state, ConversationView(command = 1), none, nowWall = 5_000)
        assertTrue(ConversationScreenRules.ui(closed.state, ConversationView(), null).lines.isEmpty())

        // Without a goal it closes the open conversation at once; with none open the screen only starts afresh.
        val asked = ConversationScreenRules.newConversation(shown, working.copy(goal = null))
        assertEquals(listOf(ScreenEffect.Submit(InputIntent.NewConversation)), asked.effects)
        val stillOpen = ConversationScreenRules.viewed(asked.state, working.copy(goal = null), none, nowWall = 0).state
        assertEquals(ScreenState(), ConversationScreenRules.viewed(stillOpen, ConversationView(command = 1), none, nowWall = 1).state)
        assertEquals(ScreenStep(ScreenState()), ConversationScreenRules.newConversation(closed.state, ConversationView()))
    }

    @Test
    fun anAnswerLeavesOnlyWhenTheGoalGoesOn() {
        val asking = ConversationView(id = id, command = 1, goal = GoalPhase.ASKING, asking = QuestionKind.FREE)
        val limit = asking.copy(asking = QuestionKind.LIMIT)

        assertTrue(ConversationScreenRules.leaves(InputIntent.Answer(ChannelAnswer.Text("Mila")), asking))
        assertTrue(ConversationScreenRules.leaves(InputIntent.Answer(ChannelAnswer.Yes), limit))
        assertFalse(ConversationScreenRules.leaves(InputIntent.Answer(ChannelAnswer.No), limit))
        assertFalse(ConversationScreenRules.leaves(InputIntent.Answer(ChannelAnswer.Yes), ConversationView(id = id, asking = QuestionKind.SWITCH)))
        assertTrue(ConversationScreenRules.leaves(InputIntent.Continue, ConversationView(id = id, goal = GoalPhase.INTERRUPTED, hold = GoalHold.TOUCH)))
        assertFalse(ConversationScreenRules.leaves(InputIntent.Continue, ConversationView(id = id)))
        assertFalse(ConversationScreenRules.leaves(InputIntent.Stop, asking))
        assertFalse(ConversationScreenRules.leaves(InputIntent.NewConversation, ConversationView()))
    }
}
