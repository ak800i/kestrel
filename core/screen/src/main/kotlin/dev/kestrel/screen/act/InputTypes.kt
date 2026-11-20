package dev.kestrel.screen.act

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** What an app's input says about its field (design spec, section 9.7), from the numbers of its EditorInfo. */
internal object InputTypes {
    /**
     * Whether an input of [inputType] and [imeOptions] takes the Enter key as a line break: a text input with the
     * multi-line flag, or one whose editor action is IME_ACTION_NONE, whose Enter key submits nothing, as Samsung Notes'
     * body (inv-oneui-a71, 4). The IME multi-line flag means that the field itself has one line (InputType).
     */
    fun multiLine(inputType: Int, imeOptions: Int): Boolean =
        inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 || imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_NONE)

    /**
     * Whether only the editor action "none" makes the input take a line break, because it has no multi-line flag. Such a
     * field may still have one line, whose app may take the Enter key as "send", so Kestrel types the line break as text
     * there and never presses Enter (plan 4D).
     */
    fun breakByAction(inputType: Int, imeOptions: Int): Boolean =
        multiLine(inputType, imeOptions) && inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0
}
