package dev.kestrel.eval.run

import dev.kestrel.eval.task.Answers

/**
 * One of the task's questions or hand-overs as the run met it (plan 6B-basefix T1): its [kind] ([Answers.kind]) and the
 * answer that reached Kestrel ([reply]), or null for a question that the run left open on purpose, such as look_closer's
 * "Which number should I use?". An answer that the hook did not deliver is never an [Asked].
 */
data class Asked(val kind: String, val reply: String?) {
    /** Whether the run's own answer ended the task ([Answers.endsTask]). */
    val endsTask: Boolean get() = reply != null && Answers.endsTask(kind, reply)

    override fun toString(): String = "$kind:${reply ?: "none"}"
}
