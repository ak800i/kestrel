package dev.kestrel.app.conversation

import dev.kestrel.conversation.model.ConversationSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** The settings that the conversation's restore decides with (design 13 of sub-project 7). */
class StoredSettingsTest {
    @Test
    fun theFirstSettingsAreTheStoredOnesAndAFailedReadGivesTheDefaults() = runTest {
        val failures = mutableListOf<String>()
        val report: (String, Throwable) -> Unit = { what, _ -> failures += what }
        val stored = flow {
            delay(100)
            emit(ConversationSettings(quietSeconds = 300))
        }

        assertEquals(ConversationSettings(quietSeconds = 300), storedConversationSettings(stored, report).first())
        assertEquals(ConversationSettings(), storedConversationSettings(flow { throw IOException("The disk failed") }, report).first())
        assertEquals(listOf("Reading the conversation settings"), failures)
    }
}
