package dev.kestrel.agent

import dev.kestrel.agent.loop.ScreenSteps
import dev.kestrel.agent.loop.TaskRun
import dev.kestrel.agent.port.ActionOutcome
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.agent.port.Press
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.port.TaskLog
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.HandOverKind
import dev.kestrel.safety.SafetySettings
import dev.kestrel.screen.model.IntRect
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTest {
    private fun finish(summary: String, vararg evidence: String) =
        call("finish", null, null, null, "summary" to summary, "evidence" to evidence.map { mapOf("target" to mapOf("label" to it)) })

    private fun sendSteps() = listOf(
        call("type_text", "Type a message…", null, null, "text" to "I'm late", "reason" to "write it", "category" to "typing_request"),
        call("tap", "Send", "Button", null, "reason" to "send it", "category" to "sending"),
        finish("Sent.", "I'm late"),
    )

    private fun sendHarness(confirm: ConfirmEnd): Harness = Harness(Screens.chat("Ana Petrović", "Hi"), sendSteps()).apply {
        screen.onTap("Send", Screens.chat("Ana Petrović", "Hi", "I'm late"))
        owner.confirms += confirm
    }

    @Test
    fun aTaskRunsItsStepsAndFinishesWithItsEvidence() = runTest {
        val harness = Harness(Screens.chats(), listOf(
            call("tap", "Ana Petrović", null, null, "reason" to "open the chat", "category" to "reading"),
            finish("Ana wrote: See you tomorrow!", "See you tomorrow!"),
        ))
        harness.screen.onTap("Ana Petrović", Screens.chat("Ana Petrović", "See you tomorrow!"))

        val report = harness.run("Read Ana's last message")

        assertEquals(EndReason.FINISHED, report.end)
        assertEquals("Ana wrote: See you tomorrow!", report.summary)
        assertEquals(2, report.steps)
        assertEquals(listOf("opened \"Ana Petrović\""), report.ledger)
        assertEquals(1, harness.reports.size)
        assertEquals(listOf(TaskLog.Kind.START, TaskLog.Kind.PROMPT, TaskLog.Kind.REPLY, TaskLog.Kind.SAFETY, TaskLog.Kind.ACTION),
            harness.log.entries.map { it.first }.take(5))
        assertEquals(TaskLog.Kind.END, harness.log.entries.last().first)
        assertTrue(harness.data.forgotten)
    }

    @Test
    fun aSendIsConfirmedAndPressedOnceAfterTheScreenIsReadAgain() = runTest {
        val harness = sendHarness(ConfirmEnd.YES)

        val report = harness.run("Send Ana a message that I'm late")

        assertEquals(EndReason.FINISHED, report.end)
        assertEquals(1, harness.owner.confirmations.size)
        assertEquals(listOf("Ana Petrović"), harness.owner.confirmations.single().recipients)
        val taps = harness.screen.actions.filterIsInstance<ScreenAction.Tap>()
        assertEquals(1, taps.size)
        assertEquals(Press.ONCE, taps.single().press)
        assertEquals(listOf("Sent to Ana Petrović on Kestrel fixtures."), report.sends)
        assertEquals(1, report.confirmed)
        assertEquals(0, report.unconfirmed)
        assertEquals(0, report.beforeTestMode)
    }

    @Test
    fun anEmailsCollapsedCopiesAreOpenedBeforeTheQuestion() = runTest {
        val harness = Harness(Screens.email(copies = false), listOf(call("tap", "Send", null, null, "reason" to "send it", "category" to "sending")))
        harness.screen.onTap("Show Cc/Bcc", Screens.email(copies = true))
        harness.owner.confirms += ConfirmEnd.NO

        val report = harness.run("Email Ana that I'm late")

        assertEquals(EndReason.DECLINED, report.end)
        val confirmation = harness.owner.confirmations.single()
        assertEquals(listOf("ana@example.com", "boss@example.com"), confirmation.recipients)
        assertTrue(confirmation.question, "as a copy, boss@example.com" in confirmation.question)
        assertEquals(listOf("Show Cc/Bcc"), harness.screen.actions.map { (it as ScreenAction.Tap).expect.label })
    }

    @Test
    fun onlyACcExpanderOfAnEmailAppIsTappedAndOnlyAfterTheSafetyCheck() = runTest {
        val hostile = ScreenBuilder.screen(Screens.MAIL, "Gmail") {
            icon("Send", 100, viewId = "send", left = 900, right = 1040)
            field("To", "ana@example.com", 300, viewId = "to")
            button("CC: Buy the premium plan", 420, viewId = "upsell")
            field("Subject", "Late", 700, viewId = "subject")
            field("Compose email", "I'm late", 850, viewId = "body")
        }
        val harness = Harness(hostile, listOf(call("tap", "Send", null, null, "reason" to "send it", "category" to "sending")))
        harness.owner.confirms += ConfirmEnd.NO

        assertEquals(EndReason.DECLINED, harness.run("Email Ana that I'm late").end)
        assertTrue(harness.screen.actions.isEmpty())

        val gmail = Harness(Screens.email(copies = false), listOf(call("tap", "Send", null, null, "reason" to "send it", "category" to "sending")))
        gmail.screen.onTap("Show Cc/Bcc", Screens.email(copies = true))
        gmail.owner.confirms += ConfirmEnd.NO
        gmail.run("Email Ana that I'm late")
        assertTrue(gmail.log.texts(TaskLog.Kind.SAFETY).any { it.startsWith("tap: category=reading") })
        assertTrue(gmail.log.texts(TaskLog.Kind.ACTION).any { it.startsWith("tap: opened Cc and Bcc") })
    }

    @Test
    fun aTypedVaultValueIsNamedByItsKindInTheSendQuestion() = runTest {
        val harness = Harness(Screens.chat("Ana Petrović", "Your verification code: 483920"), listOf(
            call("type_text", "Type a message…", null, null, "text" to "[OTP_1]", "reason" to "send her the code", "category" to "typing_screen_text"),
            call("tap", "Send", "Button", null, "reason" to "send it", "category" to "sending"),
        ))
        harness.owner.confirms += listOf(ConfirmEnd.YES, ConfirmEnd.NO)

        assertEquals(EndReason.DECLINED, harness.run("Send Ana the code").end)

        val send = harness.owner.confirmations[1]
        assertEquals("483920", harness.screen.actions.filterIsInstance<ScreenAction.SetText>().single().text)
        assertFalse(send.question, send.question.contains("483920"))
        assertFalse(send.spoken, send.spoken.contains("483920"))
        assertTrue(send.question, send.question.contains("one-time code"))
    }

    @Test
    fun aSentVaultValuePassesTheCompletionCheckByItsKind() = runTest {
        val harness = Harness(Screens.chat("Ana Petrović", "Your verification code: 483920"), listOf(
            call("type_text", "Type a message…", null, null, "text" to "[OTP_1]", "reason" to "send her the code", "category" to "typing_screen_text"),
            call("tap", "Send", "Button", null, "reason" to "send it", "category" to "sending"),
            finish("I sent Ana the code.", "[OTP_1]"),
        ))
        harness.screen.onTap("Send", Screens.chat("Ana Petrović", "Your verification code: 483920", "483 920"))
        harness.owner.confirms += listOf(ConfirmEnd.YES, ConfirmEnd.YES)

        val report = harness.run("Send Ana the code")

        assertEquals(EndReason.FINISHED, report.end)
        assertEquals(3, harness.model.requests.size)
        assertFalse(harness.log.entries.any { it.second.contains("483920") || it.second.contains("483 920") })
    }

    // A list without text, whose "Delete" only OCR reads (D20), at the rows' places after [shift] pixels.
    private fun notes(shift: Int) = ScreenBuilder.screen("com.example.notes", "Notes") {
        text("Inbox", 100, viewId = "toolbar_title")
        val list = list(null, 300, 2200, viewId = "notes")
        if (shift > 0) button(null, 320, viewId = "note_new", parent = list, className = "android.view.ViewGroup")
        for (row in 0..3) button(null, 320 + shift + row * 140, viewId = "note_$row", parent = list, className = "android.view.ViewGroup")
    }

    private fun deleteByOcr(moved: Boolean): Harness = Harness(notes(0), listOf(
        call("look_closer", null, null, null, "reason" to "the rows have no text"),
        call("tap", "Delete", "OCR", null, "reason" to "delete the note", "category" to "deleting"),
        call("fail", null, null, null, "reason" to "Stop."),
    )).apply {
        eyes.lines = listOf(OcrLine("Delete", IntRect(800, 480, 1000, 540), 0.95))
        owner.confirms += ConfirmEnd.YES
        if (moved) owner.onConfirm = { screen.current = notes(140) }
    }

    @Test
    fun aTapAtAPointIsPressedAfterTheYesOnlyWhenThePointStillLandsOnTheConfirmedElement() = runTest {
        val moved = deleteByOcr(moved = true)

        moved.run("Delete the second note")

        assertEquals(1, moved.owner.confirmations.size)
        assertTrue(moved.screen.actions.none { it is ScreenAction.TapAt })
        assertTrue(moved.lastRequestText().contains(ScreenSteps.CHANGED_AFTER))

        val still = deleteByOcr(moved = false)
        still.run("Delete the second note")
        assertEquals(listOf(ScreenAction.TapAt(900, 510)), still.screen.actions.filterIsInstance<ScreenAction.TapAt>())
    }

    @Test
    fun aPressThatSendsAndDeletesIsPressedOnceAfterItsYes() = runTest {
        val chat = ScreenBuilder.screen(Screens.MESSENGER, "Kestrel fixtures") {
            text("Ana Petrović", 100, viewId = "chat_title")
            field("Type a message…", "I'm late", 2100, viewId = "compose")
            button("Send and delete", 2250, viewId = "send_once", left = 700)
        }
        val harness = Harness(chat, listOf(
            call("tap", "Send and delete", null, null, "reason" to "send it", "category" to "sending"),
            call("fail", null, null, null, "reason" to "Stop."),
        ))
        harness.owner.confirms += ConfirmEnd.YES

        harness.run("Send Ana a message that I'm late")

        assertEquals(listOf("Ana Petrović"), harness.owner.confirmations.single().recipients)
        assertEquals(1, harness.screen.actions.filterIsInstance<ScreenAction.Tap>().size)
    }

    @Test
    fun aForwardedEmailIsAskedAsAnEmailWithTheForwardedMessage() = runTest {
        val message = ScreenBuilder.screen(Screens.MAIL, "Gmail") {
            text("Plan", 300)
            text("See you at five.", 500)
            button("Forward", 2200, viewId = "forward")
        }
        val compose = ScreenBuilder.screen(Screens.MAIL, "Gmail") {
            icon("Send", 100, viewId = "send", left = 900, right = 1040)
            field("To", "", 300, viewId = "to")
            field("Subject", "Fwd: Plan", 700, viewId = "subject")
            field("Compose email", "", 850, viewId = "body")
            text("---------- Forwarded message ---------", 1100)
        }
        val harness = Harness(message, listOf(
            call("tap", "Forward", null, null, "reason" to "forward it", "category" to "sending"),
            call("type_text", "To", null, null, "text" to "marko@example.com", "reason" to "address it", "category" to "typing_request"),
            call("tap", "Send", null, null, "reason" to "send it", "category" to "sending"),
        ))
        harness.screen.onTap("Forward", compose)
        harness.owner.confirms += listOf(ConfirmEnd.YES, ConfirmEnd.NO)

        assertEquals(EndReason.DECLINED, harness.run("Forward this e-mail to marko@example.com").end)

        val send = harness.owner.confirmations[1]
        assertTrue(send.question, send.question.startsWith("Send an e-mail to marko@example.com, subject 'Fwd: Plan' with the forwarded message"))
        assertEquals(listOf("marko@example.com"), send.recipients)
    }

    @Test
    fun aDeclinedConfirmationEndsTheTaskWithItsLedgerAndWithoutTheModelsWords() = runTest {
        val harness = sendHarness(ConfirmEnd.NO)

        val report = harness.run("Send Ana a message that I'm late")

        assertEquals(EndReason.DECLINED, report.end)
        assertNull(report.summary)
        assertTrue(report.ledger.single().startsWith("typed into"))
        assertTrue(harness.screen.actions.none { it is ScreenAction.Tap })
    }

    @Test
    fun whenTheScreenChangesAfterTheYesNothingIsPressed() = runTest {
        val harness = Harness(Screens.chat("Ana Petrović", "Hi"), sendSteps().take(2) + call("fail", null, null, null, "reason" to "The chat changed."))
        harness.owner.confirms += ConfirmEnd.YES
        harness.owner.onConfirm = { harness.screen.current = Screens.chat("Marko Marković", "Hi", typed = "I'm late") }

        val report = harness.run("Send Ana a message that I'm late")

        assertEquals(EndReason.FAILED_BY_MODEL, report.end)
        assertTrue(harness.screen.actions.none { it is ScreenAction.Tap })
        assertTrue(harness.lastRequestText().contains(ScreenSteps.CHANGED_AFTER))
        assertEquals(0, report.sends.size)
    }

    @Test
    fun aCallIsHandedOverAndEndsAsDoneWhenTheOwnerPlacesIt() = runTest {
        val harness = Harness(Screens.chat("Ana Petrović", "Hi", call = true), listOf(
            call("tap", "Call", null, null, "reason" to "call Ana", "category" to "reading"),
        ))
        harness.owner.handOverEnds += HandOverEnd.DONE_BY_ITSELF

        val report = harness.run("Call Ana")

        assertEquals(EndReason.FINISHED, report.end)
        assertEquals("You placed the call to Ana Petrović.", report.summary)
        assertEquals(HandOverKind.CALL, harness.owner.handOvers.single().kind)
        assertTrue(harness.screen.actions.isEmpty())
    }

    @Test
    fun aHandOverThatTheOwnerFinishesIsHisStepAndOneThatExpiresEndsTheTask() = runTest {
        val continued = Harness(Screens.chat("Ana Petrović", "Hi", call = true), listOf(
            call("tap", "Call", null, null, "reason" to "call Ana", "category" to "calls"),
            call("fail", null, null, null, "reason" to "Done."),
        ))
        continued.owner.handOverEnds += HandOverEnd.CONTINUED
        continued.run("Call Ana")
        assertTrue(continued.lastRequestText().contains(TaskRun.OWNER_DID_IT_RESULT))

        val expired = Harness(Screens.chat("Ana Petrović", "Hi", call = true), listOf(call("tap", "Call", null, null, "reason" to "call", "category" to "calls")))
        expired.owner.handOverEnds += HandOverEnd.EXPIRED
        assertEquals(EndReason.HAND_OVER_EXPIRED, expired.run("Call Ana").end)
    }

    @Test
    fun aCancelledTaskReportsOnceWhatItHadDoneAndAPauseThatEndedSaysSo() = runTest {
        val harness = sendHarness(ConfirmEnd.YES)
        harness.owner.onConfirm = { throw TaskCancellation(EndReason.CANCELLED) }

        assertTrue(runCatching { harness.run("Send Ana a message that I'm late") }.exceptionOrNull() is TaskCancellation)
        val report = harness.reports.single()
        assertEquals(EndReason.CANCELLED, report.end)
        assertTrue(report.ledger.single().startsWith("typed into"))

        val paused = sendHarness(ConfirmEnd.YES)
        paused.owner.onConfirm = { throw TaskCancellation(EndReason.PAUSED_TOO_LONG) }
        assertTrue(runCatching { paused.run("Send Ana a message that I'm late") }.exceptionOrNull() is TaskCancellation)
        assertEquals(EndReason.PAUSED_TOO_LONG, paused.reports.single().end)
    }

    @Test
    fun aResumedTaskObservesTheScreenAgainBeforeItActs() = runTest {
        val paused = Harness(Screens.chats(), listOf(
            call("tap", "Ana Petrović", null, null, "reason" to "open the chat", "category" to "reading"),
            call("fail", null, null, null, "reason" to "Stop."),
        ))
        paused.owner.gates += listOf(Gate.GO, Gate.RESUMED)

        paused.run("Open Ana's chat")

        assertTrue(paused.screen.actions.isEmpty())
        assertTrue(paused.lastRequestText().contains(ScreenSteps.PAUSED))

        // A resume at the gate of `say`: the owner changed the screen during the pause, and the next request shows it.
        val said = Harness(Screens.chats(), listOf(
            call("say", null, null, null, "text" to "One moment."),
            call("tap", "Ana Petrović", null, null, "reason" to "open the chat", "category" to "reading"),
            call("fail", null, null, null, "reason" to "Stop."),
        ))
        said.owner.gates += listOf(Gate.GO, Gate.RESUMED)
        said.owner.onSay = { said.screen.current = Screens.chats("Ana Petrović", "Jovan Jovanović") }

        said.run("Open Ana's chat")

        val second = said.requestText(1)
        assertTrue(second.contains(TaskRun.RESUMED))
        assertTrue(second.contains("Jovan Jovanović"))
        assertEquals(1, said.screen.actions.filterIsInstance<ScreenAction.Tap>().size)
    }

    @Test
    fun aResumeAtTheGateOfAQuestionOrAMemoryToolIsObservedBeforeTheNextRequest() = runTest {
        val asked = Harness(Screens.chats(), listOf(
            call("ask_user", null, null, null, "question" to "Which Ana?"),
            call("fail", null, null, null, "reason" to "Stop."),
        ))
        asked.owner.gates += listOf(Gate.GO, Gate.RESUMED)
        asked.owner.answers += Answer.Text("Petrović")
        asked.run("Open Ana's chat")
        assertTrue(asked.lastRequestText().contains(TaskRun.RESUMED))

        val remembered = Harness(Screens.chats(), listOf(
            call("remember", null, null, null, "key" to "gate code", "value" to "blue"),
            call("fail", null, null, null, "reason" to "Stop."),
        ))
        remembered.owner.gates += listOf(Gate.GO, Gate.RESUMED)
        remembered.owner.answers += Answer.Yes
        remembered.run("Remember that the gate code is blue")
        assertTrue(remembered.lastRequestText().contains(TaskRun.RESUMED))
    }

    @Test
    fun aResumeAtTheStartOfATurnIsObservedBeforeTheModelIsAsked() = runTest {
        val harness = Harness(Screens.chats(), listOf(call("fail", null, null, null, "reason" to "Stop.")))
        harness.owner.gates += Gate.RESUMED

        harness.run("Open Ana's chat")

        assertEquals(1, harness.model.requests.size)
        assertTrue(harness.requestText(0).contains(TaskRun.RESUMED))
    }

    @Test
    fun anUnexpectedFailureReportsOnceAsKestrelsErrorAndIsRethrown() = runTest {
        val harness = Harness(Screens.chats(), listOf(
            call("tap", "Ana Petrović", null, null, "reason" to "open the chat", "category" to "reading"),
        ))
        harness.screen.outcome = { throw IllegalStateException("broken screen") }

        val thrown = runCatching { harness.run("Open Ana's chat") }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(EndReason.KESTREL_ERROR, harness.reports.single().end)
        assertEquals(TaskLog.Kind.END, harness.log.entries.last().first)
        assertTrue(harness.data.forgotten)
    }

    @Test
    fun aSendWhosePressMayNotHaveWorkedIsNotReportedAsSent() = runTest {
        val harness = Harness(Screens.chat("Ana Petrović", "Hi"), sendSteps().take(2) + call("fail", null, null, null, "reason" to "Stop."))
        harness.owner.confirms += ConfirmEnd.YES
        harness.screen.outcome = { action ->
            ActionOutcome(done = true, uncertain = action is ScreenAction.Tap, method = "fake")
        }

        val report = harness.run("Send Ana a message that I'm late")

        assertEquals(1, harness.screen.actions.filterIsInstance<ScreenAction.Tap>().size)
        assertTrue(harness.lastRequestText().contains(ScreenSteps.UNCERTAIN))
        assertTrue(report.sends.isEmpty())
        assertTrue(report.recipients.isEmpty())
        assertTrue(report.ledger.none { it.startsWith("sent") })
        assertEquals("tried to send the message to Ana Petrović, but the tap may not have worked", report.ledger.last())
    }

    @Test
    fun aSendWhosePressMayNotHaveWorkedStillNeedsItsEvidence() = runTest {
        val harness = Harness(Screens.chat("Ana Petrović", "Hi"), sendSteps().take(2) + call("fail", null, null, null, "reason" to "Stop."))
        harness.owner.confirms += ConfirmEnd.YES
        harness.screen.outcome = { action ->
            ActionOutcome(done = true, uncertain = action is ScreenAction.Tap, method = "fake")
        }
        val ports = with(harness) { AgentPorts(screen, eyes, data, owner, model, apps, memory, log, clock) }
        val run = TaskRun(ports, harness.settings, TaskRequest("task-1-0a1b2c3d", "Send Ana a message that I'm late", emptySet()))

        val report = try {
            run.run()
        } finally {
            run.close()
        }

        assertTrue(report.sends.isEmpty())
        // The completion check (D21) must still find evidence for the doubted send.
        assertEquals(listOf(listOf("Ana Petrović")), run.confirmedSends.map { it.recipients })
        assertFalse(run.confirmedSends.single().share)
    }

    @Test
    fun testModeBlocksASendToAnotherPersonAndEndsTheTaskWithoutAQuestion() = runTest {
        val harness = Harness(Screens.chat("Ana Petrović", "Hi", typed = "I'm late"), listOf(
            call("tap", "Send", "Button", null, "reason" to "send it", "category" to "sending"),
        ), settings = AgentSettings(safety = SafetySettings(testMode = true)))

        val report = harness.run("Send Ana a message that I'm late")

        assertEquals(EndReason.TEST_MODE, report.end)
        assertTrue(harness.owner.confirmations.isEmpty())
        assertTrue(harness.screen.actions.isEmpty())
        assertTrue(harness.model.requests.single().context.orEmpty().contains(TaskRun.TEST_MODE_NOTE))
        assertTrue(harness.log.texts(TaskLog.Kind.SAFETY).single().contains("beforeTestMode=Confirm decision=Block"))
    }

    @Test
    fun anAgentFreeAppOrKestrelInFrontEndsTheTaskBeforeAnyModelCall() = runTest {
        val kestrel = Harness(Screens.app("dev.kestrel", "Safety"), emptyList())
        assertEquals(EndReason.AGENT_FREE, kestrel.run("Turn on hotspot").end)
        assertEquals("Kestrel", kestrel.reports.single().detail)

        val bank = Harness(Screens.app("com.example.bank", "Balance"), emptyList(),
            settings = AgentSettings(safety = SafetySettings(testMode = false), agentFree = AgentFreeApps(added = setOf("com.example.bank"))))
        assertEquals(EndReason.AGENT_FREE, bank.run("Check my balance").end)
        assertTrue(bank.model.requests.isEmpty())
    }

    @Test
    fun theReportSpeaksAPlaceholderAsItsKindAndNoCodeReachesTheModel() = runTest {
        val screen = ScreenBuilder.screen("com.google.android.apps.messaging") {
            text("Your verification code", 400)
            text("483920", 530)
        }
        val harness = Harness(screen, listOf(call("finish", null, null, null, "summary" to "The code is [OTP_1].",
            "evidence" to listOf(mapOf("target" to mapOf("label" to "[OTP_1]"))))))

        val report = harness.run("Read me the code")

        assertEquals(EndReason.FINISHED, report.end)
        assertEquals("The code is a one-time code.", report.summary)
        val sent = harness.lastRequestText()
        assertTrue(sent.contains("20 November 2026"))
        assertFalse(sent.contains("483920"))
        assertFalse(harness.log.entries.any { it.second.contains("483920") })
    }

    @Test
    fun rememberAndForgetAskTheOwnerFirst() = runTest {
        val harness = Harness(Screens.chats(), listOf(
            call("remember", null, null, null, "key" to "gate code", "value" to "blue"),
            call("forget", null, null, null, "key" to "parking"),
            call("forget", null, null, null, "key" to "gate code"),
            call("fail", null, null, null, "reason" to "Stop."),
        ))
        harness.owner.answers += listOf(Answer.Yes, Answer.Yes)

        harness.run("Remember that the gate code is blue")

        assertEquals(listOf("Remember that gate code is blue?", "Forget gate code?"), harness.owner.asked.map { it.first })
        assertTrue(harness.memory.facts.isEmpty())
        val results = harness.model.requests.last().messages.filterIsInstance<ChatMessage.ToolResult>().map { it.text }
        assertTrue(results.any { it.startsWith("Step 1: remember → stored") || it.startsWith("Result: stored.") })
        assertTrue(results.any { it.contains("no such fact") })
        assertTrue(results.any { it.contains("deleted") })
    }
}
