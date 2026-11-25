package dev.kestrel.app.debug

import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.conversation.engine.ChecklistSummary
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.engine.RestoreResult
import dev.kestrel.conversation.machine.HoldReason
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSettings
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHookAnswersTest {
    @Test
    fun theStateCarriesCountsAndKindsAndNeverWords() {
        val settings = ConversationSettings(quietSeconds = 60)
        val now = 1_000_000L
        val open = ConversationView(
            id = ConversationId.random(),
            command = 3,
            goal = GoalPhase.PAUSED_AT_LIMIT,
            replyChannel = InputChannel.TEXT,
            checklist = ChecklistSummary(3, 7, "write to Mila"),
            records = listOf(CommandRecord(1, now, InputChannel.VOICE, "open the secret file"), CommandRecord(2, now, InputChannel.TEXT, "send it")),
            continuedAtWall = now - 5_000,
            testMode = false,
            quiet = QuietClock.Running(2, now - 20_500),
        )

        val state = ConversationHookAnswers.state(open, settings, now)

        assertEquals(
            "open=yes continued=yes commands=2 goal=paused-at-limit quiet=running quietLeft=40 replyChannel=text testMode=off",
            state,
        )
        assertFalse(state, state.contains("secret"))
        assertEquals(
            "open=no continued=no commands=0 goal=none quiet=off quietLeft=none replyChannel=voice testMode=on",
            ConversationHookAnswers.state(ConversationView(), settings, now),
        )
        val held = open.copy(quiet = QuietClock.Held(HoldReason.REPLYING))
        assertTrue(ConversationHookAnswers.state(held, settings, now).contains(" quiet=held quietLeft=none "))
        assertEquals("3/7", ConversationHookAnswers.checklist(open.checklist))
        assertEquals("none", ConversationHookAnswers.checklist(null))
        assertEquals("goal-held", ConversationHookAnswers.restored(RestoreResult.GOAL_HELD))
        assertEquals("pending", ConversationHookAnswers.restored(null))
    }

    @Test
    fun theLimitsAreThePagesSmallestOrTheGivenOnesWithinThePagesBounds() {
        val settings = ConversationSettings()

        assertEquals(GoalLimits(50, 5, 10), ConversationHookAnswers.limits(settings, null)!!.limits)
        assertEquals(GoalLimits(100, 10, 20), ConversationHookAnswers.limits(settings, "100/10/20")!!.limits)
        assertEquals(GoalLimits(50, 5, 10), ConversationHookAnswers.limits(settings, "1/1/1")!!.limits)
        assertEquals(GoalLimits(2_000, 240, 5_000), ConversationHookAnswers.limits(settings, "9999/999/99999")!!.limits)
        assertNull(ConversationHookAnswers.limits(settings, "1/2"))
        assertNull(ConversationHookAnswers.limits(settings, "a/b/c"))
        // A check puts the limits back with the text that the hook answered as was=.
        assertEquals("500/60/1000", ConversationHookAnswers.limitsText(settings.roundLimits))
        assertEquals(settings, ConversationHookAnswers.limits(settings, ConversationHookAnswers.limitsText(settings.roundLimits)))
    }
}
