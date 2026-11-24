package dev.kestrel.agent

import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.agent.tools.Tools
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
}
