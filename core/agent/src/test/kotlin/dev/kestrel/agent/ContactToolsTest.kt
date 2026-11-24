package dev.kestrel.agent

import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataPort
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.safety.Mode
import dev.kestrel.safety.SafetySettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** add_contact and delete_contact through the loop (design D19 of sub-project 6). */
class ContactToolsTest {
    private val added = DataResult.Ok(listOf(DataLine("added contact", listOf(DataField("name", "Maple Heron"), DataField("number", "+1 202 555 0160")))))
    private val found = DataResult.Ok(listOf(
        DataLine("contact 1 (apps: Telegram)", listOf(DataField("name", "Ana Test"), DataField("phone", "+1 202 555 0143 (mobile)")), ref = "41"),
        DataLine("contact 2 (apps: Viber)", listOf(DataField("name", "Ana Testić"), DataField("phone", "+381 60 000 0000 (mobile)")), ref = "42"),
    ))
    private val stop = call("fail", null, null, null, "reason" to "Stop.")

    private fun finish(step: Int) = call("finish", null, null, null, "summary" to "Done.", "evidence" to listOf(mapOf("step" to step)))

    @Test
    fun aContactOfTheOwnersWordsIsAddedWithoutAQuestionAndOneWithAnotherNumberIsAsked() = runTest {
        val own = Harness(Screens.chats(), listOf(call("add_contact", null, null, null, "name" to "Maple Heron", "number" to "+12025550160"), finish(1)))
        own.data.answer("add_contact", added)

        assertEquals(EndReason.FINISHED, own.run("add a contact maple heron with the number +1 202 555 0160").end)
        assertTrue(own.owner.confirmations.isEmpty())
        assertEquals(listOf("Maple Heron"), own.data.names)

        val other = Harness(Screens.chats(), listOf(call("add_contact", null, null, null, "name" to "Maple Heron", "number" to "+1 202 555 0199"), stop))
        other.data.answer("add_contact", added)
        other.run("add a contact maple heron with the number +1 202 555 0160")

        assertTrue(other.owner.confirmations.single().question.endsWith("Add the contact 'Maple Heron' with the number +1 202 555 0199? Say confirm or cancel."))
        assertTrue(other.data.calls.isEmpty())
    }

    @Test
    fun anAddContactThatFindsTheContactAlreadyThereTellsTheReportNothingWasAdded() = runTest {
        val harness = Harness(Screens.chats(), listOf(call("add_contact", null, null, null, "name" to "Maple Heron", "number" to "+12025550160"), finish(1)))
        harness.data.answer("add_contact", DataResult.Ok(listOf(DataLine(DataPort.ADDED_NOTHING, added.lines.single().fields))))

        val report = harness.run("add a contact maple heron with the number +1 202 555 0160")

        assertEquals(EndReason.FINISHED, report.end)
        assertTrue(report.ledger.toString(), report.ledger.none { it.startsWith("added") })
        assertTrue(harness.lastRequestText().contains(DataPort.ADDED_NOTHING))

        val new = Harness(Screens.chats(), listOf(call("add_contact", null, null, null, "name" to "Maple Heron", "number" to "+12025550160"), finish(1)))
        new.data.answer("add_contact", added)
        assertTrue(new.run("add a contact maple heron with the number +1 202 555 0160").ledger.contains("added the contact Maple Heron"))
    }

    @Test
    fun deleteContactNeedsFindContactFirstAndDeletesTheContactBehindItsNumberAfterTheOwnersYes() = runTest {
        val first = Harness(Screens.chats(), listOf(call("delete_contact", null, null, null, "contact" to 1), stop))
        first.run("delete the contact Ana Test")
        assertTrue(first.lastRequestText(), first.lastRequestText().contains("failed: Call find_contact first"))
        assertTrue(first.data.calls.isEmpty())

        val harness = Harness(Screens.chats(), listOf(
            call("find_contact", null, null, null, "name" to "Ana Test"),
            call("delete_contact", null, null, null, "contact" to 2),
            finish(2),
        ))
        harness.data.answer("find_contact", found)
        harness.data.answer("delete_contact", DataResult.Ok(listOf(DataLine("deleted the contact", emptyList()))))
        harness.owner.confirms += ConfirmEnd.YES

        val report = harness.run("delete the contact Ana Test")

        assertEquals(EndReason.FINISHED, report.end)
        assertEquals(listOf("42"), harness.data.refs)
        assertEquals("Delete the contact 'Ana Testić' (+381 60 000 0000)? Say confirm or cancel.", harness.owner.confirmations.single().question)
        assertEquals(1, report.confirmed)
        assertEquals(0, report.unconfirmed)
        assertTrue(report.ledger.toString(), report.ledger.contains("deleted Ana Testić"))
        assertTrue(harness.lastRequestText().contains("contact 2"))
        assertTrue(!harness.lastRequestText().contains("42"))
    }

    @Test
    fun aDeleteWithoutAYesRunsOnlyInFullAutoAndCountsAsUnconfirmed() = runTest {
        val harness = Harness(
            Screens.chats(),
            listOf(call("find_contact", null, null, null, "name" to "Ana Test"), call("delete_contact", null, null, null, "contact" to 1), finish(2)),
            settings = AgentSettings(safety = SafetySettings(testMode = false, mode = Mode.FULL_AUTO)),
        )
        harness.data.answer("find_contact", found)
        harness.data.answer("delete_contact", DataResult.Ok(listOf(DataLine("deleted the contact", emptyList()))))

        val report = harness.run("delete the contact Ana Test")

        assertEquals(EndReason.FINISHED, report.end)
        assertTrue(harness.owner.confirmations.isEmpty())
        assertEquals(1, report.unconfirmed)
        assertTrue(report.ledger.toString(), report.ledger.contains("deleted Ana Test"))
    }
}
