package dev.kestrel.eval.run

import dev.kestrel.eval.report.ResultsFile

/**
 * Writes each run's line once: on the console and, when the command has one, in its results file with the step `task`.
 * The main thread and the shutdown hook may both write, so it is synchronized, and it remembers which runs have a line.
 */
class LineWriter(private val console: Console, private val results: ResultsFile?, private val detailsFile: ResultsFile? = null) {
    private val written = mutableSetOf<Pair<String, Int>>()
    private val all = mutableListOf<RunLine>()
    private val notes = mutableListOf<String>()

    /** Every line that this writer wrote, in its order. */
    val lines: List<RunLine> get() = synchronized(this) { all.toList() }

    /** Every detail line that this writer wrote, in its order. */
    val details: List<String> get() = synchronized(this) { notes.toList() }

    /**
     * A run's detail line (plan 6B-basefix): on the console and, with the step `detail`, in the suite's `details.txt`, so
     * that a run's folder says how the run answered and what Kestrel counted; kinds, counts and flags only, never text.
     * runs.txt keeps only the run lines, which scripts\a71-agent.ps1 and --resume read.
     */
    fun detail(text: String) = synchronized(this) {
        console.line(text)
        detailsFile?.append("detail", text)
        notes.add(text)
        Unit
    }

    fun write(line: RunLine) = synchronized(this) {
        console.line(line.text())
        results?.append("task", line.text())
        written.add(line.task to line.run)
        all.add(line)
        Unit
    }

    /**
     * Writes [line] as the line of run [number] of [task] unless that run has one, checked and written under one lock, so
     * that the shutdown hook and the main thread never both write a run's line; returns whether it wrote.
     */
    fun writeOnce(task: String, number: Int, line: RunLine): Boolean = synchronized(this) {
        if (wrote(task, number)) return false
        write(line)
        true
    }

    fun wrote(task: String, run: Int): Boolean = synchronized(this) { (task to run) in written }
}
