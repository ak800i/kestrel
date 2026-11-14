package dev.kestrel.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LicenceParagraphsTest {
    @Test
    fun joinsTheWrappedLinesOfAParagraph() {
        val text = "    GNU GENERAL PUBLIC LICENSE\n  Version 3, 29 June 2007\n\n Everyone is permitted to copy\n and distribute it.\n"

        assertEquals(
            listOf("GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007", "Everyone is permitted to copy and distribute it."),
            licenceParagraphs(text),
        )
    }

    @Test
    fun keepsTheLinesOfTablesAndLists() {
        assertEquals(listOf("| A | B |\n|---|---|", "- one\n- two"), licenceParagraphs("| A | B |\n|---|---|\n\n- one\n- two"))
    }

    @Test
    fun skipsExtraBlankLinesAndWindowsLineEnds() {
        assertEquals(listOf("first", "second"), licenceParagraphs("\r\nfirst\r\n\r\n  \r\n\r\nsecond\r\n"))
    }
}
