package dev.kestrel.agent

import dev.kestrel.agent.loop.ScreenSteps
import dev.kestrel.agent.port.ActionOutcome
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.Part
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.Category
import dev.kestrel.safety.SafetySettings
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The data tools, the share flow's questions and `look_closer` (design D12, D14 and D20). */
class AgentToolsTest {
    private val telegram = "org.telegram.messenger"

    // Telegram's own picker; [send] shows John's row checked and the Send button, as Telegram does once a chat is picked,
    // [friends] the row "Close friends" checked too, and [caption] a caption field.
    private fun picker(send: Boolean = false, friends: Boolean = false, caption: String? = null): ScreenSnapshot = ScreenBuilder.screen(telegram) {
        text("Send to…", 100)
        val list = list(null, 300, 2000, viewId = "dialogs_list")
        row("Saved Messages", 320, list)
        row("John Smith", 460, list, checked = if (send) true else null)
        row("Close friends", 600, list, checked = if (friends) true else null)
        caption?.let { field("Add a caption", it, 2100, viewId = "caption") }
        if (send) icon("Send", 2250, viewId = "send", left = 900, right = 1040)
    }

    private fun shareSteps(vararg then: JsonObject) = listOf(
        call("find_media", null, null, null, "day" to "yesterday", "kinds" to "photos"),
        call("share_media", null, null, null, "app" to "telegram", "items" to listOf("m1", "m2", "m3")),
    ) + then

    private fun shareHarness(vararg then: JsonObject, first: ScreenSnapshot = picker()): Harness =
        Harness(Screens.app("com.android.launcher3", "Home"), shareSteps(*then)).apply {
            data.answer("find_media", photos("m1", "m2", "m3"))
            data.answer("share_media", DataResult.Ok(emptyList(), opened = telegram))
            data.onCall = { if (it == "share_media") screen.current = first }
        }

    private val stop = call("fail", null, null, null, "reason" to "Stop.")

    private fun john(): ScreenSnapshot = ScreenBuilder.screen(telegram) {
        text("John Smith", 100)
        val list = list(null, 300, 2000, viewId = "chat_list")
        text("3 photos", 1220, parent = list)
    }

    private fun photos(vararg ids: String) = DataResult.Ok(ids.map { DataLine("$it · photo · 12:00 · 2.1 MB", emptyList()) })

    private val withImages = ScriptedModel.INFO.copy(lightweight = ScriptedModel.INFO.lightweight.copy(takesImages = true))
    private val look = call("look_closer", null, null, null, "reason" to "the screen has no text")

    @Test
    fun aMissingPermissionIsAskedOnceAndTheToolRunsAgain() = runTest {
        val photos = DataResult.Ok(listOf(DataLine("m1 · photo · 12:03", emptyList())))
        val granted = Harness(Screens.chats(), listOf(
            call("find_media", null, null, null, "day" to "yesterday", "kinds" to "photos"),
            call("fail", null, null, null, "reason" to "Stop here."),
        ))
        granted.data.answer("find_media", DataResult.NeedsPermission(listOf("android.permission.READ_MEDIA_IMAGES")), photos)
        granted.owner.permissionEnds += PermissionEnd.GRANTED
        granted.run("Find yesterday's photos")
        assertEquals(listOf("find_media", "find_media"), granted.data.calls)
        assertEquals(listOf(listOf("android.permission.READ_MEDIA_IMAGES")), granted.owner.permissions)
        assertTrue(granted.lastRequestText().contains("m1 · photo · 12:03"))

        val refused = Harness(Screens.chats(), listOf(
            call("find_media", null, null, null, "day" to "yesterday", "kinds" to "photos"),
            call("fail", null, null, null, "reason" to "Stop here."),
        ))
        refused.data.answer("find_media", DataResult.NeedsPermission(listOf("android.permission.READ_MEDIA_IMAGES")))
        refused.run("Find yesterday's photos")
        assertEquals(listOf("find_media"), refused.data.calls)
        assertTrue(refused.lastRequestText().contains("permission refused"))
    }

    @Test
    fun lookCloserReadsTextThenAsksForAScreenshotThenShowsTheNumbers() = runTest {
        val screen = ScreenBuilder.screen("com.example.map") {
            button(null, 900, viewId = "pay", suggestedName = "pay")
        }
        val withImages = ScriptedModel.INFO.copy(lightweight = ScriptedModel.INFO.lightweight.copy(takesImages = true))
        val look = call("look_closer", null, null, null, "reason" to "the button has no label")
        val harness = Harness(screen, listOf(look, look, look, call("fail", null, null, null, "reason" to "Stop.")), model = withImages)
        harness.eyes.lines = listOf(OcrLine("Pay now", IntRect(40, 900, 1040, 1020), 0.95), OcrLine("blur", IntRect(40, 1100, 1040, 1200), 0.4))
        harness.owner.answers += listOf(Answer.Text("Yes, send it."), Answer.Number(1))

        harness.run("Pay the bill")

        val requests = harness.model.requests
        assertTrue(requests[1].messages.filterIsInstance<ChatMessage.ToolResult>().last().text.contains("OCR \"Pay now\""))
        assertFalse(requests[1].messages.filterIsInstance<ChatMessage.ToolResult>().last().text.contains("blur"))
        assertTrue(requests[2].messages.last() is ChatMessage.User && (requests[2].messages.last() as ChatMessage.User).parts.single() is Part.Image)
        assertTrue(harness.owner.asked[0].first.startsWith("May I send a screenshot of"))
        assertTrue(harness.lastRequestText().contains("The owner picked [1]."))
        assertTrue(harness.screen.badges.isNotEmpty())
        assertEquals(2, harness.eyes.closed)
    }

    @Test
    fun onlyTheKeptOcrLinesAreMergedAndTheMasksUseEveryLine() = runTest {
        val screen = ScreenBuilder.screen("com.example.map") { button(null, 900, viewId = "pay", suggestedName = "pay") }
        val harness = Harness(screen, listOf(look, look, call("fail", null, null, null, "reason" to "Stop.")), model = withImages)
        // A whole line read below 0.8 must not hide the part that another region read well.
        val unsure = OcrLine("Pay now to Ana", IntRect(40, 900, 1040, 1020), 0.6)
        val part = OcrLine("Pay now", IntRect(40, 900, 600, 1020), 0.95)
        val pin = OcrLine("PIN 4821", IntRect(40, 1100, 600, 1200), 0.9)
        harness.eyes.lines = listOf(unsure, part, pin)
        harness.eyes.merge = { lines -> lines - pin }
        harness.owner.answers += Answer.Text("Yes.")

        harness.run("Pay the bill")

        assertEquals(listOf(listOf(part, pin)), harness.eyes.merged)
        assertTrue(harness.model.requests[1].messages.filterIsInstance<ChatMessage.ToolResult>().last().text.contains("OCR \"Pay now\""))
        assertTrue(harness.eyes.encoded.single().first.contains(pin.bounds))
    }

    @Test
    fun oneYesCoversTheChatAndTheAppsOwnSendOfOneShare() = runTest {
        val harness = Harness(Screens.app("com.android.launcher3", "Home"), listOf(
            call("find_media", null, null, null, "day" to "yesterday", "kinds" to "photos"),
            call("share_media", null, null, null, "app" to "telegram", "items" to listOf("m1", "m2", "m3")),
            call("tap", "John Smith", null, null, "reason" to "pick John", "category" to "reading"),
            call("tap", "Send", null, null, "reason" to "send them", "category" to "sending"),
            call("finish", null, null, null, "summary" to "Sent.", "evidence" to listOf(mapOf("target" to mapOf("label" to "3 photos")))),
        ))
        harness.data.answer("find_media", photos("m1", "m2", "m3").copy(note = "Kestrel can see only the photos that you allowed."))
        harness.data.answer("share_media", DataResult.Ok(emptyList(), opened = telegram))
        harness.data.onCall = { if (it == "share_media") harness.screen.current = picker() }
        harness.screen.onTap("John Smith", picker(send = true))
        harness.screen.onTap("Send", john())
        harness.owner.confirms += ConfirmEnd.YES

        val report = harness.run("Send yesterday's photos to John on Telegram")

        assertEquals(EndReason.FINISHED, report.end)
        assertEquals(listOf("Kestrel can see only the photos that you allowed."), harness.owner.said)
        assertEquals(1, harness.owner.confirmations.size)
        assertEquals(2, harness.screen.actions.count { it is ScreenAction.Tap })
        assertEquals(listOf("Sent to John Smith on Telegram."), report.sends)
        assertEquals(1, report.confirmed)
        assertEquals(3, report.shareItems)
    }

    @Test
    fun aDoubtedTapOfAShareAsksAgainAndTheTapThatWorksIsReported() = runTest {
        val harness = Harness(Screens.app("com.android.launcher3", "Home"), listOf(
            call("find_media", null, null, null, "day" to "yesterday", "kinds" to "photos"),
            call("share_media", null, null, null, "app" to "telegram", "items" to listOf("m1", "m2", "m3")),
            call("tap", "John Smith", null, null, "reason" to "pick John", "category" to "reading"),
            call("tap", "John Smith", null, null, "reason" to "pick John again", "category" to "reading"),
            call("tap", "Send", null, null, "reason" to "send them", "category" to "sending"),
            call("finish", null, null, null, "summary" to "Sent.", "evidence" to listOf(mapOf("target" to mapOf("label" to "3 photos")))),
        ))
        harness.data.answer("find_media", photos("m1", "m2", "m3"))
        harness.data.answer("share_media", DataResult.Ok(emptyList(), opened = telegram))
        harness.data.onCall = { if (it == "share_media") harness.screen.current = picker() }
        // The first tap on John's row may not have worked and changes nothing; the second one works.
        val taps = { harness.screen.actions.count { it is ScreenAction.Tap } }
        harness.screen.outcome = { ActionOutcome(done = true, uncertain = it is ScreenAction.Tap && taps() == 1, method = "fake") }
        harness.screen.on({ action, _ -> action is ScreenAction.Tap && taps() == 2 }) { picker(send = true) }
        harness.screen.onTap("Send", john())
        harness.owner.confirms += listOf(ConfirmEnd.YES, ConfirmEnd.YES)

        val report = harness.run("Send yesterday's photos to John on Telegram")

        assertEquals(EndReason.FINISHED, report.end)
        assertEquals(2, harness.owner.confirmations.size)
        assertEquals(3, taps())
        assertEquals(listOf("Sent to John Smith on Telegram."), report.sends)
        assertEquals(3, report.shareItems)
        val tried = report.ledger.indexOf("tried to share the items with John Smith, but the tap may not have worked")
        val shared = report.ledger.indexOf("shared 3 items with John Smith")
        assertTrue(report.ledger.toString(), tried >= 0 && shared > tried)
    }

    @Test
    fun aDoubtedSendOfAShareThatWasCountedAsksAgainAndIsCountedOnce() = runTest {
        val harness = Harness(Screens.app("com.android.launcher3", "Home"), listOf(
            call("find_media", null, null, null, "day" to "yesterday", "kinds" to "photos"),
            call("share_media", null, null, null, "app" to "telegram", "items" to listOf("m1", "m2", "m3")),
            call("tap", "John Smith", null, null, "reason" to "pick John", "category" to "reading"),
            call("tap", "Send", null, null, "reason" to "send them", "category" to "sending"),
            call("tap", "Send", null, null, "reason" to "send them again", "category" to "sending"),
            call("finish", null, null, null, "summary" to "Sent.", "evidence" to listOf(mapOf("target" to mapOf("label" to "3 photos")))),
        ))
        harness.data.answer("find_media", photos("m1", "m2", "m3"))
        harness.data.answer("share_media", DataResult.Ok(emptyList(), opened = telegram))
        harness.data.onCall = { if (it == "share_media") harness.screen.current = picker() }
        // The first tap on Send may not have worked and changes nothing; the second one works.
        val taps = { harness.screen.actions.count { it is ScreenAction.Tap } }
        harness.screen.outcome = { ActionOutcome(done = true, uncertain = it is ScreenAction.Tap && taps() == 2, method = "fake") }
        harness.screen.onTap("John Smith", picker(send = true))
        harness.screen.on({ action, _ -> action is ScreenAction.Tap && taps() == 3 }) { john() }
        harness.owner.confirms += listOf(ConfirmEnd.YES, ConfirmEnd.YES)

        val report = harness.run("Send yesterday's photos to John on Telegram")

        assertEquals(EndReason.FINISHED, report.end)
        assertEquals(2, harness.owner.confirmations.size)
        assertEquals(3, taps())
        assertEquals(listOf("Sent to John Smith on Telegram."), report.sends)
        assertEquals(listOf("John Smith"), report.recipients)
        assertEquals(3, report.shareItems)
    }

    @Test
    fun aSecondShareToTheSameChatAsksAgain() = runTest {
        val harness = Harness(Screens.app("com.android.launcher3", "Home"), listOf(
            call("find_media", null, null, null, "day" to "yesterday", "kinds" to "photos"),
            call("share_media", null, null, null, "app" to "telegram", "items" to listOf("m1", "m2", "m3")),
            call("tap", "John Smith", null, null, "reason" to "pick John", "category" to "reading"),
            call("share_media", null, null, null, "app" to "telegram", "items" to listOf("m4", "m5", "m6")),
            call("tap", "John Smith", null, null, "reason" to "pick John", "category" to "reading"),
        ))
        harness.data.answer("find_media", photos("m1", "m2", "m3", "m4", "m5", "m6"))
        harness.data.answer("share_media", DataResult.Ok(emptyList(), opened = telegram))
        harness.data.onCall = { if (it == "share_media") harness.screen.current = picker() }
        harness.screen.onTap("John Smith", john())
        harness.owner.confirms += listOf(ConfirmEnd.YES, ConfirmEnd.NO)

        val report = harness.run("Send yesterday's photos to John on Telegram")

        assertEquals(EndReason.DECLINED, report.end)
        assertEquals(2, harness.owner.confirmations.size)
        assertEquals(1, harness.screen.actions.count { it is ScreenAction.Tap })
        assertEquals(listOf("Sent to John Smith on Telegram."), report.sends)
    }

    @Test
    fun aYesInAShareFlowDoesNotCoverAQuestionOfAnotherKind() = runTest {
        val harness = shareHarness(
            call("type_text", "Add a caption", null, null, "text" to "See you at five", "reason" to "caption", "category" to "typing_screen_text"),
            call("type_text", "Add a caption", null, null, "text" to "Meet me at noon", "reason" to "caption", "category" to "typing_screen_text"),
            stop,
            first = picker(caption = ""),
        )
        harness.owner.confirms += listOf(ConfirmEnd.YES, ConfirmEnd.NO)

        val report = harness.run("Send yesterday's photos to John on Telegram")

        assertEquals(EndReason.DECLINED, report.end)
        assertEquals(2, harness.owner.confirmations.size)
        assertEquals(1, harness.screen.actions.count { it is ScreenAction.SetText })
    }

    @Test
    fun aShareWhoseSendFailsIsNotReportedAsSent() = runTest {
        val harness = shareHarness(
            call("tap", "John Smith", null, null, "reason" to "pick John", "category" to "reading"),
            call("tap", "Send", null, null, "reason" to "send them", "category" to "sending"),
            call("finish", null, null, null, "summary" to "Sent.", "evidence" to listOf(mapOf("target" to mapOf("label" to "Send to…")))),
            stop,
        )
        // The row only picks John; the app's own Send fails.
        harness.screen.onTap("John Smith", picker(send = true))
        harness.screen.outcome = { ActionOutcome(done = !(it is ScreenAction.Tap && harness.screen.actions.count { a -> a is ScreenAction.Tap } == 2), method = "fake") }
        harness.owner.confirms += ConfirmEnd.YES

        val report = harness.run("Send yesterday's photos to John on Telegram")

        assertEquals(EndReason.FAILED_BY_MODEL, report.end)
        assertEquals(1, harness.owner.confirmations.size)
        assertTrue(report.sends.isEmpty())
        assertTrue(report.recipients.isEmpty())
        assertEquals(0, report.shareItems)
        assertTrue(report.ledger.none { it.startsWith("shared") })
        assertTrue(report.ledger.toString(), report.ledger.contains("tried to share the items with John Smith, but I couldn't see that they were sent"))
        // The owner said yes to this share, so a finish still needs its evidence (D21).
        assertTrue(harness.lastRequestText().contains("finish failed: the chat it was shared to"))
    }

    @Test
    fun aFailedRowTapAndItsCoveredRetryThatSendsAreCountedOnce() = runTest {
        val harness = shareHarness(
            call("tap", "John Smith", null, null, "reason" to "pick John", "category" to "reading"),
            call("tap", "John Smith", null, null, "reason" to "pick John again", "category" to "reading"),
            stop,
        )
        // The first tap fails; the second one sends at once and shows John's chat.
        harness.screen.outcome = { ActionOutcome(done = !(it is ScreenAction.Tap && harness.screen.actions.count { a -> a is ScreenAction.Tap } == 1), method = "fake") }
        harness.screen.onTap("John Smith", john())
        harness.owner.confirms += ConfirmEnd.YES

        val report = harness.run("Send yesterday's photos to John on Telegram")

        assertEquals(1, harness.owner.confirmations.size)
        assertEquals(2, harness.screen.actions.count { it is ScreenAction.Tap })
        assertEquals(listOf("Sent to John Smith on Telegram."), report.sends)
        assertEquals(3, report.shareItems)
    }

    @Test
    fun aShareWhoseChatShowsLateIsReportedAsSent() = runTest {
        val harness = shareHarness(call("tap", "John Smith", null, null, "reason" to "pick John", "category" to "reading"), stop)
        // The row sends at once, but the app shows John's chat only on the third read after the tap, after a screen that
        // shows neither, as a chat that opens in a new activity on a slow phone does (final review of plan 5B, part A, M1).
        val between = ScreenBuilder.screen(telegram) {
            text("Chats", 100)
            val list = list(null, 300, 2000, viewId = "dialogs_list")
            row("Saved Messages", 320, list)
        }
        var reads = -1
        harness.screen.outcome = {
            if (it is ScreenAction.Tap) reads = 0
            ActionOutcome(done = true, method = "fake")
        }
        harness.screen.onSnapshot = {
            if (reads >= 0) {
                reads++
                if (reads == 2) harness.screen.current = between
                if (reads == 3) harness.screen.current = john()
            }
        }
        harness.owner.confirms += ConfirmEnd.YES

        val report = harness.run("Send yesterday's photos to John on Telegram")

        assertEquals(1, harness.owner.confirmations.size)
        assertEquals(listOf("Sent to John Smith on Telegram."), report.sends)
        assertEquals(3, report.shareItems)
        assertTrue(report.ledger.toString(), report.ledger.none { it.startsWith("tried") })
    }

    @Test
    fun aShareCancelledWhileKestrelWaitsForItsChatIsReportedAsTried() = runTest {
        val harness = shareHarness(call("tap", "John Smith", null, null, "reason" to "pick John", "category" to "reading"), stop)
        // The row sends at once, and the owner's touch or "stop" cancels the task on the first read after the tap, while
        // Kestrel waits for the chat (re-check of plan 5B's final fix round, part 1, N1).
        var tapped = false
        harness.screen.outcome = {
            if (it is ScreenAction.Tap) tapped = true
            ActionOutcome(done = true, method = "fake")
        }
        harness.screen.onSnapshot = { if (tapped) throw TaskCancellation(EndReason.CANCELLED) }
        harness.owner.confirms += ConfirmEnd.YES

        assertTrue(runCatching { harness.run("Send yesterday's photos to John on Telegram") }.exceptionOrNull() is TaskCancellation)

        val report = harness.reports.single()
        assertEquals(EndReason.CANCELLED, report.end)
        assertTrue(report.sends.isEmpty())
        assertTrue(report.ledger.toString(), report.ledger.contains("tried to share the items with John Smith, but I couldn't see that they were sent"))
    }

    @Test
    fun aShareToTwoChatsIsAskedOnceForTheTwo() = runTest {
        val harness = shareHarness(
            call("tap", "John Smith", null, null, "reason" to "pick John", "category" to "reading"),
            call("tap", "Close friends", null, null, "reason" to "pick the group", "category" to "reading"),
            call("tap", "Send", null, null, "reason" to "send them", "category" to "sending"),
            stop,
        )
        val picked = ScreenBuilder.screen(telegram) {
            text("Send to…", 100)
            val list = list(null, 300, 2000, viewId = "dialogs_list")
            row("Saved Messages", 320, list)
            row("John Smith", 460, list, checked = true)
            row("Close friends", 600, list)
        }
        harness.screen.onTap("John Smith", picked)
        harness.screen.onTap("Close friends", picker(send = true, friends = true))
        harness.screen.onTap("Send", Screens.app("com.android.launcher3", "Home"))
        harness.owner.confirms += listOf(ConfirmEnd.YES, ConfirmEnd.YES)

        val report = harness.run("Send yesterday's photos to John and Close friends on Telegram")

        assertEquals(2, harness.owner.confirmations.size)
        assertEquals(3, harness.screen.actions.count { it is ScreenAction.Tap })
        assertEquals(1, report.sends.size)
        assertEquals(setOf("John Smith", "Close friends"), report.recipients.toSet())
        assertEquals(3, report.shareItems)
    }

    @Test
    fun anAppsShareButtonOutsideAChatRecordsNoSend() = runTest {
        val gallery = ScreenBuilder.screen("com.example.gallery") {
            image(300)
            button("Share", 2250, viewId = "share")
        }
        val harness = Harness(gallery, listOf(call("tap", "Share", null, null, "reason" to "share it", "category" to "sending"), stop))
        harness.screen.onTap("Share", picker())
        harness.owner.confirms += ConfirmEnd.YES

        val report = harness.run("Send this photo to John on Telegram")

        assertEquals(1, harness.owner.confirmations.size)
        assertTrue(harness.owner.confirmations.single().recipients.isEmpty())
        assertEquals(1, harness.screen.actions.count { it is ScreenAction.Tap })
        assertTrue(report.sends.isEmpty())
        assertTrue(report.recipients.isEmpty())
    }

    @Test
    fun aDeepLinkWithDataIsAskedAlthoughItsActionWordIsInTheRequest() = runTest {
        val harness = Harness(Screens.app("com.android.launcher3", "Home"), listOf(
            call("open_deep_link", null, null, null, "uri" to "whatsapp://send?text=See%20you", "reason" to "send it"),
            stop,
        ))
        harness.screen.resolver = { "com.whatsapp" }

        val report = harness.run("Send the photo to John on WhatsApp")

        assertEquals(EndReason.DECLINED, report.end)
        val question = harness.owner.confirmations.single()
        assertEquals(Category.SENDING, question.category)
        assertTrue(question.question, question.question.contains("a whatsapp link"))
        assertTrue(harness.screen.actions.isEmpty())
    }

    @Test
    fun firefoxAsTheDefaultBrowserGetsTheLinkAgainWhenItsPageStaysEmpty() = runTest {
        fun page(filled: Boolean) = ScreenBuilder.screen("org.mozilla.firefox") {
            val web = group("web", 300, 2200, className = "android.webkit.WebView")
            if (filled) text("Wikipedia", 400, parent = web)
        }
        val opens = { harness: Harness -> harness.screen.actions.count { it is ScreenAction.OpenUri } }
        fun firefox(): Harness = Harness(Screens.app("com.android.launcher3", "Home"), listOf(
            call("open_url", null, null, null, "url" to "https://en.wikipedia.org", "reason" to "open the page"),
            stop,
        )).apply {
            screen.resolver = { "org.mozilla.firefox" }
            screen.on({ action, _ -> action is ScreenAction.OpenUri }) { page(filled = opens(this) == 2) }
        }

        val retried = firefox()
        retried.run("Open en.wikipedia.org")
        assertEquals(2, opens(retried))
        assertFalse(retried.lastRequestText().contains(ScreenSteps.EMPTY_PAGE))
        assertTrue(retried.lastRequestText().contains("Wikipedia"))

        // A pause before the second intent: it is not sent, and the model hears that the page is empty.
        val paused = firefox()
        paused.owner.gates += listOf(Gate.GO, Gate.GO, Gate.RESUMED)
        paused.run("Open en.wikipedia.org")
        assertEquals(1, opens(paused))
        assertTrue(paused.lastRequestText().contains(ScreenSteps.EMPTY_PAGE))

        // A pause before the first intent: no link is sent at all, not even by the retry.
        val pausedFirst = firefox()
        pausedFirst.owner.gates += listOf(Gate.GO, Gate.RESUMED)
        pausedFirst.run("Open en.wikipedia.org")
        assertEquals(0, opens(pausedFirst))
    }

    @Test
    fun aShareStartsItsFlowAlsoWhenTheDataPortNamesNoApp() = runTest {
        val harness = Harness(Screens.app("com.android.launcher3", "Home"), listOf(
            call("find_media", null, null, null, "day" to "yesterday", "kinds" to "photos"),
            call("share_media", null, null, null, "app" to "telegram", "items" to listOf("m1")),
            call("tap", "Close friends", null, null, "reason" to "just looking", "category" to "reading"),
        ))
        harness.data.answer("find_media", photos("m1"))
        harness.data.answer("share_media", DataResult.Ok(emptyList()))
        harness.data.onCall = { if (it == "share_media") harness.screen.current = picker() }
        harness.owner.confirms += ConfirmEnd.NO

        assertEquals(EndReason.DECLINED, harness.run("Send yesterday's photo to John on Telegram").end)
        assertTrue(harness.owner.confirmations.single().question.contains("Close friends"))
        assertTrue(harness.screen.actions.none { it is ScreenAction.Tap })
    }

    @Test
    fun onlyAnAlwaysAnswerApprovesScreenshotsForGood() = runTest {
        val screen = ScreenBuilder.screen("com.example.map") { button(null, 900, viewId = "pay", suggestedName = "pay") }
        for ((answer, approved, sent) in listOf(
            Triple("No, always ask me first.", false, false),
            Triple("Not always.", false, false),
            Triple("Yes.", false, true),
            Triple("Always for this app.", true, true),
        )) {
            val harness = Harness(screen, listOf(look, look, call("fail", null, null, null, "reason" to "Stop.")), model = withImages)
            harness.eyes.lines = null
            harness.owner.answers += listOf(Answer.Text(answer), Answer.Silence)

            harness.run("Pay the bill")

            assertEquals(answer, approved, harness.memory.approvals.isNotEmpty())
            assertEquals(answer, sent, harness.eyes.encoded.isNotEmpty())
        }
    }

    @Test
    fun aCodeInsideARowWithoutItsOwnLabelIsMasked() = runTest {
        val screen = ScreenBuilder.screen("com.example.chat") {
            val row = button(null, 400, viewId = "row")
            text("Your verification code", 400, parent = row)
            text("483920", 470, parent = row)
        }
        val harness = Harness(screen, listOf(look, call("fail", null, null, null, "reason" to "Stop.")), model = withImages)
        harness.eyes.lines = null
        harness.owner.answers += Answer.Text("Yes.")

        harness.run("Read the chat")

        val masks = harness.eyes.encoded.single().first
        assertTrue(masks.toString(), masks.any { it.top <= 400 && it.bottom >= 520 })
        assertFalse(harness.lastRequestText().contains("483920"))
    }

    @Test
    fun aScreenThatChangedDuringTheQuestionIsNeverCaptured() = runTest {
        val screen = ScreenBuilder.screen("com.example.map") { button(null, 900, viewId = "pay", suggestedName = "pay") }
        val bank = Screens.app("com.example.bank", "Balance")
        val harness = Harness(screen, listOf(look, call("fail", null, null, null, "reason" to "Stop.")), model = withImages,
            settings = AgentSettings(safety = SafetySettings(testMode = false), agentFree = AgentFreeApps(added = setOf("com.example.bank"))))
        harness.eyes.lines = null
        harness.owner.answers += listOf(Answer.Text("Yes."), Answer.Silence)
        harness.owner.onAsk = { question -> if (question.startsWith("May I send a screenshot")) harness.screen.current = bank }

        val report = harness.run("Pay the bill")

        assertTrue(harness.eyes.encoded.isEmpty())
        assertEquals(EndReason.AGENT_FREE, report.end)
    }
}
