package dev.kestrel.eval.report

import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.run.Reason
import dev.kestrel.eval.run.RunDetail
import dev.kestrel.eval.run.RunLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What a suite was asked for, in its folder's `suite.json`, which `suite --resume` compares with its own call, and the Kestrel build that ran it. */
@Serializable
data class SuiteCall(
    val device: String,
    val live: Boolean,
    val runs: Int,
    val tasks: List<String>,
    val commit: String,
    val kestrel: KestrelBuild? = null,
    /**
     * The evaluation's kept set, Kestrel's seven data-tool permissions and its notification access, by their IDs, with each
     * one's state when the live series began (plan 6B-basefix); null without a model.
     */
    val permissions: Map<String, Boolean>? = null,
) {
    fun write(folder: Path) {
        Files.createDirectories(folder)
        Files.writeString(folder.resolve(FILE), JSON.encodeToString(serializer(), this) + "\n")
    }

    companion object {
        private const val FILE = "suite.json"
        private val JSON = Json { prettyPrint = true }

        /** The call of [folder], or null for a folder without one, such as a suite's from before `--resume`. */
        fun read(folder: Path): SuiteCall? = folder.resolve(FILE).takeIf { Files.isRegularFile(it) }?.let { JSON.decodeFromString(serializer(), Files.readString(it)) }
    }
}

/** A run's line in runs.txt: the file's [text], the [line] read from it, and the run's line in details.txt ([detail]), if any. */
data class RunEntry(val text: String, val line: RunLine, val detail: String? = null)

/**
 * A task's part of runs.txt: its line "<task> on <device>, <n> runs" ([header]) and the run lines after it. [done] counts
 * its runs from the first on that have a line of a reason that ended ([Reason.ended]: not `reason=stopped` nor
 * `reason=owner-busy`), up to the first run without one.
 */
data class TaskBlock(val task: String, val count: Int, val header: String, val runs: List<RunEntry>) {
    private fun ended(run: Int) = runs.any { it.line.run == run && it.line.reason.ended }

    val done: Int = (1..count).takeWhile { ended(it) }.size
    val complete: Boolean get() = done == count

    /** Whether the runs after [done] have no line that ended, so that they can run on from [done] + 1. */
    val cutShort: Boolean get() = done in 1 until count && (done + 1..count).none { ended(it) }

    /** The block with only the first line that ended of each of its runs 1 to [done]. */
    fun kept(): TaskBlock = copy(runs = (1..done).map { run -> runs.first { it.line.run == run && it.line.reason.ended } })
}

/** What a resume does: it keeps the lines of [kept], and runs each task of [runs] from its run on, in that order. */
data class Resumption(val kept: List<TaskBlock>, val runs: List<Pair<String, Int>>)

/**
 * A results folder of a suite that `suite --resume` continues: its runs.txt in its tasks' blocks, and what it says of its
 * suite. A folder from before `suite.json` gives its device by its name, its runs by its blocks, and whether it was live by
 * its runs' model (the scripted model's IDs begin with "scripted").
 */
class SuiteFolder private constructor(val path: Path, private val text: List<String>, val blocks: List<TaskBlock>) {
    private val name = NAME.matchEntire(path.fileName.toString())

    val call: SuiteCall? = SuiteCall.read(path)

    /** The device of the folder's name (`a71` or `kestrel-api37`); null for another name, such as an injection series'. */
    val device: String? get() = name?.groupValues?.get(1)

    /** The time of the folder's name, `yyyyMMdd-HHmmss`. */
    val stamp: String? get() = name?.groupValues?.get(2)

    /** The time of runs.txt's first line, which was the suite's start. */
    val start: LocalDateTime? = text.firstOrNull()?.substringBefore('\t')?.let { runCatching { LocalDateTime.parse(it, TIME) }.getOrNull() }

    /** The models that the runs named, without `none`. */
    val models: Set<String> = blocks.flatMap { block -> block.runs.mapNotNull { it.line.answer?.get("model") } }.filter { it != "none" }.toSet()

    val live: Boolean? get() = when {
        models.isEmpty() -> null
        else -> models.none { it.startsWith("scripted") }
    }

    val runs: Int? get() = blocks.maxOfOrNull { it.count }

    /**
     * What a resume of the suite's [tasks] (each with its number of runs now) does: a task whose block is complete with
     * that number keeps its lines; the folder's last block, when it was cut short, runs on from its first run without a
     * line, first; every other task runs whole, in the suite's order, and its earlier lines are dropped. Without
     * [sameCommit], when HEAD differs from the folder's commit in paths that change no run of Kestrel, the cut-short
     * block runs whole too, because it is usually the task whose failure the tool was just fixed for.
     */
    fun resume(tasks: List<Pair<String, Int>>, sameCommit: Boolean = true): Resumption {
        val counts = tasks.toMap()
        val complete = blocks.filter { it.complete && counts[it.task] == it.count }.distinctBy { it.task }
        val last = blocks.lastOrNull()?.takeIf { sameCommit && it.cutShort && counts[it.task] == it.count && blocks.count { block -> block.task == it.task } == 1 }
        val whole = tasks.filter { (task, _) -> complete.none { it.task == task } && last?.task != task }.map { it.first to 1 }
        return Resumption(complete.map { it.kept() } + listOfNotNull(last?.kept()), listOfNotNull(last?.let { it.task to it.done + 1 }) + whole)
    }

    /**
     * Writes runs.txt with [kept]'s lines only, after a copy of it as it was, `runs-before-resume-<stamp>.txt`, and
     * details.txt, when the folder has one, with only the kept runs' detail lines, after a copy `details-before-resume-<stamp>.txt`,
     * so that details.txt keeps one detail line for each run line.
     */
    fun rewrite(kept: List<TaskBlock>, stamp: String) {
        val file = path.resolve(RUNS)
        Files.copy(file, path.resolve("runs-before-resume-$stamp.txt"), StandardCopyOption.REPLACE_EXISTING)
        write(file, kept.flatMap { block -> listOf(block.header) + block.runs.map { it.text } })
        val details = path.resolve(DETAILS)
        if (!Files.isRegularFile(details)) return
        Files.copy(details, path.resolve("details-before-resume-$stamp.txt"), StandardCopyOption.REPLACE_EXISTING)
        write(details, kept.flatMap { block -> block.runs.mapNotNull { it.detail } })
    }

    companion object {
        const val RUNS = "runs.txt"

        /** The runs' detail lines (plan 6B-basefix), one for each run line of runs.txt, which --resume rewrites with it. */
        const val DETAILS = "details.txt"
        private val NAME = Regex("(a71|kestrel-api37)-(\\d{8}-\\d{6})")
        private val HEADER = Regex("(\\S+) on .+, (\\d+) runs")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun read(path: Path): SuiteFolder {
            val file = path.resolve(RUNS)
            if (!Files.isRegularFile(file)) throw Refusal("$path has no runs.txt, so it is no results folder of a suite.")
            val text = Files.readAllLines(file, Charsets.UTF_8).filter { it.isNotBlank() }
            val details = details(path.resolve(DETAILS))
            // A run's lines pair in their order: its n-th run line in runs.txt with its n-th detail line in details.txt.
            val seen = mutableMapOf<Pair<String, Int>, Int>()
            val blocks = mutableListOf<TaskBlock>()
            for (raw in text) {
                val parts = raw.split('\t', limit = 3)
                if (parts.size < 3) continue
                when (parts[1]) {
                    "suite" -> HEADER.matchEntire(parts[2])?.let { blocks.add(TaskBlock(it.groupValues[1], it.groupValues[2].toInt(), raw, emptyList())) }
                    "task" -> {
                        val line = RunLine.parse(parts[2]) ?: continue
                        val run = line.task to line.run
                        val ordinal = seen.getOrDefault(run, 0).also { seen[run] = it + 1 }
                        val at = blocks.indexOfLast { it.task == line.task }
                        if (at >= 0) blocks[at] = blocks[at].copy(runs = blocks[at].runs + RunEntry(raw, line, details[run]?.getOrNull(ordinal)))
                    }
                }
            }
            return SuiteFolder(path, text, blocks)
        }

        /** The detail lines of [file] by the task and the run that they name, each run's in their order; none without a file. */
        private fun details(file: Path): Map<Pair<String, Int>, List<String>> {
            if (!Files.isRegularFile(file)) return emptyMap()
            return Files.readAllLines(file, Charsets.UTF_8).mapNotNull { raw ->
                val parts = raw.split('\t', limit = 3)
                RunDetail.runOf(parts.last())?.takeIf { parts.size == 3 && parts[1] == "detail" }?.let { it to raw }
            }.groupBy({ it.first }, { it.second })
        }

        /** Keeps the first [count] lines of [file], dropping those that came after them. */
        fun truncate(file: Path, count: Int) = write(file, Files.readAllLines(file, Charsets.UTF_8).take(count))

        fun lines(file: Path): Int = if (Files.isRegularFile(file)) Files.readAllLines(file, Charsets.UTF_8).size else 0

        private fun write(file: Path, lines: List<String>) {
            val temporary = file.resolveSibling("${file.fileName}.new")
            Files.writeString(temporary, lines.joinToString("") { "$it\n" }, Charsets.UTF_8)
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}

/** git's reflog of HEAD, `git log -g --format=%H%x09%gd --date=format-local:%Y%m%d-%H%M%S HEAD`, newest first. */
object Reflog {
    val COMMAND = listOf("log", "-g", "--format=%H%x09%gd", "--date=format-local:%Y%m%d-%H%M%S", "HEAD")

    /** The commit that HEAD was at, at [stamp] (`yyyyMMdd-HHmmss`), or null when the reflog does not reach back so far. */
    fun headAt(log: String, stamp: String): String? = log.lines().mapNotNull { ENTRY.matchEntire(it.trim()) }
        .firstOrNull { it.groupValues[2] <= stamp }?.groupValues?.get(1)

    private val ENTRY = Regex("([0-9a-f]{40})\\tHEAD@\\{(\\d{8}-\\d{6})\\}")
}
