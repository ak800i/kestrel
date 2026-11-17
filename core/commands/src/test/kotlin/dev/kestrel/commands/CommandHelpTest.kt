package dev.kestrel.commands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandHelpTest {
    @Test
    fun everyExampleParsesToACommand() {
        for (group in CommandHelp.groups) {
            for (example in group.examples) {
                val parsed = CommandParser.parse(example)
                assertNotNull(example, parsed)
                assertFalse("$example parses to $parsed", parsed is Command.Bare)
            }
        }
    }

    @Test
    fun theListJoinsTheGroupsOfEveryPlan() {
        assertTrue(CommandHelp.groups.containsAll(HelpScreen.groups))
        assertTrue(CommandHelp.groups.containsAll(HelpText.groups))
        assertTrue(CommandHelp.groups.containsAll(HelpPhone.groups))
        assertTrue(HelpScreen.groups.all { it.title.isNotBlank() && it.examples.isNotEmpty() })
    }
}
