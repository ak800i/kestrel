package dev.kestrel.commands

/** How an instant command ended. */
enum class Outcome { DONE, FAILED, IGNORED, CANCELLED }

/**
 * What an instant command ends with (design spec, section 9.5). [say] is spoken, [show] goes to the feedback bar.
 * [repeatable]: "again" may repeat it. [passOn]: an answer that did not answer the command's question, which the
 * Orchestrator reads as a new utterance. [acted]: the command that acted, when it differs from the one that ran, as
 * after the fallback to a bare label. [detail]: the route that the command took, a fixed word such as "panel", "tile"
 * or "page" for the radios, which the debug hook voice-state reports (plan 4D); never text of the screen or the owner.
 */
data class CommandResult(
    val outcome: Outcome,
    val say: String? = null,
    val show: String? = null,
    val repeatable: Boolean = false,
    val passOn: String? = null,
    val acted: Command? = null,
    val detail: String? = null,
) {
    override fun toString() =
        "CommandResult($outcome, say=${say?.length}, show=${show?.length}, repeatable=$repeatable, " +
            "passOn=${passOn?.length}, acted=${acted?.javaClass?.simpleName}, detail=$detail)"
}
