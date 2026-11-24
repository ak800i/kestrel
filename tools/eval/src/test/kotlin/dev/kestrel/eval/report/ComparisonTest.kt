package dev.kestrel.eval.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design D10's judgement of a device against its own part of the baseline. */
class ComparisonTest {
    private val names = ('a'..'j').map { "$it" }
    private val part = BaselinePart("c".repeat(40), "m1", names.associateWith { BaselineTask(3, 3) })

    private fun results(vararg changed: Pair<String, Int>): Map<String, Rate> = names.associateWith { name -> Rate(changed.toMap()[name] ?: 3, 3) }

    @Test
    fun aDeviceWithinFivePointsPassesAndItsFallsAreListed() {
        val judgement = Comparison.judge("kestrel-api37", results("j" to 2), part, emptySet())

        assertTrue(judgement.passed)
        assertEquals(10, judgement.shared.size)
        assertEquals(listOf("j"), judgement.fell)
        assertEquals(emptyList<String>(), judgement.collapsed)
        assertEquals("compare=kestrel-api37 result=pass rate=96.7 baseline=100.0 tasks=10", judgement.lines().first())
    }

    @Test
    fun aDeviceMoreThanFivePointsBelowFailsAndPassesWhenItsReRunsBringItWithinThem() {
        val first = results("i" to 2, "j" to 1)
        val judgement = Comparison.judge("a71", first, part, emptySet())

        assertFalse(judgement.passed)
        assertEquals(listOf("i", "j"), judgement.fell)
        assertEquals(listOf("j"), judgement.collapsed)
        assertEquals("compare=a71 result=fail rate=90.0 baseline=100.0 tasks=10", judgement.lines().first())

        val again = first + mapOf("i" to first.getValue("i") + Rate(3, 3), "j" to first.getValue("j") + Rate(3, 3))
        val rerun = Comparison.judge("a71", again, part, emptySet(), first)
        assertTrue(rerun.passed)
        // Always reported: 3 of 3 in the baseline, 1 of 3 in the first runs.
        assertEquals(listOf("j"), rerun.collapsed)
        assertTrue(rerun.lines().contains("compare=a71 collapsed=j"))
    }

    @Test
    fun aCollapseCountsOnlyFirstRunsOfThree() {
        // 1 of 1 is no fall from 3 of 3 to 1 of 3, and 1 of 5 is not one either: D10's rule names 3 runs.
        assertEquals(emptyList<String>(), Comparison.judge("a71", names.associateWith { Rate(1, 1) }, part, emptySet()).collapsed)
        assertEquals(emptyList<String>(), Comparison.judge("a71", names.associateWith { Rate(1, 5) }, part, emptySet()).collapsed)
        assertEquals(listOf("j"), Comparison.judge("a71", results("j" to 0), part, emptySet()).collapsed)
    }

    @Test
    fun knownGapsAreLeftOutAndTasksOfOnlyOneSideAreListedApart() {
        val withGap = part.copy(tasks = part.tasks + mapOf("telegram-photos" to BaselineTask(3, 3), "waze-home" to BaselineTask(1, 3)))
        val results = results() + mapOf("telegram-photos" to Rate(0, 1), "timer" to Rate(0, 3), "waze-home" to Rate(0, 0))

        val judgement = Comparison.judge("a71", results, withGap, setOf("telegram-photos"))

        assertTrue(judgement.passed)
        assertEquals(10, judgement.shared.size)
        assertEquals(listOf("timer"), judgement.onlyResults)
        assertEquals(listOf("waze-home"), judgement.onlyBaseline)
        assertTrue(judgement.lines().containsAll(listOf("compare=a71 onlyResults=timer", "compare=a71 onlyBaseline=waze-home")))
    }
}
