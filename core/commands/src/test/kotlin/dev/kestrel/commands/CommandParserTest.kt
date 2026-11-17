package dev.kestrel.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {
    @Test
    fun textWithoutWordsIsNoCommand() {
        assertNull(CommandParser.parse(""))
        assertNull(CommandParser.parse(" ?! "))
    }

    @Test
    fun aCommandMatchesOnlyTheWholeUtterance() {
        val said = "I think we should scroll down later"

        assertEquals(Command.Bare(said, null), CommandParser.parse(said))
        assertEquals(Command.Bare("Tap", null), CommandParser.parse("Tap"))
    }

    @Test
    fun bareKeepsTheWholeOriginalTranscriptAndItsNumber() {
        assertEquals(Command.Bare("Save.", null), CommandParser.parse("Save."))
        assertEquals(Command.Bare("21", 21), CommandParser.parse("21"))
        assertEquals(Command.Bare("Number five", 5), CommandParser.parse("Number five"))
    }

    @Test
    fun politeWordsAroundACommandAreDropped() {
        assertEquals(Command.Scroll(Direction.DOWN, null), CommandParser.parse("Please scroll down."))
        assertEquals(Command.Scroll(Direction.DOWN, null), CommandParser.parse("Scroll down, please"))
        assertEquals(Command.ShowNumbers, CommandParser.parse("Could you show numbers please"))
        assertEquals(Command.ShowGrid, CommandParser.parse("ok can you show grid now"))
        assertEquals(Command.Scroll(Direction.DOWN, null), CommandParser.parse("Scroll down, can you?"))
    }

    @Test
    fun politeWordsStayInPayloadsAndInBare() {
        assertEquals(Command.Tap(Target.Label("Send now")), CommandParser.parse("tap Send now"))
        assertEquals(Command.Bare("OK.", null), CommandParser.parse("OK."))
        assertEquals(Command.Bare("Please send it now", null), CommandParser.parse("Please send it now"))
    }

    @Test
    fun cutWordsKeepsTheRecognisersCaseAndInnerPunctuation() {
        assertEquals("Wi\u2011Fi", cutWords("Tap Wi\u2011Fi.", 1, 3))
        assertEquals("Hello, World", cutWords("type Hello, World!", 1, 3))
    }

    @Test
    fun targetWordsReadNumbersAndLabels() {
        val words = listOf("tap", "number", "five")
        assertEquals(Target.Number(5), TargetWords.read(words, "tap number five", 1))
        assertEquals(Target.Number(2), TargetWords.read(listOf("tap", "to"), "tap to", 1))
        assertEquals(Target.Number(5), TargetWords.read(listOf("tap", "5", "please"), "tap 5 please", 1))
        assertEquals(Target.Label("Send later"), TargetWords.read(listOf("tap", "send", "later"), "tap Send later", 1))
        assertNull(TargetWords.read(listOf("tap"), "tap", 1))
    }
}
