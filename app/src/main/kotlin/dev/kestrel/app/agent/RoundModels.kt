package dev.kestrel.app.agent

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.port.ModelPort

/**
 * How a round's model is chosen (design D28 of sub-project 5): a release build ([debuggable] false) always uses the
 * default profile's models ([profile]). In a debuggable build a round runs only with a model that a debug hook or a device
 * test installed ([modelOverride]), or, with the hook's extra `live`, while a live sitting is open ([liveSession]), with
 * the extra `model` as its lightweight model; otherwise it is refused, so that no plan step, device test or script can
 * call a provider.
 */
internal class RoundModels(
    private val debuggable: Boolean,
    private val liveSession: suspend () -> Boolean,
    private val profile: suspend (lightweight: String?) -> ModelPort?,
) {
    /** What [choose] gives: the round's model, or why it cannot have one. */
    sealed interface Choice {
        data class Model(val port: ModelPort) : Choice

        data class Refused(val sentence: String, val end: EndReason) : Choice {
            override fun toString(): String = "Refused(${sentence.length} characters, end=$end)"
        }
    }

    /** Debug builds only: the model that the hook `agent-script` or a device test installed (design D28). */
    @Volatile
    internal var modelOverride: ModelPort? = null

    /** The model of a round whose input carried the debug hook's [options]. */
    suspend fun choose(options: Map<String, String>): Choice {
        val override = modelOverride
        val port = when {
            !debuggable -> profile(null)
            options[LIVE] == "true" && liveSession() -> profile(options[MODEL])
            override != null -> override
            else -> return Choice.Refused(AgentTexts.couldNotStart(AgentTexts.TEST_BUILD), EndReason.NO_PROFILE)
        }
        return port?.let { Choice.Model(it) } ?: Choice.Refused(AgentTexts.NO_PROFILE, EndReason.NO_PROFILE)
    }

    override fun toString(): String = "RoundModels(debuggable=$debuggable, override=${modelOverride != null})"

    companion object {
        /** The debug hook's extras (plan 4D's options). */
        const val MODEL = "model"
        const val LIVE = "live"
    }
}
