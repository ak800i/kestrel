package dev.kestrel.screen.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabelsTest {
    @Test
    fun suggestedNamesDropGenericWordsFromTheViewId() {
        assertEquals("compose", Labels.suggestedName("dev.app:id/compose_button"))
        assertEquals("send message", Labels.suggestedName("dev.app:id/btnSendMessage"))
        assertEquals("search", Labels.suggestedName("dev.app:id/ic_search_24"))
        assertNull(Labels.suggestedName("dev.app:id/fab"))
        assertNull(Labels.suggestedName(null))
    }

    @Test
    fun cleanCollapsesWhitespaceAndJoinKeepsAtMostEightyCharacters() {
        assertEquals("Send now", Labels.clean("  Send \n now "))
        assertNull(Labels.clean(" \t "))
        assertEquals(80, Labels.join(listOf("a".repeat(50), "b".repeat(50)))!!.length)
    }
}
