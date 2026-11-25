package dev.kestrel.app.agent

import dev.kestrel.agent.port.DataPort

/**
 * What a debug build observes of one round of a goal for its hooks (design D28 of sub-project 5; plan 6B-basefix): the
 * texts that Kestrel says with `say` ([said]), and the round's data tools, which [watch] may wrap. The debug source set
 * installs a witness per round through [AppGoalEnvironment.witnesses]; a release build has none, and its rounds get
 * [NONE], which observes nothing. Kestrel's final words are the conversation's (its command's outcome and record, design
 * 8.1 and 14.4 of sub-project 7), so the witness does not keep them.
 */
internal interface TaskWitness {
    fun said(text: String)

    /** The data tools that the round uses: [data] itself, or a view of it that observes their results. */
    fun watch(data: DataPort): DataPort

    /** What the witness observed so far. */
    val evidence: TaskEvidence

    companion object {
        /** The witness of every round of a release build: it observes nothing. */
        val NONE: TaskWitness = object : TaskWitness {
            override fun said(text: String) = Unit

            override fun watch(data: DataPort): DataPort = data

            override val evidence: TaskEvidence get() = TaskEvidence()

            override fun toString(): String = "TaskWitness.NONE"
        }
    }
}

/**
 * What a round's witness observed: the texts that Kestrel said with `say` ([said]), and whether a data tool's result held
 * the run's marker ([markerInData]; null when the round had no marker). toString prints no text.
 */
internal data class TaskEvidence(val said: List<String> = emptyList(), val markerInData: Boolean? = null) {
    override fun toString(): String = "TaskEvidence(said=${said.size} texts, markerInData=$markerInData)"
}
