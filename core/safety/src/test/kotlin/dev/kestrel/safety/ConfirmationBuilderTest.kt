package dev.kestrel.safety

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScrollAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design D14 of sub-project 5: questions built from the screen, and hand-overs when the code cannot fill them. */
class ConfirmationBuilderTest {
    private val task = TaskSafety(TaskAllowance(setOf(Category.SENDING)))

    private fun send(key: String, label: String? = "Telegram") = Proposal("tap", ActionKind.TAP, Category.SENDING, targetKey = key, frontAppLabel = label)

    private fun chat(title: String?, message: String): Pair<TestScreen, String> {
        val screen = TestScreen(SelfChats.TELEGRAM)
        if (title != null) screen.add(80, title, left = 150, height = 50, clickable = false)
        screen.add(1800, null, editable = true, hint = "Message", text = message, right = 850)
        return screen to screen.add(1800, null, left = 850, className = "android.widget.ImageButton")
    }

    private fun confirmation(decision: Decision): Confirmation = (decision as Decision.Confirm).confirmation

    @Test
    fun aChatsSendNamesTheChatTheAppAndTheText() {
        val (screen, button) = chat("Ana Petrović", "I'm 10 minutes late")

        val asked = confirmation(ConfirmationBuilder.build(send(button), screen.snapshot(), Category.SENDING, task))

        assertEquals("Send to Ana Petrović on Telegram: 'I'm 10 minutes late'? Say confirm or cancel.", asked.question)
        assertEquals(listOf("Ana Petrović"), asked.recipients)
        assertTrue(asked.sameAs(confirmation(ConfirmationBuilder.build(send(button), screen.snapshot(), Category.SENDING, task))))
        val changed = chat("Ana Petrović", "I'm 20 minutes late")
        assertEquals(false, asked.sameAs(confirmation(ConfirmationBuilder.build(send(changed.second), changed.first.snapshot(), Category.SENDING, task))))
    }

    @Test
    fun withoutATitleOrWithTheAppsNameAsTitleItHandsOver() {
        val (untitled, button) = chat(null, "Hello")
        val (named, other) = chat("Telegram", "Hello")
        val (empty, third) = chat("Ana", "")

        for ((screen, key) in listOf(untitled to button, named to other, empty to third)) {
            assertEquals(
                Decision.HandOver(HandOver.of(HandOverKind.RECIPIENT)),
                ConfirmationBuilder.build(send(key), screen.snapshot(), Category.SENDING, task),
            )
        }
    }

    private val flow = task.copy(shareFlow = ShareFlow(SelfChats.TELEGRAM, listOf("Ana"), ShareCount(3, 0), serial = 1))

    @Test
    fun aShareFlowNamesTheRowsThatTheScreenShowsNeverAnEarlierSelection() {
        // A list that does not scroll; one row with a checked box and no label of its own; one row named by all its texts.
        val screen = TestScreen(SelfChats.TELEGRAM)
        val list = screen.add(300, null, className = "androidx.recyclerview.widget.RecyclerView", height = 600, clickable = false)
        val friends = screen.add(300, null, parent = list, height = 140)
        screen.add(310, "Close friends", parent = friends, clickable = false, height = 40)
        screen.add(310, null, parent = friends, className = "android.widget.CheckBox", checkable = true, checked = true, left = 900, height = 40)
        val school = screen.add(450, "Back to school See you 12:30", parent = list, text = null, height = 140)
        screen.add(460, "Back to school", parent = school, clickable = false, height = 40)
        val nameless = screen.add(600, null, parent = list, text = null, height = 140)

        val asked = confirmation(ConfirmationBuilder.build(send(school), screen.snapshot(), Category.SENDING, flow))

        assertEquals("Send 3 photos to Back to school and Close friends on Telegram? Say confirm or cancel.", asked.question)
        assertEquals(listOf("Back to school", "Close friends"), asked.recipients)
        assertEquals(Decision.HandOver(HandOver.of(HandOverKind.RECIPIENT)), ConfirmationBuilder.build(send(nameless), screen.snapshot(), Category.SENDING, flow))
        // Android's share sheet: its target is an app or a direct-share chat.
        val sheet = TestScreen("com.android.intentresolver")
        val grid = sheet.add(1200, null, className = "androidx.recyclerview.widget.RecyclerView", height = 600, clickable = false)
        val telegram = sheet.add(1200, "Telegram", parent = grid, right = 250)
        assertEquals("Share 3 photos with Telegram? Say confirm or cancel.", confirmation(ConfirmationBuilder.build(send(telegram), sheet.snapshot(), Category.SENDING, flow)).question)
    }

    @Test
    fun aTapAtOnARowNamesThatRowAsATapOnItDoes() {
        val screen = TestScreen(SelfChats.TELEGRAM)
        val list = screen.add(300, null, className = "androidx.recyclerview.widget.RecyclerView", height = 600, clickable = false)
        val friends = screen.add(300, "Close friends", parent = list, height = 140)
        screen.add(310, null, parent = friends, className = "android.widget.CheckBox", checkable = true, checked = true, left = 900, height = 40)
        screen.add(450, "Marko", parent = list, height = 140)
        val atMarko = Proposal("tap", ActionKind.TAP_AT, Category.SENDING, point = Point(500, 500), frontAppLabel = "Telegram")

        val asked = confirmation(ConfirmationBuilder.build(atMarko, screen.snapshot(), Category.SENDING, flow))

        assertEquals("Send 3 photos to Marko and Close friends on Telegram? Say confirm or cancel.", asked.question)
        assertEquals(listOf("Marko", "Close friends"), asked.recipients)
    }

    @Test
    fun aShareFlowAsksAboutTheChatOrTheDialogOnTheScreen() {
        val (marko, markoSend) = chat("Marko", "Hi")
        val (ana, anaSend) = chat("Ana", "Hi")
        val dialog = TestScreen(SelfChats.TELEGRAM)
        dialog.add(900, "Send to Ana?", clickable = false)
        val dialogSend = dialog.add(1000, "Send")
        val silent = TestScreen(SelfChats.TELEGRAM)
        val silentSend = silent.add(1000, "Send")

        // A chat that the flow did not select: Kestrel cannot tell where the tap sends (review 2 C1).
        assertEquals(Decision.HandOver(HandOver.of(HandOverKind.RECIPIENT)), ConfirmationBuilder.build(send(markoSend), marko.snapshot(), Category.SENDING, flow))
        assertEquals("Send 3 photos to Ana on Telegram? Say confirm or cancel.", confirmation(ConfirmationBuilder.build(send(anaSend), ana.snapshot(), Category.SENDING, flow)).question)
        assertEquals(listOf("Ana"), confirmation(ConfirmationBuilder.build(send(dialogSend), dialog.snapshot(), Category.SENDING, flow)).recipients)
        assertEquals(Decision.HandOver(HandOver.of(HandOverKind.RECIPIENT)), ConfirmationBuilder.build(send(silentSend), silent.snapshot(), Category.SENDING, flow))
        // A chat whose message field is empty is still a chat: a message in its list never names a recipient (review 2-2 New 1).
        val (empty, _) = chat("Marko", "")
        val list = empty.add(300, null, scrollable = true, axis = ScrollAxis.VERTICAL, height = 1400, clickable = false)
        empty.add(400, "Say hi to Ana", parent = list, clickable = false)
        val bar = empty.add(1700, "Share my phone number")
        assertEquals(Decision.HandOver(HandOver.of(HandOverKind.RECIPIENT)), ConfirmationBuilder.build(send(bar), empty.snapshot(), Category.SENDING, flow))
        assertEquals(
            "Share this from Telegram? It may go to Marko at once. Say confirm or cancel.",
            confirmation(ConfirmationBuilder.build(send(bar), empty.snapshot(), Category.SENDING, task)).question,
        )
    }

    @Test
    fun aVaultValueThatTheTaskTypedIsNamedByItsKind() {
        val (screen, button) = chat("Ana Petrović", "My code is 483 920")
        val typed = task.withSecret("483920", "your one-time code")

        val asked = confirmation(ConfirmationBuilder.build(send(button), screen.snapshot(), Category.SENDING, typed))

        assertEquals("Send to Ana Petrović on Telegram: 'My code is your one-time code'? Say confirm or cancel.", asked.question)
        assertTrue(asked.spoken, "483" !in asked.spoken)
    }

    @Test
    fun aVaultValueThatTheAppShowsWithNoBreakNarrowFigureOrThinSpacesIsNamedByItsKind() {
        val (screen, button) = chat("Ana Petrović", "A 483\u00A0920 B 483\u202F920 C 483\u2009920 D 483\u2007920")
        val typed = task.withSecret("483920", "your one-time code")

        val asked = confirmation(ConfirmationBuilder.build(send(button), screen.snapshot(), Category.SENDING, typed))

        assertEquals(
            "Send to Ana Petrović on Telegram: 'A your one-time code B your one-time code C your one-time code D your one-time code'? Say confirm or cancel.",
            asked.question,
        )
        val (card, cardButton) = chat("Ana Petrović", "4111\u00A01111\u00A01111\u00A01111")
        val withCard = task.withSecret("4111 1111 1111 1111", "your card number")
        assertEquals("your card number", confirmation(ConfirmationBuilder.build(send(cardButton), card.snapshot(), Category.SENDING, withCard)).content)
    }

    @Test
    fun aTapAtIsConfirmedWithTheElementUnderItsPoint() {
        fun list(shift: Int): TestScreen = TestScreen("com.example.notes").apply {
            add(80, "Inbox", id = "toolbar_title", clickable = false, height = 50)
            val rows = add(300, null, className = "androidx.recyclerview.widget.RecyclerView", height = 1200, clickable = false)
            for (row in 0..3) add(300 + shift + row * 140, null, id = "note_$row", parent = rows, className = "android.view.ViewGroup", height = 140)
        }
        val delete = Proposal("tap", ActionKind.TAP_AT, Category.DELETING, point = Point(900, 500), pointText = "Delete", frontAppLabel = "Notes")
        val before = list(0)

        val asked = confirmation(ConfirmationBuilder.build(delete, before.snapshot(), Category.DELETING, task))

        assertEquals("com.example.notes|3|", asked.targetKey)
        assertTrue(asked.sameAs(confirmation(ConfirmationBuilder.build(delete, list(0).snapshot(), Category.DELETING, task))))
        // The list moved by one row under the point: the same question, but another row.
        val moved = confirmation(ConfirmationBuilder.build(delete, list(140).snapshot(), Category.DELETING, task))
        assertEquals(asked.question, moved.question)
        assertEquals(false, asked.sameAs(moved))
    }

    @Test
    fun inSplitScreenTheQuestionNamesTheAppOfTheWindowThatHoldsTheActionNeverTheFrontApps() {
        fun split(title: Boolean): Pair<dev.kestrel.screen.model.ScreenSnapshot, String> {
            val screen = TestScreen("com.android.chrome", bounds = IntRect(0, 0, 1000, 1000))
            screen.add(100, "Search or type URL", clickable = false)
            screen.window(2, "com.whatsapp", IntRect(0, 1000, 1000, 2000), layer = 1)
            if (title) screen.add(1050, "Ana", id = "chat_title", clickable = false, height = 50, window = 2)
            screen.add(1800, null, editable = true, hint = "Message", text = "I'm late", right = 850, window = 2)
            val key = screen.add(1800, null, left = 850, className = "android.widget.ImageButton", window = 2)
            val snapshot = screen.snapshot().let { s -> s.copy(windows = s.windows.map { if (it.id == 2) it.copy(title = "WhatsApp") else it }) }
            return snapshot to key
        }
        val (untitled, untitledSend) = split(title = false)
        val (titled, titledSend) = split(title = true)

        // The other window's title is never read as a recipient by the front app's label.
        assertEquals(Decision.HandOver(HandOver.of(HandOverKind.RECIPIENT)), ConfirmationBuilder.build(send(untitledSend, "Chrome"), untitled, Category.SENDING, task))
        assertEquals(
            "Send to Ana on whatsapp: 'I'm late'? Say confirm or cancel.",
            confirmation(ConfirmationBuilder.build(send(titledSend, "Chrome"), titled, Category.SENDING, task)).question,
        )
    }

    @Test
    fun aForwardedEmailIsAskedByItsRecipientFieldsAlsoInAShareFlow() {
        val compose = TestScreen(SelfChats.GMAIL)
        val button = compose.add(80, "Send", left = 850)
        compose.add(200, null, id = "to", editable = true, text = "marko@example.com")
        compose.add(400, null, id = "subject", editable = true, text = "Fwd: Plan")
        compose.add(500, null, id = "body", editable = true, text = "See below.")
        compose.add(700, "---------- Forwarded message ---------", clickable = false)
        val inFlow = task.copy(shareFlow = ShareFlow(SelfChats.GMAIL, emptyList(), null, serial = 1))

        for (state in listOf(task, inFlow)) {
            assertEquals(
                "Send an e-mail to marko@example.com, subject 'Fwd: Plan' with the forwarded message: 'See below.'? Say confirm or cancel.",
                confirmation(ConfirmationBuilder.build(send(button, "Gmail"), compose.snapshot(), Category.SENDING, state)).question,
            )
        }
    }

    @Test
    fun aShareButtonInAChatAndADeleteBesideACheckedSwitch() {
        val (stranger, _) = chat("Stranger", "Hi")
        val share = stranger.add(200, "Share my phone number")
        assertEquals(
            "Share this from Telegram? It may go to Stranger at once. Say confirm or cancel.",
            confirmation(ConfirmationBuilder.build(send(share), stranger.snapshot(), Category.SENDING, task)).question,
        )

        val note = TestScreen("com.google.android.keep")
        val bar = note.add(80, null, id = "toolbar", clickable = false)
        note.add(90, "Shopping list", parent = bar, clickable = false)
        note.add(1700, "Keep me signed in", className = "android.widget.Switch", checkable = true, checked = true)
        val delete = note.add(1800, "Delete")
        assertEquals(
            "Delete 'Shopping list' in Keep? Say confirm or cancel.",
            confirmation(ConfirmationBuilder.build(send(delete, "Keep"), note.snapshot(), Category.DELETING, task)).question,
        )
    }

    @Test
    fun anEmailNamesEveryRecipientTheSubjectAndTheBody() {
        val screen = TestScreen(SelfChats.GMAIL)
        screen.add(200, null, id = "to", editable = true, text = "ana@example.com")
        screen.add(300, null, id = "cc", editable = true, text = "Marko <marko@example.com>")
        screen.add(400, null, id = "subject", editable = true, text = "Plan")
        screen.add(500, null, id = "body", editable = true, text = "See you at five.")
        val sendKey = screen.add(80, "Send", left = 850)

        val asked = confirmation(ConfirmationBuilder.build(send(sendKey, "Gmail"), screen.snapshot(), Category.SENDING, task))

        assertEquals(
            "Send an e-mail to ana@example.com and, as a copy, marko@example.com, subject 'Plan': 'See you at five.'? Say confirm or cancel.",
            asked.question,
        )
        val unread = TestScreen(SelfChats.GMAIL)
        unread.add(200, null, id = "to", editable = true, text = "ana@example.com, Marko")
        val button = unread.add(80, "Send", left = 850)
        assertEquals(Decision.HandOver(HandOver.of(HandOverKind.RECIPIENT)), ConfirmationBuilder.build(send(button, "Gmail"), unread.snapshot(), Category.SENDING, task))
    }

    @Test
    fun aLongTextIsCutOnTheVoiceAndShownWholeOnTheCard() {
        val text = "word ".repeat(60).trim()
        val (screen, button) = chat("Ana", text)

        val asked = confirmation(ConfirmationBuilder.build(send(button), screen.snapshot(), Category.SENDING, task))

        assertTrue(asked.question.contains(text))
        assertTrue(asked.spoken, asked.spoken.contains("… and 100 more characters"))
        assertTrue(asked.spoken.length < asked.question.length)
    }

    private fun link(kind: ActionKind, link: String, target: String?, label: String?) =
        Proposal(kind.name.lowercase(), kind, link = link, linkTargetPackage = target, receivingAppLabel = label, frontAppLabel = "Chat")

    @Test
    fun aLinkFromTheScreenAsksWithItsHostAndTheAppThatOpensIt() {
        val url = "https://Tracker.example.net/c?d=Ana%20Petrovic"
        val settings = SafetySettings(testMode = false)

        val asked = confirmation(SafetyCheck.decide(link(ActionKind.OPEN_URL, url, "org.mozilla.firefox", "Firefox"), TestScreen().snapshot(), task, settings))

        assertEquals("Open tracker.example.net in Firefox? It sends what the link holds. Say confirm or cancel.", asked.question)
        assertEquals(listOf("tracker.example.net"), asked.recipients)
        assertEquals(url, asked.content)
        val unlabelled = confirmation(ConfirmationBuilder.build(link(ActionKind.OPEN_URL, url, "org.mozilla.firefox", null), TestScreen().snapshot(), Category.SENDING, task))
        assertEquals("Open tracker.example.net in firefox? It sends what the link holds. Say confirm or cancel.", unlabelled.question)
    }

    @Test
    fun aDeepLinkWithoutAHostNamesItsSchemeAndALinkWithoutAnAppIsHandedOver() {
        val mail = link(ActionKind.OPEN_DEEP_LINK, "mailto:ana@example.com?body=hello", "com.google.android.gm", "Gmail")

        assertEquals(
            "Open a mailto link in Gmail? It sends what the link holds. Say confirm or cancel.",
            confirmation(ConfirmationBuilder.build(mail, TestScreen().snapshot(), Category.SENDING, task)).question,
        )
        val nowhere = ConfirmationBuilder.build(mail.copy(linkTargetPackage = null), TestScreen().snapshot(), Category.SENDING, task)
        assertEquals(HandOverKind.RECIPIENT, (nowhere as Decision.HandOver).handOver.kind)
        assertTrue(nowhere.handOver.text.startsWith("I can't tell where this link goes."))
    }

    @Test
    fun aSliderIsAskedAboutWithItsLabelAndThePercent() {
        val screen = TestScreen("com.android.settings")
        val key = screen.add(400, "Display brightness", className = "android.widget.SeekBar")
        val proposal = Proposal("set_slider", ActionKind.SET_SLIDER, Category.DELETING, targetKey = key, percent = 30, frontAppLabel = "Settings")

        val decision = ConfirmationBuilder.build(proposal, screen.snapshot(), Category.DELETING, task) as Decision.Confirm

        assertEquals("Set 'Display brightness' to 30% in Settings? Say confirm or cancel.", decision.confirmation.question)
    }
}
