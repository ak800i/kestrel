package dev.kestrel.eval.run

import dev.kestrel.eval.hook.Hooks

/**
 * The kind of Kestrel's refusal of a task before it ran (design D5 of sub-project 6; task-check.ps1's `Get-RefusalKind`).
 * Kestrel records every such task with the end NO_PROFILE, whatever the reason, so the kind comes from the sentence that
 * it spoke, which agent-task compares on the phone with words of each refusal's sentence; the tool never reads it.
 */
object Refusals {
    val KINDS: Map<String, String> = linkedMapOf(
        "test-build" to "only with a test script",
        "no-service" to "accessibility service is off",
        "no-watcher" to "watch the screen for your touches",
        "no-profile" to "no provider profile is set up",
        "cancelled" to "cancelled",
        "could-not-start" to "start the task",
    )

    fun kind(hooks: Hooks, tag: String): String = KINDS.entries.firstOrNull { (_, words) -> hooks.agentTask(tag, marker = words)["marker"] == "1" }?.key ?: "unknown"
}
