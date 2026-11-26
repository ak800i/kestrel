package dev.kestrel.app.debug

import dev.kestrel.app.listen.ListeningTexts
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Rules
import dev.kestrel.app.orchestrator.State
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.conversation.local.LocalOutcome
import dev.kestrel.conversation.local.LocalRequest
import dev.kestrel.conversation.machine.ConversationEffect
import dev.kestrel.conversation.machine.ConversationEvent
import dev.kestrel.conversation.machine.ConversationState
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
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
    fun aTaggedTranscriptsInputCarriesItsOptionsAndTheTagGetsTheCommandThatItStarted() {
        step(Event.Toggle)
        val task = Event.Heard("Kestrel, open the maps.", named = true)
        assertTrue(tags.register("task", task, VoiceHooks.readOf(task.text, named = true), mapOf("model" to "test-model")))
        step(task)
        assertEquals(CommandTags.Tagged(CommandTags.Role.CONVERSE, 0, "task"), tags.tagged("task"))

        // Listening makes the input of the transcript; the tag's options go with it.
        val input = tags.input(task, OwnerInput(7, InputChannel.VOICE, InputIntent.Command("open the maps."), 0))
        assertEquals(mapOf("model" to "test-model"), input.options)
        // The conversation starts the goal as command 3, here on the input's own step.
        val started = ConversationEffect.StartGoal(3, 1, input, "open the maps.")
        tags.onConversationStep(ConversationEvent.Input(input), ConversationState(), MachineStep(ConversationState(), listOf(started)))
        assertEquals(CommandTags.Tagged(CommandTags.Role.TASK, 0, "task", task = 3), tags.tagged("task"))
        assertEquals(3, tags.lastGoalWords)

        // An untagged transcript's input carries nothing.
        val other = Event.Heard("Kestrel, open the settings.", named = true)
        step(other)
        val plain = OwnerInput(8, InputChannel.VOICE, InputIntent.Command("open the settings."), 0)
        assertEquals(plain, tags.input(other, plain))
    }

    @Test
    fun aTaggedInputThatStartsNothingAndWaitsForNothingDidNothing() {
        step(Event.Toggle)
        val stop = Event.Heard("Kestrel, continue", named = true)
        assertTrue(tags.register("go-on", stop, VoiceHooks.readOf(stop.text, named = true)))
        step(stop)
        val input = tags.input(stop, OwnerInput(9, InputChannel.VOICE, InputIntent.Continue, 0))

        // The conversation took it, as steering or an answer, without starting work for it.
        tags.onConversationStep(ConversationEvent.Input(input), ConversationState(), MachineStep(ConversationState()))

        assertEquals(CommandTags.Role.NONE, tags.tagged("go-on")?.role)
    }

    // Design 14.4 of sub-project 7: conversation-type tags a typed input, which carries the tag's options.
    @Test
    fun aTypedInputsTagGetsTheCommandThatItStartedAndATagIsUsedOnce() {
        val typed = OwnerInput(11, InputChannel.TEXT, InputIntent.Command("open the maps"), 0)
        val input = checkNotNull(tags.typed("typed", typed, "command", mapOf("model" to "test-model")))
        assertEquals(mapOf("model" to "test-model"), input.options)
        assertEquals(CommandTags.Tagged(CommandTags.Role.CONVERSE, 0, "command"), tags.tagged("typed"))

        val started = ConversationEffect.StartGoal(4, 1, input, "open the maps")
        tags.onConversationStep(ConversationEvent.Input(input), ConversationState(), MachineStep(ConversationState(), listOf(started)))

        assertEquals(CommandTags.Tagged(CommandTags.Role.TASK, 0, "command", task = 4), tags.tagged("typed"))
        assertNull(tags.typed("typed", typed.copy(id = 12), "command"))
    }

    // Plan 7G's review N1: only the conversation that a tagged input's work opened is the hooks' own: never one that a switch
    // moved into, which may be the owner's past conversation, nor one that a tagged follow-up continues there, nor one
    // that an input without a tag opened.
    @Test
    fun onlyTheConversationThatATaggedInputsWorkOpenedIsMarked() {
        val own = mutableListOf<ConversationId>()
        tags.ownConversation = { own += it }
        val conversation = ConversationId("0123456789abcdef0123456789abcdef")
        val goal = checkNotNull(tags.typed("goal", OwnerInput(21, InputChannel.TEXT, InputIntent.Command("open the maps"), 0), "command"))
        val opened = ConversationState(id = conversation)
        tags.onConversationStep(ConversationEvent.Input(goal), ConversationState(), MachineStep(opened, listOf(ConversationEffect.StartGoal(5, 1, goal, "open the maps"))))

        val past = ConversationId("fedcba9876543210fedcba9876543210")
        val switch = checkNotNull(tags.typed("switch", OwnerInput(22, InputChannel.TEXT, InputIntent.Command("continue the conversation about the bus"), 0), "command"))
        val moved = ConversationState(id = past)
        tags.onConversationStep(ConversationEvent.Input(switch), opened, MachineStep(moved, listOf(ConversationEffect.SwitchTo(6, past, switch))))
        val followUp = checkNotNull(tags.typed("follow-up", OwnerInput(23, InputChannel.TEXT, InputIntent.Command("and the times"), 0), "command"))
        tags.onConversationStep(ConversationEvent.Input(followUp), moved, MachineStep(moved, listOf(ConversationEffect.StartGoal(7, 2, followUp, "and the times"))))
        val plain = OwnerInput(24, InputChannel.TEXT, InputIntent.Command("open the settings"), 0)
        val other = ConversationState(id = ConversationId("ab".repeat(16)))
        tags.onConversationStep(ConversationEvent.Input(plain), ConversationState(), MachineStep(other, listOf(ConversationEffect.StartGoal(8, 1, plain, "open the settings"))))

        assertEquals(listOf(conversation), own)
    }

    // Review low risk of plan 7F and review M1 of Task 13a: a mark means that only the hooks' inputs are in the conversation.
    // The owner's own command or answer in it makes it his again, so that delete-typed leaves it; a hook's command or answer
    // does not, nor a wordless Stop, which the goal notification's button gives for goal-control's tap too.
    @Test
    fun theOwnersOwnCommandOrAnswerInAMarkedConversationTakesItsMarkAway() {
        val gone = mutableListOf<ConversationId>()
        tags.ownerConversation = { gone += it }
        val marked = ConversationState(id = ConversationId("0123456789abcdef0123456789abcdef"))
        val hooks = OwnerInput(31, InputChannel.TEXT, InputIntent.Command("and the times"), 0)
        tags.untagged(hooks)
        tags.onConversationStep(ConversationEvent.Input(hooks), marked, MachineStep(marked, listOf(ConversationEffect.StartGoal(9, 2, hooks, "and the times"))))
        val hooksAnswer = OwnerInput(32, InputChannel.TEXT, InputIntent.Answer(ChannelAnswer.Yes), 0)
        tags.untagged(hooksAnswer)
        tags.onConversationStep(ConversationEvent.Input(hooksAnswer), marked, MachineStep(marked, emptyList()))
        val stop = OwnerInput(34, InputChannel.TEXT, InputIntent.Stop, 0)
        tags.onConversationStep(ConversationEvent.Input(stop), marked, MachineStep(marked, emptyList()))
        assertEquals(emptyList<ConversationId>(), gone)

        val owners = OwnerInput(33, InputChannel.VOICE, InputIntent.Command("and the prices"), 0)
        tags.onConversationStep(ConversationEvent.Input(owners), marked, MachineStep(marked, listOf(ConversationEffect.StartGoal(10, 3, owners, "and the prices"))))
        val answer = OwnerInput(35, InputChannel.TEXT, InputIntent.Answer(ChannelAnswer.Text("the red one")), 0)
        tags.onConversationStep(ConversationEvent.Input(answer), marked, MachineStep(marked, emptyList()))
        assertEquals(listOf(marked.id, marked.id), gone)
    }

    // Review M1 of Task 13a: the owner's switch into a marked conversation, by "continue the conversation about ..." (a
    // local request whose end switches) or by the list's Continue, makes the conversation that it moves into his; a hook's
    // switch does not.
    @Test
    fun theOwnersSwitchIntoAMarkedConversationTakesItsMarkAway() {
        val gone = mutableListOf<ConversationId>()
        tags.ownerConversation = { gone += it }
        val his = ConversationState(id = ConversationId("ab".repeat(16)))
        val marked = ConversationId("0123456789abcdef0123456789abcdef")
        val about = OwnerInput(51, InputChannel.VOICE, InputIntent.ContinuePast("the bus"), 0)
        tags.onConversationStep(ConversationEvent.Input(about), his, MachineStep(his, listOf(ConversationEffect.RunLocal(14, null, LocalRequest.ContinuePast("the bus"), about))))
        gone.clear()
        tags.onConversationStep(ConversationEvent.LocalEnded(14, LocalOutcome.Switch(marked)), his, MachineStep(his, listOf(ConversationEffect.SwitchTo(14, marked, about))))
        assertEquals(listOf(marked), gone)

        gone.clear()
        val listed = OwnerInput(52, InputChannel.TEXT, InputIntent.ContinueConversation(marked), 0)
        tags.onConversationStep(ConversationEvent.Input(listed), his, MachineStep(his, listOf(ConversationEffect.SwitchTo(15, marked, listed))))
        assertEquals(listOf(marked), gone)

        gone.clear()
        val hooks = OwnerInput(53, InputChannel.TEXT, InputIntent.ContinuePast("the bus"), 0)
        tags.untagged(hooks)
        tags.onConversationStep(ConversationEvent.Input(hooks), his, MachineStep(his, listOf(ConversationEffect.RunLocal(16, null, LocalRequest.ContinuePast("the bus"), hooks))))
        tags.onConversationStep(ConversationEvent.LocalEnded(16, LocalOutcome.Switch(marked)), his, MachineStep(his, listOf(ConversationEffect.SwitchTo(16, marked, hooks))))
        assertEquals(emptyList<ConversationId>(), gone)
    }

    // Review M2 of Task 13a: a transcript that a hook sends without a tag, as agent-answer's spoken answer or voice heard's,
    // gives an input that is the hooks' own, never the owner's.
    @Test
    fun anUntaggedTranscriptOfAHookGivesTheHooksInput() {
        val gone = mutableListOf<ConversationId>()
        tags.ownerConversation = { gone += it }
        val marked = ConversationState(id = ConversationId("0123456789abcdef0123456789abcdef"))
        step(Event.Toggle)
        val sent = Event.Heard("Kestrel, and the times.", named = true)
        tags.untagged(sent)
        step(sent)
        val hooks = tags.input(sent, OwnerInput(61, InputChannel.VOICE, InputIntent.Command("and the times."), 0))
        tags.onConversationStep(ConversationEvent.Input(hooks), marked, MachineStep(marked, listOf(ConversationEffect.StartGoal(17, 2, hooks, "and the times."))))
        assertEquals(emptyList<ConversationId>(), gone)

        // The owner's own transcript with the same words is his.
        val his = Event.Heard("Kestrel, and the times.", named = true)
        step(his)
        val owners = tags.input(his, OwnerInput(62, InputChannel.VOICE, InputIntent.Command("and the times."), 0))
        tags.onConversationStep(ConversationEvent.Input(owners), marked, MachineStep(marked, listOf(ConversationEffect.StartGoal(18, 3, owners, "and the times."))))
        assertEquals(listOf(marked.id), gone)
    }

    // Review B-I2 of plan 7F: one rule marks the hooks' conversations, the conversation's steps. An untagged command of
    // conversation-type whose work opens a conversation marks it; a hook's command that joins a conversation that the
    // owner's command opened marks nothing.
    @Test
    fun anUntaggedHookCommandThatOpensAConversationMarksItAndOneThatJoinsTheOwnersMarksNothing() {
        val own = mutableListOf<ConversationId>()
        tags.ownConversation = { own += it }
        val opened = ConversationId("0123456789abcdef0123456789abcdef")
        val hooks = OwnerInput(41, InputChannel.TEXT, InputIntent.Command("open the maps"), 0)
        tags.untagged(hooks)
        tags.onConversationStep(ConversationEvent.Input(hooks), ConversationState(), MachineStep(ConversationState(id = opened), listOf(ConversationEffect.StartGoal(11, 1, hooks, "open the maps"))))

        val his = ConversationState(id = ConversationId("ab".repeat(16)))
        val owners = OwnerInput(42, InputChannel.VOICE, InputIntent.Command("read my messages"), 0)
        tags.onConversationStep(ConversationEvent.Input(owners), ConversationState(), MachineStep(his, listOf(ConversationEffect.StartGoal(12, 1, owners, "read my messages"))))
        val joins = OwnerInput(43, InputChannel.TEXT, InputIntent.Command("and the times"), 0)
        tags.untagged(joins)
        tags.onConversationStep(ConversationEvent.Input(joins), his, MachineStep(his, listOf(ConversationEffect.StartGoal(13, 2, joins, "and the times"))))

        assertEquals(listOf(opened), own)
    }
}
