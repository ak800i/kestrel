package dev.kestrel.app.listen

import dev.kestrel.app.commands.CommandReport
import dev.kestrel.commands.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentCommandsTest {
    @Test
    fun keepsTheNewestCommandsAndForgetsTheOldest() {
        val recent = RecentCommands(capacity = 3)

        for (command in 1..4) {
            recent.started(command, "Tap")
            recent.ended(CommandReport(command, "Tap", Outcome.DONE))
        }

        assertNull(recent[1])
        assertEquals((2..4).map { RecentCommands.Entry(it, "Tap", Outcome.DONE) }, (2..4).map { recent[it] })
    }

    @Test
    fun aCommandRunsUntilItsEndAndAnEndThatComesLateKeepsTheOrder() {
        val recent = RecentCommands(capacity = 2)
        recent.started(2, "Scroll")
        recent.started(3, "Global")

        assertEquals(RecentCommands.Entry(2, "Scroll", null), recent[2])

        // The end of a command that is older than every command kept changes nothing.
        recent.ended(CommandReport(1, "Tap", Outcome.CANCELLED))
        recent.ended(CommandReport(2, "Scroll", Outcome.CANCELLED))

        assertNull(recent[1])
        assertEquals(RecentCommands.Entry(2, "Scroll", Outcome.CANCELLED), recent[2])
        assertEquals(RecentCommands.Entry(3, "Global", null), recent[3])
    }
}
