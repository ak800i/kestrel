package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.WindowKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnapshotTest {
    @get:Rule
    val screen = ScreenServiceRule()

    @Test
    fun viewsAreReadWithTheirNamesStatesAndActions() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        val snapshot = screen.session.snapshots.current().snapshot
        val send = snapshot.elements.single { it.viewId == "$FIXTURES:id/send_button" }
        val compose = snapshot.elements.single { it.viewId == "$FIXTURES:id/compose_button" }
        val row = snapshot.elements.single { it.viewId == "$FIXTURES:id/contact_row" }
        val password = snapshot.elements.single { it.viewId == "$FIXTURES:id/password_field" }
        val message = snapshot.elements.single { it.viewId == "$FIXTURES:id/message_field" }

        assertEquals("SEND", send.label)
        assertTrue(send.clickable && ElementAction.CLICK in send.actions)
        assertNull(compose.label)
        assertEquals("compose", compose.suggestedName)
        assertEquals("Ana Petrović Online", row.label)
        assertTrue(password.password && password.editable)
        assertNull(password.text)
        assertEquals("Password", password.label)
        assertNull("An empty field's hint is not its text", message.text)
        assertEquals("Message", message.label)
        assertTrue(snapshot.elements.single { it.label == "Remember me" }.checkable)
        assertTrue(snapshot.windows.any { it.kind == WindowKind.APPLICATION && it.packageName == FIXTURES })
    }

    @Test
    fun keysStayTheSameInANewSnapshotOfTheSameScreen() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val first = screen.session.snapshots.current().snapshot.elements.map { it.key }

        screen.session.snapshots.invalidate()
        val second = screen.session.snapshots.current().snapshot.elements.map { it.key }

        assertEquals(first, second)
    }

    @Test
    fun composeElementsAreRead() = runBlocking {
        screen.open("ComposeActivity", "Compose fixture")

        val elements = screen.session.snapshots.current().snapshot.elements

        assertTrue(elements.single { it.clickable && it.label == "Compose button" }.clickable)
        assertTrue(elements.any { it.checkable && it.label == "Subscribe" })
        assertTrue(elements.any { it.editable })
    }

    @Test
    fun webPageContentIsRead() = runBlocking {
        screen.open("WebActivity", "Web fixture")

        assertTrue(screen.awaitElement { it.label == "Web button" }.clickable)
        screen.awaitElement { it.label == "Web link" }
        screen.awaitElement { it.editable && it.label == "Web field" }
        Unit
    }

    @Test
    fun aSecureWindowCanStillBeRead() = runBlocking {
        screen.open("SecureActivity", "Secure fixture")

        assertTrue(screen.awaitElement { it.label == "SECRET ACTION" }.clickable)
    }

    @Test
    fun aDialogComesBeforeTheActivityBehindIt() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val showDialog = screen.session.snapshots.current().snapshot.elements.single { it.label == "SHOW DIALOG" }
        screen.shell("input tap ${showDialog.bounds.centerX} ${showDialog.bounds.centerY}")

        val title = screen.awaitElement { it.label == "Delete draft?" }

        val snapshot = screen.session.snapshots.current().snapshot
        assertEquals(title.windowId, snapshot.windows.first { it.kind == WindowKind.APPLICATION }.id)
        assertTrue(snapshot.elements.any { it.label == "KEEP" && it.windowId == title.windowId })
        screen.shell("input keyevent KEYCODE_BACK")
        Unit
    }
}
