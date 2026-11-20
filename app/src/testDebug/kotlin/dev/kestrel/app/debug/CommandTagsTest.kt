package dev.kestrel.app.debug

import dev.kestrel.app.listen.ListeningTexts
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Rules
import dev.kestrel.app.orchestrator.State
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tags of plan 4D's checks (hook H1), on the Orchestrator's real rules. */
class CommandTagsTest {
    private val tags = CommandTags()
    private var state = State()

    // Applies the rules to [event] as the Orchestrator does, and gives the step to the tags.
    private fun step(event: Event) {
        val before = state
        val step = Rules.next(before, event)
        state = step.state
        tags.onStep(event, before, step)
    }

    private fun send(text: String, tag: String? = null) {
        val event = Event.Heard(text, named = false)
        if (tag != null) assertTrue(tags.register(tag, event, VoiceHooks.readOf(text, named = false)))
        step(event)
    }

    @Test
    fun aTaggedTranscriptGetsItsOwnCommandItsAnswerAndWhatTheCommandSaidShowedAndTook() {
        step(Event.Toggle)
        val pending = Event.Heard("Tap Delete.", named = false)
        assertTrue(tags.register("ask", pending, "other"))
        assertEquals(CommandTags.Tagged(CommandTags.Role.PENDING, 0, "other"), tags.tagged("ask"))

        step(pending)
        // A stray transcript while the command runs takes nothing from the tag.
        send("Scroll down.")
        step(Event.CommandAsks(1, "Which one?"))
        send("1", tag = "answer")
        step(Event.CommandEnded(1, CommandResult(Outcome.DONE, say = "Done.", detail = "tile")))

        assertEquals(CommandTags.Tagged(CommandTags.Role.COMMAND, 1, "other"), tags.tagged("ask"))
        assertEquals(CommandTags.Role.ANSWER, tags.tagged("answer")?.role)
        assertEquals(1, tags.tagged("answer")?.command)
        val record = tags.record(1)
        assertEquals(listOf(CommandTags.hash("Which one?"), CommandTags.hash("Done.")), record.said)
        assertEquals(listOf(CommandTags.hash("Which one?")), record.shown)
        assertEquals("tile", record.detail)
        assertEquals(CommandTags.Record(), tags.record(2))
    }

    @Test
    fun aStopCancelsItsOwnCommandAndAnAnswerThatIsNoAnswerBecomesTheCommandThatItStarts() {
        step(Event.Toggle)
        send("Scroll down.", tag = "scroll")
        send("Stop.", tag = "stop")

        assertEquals(CommandTags.Tagged(CommandTags.Role.CANCEL, 1, "stop"), tags.tagged("stop"))
        assertEquals(listOf(CommandTags.hash(ListeningTexts.CANCELLED)), tags.record(1).shown)

        send("Tap Send.", tag = "send")
        step(Event.CommandAsks(2, "Which one?"))
        send("Scroll up.", tag = "other")
        assertEquals(CommandTags.Role.ANSWER, tags.tagged("other")?.role)
        step(Event.CommandEnded(2, CommandResult(Outcome.CANCELLED, passOn = "Scroll up.")))

        assertEquals(CommandTags.Tagged(CommandTags.Role.COMMAND, 3, "other"), tags.tagged("other"))

        // A later untagged answer that is passed on leaves the earlier tagged answer as it was.
        step(Event.CommandEnded(3, CommandResult(Outcome.DONE)))
        send("Tap Send.", tag = "pick")
        step(Event.CommandAsks(4, "Which one?"))
        send("1", tag = "one")
        step(Event.CommandAsks(4, "Which one?"))
        send("Scroll down.")
        step(Event.CommandEnded(4, CommandResult(Outcome.CANCELLED, passOn = "Scroll down.")))
        assertEquals(CommandTags.Role.ANSWER, tags.tagged("one")?.role)
        assertEquals(4, tags.tagged("one")?.command)
    }

    @Test
    fun aTagIsUsedOnceATranscriptWhileKestrelIsOffStartsNothingAndTheHashIsSha256() {
        send("Scroll down.", tag = "off")

        assertEquals(CommandTags.Tagged(CommandTags.Role.NONE, 0, "other"), tags.tagged("off"))
        assertFalse(tags.register("off", Event.Heard("Scroll up.", named = false), "other"))
        assertNull(tags.tagged("never"))
        // A transcript that a hook drops before the Orchestrator sees it did nothing.
        val dropped = Event.Heard("Scroll up.", named = false)
        assertTrue(tags.register("dropped", dropped, "other"))
        tags.dropped(dropped)
        assertEquals(CommandTags.Tagged(CommandTags.Role.NONE, 0, "other"), tags.tagged("dropped"))
        // The first 16 hexadecimal characters of SHA-256("abc"), as the plan's interfaces' Get-TextHash (2026-11-20-a71-checks-4d-interfaces.md) gives them.
        assertEquals("ba7816bf8f01cfea", CommandTags.hash("abc"))
    }

    @Test
    fun aTaggedTranscriptThatStartsATaskGetsTheTasksNumberAndPassesItsOptionsBeforeTheTaskRuns() {
        step(Event.Toggle)
        val task = Event.Heard("Kestrel, open the maps.", named = true)
        assertTrue(tags.register("task", task, VoiceHooks.readOf(task.text, named = true), mapOf("model" to "test-model")))

        val before = state
        val step = Rules.next(before, task)
        state = step.state
        val started = tags.onStep(task, before, step)

        assertEquals(CommandTags.StartedTask(1, mapOf("model" to "test-model")), started)
        assertEquals(CommandTags.Tagged(CommandTags.Role.TASK, 0, "task", task = 1), tags.tagged("task"))
        // An untagged task passes nothing.
        step(Event.TaskEnded(1, reply = null))
        val other = Event.Heard("Kestrel, open the settings.", named = true)
        val next = Rules.next(state, other)
        assertNull(tags.onStep(other, state, next))
    }
}
