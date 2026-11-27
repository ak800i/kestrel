package dev.kestrel.app.debug

import dev.kestrel.conversation.local.LocalOutcome
import dev.kestrel.conversation.local.LocalRequest
import dev.kestrel.conversation.machine.ConversationEffect
import dev.kestrel.conversation.machine.ConversationEvent
import dev.kestrel.conversation.machine.ConversationState
import dev.kestrel.conversation.machine.LocalRun
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The hooks' own conversations (plan 7F): their marks survive a restart of Kestrel, a deletion clears its mark, and the
 * owner's words clear it from the process's start.
 */
class HookConversationsTest {
    @get:Rule
    val folder = TemporaryFolder()

    // The process-wide marks point into this test's folder, which goes after the test.
    @After
    fun clearTheMarks() = HookConversations.reset()

    @Test
    fun theMarksSurviveARestartAndADeletionClearsItsMark() {
        val file = folder.root.resolve(HookConversations.FILE)
        val one = ConversationId("1".repeat(32))
        val two = ConversationId("2".repeat(32))
        HookConversations(file).apply {
            own(one)
            own(two)
            own(one)
        }

        val restarted = HookConversations(file)
        assertEquals(listOf(one, two), restarted.all())
        restarted.forget(listOf(one))
        assertEquals(listOf(two), HookConversations(file).all())
    }

    // Review I2 of plan 7F: the marks' rule watches the conversation's steps from the process's start, so the owner's
    // command in a conversation whose mark a restart kept clears the mark before any hook has been called in the new
    // process.
    @Test
    fun theOwnersCommandBeforeAnyHookCallClearsAMarkThatARestartKept() {
        val file = folder.root.resolve(HookConversations.FILE)
        val marked = ConversationId("1".repeat(32))
        HookConversations(file).own(marked)

        var steps: StepWatcher? = null
        HookConversations.watch(folder.root, CommandTags()) { steps = it }
        assertEquals(listOf(marked), HookConversations.all())
        val open = ConversationState(id = marked)
        val owners = OwnerInput(1, InputChannel.TEXT, InputIntent.Command("and the prices"), 0)
        checkNotNull(steps)(ConversationEvent.Input(owners), open, MachineStep(open, listOf(ConversationEffect.StartGoal(2, 2, owners, "and the prices"))))

        assertEquals(emptyList<ConversationId>(), HookConversations.all())
        assertEquals(emptyList<ConversationId>(), HookConversations(file).all())
    }

    // Plan 7G's typed= and expire-typed: a conversation is the hooks' own only while it carries their mark, which the owner's
    // words clear; no conversation, and none before the marks' rule started, is the hooks'.
    @Test
    fun aConversationIsTheHooksOwnOnlyWhileItCarriesTheirMark() {
        val marked = ConversationId("1".repeat(32))
        val owners = ConversationId("2".repeat(32))
        HookConversations(folder.root.resolve(HookConversations.FILE)).own(marked)
        assertFalse(HookConversations.owns(marked))

        var steps: StepWatcher? = null
        HookConversations.watch(folder.root, CommandTags()) { steps = it }

        assertTrue(HookConversations.owns(marked))
        assertFalse(HookConversations.owns(owners))
        assertFalse(HookConversations.owns(null))
        val open = ConversationState(id = marked)
        val words = OwnerInput(1, InputChannel.TEXT, InputIntent.Command("and the prices"), 0)
        checkNotNull(steps)(ConversationEvent.Input(words), open, MachineStep(open, listOf(ConversationEffect.StartGoal(2, 2, words, "and the prices"))))
        assertFalse(HookConversations.owns(marked))
    }

    // Plan 7G's typed= and stop-typed: the work that a stop would end is the hooks' own by the open conversation's mark, or
    // while none is open, by the mark of Kestrel's own request that runs, which the watched steps keep until it ends.
    @Test
    fun theWorkIsTheHooksOwnByTheOpenConversationsMarkOrWithNoneOpenByTheRequestsMark() {
        val marked = ConversationId("1".repeat(32))
        val owners = ConversationId("2".repeat(32))
        HookConversations(folder.root.resolve(HookConversations.FILE)).own(marked)
        val tags = CommandTags()
        var steps: StepWatcher? = null
        HookConversations.watch(folder.root, tags) { steps = it }
        assertTrue(HookConversations.ownsWork(marked))
        assertFalse(HookConversations.ownsWork(owners))
        assertFalse(HookConversations.ownsWork(null))

        val hooks = OwnerInput(1, InputChannel.TEXT, InputIntent.ContinuePast("the bus"), 0)
        tags.untagged(hooks)
        val asking = ConversationState(local = LocalRun(3, null, hooks))
        checkNotNull(steps)(ConversationEvent.Input(hooks), ConversationState(), MachineStep(asking, listOf(ConversationEffect.RunLocal(3, null, LocalRequest.ContinuePast("the bus"), hooks))))
        assertTrue(HookConversations.ownsWork(null))
        assertFalse(HookConversations.ownsWork(owners))
        checkNotNull(steps)(ConversationEvent.LocalEnded(3, LocalOutcome.Quiet), asking, MachineStep(ConversationState(), emptyList()))
        assertFalse(HookConversations.ownsWork(null))
    }
}
