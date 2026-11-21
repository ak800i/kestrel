package dev.kestrel.agent.memory

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Harness
import dev.kestrel.agent.Screens
import dev.kestrel.agent.port.ConfirmEnd
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
