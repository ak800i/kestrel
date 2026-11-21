package dev.kestrel.agent

import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.Part
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.SafetySettings
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The data tools, the share flow's questions and `look_closer` (design D12, D14 and D20). */
class AgentToolsTest {
    private val telegram = "org.telegram.messenger"

    // Telegram's own picker; [send] shows John's row checked and the Send button, as Telegram does once a chat is picked.
    private fun picker(send: Boolean = false): ScreenSnapshot = ScreenBuilder.screen(telegram) {
        text("Send to…", 100)
        val list = list(null, 300, 2000, viewId = "dialogs_list")
        row("Saved Messages", 320, list)
        row("John Smith", 460, list, checked = if (send) true else null)
        row("Close friends", 600, list)
        if (send) icon("Send", 2250, viewId = "send", left = 900, right = 1040)
    }

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
