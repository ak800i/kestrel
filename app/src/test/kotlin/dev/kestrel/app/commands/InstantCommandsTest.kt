package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.screen.read.QuietBudget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class InstantCommandsTest {
    // A part that takes no command and notes the quiet budget that it ran with.
    private class Part(private val budgets: MutableList<QuietBudget?>) : CommandPart {
        override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult? {
            budgets += currentCoroutineContext()[QuietBudget]
            return null
        }
    }

    private object Silent : CommandConversation {
        override suspend fun say(text: String) = Unit

        override suspend fun ask(question: String): String? = null

        override fun keep(text: String) = Unit

        override suspend fun countdown(millis: Long, text: (secondsLeft: Int) -> String): Boolean = false
    }

    @Test
    fun allThePartsOfACommandShareOneQuietBudgetAndTheNextCommandGetsANewOne() = runTest {
        val budgets = mutableListOf<QuietBudget?>()
        val commands = InstantCommands({ null }, Part(budgets), Part(budgets), Part(budgets))
        val volume = PhoneCommand.Volume(stream = null, up = true)

        assertEquals(Outcome.IGNORED, commands.run(volume, "volume up", Silent).outcome)
        // The text, phone and screen parts, and the screen part again with the bare label.
        assertEquals(4, budgets.size)
        val first = budgets.first()
        assertNotNull(first)
        budgets.forEach { assertSame(first, it) }

        budgets.clear()
        commands.run(volume, "volume up", Silent)
        assertNotSame(first, budgets.first())
        assertNull(currentCoroutineContext()[QuietBudget])
    }
}
