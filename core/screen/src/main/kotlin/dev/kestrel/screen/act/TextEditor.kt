package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache
import dev.kestrel.screen.read.shownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 * 9.7, and prototype P4). Before any call on the input connection, Kestrel focuses the field, waits until the field's app
 * has started the input, and checks that the field still has the focus, because Compose keeps the connection on the
 * field that has it. Every edit is read back. Kestrel never uses Android's movement actions or the app's own undo: it
 * computes every offset itself. The node actions are blocking binder calls on the caller's dispatcher, so callers run
 * them on `Dispatchers.IO`; the input connection is called on the main thread.
 */
class TextEditor(
    private val service: AccessibilityService,
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val tracker: ScreenChangeTracker,
) {
    /**
     * The live state of the field with [key], or null when it is not on the screen. A negative selection counts as the
     * cursor at the end. It has more than one line when its node says so or the input that its app started does, because
     * Compose's nodes always say one line. It is a password field when its node says so or the input does, because a
     * password that "show password" shows loses the node's flag but keeps its input type.
     */
    suspend fun read(key: String): FieldState? {
        val field = field(key) ?: return null
        val text = field.node.shownText().orEmpty()
        val (start, end) = selection(field.node, text)
        return FieldState(text, start, end, password(field), multiLine(field), field.node.isFocused, field.element.packageName)
    }

    /**
     * Replaces the text from [start] until [end] of the field with [key] by [replacement], and then selects
     * [selectionStart] to [selectionEnd]. The input method comes first: it replaces only that range, makes the events
     * that web pages expect, and lands in the field's undo history. When the input does not start or the field does not
     * show the result within 1 s, Kestrel sets the whole new text with the set-text action instead, but only while the
     * field still shows the text from before the edit, so that it never writes over a change that the field or the app
     * made. When the text changed while the field connected, as when an app fills in a draft or a browser shows the whole
     * address once its address bar has the focus, it changes nothing and gives [CHANGED], because the offsets were
     * planned on the old text. A password field shows only dots, so there only the length is checked, and set-text only
     * empties it. A phone number field may format the number as it goes in, so there only the dialable characters count.
     */
    suspend fun replace(key: String, start: Int, end: Int, replacement: String, selectionStart: Int, selectionEnd: Int): ActionResult {
        val field = field(key) ?: return NOT_ON_SCREEN
        if (!field.element.editable) return NOT_A_FIELD
        if (!field.node.isEnabled) return DISABLED
        val first = field.node.shownText().orEmpty()
        if (start < 0 || start > end || end > first.length) return CHANGED
        val connection = connect(field)
        if (!field.node.refresh()) return NOT_ON_SCREEN
        val password = password(field)
        val before = field.node.shownText().orEmpty()
        if (!same(before, first, password)) return CHANGED
        val expected = before.substring(0, start) + replacement + before.substring(end)
        val phone = phone(field)
        if (connection != null) {
            withContext(Dispatchers.Main) {
                connection.setSelection(start, end)
                connection.commitText(replacement, 1, null)
                // The commit leaves the cursor there already; a field that formats its text puts it after the formatting.
                if (selectionStart != selectionEnd || selectionStart != start + replacement.length) {
                    connection.setSelection(selectionStart, selectionEnd)
                }
            }
            if (shows(field.node, expected, password, phone)) return settled(ActionMethod.INPUT_METHOD)
        }
        if (password && expected.isNotEmpty()) return NOT_TAKEN
        if (!field.node.refresh() || !same(field.node.shownText().orEmpty(), before, password)) return NOT_TAKEN
        return setText(field, expected, selectionStart, selectionEnd)
    }

    /**
     * Starts a new line at the cursor with the Enter key, which every kind of multi-line field takes as a line break
     * (P4). A field with one line gets nothing, because the Enter key would submit it. The field is read after it has
     * connected, so that the set-text fallback never writes over a change that the app made meanwhile.
     */
    suspend fun newLine(key: String): ActionResult {
        val field = field(key) ?: return NOT_ON_SCREEN
        if (!field.node.isEnabled) return DISABLED
        val connection = connect(field)
        if (!multiLine(field)) return ONE_LINE
        if (!field.node.refresh()) return NOT_ON_SCREEN
        val before = field.node.shownText().orEmpty()
        val (start, end) = selection(field.node, before)
        if (connection != null) {
            withContext(Dispatchers.Main) { connection.enter() }
            if (changes(field.node, before)) return settled(ActionMethod.INPUT_METHOD)
        }
        if (password(field)) return NOT_TAKEN
        return setText(field, before.substring(0, start) + "\n" + before.substring(end), start + 1, start + 1)
    }

    /**
     * Selects [start] to [end] of the field with [key] with one set-selection action. `EditText` and Compose answer false
     * when the selection is already there, which counts as done.
     */
    suspend fun select(key: String, start: Int, end: Int): ActionResult {
        val field = field(key) ?: return NOT_ON_SCREEN
        if (!field.node.isEnabled) return DISABLED
        val done = setSelection(field.node, start, end)
        if (selects(field.node, start, end)) return settled(ActionMethod.ACTION)
        return if (done) settled(ActionMethod.ACTION) else ActionResult.Failed("The text field refused the selection.")
    }

    /**
     * Submits the field with [key] with its own editor action, which the input method sends as the app expects: search,
     * go, send, done, next or previous, and in a field with more lines only search, go or send. Without one, a field with
     * one line gets the Enter key, and a field with more lines has nothing to submit. When the input does not start, the
     * IME-enter action is the fallback, never on a field with more lines, where it moves the focus instead, nor on a
     * Compose field, whose node says one line also when it has more.
     */
    suspend fun submit(key: String): ActionResult {
        val field = field(key) ?: return NOT_ON_SCREEN
        if (!field.node.isEnabled) return DISABLED
        val connection = connect(field)
        if (connection != null) {
            val editor = withContext(Dispatchers.Main) { service.inputMethod?.currentInputEditorInfo }
            val action = (editor?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
            val multiLine = multiLine(field)
            // Android gives every field without an action of its own "next" or "done", also a field with more lines,
            // where Kestrel submits only with an action that sends something.
            if (action in (if (multiLine) SENDING_ACTIONS else REAL_ACTIONS)) {
                withContext(Dispatchers.Main) { connection.performEditorAction(action) }
                return settled(ActionMethod.INPUT_METHOD)
            }
            if (multiLine) return NO_ACTION
            withContext(Dispatchers.Main) { connection.enter() }
            return settled(ActionMethod.INPUT_METHOD)
        }
        if (field.node.isMultiLine) return NO_ACTION
        // A Compose node says one line also when its field has more, so without the input Kestrel cannot tell.
        if (composed(field.node)) return NOT_CONNECTED
        return if (field.node.performAction(IME_ENTER)) {
            settled(ActionMethod.ACTION)
        } else {
            ActionResult.Failed("The text field refused to submit.")
        }
    }

    /**
     * Whether the field with [key] has the focus and its app started an input with the search action, as a search box
     * and a web page's search field do. It focuses nothing.
     */
    suspend fun searches(key: String): Boolean {
        val field = field(key) ?: return false
        val editor = withContext(Dispatchers.Main) { startedEditor(field) } ?: return false
        return editor.imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEARCH
    }

    /**
     * Whether the field with [key] takes a phone number, as a dialpad's number field does: its node's input type, or the
     * input that its app started for it, has the phone class. It focuses nothing.
     */
    suspend fun phone(key: String): Boolean {
        val field = field(key) ?: return false
        return phone(field)
    }

    /**
     * Whether the input that the app started last, for a field of [packageName], is a password's by its type. A password
     * that "show password" shows loses the node's flag but keeps its input type. It reads only what Kestrel's input
     * method holds, without a binder call, so the controller's thread may ask it before it shows a transcript.
     */
    fun typingPassword(packageName: String?): Boolean {
        val inputMethod = service.inputMethod ?: return false
        if (!inputMethod.currentInputStarted) return false
        val editor = inputMethod.currentInputEditorInfo ?: return false
        return editor.packageName == packageName && isPassword(editor)
    }

    /**
     * Cuts, copies or pastes in the field with [key]. These actions act only on the field that has the focus (research
     * r17), so Kestrel focuses it first. Cut and copy need a selection, because Chrome answers true for an empty one and
     * does nothing. A cut, and a paste at the cursor, are read back.
     */
    suspend fun clipboard(key: String, kind: ClipboardKind): ActionResult {
        val field = field(key) ?: return NOT_ON_SCREEN
        if (!field.node.isEnabled) return DISABLED
        if (!focus(field)) return ActionResult.Failed("The text field did not take the focus.")
        val before = field.node.shownText().orEmpty()
        val (start, end) = selection(field.node, before)
        if (kind != ClipboardKind.PASTE && start == end) return SELECT_FIRST
        val action = when (kind) {
            ClipboardKind.CUT -> AccessibilityNodeInfo.ACTION_CUT
            ClipboardKind.COPY -> AccessibilityNodeInfo.ACTION_COPY
            ClipboardKind.PASTE -> AccessibilityNodeInfo.ACTION_PASTE
        }
        if (!field.node.performAction(action)) return ActionResult.Failed("The text field refused the action.")
        // A cut, and a paste at the cursor, must change the text; a paste over a selection may put back the same text.
        val changing = kind == ClipboardKind.CUT || (kind == ClipboardKind.PASTE && start == end)
        if (changing && !changes(field.node, before)) return NOT_TAKEN
        return settled(ActionMethod.ACTION)
    }

    /**
     * Types [text] at the cursor of the field with [key] through the input method. Password fields need it, because
     * Kestrel cannot read their text to set it whole; the read-back checks only the length. The field and its cursor are
     * read once it has connected, and a text whose length changed meanwhile gives [CHANGED].
     */
    suspend fun typeAtCursor(key: String, text: String): ActionResult {
        val field = field(key) ?: return NOT_ON_SCREEN
        if (!field.node.isEnabled) return DISABLED
        val first = field.node.shownText().orEmpty().length
        val connection = connect(field) ?: return NOT_CONNECTED
        if (!field.node.refresh()) return NOT_ON_SCREEN
        val before = field.node.shownText().orEmpty()
        if (before.length != first) return CHANGED
        val (start, end) = selection(field.node, before)
        withContext(Dispatchers.Main) { connection.commitText(text, 1, null) }
        return if (hasLength(field.node, before.length - (end - start) + text.length)) {
            settled(ActionMethod.INPUT_METHOD)
        } else {
            NOT_TAKEN
        }
    }

    /**
     * Deletes the selection, or else [count] characters before the cursor, of the field with [key], through the input
     * method. The field and its cursor are read once it has connected, as in [typeAtCursor].
     */
    suspend fun deleteBeforeCursor(key: String, count: Int): ActionResult {
        val field = field(key) ?: return NOT_ON_SCREEN
        if (!field.node.isEnabled) return DISABLED
        val first = field.node.shownText().orEmpty().length
        val connection = connect(field) ?: return NOT_CONNECTED
        if (!field.node.refresh()) return NOT_ON_SCREEN
        val before = field.node.shownText().orEmpty()
        if (before.length != first) return CHANGED
        val (start, end) = selection(field.node, before)
        val removed = if (end > start) end - start else minOf(count, start)
        if (removed == 0) return ActionResult.Failed("There is nothing before the cursor.")
        withContext(Dispatchers.Main) {
            if (end > start) connection.commitText("", 1, null) else connection.deleteSurroundingText(removed, 0)
        }
        return if (hasLength(field.node, before.length - removed)) settled(ActionMethod.INPUT_METHOD) else NOT_TAKEN
    }

    private class Field(val element: ScreenElement, val node: AccessibilityNodeInfo)

    /** The field with [key] in the current snapshot, with its live node. */
    private suspend fun field(key: String): Field? {
        val live = snapshots.current()
        val element = live.snapshot.element(key) ?: return null
        val node = live.node(key) ?: return null
        return if (node.refresh()) Field(element, node) else null
    }

    /**
     * Focuses [field], waits up to 2 s until its app has started the input with Kestrel's input method, and then up to
     * 1 s until the field has the focus, because a web page's field may start its input a moment before its node reports
     * the focus. Only then does it take the input connection, because the app may restart the input meanwhile. Null when
     * the input does not start, the field does not get the focus, or the input is no longer the field's at the end.
     */
    private suspend fun connect(field: Field): InputMethod.AccessibilityInputConnection? {
        val inputMethod = service.inputMethod ?: return null
        val before = withContext(Dispatchers.Main) { inputMethod.currentInputEditorInfo }
        val wasFocused = field.node.isFocused
        if (!wasFocused && !field.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            field.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(INPUT_START_MILLIS) {
                while (startedConnection(inputMethod, field, before, wasFocused) == null) delay(POLL_MILLIS)
                true
            }
        } ?: return null
        if (!waitFor { field.node.refresh() && field.node.isFocused }) return null
        return withContext(Dispatchers.Main) { startedConnection(inputMethod, field, before, wasFocused) }
    }

    /**
     * The connection of the input that [field]'s app started with Kestrel's input method, or null when the current input
     * is not one: the field must have had the focus already, or the input must be newer than [before].
     */
    private fun startedConnection(
        inputMethod: InputMethod,
        field: Field,
        before: EditorInfo?,
        wasFocused: Boolean,
    ): InputMethod.AccessibilityInputConnection? {
        val editor = inputMethod.currentInputEditorInfo
        // A window without a text field also starts an input, but with the type 0 and no connection (P4).
        val started = inputMethod.currentInputStarted && editor != null && editor.packageName == field.element.packageName &&
            editor.inputType != InputType.TYPE_NULL && (wasFocused || editor !== before)
        return if (started) inputMethod.currentInputConnection else null
    }

    // Compose offers its semantics ID as extra data on every node (androidx.compose.ui.platform, ExtraDataIdKey).
    private fun composed(node: AccessibilityNodeInfo): Boolean = node.availableExtraData.any { it.startsWith(COMPOSE_EXTRA_DATA) }

    /** Focuses [field] when it does not have the focus, and waits up to 2 s until it has. */
    private suspend fun focus(field: Field): Boolean {
        if (field.node.isFocused) return true
        if (!field.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) field.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return withTimeoutOrNull(INPUT_START_MILLIS) {
            while (!(field.node.refresh() && field.node.isFocused)) delay(POLL_MILLIS)
            true
        } ?: false
    }

    /**
     * Whether the field has more than one line: its node says so, or the input that its app started does, because Compose's
     * nodes always say one line (P4).
     */
    private suspend fun multiLine(field: Field): Boolean =
        field.node.isMultiLine || withContext(Dispatchers.Main) { startedEditor(field)?.let { multiLine(it) } } == true

    /**
     * Whether the field is a password field: its node says so, or the input that its app started does. A password that
     * "show password" shows loses the node's flag, but its input keeps the password's type.
     */
    private suspend fun password(field: Field): Boolean =
        field.node.isPassword || withContext(Dispatchers.Main) { startedEditor(field)?.let { isPassword(it) } } == true

    /** The input that the field's app started for it while it has the focus; null before that. */
    private fun startedEditor(field: Field): EditorInfo? {
        val inputMethod = service.inputMethod ?: return null
        if (!inputMethod.currentInputStarted || !field.node.isFocused) return null
        val editor = inputMethod.currentInputEditorInfo ?: return null
        if (editor.packageName != field.element.packageName || editor.inputType == InputType.TYPE_NULL) return null
        return editor
    }

    // Only the multi-line flag: the IME multi-line flag means that the field itself has one line (InputType).
    private fun multiLine(editor: EditorInfo): Boolean =
        editor.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            editor.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0

    // Not the visible-password variation: apps use it to turn suggestions off in fields that are no passwords.
    private fun isPassword(editor: EditorInfo): Boolean =
        editor.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION) in PASSWORD_TYPES

    /** Sets the whole [text] with the set-text action, then the selection, and reads it back. */
    private suspend fun setText(field: Field, text: String, selectionStart: Int, selectionEnd: Int): ActionResult {
        if (!field.node.refresh()) return NOT_ON_SCREEN
        // Compose reads the argument as a String, so it must be one (research r17).
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!field.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return NOT_TAKEN
        setSelection(field.node, selectionStart, selectionEnd)
        return if (shows(field.node, text, password(field), phone(field))) settled(ActionMethod.ACTION) else NOT_TAKEN
    }

    /**
     * Whether the field takes a phone number, as a dialpad's number field does: its node's input type, or the input that
     * its app started for it, has the phone class.
     */
    private suspend fun phone(field: Field): Boolean {
        if (field.node.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_PHONE) return true
        val editor = withContext(Dispatchers.Main) { startedEditor(field) } ?: return false
        return editor.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_PHONE
    }

    private fun setSelection(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean {
        val arguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, minOf(start, end))
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, maxOf(start, end))
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
    }

    /** The node's selection, with a negative one as the cursor at the end, in order and within [text]. */
    private fun selection(node: AccessibilityNodeInfo, text: String): Pair<Int, Int> {
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start < 0 || end < 0) return text.length to text.length
        return minOf(start, end).coerceAtMost(text.length) to maxOf(start, end).coerceAtMost(text.length)
    }

    /**
     * Whether the field shows [expected] within 1 s; for a password field, whether it has as many characters; for a phone
     * number field, whether it has the same dialable characters, because it may add spaces and brackets as it formats.
     */
    private suspend fun shows(node: AccessibilityNodeInfo, expected: String, password: Boolean, phone: Boolean = false): Boolean = when {
        password -> hasLength(node, expected.length)
        phone -> waitFor { node.refresh() && dialable(node.shownText().orEmpty()) == dialable(expected) }
        else -> waitFor { node.refresh() && sameText(node.shownText().orEmpty(), expected) }
    }

    // Web editors turn a space next to new words into a no-break space, or back, as they balance whitespace.
    private fun sameText(shown: String, expected: String): Boolean = shown.replace('\u00A0', ' ') == expected.replace('\u00A0', ' ')

    /** Whether [shown] is still [text]; a password field shows only dots, so there only the length counts. */
    private fun same(shown: String, text: String, password: Boolean): Boolean =
        if (password) shown.length == text.length else sameText(shown, text)

    private fun dialable(text: String): String = text.filter { it.isLetterOrDigit() || it in DIALABLE_MARKS }

    private suspend fun hasLength(node: AccessibilityNodeInfo, length: Int): Boolean =
        waitFor { node.refresh() && node.shownText().orEmpty().length == length }

    private suspend fun changes(node: AccessibilityNodeInfo, before: String): Boolean =
        waitFor { node.refresh() && node.shownText().orEmpty() != before }

    private suspend fun selects(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean =
        waitFor { node.refresh() && node.textSelectionStart == start && node.textSelectionEnd == end }

    /**
     * Checks [condition] every 50 ms for up to 1 s: an edit shows within 300 to 400 ms (P4), and a web page's field
     * reports the focus about 50 ms after its input has started.
     */
    private suspend fun waitFor(condition: () -> Boolean): Boolean {
        repeat(READ_BACK_TRIES) {
            if (condition()) return true
            delay(POLL_MILLIS)
        }
        return condition()
    }

    private fun InputMethod.AccessibilityInputConnection.enter() {
        val now = SystemClock.uptimeMillis()
        sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
    }

    /** Waits until the screen has been quiet after the action, but never longer than 3 s (design spec, section 9.3). */
    private suspend fun settled(method: ActionMethod): ActionResult {
        tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = tracker.now())
        return ActionResult.Done(method)
    }

    companion object {
        /** The reasons that the text commands tell the owner (design spec, section 9.7). */
        val ONE_LINE = ActionResult.Failed("This field has only one line.")
        val NO_ACTION = ActionResult.Failed("This field has no action to submit.")
        val SELECT_FIRST = ActionResult.Failed("Select some text first.")
        val NOT_TAKEN = ActionResult.Failed("The text field didn't take the change.")

        /** The text changed between the command's read and the edit, so the edit would land on other characters. */
        val CHANGED = ActionResult.Failed("The text changed. Say it again.")

        private val NOT_ON_SCREEN = ActionResult.Failed("The text field is not on the screen.")
        private val NOT_A_FIELD = ActionResult.Failed("The element is not a text field.")
        private val DISABLED = ActionResult.Failed("The text field is disabled.")
        private val NOT_CONNECTED = ActionResult.Failed("The text field did not connect to Kestrel's input method.")
        private const val DIALABLE_MARKS = "+*#,;"
        private val IME_ENTER = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        private val SENDING_ACTIONS = setOf(EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND)
        private val PASSWORD_TYPES = setOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        private val REAL_ACTIONS = setOf(
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_PREVIOUS,
        )
        private const val COMPOSE_EXTRA_DATA = "androidx.compose.ui.semantics."
        private const val INPUT_START_MILLIS = 2_000L
        private const val READ_BACK_TRIES = 20
        private const val POLL_MILLIS = 50L
    }
}
