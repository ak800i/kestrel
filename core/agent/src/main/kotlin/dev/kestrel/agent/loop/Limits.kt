package dev.kestrel.agent.loop

import dev.kestrel.agent.port.AgentClock

/**
 * A task's working time and credits (design D7): the time spent waiting for the owner and a paused task do not count as
 * work, and every model answer's credits add up.
 */
class Limits(private val clock: AgentClock) {
    private val started = clock.elapsedMillis()
    private var waited = 0L

    var credits = 0.0
        private set

    val workMillis: Long get() = clock.elapsedMillis() - started - waited

    /** Runs [block], a wait for the owner or a pause, whose time does not count as work. */
    suspend fun <T> waiting(block: suspend () -> T): T {
        val start = clock.elapsedMillis()
        try {
            return block()
        } finally {
            waited += clock.elapsedMillis() - start
        }
    }

    fun spent(credits: Double) {
        this.credits += credits
    }

    override fun toString(): String = "Limits(workMillis=$workMillis, credits=$credits)"
}
