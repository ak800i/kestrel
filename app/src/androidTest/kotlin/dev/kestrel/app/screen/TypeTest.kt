package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TypeTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val actions get() = screen.session.actions

    private suspend fun field(name: String) = screen.awaitElement { it.viewId == "$FIXTURES:id/$name" }

    @Test
    fun setTextUsesTheSetTextActionAndReadsTheTextBack() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.setText(field("message_field").key, "Hello Ana"))
        screen.awaitElement { it.viewId == "$FIXTURES:id/message_field" && it.text == "Hello Ana" }
        Unit
    }

    @Test
    fun aFieldKeepsItsKeyWhileTypingAndCanBeCleared() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val key = field("message_field").key

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.setText(key, "Hello Ana"))
        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.setText(key, ""))
        screen.awaitElement { it.key == key && it.text == null }
        Unit
    }

    @Test
    fun anAppThatRefusesSetTextGetsTheInputMethodFromThenOn() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertEquals(ActionResult.Done(ActionMethod.INPUT_METHOD), actions.setText(field("strict_field").key, "Strict text"))
        assertEquals(ActionResult.Done(ActionMethod.INPUT_METHOD), actions.setText(field("message_field").key, "Typed text"))
        screen.awaitElement { it.viewId == "$FIXTURES:id/strict_field" && it.text == "Strict text" }
        screen.awaitElement { it.viewId == "$FIXTURES:id/message_field" && it.text == "Typed text" }
        Unit
    }

    @Test
    fun aPasswordIsTypedButNeverReadBack() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.setText(field("password_field").key, "hunter2"))
        screen.session.snapshots.invalidate()
        assertNull(field("password_field").text)
    }

    @Test
    fun aDisabledFieldIsNotTypedIntoAndDoesNotSwitchTheAppToTheInputMethod() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val disabled = screen.awaitElement { it.editable && it.label == "Disabled field" }

        assertEquals(ActionResult.Failed("The element is disabled"), actions.setText(disabled.key, "Hello"))
        assertEquals(ActionResult.Done(ActionMethod.ACTION), actions.setText(field("message_field").key, "Hello"))
    }

    @Test
    fun setTextRefusesAnElementThatIsNotATextField() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertTrue(actions.setText(field("send_button").key, "Hello") is ActionResult.Failed)
    }
}
