package dev.kestrel.app.commands

import dev.kestrel.commands.edit.EditHistory
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot

/**
 * When dictation is on (design spec, section 9.7): while a text field has the focus, unless the owner said "stop editing"
 * for that field. A change of the focus ends "stop editing", and so does "start editing". It also keeps Kestrel's own
 * history of its edits for "undo" and "redo", and which field it last saw as a password field. It holds the keys of
 * fields, never their text.
 */
internal class EditingState {
    private class Field(val packageName: String?, val key: String)

    @Volatile
    private var stopped: Field? = null

    val history = EditHistory()

    /** The field that dictation types into, or null while dictation is off. */
    fun dictating(snapshot: ScreenSnapshot): ScreenElement? {
        val field = TextFields.focused(snapshot)
        val stop = stopped
        if (stop != null && (field == null || field.key != stop.key || field.packageName != stop.packageName)) stopped = null
        return field?.takeIf { stopped == null }
    }

    /** "Stop editing" for [field]. */
    fun stop(field: ScreenElement) {
        stopped = Field(field.packageName, field.key)
    }

    /** "Start editing". */
    fun resume() {
        stopped = null
    }

    // The field that Kestrel last saw as a password field, and whether it has had the focus since. "Show password" clears
    // the node's flag, and a web page turns the input into a plain one, but the field still holds the password.
    private var passwordField: String? = null
    private var passwordFocused = false

    /**
     * Whether [field] holds a password: [password] says so now, or Kestrel saw it as a password field before and no other
     * text field has had the focus since that field had it. [focused] is the text field that has the focus now.
     */
    @Synchronized
    fun password(field: ScreenElement, password: Boolean, focused: ScreenElement? = field): Boolean {
        follow(focused)
        val id = id(field)
        if (password) {
            passwordField = id
            passwordFocused = focused?.let(::id) == id
        }
        return password || id == passwordField
    }

    /**
     * Forgets the password field once it has had the focus and another text field has it now. While no text field has
     * the focus, as when the page's own "show password" button took it, the record stays.
     */
    @Synchronized
    fun follow(focused: ScreenElement?) {
        val remembered = passwordField ?: return
        val now = focused?.let(::id) ?: return
        if (now == remembered) {
            passwordFocused = true
        } else if (passwordFocused) {
            passwordField = null
            passwordFocused = false
        }
    }

    /** The name of [field] in the history: its app and its key. */
    fun id(field: ScreenElement): String = "${field.packageName}/${field.key}"
}
