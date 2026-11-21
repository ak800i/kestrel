package dev.kestrel.safety

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
}
