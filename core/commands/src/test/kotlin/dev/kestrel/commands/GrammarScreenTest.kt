package dev.kestrel.commands

import dev.kestrel.commands.Command.Bare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen phrases of Appendix A (design spec, section 16.2): every row in two or more phrasings. None of them is a
 * phrase that plan 3C's phone grammar takes.
 */
class GrammarScreenTest {
    private fun label(text: String) = Target.Label(text)

    private fun number(value: Int) = Target.Number(value)

    private val phrases: List<Pair<String, Command>> = listOf(
        // Tap and long press, by label and by number.
        "tap Send" to Command.Tap(label("Send")),
        "Click Send later." to Command.Tap(label("Send later")),
        "press save" to Command.Tap(label("save")),
        "touch the send button" to Command.Tap(label("the send button")),
        "hit Delete" to Command.Tap(label("Delete")),
        "click on Subscribe" to Command.Tap(label("Subscribe")),
        "tap 7" to Command.Tap(number(7)),
        "tap number twelve" to Command.Tap(number(12)),
        "tap to" to Command.Tap(number(2)),
        "Tap #21" to Command.Tap(number(21)),
        "Tap number 12." to Command.Tap(number(12)),
        "tap 5 please" to Command.Tap(number(5)),
        "tap now" to Command.Tap(label("now")),
        "tap home" to Command.Tap(label("home")),
        "tap Wi\u2011Fi" to Command.Tap(label("Wi\u2011Fi")),
        "long press Send" to Command.LongPress(label("Send")),
        "long-press Counter" to Command.LongPress(label("Counter")),
        "long tap 4" to Command.LongPress(number(4)),
        "press and hold Counter" to Command.LongPress(label("Counter")),
        "Long-press the second photo." to Command.LongPress(label("the second photo")),
        "touch and hold 3" to Command.LongPress(number(3)),
        // Check, uncheck and switches.
        "check Subscribe" to Command.Check(label("Subscribe"), on = true),
        "check" to Command.Check(null, on = true),
        "uncheck Subscribe" to Command.Check(label("Subscribe"), on = false),
        "uncheck 5" to Command.Check(number(5), on = false),
        "switch on Dark theme" to Command.Switch(label("Dark theme"), on = true),
        "turn on Airplane mode" to Command.Switch(label("Airplane mode"), on = true),
        "Turn on the flashlight." to Command.Switch(label("the flashlight"), on = true),
        "switch off 4" to Command.Switch(number(4), on = false),
        "turn Dark theme off" to Command.Switch(label("Dark theme"), on = false),
        "switch on" to Command.Switch(null, on = true),
        "switch off" to Command.Switch(null, on = false),
        // Expand, collapse and dismiss.
        "expand Details" to Command.Expand(label("Details")),
        "expand" to Command.Expand(null),
        "collapse 3" to Command.Collapse(number(3)),
        "collapse" to Command.Collapse(null),
        "dismiss the notification" to Command.Dismiss(label("the notification")),
        "dismiss 2" to Command.Dismiss(number(2)),
        // Drag.
        "drag 3 to 7" to Command.Drag(number(3), number(7)),
        "drag Drag me to Drop here" to Command.Drag(label("Drag me"), label("Drop here")),
        "drag 5" to Command.Drag(number(5), null),
        "drag the photo" to Command.Drag(label("the photo"), null),
        // A last "to" without a place: Kestrel asks "Drag it where?".
        "drag 3 to" to Command.Drag(number(3), null),
        "drag Send to" to Command.Drag(label("Send"), null),
        // "Drag three to two", as the recogniser writes it: the last "to" is the place.
        "drag 3 to to" to Command.Drag(number(3), number(2)),
        "drag Send to to" to Command.Drag(label("Send"), number(2)),
        // Polite words after the place's "to" are no place.
        "drag 3 to please" to Command.Drag(number(3), null),
        "drag 3 to to please" to Command.Drag(number(3), number(2)),
        // Scrolling.
        "scroll down" to Command.Scroll(Direction.DOWN, null),
        "scroll up" to Command.Scroll(Direction.UP, null),
        "scroll left" to Command.Scroll(Direction.LEFT, null),
        "Scroll right." to Command.Scroll(Direction.RIGHT, null),
        "scroll down on 3" to Command.Scroll(Direction.DOWN, number(3)),
        "Scroll down on the list." to Command.Scroll(Direction.DOWN, label("the list")),
        "scroll up in Messages" to Command.Scroll(Direction.UP, label("Messages")),
        "scroll to top" to Command.ScrollToEnd(top = true, target = null),
        "scroll to the top" to Command.ScrollToEnd(top = true, target = null),
        "scroll to bottom" to Command.ScrollToEnd(top = false, target = null),
        "scroll to the bottom of 2" to Command.ScrollToEnd(top = false, target = number(2)),
        "scroll to the top on Messages" to Command.ScrollToEnd(top = true, target = label("Messages")),
        // Swipes.
        "swipe forwards" to Command.SwipePage(forward = true),
        "swipe forward" to Command.SwipePage(forward = true),
        "swipe backwards" to Command.SwipePage(forward = false),
        "swipe backward" to Command.SwipePage(forward = false),
        "swipe from the bottom edge" to Command.SwipeFromEdge(Edge.BOTTOM),
        "swipe from bottom edge" to Command.SwipeFromEdge(Edge.BOTTOM),
        "swipe from the top" to Command.SwipeFromEdge(Edge.TOP),
        "swipe from the left edge" to Command.SwipeFromEdge(Edge.LEFT),
        "swipe from the right edge" to Command.SwipeFromEdge(Edge.RIGHT),
        "swipe from left" to Command.SwipeFromEdge(Edge.LEFT),
        "swipe up" to Command.Swipe(Direction.UP, null),
        "swipe left" to Command.Swipe(Direction.LEFT, null),
        "swipe up on 5" to Command.Swipe(Direction.UP, number(5)),
        "swipe 5 right" to Command.Swipe(Direction.RIGHT, number(5)),
        "swipe left on the pager" to Command.Swipe(Direction.LEFT, label("the pager")),
        // Pinch.
        "pinch in" to Command.Pinch(out = false, target = null),
        "pinch out" to Command.Pinch(out = true, target = null),
        "pinch out 6" to Command.Pinch(out = true, target = number(6)),
        "pinch in on the map" to Command.Pinch(out = false, target = label("the map")),
        // Polite words around a command go; without "on", a word after the direction is no target.
        "swipe up please" to Command.Swipe(Direction.UP, null),
        "pinch out please" to Command.Pinch(out = true, target = null),
        // Polite words alone are no target of a verb that may go without one.
        "check please" to Command.Check(null, on = true),
        "uncheck now" to Command.Check(null, on = false),
        "Expand, please." to Command.Expand(null),
        "collapse now" to Command.Collapse(null),
        "increase please" to Command.Slider(increase = true, target = null),
        "decrement, could you?" to Command.Slider(increase = false, target = null),
        "switch off please" to Command.Switch(null, on = false),
        "Switch on, please." to Command.Switch(null, on = true),
        "check now please" to Command.Check(null, on = true),
        "check Send now" to Command.Check(label("Send now"), on = true),
        // The recogniser may write "check" as "check it".
        "Check it." to Command.Check(null, on = true),
        // Sliders.
        "increment 4" to Command.Slider(increase = true, target = number(4)),
        "increase Brightness" to Command.Slider(increase = true, target = label("Brightness")),
        "decrement 4" to Command.Slider(increase = false, target = number(4)),
        "decrease the slider" to Command.Slider(increase = false, target = label("the slider")),
        "increment" to Command.Slider(increase = true, target = null),
        "decrease" to Command.Slider(increase = false, target = null),
        // Numbers and labels.
        "show numbers" to Command.ShowNumbers,
        "show the numbers" to Command.ShowNumbers,
        "hide numbers" to Command.HideNumbers,
        "hide the numbers" to Command.HideNumbers,
        "show labels" to Command.ShowLabels,
        "show the labels" to Command.ShowLabels,
        "hide labels" to Command.HideLabels,
        "hide the labels" to Command.HideLabels,
        "what is 5" to Command.WhatIs(5),
        "What is 5?" to Command.WhatIs(5),
        "what's number twelve" to Command.WhatIs(12),
        "show commands for 5" to Command.CommandsFor(number(5)),
        "show commands for Send" to Command.CommandsFor(label("Send")),
        "commands for Subscribe" to Command.CommandsFor(label("Subscribe")),
        // The grid.
        "show grid" to Command.ShowGrid,
        "show the grid" to Command.ShowGrid,
        "hide grid" to Command.HideGrid,
        "hide the grid" to Command.HideGrid,
        "more squares" to Command.MoreSquares,
        "more grid squares" to Command.MoreSquares,
        "fewer squares" to Command.FewerSquares,
        "less squares" to Command.FewerSquares,
        // Help and again.
        "what can I say" to Command.Help(HelpKind.SUMMARY),
        "What can I say?" to Command.Help(HelpKind.SUMMARY),
        "show all commands" to Command.Help(HelpKind.ALL),
        "show commands" to Command.Help(HelpKind.ALL),
        "open tutorial" to Command.Help(HelpKind.TUTORIAL),
        "again" to Command.Again,
        "Again." to Command.Again,
        "repeat that" to Command.Again,
        "do it again" to Command.Again,
    )

    @Test
    fun everyScreenPhraseParsesToItsCommand() {
        for ((said, expected) in phrases) {
            assertEquals(said, expected, CommandParser.parse(said))
        }
    }

    @Test
    fun phrasesThatAreNoScreenCommandStayBare() {
        for (said in listOf(
            "Save",
            "Send later",
            "play",
            "pause",
            "help",
            "stop",
            "cancel",
            "tap",
            "scroll",
            "swipe",
            "drag",
            "I think we should go back later",
            "Can you tell me what time it is",
            "what is this",
            "let me check",
            // Not in Appendix A, and common in conversation.
            "what can I do",
            "turn on",
            "Turn off.",
            "turn on please",
            "Turn off, now.",
            "switch please off",
        )) {
            val parsed = CommandParser.parse(said)
            assertTrue("$said gave $parsed", parsed is Bare)
            assertEquals(said, (parsed as Bare).text)
        }
    }
}
