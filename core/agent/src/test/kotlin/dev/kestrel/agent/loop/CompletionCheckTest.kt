package dev.kestrel.agent.loop

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Harness
import dev.kestrel.agent.Screens
import dev.kestrel.agent.observe.ObservationBuilder
import dev.kestrel.agent.observe.ObservationInput
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.agent.tools.AgentCall
import dev.kestrel.agent.tools.Evidence
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.Redactor
import dev.kestrel.safety.Vault
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionCheckTest {
    private val builder = ObservationBuilder(Redactor(Vault()), AgentFreeApps())

    private fun table(snapshot: ScreenSnapshot) = builder.build(ObservationInput(1, snapshot, "Kestrel fixtures")).table

    private fun number(snapshot: ScreenSnapshot, label: String) = table(snapshot).entries.first { it.label == label }.number

    private fun finish(vararg evidence: Evidence) = AgentCall.Finish("Done.", evidence.toList(), null, emptyList())

    private fun check(
        finish: AgentCall.Finish,
        shown: ScreenSnapshot,
        fresh: ScreenSnapshot = shown,
        dataSteps: Set<Int> = emptySet(),
        sends: List<ConfirmedSend> = emptyList(),
        lastChecked: Map<String, Boolean> = emptyMap(),
    ) = CompletionCheck.check(finish, table(shown), fresh, "Kestrel fixtures", dataSteps, sends, lastChecked)

    @Test
    fun aFinishWithoutEvidenceOrWithAGoneElementFails() {
        val chat = Screens.chat("Ana Petrović", "See you")

        assertEquals(Verdict.Missing("no evidence was cited"), check(finish(), chat))
        assertEquals(Verdict.Passed, check(finish(Evidence(number(chat, "See you"), null, null)), chat))
        assertTrue(check(finish(Evidence(number(chat, "See you"), null, null)), chat, fresh = Screens.chats()) is Verdict.Missing)
    }

    @Test
    fun aSwitchMustShowTheStateThatItsEvidenceOrTheTasksLastActionNames() {
        val off = Screens.settingsPage("Hotspot", "Wi-Fi hotspot", checked = false)
        val on = Screens.settingsPage("Hotspot", "Wi-Fi hotspot", checked = true)
        val switch = number(on, "Wi-Fi hotspot")
        val key = table(on).entry(switch)!!.key!!

        assertTrue(check(finish(Evidence(switch, null, checked = true)), on, fresh = off) is Verdict.Missing)
        assertEquals(Verdict.Passed, check(finish(Evidence(switch, null, checked = true)), on))
        assertTrue(check(finish(Evidence(switch, null, null)), on, fresh = off, lastChecked = mapOf(key to true)) is Verdict.Missing)
        assertEquals(Verdict.Missing("say which state ($switch) should show"), check(finish(Evidence(switch, null, null)), on))
    }

    @Test
    fun aCitedStepMustBeADataStepWithAResult() {
        val chat = Screens.chat("Ana Petrović", "See you")

        assertTrue(check(finish(Evidence(null, 3, null)), chat, dataSteps = setOf(2)) is Verdict.Missing)
        assertEquals(Verdict.Passed, check(finish(Evidence(null, 2, null)), chat, dataSteps = setOf(2)))
    }

    @Test
    fun everyConfirmedSendNeedsItsOwnEvidence() {
        val sent = Screens.chat("Ana Petrović", "Hi", "I'm late")
        val send = ConfirmedSend(listOf("Ana Petrović"), "I'm late", share = false)

        assertTrue(check(finish(Evidence(number(sent, "Hi"), null, null)), sent, sends = listOf(send)) is Verdict.Missing)
        assertEquals(Verdict.Passed, check(finish(Evidence(number(sent, "I'm late"), null, null)), sent, sends = listOf(send)))
        // The owner's step after a hand-over may be cited as a step, but a confirmed send still needs its own evidence.
        assertTrue(check(finish(Evidence(null, 4, null)), sent, dataSteps = setOf(4), sends = listOf(send)) is Verdict.Missing)

        val shared = ConfirmedSend(listOf("Ana Petrović"), null, share = true)
        val photos = Screens.chat("Ana Petrović", "Hi", "3 photos")
        assertEquals(Verdict.Passed, check(finish(Evidence(number(photos, "3 photos"), null, null)), photos, sends = listOf(shared)))
        assertTrue(check(finish(Evidence(number(photos, "Hi"), null, null)), photos, sends = listOf(shared)) is Verdict.Missing)
        val other = Screens.chat("Marko Marković", "3 photos")
        assertTrue(check(finish(Evidence(number(other, "3 photos"), null, null)), other, sends = listOf(shared)) is Verdict.Missing)
    }

    @Test
    fun evidenceOfOnlyOcrLinesCannotBeChecked() {
        val chat = Screens.chat("Ana Petrović", "Hi")
        val withOcr = builder.build(ObservationInput(1, chat, "Kestrel fixtures", ocr = listOf(OcrLine("Paid", IntRect(40, 1500, 1040, 1600), 0.95)))).table
        val ocr = withOcr.entries.first { it.ocr }.number

        assertEquals(Verdict.Unverifiable, CompletionCheck.check(finish(Evidence(ocr, null, null)), withOcr, chat, "Kestrel fixtures", emptySet(), emptyList(), emptyMap()))
    }

    @Test
    fun aFailedFinishGoesBackToTheModelAndAnUncheckableOneIsUnverified() = runTest {
        val harness = Harness(Screens.chat("Ana Petrović", "Hi"), listOf(
            call("finish", null, null, null, "summary" to "Sent.", "evidence" to emptyList<Any>()),
            call("finish", null, null, null, "summary" to "Sent.", "evidence" to listOf(mapOf("target" to mapOf("label" to "Hi")))),
        ))
        assertEquals(EndReason.FINISHED, harness.run("Read Ana's chat").end)
        assertTrue(harness.lastRequestText().contains("finish failed: no evidence was cited"))

        val gone = Harness(Screens.chat("Ana Petrović", "Hi"), listOf(
            call("say", null, null, null, "text" to "Almost done."),
            call("finish", null, null, null, "summary" to "Read it.", "evidence" to listOf(mapOf("target" to mapOf("label" to "Hi")))),
        ))
        gone.owner.onSay = { gone.screen.current = null }
        val report = gone.run("Read Ana's chat")
        assertEquals(EndReason.UNVERIFIED, report.end)
        assertEquals("Read it.", report.summary)

        // A pause while the model answered (a lock, a call): the screen that it saw cannot be checked.
        val paused = Harness(Screens.chat("Ana Petrović", "Hi"), listOf(
            call("finish", null, null, null, "summary" to "Read it.", "evidence" to listOf(mapOf("target" to mapOf("label" to "Hi")))),
        ))
        paused.owner.gates += listOf(Gate.GO, Gate.RESUMED)
        assertEquals(EndReason.UNVERIFIED, paused.run("Read Ana's chat").end)
    }
}
