package dev.kestrel.voice.sherpa

import dev.kestrel.voice.Keyword
import dev.kestrel.voice.KeywordTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordLinesTest {
    private val lines = KeywordLines.text().lines().filter { it.isNotEmpty() }

    @Test
    fun everyLineNamesAKeywordAndEveryKeywordHasALine() {
        val named = lines.map { line -> KeywordLines.keyword(line.substringAfterLast(" @")) }

        assertEquals(Keyword.entries.toSet(), named.toSet())
    }

    @Test
    fun theTuningGoesOnEveryLine() {
        val tuned = KeywordLines.text(KeywordTuning(boost = 3.5f, threshold = 0.05f)).lines().filter { it.isNotEmpty() }

        assertEquals(lines.size, tuned.size)
        assertTrue(tuned.all { " :3.5 #0.05 @" in it })
        // Kestrel's values since plan 2A.
        assertTrue(lines.all { " :2.0 #0.15 @" in it })
    }

    @Test
    fun anUnknownNameIsNoKeyword() {
        assertEquals(null, KeywordLines.keyword(""))
    }
}
