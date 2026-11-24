package dev.kestrel.eval.report

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class BaselineTask(val passes: Int, val runs: Int)

/** A device's part of the baseline: the commit and the model of its live suite, and each task that ran there. */
@Serializable
data class BaselinePart(val commit: String, val model: String, val tasks: Map<String, BaselineTask>)

/**
 * `eval\baseline.json` (design D10 of sub-project 6): a part per device, each recorded with Kestrel's defaults at its own
 * commit, because the A71's part is recorded later than the emulator's; it holds only counts, the model's ID and the
 * commit. `suite --record-baseline` writes or replaces only its device's part.
 */
@Serializable
data class Baseline(val devices: Map<String, BaselinePart> = emptyMap()) {
    fun with(device: String, part: BaselinePart): Baseline = Baseline(devices + (device to part))

    fun write(file: Path) {
        Files.createDirectories(file.toAbsolutePath().parent)
        Files.writeString(file, JSON.encodeToString(serializer(), this) + "\n")
    }

    companion object {
        private val JSON = Json { prettyPrint = true }

        /** The baseline of [file], or an empty one when there is none yet. */
        fun read(file: Path): Baseline = if (Files.isRegularFile(file)) JSON.decodeFromString(serializer(), Files.readString(file)) else Baseline()

        /** A device's part from its live suite's [report]: each task that ran, with its passes and runs. */
        fun part(report: Report): BaselinePart =
            BaselinePart(report.commit, report.model, report.tasks.filter { it.runs > 0 }.associate { it.task to BaselineTask(it.passes, it.runs) })
    }
}
