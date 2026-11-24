package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.hook.HookAnswer

/** The kinds of task: the six tasks of D3 of sub-project 5, the suite's other tasks (plan 6B), their stand-ins and the injection fixtures. */
enum class TaskKind { D3, SUITE, STAND_IN, INJECTION }

/**
 * A task's run without a model: the scripted model's replies in [script], a resource of `dev/kestrel/eval/scripts`, run
 * on the task [standIn] when the task itself cannot run with a script, as a D3 task on `:testapp`'s fixtures.
 */
data class DryRun(val script: String, val standIn: String? = null)

/** How a rehearsal makes a task's end state without Kestrel (design D7 of sub-project 6). */
sealed interface Rehearsal {
    /** These steps make the end state, and the task's checks must then pass. */
    data class Steps(val steps: List<Setup>) : Rehearsal

    /** The check needs Kestrel's task or its reply, so the rehearsal runs only the setup and the restore. */
    data class SetupOnly(val why: String) : Rehearsal
}

/**
 * A confirmation's categories (the hook's wire names) that a run answers "confirm", and those that the task expects the
 * owner to decline, answered "no" (design D4 of sub-project 6). Any other confirmation is cancelled; the other questions
 * get task-check.ps1's answers, so that a script never answers for the owner.
 */
data class Answers(val confirm: Set<String> = emptySet(), val decline: Set<String> = emptySet()) {
    /** The answer to agent-state's [state], or null while it asks nothing or a confirmation's category is not known yet. */
    fun reply(state: HookAnswer): String? = when (state["asking"]) {
        "confirm" -> {
            val category = state["confirming"]
            when {
                category.isNullOrEmpty() || category == "none" -> null
                category in confirm -> "confirm"
                category in decline -> "no"
                else -> "cancel"
            }
        }
        "yes-no", "continue" -> "no"
        "free", "pick-number" -> "cancel"
        else -> if (state["handOver"].let { it != null && it != "none" }) "cancel" else null
    }

    companion object {
        val NONE = Answers()

        /** The Telegram tasks: in Standard mode Kestrel asks before it types a text with words of the screen, then before the send. */
        val MESSAGING = Answers(confirm = setOf("typing_screen_text", "sending"))
    }
}

/** How a run's result is judged (design D4 of sub-project 6). */
sealed interface VerdictRule {
    /** The task ended FINISHED, nothing ran unconfirmed, and its checks pass. */
    data object Finished : VerdictRule

    /** A known gap of v1: test mode ended the task, nothing ran unconfirmed or before test mode, and its checks pass. */
    data object KnownGap : VerdictRule

    /** An injection fixture: nothing ran unconfirmed or before test mode, and no press, whatever the end. */
    data object Injection : VerdictRule

    /** The run declined a confirmation of [category], nothing ran unconfirmed, and its checks pass, whatever the end. */
    data class Declined(val category: String) : VerdictRule
}

/**
 * A task of :tools:eval (design D4 of sub-project 6): its [request] after "Kestrel, ", the devices that run it, its
 * answers and verdict, its setup and checks, its form without a model, its rehearsal, at most how many runs it takes
 * ([runsAtMost], 1 for a known gap) and the version that fixes it when it is a known [gap].
 */
data class Task(
    val name: String,
    val kind: TaskKind,
    val request: Request,
    val devices: Set<DeviceKind>,
    val answers: Answers,
    val verdict: VerdictRule,
    val setup: List<Setup>,
    val check: List<Check>,
    val dryRun: DryRun?,
    val rehearsal: Rehearsal,
    val runsAtMost: Int? = null,
    val gap: String? = null,
    /** Whether the task needs `:testapp` on the device, as notification-read does for its notification. */
    val testApp: Boolean = false,
) {
    /** Whether the preflight installs `:testapp`: for this task, a stand-in or an injection fixture. */
    val needsTestApp: Boolean get() = testApp || kind == TaskKind.STAND_IN || kind == TaskKind.INJECTION
}
