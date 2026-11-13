package dev.kestrel.privacy.logs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ErrorSummaryTest {
    @Test
    fun leavesOutMessagesButKeepsClassesFramesAndCauses() {
        val error = IllegalStateException("Message from Ana: see you at 5", IOException("token=secret"))

        val text = error.describeWithoutMessages()

        assertFalse(text, text.contains("Ana"))
        assertFalse(text, text.contains("secret"))
        assertTrue(text, text.startsWith("java.lang.IllegalStateException\n    at dev.kestrel.privacy.logs.ErrorSummaryTest"))
        assertTrue(text, text.contains("Caused by: java.io.IOException"))
    }
}
