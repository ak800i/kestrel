package dev.kestrel.app.goal

import dev.kestrel.conversation.model.GoalPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the goal service runs, what it shows and with which types (design 10.6 and D19 of sub-project 7; plan 7E). */
class GoalServiceRulesTest {
    private val working = GoalNotice(NoticeKind.WORKING, GoalTexts.WORKING, null, null, listOf(GoalAction.STOP, GoalAction.OPEN))
    private val held = GoalNotice(NoticeKind.HELD, "Continue the goal?", null, null, listOf(GoalAction.CANCEL, GoalAction.CONTINUE))

    private fun viewed(state: GoalServiceState, goal: GoalPhase?, command: Int = 1, replying: Boolean = false, stopping: Boolean = false): GoalServiceStep {
        val notice = when (goal) {
            null -> null
            GoalPhase.ACTING, GoalPhase.ASKING, GoalPhase.HANDED_OVER -> working
            GoalPhase.PAUSED, GoalPhase.PAUSED_AT_LIMIT, GoalPhase.INTERRUPTED -> held
        }
        return GoalServiceRules.next(state, GoalServiceEvent.Viewed(goal, stopping, command, replying, notice))
    }

    private fun running(): GoalServiceState = GoalServiceRules.next(viewed(GoalServiceState(), GoalPhase.ACTING).state, GoalServiceEvent.Started).state

    @Test
    fun aGoalThatOpensOrResumesStartsTheServiceAndAHoldOrAnEndingStartsNothing() {
        val opened = viewed(GoalServiceState(), GoalPhase.ACTING)
        assertEquals(listOf(GoalServiceCommand.Start), opened.commands)
        assertEquals(ServiceState.STARTING, opened.state.service)
        val running = running()
        assertTrue(viewed(running, GoalPhase.ASKING).commands.isEmpty())
        assertTrue(viewed(running, GoalPhase.HANDED_OVER).commands.isEmpty())

        val interrupted = viewed(running, GoalPhase.INTERRUPTED)
        assertTrue(interrupted.commands.isEmpty())
        assertEquals(held, GoalServiceRules.shown(interrupted.state, 37)?.notice)

        // Design 10.6: the resume starts the running service again, from the context of the owner's input.
        val resumed = viewed(interrupted.state, GoalPhase.ACTING)
        assertEquals(listOf(GoalServiceCommand.Start), resumed.commands)
        assertEquals(ServiceState.RUNNING, resumed.state.service)
        assertEquals(2, resumed.state.starts)

        // Design 6.7: a goal that a restart restored is held, and its notification asks "Continue the goal?"; a goal that
        // the restore ends after the owner's stop in "Active apps" starts nothing (plan 7B).
        assertEquals(listOf(GoalServiceCommand.Start), viewed(GoalServiceState(), GoalPhase.INTERRUPTED, command = 0).commands)
        assertTrue(viewed(GoalServiceState(), GoalPhase.INTERRUPTED, command = 0, stopping = true).commands.isEmpty())
    }

    @Test
    fun theServiceRunsUntilTheGoalsFinalReplyHasBeenSaid() {
        val replying = viewed(running(), null, replying = true).state
        assertEquals(GoalNotices.CLOSING, GoalServiceRules.shown(replying, 37)?.notice)

        val ended = viewed(replying, null).state
        assertNull(GoalServiceRules.shown(ended, 37))
        assertEquals(ServiceState.STOPPED, ended.service)
        assertTrue(GoalServiceRules.next(ended, GoalServiceEvent.Destroyed).commands.isEmpty())
        // A final reply without a goal's service starts none.
        assertNull(GoalServiceRules.shown(viewed(GoalServiceState(), null, replying = true).state, 37))
    }

    @Test
    fun aRefusedStartHoldsItsGoalWithoutTheServiceAndNothingElse() {
        val refused = GoalServiceRules.next(viewed(GoalServiceState(), GoalPhase.ACTING, command = 4).state, GoalServiceEvent.Refused)
        assertEquals(listOf(GoalServiceCommand.HoldGoal(4)), refused.commands)
        assertEquals(ServiceState.STOPPED, refused.state.service)
        assertEquals(1, refused.state.refusals)

        // A refused new start of a service that runs leaves it running, and the goal working.
        val resumed = viewed(viewed(running(), GoalPhase.PAUSED).state, GoalPhase.ACTING).state
        val again = GoalServiceRules.next(resumed, GoalServiceEvent.Refused)
        assertTrue(again.commands.isEmpty())
        assertEquals(ServiceState.RUNNING, again.state.service)
        assertEquals(1, again.state.refusals)

        assertTrue(GoalServiceRules.next(GoalServiceState(service = ServiceState.STARTING), GoalServiceEvent.Refused).commands.isEmpty())
    }

    @Test
    fun aRefusedGoalHeldAsInterruptedShowsNothingAndIsPostedWithoutTheServiceUntilItsNextStart() {
        val refused = GoalServiceRules.next(viewed(GoalServiceState(), GoalPhase.ACTING, command = 4).state, GoalServiceEvent.Refused).state
        // A stopped service shows nothing, and the goal is posted only once the conversation holds it.
        assertNull(GoalServiceRules.shown(refused, 37))
        assertNull(GoalServiceRules.posted(refused))

        val heldNow = viewed(refused, GoalPhase.INTERRUPTED, command = 4).state
        assertNull(GoalServiceRules.shown(heldNow, 37))
        assertEquals(held, GoalServiceRules.posted(heldNow))

        // Design 10.6: the posted notice's Continue resumes the goal, which starts the service and takes the posted notice away.
        val resumed = viewed(heldNow, GoalPhase.ACTING, command = 5)
        assertEquals(listOf(GoalServiceCommand.Start), resumed.commands)
        assertNull(GoalServiceRules.posted(resumed.state))
        assertEquals(working, GoalServiceRules.shown(resumed.state, 37)?.notice)
        // The goal's end takes it away too.
        assertNull(GoalServiceRules.posted(viewed(heldNow, null).state))
    }

    @Test
    fun androidsEndOfTheServiceOfAnOpenGoalStartsItAgain() {
        val restarted = GoalServiceRules.next(running(), GoalServiceEvent.Destroyed)
        assertEquals(listOf(GoalServiceCommand.Start), restarted.commands)
        assertEquals(ServiceState.STARTING, restarted.state.service)
        // The end of an older instance while a start is on its way changes nothing.
        assertEquals(GoalServiceStep(restarted.state), GoalServiceRules.next(restarted.state, GoalServiceEvent.Destroyed))
    }

    @Test
    fun theTypesFollowAndroidsVersionAndKestrelsSpeech() {
        assertEquals(emptySet<ForegroundType>(), GoalServiceRules.types(33, speaking = true))
        assertEquals(setOf(ForegroundType.SPECIAL_USE), GoalServiceRules.types(34, speaking = false))
        assertEquals(setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK), GoalServiceRules.types(37, speaking = true))

        val two = listOf(true, true).fold(running()) { state, active -> GoalServiceRules.next(state, GoalServiceEvent.Speaking(active)).state }
        val one = GoalServiceRules.next(two, GoalServiceEvent.Speaking(false)).state
        assertEquals(setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK), GoalServiceRules.shown(one, 37)?.types)
        val none = GoalServiceRules.next(one, GoalServiceEvent.Speaking(false)).state
        assertEquals(setOf(ForegroundType.SPECIAL_USE), GoalServiceRules.shown(none, 37)?.types)
    }
}
