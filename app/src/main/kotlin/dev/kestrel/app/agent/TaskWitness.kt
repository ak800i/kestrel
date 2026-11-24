package dev.kestrel.app.agent

import dev.kestrel.agent.port.DataPort

/**
 * What a debug build observes of one task for its hooks (design D28 of sub-project 5; plan 6B-basefix): the texts that
 * Kestrel says with `say` ([said]), and the task's data tools, which [watch] may wrap. The debug source set installs a
 * witness per task through [AgentRunner.witnesses]; a release build has none, and its tasks get [NONE], which observes
 * nothing, so that only the debug build keeps what Kestrel said.
 */
internal interface TaskWitness {
    fun said(text: String)

    /** The data tools that the task uses: [data] itself, or a view of it that observes their results. */
    fun watch(data: DataPort): DataPort

    /** What the witness observed so far. */
    val evidence: TaskEvidence

    companion object {
        /** The witness of every task of a release build: it observes nothing. */
        val NONE: TaskWitness = object : TaskWitness {
            override fun said(text: String) = Unit

            override fun watch(data: DataPort): DataPort = data

            override val evidence: TaskEvidence get() = TaskEvidence()

            override fun toString(): String = "TaskWitness.NONE"
        }
    }
}

/**
 * What a task's witness observed: the texts that Kestrel said with `say` ([said]), and whether a data tool's result held
 * the run's marker ([markerInData]; null when the task had no marker). toString prints no text.
 */
internal data class TaskEvidence(val said: List<String> = emptyList(), val markerInData: Boolean? = null) {
    override fun toString(): String = "TaskEvidence(said=${said.size} texts, markerInData=$markerInData)"
}
