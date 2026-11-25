package dev.kestrel.app.conversation

import dev.kestrel.conversation.model.ConversationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * The Conversations page's choices as the conversation reads them (design 13 of sub-project 7): a cold flow whose first
 * value is the stored one, so that the restore at the process's start decides with the owner's quiet time, never with
 * the defaults that a StateFlow holds before the store answers. A failed read gives the defaults, and [report] gets it.
 */
internal fun storedConversationSettings(stored: Flow<ConversationSettings>, report: (String, Throwable) -> Unit): Flow<ConversationSettings> =
    stored.catch { error ->
        report("Reading the conversation settings", error)
        emit(ConversationSettings())
    }
