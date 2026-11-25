package dev.kestrel.app.conversation

import dev.kestrel.conversation.model.ConversationSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen

/**
 * The Conversations page's choices as the conversation reads them (design 13 of sub-project 7): a cold flow whose first
 * value is the stored one, so that the restore at the process's start decides with the owner's quiet time, never with
 * the defaults that a StateFlow holds before the store answers. A failed read never ends it: before the first value it
 * gives the defaults, after one it keeps the last value, and either way it reads the store again after [retryMillis]
 * and follows it on, so that a later change of the page still reaches the conversation. [report] gets the first failure
 * of each run of failed reads.
 */
internal fun storedConversationSettings(
    stored: Flow<ConversationSettings>,
    report: (String, Throwable) -> Unit,
    retryMillis: Long = SETTINGS_RETRY_MILLIS,
): Flow<ConversationSettings> = flow {
    var given = false
    var failing = false
    val following = stored
        .onEach {
            given = true
            failing = false
        }
        .retryWhen { error, _ ->
            if (!failing) report("Reading the conversation settings", error)
            failing = true
            if (!given) {
                given = true
                emit(ConversationSettings())
            }
            delay(retryMillis)
            true
        }
    following.collect { emit(it) }
}.distinctUntilChanged()

/** How long a failed read of the conversation settings waits before it reads the store again. */
internal const val SETTINGS_RETRY_MILLIS = 5_000L
