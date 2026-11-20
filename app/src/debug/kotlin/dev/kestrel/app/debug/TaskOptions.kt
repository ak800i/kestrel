package dev.kestrel.app.debug

/**
 * The options that a tagged transcript carried, by the number of the task that it started (plan 4D, for sub-project 5):
 * the hook's extras `model` and `live`, which sub-project 5's AgentRunner reads once per task through
 * `Listening.taskOptions`. Debug only: release builds have no hook that sets that seam, so a task there has no options.
 * It keeps the newest [MAX_TASKS] tasks' options, in memory only. Any thread may call it.
 */
internal class TaskOptions {
    private val byTask = LinkedHashMap<Int, Map<String, String>>()

    @Synchronized
    fun put(task: Int, options: Map<String, String>) {
        byTask[task] = options
        while (byTask.size > MAX_TASKS) byTask.remove(byTask.keys.first())
    }

    /** The options of task number [task], which this read removes; empty when it has none. */
    @Synchronized
    fun take(task: Int): Map<String, String> = byTask.remove(task).orEmpty()

    companion object {
        const val MAX_TASKS = 16
    }
}
