package dev.kestrel.agent.memory

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Harness
import dev.kestrel.agent.Screens
import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.testing.FakeApps
import dev.kestrel.agent.testing.FakeClock
import dev.kestrel.agent.testing.FakeMemory
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.agent.tools.NoteDraft
import dev.kestrel.safety.Redactor
import dev.kestrel.safety.Vault
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {
    private val fixtures = Screens.MESSENGER

    private fun noteTask(vararg notes: String): Harness = Harness(Screens.chats(), listOf(
        call("tap", "Ana Petrović", null, null, "reason" to "open the chat", "category" to "reading"),
        call("finish", null, null, null, "summary" to "Opened.",
            "evidence" to listOf(mapOf("target" to mapOf("label" to "Hi"))),
            "notes" to notes.map { mapOf("app" to "Kestrel fixtures", "note" to it) }),
    )).apply { screen.onTap("Ana Petrović", Screens.chat("Ana Petrović", "Hi")) }

    @Test
    fun aRouteNoteIsAPathOfFixedVerbsWithQuotedLabels() {
        assertEquals(
            listOf(RouteStep("open", "Chats"), RouteStep("tap", "New chat"), RouteStep("long press", "Ana"), RouteStep("back", null)),
            RouteNote.parse("open \"Chats\" → tap \"New chat\" -> long press \"Ana\" → back"),
        )
        assertEquals("open \"Chats\" → back", RouteNote.format(RouteNote.parse("open  \"Chats\"->back")!!))
        assertNull(RouteNote.parse("In Viber, the new-chat button is the pencil icon"))
        assertNull(RouteNote.parse("press \"Send\""))
        assertNull(RouteNote.parse("tap \"\""))
        assertNull(RouteNote.parse(List(9) { "back" }.joinToString(" → ")))
    }

    @Test
    fun aNoteIsStoredAfterAVerifiedFinishOnlyWithLabelsThatTheTaskActedOnAndCountsAtItsSecondProposal() = runTest {
        val first = noteTask("tap \"Ana Petrović\"", "tap \"Settings\"")
        assertEquals(EndReason.FINISHED, first.run("Open Ana's chat in Kestrel fixtures").end)
        val stored = first.memory.notes[fixtures]!!
        assertEquals(listOf("tap \"Ana Petrović\""), stored.map { it.path })
        assertFalse(stored.single().active)
        assertEquals("1.0", stored.single().version)

        val second = noteTask("tap \"Ana Petrović\"")
        second.memory.notes[fixtures] = stored
        second.run("Open Ana's chat in Kestrel fixtures")
        assertTrue(second.memory.notes[fixtures]!!.single().active)
    }

    @Test
    fun aNoteWithANumberACodeOrAnAddressOrFromAnUnverifiedTaskIsNotStored() = runTest {
        val store = FakeMemory()
        val memory = TaskMemory(store, FakeApps(), FakeClock(), Redactor(Vault()))
        memory.load()
        listOf("064 123 4567", "ana@example.com", "Your code 483920", "Chats").forEach { memory.acted(fixtures, it) }
        memory.admit(listOf(
            NoteDraft("Kestrel fixtures", "tap \"064 123 4567\""),
            NoteDraft("Kestrel fixtures", "tap \"ana@example.com\""),
            NoteDraft("Kestrel fixtures", "tap \"Your code 483920\""),
        ))
        assertNull(store.notes[fixtures])
        memory.admit(listOf(NoteDraft("Kestrel fixtures", "open \"Chats\"")))
        assertEquals(listOf("open \"Chats\""), store.notes[fixtures]!!.map { it.path })

        // A finish that cannot be checked (the screen is gone) stores no note, even a good one.
        val unverified = Harness(Screens.chats(), listOf(
            call("tap", "Ana Petrović", null, null, "reason" to "open", "category" to "reading"),
            call("say", null, null, null, "text" to "Almost done."),
            call("finish", null, null, null, "summary" to "Opened.", "evidence" to listOf(mapOf("target" to mapOf("label" to "Hi"))),
                "notes" to listOf(mapOf("app" to "Kestrel fixtures", "note" to "tap \"Ana Petrović\""))),
        ))
        unverified.screen.onTap("Ana Petrović", Screens.chat("Ana Petrović", "Hi"))
        unverified.owner.onSay = { unverified.screen.current = null }
        assertEquals(EndReason.UNVERIFIED, unverified.run("Open Ana's chat in Kestrel fixtures").end)
        assertNull(unverified.memory.notes[fixtures])
    }

    @Test
    fun activeNotesGoIntoTheContextOfATaskThatNamesTheAppAndStrikesRemoveThem() = runTest {
        val note = RouteNote(fixtures, "tap \"Ana Petrović\"", "0.9", "en", proposals = 2)
        val harness = Harness(Screens.chats(), listOf(call("fail", null, null, null, "reason" to "Stop.")))
        harness.memory.notes[fixtures] = listOf(note)

        harness.run("Open Ana's chat in Kestrel fixtures")

        val context = harness.model.requests.single().context!!
        assertTrue(context.contains(TaskMemory.NOTES_HEADER))
        assertTrue(context.contains("- Kestrel fixtures: tap \"Ana Petrović\" (written for version 0.9)"))
        assertEquals(1, harness.memory.notes[fixtures]!!.single().strikes)

        val again = Harness(Screens.chats(), listOf(call("fail", null, null, null, "reason" to "Stop.")))
        again.memory.notes[fixtures] = harness.memory.notes[fixtures]!!
        again.run("Open Ana's chat in Kestrel fixtures")
        assertTrue(again.memory.notes[fixtures]!!.isEmpty())

        // A pause while the model answered ends the task as unverified, but says nothing about the notes.
        val paused = Harness(Screens.chats(), listOf(
            call("finish", null, null, null, "summary" to "Opened.", "evidence" to listOf(mapOf("target" to mapOf("label" to "Chats")))),
        ))
        paused.memory.notes[fixtures] = listOf(note)
        paused.owner.gates += listOf(Gate.GO, Gate.RESUMED)
        assertEquals(EndReason.UNVERIFIED, paused.run("Open Ana's chat in Kestrel fixtures").end)
        assertEquals(0, paused.memory.notes[fixtures]!!.single().strikes)
    }

    @Test
    fun theNotesOfAnAppThatATaskOpensComeWithItsResult() = runTest {
        val harness = Harness(Screens.app("com.android.launcher3", "Home"), listOf(
            call("open_app", null, null, null, "name" to "Telegram", "reason" to "open it"),
            call("fail", null, null, null, "reason" to "Stop."),
        ))
        harness.memory.notes["org.telegram.messenger"] = listOf(RouteNote("org.telegram.messenger", "tap \"Saved Messages\"", "12.2", "en", proposals = 2))
        harness.screen.onOpen(Screens.app("org.telegram.messenger", "Chats"))

        harness.run("Send a note to myself")

        assertNull(harness.model.requests.first().context)
        assertTrue(harness.lastRequestText().contains("- Telegram: tap \"Saved Messages\""))
    }

    @Test
    fun factsThatTheRequestNamesGoIntoTheContextAndAliasesResolveFirst() = runTest {
        val harness = Harness(Screens.chats(), listOf(
            call("find_contact", null, null, null, "name" to "mom"),
            call("navigate", null, null, null, "app" to "waze", "to" to "home"),
            call("fail", null, null, null, "reason" to "Stop."),
        ))
        harness.memory.facts += listOf(Fact("home", "Main Street 1", FactKind.ALIAS), Fact("mom", "contact Mama", FactKind.ALIAS), Fact("gate code", "blue"))
        harness.data.answer("find_contact", DataResult.Ok(listOf(DataLine("contact 1", listOf(DataField("name", "Mama"))))))
        harness.data.answer("navigate", DataResult.Ok(emptyList(), note = "Waze shows the route; tap Go to start"))

        harness.run("Call mom and then navigate home")

        val context = harness.model.requests.first().context!!
        assertTrue(context.contains("- home = Main Street 1"))
        assertTrue(context.contains("- mom = contact Mama"))
        assertFalse(context.contains("gate code"))
        assertEquals(listOf("Mama", "Main Street 1"), harness.data.names)
        assertEquals(listOf("Mama"), harness.memory.found)

        // Only aliases resolve names and count as known names; a plain fact never does.
        val memory = TaskMemory(harness.memory, FakeApps(), FakeClock(), Redactor(Vault()))
        memory.load()
        assertNull(memory.alias("gate code"))
        assertEquals(setOf("home", "mom"), memory.aliasKeys)
    }
}
