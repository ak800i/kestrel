package dev.kestrel.app.debug

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.app.agent.TaskEvidence
import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ChecklistSummary
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.safety.Category
import dev.kestrel.safety.EmergencyGuard
import dev.kestrel.safety.HandOverKind
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The answers of the agent's debug hooks (design D28 of sub-project 5): counts, kinds and matches, never words. */
class AgentHookAnswersTest {
    @Test
    fun aResultHoldsOnlyKindsCountsAndMatches() {
        val report = TaskReport(
            EndReason.FINISHED, summary = "I told Ana that you're late for maple heron.", recipients = listOf("Saved Messages"), steps = 5,
            workMillis = 41_000, credits = 0.5, inputTokens = 5000, outputTokens = 250, cacheRead = 2500, confirmed = 1, modelId = "scripted-light",
        )

        val ended = AgentHookAnswers.Ended(report, "Sent to Saved Messages. I told Ana that you're late for maple heron.", touches = 2, signals = 1)
        val line = AgentHookAnswers.result(ended, "Maple heron", "saved messages")

        assertEquals(
            "state=done end=FINISHED steps=5 workMs=41000 credits=0.50 confirmed=1 unconfirmed=0 shareItems=0 recipient=match marker=1 " +
                "beforeTestMode=0 model=scripted-light in=5000 out=250 cacheRead=2500 cacheWrite=0 ownerTouches=2 signals=1 markerSaid=0 markerInData=none",
            line,
        )
        assertFalse(line.contains("Ana"))
    }

    @Test
    fun aRecipientIsComparedOnThePhoneAndAMarkerByItsWordsInOrder() {
        assertEquals("other", AgentHookAnswers.recipient(listOf("Ana Petrović"), "Saved Messages"))
        assertEquals("match", AgentHookAnswers.recipient(listOf("Ana Petrovic"), "ana petrović"))
        assertEquals("none", AgentHookAnswers.recipient(emptyList(), "Saved Messages"))
        assertEquals("none", AgentHookAnswers.recipient(listOf("Saved Messages"), null))
        assertEquals(0, AgentHookAnswers.marker("I read you the e-mail about heron maple.", "maple heron"))
        assertEquals(1, AgentHookAnswers.marker("Kestrel test: Maple heron.", "maple heron"))
        assertEquals(0, AgentHookAnswers.marker("Kestrel test: maple heron.", null))
    }

    @Test
    fun theStateGivesTheChecklistOnlyForTheCurrentRoundAndWhetherAConversationIsOpen() {
        val acting = ConversationView(id = ConversationId.random(), command = 3, goal = GoalPhase.ACTING, checklist = ChecklistSummary(2, 5, "write to Mila"))

        val line = AgentHookAnswers.state(3, acting, Work.IDLE, AgentHookAnswers.Open())

        assertEquals("phase=running asking=none work=idle handOver=none confirming=none question=none checklist=2/5 conversation=open", line)
        assertFalse(line, line.contains("Mila"))
        // An earlier round of the open conversation has ended, and its line names nothing of the current round.
        val confirming = acting.copy(goal = GoalPhase.ASKING, asking = QuestionKind.CONFIRM)
        val open = AgentHookAnswers.Open(HandOverKind.CALL, Category.SENDING, QuestionKind.CONFIRM)
        assertEquals(
            "phase=ended asking=none work=awaiting-task handOver=none confirming=none question=none checklist=none conversation=open",
            AgentHookAnswers.state(2, confirming, Work.AWAITING_TASK, open),
        )
        assertEquals(
            "phase=running asking=confirm work=awaiting-task handOver=CALL confirming=sending question=none checklist=2/5 conversation=open",
            AgentHookAnswers.state(3, confirming, Work.AWAITING_TASK, open),
        )
        assertEquals(
            "phase=paused-at-limit asking=none work=idle handOver=none confirming=none question=none checklist=2/5 conversation=open",
            AgentHookAnswers.state(3, acting.copy(goal = GoalPhase.PAUSED_AT_LIMIT), Work.IDLE, AgentHookAnswers.Open()),
        )
        assertEquals(
            "phase=ended asking=none work=idle handOver=none confirming=none question=none checklist=none conversation=closed",
            AgentHookAnswers.state(3, ConversationView(), Work.IDLE, AgentHookAnswers.Open()),
        )
    }

    @Test
    fun anAnswerIsOneOfTheOwnersWordsANumberOrAText() {
        for (word in listOf("yes", "no", "confirm", "cancel", "continue")) assertEquals(word, AgentHookAnswers.words(word))
        assertEquals("12", AgentHookAnswers.words("12"))
        assertEquals("the blue one", AgentHookAnswers.words("text:the blue one"))
        assertNull(AgentHookAnswers.words("text:"))
        assertNull(AgentHookAnswers.words("yes, call"))
    }

    @Test
    fun hasAnswersOnlyWhetherAKeyExistsComparedAsTheMemoryStoreDoes() {
        assertEquals("yes", AgentHookAnswers.has(listOf("Home", "Ana"), "ana"))
        assertEquals("yes", AgentHookAnswers.has(listOf("Ana Petrović"), "ana petrovic"))
        assertEquals("no", AgentHookAnswers.has(listOf("Home"), "Ana"))
        assertEquals("no", AgentHookAnswers.has(emptyList(), "Ana"))
    }

    @Test
    fun digitsAreComparedOnThePhoneWithoutEverythingElseOfTheReply() {
        assertEquals(1, AgentHookAnswers.digits("Ana Test's number is +1 202-555-0143.", "2025550143"))
        assertEquals(1, AgentHookAnswers.digits("Your battery is at 87 percent.", "87"))
        assertEquals(0, AgentHookAnswers.digits("Your battery is at 86 percent.", "87"))
        assertEquals(0, AgentHookAnswers.digits("No number here.", "87"))
        assertEquals(0, AgentHookAnswers.digits("87", null))
        assertEquals(1, AgentHookAnswers.digits("Call (202) 555 0143 tomorrow.", "2025550143"))
        assertEquals(1, AgentHookAnswers.digits("It is at 87.", "87"))
        assertEquals(0, AgentHookAnswers.digits("Your battery is at 58 percent, about 7 hours left.", "87"))
        assertEquals(0, AgentHookAnswers.digits("Room 187 is free.", "87"))
        assertEquals(0, AgentHookAnswers.digits("It costs 870 dinars.", "87"))
        assertEquals(0, AgentHookAnswers.digits("At 10:58 it was 7 percent.", "587"))
        val report = TaskReport(EndReason.FINISHED, summary = "s", steps = 1)

        assertTrue(AgentHookAnswers.result(AgentHookAnswers.Ended(report, "It is 87 percent."), null, null, "87").endsWith(" digits=1"))
        assertFalse(AgentHookAnswers.result(AgentHookAnswers.Ended(report, "It is 87 percent."), null, null).contains("digits="))
    }

    @Test
    fun screenTapFindsALabelOrAPartOfItInTheFrontWindowAndTheAllowlistIsSorted() {
        val front = WindowSummary(1, WindowKind.APPLICATION, "com.google.android.gm", bounds = IntRect(0, 0, 100, 100), active = true)
        val behind = WindowSummary(2, WindowKind.APPLICATION, "org.mozilla.firefox", bounds = IntRect(0, 0, 100, 100), layer = -1)
        val snapshot = ScreenSnapshot(
            0, listOf(front, behind),
            listOf(
                button("Kestrel test maple heron").copy(windowId = 2),
                button("Inbox"),
                button("me · Kestrel test maple heron · 23:59"),
                button("Delete"),
            ),
        )

        assertEquals(listOf(3), AgentHookAnswers.tapMatches(snapshot, "Delete", null))
        assertEquals(listOf(2), AgentHookAnswers.tapMatches(snapshot, null, "KESTREL TEST maple"))
        assertEquals(emptyList<Int>(), AgentHookAnswers.tapMatches(snapshot, "Kestrel test", null))
        assertEquals("api.githubcopilot.com,github.com", AgentHookAnswers.hostList(listOf("github.com", "api.githubcopilot.com")))
        assertEquals("none", AgentHookAnswers.hostList(emptyList()))
    }

    @Test
    fun aliasesMapPackagesAndRefuseAnythingElse() {
        assertEquals(mapOf("dev.kestrel.testapp" to "org.telegram.messenger"), AgentHookAnswers.aliases("dev.kestrel.testapp=org.telegram.messenger"))
        assertEquals(emptyMap<String, String>(), AgentHookAnswers.aliases(""))
        assertEquals(
            IllegalArgumentException::class.java,
            runCatching { AgentHookAnswers.aliases("dev.kestrel.testapp=Saved Messages") }.exceptionOrNull()?.javaClass,
        )
    }

    @Test
    fun theTreeKeepsOnlyTheSelfChatsFixedTextsAndTheTilesOfSystemUi() {
        val safe = AgentHookAnswers.FIXED_SAFE

        assertEquals("Saved Messages", AgentHookAnswers.safeLabel("Saved Messages", "org.telegram.messenger", safe))
        assertNull(AgentHookAnswers.safeLabel("Ana Petrović", "org.telegram.messenger", safe))
        assertEquals("Wi-Fi", AgentHookAnswers.safeLabel("Wi-Fi", AgentHookAnswers.SYSTEM_UI, safe))
        assertNull(AgentHookAnswers.safeLabel("Saved Messages", AgentHookAnswers.SYSTEM_UI, safe))
        assertEquals("last-seen", AgentHookAnswers.status("last seen recently"))
        assertEquals("members", AgentHookAnswers.status("3 members"))
        assertEquals("none", AgentHookAnswers.status("Ana Petrović"))
    }

    @Test
    fun anAnswerReachesAConfirmationOnlyWithItsCategory() {
        assertNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "confirm", Category.SENDING, "sending"))
        assertNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "cancel", Category.DELETING, null))
        assertNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "no", Category.DELETING, null))
        assertNull(AgentHookAnswers.answerRefusal(QuestionKind.YES_NO, "no", null, null))
        assertNull(AgentHookAnswers.answerRefusal(null, "continue", null, null))

        // The confirmation changed between agent-state and agent-answer.
        assertNotNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "confirm", Category.DELETING, "sending"))
        assertNotNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "cancel", Category.DELETING, "sending"))
        assertNotNull(AgentHookAnswers.answerRefusal(QuestionKind.FREE, "yes", null, "sending"))
        assertNotNull(AgentHookAnswers.answerRefusal(null, "continue", null, "sending"))
        // A word that confirms needs the category that the script read.
        assertNotNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "confirm", Category.SENDING, null))
        assertNotNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "yes", Category.SENDING, null))
        assertNotNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "send it", Category.SENDING, null))
    }

    @Test
    fun screenTapRefusesWhatTheAgentWouldNeverPressWithoutTheOwner() {
        val phones = EmergencyGuard.PHONE_PACKAGES + "com.example.dialer"
        fun refused(packageName: String, vararg elements: ScreenElement, label: String = elements.last().label.orEmpty()): Boolean {
            val window = WindowSummary(1, WindowKind.APPLICATION, packageName, bounds = IntRect(0, 0, 100, 100), active = true)
            val snapshot = ScreenSnapshot(0, listOf(window), elements.map { it.copy(packageName = packageName) })
            return AgentHookAnswers.tapRefused(snapshot, elements.lastIndex, label, phones)
        }
        val chat = "org.telegram.messenger"

        assertFalse(refused(chat, button("Send")))
        assertFalse(refused(AgentHookAnswers.SYSTEM_UI, button("Mobile Hotspot")))
        // Kestrel's own app, the phone apps, the contacts apps and the default phone app, whatever the label.
        for (packageName in listOf("dev.kestrel", "com.android.dialer", "com.google.android.contacts", "com.example.dialer")) {
            assertTrue(packageName, refused(packageName, button("Back")))
        }
        // The call rules' words in any app, in English and Serbian, in a label or a content description.
        assertTrue(refused(chat, button("Voice call")))
        assertTrue(refused(chat, button(null, description = "Pozovi")))
        assertTrue(refused(chat, button("Back"), label = "Video call"))
        // Emergency and SOS, such as the lock screen's button, also by its view ID.
        assertTrue(refused(AgentHookAnswers.SYSTEM_UI, button("Emergency call")))
        assertTrue(refused(AgentHookAnswers.SYSTEM_UI, button("SOS")))
        assertTrue(refused(AgentHookAnswers.SYSTEM_UI, button("Help", viewId = "com.android.systemui:id/emergency_button")))
        // A contact's name inside a row that calls.
        assertTrue(refused(chat, button("Call Ana"), button("Ana", parent = 0, clickable = false)))
    }

    private fun button(label: String?, description: String? = null, viewId: String? = null, parent: Int? = null, clickable: Boolean = true) =
        ScreenElement(
            key = "k${label ?: description}", windowId = 1, parent = parent, text = label, contentDescription = description, viewId = viewId,
            label = label ?: description, bounds = IntRect(0, 0, 10, 10), clickable = clickable,
        )

    // The wire names that :tools:eval's answers read (plan 6B-basefix).
    @Test
    fun theQuestionAndTheAskingKeepSubProjectFivesWords() {
        assertEquals("screenshot", AgentHookAnswers.question(QuestionKind.SCREENSHOT))
        assertEquals("pick-number", AgentHookAnswers.question(QuestionKind.PICK))
        assertEquals("free", AgentHookAnswers.question(QuestionKind.FREE))
        assertEquals("yes-no", AgentHookAnswers.question(QuestionKind.YES_NO))
        assertEquals("limit", AgentHookAnswers.question(QuestionKind.LIMIT))
        assertEquals("none", AgentHookAnswers.question(QuestionKind.CONTINUE_GOAL))
        // agent-state's asking= keeps the words that :tools:eval reads (design 14.4 of sub-project 7).
        assertEquals("continue", AgentHookAnswers.asking(QuestionKind.CONTINUE_GOAL))
        assertEquals("yes-no", AgentHookAnswers.asking(QuestionKind.LIMIT))
        assertEquals("free", AgentHookAnswers.asking(QuestionKind.SCREENSHOT))
        assertEquals("pick-number", AgentHookAnswers.asking(QuestionKind.PICK))
        assertEquals("confirm", AgentHookAnswers.asking(QuestionKind.CONFIRM))
    }

    @Test
    fun theMarkerAndTheDigitsCountAlsoInWhatKestrelSaidDuringTheTask() {
        val report = TaskReport(EndReason.FINISHED, summary = "s", steps = 2)
        val evidence = TaskEvidence(listOf("Kestrel test maple heron: a reminder about maple heron.", "Your battery is at 87 percent."), markerInData = true)

        val line = AgentHookAnswers.result(AgentHookAnswers.Ended(report, "I read your notifications.", evidence = evidence), "maple heron", null, "87")

        assertTrue(line, line.contains(" marker=1 "))
        assertTrue(line, line.endsWith(" ownerTouches=0 signals=0 markerSaid=1 markerInData=1 digits=1"))
        assertTrue(AgentHookAnswers.result(AgentHookAnswers.Ended(report, "Kestrel test maple heron."), "maple heron", null).endsWith(" markerSaid=0 markerInData=none"))
        assertFalse(line.contains("reminder"))
    }

    // Design 10.3 of sub-project 7: agent-answer --es channel text presses the card's button that its words name.
    @Test
    fun anAnswerOnTheTextChannelPressesTheButtonThatItsWordsName() {
        val confirmation = listOf(CardChoice.Confirm, CardChoice.Cancel)
        val pick = listOf(CardChoice.Pick(1), CardChoice.Pick(2), CardChoice.Reply)

        assertEquals(CardChoice.Confirm, AgentHookAnswers.cardChoice("confirm", confirmation))
        assertEquals(CardChoice.Cancel, AgentHookAnswers.cardChoice("cancel", confirmation))
        assertNull(AgentHookAnswers.cardChoice("yes", confirmation))
        assertEquals(CardChoice.Pick(2), AgentHookAnswers.cardChoice("2", pick))
        assertNull(AgentHookAnswers.cardChoice("3", pick))
        assertNull(AgentHookAnswers.cardChoice("Half past seven", listOf(CardChoice.Reply, CardChoice.Cancel)))
    }
}
