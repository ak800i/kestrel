package dev.kestrel.safety

import dev.kestrel.screen.model.ScrollAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test mode's recogniser and its look-alikes (design D18 of sub-project 5; research r63), on snapshots with the real apps' packages. */
class SelfChatsTest {
    private val settings = SafetySettings(ownerEmails = listOf("owner@example.com"), whatsappOwnName = "Aleksa")

    /** A chat with a header of [title] and [subtitle], a list of messages and a Send button; returns the snapshot and the button. */
    private fun chat(packageName: String, title: String, subtitle: String?, message: String = "Hello", extra: TestScreen.() -> Unit = {}): Pair<TestScreen, String> {
        val screen = TestScreen(packageName)
        screen.add(80, null, right = 100, className = "android.widget.ImageView")
        screen.add(80, title, left = 150, height = 50, clickable = false)
        if (subtitle != null) screen.add(140, subtitle, left = 150, height = 40, clickable = false)
        val list = screen.add(300, null, scrollable = true, height = 1400, clickable = false)
        screen.add(400, message, parent = list, clickable = false)
        screen.add(1800, null, editable = true, hint = "Message", text = "Test", right = 850)
        val send = screen.add(1800, "Send", left = 850)
        screen.extra()
        return screen to send
    }

    /** A picker whose list holds [rows], each a name with its texts; returns the snapshot and each row's key. */
    private fun picker(packageName: String, rows: List<List<String>>, searching: Boolean = false): Pair<TestScreen, List<String>> {
        val screen = TestScreen(packageName)
        if (searching) screen.add(100, null, editable = true, text = "Saved", hint = "Search")
        val list = screen.add(300, null, scrollable = true, axis = ScrollAxis.VERTICAL, height = 1600, clickable = false)
        val keys = rows.mapIndexed { index, texts ->
            val row = screen.add(300 + index * 150, null, parent = list, height = 140, text = null)
            texts.forEachIndexed { line, text -> screen.add(310 + index * 150 + line * 40, text, parent = row, clickable = false, height = 40) }
            row
        }
        return screen to keys
    }

    private val telegram = setOf(SelfChats.TELEGRAM)


    @Test
    fun aTabStripAboveThePickerIsNoListOfRows() {
        val screen = TestScreen(SelfChats.TELEGRAM)
        val tabs = screen.add(150, null, className = "androidx.recyclerview.widget.RecyclerView", axis = ScrollAxis.HORIZONTAL, scrollable = true, height = 100, clickable = false)
        screen.add(150, "All chats", parent = tabs, right = 300)
        val list = screen.add(300, null, scrollable = true, axis = ScrollAxis.VERTICAL, height = 1600, clickable = false)
        val row = screen.add(300, null, parent = list, height = 140, text = null)
        screen.add(310, "Saved Messages", parent = row, clickable = false, height = 40)
        screen.add(350, "Forward here to save.", parent = row, clickable = false, height = 40)

        assertTrue(recognise(screen to row))
    }

    private fun recognise(pair: Pair<TestScreen, String>, active: Set<String> = telegram, aliases: Map<String, String> = emptyMap(), flow: ShareFlow? = null, owner: SafetySettings = settings) =
        SelfChats.recognise(pair.first.snapshot(), pair.second, owner, flow, aliases, active)

    @Test
    fun telegramsSavedMessagesNeedsItsSubtitleAndAnActiveRuleOrAnAlias() {
        val saved = chat(SelfChats.TELEGRAM, "Saved Messages", "forward here to save")

        assertTrue(recognise(saved))
        assertFalse(recognise(saved, active = SelfChats.ACTIVE))
        assertTrue(SelfChats.ACTIVE.isEmpty())
        val fixture = chat("dev.kestrel.testapp", "Saved Messages", "forward here to save")
        assertTrue(recognise(fixture, active = emptySet(), aliases = mapOf("dev.kestrel.testapp" to SelfChats.TELEGRAM)))
        // An alias never turns on a rule for a real app's package (review 2 n2).
        val lookAlike = chat("com.example.messenger", "Saved Messages", "forward here to save")
        assertFalse(recognise(lookAlike, active = emptySet(), aliases = mapOf("com.example.messenger" to SelfChats.TELEGRAM)))
        assertFalse(recognise(fixture, active = emptySet()))
        assertTrue(SafetyEnvironment().selfChatAliases.isEmpty())
    }

    @Test
    fun telegramsLookAlikesAreNoSelfChat() {
        assertFalse(recognise(chat(SelfChats.TELEGRAM, "Saved Messages", null)))
        assertFalse(recognise(chat(SelfChats.TELEGRAM, "Saved Messages", "last seen recently")))
        assertFalse(recognise(chat(SelfChats.TELEGRAM, "Saved Messages", "3 members")))
        assertFalse(recognise(chat(SelfChats.TELEGRAM, "Saved Messages", "bot")))
        assertFalse(recognise(chat(SelfChats.TELEGRAM, "Ana", "online", message = "forward here to save")))
    }

    @Test
    fun telegramsPickerRowIsTheFirstWithItsPreviewAndNoSearch() {
        val (screen, rows) = picker(SelfChats.TELEGRAM, listOf(listOf("Saved Messages", "Forward here to save."), listOf("Ana", "See you", "12:30")))
        val (impostor, impostorRows) = picker(SelfChats.TELEGRAM, listOf(listOf("Saved Messages", "Forward here to save.", "12:30")))
        val (second, secondRows) = picker(SelfChats.TELEGRAM, listOf(listOf("Ana", "Hi"), listOf("Saved Messages", "Forward here to save.")))
        val (search, searchRows) = picker(SelfChats.TELEGRAM, listOf(listOf("Saved Messages", "Forward here to save.")), searching = true)

        assertTrue(recognise(screen to rows[0]))
        assertFalse(recognise(screen to rows[1]))
        assertFalse(recognise(impostor to impostorRows[0]))
        assertFalse(recognise(second to secondRows[1]))
        assertFalse(recognise(search to searchRows[0]))
    }

    @Test
    fun aSendAfterTheRowCountsOnlyWhenTheScreenShowsTheSelfChat() {
        val dialog = TestScreen(SelfChats.TELEGRAM)
        dialog.add(900, "Send to Saved Messages?", clickable = false)
        val send = dialog.add(1000, "Send")
        val silent = TestScreen(SelfChats.TELEGRAM)
        val silentSend = silent.add(1000, "Send")
        val saved = ShareFlow(SelfChats.TELEGRAM, listOf("Saved Messages"), ShareCount(1, 0), serial = 1)

        assertTrue(recognise(dialog to send, flow = saved))
        assertFalse(recognise(dialog to send, flow = ShareFlow(SelfChats.TELEGRAM, listOf("Ana"), ShareCount(1, 0), serial = 1)))
        assertFalse(recognise(silent to silentSend, flow = saved))
        // Another chat on the screen is checked by the chat's own rule, never by the flow's selection (review 2 C1), also
        // when its message field is empty and a message in it names the self-chat (review 2-2 New 1).
        assertFalse(recognise(chat(SelfChats.TELEGRAM, "Marko", "online"), flow = saved))
        val marko = TestScreen(SelfChats.TELEGRAM)
        marko.add(80, "Marko", left = 150, height = 50, clickable = false)
        marko.add(140, "online", left = 150, height = 40, clickable = false)
        val list = marko.add(300, null, scrollable = true, height = 1400, clickable = false)
        marko.add(400, "Saved Messages are handy", parent = list, clickable = false)
        marko.add(1800, null, editable = true, hint = "Message", text = "", right = 850)
        val share = marko.add(1700, "Share my phone number")
        assertFalse(recognise(marko to share, flow = saved))
    }

    @Test
    fun whatsappNeedsTheOwnersNameWithYouAndItsSubtitle() {
        val whatsapp = setOf(SelfChats.WHATSAPP)

        assertTrue(recognise(chat(SelfChats.WHATSAPP, "Aleksa (You)", "Message yourself"), active = whatsapp))
        assertFalse(recognise(chat(SelfChats.WHATSAPP, "Aleksa (You)", null), active = whatsapp))
        assertFalse(recognise(chat(SelfChats.WHATSAPP, "Aleksa (You)", "Message yourself"), active = whatsapp, owner = settings.copy(whatsappOwnName = null)))
    }

    @Test
    fun vibersNotesNeedTheNameAndASignAndTheRuleIsNotActive() {
        val viber = setOf(SelfChats.VIBER)
        val notes = chat(SelfChats.VIBER, "My Notes", null) { add(80, null, left = 850, description = "Reminders list", text = null) }

        assertTrue(recognise(notes, active = viber))
        assertFalse(recognise(chat(SelfChats.VIBER, "My Notes", null), active = viber))
        assertFalse(recognise(notes))
        assertFalse(SelfChats.VIBER in SelfChats.ACTIVE)
    }

    @Test
    fun anEmailGoesOnlyToTheOwnersAddresses() {
        fun mail(vararg fields: Pair<String, String>): Pair<TestScreen, String> {
            val screen = TestScreen(SelfChats.GMAIL)
            fields.forEachIndexed { index, (id, text) -> screen.add(200 + index * 100, null, id = id, editable = true, text = text) }
            return screen to screen.add(80, "Send", left = 850)
        }
        val gmail = setOf(SelfChats.GMAIL)

        assertTrue(recognise(mail("to" to "owner@example.com"), active = gmail))
        assertTrue(recognise(mail("to" to "Owner@Example.com"), active = gmail))
        assertFalse(recognise(mail("to" to "owner@example.com", "cc" to "marko@example.com"), active = gmail))
        assertFalse(recognise(mail("to" to "owner@example.com, Ana"), active = gmail))
        assertFalse(recognise(mail("to" to ""), active = gmail))
        assertFalse(recognise(mail("to" to "owner@example.com"), active = gmail, owner = settings.copy(ownerEmails = emptyList())))
    }
}
