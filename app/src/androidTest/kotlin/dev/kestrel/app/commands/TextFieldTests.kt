package dev.kestrel.app.commands

import android.content.ClipData
import android.content.ClipboardManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.WindowKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Helpers for the device tests of the text commands (design D15 of sub-project 3). The text fixtures report each field's
 * text and selection in a line of their own, such as `Notes: "Hello" 5-5`, so the tests check what the app saw, not
 * what Kestrel read. Their failure messages name only the fixture app's labels.
 */
internal object TextFieldTests {
    /** How long a test waits for a field of the web page, which loads after its window shows. */
    const val WEB_MILLIS = 10_000L

    /** Waits until the fixture shows the line [text]. */
    fun ScreenServiceRule.awaitLine(text: String, timeoutMillis: Long = 5_000): ScreenElement =
        runBlocking { awaitElement(timeoutMillis) { it.packageName == FIXTURES && it.label == text } }

    /**
     * The text field of the fixture with the view ID [id]: `text_notes` for a view, the test tag `compose_notes` for
     * Compose, the HTML ID `web_notes` for the web page, for which a test waits [WEB_MILLIS].
     */
    fun ScreenServiceRule.field(id: String, timeoutMillis: Long = 5_000): ScreenElement =
        runBlocking {
            awaitElement(timeoutMillis) { it.editable && it.packageName == FIXTURES && it.viewId?.substringAfter(":id/") == id }
        }

    /** Waits until the field with the view ID [id] has the focus. */
    fun ScreenServiceRule.awaitFocus(id: String): ScreenElement =
        runBlocking { awaitElement { it.editable && it.focused && it.viewId?.substringAfter(":id/") == id } }

    /** Waits until the keyboard shows, or with [shown] false, until it has gone. */
    fun ScreenServiceRule.awaitKeyboard(shown: Boolean) = runBlocking {
        withTimeout(5_000) {
            while (session.snapshots.current().snapshot.windows.any { it.kind == WindowKind.INPUT_METHOD } != shown) delay(100)
        }
    }

    /** Waits until the feedback bar shows [text]. */
    fun ScreenServiceRule.awaitBar(text: String) = runBlocking {
        withTimeout(5_000) { while (session.feedbackText != text) delay(50) }
    }

    /**
     * Puts [text] on the clipboard, runs [block], and then puts back what the clipboard held before, also after a
     * failure: the emulator shares its clipboard with the computer, so a test's copy would replace the owner's. A
     * background app may read the clipboard only with the shell's permission. What the clipboard holds stays in memory
     * and is never printed.
     */
    fun ScreenServiceRule.keepClipboard(text: String, block: () -> Unit) {
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
        automation.adoptShellPermissionIdentity(READ_CLIPBOARD)
        val saved = try {
            clipboard.primaryClip
        } finally {
            automation.dropShellPermissionIdentity()
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        try {
            block()
        } finally {
            if (saved != null) clipboard.setPrimaryClip(saved) else clipboard.clearPrimaryClip()
        }
    }

    private const val READ_CLIPBOARD = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"
    private const val CLIP_LABEL = "Kestrel test"
}
