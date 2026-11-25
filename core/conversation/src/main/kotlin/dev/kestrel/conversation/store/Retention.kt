package dev.kestrel.conversation.store

import dev.kestrel.conversation.engine.ConversationClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Keeps past conversations for the retention time after their last change (design 9.3 of sub-project 7): [keepPurging]
 * purges at once, again whenever the retention changes, so that a shorter retention counts at once, and otherwise once a
 * day. As `LogStore.keepPurging` does, it checks the wall clock every hour, because a coroutine's delay stops counting
 * while the phone sleeps, and purges when a day has passed on it since the last purge or the clock went back. The open
 * conversation is never purged. A failure is reported to [report] by its class, and the next check tries again. Plan 7C's
 * purge job runs it for as long as Kestrel's process lives.
 */
class Retention(
    private val store: ConversationStore,
    private val clock: ConversationClock,
    private val report: (String, Throwable) -> Unit,
) {
    /** Purges once; returns how many conversations went, 0 after a failure. */
    suspend fun purge(days: Int): Int = attempt(days) ?: 0

    suspend fun keepPurging(days: Flow<Int>) {
        days.distinctUntilChanged().collectLatest { retention ->
            var lastPurge: Long? = null
            while (true) {
                val now = clock.wallMillis()
                val last = lastPurge
                // After the clock went back, the interval check alone would wait until the clock caught up again.
                if ((last == null || now < last || now - last >= ArchivedConversationStore.DAY_MILLIS) && attempt(retention) != null) {
                    lastPurge = now
                }
                delay(CHECK_MILLIS)
            }
        }
    }

    private suspend fun attempt(days: Int): Int? = try {
        store.purge(days, clock.wallMillis())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report("Purging past conversations", e)
        null
    }

    override fun toString(): String = "Retention"

    private companion object {
        const val CHECK_MILLIS = 60 * 60_000L
    }
}
