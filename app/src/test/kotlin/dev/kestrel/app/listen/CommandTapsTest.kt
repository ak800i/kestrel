package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Rules
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.conversation.model.InputIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which assistant taps each wait for the owner's command answers, which the conversation screen's microphone follows (plan 7F). */
class CommandTapsTest {
    private val taps = CommandTaps()
    private val idle = State(mode = Mode.LISTENING)
    private val waiting = State(mode = Mode.LISTENING, work = Work.AWAITING_TASK, wait = 3)

    // The Orchestrator's step for [event], as listening's onStep sees it.
    private fun step(state: State, event: Event): Pair<State, CommandWaitEnd?> {
        val step = Rules.next(state, event)
        return step.state to taps.stepped(event, state, step)
    }

    private fun assist(state: State, vararg tapped: Long): Pair<State, CommandWaitEnd?> {
        taps.assisting(tapped.toList())
        return step(state, Event.Assist)
    }

    @Test
    fun theWaitThatATapOpenedEndsWithItsTap() {
        val (asked, opened) = assist(idle, 1)
        assertEquals(Work.AWAITING_TASK, asked.work)
        assertNull(opened)

        assertEquals(CommandWaitEnd(listOf(1), null), step(asked, Event.WaitEnded(asked.wait)).second)
    }

    @Test
    fun wordsAfterYesEndTheTapsWaitWithTheCommandThatTheyGaveTheConversation() {
        val (asked, _) = assist(idle, 1)

        val end = step(asked, Event.Heard("Open the fixture", named = false)).second

        assertEquals(listOf(1L), end?.taps)
        assertEquals(InputIntent.Command::class, end?.input?.let { it::class })
    }

    // Review M1 of Task 8: a wait that "Kestrel" began by voice ends after the tap, before the Orchestrator handles the
    // tap's Assist; that end is not the tap's, and the tap's own wait comes after it.
    @Test
    fun anOlderWaitThatEndsAfterTheTapDoesNotEndTheTap() {
        val (voice, _) = step(idle, Event.Heard("Kestrel", named = true))
        assertEquals(Work.AWAITING_TASK, voice.work)
        taps.assisting(listOf(1))

        val (ended, older) = step(voice, Event.WaitEnded(voice.wait))
        assertNull(older)
        val (asked, _) = step(ended, Event.Assist)

        assertEquals(CommandWaitEnd(listOf(1), null), step(asked, Event.WaitEnded(asked.wait)).second)
    }

    @Test
    fun aTapDuringAWaitJoinsItAndAWaitThatGoesOnHasNoEnd() {
        val (asked, _) = assist(waiting, 1)
        assertEquals(Work.AWAITING_TASK, asked.work)
        assertNull(step(asked, Event.NothingHeard).second)
        assertNull(step(asked, Event.WaitEnded(asked.wait - 1)).second)

        assertEquals(CommandWaitEnd(listOf(1), null), step(asked, Event.TurnOff).second)
    }

    // Review M1 of Task 8: a tap that opens no wait, as while Kestrel is off or an instant command counts down, ends at once.
    @Test
    fun aTapThatOpensNoWaitEndsAtOnce() {
        assertEquals(CommandWaitEnd(listOf(1), null), assist(State(), 1).second)
        val counting = State(mode = Mode.LISTENING, work = Work.RUNNING_COMMAND, counting = true)
        assertEquals(CommandWaitEnd(listOf(2), null), assist(counting, 2).second)
    }

    @Test
    fun tapsThatWaitedForASessionAskOnceItOpensAndEndWhenItNeverDoes() {
        taps.awaitSession(1)
        taps.awaitSession(2)
        assertEquals(listOf(1L, 2L), taps.opened())
        assertNull(taps.closed())

        taps.awaitSession(3)

        assertEquals(CommandWaitEnd(listOf(3), null), taps.closed())
        assertNull(taps.closed())
    }
}
