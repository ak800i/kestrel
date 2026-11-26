package dev.kestrel.app.debug

import android.app.ForegroundServiceStartNotAllowedException
import dev.kestrel.app.AppGraph
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A refusal of the goal service's next start, as Android refuses it (plan 7E, Task 8, Step 8a), which the hook
 * `goal-control do=refuse` arms. The start takes it, so it refuses one start; `goal-control do=refuse-off` takes it back
 * when a check stops before that start, and `goal-service-state` shows whether one is armed (Task 6's review M2). It lives
 * in memory, so a new process starts with none. It exists only in debug builds: [of] installs it as the app graph's
 * `goalStartGate`, which a release build leaves null (the whole-plan review's I2).
 */
internal class GoalStartRefusal {
    private val armedNow = AtomicBoolean(false)

    val armed: Boolean get() = armedNow.get()

    fun arm() = armedNow.set(true)

    /** Leaves nothing armed: true when a refusal was armed, which the caller then owns. */
    fun take(): Boolean = armedNow.getAndSet(false)

    /** The start gate: throws [refusal]'s exception when a refusal was armed, which this start then takes. */
    fun check(refusal: () -> Exception) {
        if (take()) throw refusal()
    }

    companion object {
        private val process = GoalStartRefusal()

        /** The process's refusal, installed first as [graph]'s gate of the goal service's starts. */
        @Synchronized
        fun of(graph: AppGraph): GoalStartRefusal {
            if (graph.goalStartGate == null) {
                graph.goalStartGate = { process.check { ForegroundServiceStartNotAllowedException("goal-control do=refuse") } }
            }
            return process
        }
    }
}