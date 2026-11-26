package dev.kestrel.app.debug

import dev.kestrel.conversation.machine.ConversationEffect
import dev.kestrel.conversation.machine.ConversationEvent
import dev.kestrel.conversation.machine.ConversationState
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import org.junit.After
import org.junit.Assert.assertEquals
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
}
