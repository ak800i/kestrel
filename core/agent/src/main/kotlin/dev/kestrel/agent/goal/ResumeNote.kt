package dev.kestrel.agent.goal

/** Why a goal resumes in a new run (design 7.6 of sub-project 7), in the words of its task message. */
enum class ResumeCause(val words: String) {
    LIMIT("a pause at a limit"),
    INTERRUPTION("an interruption"),
    RESTART("a restart of Kestrel"),
}

/**
 * Set when a goal resumes in a new run (design 7.6 of sub-project 7): why, what Kestrel did so far, its ledger lines in
 * Kestrel's own words, which the run's task message names, so that no earlier step is done again, and the owner's
 * steering that the goal's earlier rounds took ([steered], oldest first, in its record's form), which the message names as
 * his instructions (plan 7A at plan 7B's request). Steering that still waits in the goal's inbox reaches the run through
 * `GoalPort.takeSteering()` and is not in [steered]. toString prints only counts.
 */
data class ResumeNote(val after: ResumeCause, val done: List<String>, val steered: List<Steer> = emptyList()) {
    override fun toString(): String = "ResumeNote(after=$after, done=${done.size}, steered=${steered.size})"
}
