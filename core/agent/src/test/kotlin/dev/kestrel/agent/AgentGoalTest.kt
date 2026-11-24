package dev.kestrel.agent

import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.agent.loop.Redacted
import dev.kestrel.agent.loop.TaskText
import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.agent.tools.Tools
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.Part
import dev.kestrel.safety.Category
import dev.kestrel.safety.HandOverKind
import dev.kestrel.safety.SafetySettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a run gives the goal around it and takes from it (plan 7A; design 5.4, 7 and 8 of sub-project 7): the record's
 * traces, the conversation's context and allowance, steering, the checklist, the interruption, the search over past
 * conversations and a resumed goal's task message.
 */
class AgentGoalTest {
    private fun fail() = call("fail", null, null, null, "reason" to "Stop.")

    private fun sms(vararg bodies: String) = DataResult.Ok(bodies.map { DataLine("", listOf(DataField("from", "Ana"), DataField("body", it))) })

    @Test
    fun theRunTracesTheRequestTheDialogueTheDataTheAppsAndTheLedger() = runTest {
        val harness = Harness(Screens.chats(), listOf(
            call("say", null, null, null, "text" to "Looking."),
            call("list_sms"),
            call("tap", "Ana Petrović", null, null, "reason" to "open the chat", "category" to "reading"),
            call("ask_user", null, null, null, "question" to "Which one?"),
            fail(),
        ))
        harness.data.answer(Tools.LIST_SMS, sms("Hi"))
        harness.screen.onTap("Ana Petrović", Screens.chat("Ana Petrović", "Hi"))
        harness.owner.answers += Answer.Text("the newest")

        harness.run("Read Ana's messages")

        assertEquals(
            listOf(
                TraceEntry.Requested("Read Ana's messages"),
                TraceEntry.Said("Looking."),
                TraceEntry.Data(Tools.LIST_SMS, 2, "from: \"Ana\" · body: \"Hi\""),
                TraceEntry.AppUsed("Kestrel fixtures", Screens.MESSENGER),
                TraceEntry.Did("opened \"Ana Petrović\""),
                TraceEntry.Asked("Which one?"),
                TraceEntry.Answered("the newest"),
            ),
            harness.goal.traces,
        )
    }

    @Test
    fun aRecordNamesAVaultValueOnlyByItsKind() = runTest {
        val harness = Harness(Screens.chats(), listOf(call("list_sms"), call("say", null, null, null, "text" to "Your code is [OTP_1]."), fail()))
        harness.data.answer(Tools.LIST_SMS, sms("Your verification code: 483920"))

        harness.run("Read my newest SMS")

        val data = harness.goal.traces.filterIsInstance<TraceEntry.Data>().single().text
        assertFalse(data, data.contains("483920") || data.contains("[OTP_1]"))
        assertTrue(data, data.contains("a one-time code"))
        assertEquals(TraceEntry.Said("Your code is a one-time code."), harness.goal.traces.filterIsInstance<TraceEntry.Said>().single())
    }

    // Design 10.5 of sub-project 7: the goal learns each step's number when the step starts, before anything of it runs.
    @Test
    fun theGoalLearnsEachStepAsItStarts() = runTest {
        val harness = Harness(Screens.chats(), listOf(call("say", null, null, null, "text" to "Looking."), fail()))
        var whileSaying: List<Int>? = null
        harness.owner.onSay = { whileSaying = harness.goal.steps.toList() }

        harness.run("Read Ana's messages")

        assertEquals(listOf(1), whileSaying)
        assertEquals(listOf(1, 2), harness.goal.steps)
    }

    private fun firstMessage(harness: Harness): String =
        (harness.model.requests.first().messages.first() as ChatMessage.User).parts.filterIsInstance<Part.Text>().single().text

    @Test
    fun theContextHoldsTheTestModeNoteThenTheConversationThenTheMemory() = runTest {
        val harness = Harness(Screens.chats(), listOf(fail()), settings = AgentSettings(safety = SafetySettings(testMode = true)))
        harness.memory.facts += Fact("gate code", "blue")
        val records = Redacted.kestrel("This conversation so far:\nCommand 1: The owner said: \"Turn on Wi-Fi\"")

        harness.run(TaskRequest(Harness.ID, "What is the gate code?", conversation = records))

        assertEquals("${TaskText.TEST_MODE_NOTE}\n\n${records.text}\n\nFacts the owner saved:\n- gate code = blue", harness.model.requests.single().context)
        assertTrue(firstMessage(harness), firstMessage(harness).startsWith("The owner's new command: What is the gate code?\nToday is "))
    }

    @Test
    fun theOwnersWordsOfEarlierCommandsCountAsHisOwnText() = runTest {
        fun typing() = Harness(Screens.chat("Ana Petrović", "Hi"), listOf(
            call("type_text", "Type a message…", null, null, "text" to "I'm late", "reason" to "write it", "category" to "typing_request"),
            fail(),
        ))
        val earlier = typing()
        val alone = typing()

        earlier.run(TaskRequest(Harness.ID, "And to Mila too", ownerHistory = listOf("Tell Ana that I'm late")))
        alone.run(TaskRequest(Harness.ID, "And to Mila too"))

        assertTrue(earlier.owner.confirmations.isEmpty())
        assertEquals("I'm late", earlier.screen.actions.filterIsInstance<ScreenAction.SetText>().single().text)
        assertEquals(1, alone.owner.confirmations.size)
    }

    @Test
    fun aCarriedSendIsStillConfirmedInStandardModeAndACarriedCallIsHandedOver() = runTest {
        fun sending() = Harness(Screens.chat("Mila Ilić", "Hi", typed = "I'm late"), listOf(call("tap", "Send", "Button", null, "reason" to "send it", "category" to "sending")))
        val carried = sending()
        val alone = sending()
        val calling = Harness(Screens.chat("Mila Ilić", "Hi", call = true), listOf(call("tap", "Call", "Button", null, "reason" to "call her", "category" to "calls")))

        carried.run(TaskRequest(Harness.ID, "And to Mila too", carried = setOf(Category.SENDING)))
        alone.run(TaskRequest(Harness.ID, "And to Mila too"))
        calling.run(TaskRequest(Harness.ID, "And Mila too", carried = setOf(Category.CALLS)))

        val asked = carried.owner.confirmations.single().question
        assertFalse(asked, asked.contains("You didn't ask me to send anything."))
        assertTrue(alone.owner.confirmations.single().question.contains("You didn't ask me to send anything."))
        assertEquals(HandOverKind.CALL, calling.owner.handOvers.single().kind)
        assertTrue(calling.screen.actions.isEmpty())
    }
}
