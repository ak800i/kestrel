package dev.kestrel.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandHelpTest {
    // The commands that each screen group of the list stands for.
    private val screenKinds: Map<String, Set<Class<out Command>>> = mapOf(
        "Tap and press" to setOf(Command.Tap::class.java, Command.LongPress::class.java),
        "Switches and sliders" to setOf(Command.Check::class.java, Command.Switch::class.java, Command.Slider::class.java),
        "Notifications and lists" to setOf(Command.Expand::class.java, Command.Collapse::class.java, Command.Dismiss::class.java),
        "Scroll and swipe" to setOf(
            Command.Scroll::class.java,
            Command.ScrollToEnd::class.java,
            Command.SwipePage::class.java,
            Command.SwipeFromEdge::class.java,
            Command.Swipe::class.java,
        ),
        "Drag and pinch" to setOf(Command.Drag::class.java, Command.Pinch::class.java),
        "Numbers and labels" to setOf(
            Command.ShowNumbers::class.java,
            Command.HideNumbers::class.java,
            Command.ShowLabels::class.java,
            Command.HideLabels::class.java,
            Command.WhatIs::class.java,
            Command.CommandsFor::class.java,
        ),
        "The grid" to setOf(
            Command.ShowGrid::class.java,
            Command.HideGrid::class.java,
            Command.MoreSquares::class.java,
            Command.FewerSquares::class.java,
            Command.Tap::class.java,
        ),
        "Help and repeating" to setOf(Command.Help::class.java, Command.Again::class.java),
    )

    @Test
    fun everyExampleParsesToACommand() {
        for (group in CommandHelp.groups) {
            for (example in group.examples) {
                val parsed = CommandParser.parse(example)
                assertNotNull(example, parsed)
                assertFalse("$example parses to $parsed", parsed is Command.Bare)
            }
        }
        // A screen example parses to a command of its own group, not to a tap on a label with its words.
        assertEquals(screenKinds.keys, HelpScreen.groups.map { it.title }.toSet())
        for (group in HelpScreen.groups) {
            for (example in group.examples) {
                val parsed = CommandParser.parse(example)!!
                assertTrue("$example parses to $parsed", parsed.javaClass in screenKinds.getValue(group.title))
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
