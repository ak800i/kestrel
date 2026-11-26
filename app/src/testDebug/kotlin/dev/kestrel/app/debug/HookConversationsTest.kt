package dev.kestrel.app.debug

import dev.kestrel.conversation.model.ConversationId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The hooks' own conversations (plan 7F): their marks survive a restart of Kestrel, and a deletion clears its mark. */
class HookConversationsTest {
    @get:Rule
    val folder = TemporaryFolder()

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
}
