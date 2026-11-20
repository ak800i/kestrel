package dev.kestrel.screen.act

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which inputs take the Enter key as a line break (design spec, section 9.7; inv-oneui-a71, 4). */
class InputTypesTest {
    @Test
    fun anInputTakesALineBreakWithTheMultiLineFlagOrWithoutAnEditorAction() {
        // Samsung Notes' body on the A71: text with capital sentences, IME_ACTION_NONE and IME_FLAG_NO_EXTRACT_UI.
        assertTrue(InputTypes.multiLine(0x4001, 0x10000001))
        assertTrue(InputTypes.multiLine(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_DONE))
        assertFalse(InputTypes.multiLine(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE))
        assertFalse(InputTypes.multiLine(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED))
        assertFalse(InputTypes.multiLine(InputType.TYPE_CLASS_PHONE, EditorInfo.IME_ACTION_NONE))
        // Only the action "none" makes it multi-line: the line break is typed as text, never with the Enter key.
        assertTrue(InputTypes.breakByAction(0x4001, 0x10000001))
        assertFalse(InputTypes.breakByAction(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_NONE))
        assertFalse(InputTypes.breakByAction(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE))
    }
}
