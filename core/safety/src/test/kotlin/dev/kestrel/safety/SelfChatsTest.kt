package dev.kestrel.safety

import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScrollAxis
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

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

    private fun recognise(pair: Pair<TestScreen, String?>, active: Set<String> = SelfChats.ACTIVE, aliases: Map<String, String> = emptyMap(), flow: ShareFlow? = null, owner: SafetySettings = settings) =
        SelfChats.recognise(pair.first.snapshot(), pair.second, owner, flow, aliases, active)

    /**
     * A Telegram chat in the structure that sitting 1 recorded from Telegram 13.3.1's Saved Messages on the A71, in [window]
     * (the A71's full screen unless the test gives a split screen, a pop-up window or a bubble), with [inset] pixels of the
     * status bar at its top: the messages behind everything; the toolbar with Back, the title block, whose own description
     * is the title and, for any other chat, the line below it ([description] unless the test gives another), the title and
     * that line, and two icons; a pinned message just below the toolbar; the message field and Send. The toolbar is 147
     * pixels tall below the inset in every window, because Telegram sizes it in dp. Returns the snapshot and Send.
     */
    private fun telegram(
        title: String,
        subtitle: String? = null,
        packageName: String = SelfChats.TELEGRAM_WEB,
        window: IntRect = FULL_SCREEN,
        inset: Int = STATUS_BAR,
        description: String? = listOfNotNull(title, subtitle).joinToString(" "),
        extra: TestScreen.(bar: String) -> Unit = {},
    ): Pair<TestScreen, String?> {
        val top = window.top
        val left = window.left
        val right = window.right
        val screen = TestScreen(packageName, bounds = window)
        val messages = screen.add(top, null, className = RECYCLER, scrollable = true, axis = ScrollAxis.VERTICAL, left = left, right = right, height = window.height, clickable = false)
        screen.add(top + inset + 400, "Note", parent = messages, className = VIEW_GROUP, left = left, right = right, height = 200, clickable = false)
        val bar = screen.add(top, null, className = FRAME, left = left, right = right, height = inset + 147)
        screen.add(top + inset, null, parent = bar, className = IMAGE, left = left + 6, right = left + 148, height = 147, description = "Go back")
        val block = screen.add(top, null, parent = bar, className = FRAME, left = left + 137, right = left + 700, height = inset + 147, description = description)
        if (subtitle == null) {
            screen.add(top + inset + 31, title, parent = block, className = TEXT, left = left + 289, right = left + 680, height = 86, clickable = false)
        } else {
            screen.add(top + inset + 14, title, parent = block, className = TEXT, left = left + 289, right = left + 680, height = 60, clickable = false)
            screen.add(top + inset + 74, subtitle, parent = block, className = TEXT, left = left + 289, right = left + 680, height = 48, clickable = false)
        }
        screen.add(top + inset, null, parent = bar, className = IMAGE_BUTTON, left = right - 223, right = right - 111, height = 147, description = "Search")
        screen.add(top + inset, null, parent = bar, className = IMAGE_BUTTON, left = right - 139, right = right - 27, height = 147, description = "More options")
        val pinned = screen.add(top + inset + 152, null, className = FRAME, left = left + 19, right = right - 19, height = 126)
        screen.add(top + inset + 172, "Pinned Message", parent = pinned, className = TEXT, left = left + 80, right = right - 135, height = 48, clickable = false)
        screen.add(top + inset + 219, "Note", parent = pinned, className = TEXT, left = left + 80, right = right - 135, height = 48, clickable = false)
        screen.add(window.bottom - 179, null, className = IMAGE, left = left + 27, right = left + 143, height = 116, description = "Emoji")
        screen.add(window.bottom - 174, null, className = EDIT, editable = true, hint = "Message", text = "Test", left = left + 151, right = right - 141, height = 107)
        val send = screen.add(window.bottom - 179, null, className = VIEW, left = right - 282, right = right - 19, height = 116, description = "Send")
        screen.extra(bar)
        return screen to send
    }

    @Test
    fun telegramsSavedMessagesIsTheRecordedToolbarInBothPackagesInEveryWindowAndItsRuleIsActive() {
        assertEquals(setOf(SelfChats.TELEGRAM, SelfChats.TELEGRAM_WEB), SelfChats.ACTIVE)
        assertTrue(recognise(telegram("Saved Messages")))
        assertTrue(recognise(telegram("Saved Messages", packageName = SelfChats.TELEGRAM)))
        // The check without a target, as the hook selfchat-check makes it.
        assertTrue(recognise(telegram("Saved Messages").first to null))
        assertFalse(recognise(telegram("Saved Messages", packageName = "com.example.messenger")))
        for ((window, inset) in WINDOWS) assertTrue("$window", recognise(telegram("Saved Messages", window = window, inset = inset)))
    }

    @Test
    fun anAliasAppliesTelegramsRuleOnlyToTheFixtures() {
        val fixture = telegram("Saved Messages", packageName = "dev.kestrel.testapp")

        assertTrue(recognise(fixture, active = emptySet(), aliases = mapOf("dev.kestrel.testapp" to SelfChats.TELEGRAM)))
        // An alias never turns on a rule for a real app's package (review 2 n2).
        val lookAlike = telegram("Saved Messages", packageName = "com.example.messenger")
        assertFalse(recognise(lookAlike, active = emptySet(), aliases = mapOf("com.example.messenger" to SelfChats.TELEGRAM)))
        assertFalse(recognise(fixture, active = emptySet()))
        assertTrue(SafetyEnvironment().selfChatAliases.isEmpty())
    }

    @Test
    fun aChatWithAPersonAGroupOrAChannelIsNoSelfChatInAnyWindow() {
        for ((window, inset) in WINDOWS) {
            assertFalse("$window", recognise(telegram("Ana", "last seen recently", window = window, inset = inset)))
            assertFalse("$window", recognise(telegram("Ana", "online", window = window, inset = inset)))
            assertFalse("$window", recognise(telegram("Close friends", "12 members", window = window, inset = inset)))
            assertFalse("$window", recognise(telegram("News", "1,024 subscribers", window = window, inset = inset)))
            assertFalse("$window", recognise(telegram("Helper", "bot", window = window, inset = inset)))
        }
        // A person's chat with a message that names the self-chat.
        assertFalse(recognise(telegram("Ana", "online") { add(1300, "Saved Messages", parent = "${SelfChats.TELEGRAM_WEB}|0|", clickable = false) }))
    }

    @Test
    fun aContactGroupChannelOrBotNamedSavedMessagesIsNoSelfChatInAnyWindow() {
        // Telegram shows a line below the name of every other chat, which its owner cannot remove, and adds it to the title
        // block's description; in a split screen, a pop-up window or a bubble, the toolbar keeps its size in dp.
        val lines = listOf("last seen recently", "last seen a long time ago", "online", "typing…", "3 members", "15 subscribers", "bot", "Saved Messages")
        for ((window, inset) in WINDOWS) {
            for (line in lines) assertFalse("$window $line", recognise(telegram("Saved Messages", line, window = window, inset = inset)))
        }
    }

    @Test
    fun aScreenThatTheRecordingDoesNotMatchIsNoSelfChat() {
        // Research r63's second line, which Telegram 13.3.1 does not show.
        assertFalse(recognise(telegram("Saved Messages", "forward here to save")))
        assertFalse(recognise(chat(SelfChats.TELEGRAM, "Saved Messages", "forward here to save")))
        // Another text in the toolbar, such as a state of the connection in the title's place.
        assertFalse(recognise(telegram("Saved Messages") { add(91, "Connecting…", left = 300, right = 800, height = 60, className = TEXT, clickable = false) }))
        assertFalse(recognise(telegram("Connecting…")))
        for ((window, inset) in WINDOWS) {
            // A line below the title outside its block, or a title block whose description is not the title alone or is missing.
            val outside = telegram("Saved Messages", window = window, inset = inset) { bar ->
                add(window.top + inset + 100, "last seen recently", parent = bar, className = TEXT, left = window.left + 289, right = window.left + 680, height = 48, clickable = false)
            }
            assertFalse("$window", recognise(outside))
            assertFalse("$window", recognise(telegram("Saved Messages", window = window, inset = inset, description = "Saved Messages last seen recently")))
            assertFalse("$window", recognise(telegram("Saved Messages", window = window, inset = inset, description = null)))
            // Two toolbars, as while Telegram moves from one chat to the next, each with a title "Saved Messages".
            val twice = telegram("Saved Messages", window = window, inset = inset) {
                val other = add(window.top, null, className = FRAME, left = window.left, right = window.right, height = inset + 147)
                val block = add(window.top, null, parent = other, className = FRAME, left = window.left + 137, right = window.left + 700, height = inset + 147, description = "Saved Messages")
                add(window.top + inset + 31, "Saved Messages", parent = block, className = TEXT, left = window.left + 289, right = window.left + 680, height = 86, clickable = false)
            }
            assertFalse("$window", recognise(twice))
        }
        // No toolbar at all, or a title below the toolbar's part of the window.
        val low = TestScreen(SelfChats.TELEGRAM_WEB, bounds = IntRect(0, 0, 1080, 2400))
        low.add(300, "Saved Messages", className = TEXT, left = 289, right = 806, height = 86, clickable = false)
        assertFalse(recognise(low to low.add(2221, null, className = VIEW, left = 798, right = 1061, height = 116, description = "Send")))
        // Telegram's chat list and share picker as sitting 1 recorded them: a title of their own and a search field.
        assertFalse(recognise(recordedList("Telegram") to null))
        assertFalse(recognise(recordedList("Select chat") to null))
    }

    @Test
    fun aSearchIsNoSelfChat() {
        // A search in the chat takes the title's place with its field.
        for ((window, inset) in WINDOWS) {
            val search = telegram("Saved Messages", window = window, inset = inset) { bar ->
                add(window.top + inset, null, parent = bar, className = EDIT, editable = true, hint = "Search", left = window.left + 150, right = window.left + 700, height = 147)
            }
            assertFalse("$window", recognise(search))
        }
        // The search page of the chat list or the share picker, as the investigation recorded it: the row "Saved Messages",
        // named by itself, with its check box, whether or not it lies in a list.
        for (inList in listOf(false, true)) {
            val page = TestScreen(SelfChats.TELEGRAM_WEB, bounds = IntRect(0, 0, 1080, 2400))
            page.add(0, null, className = FRAME, right = 1080, height = 238)
            page.add(129, null, className = EDIT, editable = true, hint = "Search", left = 30, right = 1050, height = 104)
            val list = if (inList) page.add(406, null, className = RECYCLER, scrollable = true, axis = ScrollAxis.VERTICAL, right = 1080, height = 1800, clickable = false) else null
            page.add(464, "Recent", parent = list, className = TEXT, left = 42, right = 1038, height = 84, clickable = false)
            val row = page.add(548, null, parent = list, className = VIEW_GROUP, right = 1080, height = 158, clickable = false, description = "Saved Messages")
            page.add(643, null, parent = row, className = SWITCH, checkable = true, left = 111, right = 174, height = 63, clickable = false)

            assertFalse("inList=$inList", recognise(page to row))
            assertFalse("inList=$inList", recognise(page to null))
        }
    }

    @Test
    fun noRowOfTelegramsPickerCounts() {
        // The picker after a tap selected a row, as the investigation recorded it: a checked row without a name, a field
        // for a comment and Send. A row that named itself "Saved Messages" would not count either.
        for (named in listOf(false, true)) {
            val picker = recordedList("Select chat")
            val list = picker.add(0, null, className = RECYCLER, scrollable = true, axis = ScrollAxis.VERTICAL, right = 1080, height = 2400, clickable = false)
            val selected = picker.add(482, null, parent = list, className = CHECKBOX, checkable = true, checked = true, right = 1080, height = 185, clickable = false)
            picker.add(593, null, parent = selected, className = SWITCH, checkable = true, checked = true, left = 119, right = 182, height = 63, clickable = false)
            if (named) picker.add(500, "Saved Messages", parent = selected, className = TEXT, left = 220, right = 900, height = 60, clickable = false)
            picker.add(2226, null, className = EDIT, editable = true, hint = "Add a caption", left = 151, right = 886, height = 107)
            val send = picker.add(2229, "Send", left = 791, right = 1080, height = 132)

            assertFalse("named=$named", recognise(picker to send))
            assertFalse("named=$named", recognise(picker to selected))
        }
        // Research r63's first row with its preview, which Telegram 13.3.1's picker does not show.
        val (screen, rows) = picker(SelfChats.TELEGRAM, listOf(listOf("Saved Messages", "Forward here to save."), listOf("Ana", "See you", "12:30")))
        assertFalse(recognise(screen to rows[0]))
        assertFalse(recognise(screen to rows[1]))
    }

    /**
     * Telegram's picker or chat list in the structure that sitting 1 recorded: the toolbar with [title] and Back, the folder
     * tabs and the search field; its rows are no elements until one is selected.
     */
    private fun recordedList(title: String): TestScreen {
        val screen = TestScreen(SelfChats.TELEGRAM_WEB, bounds = IntRect(0, 0, 1080, 2400))
        val bar = screen.add(0, null, className = FRAME, right = 1080, height = 238)
        screen.add(112, title, parent = bar, className = TEXT, left = 189, right = 1038, height = 83, clickable = false)
        screen.add(91, null, parent = bar, className = IMAGE, right = 142, height = 147, description = "Go back")
        screen.add(372, "All chats", className = VIEW, left = 42, right = 555, height = 95, clickable = false)
        screen.add(244, null, className = EDIT, editable = true, hint = "Search", left = 30, right = 1050, height = 104)
        return screen
    }

    @Test
    fun aTabStripAboveThePickerIsNoListOfRows() {
        val screen = TestScreen(SelfChats.VIBER)
        val tabs = screen.add(150, null, className = RECYCLER, axis = ScrollAxis.HORIZONTAL, scrollable = true, height = 100, clickable = false)
        screen.add(150, "All chats", parent = tabs, right = 300)
        val list = screen.add(300, null, scrollable = true, axis = ScrollAxis.VERTICAL, height = 1600, clickable = false)
        val row = screen.add(300, null, parent = list, height = 140, text = null)
        screen.add(310, "My Notes", parent = row, clickable = false, height = 40)

        assertTrue(recognise(screen to row, active = setOf(SelfChats.VIBER)))
    }

    @Test
    fun aSendAfterTheRowCountsOnlyWhenTheScreenShowsTheSelfChat() {
        val whatsapp = setOf(SelfChats.WHATSAPP)
        val dialog = TestScreen(SelfChats.WHATSAPP)
        dialog.add(900, "Send to Aleksa (You)?", clickable = false)
        val send = dialog.add(1000, "Send")
        val silent = TestScreen(SelfChats.WHATSAPP)
        val silentSend = silent.add(1000, "Send")
        val own = ShareFlow(SelfChats.WHATSAPP, listOf("Aleksa (You)"), ShareCount(1, 0), serial = 1)

        assertTrue(recognise(dialog to send, active = whatsapp, flow = own))
        assertFalse(recognise(dialog to send, active = whatsapp, flow = ShareFlow(SelfChats.WHATSAPP, listOf("Ana"), ShareCount(1, 0), serial = 1)))
        assertFalse(recognise(silent to silentSend, active = whatsapp, flow = own))
        // No recording shows such a dialog in Telegram, so there the flow's selection never counts.
        val telegramDialog = TestScreen(SelfChats.TELEGRAM)
        telegramDialog.add(900, "Send to Saved Messages?", clickable = false)
        val saved = ShareFlow(SelfChats.TELEGRAM, listOf("Saved Messages"), ShareCount(1, 0), serial = 1)
        assertFalse(recognise(telegramDialog to telegramDialog.add(1000, "Send"), flow = saved))
        // Another chat on the screen is checked by the chat's own rule, never by the flow's selection (review 2 C1), also
        // when its message field is empty and a message in it names the self-chat (review 2-2 New 1).
        assertFalse(recognise(telegram("Marko", "online"), flow = saved))
        val marko = TestScreen(SelfChats.TELEGRAM)
        marko.add(80, "Marko", left = 150, height = 50, clickable = false)
        marko.add(140, "online", left = 150, height = 40, clickable = false)
        val list = marko.add(300, null, scrollable = true, height = 1400, clickable = false)
        marko.add(400, "Saved Messages are handy", parent = list, clickable = false)
        marko.add(1800, null, editable = true, hint = "Message", text = "", right = 850)
        val share = marko.add(1700, "Share my phone number")
        assertFalse(recognise(marko to share, flow = saved))
    }

    /**
     * The trees that the owner's sittings recorded on the A71 (`corpus/owner/trees`, which git ignores; skipped when they
     * are missing): Saved Messages passes, and no element of the chat list or the share picker does.
     */
    @Test
    fun theRecordedTreesOfTelegramPassOnlyInSavedMessages() {
        val folders = repositoryRoot()?.resolve("corpus/owner/trees")?.listFiles()?.filter { it.isDirectory }.orEmpty().sortedBy { it.name }
        val saved = folders.mapNotNull { File(it, "a71-telegram-saved.json").takeIf(File::isFile) }.lastOrNull()
        assumeTrue("no recorded tree of Telegram's Saved Messages", saved != null)

        assertTrue(SelfChats.recognise(recorded(saved!!), null, settings))
        val others = folders.flatMap { folder -> listOf("a71-telegram-chats.json", "a71-telegram-share.json").map { File(folder, it) } }.filter(File::isFile)
        for (file in others) {
            val snapshot = recorded(file)
            assertFalse(file.path, SelfChats.recognise(snapshot, null, settings))
            for (element in snapshot.elements) assertFalse(file.path, SelfChats.recognise(snapshot, element.key, settings))
        }
    }

    private fun repositoryRoot(): File? =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").isFile }

    /**
     * A snapshot from a tree of the hook agent-tree, which keeps a label only where it is safe and else its length: a
     * TextView's label becomes its text, an EditText's its hint and any other element's its description, and a length
     * becomes as many x's.
     */
    private fun recorded(file: File): ScreenSnapshot {
        val tree = Json.parseToJsonElement(file.readText()).jsonObject
        fun JsonObject.flag(name: String) = getValue(name).jsonPrimitive.boolean
        fun JsonObject.bounds() = getValue("bounds").jsonArray.map { it.jsonPrimitive.int }.let { IntRect(it[0], it[1], it[2], it[3]) }
        val windows = tree.getValue("windows").jsonArray.map { it.jsonObject }.mapIndexed { index, window ->
            WindowSummary(
                window.getValue("id").jsonPrimitive.int, WindowKind.valueOf(window.getValue("kind").jsonPrimitive.content),
                window["packageName"]?.jsonPrimitive?.contentOrNull, null, window.bounds(), layer = index, active = window.flag("active"),
            )
        }
        val elements = tree.getValue("elements").jsonArray.map { it.jsonObject }.mapIndexed { index, element ->
            val className = element["className"]?.jsonPrimitive?.contentOrNull
            val label = element["label"]?.jsonPrimitive?.contentOrNull ?: element["labelLength"]?.jsonPrimitive?.intOrNull?.let { "x".repeat(it) }
            val editable = element.flag("editable")
            val text = label.takeIf { className.orEmpty().endsWith("TextView") }
            ScreenElement(
                key = "$index", windowId = element.getValue("windowId").jsonPrimitive.int, parent = element["parent"]?.jsonPrimitive?.intOrNull,
                packageName = element["packageName"]?.jsonPrimitive?.contentOrNull, className = className, text = text,
                contentDescription = label.takeIf { text == null && !editable }, hint = label.takeIf { editable }, label = label,
                bounds = element.bounds(), actions = if (element.flag("scrollable")) setOf(ElementAction.SCROLL_FORWARD) else emptySet(),
                clickable = element.flag("clickable"), editable = editable, checkable = element.flag("checkable"),
                checked = element.flag("checked"), scrollable = element.flag("scrollable"), password = element.flag("password"),
            )
        }
        return ScreenSnapshot(0, windows, elements)
    }

    private companion object {
        val FULL_SCREEN = IntRect(0, 0, 1080, 2400)
        const val STATUS_BAR = 91

        /**
         * The A71's full screen and the windows in which a share of the window's height would cut the toolbar between the
         * title and the line below it (fix-5c-selfchats-review I1): the upper app of a split screen with the status bar, and a
         * pop-up window and a bubble without it.
         */
        val WINDOWS = listOf(FULL_SCREEN to STATUS_BAR, IntRect(0, 0, 1080, 1600) to STATUS_BAR, IntRect(0, 700, 1080, 1700) to 0, IntRect(90, 1200, 990, 2000) to 0)

        const val RECYCLER = "androidx.recyclerview.widget.RecyclerView"
        const val VIEW_GROUP = "android.view.ViewGroup"
        const val VIEW = "android.view.View"
        const val FRAME = "android.widget.FrameLayout"
        const val TEXT = "android.widget.TextView"
        const val EDIT = "android.widget.EditText"
        const val IMAGE = "android.widget.ImageView"
        const val IMAGE_BUTTON = "android.widget.ImageButton"
        const val SWITCH = "android.widget.Switch"
        const val CHECKBOX = "android.widget.CheckBox"
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
