package dev.kestrel.app.debug

import dev.kestrel.agent.port.DataPort
import dev.kestrel.app.agent.TaskEvidence
import dev.kestrel.app.agent.TaskWitness

/**
 * The debug build's witness of one task (plan 6B-basefix): it keeps the newest [MAX_SAID] texts that Kestrel said with
 * `say`, for agent-task's marker and digits, and, when the task's options carry a run's [marker] (`voice -Send heard
 * --es marker`), it watches the task's data tools for it ([MarkerWatch]). VoiceHooks installs [open] as
 * AgentRunner.witnesses beside Listening.taskOptions. The texts stay in memory, with the newest tasks' ends that
 * AgentRunner keeps.
 */
internal class DebugTaskWitness(private val marker: String?) : TaskWitness {
    private val texts = ArrayDeque<String>()

    @Volatile
    private var watched: MarkerWatch? = null

    override fun said(text: String) {
        synchronized(texts) {
            texts.addLast(text)
            if (texts.size > MAX_SAID) texts.removeFirst()
        }
    }

    override fun watch(data: DataPort): DataPort = marker?.let { MarkerWatch(data, it).also { watch -> watched = watch } } ?: data

    override val evidence: TaskEvidence
        get() = TaskEvidence(synchronized(texts) { texts.toList() }, marker?.let { watched?.seen ?: false })

    override fun toString(): String = "DebugTaskWitness(marker=${marker != null})"

    companion object {
        /** How many of a task's newest `say` texts the witness keeps. */
        const val MAX_SAID = 32

        /** The task option that carries a run's marker (VoiceHooks.taskOptions). */
        const val MARKER = "marker"

        /** The witness of a task with [options]. */
        fun open(options: Map<String, String>): TaskWitness = DebugTaskWitness(options[MARKER]?.takeIf { it.isNotBlank() })
    }
}
