package dev.kestrel.app.goal

import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.HoldReason
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.GoalPhase
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The goal service's coordinator over the conversation's view (design 10.6 of sub-project 7; plan 7E). */
class GoalForegroundTest {
    private val views = MutableStateFlow(ConversationView())
    private var starts = 0
    private val holds = mutableListOf<Int>()
    private var refuse = false
    private val reports = mutableListOf<String>()

    private fun TestScope.following(sdk: Int = 37): GoalForeground {
        val foreground = GoalForeground(
            start = {
                starts++
                if (refuse) throw IllegalStateException("startForegroundService() not allowed")
            },
            holdGoal = { command -> holds += command },
            sdk = sdk,
        ) { what, _ -> reports += what }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { foreground.follow(views) }
        return foreground
    }

    @Test
    fun aGoalStartsTheServiceWhichStopsOnceTheGoalsFinalReplyHasBeenSaid() = runTest {
        val foreground = following()

        views.value = ConversationView(command = 1, goalCommand = 1, goal = GoalPhase.ACTING)
        assertEquals(1, starts)
        foreground.started(setOf(ForegroundType.SPECIAL_USE))
        assertEquals(Shown(GoalNotices.of(views.value)!!, setOf(ForegroundType.SPECIAL_USE)), foreground.shown.value)

        views.value = ConversationView(command = 1, quiet = QuietClock.Held(HoldReason.REPLYING))
        assertEquals(GoalNotices.CLOSING, foreground.shown.value?.notice)
        // A final reply kept until the owner is back keeps the service, also when its conversation closed meanwhile.
        views.value = ConversationView(command = 1, keptForOwner = true)
        assertEquals(GoalNotices.CLOSING, foreground.shown.value?.notice)
        views.value = ConversationView(command = 1, quiet = QuietClock.Running(1, 0))
        assertNull(foreground.shown.value)
        foreground.destroyed()

        assertEquals(1, starts)
        assertEquals(ServiceState.STOPPED, foreground.status().service)
    }

    @Test
    fun aRefusedStartHoldsItsGoalOncePostsTheHeldGoalAndTheNextResumeStartsAgain() = runTest {
        val foreground = following()
        refuse = true

        views.value = ConversationView(command = 1, goalCommand = 1, goal = GoalPhase.ACTING)
        assertEquals(listOf(1), holds)
        assertEquals(listOf("Starting the goal service"), reports)
        assertNull(foreground.shown.value)
        // The conversation holds the goal: a held goal starts nothing, so the refusal does not repeat, and Kestrel posts it.
        views.value = ConversationView(command = 1, goalCommand = 1, goal = GoalPhase.INTERRUPTED, hold = GoalHold.BACKGROUND_REFUSED)
        assertEquals(1, starts)
        assertEquals(GoalNotices.of(views.value), foreground.posted.value)
        assertEquals(foreground.posted.value, foreground.notice())

        refuse = false
        views.value = ConversationView(command = 2, goalCommand = 2, goal = GoalPhase.ACTING)
        assertEquals(2, starts)
        assertEquals(listOf(1), holds)
        assertEquals(
            GoalServiceStatus(ServiceState.STARTING, GoalNotices.of(views.value), null, setOf(ForegroundType.SPECIAL_USE), null, 2, 1),
            foreground.status(),
        )
    }

    @Test
    fun aSpeechWaitsUntilTheServiceAddedMediaPlaybackAndAndroid13NeedsNoType() = runTest {
        val foreground = following()
        views.value = ConversationView(command = 1, goalCommand = 1, goal = GoalPhase.ACTING)
        foreground.started(setOf(ForegroundType.SPECIAL_USE))

        val speech = async { foreground.whileSpeaking { "spoken" } }
        runCurrent()
        assertFalse(speech.isCompleted)
        assertEquals(setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK), foreground.shown.value?.types)
        foreground.applied(setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK))
        assertEquals("spoken", speech.await())
        assertEquals(setOf(ForegroundType.SPECIAL_USE), foreground.shown.value?.types)

        val android13 = following(sdk = 33)
        android13.started(emptySet())
        assertEquals(emptySet<ForegroundType>(), android13.shown.value?.types)
        assertEquals("spoken", android13.whileSpeaking { "spoken" })
    }
}
