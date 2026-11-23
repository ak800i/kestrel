package dev.kestrel.eval.run

import dev.kestrel.eval.config.Redactions

/** What puts back one change: [what] it is, what to do [byHand] when it fails, and the [action]. */
class Restore(val what: String, val byHand: String, val action: () -> Unit)

data class RestoreFailure(val what: String, val byHand: String, val reason: String)

/**
 * The restores of a command (design D3 of sub-project 6): a stack on which each change pushes its restore before it is
 * made. They run newest first, each once, and a failure does not stop the next one. The first push registers a JVM
 * shutdown hook that runs every restore still on the stack, so that Ctrl+C or an error puts the device back; the stack is
 * shared by the main thread and the hook, and a restore leaves it before it runs, so that no restore runs twice. A run
 * puts back only its own changes with [mark] and [runDownTo].
 */
class Restores(
    private val console: Console,
    private val redactions: Redactions,
    private val addShutdownHook: (Thread) -> Unit = { Runtime.getRuntime().addShutdownHook(it) },
) {
    private val stack = ArrayList<Restore>()
    private var hooked = false

    val size: Int get() = synchronized(stack) { stack.size }

    fun push(what: String, byHand: String, action: () -> Unit): Restore = synchronized(stack) {
        if (!hooked) {
            hooked = true
            addShutdownHook(Thread({ report(runAll()) }, "eval-restores"))
        }
        Restore(what, byHand, action).also { stack.add(it) }
    }

    /** The stack's height now, so that [runDownTo] later runs only what was pushed after it. */
    fun mark(): Int = size

    /** Runs the restores pushed after [mark], newest first, and returns those that failed. */
    fun runDownTo(mark: Int): List<RestoreFailure> {
        val failures = mutableListOf<RestoreFailure>()
        while (true) {
            val next = synchronized(stack) { if (stack.size > mark) stack.removeAt(stack.size - 1) else null } ?: break
            try {
                next.action()
            } catch (e: Exception) {
                failures.add(RestoreFailure(next.what, next.byHand, redactions.forConsole(e.message ?: e.javaClass.simpleName)))
            }
        }
        return failures
    }

    fun runAll(): List<RestoreFailure> = runDownTo(0)

    /** Says what could not be put back and how to do it by hand, as a71-agent.ps1's last lines do. */
    fun report(failures: List<RestoreFailure>) {
        for (failure in failures) console.warn("Not put back: ${failure.what} (${failure.reason}). By hand: ${failure.byHand}")
    }
}
