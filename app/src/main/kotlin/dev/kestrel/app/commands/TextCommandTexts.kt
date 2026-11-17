package dev.kestrel.app.commands

/**
 * What the text commands say and show (design spec, section 9.7). Kestrel speaks only what the owner must act on; the
 * other texts go to the feedback bar. The reasons of `TextEditor` ("This field has only one line." and the others) are
 * fixed texts of the screen layer.
 */
internal object TextCommandTexts {
    const val NO_FIELD = "No text field has the focus."
    const val NO_TEXT_FIELD = "I can't find a text field here."
    const val FIELD_GONE = "The text field is no longer on the screen."
    const val PASSWORD_FIELD = "I can't edit a password field that way."
    const val NOTHING_SELECTED = "Nothing is selected."
    const val NOTHING_THERE = "There's no text there."
    const val LINE_BREAK = "Say \"new line\" on its own."
    const val NOTHING_TO_UNDO = "There's nothing to undo."
    const val NOTHING_TO_REDO = "There's nothing to redo."
    const val KEYBOARD_NOT_SHOWING = "The keyboard is not showing."
    const val NO_PASSWORD_TOGGLE = "I can't find a way to show the password here."
    const val PASSWORD_SHOWING = "The password is showing already."
    const val NO_SEARCH_FIELD = "I can't find a search field here."
    const val PHONE_FIELD = "This field takes only a phone number."
    const val NOT_A_TEXT_FIELD = "It isn't a text field."

    /** For "undo" and "redo" when the field no longer shows the text of Kestrel's last edit. */
    fun textChanged(redo: Boolean) =
        "The text changed since my last edit, so I can't ${if (redo) "redo" else "undo"} it."

    /** [phrase] is the owner's words as the recogniser wrote them. Kestrel says this text and never logs it. */
    fun notInText(phrase: String) = "I can't find $phrase in the text."
}
