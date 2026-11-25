package dev.kestrel.app.agent

import dev.kestrel.agent.port.DataPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the debug hooks read of the newest rounds (plan 6B-basefix; design 14.4 of sub-project 7). */
class RoundEvidencesTest {
    @Test
    fun eachRoundCountsItsTouchesAndItsSignalsAndTheNewestSixteenStay() {
        val evidences = RoundEvidences()
        val witness = object : TaskWitness {
            override fun said(text: String) = Unit

            override fun watch(data: DataPort): DataPort = data

            override val evidence: TaskEvidence get() = TaskEvidence(listOf("Done."), markerInData = true)
        }

        val round = evidences.start(1, witness)
        round.touched(signal = false)
        round.touched(signal = true)

        assertEquals(RoundEvidences.Evidence(2, 1, TaskEvidence(listOf("Done."), markerInData = true)), evidences.of(1))
        for (command in 2..RoundEvidences.MAX + 1) evidences.start(command, TaskWitness.NONE)
        assertNull(evidences.of(1))
        assertEquals(RoundEvidences.Evidence(), evidences.of(RoundEvidences.MAX + 1))
    }
}
