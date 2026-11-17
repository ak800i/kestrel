package dev.kestrel.commands.text

import org.junit.Assert.assertEquals
import org.junit.Test

class JaroWinklerTest {
    @Test
    fun givesTheKnownValues() {
        assertEquals(0.9611, JaroWinkler.similarity("martha", "marhta"), 0.0001)
        assertEquals(0.8133, JaroWinkler.similarity("dixon", "dicksonx"), 0.0001)
        assertEquals(0.8667, JaroWinkler.similarity("save", "safe"), 0.0001)
    }

    @Test
    fun equalAndEmptyStrings() {
        assertEquals(1.0, JaroWinkler.similarity("send", "send"), 0.0)
        assertEquals(1.0, JaroWinkler.similarity("", ""), 0.0)
        assertEquals(0.0, JaroWinkler.similarity("send", ""), 0.0)
        assertEquals(0.0, JaroWinkler.similarity("abc", "xyz"), 0.0)
    }
}
