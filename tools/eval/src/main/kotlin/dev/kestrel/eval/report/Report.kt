package dev.kestrel.eval.report

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.run.RunLine
import dev.kestrel.eval.run.RunResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

@Serializable
data class Tokens(val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0) {
    operator fun plus(other: Tokens) = Tokens(input + other.input, output + other.output, cacheRead + other.cacheRead, cacheWrite + other.cacheWrite)
}

/**
 * A task's part of a report (design D10 of sub-project 6): the passes of the runs that ran, the runs that ran, the skipped
 * runs by their reason, the mean steps and working time of the runs that ran, the AI Credits with the number of runs whose
 * credits are unknown, and the tokens.
 */
@Serializable
data class TaskReport(
    val task: String,
    val passes: Int,
    val runs: Int,
    val skipped: Map<String, Int>,
    val meanSteps: Double,
    val meanWorkMs: Double,
    val credits: Double,
    val unknownCredits: Int,
    val tokens: Tokens,
)

@Serializable
data class Totals(val passes: Int, val runs: Int, val skipped: Int, val credits: Double, val unknownCredits: Int, val tokens: Tokens)

/**
 * The egress check's result in a report (design D11 of sub-project 6): counts only; `egress.txt` beside it names the hosts.
 * [unknown] counts the parts of the folder whose runs' traffic was not judged ([dev.kestrel.eval.egress.EgressParts]),
 * each of which fails the check.
 */
@Serializable
data class EgressSummary(val passed: Boolean, val connections: Int, val failures: Int, val unknown: Int = 0)

/**
 * `report.json` of a results folder (design D10 of sub-project 6), the per-task fields of plan 6A; plan 6B adds the
 * egress check's result and plan 6C datamarking's state.
 */
@Serializable
data class Report(
    val device: String,
    val commit: String,
    val model: String,
    val live: Boolean,
    val start: String,
    val end: String,
    val tasks: List<TaskReport>,
    val totals: Totals,
    val egress: EgressSummary? = null,
) {
    fun write(file: Path) {
        Files.createDirectories(file.toAbsolutePath().parent)
        Files.writeString(file, JSON.encodeToString(serializer(), this) + "\n")
    }

    companion object {
        private val JSON = Json { prettyPrint = true }
        private val READ = Json { ignoreUnknownKeys = true }

        /** The report of a results folder's `report.json`. */
        fun read(file: Path): Report = READ.decodeFromString(serializer(), Files.readString(file))

        /** The report of [lines], in the order of their tasks; a run that was skipped counts only among the skipped. */
        fun of(device: DeviceKind, commit: String, live: Boolean, start: LocalDateTime, end: LocalDateTime, lines: List<RunLine>): Report {
            val tasks = lines.groupBy { it.task }.map { (task, runs) -> task(task, runs) }
            val model = lines.mapNotNull { it.answer?.get("model") }.firstOrNull { it != "none" } ?: "none"
            val totals = Totals(
                tasks.sumOf { it.passes }, tasks.sumOf { it.runs }, tasks.sumOf { it.skipped.values.sum() }, tasks.sumOf { it.credits },
                tasks.sumOf { it.unknownCredits }, tasks.fold(Tokens()) { sum, task -> sum + task.tokens },
            )
            return Report(device.folder, commit, model, live, start.toString(), end.toString(), tasks, totals)
        }

        private fun task(task: String, lines: List<RunLine>): TaskReport {
            val ran = lines.filter { it.result != RunResult.SKIPPED }
            fun number(line: RunLine, field: String) = line.answer?.get(field)?.toLongOrNull() ?: 0L
            val credits = lines.map { it.credits ?: it.answer?.get("credits") ?: "0.00" }
            return TaskReport(
                task = task,
                passes = ran.count { it.result == RunResult.PASS },
                runs = ran.size,
                skipped = lines.filter { it.result == RunResult.SKIPPED }.groupingBy { it.reason.wire }.eachCount(),
                meanSteps = if (ran.isEmpty()) 0.0 else ran.map { number(it, "steps") }.average(),
                meanWorkMs = if (ran.isEmpty()) 0.0 else ran.map { number(it, "workMs") }.average(),
                credits = credits.mapNotNull { it.toDoubleOrNull() }.sum(),
                unknownCredits = credits.count { it.toDoubleOrNull() == null },
                tokens = lines.fold(Tokens()) { sum, line ->
                    sum + Tokens(number(line, "in"), number(line, "out"), number(line, "cacheRead"), number(line, "cacheWrite"))
                },
            )
        }
    }
}
