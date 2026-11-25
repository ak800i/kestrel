package dev.kestrel.eval.run

import dev.kestrel.eval.hook.Hooks

/**
 * The kind of Kestrel's refusal of a task before it ran (design D5 of sub-project 6; task-check.ps1's `Get-RefusalKind`).
 * From plan 7D on, a goal's round that could not start ends as NO_SCREEN when Kestrel's accessibility service is off or
 * the touch watcher cannot start, and as NO_PROFILE without a profile or in a test build without a script ([ENDS]). The
 * kind comes from the sentence that Kestrel spoke, which agent-task compares on the phone with words of each refusal's
 * sentence; the tool never reads it. A stop before the round started ends as CANCELLED and a failed start as
 * KESTREL_ERROR, which fail a run by their end, so they are no refusal kinds any more.
 */
object Refusals {
    /** The ends of a goal's round that could not start. */
    val ENDS: Set<String> = setOf("NO_PROFILE", "NO_SCREEN")

    val KINDS: Map<String, String> = linkedMapOf(
        "test-build" to "only with a test script",
        "no-service" to "accessibility service is off",
        "no-watcher" to "watch the screen for your touches",
        "no-profile" to "no provider profile is set up",
    )

    fun kind(hooks: Hooks, tag: String): String = KINDS.entries.firstOrNull { (_, words) -> hooks.agentTask(tag, marker = words)["marker"] == "1" }?.key ?: "unknown"
}
