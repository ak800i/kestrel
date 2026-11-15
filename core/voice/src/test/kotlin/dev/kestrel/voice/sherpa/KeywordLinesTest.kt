package dev.kestrel.voice.sherpa

import dev.kestrel.voice.Keyword
import org.junit.Assert.assertEquals
import org.junit.Test

class KeywordLinesTest {
    private val lines = KeywordLines.text().lines().filter { it.isNotEmpty() }

    @Test
    fun everyLineNamesAKeywordAndEveryKeywordHasALine() {
        val named = lines.map { line -> KeywordLines.keyword(line.substringAfterLast(" @")) }

        assertEquals(Keyword.entries.toSet(), named.toSet())
    }

    @Test
    fun anUnknownNameIsNoKeyword() {
        assertEquals(null, KeywordLines.keyword(""))
    }
}
