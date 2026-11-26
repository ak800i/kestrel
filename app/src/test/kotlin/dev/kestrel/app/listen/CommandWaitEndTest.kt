package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Rules
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.conversation.model.InputIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a wait for the owner's command after "Yes?" ended, which the conversation screen's microphone follows (plan 7F). */
class CommandWaitEndTest {
    private val waiting = State(mode = Mode.LISTENING, work = Work.AWAITING_TASK, wait = 3)

    private fun end(event: Event): CommandWaitEnd? = CommandWaitEnd.of(waiting, Rules.next(waiting, event))

    @Test
    fun aWaitThatRunsOutEndsWithoutAnInput() {
        assertEquals(CommandWaitEnd(null), end(Event.WaitEnded(3)))
        assertEquals(CommandWaitEnd(null), end(Event.TurnOff))
    }

    @Test
    fun wordsAfterYesEndTheWaitWithTheCommandThatTheyGaveTheConversation() {
        assertTrue(end(Event.Heard("Open the fixture", named = false))?.input is InputIntent.Command)
    }

    @Test
    fun aWaitThatGoesOnHasNoEnd() {
        assertNull(end(Event.NothingHeard))
        assertNull(end(Event.WaitEnded(2)))
    }
}
