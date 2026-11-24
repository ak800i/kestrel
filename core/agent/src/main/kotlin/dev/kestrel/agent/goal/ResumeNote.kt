package dev.kestrel.agent.goal

/** Why a goal resumes in a new run (design 7.6 of sub-project 7), in the words of its task message. */
enum class ResumeCause(val words: String) {
    LIMIT("a pause at a limit"),
    INTERRUPTION("an interruption"),
    RESTART("a restart of Kestrel"),
}

/**
 * Set when a goal resumes in a new run (design 7.6 of sub-project 7): why, and what Kestrel did so far, its ledger lines
 * in Kestrel's own words, which the run's task message names, so that no earlier step is done again. toString prints only
 * counts.
 */
data class ResumeNote(val after: ResumeCause, val done: List<String>) {
    override fun toString(): String = "ResumeNote(after=$after, done=${done.size})"
}
