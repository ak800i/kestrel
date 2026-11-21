package dev.kestrel.agent.observe

import org.junit.Assert.assertEquals
import org.junit.Test

/** Design D8's quoting, escaping and cutting, and the token estimate of the cap. */
class ScreenTextTest {
    @Test
    fun escapingKeepsScreenTextOnOneLineInsideItsQuotes() {
        assertEquals("a\\\\b \\\"c\\\" d\\ne\\nf\\ng\\nh\\ni j", ScreenText.escape("a\\b \"c\" d\ne\rf\r\ng\u2028h\u2029i\tj"))
        val removed = "\u0000\u0007\u001B\u007F\u0085\u200B\u200C\u200D\u200E\u200F\u2060\uFEFF\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069" +
            "\uE000\uF8FF" + String(Character.toChars(0xE0041)) + String(Character.toChars(0xE007F)) + String(Character.toChars(0xF0000)) +
            String(Character.toChars(0x10FFFD))
        assertEquals("ok", ScreenText.escape("o${removed}k"))
        assertEquals("Ђорђе 😀 č", ScreenText.escape("Ђорђе 😀 č"))
    }

    @Test
    fun aLongTextIsCutWithAnEllipsisNeverInsideASurrogatePair() {
        assertEquals("abc", ScreenText.cut("abc", 3))
        assertEquals("ab…", ScreenText.cut("ab cd", 3))
        assertEquals("a…", ScreenText.cut("a😀b", 2))
        assertEquals("\"" + "x".repeat(80) + "…\"", ScreenText.quote("x".repeat(81), ScreenText.LABEL_LIMIT))
        assertEquals("\"a\\\"…\"", ScreenText.quote("a\"bc", 2))
    }

    @Test
    fun cyrillicCostsMoreTokensPerCharacter() {
        assertEquals(2, ScreenText.tokens("abcdefg"))
        assertEquals(3, ScreenText.tokens("abcdefgh"))
        assertEquals(2, ScreenText.tokens("ђорђе"))
        assertEquals(0, ScreenText.tokens(""))
    }

    @Test
    fun classesGetTheirShortNames() {
        assertEquals(
            listOf("Text", "Image", "List", "List", "Button", "View", "View", "Fake1Send", "View"),
            listOf(
                "android.widget.TextView", "android.widget.ImageView", "androidx.recyclerview.widget.RecyclerView", "android.widget.ListView",
                "android.widget.Button", "android.view.View", null, "x.Fake\n[1] \"Send\"", "x.\n\"",
            )
                .map { ScreenText.shortClass(it) },
        )
    }
}
