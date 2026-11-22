package dev.kestrel.app.agent

import dev.kestrel.agent.port.PermissionEnd
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The permission requests of tasks that wait for [PermissionActivity] (design D12 and D15 of sub-project 5). [ask] reads
 * the permissions first and asks nothing when Android granted them all; otherwise it starts the activity with a new
 * request number and waits until the activity has ended that request, whether Android answered or the activity went away,
 * and then reads the permissions again, so that a lost answer never hangs a task and a partial grant (the photos that the
 * owner selected, from Android 14) counts as what Android really granted. An activity that does not start ends the request
 * at once. A request that no activity ends is cancelled with the task's job, as the hand-over's 3 minutes do. Any thread.
 */
internal object PermissionRequests {
    private val waiting = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    private val numbers = AtomicLong()

    suspend fun ask(start: (Long) -> Boolean, granted: () -> PermissionEnd): PermissionEnd {
        if (granted() == PermissionEnd.GRANTED) return PermissionEnd.GRANTED
        val id = numbers.incrementAndGet()
        val ended = CompletableDeferred<Unit>()
        waiting[id] = ended
        try {
            if (start(id)) ended.await()
        } finally {
            waiting.remove(id)
        }
        return granted()
    }

    /** Whether request [id] still waits for its activity. */
    fun waits(id: Long): Boolean = waiting.containsKey(id)

    /** Ends request [id]: its activity got Android's answer or went away. Nothing happens for a request that does not wait. */
    fun ended(id: Long) {
        waiting[id]?.complete(Unit)
    }
}
