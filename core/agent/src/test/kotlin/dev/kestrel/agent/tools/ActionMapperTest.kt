package dev.kestrel.agent.tools

import dev.kestrel.agent.observe.ObservationBuilder
import dev.kestrel.agent.observe.ObservationInput
import dev.kestrel.agent.observe.TestTree
import dev.kestrel.agent.port.DataPort
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.Direction
import dev.kestrel.agent.port.Expect
import dev.kestrel.agent.port.MediaAction
import dev.kestrel.agent.port.MediaKinds
import dev.kestrel.agent.port.NavigationApp
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.agent.port.Press
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.port.TravelMode
import dev.kestrel.safety.ActionKind
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.Category
import dev.kestrel.safety.Point
import dev.kestrel.safety.Redactor
import dev.kestrel.safety.Vault
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScrollAxis
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** Design D10 to D13 and D32 items 27 and 29 of sub-project 5: calls onto proposals, screen actions and data tools. */
class ActionMapperTest {
    private val builder = ObservationBuilder(Redactor(Vault()), AgentFreeApps())

    /** [1] a pager that scrolls sideways, [2] a list, [3] its row, [4] a field, [5] Send and [6] an OCR line. */
    private val tree = TestTree().apply {
        add(100, description = "Photos", scrollable = true, actions = setOf(ElementAction.SCROLL_FORWARD), axis = ScrollAxis.HORIZONTAL, height = 200)
        val list = add(400, description = "Chats", scrollable = true, actions = setOf(ElementAction.SCROLL_FORWARD), axis = ScrollAxis.VERTICAL, height = 1200)
        add(500, "Ana Petrović", clickable = true, parent = list)
        add(1800, hint = "Message", editable = true, className = "android.widget.EditText", right = 900)
        add(1800, "Send", clickable = true, className = "android.widget.Button", left = 900)
    }
    private val table = builder.build(ObservationInput(1, tree.snapshot(), "Chat", ocr = listOf(OcrLine("Pay", IntRect(100, 2000, 300, 2100), 0.9)))).table

    @Test
    fun aTapCarriesTheElementsExpectAndAnOcrLineIsTappedAtItsCentre() {
        val send = AgentCall.Tap(5, "send", Category.SENDING)
        val pay = AgentCall.Tap(6, "pay", Category.PAYMENTS)

        assertEquals(
            ScreenAction.Tap(tree.key(4), Expect("Send", "android.widget.Button", "com.example.chat", editable = false, password = false, checkable = false), Press.ONCE),
            ActionMapper.screenAction(send, table, press = Press.ONCE),
        )
        assertEquals(ScreenAction.TapAt(200, 2050), ActionMapper.screenAction(pay, table, press = Press.ONCE))
        val proposal = ActionMapper.proposal(send, table, "Chat")
        assertEquals(listOf(ActionKind.TAP, Category.SENDING, tree.key(4), "Chat"), listOf(proposal.kind, proposal.declared, proposal.targetKey, proposal.frontAppLabel))
        val ocr = ActionMapper.proposal(pay, table, "Chat")
        assertEquals(listOf(ActionKind.TAP_AT, Point(200, 2050), "Pay"), listOf(ocr.kind, ocr.point, ocr.pointText))
    }

    @Test
    fun typingUsesTheRevealedTextAndAScrollWithoutATargetTakesTheLargestList() {
        val typed = ActionMapper.screenAction(AgentCall.TypeText(4, "Code [OTP_1]", "type", Category.TYPING_SCREEN_TEXT), table, "Code 483920", Press.ONCE)
        assertEquals("Code 483920", (typed as ScreenAction.SetText).text)
        assertEquals(tree.key(3), typed.key)
        assertEquals("Code [OTP_1]", ActionMapper.proposal(AgentCall.TypeText(4, "Code [OTP_1]", "type", Category.TYPING_SCREEN_TEXT), table, "Chat").text)

        val scroll = ActionMapper.screenAction(AgentCall.Scroll(Direction.DOWN, null, "more", Category.READING), table, press = Press.VERIFIED)
        assertEquals(tree.key(1), (scroll as ScreenAction.Scroll).key)
        assertEquals("Chats", scroll.expect?.label)
        val empty = builder.build(ObservationInput(1, TestTree().snapshot(), "Chat")).table
        assertEquals(ScreenAction.Scroll(null, Direction.DOWN, null), ActionMapper.screenAction(AgentCall.Scroll(Direction.DOWN, null, "more", Category.READING), empty, press = Press.VERIFIED))
        assertNull(ActionMapper.screenAction(AgentCall.Wait(2, "load"), table, press = Press.VERIFIED))
    }

    @Test
    fun aPressIsOnceFromTypingScreenTextAndAfterAConfirmation() {
        assertEquals(
            listOf(Press.VERIFIED, Press.VERIFIED, Press.VERIFIED, Press.ONCE, Press.ONCE, Press.ONCE),
            listOf(
                ActionMapper.press(Category.READING, confirmed = false),
                ActionMapper.press(Category.TYPING_REQUEST, confirmed = false),
                ActionMapper.press(Category.ORDINARY_SETTINGS, confirmed = false),
                ActionMapper.press(Category.TYPING_SCREEN_TEXT, confirmed = false),
                ActionMapper.press(Category.SENDING, confirmed = false),
                ActionMapper.press(Category.READING, confirmed = true),
            ),
        )
    }

    @Test
    fun aSidewaysSwipeOnAListRowIsRefused() {
        val snapshot = tree.snapshot()
        fun refusal(direction: Direction, target: Int?) = ActionMapper.swipeRefusal(AgentCall.Swipe(direction, target, "r", Category.READING), table, snapshot)
        val plain = TestTree().apply { add(500, "Next photo", clickable = true) }

        assertEquals(ActionMapper.SWIPE_REFUSAL, refusal(Direction.LEFT, 3))
        assertEquals(ActionMapper.SWIPE_REFUSAL, refusal(Direction.RIGHT, null))
        assertNull(refusal(Direction.LEFT, 1))
        assertNull(refusal(Direction.UP, 3))
        assertNull(ActionMapper.swipeRefusal(AgentCall.Swipe(Direction.LEFT, null, "r", Category.READING), table, plain.snapshot()))
    }

    @Test
    fun dataToolsRunOnThePortWithTheirDays() = runTest {
        val port = RecordingData()
        val today = LocalDate.of(2026, 11, 20)

        ActionMapper.runData(AgentCall.FindMedia("yesterday", null, MediaKinds.PHOTOS), port, today)
        ActionMapper.runData(AgentCall.ShareMedia("telegram", listOf("m1", "m2")), port, today, sharePackage = "org.telegram.messenger")
        ActionMapper.runData(AgentCall.CreateEvent("Dentist", "2026-11-21T10:00", null, null), port, today)
        val refused = ActionMapper.runData(AgentCall.ShareMedia("unknown", listOf("m1")), port, today)

        assertEquals(listOf("findMedia 2026-11-19 2026-11-19 PHOTOS", "shareMedia org.telegram.messenger 2", "createEvent 2026-11-21T10:00"), port.calls)
        assertEquals(DataResult.Failed("Kestrel can't share with that app."), refused)
        assertNull(ActionMapper.runData(AgentCall.Say("Hi"), port, today))
        val share = ActionMapper.proposal(AgentCall.ShareMedia("telegram", listOf("m1")), table, "Gallery", "org.telegram.messenger", "Telegram")
        assertEquals(listOf(ActionKind.SHARE_MEDIA, "org.telegram.messenger", "Telegram"), listOf(share.kind, share.receivingPackage, share.receivingAppLabel))
        assertEquals(ActionKind.READ_DATA, ActionMapper.proposal(AgentCall.ListSms(null, 5), table, "Chat").kind)
        assertEquals(ActionKind.SETTINGS_DATA, ActionMapper.proposal(AgentCall.SetTimer(60, null), table, "Chat").kind)
        assertEquals("Hi", ActionMapper.proposal(AgentCall.ComposeSms("+381 60 000 0000", "Hi"), table, "Chat").text)
        val link = ActionMapper.proposal(AgentCall.OpenUrl("https://example.com/?q=1", Browser.FIREFOX, "read"), table, "Chat", receivingAppLabel = "Firefox", linkTargetPackage = "org.mozilla.firefox")
        assertEquals(listOf("https://example.com/?q=1", "org.mozilla.firefox", "Firefox"), listOf(link.link, link.linkTargetPackage, link.receivingAppLabel))
        // The A71 runs Telegram's website build (review 3 I2).
        assertEquals(listOf("org.telegram.messenger", "org.telegram.messenger.web"), SharePackages.of(" Telegram "))
        assertEquals(emptyList<String>(), SharePackages.of("Signal"))
    }

    /** Records each call; answers every tool with an empty result. */
    private class RecordingData : DataPort {
        val calls = mutableListOf<String>()
        private val ok = DataResult.Ok(emptyList())

        override suspend fun findContact(name: String) = ok.also { calls += "findContact" }
        override suspend fun findMedia(from: LocalDate, until: LocalDate, kinds: MediaKinds) = ok.also { calls += "findMedia $from $until $kinds" }
        override suspend fun shareMedia(packageName: String, items: List<String>) = ok.also { calls += "shareMedia $packageName ${items.size}" }
        override suspend fun listEvents(from: LocalDate, to: LocalDate) = ok.also { calls += "listEvents" }
        override suspend fun createEvent(title: String, start: LocalDateTime, end: LocalDateTime?, location: String?) = ok.also { calls += "createEvent $start" }
        override suspend fun setAlarm(hour: Int, minute: Int, label: String?, days: List<DayOfWeek>) = ok.also { calls += "setAlarm" }
        override suspend fun setTimer(seconds: Int, label: String?) = ok.also { calls += "setTimer" }
        override suspend fun listNotifications() = ok.also { calls += "listNotifications" }
        override suspend fun listSms(from: String?, limit: Int) = ok.also { calls += "listSms" }
        override suspend fun composeSms(number: String, text: String) = ok.also { calls += "composeSms" }
        override suspend fun listCalls(limit: Int) = ok.also { calls += "listCalls" }
        override suspend fun getLocation() = ok.also { calls += "getLocation" }
        override suspend fun mediaControl(action: MediaAction) = ok.also { calls += "mediaControl" }
        override suspend fun navigate(app: NavigationApp, to: String, mode: TravelMode?) = ok.also { calls += "navigate" }
        override fun forgetTask() = Unit
    }
}
