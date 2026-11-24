package dev.kestrel.app.debug

import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.testing.FakeData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The debug build's witness of a task: its newest say texts, the sentence of its end and the run's marker in the data tools (plan 6B-basefix). */
class DebugTaskWitnessTest {
    @Test
    fun aWitnessKeepsTheNewestSayTextsAndTheSentenceOfTheEndAndWithAMarkerWatchesTheDataTools() = runTest {
        val data = FakeData()
        val notification = DataResult.Ok(listOf(DataLine("Notification 1.", listOf(DataField("title", "Kestrel test Maple heron")))))
        data.answer("list_notifications", notification)
        data.answer("list_events", DataResult.Ok(listOf(DataLine("Event 1.", listOf(DataField("title", "heron maple"))))))
        val witness = DebugTaskWitness.open(mapOf(DebugTaskWitness.MARKER to "maple heron"))
        val watched = witness.watch(data)

        watched.listEvents(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25))
        assertEquals(false, witness.evidence.markerInData)
        assertEquals(notification, watched.listNotifications())
        assertEquals(true, witness.evidence.markerInData)
        repeat(DebugTaskWitness.MAX_SAID + 1) { witness.said("Line $it.") }
        assertEquals((1..DebugTaskWitness.MAX_SAID).map { "Line $it." }, witness.evidence.said)
        assertNull(witness.evidence.spoken)
        witness.ended("I read your notifications.")
        assertEquals("I read your notifications.", witness.evidence.spoken)

        // Without a marker the data tools stay as they are, and the evidence says that the task had none.
        val plain = DebugTaskWitness.open(emptyMap())
        assertSame(data, plain.watch(data))
        assertNull(plain.evidence.markerInData)
    }

    @Test
    fun aMarkerIsHeldOnlyWhenItsWordsComeInOrder() {
        assertTrue(MarkerWatch.holds("Kestrel test: Maple heron.", "maple heron"))
        assertFalse(MarkerWatch.holds("The e-mail about heron maple.", "maple heron"))
        assertFalse(MarkerWatch.holds("maple", "maple heron"))
    }
}
