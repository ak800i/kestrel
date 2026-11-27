package dev.kestrel.eval.run

import dev.kestrel.eval.hook.HookAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The run's commands' ends as one answer (design 14.4 of sub-project 7). */
class OutcomesTest {
    private fun command(tag: String, answer: String?, expected: String? = "FINISHED") = SentCommand(tag, expected).apply { this.answer = answer?.let(::HookAnswer) }

    @Test
    fun aRunOfOneCommandKeepsItsAnswerAndACommandWhoseEndIsNotReadLeavesNone() {
        val one = "state=done end=FINISHED steps=3 credits=0.30 marker=1"

        assertEquals(one, Outcomes.merge(listOf(command("t1", one)))?.text)
        assertNull(Outcomes.merge(listOf(command("t1", one), command("t2", null))))
        assertNull(Outcomes.merge(emptyList()))
    }

    @Test
    fun theEndIsTheFirstUnexpectedEndOfAnEarlierCommandElseTheLastAndTheCountsAreSums() {
        val paused = "state=done end=PAUSED_AT_LIMIT steps=2 workMs=10 credits=20.00 confirmed=0 unconfirmed=0 model=scripted in=5"
        val finished = "state=done end=FINISHED steps=1 workMs=5 credits=0.10 confirmed=1 unconfirmed=1 model=none in=2"

        val merged = Outcomes.merge(listOf(command("t1", paused, expected = "PAUSED_AT_LIMIT"), command("t2", finished)))!!

        assertEquals("FINISHED", merged["end"])
        assertEquals(listOf("3", "15", "20.10", "1", "1", "scripted", "7"), listOf("steps", "workMs", "credits", "confirmed", "unconfirmed", "model", "in").map { merged[it] })
        assertEquals("FAILED_BY_MODEL", Outcomes.merge(listOf(command("t1", "state=done end=FAILED_BY_MODEL"), command("t2", finished)))!!["end"])
        assertEquals("FINISHED", Outcomes.merge(listOf(command("t1", "state=done end=CANCELLED", expected = null), command("t2", finished)))!!["end"])
    }
}
