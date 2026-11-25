package dev.kestrel.app.agent

import dev.kestrel.agent.port.ModelPort
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.profiles.ModelRole
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/**
 * [inner], whose replies wait until the test lets each one go ([release]), so that a device test decides when the agent
 * goes on, not a fixed time (plan 7D's GoalPauseTest).
 */
internal class HeldModel(private val inner: ModelPort) : ModelPort by inner {
    private val releases = Channel<Unit>(Channel.UNLIMITED)
    private val asked = AtomicInteger()

    /** How many requests have reached the model, the waiting one too. */
    val calls: Int get() = asked.get()

    /** Lets the waiting reply go, or the next one when none waits. */
    fun release() {
        releases.trySend(Unit)
    }

    override suspend fun call(role: ModelRole, request: ChatRequest, step: Int): PricedResponse {
        asked.incrementAndGet()
        releases.receive()
        return inner.call(role, request, step)
    }

    override fun toString(): String = "HeldModel(calls=$calls)"
}
