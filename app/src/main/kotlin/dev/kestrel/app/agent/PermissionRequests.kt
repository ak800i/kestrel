package dev.kestrel.app.agent

import dev.kestrel.agent.port.PermissionEnd
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * The permission requests of tasks that wait for [PermissionActivity] (design D12 and D15 of sub-project 5). [ask] reads
 * the permissions first and asks nothing when Android granted them all; otherwise it starts the activity with a new
 * request number and waits until the activity has ended that request, whether Android answered or the activity went away,
 * and then reads the permissions again, so that a lost answer never hangs a task and a partial grant (the photos that the
 * owner selected, from Android 14) counts as what Android really granted. An activity that does not start ends the request
 * at once. A request that no activity ends is cancelled with the task's job, as the hand-over's 3 minutes, "Kestrel,
 * continue" and "stop" do; then the finish that its activity [register]ed closes the activity and Android's dialog, so that
 * no stale dialog stays in a hidden task. The numbers start from a random base in each process, so that an activity that
 * an earlier process left never ends a request of this one. Any thread.
 */
internal object PermissionRequests {
    private val waiting = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    private val finishes = ConcurrentHashMap<Long, () -> Unit>()
    private val numbers = AtomicLong(Random.nextLong(1L, Long.MAX_VALUE / 2))

    suspend fun ask(start: (Long) -> Boolean, granted: () -> PermissionEnd): PermissionEnd {
        if (granted() == PermissionEnd.GRANTED) return PermissionEnd.GRANTED
        val id = next()
        val ended = CompletableDeferred<Unit>()
        waiting[id] = ended
        try {
            if (start(id)) ended.await()
        } finally {
            waiting.remove(id)
            val finish = finishes.remove(id)
            if (!ended.isCompleted) finish?.invoke()
        }
        return granted()
    }

    private fun next(): Long = numbers.incrementAndGet()

    /** Whether request [id] still waits for its activity. */
    fun waits(id: Long): Boolean = waiting.containsKey(id)

    /** Ends request [id]: its activity got Android's answer or went away. Nothing happens for a request that does not wait. */
    fun ended(id: Long) {
        waiting[id]?.complete(Unit)
    }

    /** [finish] closes the activity of request [id] when the request ends without an answer; it may run on any thread. */
    fun register(id: Long, finish: () -> Unit) {
        finishes[id] = finish
    }

    /** Forgets [finish] of request [id], unless another instance of its activity registered its own since. */
    fun unregister(id: Long, finish: () -> Unit) {
        finishes.remove(id, finish)
    }
}