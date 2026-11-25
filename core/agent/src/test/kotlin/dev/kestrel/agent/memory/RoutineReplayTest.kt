package dev.kestrel.agent.memory

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Harness
import dev.kestrel.agent.Screens
import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.agent.port.CheckedLaunch
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.port.TaskLog
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.agent.tools.Tools
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.Part
import dev.kestrel.screen.model.ScreenSignature
import dev.kestrel.screen.model.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineReplayTest {
    private val chats = Screens.chats("Ana Petrović", "John Smith")
    private val anaChat = Screens.chat("Ana Petrović", "Hi")
    private val compose = anaChat.elements.first { it.editable }
    private val send = anaChat.elements.first { it.label == "Send" }

    // Recorded with Ana: open her chat, type the text, send it.
    private val routine = Routine(
        name = "Fixtures late",
        phrasings = listOf("send {contact} a fixtures message that {text}", "tell {contact} in fixtures that {text}", "fixtures {contact} that {text}"),
        slots = listOf(Slot("contact", SlotType.CONTACT), Slot("text", SlotType.TEXT)),
        steps = listOf(
            RoutineStep(Tools.TAP, key = chats.elements.first { it.label == "Ana Petrović" }.key, label = "Ana Petrović",
                className = "android.view.ViewGroup", category = "reading", before = ScreenSignature.of(chats), after = ScreenSignature.of(anaChat),
                arguments = buildJsonObject { put("target", 3); put("reason", "open the chat"); put("category", "reading") },
                labelSlot = "contact", titleSlot = "contact"),
            RoutineStep(Tools.TYPE_TEXT, key = compose.key, label = compose.label, className = compose.className, category = "typing_request",
                before = ScreenSignature.of(anaChat), text = "{text}",
                arguments = buildJsonObject { put("target", 5); put("text", "I'm late"); put("reason", "write it"); put("category", "typing_request") }),
            RoutineStep(Tools.TAP, key = send.key, label = "Send", className = send.className, category = "sending",
                before = ScreenSignature.of(anaChat),
                arguments = buildJsonObject { put("target", 6); put("reason", "send it"); put("category", "sending") }),
        ),
    )

    private fun harness(): Harness = Harness(chats, listOf(call("fail", null, null, null, "reason" to "The agent took over."))).apply {
        memory.routines += routine
        screen.onTap("John Smith", Screens.chat("John Smith", "Hello"))
        screen.onTap("Send", Screens.chat("John Smith", "Hello", "I'm on my way"))
        owner.confirms += ConfirmEnd.YES
    }

    @Test
    fun aRoutineRecordedWithAnaReplaysForJohnWithoutTheModel() = runTest {
        val harness = harness()

        val report = harness.run("Send John a fixtures message that I'm on my way", knownNames = setOf("Ana Petrović", "John Smith"))

        assertEquals(EndReason.FINISHED, report.end)
        assertEquals("I ran the routine 'Fixtures late'.", report.summary)
        assertTrue(harness.model.requests.isEmpty())
        assertEquals(listOf("John Smith"), harness.owner.confirmations.single().recipients)
        assertEquals("I'm on my way", harness.screen.actions.filterIsInstance<ScreenAction.SetText>().single().text)
        assertEquals("opened \"John Smith\"", report.ledger.first())
        assertEquals(3, harness.log.entries.count { it.first == TaskLog.Kind.ACTION && it.second.startsWith("routine ") })
    }

    // Design 8.1 of sub-project 7: a replayed routine's record names each data result by the routine's own step and each app
    // that its steps acted in, as a model's steps do.
    @Test
    fun aReplayedRoutineTracesItsDataByItsOwnStepAndTheAppsThatItActedIn() = runTest {
        val read = RoutineStep(Tools.LIST_SMS, arguments = buildJsonObject {})
        val harness = harness().apply {
            memory.routines[0] = routine.copy(steps = listOf(routine.steps[0], read) + routine.steps.drop(1))
            data.answer(Tools.LIST_SMS, DataResult.Ok(listOf("Hello", "Bye").map { DataLine("", listOf(DataField("from", "John"), DataField("body", it))) }, note = "Only the newest 2 messages."))
        }

        val report = harness.run("Send John a fixtures message that I'm on my way", knownNames = setOf("Ana Petrović", "John Smith"))

        assertEquals(EndReason.FINISHED, report.end)
        assertTrue(harness.model.requests.isEmpty())
        // Its step is the routine's own, and it counts the result's two items, not its note.
        assertEquals(listOf(2 to 2), harness.goal.traces.filterIsInstance<TraceEntry.Data>().map { it.step to it.items })
        assertEquals(listOf(TraceEntry.AppUsed("Kestrel fixtures", Screens.MESSENGER)), harness.goal.traces.filterIsInstance<TraceEntry.AppUsed>())
    }

    // Android gives each new window its own ID, so a later session of the app shows the same screens in other windows.
    private fun moved(snapshot: ScreenSnapshot, id: Int) =
        snapshot.copy(windows = snapshot.windows.map { it.copy(id = id) }, elements = snapshot.elements.map { it.copy(windowId = id) })

    @Test
    fun aRoutineReplaysOnTheSameScreensInOtherWindows() = runTest {
        val harness = Harness(moved(chats, 7), listOf(call("fail", null, null, null, "reason" to "The agent took over."))).apply {
            memory.routines += routine
            screen.onTap("John Smith", moved(Screens.chat("John Smith", "Hello"), 8))
            screen.onTap("Send", moved(Screens.chat("John Smith", "Hello", "I'm on my way"), 8))
            owner.confirms += ConfirmEnd.YES
        }

        val report = harness.run("Send John a fixtures message that I'm on my way", knownNames = setOf("Ana Petrović", "John Smith"))

        assertEquals(EndReason.FINISHED, report.end)
        assertTrue(harness.model.requests.isEmpty())
    }

    // A step that does not act on the screen before it (opening an app) replays from any screen; the steps after it still
    // check theirs.
    @Test
    fun aRoutineThatOpensItsAppReplaysFromAnyScreen() = runTest {
        val open = RoutineStep(Tools.OPEN_APP, category = "reading", before = ScreenSignature.of(Screens.app("org.telegram.messenger", "Chats")),
            after = ScreenSignature.of(chats), arguments = buildJsonObject { put("name", "Kestrel fixtures"); put("reason", "open the app") })
        val harness = harness().apply {
            memory.routines[0] = routine.copy(steps = listOf(open) + routine.steps)
            screen.current = Screens.app(Screens.MAIL, "Inbox")
            screen.onOpen(chats)
        }

        val report = harness.run("Send John a fixtures message that I'm on my way", knownNames = setOf("Ana Petrović", "John Smith"))

        assertEquals(EndReason.FINISHED, report.end)
        assertTrue(harness.model.requests.isEmpty())
        assertTrue(harness.screen.launched.first() is CheckedLaunch.Opens.App)
        assertEquals(listOf("John Smith"), harness.owner.confirmations.single().recipients)
    }

    @Test
    fun aContactAliasReplaysByTheContactThatItNames() = runTest {
        val harness = harness().apply { memory.facts += Fact("my brother", "contact John Smith", FactKind.ALIAS) }

        val report = harness.run("Send my brother a fixtures message that I'm on my way", knownNames = setOf("Ana Petrović", "John Smith"))

        assertEquals(EndReason.FINISHED, report.end)
        assertTrue(harness.model.requests.isEmpty())
        assertEquals(listOf("John Smith"), harness.owner.confirmations.single().recipients)
    }

    @Test
    fun aPlaceAliasReplaysByItsSavedAddress() = runTest {
        val search = ScreenBuilder.screen(Screens.MESSENGER, "Kestrel fixtures") { field("Search places", "", 300, viewId = "search") }
        val field = search.elements.first { it.editable }
        val places = Routine(
            name = "Fixtures places",
            phrasings = listOf("find {place} in fixtures search", "fixtures search for {place}", "look up {place} in fixtures"),
            slots = listOf(Slot("place", SlotType.PLACE)),
            steps = listOf(RoutineStep(Tools.TYPE_TEXT, key = field.key, label = field.label, className = field.className, category = "typing_request",
                before = ScreenSignature.of(search), text = "{place}",
                arguments = buildJsonObject { put("target", 1); put("text", "Main Street 1"); put("reason", "search it"); put("category", "typing_request") })),
        )
        val harness = Harness(search, listOf(call("fail", null, null, null, "reason" to "The agent took over."))).apply {
            memory.routines += places
            memory.facts += Fact("the office", "Fixture Street 12", FactKind.ALIAS)
        }

        val report = harness.run("Find the office in fixtures search")

        assertEquals(EndReason.FINISHED, report.end)
        assertEquals("Fixture Street 12", harness.screen.actions.filterIsInstance<ScreenAction.SetText>().single().text)
    }

    @Test
    fun aRoutineThatStartsWithAWaitOrADataToolThatOpensNothingReplaysFromAnyScreen() = runTest {
        val home = Screens.app("com.android.launcher3", "Home")
        val tea = Routine(
            name = "Tea",
            phrasings = listOf("start the tea timer now", "tea timer start now", "time my tea right now"),
            slots = emptyList(),
            steps = listOf(
                RoutineStep(Tools.WAIT, before = ScreenSignature.of(home), after = ScreenSignature.of(home),
                    arguments = buildJsonObject { put("seconds", 1); put("reason", "let the screen settle") }),
                RoutineStep(Tools.SET_TIMER, before = ScreenSignature.of(home), after = ScreenSignature.of(home),
                    arguments = buildJsonObject { put("seconds", 180); put("label", "tea") }),
            ),
        )
        val harness = Harness(chats, listOf(call("fail", null, null, null, "reason" to "The agent took over."))).apply {
            memory.routines += tea
            data.answer("set_timer", DataResult.Ok(emptyList()))
        }

        val report = harness.run("Start the tea timer now")

        assertEquals(EndReason.FINISHED, report.end)
        assertTrue(harness.model.requests.isEmpty())
        assertEquals(listOf("set_timer"), harness.data.calls)
    }

    @Test
    fun aRecordedTargetWithoutAKeyNeverReplaysByItsOldNumber() = runTest {
        val screen = ScreenBuilder.screen(Screens.MESSENGER, "Kestrel fixtures") {
            button("Beta", 300)
            button("Alpha", 500)
        }
        val keyless = Routine(
            name = "Alpha",
            phrasings = listOf("press alpha in fixtures now", "fixtures alpha press", "alpha in fixtures please"),
            slots = emptyList(),
            steps = listOf(RoutineStep(Tools.TAP, key = null, label = "Alpha", className = "android.widget.Button", category = "reading",
                arguments = buildJsonObject { put("target", 1); put("reason", "press it"); put("category", "reading") })),
        )
        val harness = Harness(screen, listOf(call("fail", null, null, null, "reason" to "The agent took over."))).apply { memory.routines += keyless }

        val report = harness.run("Press alpha in fixtures now")

        assertEquals(EndReason.FAILED_BY_MODEL, report.end)
        assertTrue(harness.screen.actions.isEmpty())
        val task = ((harness.model.requests.single().messages.first() as ChatMessage.User).parts.single() as Part.Text).text
        assertTrue(task, task.contains("Routine 'Alpha' failed at step 1 of 1: its element was not on the screen."))
    }

    @Test
    fun aStepThatDoesNotFitHandsTheTaskToTheAgentWithItsNote() = runTest {
        val harness = harness()
        harness.screen.current = Screens.chat("Marko Marković", "Hi")

        val report = harness.run("Send John a fixtures message that I'm on my way", knownNames = setOf("Ana Petrović", "John Smith"))

        assertEquals(EndReason.FAILED_BY_MODEL, report.end)
        val task = (harness.model.requests.single().messages.first() as ChatMessage.User).parts.single()
        assertTrue((task as Part.Text).text.contains("Routine 'Fixtures late' failed at step 1 of 3: the screen before it was not the recorded one."))
        assertTrue(harness.screen.actions.isEmpty())
        assertEquals(JsonPrimitive(3), routine.steps[0].arguments!!["target"])
    }
}
