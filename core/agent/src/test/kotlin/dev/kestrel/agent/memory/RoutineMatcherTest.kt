package dev.kestrel.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineMatcherTest {
    private val viber = Routine(
        name = "Viber late",
        phrasings = listOf("send {contact} a viber message that {text}", "tell {contact} on viber that {text}", "viber {contact} that {text}"),
        slots = listOf(Slot("contact", SlotType.CONTACT), Slot("text", SlotType.TEXT)),
        steps = emptyList(),
    )
    private val alarm = Routine(
        name = "Alarm",
        phrasings = listOf("set my alarm for {time}", "wake me at {time}", "alarm at {time}"),
        slots = listOf(Slot("time", SlotType.TIME)),
        steps = emptyList(),
    )
    private val contacts = setOf("Ana Petrović", "John Smith")

    @Test
    fun theFixedWordsMatchInOrderAndTheSlotsTakeTheWordsBetweenThem() {
        val match = RoutineMatcher.match("Send John a Viber message that I'm 10 minutes late.", listOf(viber, alarm), emptySet(), contacts)

        assertEquals(viber, match!!.routine)
        assertEquals(mapOf("contact" to "John", "text" to "I'm 10 minutes late"), match.values)
        assertEquals("Ana Petrovic", RoutineMatcher.match("Tell Ana Petrovic on Viber that hi", listOf(viber), emptySet(), contacts)!!.values["contact"])
    }

    @Test
    fun aContactSlotNeedsOneContactOrAnAlias() {
        assertNull(RoutineMatcher.match("Send Zed a Viber message that hi", listOf(viber), emptySet(), contacts))
        assertEquals("Zed", RoutineMatcher.match("Send Zed a Viber message that hi", listOf(viber), setOf("Zed"), contacts)!!.values["contact"])
    }

    @Test
    fun aTimeSlotMustReadAsATime() {
        assertEquals("7:30", RoutineMatcher.match("Wake me at 7:30", listOf(alarm), emptySet(), contacts)!!.values["time"])
        assertEquals("7 pm", RoutineMatcher.match("Set my alarm for 7 pm", listOf(alarm), emptySet(), contacts)!!.values["time"])
        assertNull(RoutineMatcher.match("Wake me at the station", listOf(alarm), emptySet(), contacts))
    }

    @Test
    fun aRequestThatMissesAWordOrMatchesTwoRoutinesRunsNoRoutine() {
        assertNull(RoutineMatcher.match("Send John a message that hi", listOf(viber), emptySet(), contacts))
        assertNull(RoutineMatcher.match("Send John a Viber message", listOf(viber), emptySet(), contacts))
        val twin = viber.copy(name = "Twin")
        assertNull(RoutineMatcher.match("Send John a Viber message that hi", listOf(viber, twin), emptySet(), contacts))
    }

    @Test
    fun overlapsTellsAPhrasingThatWouldTakeAnotherPhrase() {
        assertTrue(RoutineMatcher.overlaps("delete history", "delete {thing}"))
        assertTrue(RoutineMatcher.overlaps("send x a viber message that x", "send {who} a viber message that {what}"))
        assertEquals(false, RoutineMatcher.overlaps("continue", "send {contact} a viber message that {text}"))
    }
}
