package dev.kestrel.eval.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The rules of a task's turns (design 14.4 of sub-project 7), which every task's construction checks. */
class TurnsTest {
    @Test
    fun aTasksTurnsFollowTheirRules() {
        val say = Turn.Say(Request("turn on hotspot"))
        val acting = Turn.Say(Request("set an alarm for 7:00"), until = Until.ACTING)

        assertNull(Turns.problem(Turns.one("turn on hotspot")))
        assertNull(Turns.problem(listOf(acting, Turn.Steer(Request("make it 7:30 instead")), Turn.AwaitEnd(), Turn.Say(Request("and more"), TurnChannel.FOLLOW_UP))))
        assertNull(Turns.problem(listOf(acting, Turn.Restart, Turn.Say(Request("continue")))))
        assertEquals("its first turn must be a command", Turns.problem(listOf(Turn.Restart, say)))
        assertEquals("its first command cannot speak in a follow-up window", Turns.problem(listOf(Turn.Say(Request("and more"), TurnChannel.FOLLOW_UP))))
        assertEquals("turn 2 gives a command while the one before it runs; await its end first", Turns.problem(listOf(acting, say)))
        assertEquals("turn 2 steers no goal that acts", Turns.problem(listOf(say, Turn.Steer(Request("later")))))
        assertEquals("turn 2 steers inside a follow-up window, which steering never uses", Turns.problem(listOf(acting, Turn.Steer(Request("later"), TurnChannel.FOLLOW_UP))))
        assertEquals("turn 2 awaits no command", Turns.problem(listOf(say, Turn.AwaitEnd())))
        assertEquals(
            "turn 2 speaks in a follow-up window that no spoken command opened",
            Turns.problem(listOf(Turn.Say(Request("find it"), TurnChannel.TYPED), Turn.Say(Request("and send it"), TurnChannel.FOLLOW_UP))),
        )
        assertEquals("its turns end while a command runs; await its end", Turns.problem(listOf(acting)))
        assertEquals("Task broken: its first turn must be a command.", assertThrows(IllegalArgumentException::class.java) { StandIns.HOTSPOT.copy(name = "broken", turns = emptyList()) }.message)
    }
}
