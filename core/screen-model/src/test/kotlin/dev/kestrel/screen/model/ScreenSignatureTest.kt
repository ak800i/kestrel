package dev.kestrel.screen.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSignatureTest {
    private val button = "android.widget.ImageButton"
    private val text = "android.widget.TextView"

    /**
     * A chat with view IDs: eleven parts outside the list of messages. With the [keyboard] up, the app's list shrinks and
     * its bottom bar sits just above the keyboard, while its window keeps its full height.
     */
    private fun chat(subtitle: String, sendInsteadOfMic: Boolean, rows: Int = 3, keyboard: Boolean = false): ScreenSnapshot {
        val screen = TestScreen(packageName = "com.example.chat", keyboardTop = if (keyboard) 1200 else null)
        val bar = if (keyboard) 1100 else 1800
        screen.add(80, id = "back", className = button, right = 100, clickable = true)
        screen.add(80, id = "avatar", className = "android.widget.ImageView", left = 100, right = 200)
        screen.add(80, text = "Ana", id = "title", left = 220, right = 600, height = 50)
        screen.add(130, text = subtitle, id = "subtitle", left = 220, right = 600, height = 40)
        screen.add(80, id = "call", className = button, left = 700, right = 800, clickable = true)
        screen.add(80, id = "menu", className = button, left = 900, clickable = true)
        val list = screen.add(250, id = "messages", className = "androidx.recyclerview.widget.RecyclerView", height = bar - 350, scrollable = true)
        repeat(rows) { screen.add(300 + it * 100, text = "Message $it", id = "bubble", parent = list) }
        screen.add(bar, id = "emoji", className = button, right = 100, clickable = true)
        screen.add(bar, id = "compose", className = "android.widget.EditText", left = 100, right = 700, editable = true)
        screen.add(bar, id = "attach", className = button, left = 700, right = 800, clickable = true)
        screen.add(bar, id = if (sendInsteadOfMic) "send" else "mic", className = button, left = 900, clickable = true)
        return screen.snapshot()
    }

    @Test
    fun aTypingLineOrASendButtonInPlaceOfTheMicrophoneIsTheSameScreen() {
        val before = ScreenSignature.of(chat("online", sendInsteadOfMic = false))
        val after = ScreenSignature.of(chat("typing…", sendInsteadOfMic = true))

        assertTrue(before.sameScreen(after))
        assertEquals("com.example.chat", before.packageName)
    }

    @Test
    fun theKeyboardNeverMovesTheBarAboveItToAnotherRegion() {
        val before = ScreenSignature.of(chat("online", sendInsteadOfMic = false))
        val after = ScreenSignature.of(chat("online", sendInsteadOfMic = false, keyboard = true))

        assertEquals(before.parts, after.parts)
        assertTrue(before.sameScreen(after))
    }

    @Test
    fun rowsOfAListDoNotCount() {
        assertEquals(ScreenSignature.of(chat("online", false, rows = 3)), ScreenSignature.of(chat("online", false, rows = 9)))
    }

    @Test
    fun anAppWithoutViewIdsStillTellsItsChatListFromAChat() {
        val list = TestScreen(packageName = "org.telegram.messenger")
        list.add(80, className = button, right = 100, clickable = true)
        list.add(80, text = "Telegram", left = 200, right = 600)
        list.add(80, className = button, left = 900, clickable = true)
        list.add(250, className = "androidx.recyclerview.widget.RecyclerView", height = 1450, scrollable = true)
        list.add(1800, className = button, left = 900, clickable = true)
        val chat = TestScreen(packageName = "org.telegram.messenger")
        chat.add(80, className = button, right = 100, clickable = true)
        chat.add(80, className = "android.widget.ImageView", left = 100, right = 200)
        chat.add(80, text = "Ana", left = 220, right = 600, height = 50)
        chat.add(130, text = "online", left = 220, right = 600, height = 40)
        chat.add(80, className = button, left = 700, right = 800, clickable = true)
        chat.add(80, className = button, left = 900, clickable = true)
        chat.add(250, className = "androidx.recyclerview.widget.RecyclerView", height = 1450, scrollable = true)
        chat.add(1800, className = button, right = 100, clickable = true)
        chat.add(1800, className = "android.widget.EditText", left = 100, right = 700, editable = true)
        chat.add(1800, className = button, left = 700, right = 800, clickable = true)
        chat.add(1800, className = button, left = 900, clickable = true)

        assertFalse(ScreenSignature.of(list.snapshot()).sameScreen(ScreenSignature.of(chat.snapshot())))
    }

    @Test
    fun anotherPackageIsAnotherScreenButAnotherWindowOfTheSameShapeIsNot() {
        val first = TestScreen(packageName = "com.example.a")
        first.add(100, text = "Hello", className = text)
        val second = TestScreen(packageName = "com.example.b")
        second.add(100, text = "Hello", className = text)
        val window = TestScreen(packageName = "com.example.a", windowId = 2)
        window.add(100, text = "Hello", className = text)
        val signature = ScreenSignature.of(first.snapshot())

        assertTrue(signature.sameScreen(ScreenSignature.of(first.snapshot())))
        assertFalse(signature.sameScreen(ScreenSignature.of(second.snapshot())))
        // Android gives every window instance a new ID, also the same screen after its app restarted (design D23).
        assertTrue(signature.sameScreen(ScreenSignature.of(window.snapshot())))
    }

    @Test
    fun theSimilarityCountsEachPartAsOftenAsItOccurs() {
        val one = TestScreen()
        one.add(100, className = button, clickable = true)
        val two = TestScreen()
        two.add(100, className = button, clickable = true)
        two.add(100, className = button, clickable = true)

        assertEquals(0.5, ScreenSignature.of(one.snapshot()).similarity(ScreenSignature.of(two.snapshot())), 1e-9)
        assertEquals(1.0, Signature(null, null, null, emptyList()).similarity(Signature(null, null, null, emptyList())), 1e-9)
    }
}
