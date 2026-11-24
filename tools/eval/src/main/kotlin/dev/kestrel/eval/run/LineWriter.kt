package dev.kestrel.eval.run

import dev.kestrel.eval.report.ResultsFile

/**
 * Writes each run's line once, together with its detail line ([RunDetail], plan 6B-basefix): the run line on the console
 * and, when the command has one, in its results file with the step `task`; the detail line on the console and, in a
 * suite, in its `details.txt` with the step `detail`, so that runs.txt keeps only the run lines, which
 * scripts\a71-agent.ps1 and --resume read. The main thread and the shutdown hook may both write, so the pair is written
 * under one lock, and the writer remembers which runs have their lines: every run line has exactly one detail line.
 */
class LineWriter(private val console: Console, private val results: ResultsFile?, private val detailsFile: ResultsFile? = null) {
    private val written = mutableSetOf<Pair<String, Int>>()
    private val all = mutableListOf<RunLine>()
    private val notes = mutableListOf<String>()

    /** Every run line that this writer wrote, in its order. */
    val lines: List<RunLine> get() = synchronized(this) { all.toList() }

    /** Every detail line that this writer wrote, in its order, one for each of [lines]. */
    val details: List<String> get() = synchronized(this) { notes.toList() }

    /**
     * Writes [line] and its [detail] as the lines of run [number] of [task] unless that run has them, checked and written
     * under one lock, so that the shutdown hook and the main thread never both write a run's lines and never write one
     * without the other; returns whether it wrote.
     */
    fun writeOnce(task: String, number: Int, line: RunLine, detail: RunDetail): Boolean = synchronized(this) {
        require(line.task == task && line.run == number && detail.task == task && detail.run == number) { "The lines are not those of run $number of $task." }
        if (wrote(task, number)) return false
        val text = detail.text()
        console.line(line.text())
        results?.append("task", line.text())
        console.line(text)
        detailsFile?.append("detail", text)
        written.add(task to number)
        all.add(line)
        notes.add(text)
        true
    }

    fun wrote(task: String, run: Int): Boolean = synchronized(this) { (task to run) in written }
}