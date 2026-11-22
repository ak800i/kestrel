package dev.kestrel.app.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.safety.SafetySettings
import dev.kestrel.safety.SelfChats
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The agent's fixtures in `:testapp` (design D18, D27 and D28 of sub-project 5), read through Kestrel's own snapshots: the
 * fixture messenger's self-chats pass test mode's recogniser under an alias and its look-alikes do not, the share picker's
 * first row is the self-chat and its other rows are not, and every press of a send control is recorded by its kind only.
 */
@RunWith(AndroidJUnit4::class)
class AgentFixturesTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val owner = SafetySettings(whatsappOwnName = "Test Owner", ownerEmails = listOf("owner@example.com"))
    private val send: (ScreenElement) -> Boolean = { it.packageName == FIXTURES && it.viewId == "$FIXTURES:id/send" }

    @After
    fun clear() {
        screen.shell("run-as $FIXTURES rm -f files/presses.log")
        screen.shell("am force-stop $FIXTURES")
    }

    private fun chat(id: String, title: String) = screen.open("MessengerActivity", title, "--es chat $id")

    // Whether test mode's recogniser, with [app]'s rule applied to the fixtures, takes the action on [target] for the
    // owner's own chat. No app's rule is active for real packages here.
    private suspend fun recognised(app: String, target: (ScreenElement) -> Boolean): Boolean {
        val snapshot = screen.session.snapshots.current().snapshot
        val key = snapshot.elements.first(target).key
        return SelfChats.recognise(snapshot, key, owner, aliases = mapOf(FIXTURES to app), active = emptySet())
    }

    private fun presses(): List<String> =
        screen.shell("run-as $FIXTURES cat files/presses.log").lines().filter { it.isNotBlank() }

    @Test
    fun theTelegramSelfChatPassesAndItsLookAlikesDoNot() = runBlocking {
        chat("saved", "Saved Messages")
        assertTrue(recognised(SelfChats.TELEGRAM, send))
        for (id in listOf("saved-contact", "saved-group", "saved-user", "saved-bot")) {
            chat(id, "Saved Messages")
            assertFalse(id, recognised(SelfChats.TELEGRAM, send))
        }
        chat("forward-text", "Mira Jović")
        assertFalse(recognised(SelfChats.TELEGRAM, send))
    }

    @Test
    fun theWhatsAppViberAndEmailSelfChatsPassAndTheirLookAlikesDoNot() = runBlocking {
        chat("whatsapp-self", "Test Owner (You)")
        assertTrue(recognised(SelfChats.WHATSAPP, send))
        chat("whatsapp-lookalike", "Test Owner (You)")
        assertFalse(recognised(SelfChats.WHATSAPP, send))
        chat("viber-notes", "My Notes")
        assertTrue(recognised(SelfChats.VIBER, send))
        chat("viber-lookalike", "My Notes")
        assertFalse(recognised(SelfChats.VIBER, send))
        for ((kind, expected) in listOf("self" to true, "two" to false, "name" to false)) {
            screen.open("MessengerActivity", "Compose", "--es mail $kind")
            assertEquals(kind, expected, recognised(SelfChats.GMAIL, send))
        }
    }

    @Test
    fun thePickersFirstRowIsTheSelfChatAndCloseFriendsIsNot() = runBlocking {
        screen.shell("am start -W -n $FIXTURES/.MessengerActivity --es shareForm tap")
        screen.shell("am start -W -S -a android.intent.action.SEND -t image/jpeg -n $FIXTURES/.ShareActivity")
        screen.awaitElement { it.label == "Share with" }
        fun row(name: String): (ScreenElement) -> Boolean = { it.viewId == "$FIXTURES:id/picker_row" && it.label?.startsWith(name) == true }

        assertTrue(recognised(SelfChats.TELEGRAM, row("Saved Messages")))
        assertFalse(recognised(SelfChats.TELEGRAM, row("Close friends")))
        assertFalse(recognised(SelfChats.TELEGRAM, row("Back to school")))
    }

    @Test
    fun aPressOfSendIsRecordedByItsKindAndTimeOnly() = runBlocking {
        chat("ana", "Ana Petrović")
        val button = screen.awaitElement(predicate = send)

        screen.session.actions.tap(button.key)
        screen.awaitElement { it.label == "Nothing to send" }

        val lines = presses()
        assertEquals(1, lines.size)
        assertTrue(lines.single().matches(Regex("send \\d+")))
    }

    @Test
    fun withEnterSendsALineBreakInTheFieldSends() = runBlocking {
        chat("ana", "Ana Petrović")
        screen.session.actions.tap(screen.awaitElement { it.label == "Enter sends" }.key)
        val field = screen.awaitElement { it.viewId == "$FIXTURES:id/compose" }

        // Kestrel types the text as given; a check of the agent's type_text strips the line break before (design D13).
        screen.session.actions.setText(field.key, "On my way\n")
        screen.awaitElement { it.label == "Sent" }

        val kinds = presses().map { it.substringBefore(' ') }
        assertTrue(kinds.isNotEmpty() && kinds.all { it == "send" })
    }
}
