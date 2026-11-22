package dev.kestrel.app.agent

import dev.kestrel.agent.port.ActionOutcome
import dev.kestrel.agent.port.Expect
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentScreenChecksTest {
    private val chat = ScreenBuilder.screen("com.viber.voip") {
        field("Type a message", null, 2000)
        button("Send", 2000, left = 940)
    }
    private val field = chat.elements[0]

    @Test
    fun theElementMustStillLookAsTheObservationShowedIt() {
        val expect = Expect("Type a message", "android.widget.EditText", "com.viber.voip", editable = true, password = false, checkable = false)
        val expected = AgentScreenChecks.expected(expect)

        assertTrue(expected(field))
        assertFalse(expected(field.copy(label = "Search")))
        assertFalse(expected(field.copy(password = true)))
        assertFalse(expected(field.copy(packageName = "org.telegram.messenger")))
    }

    @Test
    fun anAppIsInFrontOnlyWithAnActionableElementInItsWindow() {
        val loading = ScreenBuilder.screen("com.viber.voip") { text("Loading", 1000) }

        assertTrue(AgentScreenChecks.inFront(chat, "com.viber.voip", before = null))
        assertFalse(AgentScreenChecks.inFront(loading, "com.viber.voip", before = null))
        assertFalse(AgentScreenChecks.inFront(chat, "org.telegram.messenger", before = null))
        assertTrue(AgentScreenChecks.inFront(chat, null, before = "com.android.launcher3"))
        assertFalse(AgentScreenChecks.inFront(chat, null, before = "com.viber.voip"))
    }

    @Test
    fun aSwipeGoesAcrossItsElementOrTheAppWindow() {
        val screen = IntRect(0, 0, 1080, 2400)

        assertEquals(field.bounds, AgentScreenChecks.swipeArea(chat, field.key, screen))
        assertEquals(IntRect(0, 0, 1080, 2400), AgentScreenChecks.swipeArea(chat, null, IntRect(0, 0, 10, 10)))
        assertNull(AgentScreenChecks.swipeArea(chat, "gone", screen))
    }

    @Test
    fun anUncertainPressReachesTheAgentAsDoneAndUncertain() {
        // Plan 5A's ScreenSteps reads `uncertain` only on an outcome that is done (design D32 item 29 of sub-project 5).
        assertEquals(ActionOutcome(done = true, uncertain = true), AppScreenPort.outcome(ActionResult.UNCERTAIN))
        assertEquals(ActionOutcome(done = false, reason = ActionResult.CHANGED.reason), AppScreenPort.outcome(ActionResult.CHANGED))
    }
}
