package dev.kestrel.eval.command

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.report.Comparison
import dev.kestrel.eval.report.Reflog
import dev.kestrel.eval.report.SuiteCall
import dev.kestrel.eval.report.SuiteFolder
import dev.kestrel.eval.task.Task
import kotlin.time.Duration.Companion.seconds

/**
 * The folder that `suite --resume` continues, once it passed the checks: the same device and the same `--live` as this
 * call, the same `--multi-turn` (plan 7G), one model in its runs, and a commit that differs from HEAD at most in paths
 * that change no run of Kestrel
 * ([KESTREL_PATHS]), because the baseline names one commit for the whole folder, HEAD's. [call] is the folder's suite with
 * HEAD's commit; a folder from before `suite.json` counts as the whole suite of its device, with the commit that git's
 * reflog shows for HEAD at the time of its name. [commit] is the commit that the folder's runs ran at; when it is not
 * HEAD's ([sameCommit] false), the task that was cut short runs whole again, and the kept tasks are named on the console.
 */
class Resume private constructor(val folder: SuiteFolder, val call: SuiteCall, val commit: String) {
    val sameCommit: Boolean get() = commit == call.commit

    companion object {
        /**
         * git's pathspec of the paths that can change a run of Kestrel: every path but those that the repository's
         * `.gitattributes` marks `kestrel-neutral` (the tool, the baseline, the documentation and Markdown files), their
         * one definition, which build-logic's BuildCommitTask reads through the same pathspec for a build's dirty flag.
         */
        val KESTREL_PATHS = listOf("--", ".", ":(exclude,attr:kestrel-neutral)")

        fun open(
            environment: Environment, path: String, kind: DeviceKind, live: Boolean, suite: List<Task>, head: String, recordBaseline: Boolean,
            multiTurn: Boolean = false,
        ): Resume {
            val folder = SuiteFolder.read(environment.root.resolve(path))
            val device = folder.call?.device ?: folder.device
                ?: throw Refusal("${folder.path} is no suite's results folder: its name is not <device>-<yyyyMMdd-HHmmss>, and it has no suite.json.")
            if (device != kind.folder) {
                val label = DeviceKind.entries.firstOrNull { it.folder == device }?.label ?: device
                throw Refusal("${folder.path} holds a suite of $label, and this call's device is ${kind.label}; give --device the serial of $label.")
            }
            val ran = folder.call?.live ?: folder.live ?: live
            if (ran != live) throw Refusal("${folder.path}'s suite ran ${with(ran)}, and this call runs ${with(live)}; give the same.")
            // A folder from before suite.json holds a suite, never the multi-turn tasks.
            if ((folder.call?.multiTurn ?: false) != multiTurn) {
                throw Refusal(
                    if (multiTurn) "${folder.path} holds a suite, not the multi-turn tasks; give no --multi-turn." else "${folder.path} holds the multi-turn tasks; give --multi-turn as that call did.",
                )
            }
            if (folder.models.size > 1) throw Refusal("${folder.path}'s runs name more than one model (${folder.models.sorted().joinToString()}); run a new suite.")
            val commit = folder.call?.commit ?: folder.stamp?.let { Reflog.headAt(git(environment, Reflog.COMMAND) ?: "", it) }
                ?: throw Refusal("${folder.path} names no commit, and git's reflog has no HEAD from its time; run a new suite.")
            if (commit != head) {
                val kestrel = kestrelChanges(environment, commit, head)
                    ?: throw Refusal("git could not compare ${folder.path}'s commit $commit with HEAD $head; run a new suite.")
                if (kestrel.isNotEmpty()) {
                    throw Refusal(
                        "${folder.path}'s runs ran at $commit, and HEAD $head changes paths that can change a run of Kestrel (${kestrel.joinToString()}), which the baseline's " +
                            "one commit would hide; run a new suite.",
                    )
                }
            }
            val call = SuiteCall(device, live, folder.call?.runs ?: folder.runs ?: Comparison.RUNS, folder.call?.tasks ?: suite.map { it.name }, head, multiTurn = multiTurn)
            if (recordBaseline && (call.runs != Comparison.RUNS || call.tasks.toSet() != suite.map { it.name }.toSet())) {
                throw Refusal("--record-baseline records a suite of every task with ${Comparison.RUNS} runs, and ${folder.path} holds ${call.tasks.size} tasks with ${call.runs} runs.")
            }
            return Resume(folder, call, commit)
        }

        private fun with(live: Boolean) = if (live) "with --live" else "without --live"

        /** The files of [KESTREL_PATHS] that differ between the commits [from] and [to], or null when git failed. */
        fun kestrelChanges(environment: Environment, from: String, to: String): List<String>? =
            git(environment, listOf("diff", "--name-only", from, to) + KESTREL_PATHS)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }

        /** git's answer in the repository's folder, or null when git failed. */
        private fun git(environment: Environment, args: List<String>): String? = try {
            environment.processes.run(listOf("git", "-C", environment.root.toString()) + args, 30.seconds).takeIf { it.exitCode == 0 }?.out
        } catch (e: Exception) {
            null
        }
    }
}
