package dev.kestrel.eval.run

import dev.kestrel.eval.hook.HookAnswer

enum class RunResult(val wire: String) { PASS("pass"), FAIL("fail"), SKIPPED("skipped") }

enum class CheckResult(val wire: String) { PASS("pass"), FAIL("fail"), SKIPPED("skipped") }

/** Why a run ended as it did, as task-check.ps1 named it. */
enum class Reason(val wire: String) {
    OK("ok"), TIMEOUT("timeout"), CHECK("check"), END("end"), UNTESTABLE("untestable"), LIMIT("limit"), CONFIRM("confirm"),
    CALL("call"), STOPPED("stopped"), SETUP("setup"), STALLED("stalled"),
}

/**
 * One run's line (design D6 of sub-project 6), exactly as task-check.ps1's `Format-TaskLine` wrote it, so that
 * scripts\a71-agent.ps1's readers of the lines keep working: `task=<name> run=<i> result=<pass|fail|skipped>
 * end=<EndReason|none> check=<pass|fail|skipped> steps= workMs= credits=<n|unknown> model= in= out= cacheRead= cacheWrite=
 * reason=<…>`, with only what the hooks answered. [credits], when given, replaces the answer's credits.
 */
data class RunLine(
    val task: String,
    val run: Int,
    val result: RunResult,
    val answer: HookAnswer?,
    val check: CheckResult,
    val reason: Reason,
    val credits: String? = null,
) {
    fun text(): String {
        fun field(name: String, default: String) = answer?.get(name) ?: default
        val spent = credits ?: field("credits", "0.00")
        return "task=$task run=$run result=${result.wire} end=${field("end", "none")} check=${check.wire} steps=${field("steps", "0")} " +
            "workMs=${field("workMs", "0")} credits=$spent model=${field("model", "none")} in=${field("in", "0")} " +
            "out=${field("out", "0")} cacheRead=${field("cacheRead", "0")} cacheWrite=${field("cacheWrite", "0")} reason=${reason.wire}"
    }

    companion object {
        /** The line of [text], as [text] wrote it, with its fields as the answer; null when it is no run's line. */
        fun parse(text: String): RunLine? {
            val fields = HookAnswer(text)
            val result = RunResult.entries.firstOrNull { it.wire == fields["result"] } ?: return null
            val check = CheckResult.entries.firstOrNull { it.wire == fields["check"] } ?: return null
            val reason = Reason.entries.firstOrNull { it.wire == fields["reason"] } ?: return null
            val run = fields["run"]?.toIntOrNull() ?: return null
            return RunLine(fields["task"] ?: return null, run, result, fields, check, reason)
        }
    }
}
