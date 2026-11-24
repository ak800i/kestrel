package dev.kestrel.agent

import dev.kestrel.agent.loop.Launches
import dev.kestrel.agent.loop.ScreenSteps
import dev.kestrel.agent.port.ActionOutcome
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.IntentSpec
import dev.kestrel.agent.port.LauncherApp
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.testing.FakeApps
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.Part
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.Category
import dev.kestrel.safety.EmergencyNumbers
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
            data.answer("share_media", DataResult.Opens(IntentSpec("android.intent.action.SEND_MULTIPLE", packageName = telegram), "Kestrel opened the share screen."))
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
        harness.data.answer("share_media", DataResult.Opens(IntentSpec("android.intent.action.SEND_MULTIPLE", packageName = telegram), "Kestrel opened the share screen."))
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
        harness.data.answer("share_media", DataResult.Opens(IntentSpec("android.intent.action.SEND_MULTIPLE", packageName = telegram), "Kestrel opened the share screen."))
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
        harness.data.answer("share_media", DataResult.Opens(IntentSpec("android.intent.action.SEND_MULTIPLE", packageName = telegram), "Kestrel opened the share screen."))
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
        harness.data.answer("share_media", DataResult.Opens(IntentSpec("android.intent.action.SEND_MULTIPLE", packageName = telegram), "Kestrel opened the share screen."))
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

    private val maps = "com.google.android.apps.maps"

    // A deep link with the app that the owner named; Android would ask which app opens a geo: link, so without a package
    // it resolves to none, and each app opens only the links of [handles]. As on real phones, Google Maps' launcher label
    // is "Maps"; Maps Go is called Maps too, and by default only Google Maps opens the link. The Google app opens none.
    private fun namedApp(
        app: String,
        link: String = "geo:0,0?q=Kalemegdan",
        handles: Map<String, String> = mapOf("geo:" to maps),
        extra: List<LauncherApp> = emptyList(),
    ): Harness =
        Harness(
            Screens.app("com.android.launcher3", "Home"),
            listOf(call("open_deep_link", null, null, null, "uri" to link, "reason" to "show the place", "app" to app), stop),
            launcher = FakeApps.DEFAULT + LauncherApp("Maps", maps, "11.150") + LauncherApp("Maps", "com.google.android.apps.mapslite", "1.0") +
                LauncherApp("Google", "com.google.android.googlequicksearchbox", "17.0") + extra,
        ).apply {
            screen.resolver = { spec -> spec.packageName?.takeIf { handles.any { (scheme, app) -> spec.data.orEmpty().startsWith(scheme) && app == spec.packageName } } }
        }

    @Test
    fun aDeepLinkOpensInTheAppThatTheOwnerNamedWithoutAChooser() = runTest {
        for (app in listOf("Google Maps", "Maps", maps)) {
            val harness = namedApp(app)

            harness.run("Show Kalemegdan on Google Maps")

            assertEquals(app, listOf(IntentSpec(IntentSpec.VIEW, "geo:0,0?q=Kalemegdan", maps, browsable = true)), harness.screen.sent)
            assertTrue(app, harness.owner.confirmations.isEmpty())
        }
    }

    @Test
    fun aNamedAppThatIsMissingOrDoesNotOpenTheLinkIsAnErrorForTheModel() = runTest {
        for ((app, error) in listOf(
            "Here WeGo" to "no app named \"Here WeGo\"",
            "com.here.app.maps" to "no installed app \"com.here.app.maps\" opens this link",
            "Waze" to "\"Waze\" doesn't open this link",
            "com.waze" to "\"Waze\" doesn't open this link",
        )) {
            val harness = namedApp(app)

            harness.run("Show Kalemegdan on Google Maps")

            assertTrue(app, harness.screen.actions.isEmpty())
            assertTrue(harness.lastRequestText(), harness.lastRequestText().contains(error))
        }
        // Two apps with the label, and both open the link: the model has to name one.
        val both = namedApp("Maps")
        both.screen.resolver = { spec -> spec.packageName }
        both.run("Show Kalemegdan on Maps")
        assertTrue(both.screen.actions.isEmpty())
        assertTrue(both.lastRequestText(), both.lastRequestText().contains("several apps match"))
    }

    @Test
    fun anAppThatOpensTheLinkIsFoundByItsLabelOrByItsPackagesWords() = runTest {
        // Every launcher app opens the link here: "Google Maps" is exactly Google Maps' package words, and no other app's.
        // The Google app's label only comes close, and an exact name wins over a near one.
        val every = namedApp("Google Maps")
        every.screen.resolver = { spec -> spec.packageName }

        every.run("Show Kalemegdan on Google Maps")

        assertEquals(every.lastRequestText(), listOf(IntentSpec(IntentSpec.VIEW, "geo:0,0?q=Kalemegdan", maps, browsable = true)), every.screen.sent)
        assertEquals("google maps", ScreenSteps.packageWords(maps))
        assertEquals("google mapslite", ScreenSteps.packageWords("com.google.android.apps.mapslite"))
        assertEquals("settings", ScreenSteps.packageWords("com.android.settings"))
        assertEquals("telegram messenger", ScreenSteps.packageWords("org.telegram.messenger"))
        assertEquals(null, ScreenSteps.packageWords("com.android.app"))

        // A vendor's word alone is no name: only Google Maps opens the link, and the Google app, which does not, is "Google".
        val vendor = namedApp("Google")
        vendor.run("Show Kalemegdan on Google")
        assertTrue(vendor.screen.actions.isEmpty())
        assertTrue(vendor.lastRequestText(), vendor.lastRequestText().contains("\"Google\" doesn't open this link"))
    }

    @Test
    fun aLookalikeLabelAndTheRealAppsPackageWordsAreTwoAppsWhenBothOpenTheLink() = runTest {
        // An app that calls itself Google Maps opens geo: links too; the real Google Maps is labelled "Maps".
        val lookalike = "com.example.atlas"
        val both = namedApp("Google Maps", extra = listOf(LauncherApp("Google Maps", lookalike, "1.0")))
        both.screen.resolver = { spec -> spec.packageName?.takeIf { it == maps || it == lookalike } }

        both.run("Show Kalemegdan on Google Maps")

        assertTrue(both.screen.actions.isEmpty())
        assertTrue(both.lastRequestText(), both.lastRequestText().contains("several apps match"))

        // When the lookalike does not open the link, its exact label does not hide the real app's exact package words.
        val real = namedApp("Google Maps", extra = listOf(LauncherApp("Google Maps", lookalike, "1.0")))

        real.run("Show Kalemegdan on Google Maps")

        assertEquals(real.lastRequestText(), listOf(IntentSpec(IntentSpec.VIEW, "geo:0,0?q=Kalemegdan", maps, browsable = true)), real.screen.sent)
    }

    @Test
    fun packageWordsAreExactInTheSameWaysAsALabel() = runTest {
        // The words joined, or with an article before them, are still exactly the lookalike's label and Google Maps' words.
        val lookalike = "com.example.atlas"
        for (name in listOf("GoogleMaps", "the Google Maps")) {
            val harness = namedApp(name, extra = listOf(LauncherApp("Google Maps", lookalike, "1.0")))
            harness.screen.resolver = { spec -> spec.packageName?.takeIf { it == maps || it == lookalike } }

            harness.run("Show Kalemegdan on Google Maps")

            assertTrue(name, harness.screen.actions.isEmpty())
            assertTrue(harness.lastRequestText(), harness.lastRequestText().contains("several apps match"))
        }
    }

    @Test
    fun anotherAppsPackageWordsThatAreTheLabelMakeTheNameAQuestion() = runTest {
        // A hostile app opens geo: links too, and its package's words are "maps", which is Google Maps' label: both names
        // are exact, so "Maps" means two apps.
        val hostile = "com.maps"
        val harness = namedApp("Maps", extra = listOf(LauncherApp("Weather", hostile, "1.0")))
        harness.screen.resolver = { spec -> spec.packageName?.takeIf { it == maps || it == hostile } }

        harness.run("Show Kalemegdan on Maps")

        assertTrue(harness.screen.actions.isEmpty())
        assertTrue(harness.lastRequestText(), harness.lastRequestText().contains("several apps match"))
    }

    @Test
    fun anAppNamedExactlyThatDoesNotOpenTheLinkIsNeverReplacedByANearMiss() = runTest {
        // Waze is installed and does not open geo: links; Wazer, which does, only comes close to the name.
        val wazer = "com.wazer.navigation"
        val harness = namedApp("Waze", handles = mapOf("geo:" to wazer), extra = listOf(LauncherApp("Wazer", wazer, "1.0")))

        harness.run("Show Kalemegdan on Waze")

        assertTrue(harness.screen.actions.isEmpty())
        assertTrue(harness.lastRequestText(), harness.lastRequestText().contains("\"Waze\" doesn't open this link"))
    }

    @Test
    fun twoAppsThatOpenTheLinkWithTheSamePackageWordsAreAQuestionForTheModel() = runTest {
        val copy = "com.google.maps"
        val harness = namedApp("Google Maps", extra = listOf(LauncherApp("Atlas", copy, "1.0")))
        harness.screen.resolver = { spec -> spec.packageName?.takeIf { it == maps || it == copy } }

        harness.run("Show Kalemegdan on Google Maps")

        assertTrue(harness.screen.actions.isEmpty())
        assertTrue(harness.lastRequestText(), harness.lastRequestText().contains("several apps match"))
    }

    @Test
    fun theAppsThatOpenTheLinkComeFromOneQuery() = runTest {
        val harness = namedApp("Google Maps")

        harness.run("Show Kalemegdan on Google Maps")

        assertEquals(listOf(IntentSpec(IntentSpec.VIEW, "geo:0,0?q=Kalemegdan", maps, browsable = true)), harness.screen.sent)
        // Whatever the number of launcher apps: one query finds the apps that open the link, and the launch check asks
        // once more for the chosen app, as the link will be sent.
        assertEquals(2, harness.screen.handlerQueries)
        assertEquals(0, harness.screen.resolves)
    }

    @Test
    fun aNamedAppIsTheLinksTargetForEveryRuleOfTheSafetyCheck() = runTest {
        // A messenger opens it: the search of the owner's words no longer reads, so Standard asks, and the owner declines.
        val messenger = namedApp("Telegram", handles = mapOf("geo:" to telegram))
        val report = messenger.run("Show Kalemegdan in Telegram")
        assertEquals(EndReason.DECLINED, report.end)
        assertEquals(Category.SENDING, messenger.owner.confirmations.single().category)
        assertTrue(messenger.screen.actions.isEmpty())

        // Kestrel itself, an agent-free app, is refused.
        val kestrel = namedApp("dev.kestrel", handles = mapOf("geo:" to "dev.kestrel"))
        kestrel.run("Show Kalemegdan in Kestrel")
        assertTrue(kestrel.screen.actions.isEmpty())
        assertTrue(kestrel.lastRequestText(), kestrel.lastRequestText().contains("The link opens an app that the agent may not use."))

        // A tel: link opens only in a phone app, never in another app that takes it.
        val inTelegram = namedApp(telegram, "tel:+12025550143", handles = mapOf("tel:" to telegram))
        inTelegram.run("Call Ana in Telegram")
        assertTrue(inTelegram.screen.actions.isEmpty())
        assertTrue(inTelegram.lastRequestText(), inTelegram.lastRequestText().contains("Kestrel opens a tel: link only in the phone app."))
        val dialer = "com.google.android.dialer"
        val inPhone = namedApp(dialer, "tel:+12025550143", handles = mapOf("tel:" to dialer))
        // The phone app is installed, though the launcher does not show it.
        inPhone.screen.installed += dialer
        inPhone.settings = inPhone.settings.copy(emergency = EmergencyNumbers { false })
        inPhone.run("Dial Ana")
        assertEquals(inPhone.lastRequestText(), listOf(IntentSpec(IntentSpec.VIEW, "tel:+12025550143", dialer, browsable = true)), inPhone.screen.sent)
    }

    @Test
    fun firefoxAsTheDefaultBrowserGetsTheLinkAgainWhenItsPageStaysEmpty() = runTest {
        fun page(filled: Boolean) = ScreenBuilder.screen("org.mozilla.firefox") {
            val web = group("web", 300, 2200, className = "android.webkit.WebView")
            if (filled) text("Wikipedia", 400, parent = web)
        }
        val opens = { harness: Harness -> harness.screen.actions.count { it is ScreenAction.Launch } }
        fun firefox(): Harness = Harness(Screens.app("com.android.launcher3", "Home"), listOf(
            call("open_url", null, null, null, "url" to "https://en.wikipedia.org", "reason" to "open the page"),
            stop,
        )).apply {
            screen.resolver = { "org.mozilla.firefox" }
            screen.on({ action, _ -> action is ScreenAction.Launch }) { page(filled = opens(this) == 2) }
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

        // The retry is judged again: when the link now resolves to an app that the agent may not use, it is not sent.
        val moved = firefox()
        moved.screen.resolver = { if (opens(moved) == 0) "org.mozilla.firefox" else "dev.kestrel" }
        moved.run("Open en.wikipedia.org")
        assertEquals(1, opens(moved))
        assertTrue(moved.lastRequestText(), moved.lastRequestText().contains("The link opens an app that the agent may not use."))
    }

    @Test
    fun aLinkIsNotSentToTheChosenAppWhenAndroidNoLongerResolvesItThere() = runTest {
        // Google Maps opens the link when it is chosen, and no longer when the link is checked before it opens.
        val harness = namedApp("Google Maps")
        harness.screen.resolver = { spec -> spec.packageName?.takeIf { it == maps && harness.screen.handlerQueries <= 1 } }

        harness.run("Show Kalemegdan on Google Maps")

        assertTrue(harness.screen.actions.isEmpty())
        assertTrue(harness.owner.confirmations.isEmpty())
        assertTrue(harness.lastRequestText(), harness.lastRequestText().contains(Launches.NOT_RESOLVED))
    }

    // Each data tool that opens an app's screen, with the intent that a port prepared for it (plan 6B-basefix): the
    // intent opens only through the launch check, whatever app the port named.
    private val openingTools: List<Pair<String, JsonObject>> = listOf(
        "create_event" to call("create_event", null, null, null, "title" to "Dentist", "start" to "2026-11-21T10:00"),
        "set_alarm" to call("set_alarm", null, null, null, "hour" to 7, "minute" to 30),
        "set_timer" to call("set_timer", null, null, null, "seconds" to 90),
        "compose_sms" to call("compose_sms", null, null, null, "number" to "+12025550143", "text" to "I'm late"),
        "navigate" to call("navigate", null, null, null, "app" to "waze", "to" to "Main Street 1"),
        "share_media" to call("share_media", null, null, null, "app" to "telegram", "items" to listOf("m1")),
    )

    private fun opening(tool: String, step: JsonObject, intent: IntentSpec, agentFree: Set<String> = emptySet()): Harness =
        Harness(
            Screens.app("com.android.launcher3", "Home"),
            listOf(step, stop),
            settings = AgentSettings(safety = SafetySettings(testMode = false), agentFree = AgentFreeApps(added = agentFree)),
        ).apply {
            data.answer(tool, DataResult.Opens(intent, "Kestrel opened it."))
            owner.confirms += List(3) { ConfirmEnd.YES }
        }

    @Test
    fun aDataToolNeverOpensAnAppThatTheAgentMayNotUse() = runTest {
        // The intent names no app, and Android resolves it to an agent-free one; or the port named that app itself.
        val vault = "com.example.vault"
        for (intent in listOf(IntentSpec("android.intent.action.INSERT", "content://com.android.calendar/events"), IntentSpec("android.intent.action.SEND", packageName = vault))) {
            for ((tool, step) in openingTools) {
                val harness = opening(tool, step, intent, agentFree = setOf(vault))
                harness.screen.resolver = { vault }

                harness.run("Use $tool")

                assertEquals(tool, listOf(tool), harness.data.calls)
                assertTrue(tool, harness.screen.launched.isEmpty())
                assertTrue(harness.lastRequestText(), harness.lastRequestText().contains("is agent-free"))
            }
        }
    }

    @Test
    fun aDataToolOpensNothingWhenAndroidDoesNotOpenItsIntentInTheAppThatItNames() = runTest {
        val intent = IntentSpec("android.intent.action.SET_ALARM", packageName = "com.example.clock")
        for ((tool, step) in openingTools) {
            val harness = opening(tool, step, intent)
            harness.screen.resolver = { null }

            harness.run("Use $tool")

            assertTrue(tool, harness.screen.launched.isEmpty())
            assertTrue(harness.lastRequestText(), harness.lastRequestText().contains(Launches.NOT_RESOLVED))
        }
    }

    @Test
    fun aDataToolOpensItsIntentPinnedToTheAppThatAndroidResolvesItTo() = runTest {
        val clock = "com.google.android.deskclock"
        val intent = IntentSpec("android.intent.action.SET_ALARM")
        val harness = opening("set_alarm", call("set_alarm", null, null, null, "hour" to 7, "minute" to 30), intent)
        harness.screen.resolver = { clock }

        harness.run("Set an alarm for 7:30")

        assertEquals(listOf(intent.copy(packageName = clock)), harness.screen.sent)
        assertTrue(harness.lastRequestText(), harness.lastRequestText().contains("Kestrel opened it."))
    }

    @Test
    fun navigateSendsItsLinkOnlyAfterLinkCheckJudgedIt() = runTest {
        val step = call("navigate", null, null, null, "app" to "waze", "to" to "Main Street 1")
        // Waze is agent-free: LinkCheck refuses the link before the launch's own check.
        val waze = IntentSpec(IntentSpec.VIEW, "waze://?q=Main%20Street%201&navigate=yes", "com.waze")
        val closed = opening("navigate", step, waze, agentFree = setOf("com.waze"))
        closed.run("Navigate to Main Street 1 with Waze")
        assertTrue(closed.screen.launched.isEmpty())
        assertTrue(closed.lastRequestText(), closed.lastRequestText().contains("The link opens an app that the agent may not use."))

        // A scheme that no link of the agent may have is refused, whichever tool prepared it.
        val file = opening("navigate", step, IntentSpec(IntentSpec.VIEW, "file:///sdcard/route.gpx", "com.waze"))
        file.run("Navigate to Main Street 1 with Waze")
        assertTrue(file.screen.launched.isEmpty())
        assertTrue(file.lastRequestText(), file.lastRequestText().contains("Kestrel never opens file links."))

        val open = opening("navigate", step, waze)
        open.run("Navigate to Main Street 1 with Waze")
        assertEquals(listOf(waze), open.screen.sent)
    }

    @Test
    fun aLinkWithoutANamedAppGoesOnlyToTheAppThatWasJudgedOrElseToAndroidsChooser() = runTest {
        fun link(resolved: String?): Harness = Harness(
            Screens.app("com.android.launcher3", "Home"),
            listOf(call("open_deep_link", null, null, null, "uri" to "geo:0,0?q=Kalemegdan", "reason" to "show the place"), stop),
        ).apply { screen.resolver = { spec -> spec.packageName ?: resolved } }

        val one = link(maps)
        one.run("Show Kalemegdan on a map")
        assertEquals(listOf(IntentSpec(IntentSpec.VIEW, "geo:0,0?q=Kalemegdan", maps, browsable = true)), one.screen.sent)

        // No app or several apps take it: no package, so Android shows its chooser (spec 12.1).
        val chooser = link(null)
        chooser.run("Show Kalemegdan on a map")
        assertEquals(listOf(IntentSpec(IntentSpec.VIEW, "geo:0,0?q=Kalemegdan", null, browsable = true)), chooser.screen.sent)
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

    @Test
    fun aScreenshotWithATargetHoldsOnlyTheTarget() = runTest {
        val screen = ScreenBuilder.screen("com.example.map") { button(null, 900, viewId = "pay", suggestedName = "pay") }
        val lookAt = call("look_closer", null, null, null, "target" to 1, "reason" to "the button has no label")
        val harness = Harness(screen, listOf(lookAt, call("fail", null, null, null, "reason" to "Stop.")), model = withImages)
        harness.eyes.lines = null
        harness.owner.answers += Answer.Text("Yes.")

        harness.run("Pay the bill")

        assertEquals(listOf(IntRect(40, 900, 1040, 1020)), harness.eyes.areas)
    }

    // A chat whose row with a code is at [top] in its list; the redactor changes the code, so the row is masked.
    private fun codeRow(top: Int): ScreenSnapshot = ScreenBuilder.screen("com.example.chat") {
        val list = list(null, 300, 2000, viewId = "chat_list")
        val row = button(null, top, viewId = "row", parent = list)
        text("Your verification code", top, parent = row)
        text("483920", top + 70, parent = row)
    }

    @Test
    fun aMaskedRowThatMovedDuringTheCaptureIsCapturedAgain() = runTest {
        val moved = codeRow(700)
        val harness = Harness(codeRow(400), listOf(look, call("fail", null, null, null, "reason" to "Stop.")), model = withImages)
        harness.eyes.lines = null
        // The first read is step 1's OCR; the row moves while the screenshot's first capture is read, and then stays.
        var reads = 0
        harness.eyes.onRead = { if (++reads == 2) harness.screen.current = moved }
        harness.owner.answers += Answer.Text("Yes.")

        harness.run("Read the chat")

        assertEquals(3, harness.eyes.captures)
        val masks = harness.eyes.encoded.single().first
        assertTrue(masks.toString(), masks.any { it.top <= 700 && it.bottom >= 820 })
        assertFalse(masks.toString(), masks.any { it.top == 400 })
    }

    @Test
    fun withoutOcrAMaskedRowThatMovedJustBeforeTheCaptureIsSeenAsMoved() = runTest {
        val moved = codeRow(700)
        val harness = Harness(codeRow(400), listOf(look, call("fail", null, null, null, "reason" to "Stop.")), model = withImages)
        harness.eyes.lines = null
        // The row moves just before the screenshot's first capture, and the tree shows it 150 ms later, as Kestrel's
        // snapshot cache does.
        var reads = 0
        var movedAt: Long? = null
        harness.eyes.onRead = { if (++reads == 2) movedAt = testScheduler.currentTime }
        harness.screen.onSnapshot = {
            if (movedAt?.let { testScheduler.currentTime >= it + 150 } == true) harness.screen.current = moved
        }
        harness.owner.answers += Answer.Text("Yes.")

        harness.run("Read the chat")

        assertEquals(3, harness.eyes.captures)
        val masks = harness.eyes.encoded.single().first
        assertTrue(masks.toString(), masks.any { it.top <= 700 && it.bottom >= 820 })
        assertFalse(masks.toString(), masks.any { it.top == 400 })
    }

    @Test
    fun aMaskedRowThatMovesDuringEachCaptureSendsNoScreenshot() = runTest {
        val still = codeRow(400)
        val moved = codeRow(700)
        val harness = Harness(still, listOf(look, call("fail", null, null, null, "reason" to "Stop.")), model = withImages)
        harness.eyes.lines = null
        var reads = 0
        harness.eyes.onRead = { harness.screen.current = if (++reads % 2 == 0) moved else still }
        harness.owner.answers += Answer.Text("Yes.")

        harness.run("Read the chat")

        assertEquals(3, harness.eyes.captures)
        assertTrue(harness.eyes.encoded.isEmpty())
        assertTrue(harness.lastRequestText().contains("took no screenshot"))
        assertFalse(harness.lastRequestText().contains("483920"))
    }
}
