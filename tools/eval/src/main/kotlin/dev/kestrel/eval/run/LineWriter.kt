package dev.kestrel.eval.run

import dev.kestrel.eval.report.ResultsFile

/**
 * Writes each run's line once: on the console and, when the command has one, in its results file with the step `task`.
 * The main thread and the shutdown hook may both write, so it is synchronized, and it remembers which runs have a line.
 */
class LineWriter(private val console: Console, private val results: ResultsFile?) {
    private val written = mutableSetOf<Pair<String, Int>>()
    private val all = mutableListOf<RunLine>()

    /** Every line that this writer wrote, in its order. */
    val lines: List<RunLine> get() = synchronized(this) { all.toList() }

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
