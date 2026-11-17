package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache

/** Which clipboard action [TextEditor.clipboard] performs. */
enum class ClipboardKind { CUT, COPY, PASTE }

/**
 * A text field as the editing commands see it (design spec, section 9.7): its [text] (empty while it shows its hint), the
 * selection, whether it is a password field, has more than one line and has the focus, and its app.
 */
data class FieldState(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val password: Boolean,
    val multiLine: Boolean,
    val focused: Boolean,
    val packageName: String?,
) {
    override fun toString() =
        "FieldState(${text.length} characters, selection $selectionStart to $selectionEnd, password=$password, " +
            "multiLine=$multiLine, focused=$focused)"
}

/**
 * Edits text fields through Kestrel's accessibility input method, with set-text as the fallback (design spec, section
 * 9.7). Plan 3B replaces this stub, which edits nothing, and keeps its constructor and signatures.
 */
@Suppress("unused")
class TextEditor(
    private val service: AccessibilityService,
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val tracker: ScreenChangeTracker,
) {
    suspend fun read(key: String): FieldState? = null

    suspend fun replace(key: String, start: Int, end: Int, replacement: String, selectionStart: Int, selectionEnd: Int): ActionResult =
        NOT_AVAILABLE

    suspend fun newLine(key: String): ActionResult = NOT_AVAILABLE

    suspend fun select(key: String, start: Int, end: Int): ActionResult = NOT_AVAILABLE

    suspend fun submit(key: String): ActionResult = NOT_AVAILABLE

    suspend fun clipboard(key: String, kind: ClipboardKind): ActionResult = NOT_AVAILABLE

    suspend fun typeAtCursor(key: String, text: String): ActionResult = NOT_AVAILABLE

    suspend fun deleteBeforeCursor(key: String, count: Int): ActionResult = NOT_AVAILABLE

    private companion object {
        val NOT_AVAILABLE = ActionResult.Failed("Not available yet")
    }
}
