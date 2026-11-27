package dev.kestrel.app.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceRestoreTest {
    @Test
    fun theRestoresAgeAndTheGuardAreAnsweredInWholeSecondsAndNoneWithoutARestore() {
        assertEquals("restoredAgo=none guard=120", VoiceRestore.answer(null, 1_795_086_000_000))
        assertEquals("restoredAgo=125 guard=120", VoiceRestore.answer(1_795_086_000_000, 1_795_086_125_999))
        assertEquals("restoredAgo=0 guard=120", VoiceRestore.answer(1_795_086_000_000, 1_795_086_000_000))
    }
}
