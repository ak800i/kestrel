package dev.kestrel.app.conversation

import dev.kestrel.conversation.model.ConversationSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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

    // A failed read never ends the following: the next change of the Conversations page still reaches the conversation,
    // and a value that came before the failure stays until the store answers again (whole-plan review of plan 7D, nits).
    @Test
    fun aFailedReadKeepsFollowingTheStore() = runTest {
        val failures = mutableListOf<String>()
        val report: (String, Throwable) -> Unit = { what, _ -> failures += what }
        var reads = 0
        val failsFirst = flow {
            if (reads++ == 0) throw IOException("The disk failed")
            emit(ConversationSettings(quietSeconds = 300))
        }

        assertEquals(
            listOf(ConversationSettings(), ConversationSettings(quietSeconds = 300)),
            storedConversationSettings(failsFirst, report).take(2).toList(),
        )

        reads = 0
        val failsLater = flow {
            when (reads++) {
                0 -> {
                    emit(ConversationSettings(quietSeconds = 300))
                    throw IOException("The disk failed")
                }
                1 -> throw IOException("The disk failed again")
                else -> emit(ConversationSettings(quietSeconds = 600))
            }
        }
        assertEquals(
            listOf(ConversationSettings(quietSeconds = 300), ConversationSettings(quietSeconds = 600)),
            storedConversationSettings(failsLater, report).take(2).toList(),
        )
        // Each run of failed reads is reported once.
        assertEquals(List(2) { "Reading the conversation settings" }, failures)
    }
}
