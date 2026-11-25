package dev.kestrel.app.goal

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug builds only (plan 7E, Task 8, Step 8a): a refusal of the goal service's next start, as Android refuses it, which
 * the hook `goal-control do=refuse` arms. The start takes it, so it refuses one start; `goal-control do=refuse-off` takes
 * it back when a check stops before that start, and `goal-service-state` shows whether one is armed (Task 6's review M2).
 * It lives in memory, so a new process starts with none. A release build never arms it.
 */
internal class GoalStartRefusal {
    private val armedNow = AtomicBoolean(false)

    val armed: Boolean get() = armedNow.get()

    fun arm() = armedNow.set(true)

    /** Leaves nothing armed: true when a refusal was armed, which the caller then owns. */
    fun take(): Boolean = armedNow.getAndSet(false)
}
