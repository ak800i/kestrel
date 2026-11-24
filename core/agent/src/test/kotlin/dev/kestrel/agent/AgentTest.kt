package dev.kestrel.agent

import dev.kestrel.agent.loop.ScreenSteps
import dev.kestrel.agent.loop.TaskRun
import dev.kestrel.agent.loop.TaskText
import dev.kestrel.agent.memory.RouteNote
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
import dev.kestrel.safety.SelfChats
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
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

    // A full list whose rows share one view ID and have no text, as real lists show them: a row that arrives at the top
    // leaves the tree as it was, and only the texts that OCR reads in the rows move.
    private fun sharedRows() = ScreenBuilder.screen("com.example.notes", "Notes") {
        text("Inbox", 100, viewId = "toolbar_title")
        val list = list(null, 300, 880, viewId = "notes")
        for (row in 0..3) button(null, 320 + row * 140, viewId = "note", parent = list, className = "android.view.ViewGroup")
    }

    // OCR's lines of the rows named [titles], each with its own "Delete"; the second row's "Delete" comes first, so that
    // the script's "Delete" is that one, at (900, 510).
    private fun rowLines(vararg titles: String): List<OcrLine> = titles.flatMapIndexed { row, title ->
        val top = 340 + row * 140
        listOf(OcrLine(title, IntRect(80, top, 500, top + 60), 0.95), OcrLine("Delete", IntRect(800, top, 1000, top + 60), 0.95))
    }.sortedBy { if (it.text == "Delete" && it.bounds.top == 480) 0 else 1 }

    private fun deleteInSharedRows(arrives: Boolean): Harness = Harness(sharedRows(), listOf(
        call("look_closer", null, null, null, "reason" to "the rows have no text"),
        call("tap", "Delete", "OCR", null, "reason" to "delete the note", "category" to "deleting"),
        call("fail", null, null, null, "reason" to "Stop."),
    )).apply {
        eyes.lines = rowLines("Groceries", "Plans", "Trip", "Gifts")
        owner.confirms += ConfirmEnd.YES
        if (arrives) owner.onConfirm = { eyes.lines = rowLines("New note", "Groceries", "Plans", "Trip") }
    }

    @Test
    fun aTapAtAPointIsRefusedAfterTheYesWhenARowArrivesAboveInAListWhoseRowsShareOneViewIdAndHaveNoText() = runTest {
        val arrived = deleteInSharedRows(arrives = true)

        arrived.run("Delete the note Plans")

        assertEquals(1, arrived.owner.confirmations.size)
        assertTrue(arrived.screen.actions.none { it is ScreenAction.TapAt })
        assertTrue(arrived.lastRequestText().contains(ScreenSteps.CHANGED_AFTER))

        val still = deleteInSharedRows(arrives = false)
        still.run("Delete the note Plans")
        assertEquals(listOf(ScreenAction.TapAt(900, 510)), still.screen.actions.filterIsInstance<ScreenAction.TapAt>())
    }

    // Rows that share one view ID, each with a clickable "delete" control whose "Delete" only OCR reads, as rows with
    // action buttons show them: the point lands on the control, and the row's title lies outside it.
    private fun rowsWithControls() = ScreenBuilder.screen("com.example.notes", "Notes") {
        text("Inbox", 100, viewId = "toolbar_title")
        val list = list(null, 300, 880, viewId = "notes")
        for (row in 0..3) {
            val top = 320 + row * 140
            val note = button(null, top, viewId = "note", parent = list, className = "android.view.ViewGroup")
            button(null, top, viewId = "delete", parent = note, className = "android.widget.ImageButton", left = 800, right = 1000)
        }
    }

    // The script taps the second row's "Delete" at (900, 510) after look_closer; [question] runs while the owner is asked.
    private fun deleteInRows(lines: List<OcrLine>, screen: ScreenSnapshot = rowsWithControls(), question: Harness.() -> Unit = {}): Harness =
        Harness(screen, listOf(
            call("look_closer", null, null, null, "reason" to "the rows have no text"),
            call("tap", "Delete", "OCR", null, "reason" to "delete the note", "category" to "deleting"),
            call("fail", null, null, null, "reason" to "Stop."),
        )).apply {
            eyes.lines = lines
            owner.confirms += ConfirmEnd.YES
            owner.onConfirm = { question() }
        }

    @Test
    fun aTapAtAPointOnARowsControlIsRefusedAfterTheYesWhenARowArrivesAbove() = runTest {
        val arrived = deleteInRows(rowLines("Groceries", "Plans", "Trip", "Gifts")) {
            eyes.lines = rowLines("New note", "Groceries", "Plans", "Trip")
        }

        arrived.run("Delete the note Plans")

        assertEquals(1, arrived.owner.confirmations.size)
        assertTrue(arrived.screen.actions.none { it is ScreenAction.TapAt })
        assertTrue(arrived.lastRequestText().contains(ScreenSteps.CHANGED_AFTER))

        val still = deleteInRows(rowLines("Groceries", "Plans", "Trip", "Gifts"))
        still.run("Delete the note Plans")
        assertEquals(listOf(ScreenAction.TapAt(900, 510)), still.screen.actions.filterIsInstance<ScreenAction.TapAt>())
        assertEquals(2, still.eyes.captures)
    }

    @Test
    fun aTapAtAPointInRowsThatLookAlikeIsHandedToTheOwner() = runTest {
        val harness = deleteInRows(rowLines("Plans", "Plans", "Plans", "Plans"))

        val report = harness.run("Delete the note Plans")

        assertEquals(EndReason.CANCELLED, report.end)
        assertEquals(listOf(HandOverKind.RECIPIENT), harness.owner.handOvers.map { it.kind })
        assertTrue(harness.screen.actions.none { it is ScreenAction.TapAt })
        assertEquals(1, harness.eyes.captures)
    }

    // A floating "New note" button over the list, at the height of the second row's "Delete": its text stays in place
    // when a row arrives above, whether the tree holds it or only OCR reads it.
    @Test
    fun aFloatingButtonOverTheListDoesNotMakeRowsThatLookAlikeDiffer() = runTest {
        val rows = rowsWithControls()
        val alike = rowLines("Plans", "Plans", "Plans", "Plans")
        val drawn = OcrLine("New note", IntRect(540, 480, 760, 540), 0.95)
        for (label in listOf("New note", null)) {
            val fab = ScreenBuilder.screen("com.example.notes", "Notes") { button(label, 470, viewId = "fab", left = 520, right = 780) }
            val floating = rows.copy(elements = rows.elements + fab.elements)
            val shown = if (label == null) alike + drawn else alike

            val still = deleteInRows(shown, floating)
            assertEquals(EndReason.CANCELLED, still.run("Delete the note Plans").end)
            assertEquals(listOf(HandOverKind.RECIPIENT), still.owner.handOvers.map { it.kind })
            assertTrue(still.screen.actions.none { it is ScreenAction.TapAt })
            assertEquals(1, still.eyes.captures)

            val arrived = deleteInRows(shown, floating) {
                eyes.lines = rowLines("New", "Plans", "Plans", "Plans") + listOfNotNull(drawn.takeIf { label == null })
            }
            arrived.run("Delete the note Plans")
            assertTrue(arrived.screen.actions.none { it is ScreenAction.TapAt })
        }
    }

    @Test
    fun aTapAtAPointIsHandedToTheOwnerWithoutAScreenshotWhenAnAgentFreeAppOrPlayServicesIsOnScreen() = runTest {
        val rows = rowsWithControls()
        val notes = rows.windows.single()
        val split = rows.copy(windows = listOf(
            notes.copy(bounds = IntRect(0, 0, 1080, 1400), active = false, focused = false),
            WindowSummary(2, WindowKind.APPLICATION, "com.x8bit.bitwarden", null, IntRect(0, 1400, 1080, 2400), active = true, focused = true),
        ))
        val autofill = rows.copy(windows = rows.windows + WindowSummary(3, WindowKind.APPLICATION, AgentFreeApps.PLAY_SERVICES, null, IntRect(0, 2000, 1080, 2300), layer = 1))

        for ((shown, kind) in listOf(split to HandOverKind.RECIPIENT, autofill to HandOverKind.PLAY_SERVICES)) {
            val harness = deleteInRows(rowLines("Groceries", "Plans", "Trip", "Gifts")) { screen.current = shown }

            assertEquals(EndReason.CANCELLED, harness.run("Delete the note Plans").end)

            assertEquals(listOf(kind), harness.owner.handOvers.map { it.kind })
            assertTrue(harness.screen.actions.none { it is ScreenAction.TapAt })
            assertEquals(1, harness.eyes.captures)
        }
    }

    @Test
    fun aDialogThatComesOverThePointDuringTheOcrStopsThePress() = runTest {
        val rows = rowsWithControls()
        val dialog = ScreenBuilder("com.example.notes", "Delete all?", windowId = 5).apply {
            text("Delete all notes?", 400)
            button("Delete all", 700, left = 600, right = 980)
        }.build()
        val covered = rows.copy(
            windows = rows.windows + dialog.windows.single().copy(bounds = IntRect(100, 300, 980, 900), layer = 1),
            elements = rows.elements + dialog.elements,
        )
        val harness = deleteInRows(rowLines("Groceries", "Plans", "Trip", "Gifts")) {
            eyes.onRead = { screen.current = covered }
        }

        harness.run("Delete the note Plans")

        assertEquals(1, harness.owner.confirmations.size)
        assertTrue(harness.screen.actions.none { it is ScreenAction.TapAt })
        assertTrue(harness.lastRequestText().contains(ScreenSteps.CHANGED_AFTER))
    }

    @Test
    fun aShareThatOpensAnEmailsComposeScreenIsNotSentUntilTheEmailIs() = runTest {
        val gallery = ScreenBuilder.screen("com.example.gallery", "Gallery") {
            text("Beach", 300)
            button("Share", 2200, viewId = "share")
        }
        val sheet = ScreenBuilder.screen("com.android.intentresolver", "Share") {
            val apps = list(null, 1200, 1800, viewId = "apps")
            button("Gmail", 1220, parent = apps, className = "android.view.ViewGroup", right = 300)
            button("Telegram", 1220, parent = apps, className = "android.view.ViewGroup", left = 340, right = 600)
        }
        val compose = ScreenBuilder.screen(Screens.MAIL, "Gmail") {
            icon("Send", 100, viewId = "send", left = 900, right = 1040)
            field("To", "", 300, viewId = "to")
            field("Subject", "", 700, viewId = "subject")
            field("Compose email", "", 850, viewId = "body")
        }
        val harness = Harness(gallery, listOf(
            call("tap", "Share", null, null, "reason" to "share the photo", "category" to "sending"),
            call("tap", "Gmail", null, null, "reason" to "share it by e-mail", "category" to "sending"),
            call("type_text", "To", null, null, "text" to "marko@example.com", "reason" to "address it", "category" to "typing_request"),
            call("tap", "Send", null, null, "reason" to "send it", "category" to "sending"),
        ))
        harness.screen.onTap("Share", sheet)
        harness.screen.onTap("Gmail", compose)
        harness.owner.confirms += listOf(ConfirmEnd.YES, ConfirmEnd.YES, ConfirmEnd.NO)

        val report = harness.run("Share this photo with marko@example.com by e-mail")

        assertEquals(EndReason.DECLINED, report.end)
        assertEquals(3, harness.owner.confirmations.size)
        assertEquals(listOf("marko@example.com"), harness.owner.confirmations[2].recipients)
        assertTrue(report.sends.toString(), report.sends.isEmpty())
        assertTrue(report.ledger.toString(), report.ledger.none { it.startsWith("shared") || it.startsWith("sent") })
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
        val ports = with(harness) { AgentPorts(screen, eyes, data, owner, model, apps, memory, log, clock, goal, history) }
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
        assertTrue(harness.model.requests.single().context.orEmpty().contains(TaskText.TEST_MODE_NOTE))
        assertTrue(harness.log.texts(TaskLog.Kind.SAFETY).single().contains("beforeTestMode=Confirm decision=Block"))
    }

    @Test
    fun aSendInTestModeIsPressedOnlyOnAScreenThatStillShowsTheOwnersOwnChat() = runTest {
        // Telegram's Saved Messages and another chat whose Send has the same key, label and class (fix-5c-selfchats-review M1).
        fun telegram(title: String, subtitle: String?) = ScreenBuilder.screen(SelfChats.TELEGRAM_WEB, "Telegram") {
            val bar = group("action_bar", 0, 240)
            icon("Navigate up", 100, parent = bar)
            val block = icon(listOfNotNull(title, subtitle).joinToString(" "), 0, parent = bar, left = 137, right = 838)
            text(title, 40, parent = block)
            if (subtitle != null) text(subtitle, 160, parent = block)
            val list = list("Messages", 300, 2000)
            text("Bread and milk", 320, parent = list)
            field("Message", "I'm late", 2100)
            button("Send", 2250, left = 900)
        }
        val saved = telegram("Saved Messages", null)
        val harness = Harness(saved, listOf(
            call("tap", "Send", "Button", null, "reason" to "send it", "category" to "sending"),
            call("fail", null, null, null, "reason" to "Stop."),
        ), settings = AgentSettings(safety = SafetySettings(testMode = true)))
        harness.owner.confirms += ConfirmEnd.YES

        harness.run("Send a message to my Saved Messages that I'm late")

        val press = harness.screen.actions.filterIsInstance<ScreenAction.Tap>().single()
        val guard = press.guard
        assertTrue(guard != null && guard(saved))
        assertFalse(guard!!(telegram("Ana Petrović", "online")))
    }

    // Plan 7A: Kestrel's own screen is waited for at the owner port (design 10.2 of sub-project 7); when it stays, the task
    // stops as interrupted before any model call.
    @Test
    fun kestrelsOwnScreenThatStaysInFrontInterruptsTheTaskBeforeAnyModelCall() = runTest {
        val kestrel = Harness(Screens.app("dev.kestrel", "Safety"), emptyList())

        val report = kestrel.run("Turn on hotspot")

        assertEquals(EndReason.INTERRUPTED, report.end)
        assertEquals(Interruption.KESTREL_SCREEN, report.interruption)
        assertTrue(kestrel.model.requests.isEmpty())
        assertTrue(kestrel.screen.actions.isEmpty())
    }

    // Kestrel's own screen that leaves lets the task go on; another agent-free app ends it before any model call (D16).
    @Test
    fun kestrelsOwnScreenThatLeavesLetsTheTaskGoOnAndAnotherAgentFreeAppEndsIt() = runTest {
        val kestrel = Harness(Screens.app("dev.kestrel", "Safety"), listOf(call("fail", null, null, null, "reason" to "Stop.")))
        kestrel.owner.leaves += true
        kestrel.owner.onLeave = { kestrel.screen.current = Screens.chats() }

        assertEquals(EndReason.FAILED_BY_MODEL, kestrel.run("Open Ana's chat").end)
        assertTrue(kestrel.requestText(0).contains("Ana Petrović"))

        val bank = Harness(Screens.app("com.example.bank", "Balance"), emptyList(),
            settings = AgentSettings(safety = SafetySettings(testMode = false), agentFree = AgentFreeApps(added = setOf("com.example.bank"))))
        assertEquals(EndReason.AGENT_FREE, bank.run("Check my balance").end)
        assertTrue(bank.model.requests.isEmpty())
    }

    // Kestrel's own screen in front of the finish's fresh look is waited for as before every observation, also again when it
    // comes back, at most three times: when it stays, or is still in front after the last wait, the task stops as
    // interrupted, not unverified, and its route notes get no strike; when it leaves, the finish is checked on the screen
    // behind it (design 10.2 of sub-project 7).
    @Test
    fun kestrelsOwnScreenAtTheFinishIsWaitedForAndInterruptsTheTaskWithoutAStrike() = runTest {
        val note = RouteNote(Screens.MESSENGER, "tap \"Ana Petrović\"", "0.9", "en", proposals = 2)

        // The run's report, the route note's strikes and the waits, when the screen behind Kestrel's own shows after the
        // wait numbered [leftAt] (none: Kestrel's own screen stays in front) and [leaves] are the port's answers.
        data class Finished(val report: TaskReport, val strikes: Int, val waits: Int)
        suspend fun finishBehindKestrel(vararg leaves: Boolean, leftAt: Int? = null): Finished {
            val harness = Harness(Screens.chats(), listOf(
                call("tap", "Ana Petrović", null, null, "reason" to "open the chat", "category" to "reading"),
                call("say", null, null, null, "text" to "Almost done."),
                finish("Opened.", "Hi"),
            ))
            harness.screen.onTap("Ana Petrović", Screens.chat("Ana Petrović", "Hi"))
            harness.memory.notes[Screens.MESSENGER] = listOf(note)
            harness.owner.onSay = { harness.screen.current = Screens.app(AgentFreeApps.KESTREL, "Safety") }
            harness.owner.leaves += leaves.toList()
            var waits = 0
            harness.owner.onLeave = { if (++waits == leftAt) harness.screen.current = Screens.chat("Ana Petrović", "Hi") }
            val report = harness.run("Open Ana's chat in Kestrel fixtures")
            return Finished(report, harness.memory.notes[Screens.MESSENGER]!!.single().strikes, waits)
        }

        val stayed = finishBehindKestrel()
        assertEquals(EndReason.INTERRUPTED, stayed.report.end)
        assertEquals(Interruption.KESTREL_SCREEN, stayed.report.interruption)
        assertEquals(0, stayed.strikes)
        assertEquals(1, stayed.waits)

        val keptComingBack = finishBehindKestrel(true, true, true)
        assertEquals(EndReason.INTERRUPTED, keptComingBack.report.end)
        assertEquals(Interruption.KESTREL_SCREEN, keptComingBack.report.interruption)
        assertEquals(0, keptComingBack.strikes)
        assertEquals(3, keptComingBack.waits)

        val cameBackOnce = finishBehindKestrel(true, true, leftAt = 2)
        assertEquals(EndReason.FINISHED, cameBackOnce.report.end)
        assertEquals(2, cameBackOnce.waits)

        val left = finishBehindKestrel(true, leftAt = 1)
        assertEquals(EndReason.FINISHED, left.report.end)
        assertEquals(1, left.waits)
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

    // Plan 7A: one loop asks every question; the third interruption in a row counts as no answer and ends the row, so the
    // next question is asked again after an interruption.
    @Test
    fun aQuestionInterruptedThreeTimesCountsAsNoAnswerAndTheNextStartsAfresh() = runTest {
        val harness = Harness(Screens.chats(), listOf(
            call("remember", null, null, null, "key" to "gate code", "value" to "blue"),
            call("ask_user", null, null, null, "question" to "Which chat?"),
            call("fail", null, null, null, "reason" to "Stop."),
        ))
        harness.owner.answers += List(4) { Answer.Interrupted } + Answer.Text("Ana's")

        harness.run("Remember the gate code")

        assertEquals(5, harness.owner.asked.size)
        assertTrue(harness.requestText(1).contains("Result: the owner said no."))
        assertTrue(harness.requestText(2).contains("The owner said: \"Ana's\""))
    }
}
