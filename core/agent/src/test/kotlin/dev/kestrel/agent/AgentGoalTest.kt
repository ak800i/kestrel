package dev.kestrel.agent

import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.agent.goal.Steer
import dev.kestrel.agent.goal.SteerChannel
import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.agent.loop.Redacted
import dev.kestrel.agent.loop.TaskText
import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.agent.port.ActionOutcome
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.agent.tools.Tools
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.safety.Category
import dev.kestrel.safety.HandOverKind
import dev.kestrel.safety.Mode
import dev.kestrel.safety.SafetySettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    // D16 of sub-project 7: only sending, calls and deleting carry, so a carried typing_screen_text is no allowance.
    @Test
    fun aCarriedCategoryThatDoesNotCarryIsIgnored() = runTest {
        val harness = Harness(Screens.chat("Mila Ilić", "Hi"), listOf(
            call("type_text", "Type a message…", null, null, "text" to "See you at five", "reason" to "copy it", "category" to "typing_screen_text"),
            fail(),
        ), settings = AgentSettings(safety = SafetySettings(mode = Mode.RELAXED)))

        harness.run(TaskRequest(Harness.ID, "And to Mila too", carried = setOf(Category.TYPING_SCREEN_TEXT)))

        val asked = harness.owner.confirmations.single().question
        assertTrue(asked, asked.startsWith("You didn't ask me to copy text from the screen."))
    }

    private fun lastMessage(harness: Harness, request: Int): String =
        (harness.model.requests[request].messages.last() as ChatMessage.User).parts.filterIsInstance<Part.Text>().single().text

    @Test
    fun steeringReachesTheModelAtTheNextTurnAfterTheStepThatRan() = runTest {
        val harness = Harness(Screens.chats(), listOf(call("say", null, null, null, "text" to "Opening it."), fail()))
        harness.owner.onSay = { harness.goal.steering += Steer("Also send it to Mila", SteerChannel.TYPED) }

        harness.run("Send Ana a photo")

        assertEquals(listOf("Opening it."), harness.owner.said)
        assertFalse(harness.requestText(0).contains(TaskText.OWNER_ADDED))
        assertEquals("The owner added (typed) at step 1: \"Also send it to Mila\"", lastMessage(harness, 1))
    }

    @Test
    fun steeringIsTheOwnersOwnTextAndExtendsTheAllowance() = runTest {
        val harness = Harness(Screens.chat("Ana Petrović", "Hi"), listOf(
            call("type_text", "Type a message…", null, null, "text" to "I'm late", "reason" to "write it", "category" to "typing_request"),
            call("tap", "Send", "Button", null, "reason" to "send it", "category" to "sending"),
        ))
        harness.goal.steering += Steer("Tell her that I'm late", SteerChannel.SPOKEN)

        assertEquals(EndReason.DECLINED, harness.run("Open Ana's chat").end)

        assertEquals(
            "The owner added (spoken) before the first step: \"Tell her that I'm late\"\nThis task may send messages after the owner confirms.",
            lastMessage(harness, 0),
        )
        assertEquals("I'm late", harness.screen.actions.filterIsInstance<ScreenAction.SetText>().single().text)
        val asked = harness.owner.confirmations.single().question
        assertFalse(asked, asked.contains("You didn't ask me to send anything."))
    }

    // The owner's steering and his answers are his own words, which are never cut, as his command's are not.
    @Test
    fun theOwnersLongWordsReachTheModelWhole() = runTest {
        val stations = listOf("Novi Sad", "Inđija", "Stara Pazova", "Batajnica", "Zemun", "Novi Beograd")
        val words = "Also tell her that " + stations.joinToString(", ") { "the train waited outside $it for a free track" }
        val harness = Harness(Screens.chats(), listOf(call("ask_user", null, null, null, "question" to "Anything else?"), fail()))
        harness.goal.steering += Steer(words, SteerChannel.TYPED)
        harness.owner.answers += Answer.Text(words)

        harness.run("Tell Ana that I'm late")

        assertTrue(words.length > ScreenText.VALUE_LIMIT)
        assertEquals("The owner added (typed) before the first step: \"$words\"", lastMessage(harness, 0))
        assertTrue(harness.requestText(1).contains("The owner said: \"$words\""))
    }

    private fun item(text: String, state: String, vararg more: Pair<String, Any?>): Map<String, Any?> = mapOf("text" to text, "state" to state) + more

    private fun checklist(vararg items: Map<String, Any?>) = call("update_checklist", null, null, null, "items" to items.toList())

    @Test
    fun aChecklistThatKeepsTheRulesIsSavedReportedAndNamedInItsResult() = runTest {
        val harness = Harness(Screens.chats(), listOf(
            checklist(item("Read the SMS", "doing"), item("Tell the owner", "todo")),
            call("list_sms"),
            checklist(item("Read the SMS", "done", "id" to "c1", "step" to 2), item("Tell the owner", "todo", "id" to "c2")),
            fail(),
        ))
        harness.data.answer(Tools.LIST_SMS, sms("Hi"))

        val report = harness.run("Read my newest SMS")

        val planned = Checklist(listOf(ChecklistItem("c1", "Read the SMS", ItemState.DOING), ChecklistItem("c2", "Tell the owner", ItemState.TODO)))
        val saved = Checklist(listOf(ChecklistItem("c1", "Read the SMS", ItemState.DONE, step = 2), ChecklistItem("c2", "Tell the owner", ItemState.TODO)))
        assertEquals(listOf(planned, saved), harness.goal.checklists)
        assertTrue(harness.requestText(3).contains("Result: checklist saved, 1 of 2 done (c1, c2)."))
        assertEquals(saved, report.checklist)
    }

    // An item comes into the checklist at the step of its update, so no earlier step can have done it.
    @Test
    fun aStepBeforeTheUpdateThatAddedAnItemCannotMarkItDone() = runTest {
        val harness = Harness(Screens.chats(), listOf(
            call("list_sms"),
            checklist(item("Read the SMS", "done", "step" to 1)),
            fail(),
        ))
        harness.data.answer(Tools.LIST_SMS, sms("Hi"))

        harness.run("Read my newest SMS")

        assertTrue(harness.requestText(2).contains("Result: update_checklist failed: Step 1 cannot mark c1 done: c1 was added at step 2, after it."))
        assertEquals(emptyList<Checklist>(), harness.goal.checklists)
    }

    // Most of a goal's items are done on the screen: a tap that worked marks one done, a tap that failed cannot.
    @Test
    fun aScreenStepThatWorkedMarksAnItemDoneAndOneThatFailedCannot() = runTest {
        val tap = call("tap", "Ana Petrović", null, null, "reason" to "open the chat", "category" to "reading")
        val harness = Harness(Screens.chats(), listOf(
            checklist(item("Open Ana's chat", "doing"), item("Read her message", "todo")),
            tap,
            tap,
            checklist(item("Open Ana's chat", "done", "id" to "c1", "step" to 2), item("Read her message", "todo", "id" to "c2")),
            checklist(item("Open Ana's chat", "done", "id" to "c1", "step" to 3), item("Read her message", "todo", "id" to "c2")),
            fail(),
        ))
        var taps = 0
        harness.screen.outcome = { action -> ActionOutcome(done = action !is ScreenAction.Tap || ++taps > 1, method = "fake") }
        harness.screen.onTap("Ana Petrović", Screens.chat("Ana Petrović", "Hi"))

        val report = harness.run("Read Ana's newest message")

        assertTrue(
            harness.requestText(4).contains(
                "Result: update_checklist failed: Step 2 cannot mark c1 done: name a step that acted on the screen without failing, a data step with a result or a step that the owner did.",
            ),
        )
        assertTrue(harness.requestText(5).contains("Result: checklist saved, 1 of 2 done (c1, c2)."))
        val saved = Checklist(listOf(ChecklistItem("c1", "Open Ana's chat", ItemState.DONE, step = 3), ChecklistItem("c2", "Read her message", ItemState.TODO)))
        assertEquals(saved, report.checklist)
    }

    // A step that only talked did nothing that an item could be done by (design 7.2 of sub-project 7).
    @Test
    fun aChecklistThatBreaksARuleOrComesRightAfterAnotherIsAnInvalidCall() = runTest {
        val harness = Harness(Screens.chats(), listOf(
            call("say", null, null, null, "text" to "Opening it."),
            checklist(item("Open the chat", "done", "step" to 1)),
            checklist(item("Open the chat", "todo")),
            checklist(item("Open the chat", "doing", "id" to "c1")),
            fail(),
        ))

        harness.run("Open Ana's chat")

        assertEquals(List(4) { ModelRole.LIGHTWEIGHT } + ModelRole.VERSATILE, harness.model.roles)
        assertTrue(
            harness.requestText(2).contains(
                "Result: update_checklist failed: Step 1 cannot mark c1 done: name a step that acted on the screen without failing, a data step with a result or a step that the owner did.",
            ),
        )
        assertTrue(
            harness.requestText(4).contains(
                "Result: update_checklist failed: it came right after the last update; act on the screen or with another tool before you update the checklist again.",
            ),
        )
        assertEquals(1, harness.goal.checklists.size)
    }

    @Test
    fun aChecklistUpdateNeitherMakesProgressNorCountsAsAStepWithout() = runTest {
        val say = call("say", null, null, null, "text" to "Working.")
        val harness = Harness(Screens.chats(), listOf(say, say, checklist(item("Say hello", "doing")), say, fail()))

        harness.run("Say hello")

        // Three steps without progress switch to the versatile model; the update between them is none of the three.
        assertEquals(List(4) { ModelRole.LIGHTWEIGHT } + ModelRole.VERSATILE, harness.model.roles)
    }

    // The open items refuse the finish before its pause gate, which would otherwise end the run as unverified.
    @Test
    fun finishFailsWhileTheChecklistHasOpenItemsBeforeAPauseCouldEndTheRun() = runTest {
        val harness = Harness(Screens.chats(), listOf(
            checklist(item("Open the chat", "todo"), item("Read it", "todo")),
            call("finish", null, null, null, "summary" to "Done.", "evidence" to listOf(mapOf("target" to mapOf("label" to "Ana Petrović")))),
            fail(),
        ))
        // The turns' gates of the first two steps, then a pause that ends while the model answers the finish.
        harness.owner.gates += listOf(Gate.GO, Gate.GO, Gate.RESUMED)

        assertEquals(EndReason.FAILED_BY_MODEL, harness.run("Read Ana's chat").end)

        assertTrue(harness.requestText(2).contains("Result: finish failed: the checklist has open items c1 and c2; mark them done, skipped or failed."))
    }

    // D13 of sub-project 7: a touch interrupts a goal's run; the report carries the touch as its cause and what the run did.
    @Test
    fun aTouchsCancellationCarriesItsCauseIntoTheReport() = runTest {
        val harness = Harness(Screens.chats(), listOf(
            call("tap", "Ana Petrović", null, null, "reason" to "open the chat", "category" to "reading"),
            call("say", null, null, null, "text" to "Reading."),
        ))
        harness.screen.onTap("Ana Petrović", Screens.chat("Ana Petrović", "Hi"))
        harness.owner.onSay = { throw TaskCancellation(EndReason.INTERRUPTED, Interruption.TOUCH) }

        val failure = runCatching { harness.run("Read Ana's messages") }.exceptionOrNull()

        assertTrue(failure is TaskCancellation)
        val report = harness.reports.single()
        assertEquals(EndReason.INTERRUPTED, report.end)
        assertEquals(Interruption.TOUCH, report.interruption)
        assertEquals(listOf("opened \"Ana Petrović\""), report.ledger)
        // An interruption always has its cause, and only an interruption has one.
        assertThrows(IllegalArgumentException::class.java) { TaskCancellation(EndReason.INTERRUPTED) }
        assertThrows(IllegalArgumentException::class.java) { TaskCancellation(EndReason.CANCELLED, Interruption.TOUCH) }
    }
}
