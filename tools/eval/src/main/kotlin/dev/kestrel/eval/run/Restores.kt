package dev.kestrel.eval.run

import dev.kestrel.eval.config.Redactions
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What puts back one change: [what] it is, what to do [byHand] when it fails, and the [action]. */
class Restore(val what: String, val byHand: String, val action: () -> Unit)

data class RestoreFailure(val what: String, val byHand: String, val reason: String)

/** [Restores.push] after the shutdown hook began: the command is ending, and it must make no further change. */
class Stopping(message: String) : Exception(message)

/**
 * The restores of a command (design D3 of sub-project 6): a stack on which each change pushes its restore before it is
 * made. They run newest first, each once, and a failure, even an [Error], does not stop the next one. The first push
 * registers a JVM shutdown hook that runs every restore still on the stack, so that Ctrl+C or an error puts the device
 * back; the stack is shared by the main thread and the hook, and a restore leaves it before it runs, so that no restore
 * runs twice. A run puts back only its own changes with [mark] and [runDownTo].
 *
 * The JVM does not stop the main thread while the hook runs. So the hook first sets [stopping], after which [push] and
 * [change] throw [Stopping] and no new change starts; then it waits for the [change] under way, runs the restores, and
 * waits for the restores that the main thread is running, each wait for at most [stopWait]. A change or a restore that is
 * still under way at the bound is reported as not put back. While [stopping], a failure is reported as soon as it
 * happens, because the main thread may never reach its [report].
 */
class Restores internal constructor(
    private val console: Console,
    private val redactions: Redactions,
    private val stopWait: Duration,
    private val addShutdownHook: (Thread) -> Unit,
) {
    constructor(
        console: Console,
        redactions: Redactions,
        addShutdownHook: (Thread) -> Unit = { Runtime.getRuntime().addShutdownHook(it) },
    ) : this(console, redactions, STOP_WAIT, addShutdownHook)

    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private val stack = ArrayList<Restore>()
    private val running = LinkedHashSet<Restore>()
    private val changing: MutableSet<Change> = Collections.newSetFromMap(IdentityHashMap())
    private val unreported = ArrayList<RestoreFailure>()
    private val reported: MutableSet<RestoreFailure> = Collections.newSetFromMap(IdentityHashMap())
    private var hooked = false

    /** Whether the shutdown hook has begun; a run checks it to end at once instead of on its next [push]. */
    @Volatile
    var stopping = false
        private set

    val size: Int get() = lock.withLock { stack.size }

    fun push(what: String, byHand: String, action: () -> Unit): Restore = lock.withLock {
        if (stopping) throw Stopping("The restores are running because :tools:eval is stopping; no change may start.")
        if (!hooked) {
            hooked = true
            addShutdownHook(Thread(::stop, "eval-restores"))
        }
        Restore(what, byHand, action).also { stack.add(it) }
    }

    /**
     * Makes a change that a restore already on the stack puts back: [block] does not start once the hook began ([Stopping]),
     * and the hook waits for a change under way before it runs any restore, so that no change is made after the restore
     * that covers it has run. A step whose one restore covers several device commands makes each of them through it; a
     * restore never calls it. [what] and [byHand] name the change when the hook stops waiting for it.
     */
    fun <T> change(what: String, byHand: String, block: () -> T): T {
        val change = Change(what, byHand)
        lock.withLock {
            if (stopping) throw Stopping("The restores are running because :tools:eval is stopping; no change may start.")
            changing.add(change)
        }
        try {
            return block()
        } finally {
            lock.withLock {
                changing.remove(change)
                idle.signalAll()
            }
        }
    }

    /** The stack's height now, so that [runDownTo] later runs only what was pushed after it. */
    fun mark(): Int = size

    /** Runs the restores pushed after [mark], newest first, and returns those that failed. */
    fun runDownTo(mark: Int): List<RestoreFailure> {
        val failures = mutableListOf<RestoreFailure>()
        while (true) {
            val next = lock.withLock {
                if (stack.size > mark) stack.removeAt(stack.size - 1).also { running.add(it) } else null
            } ?: break
            val failure = try {
                next.action()
                null
            } catch (e: Throwable) {
                RestoreFailure(next.what, next.byHand, redactions.forConsole(e.message ?: e.javaClass.simpleName))
            }
            lock.withLock {
                failure?.let {
                    failures.add(it)
                    unreported.add(it)
                }
                if (stopping) warn(failures)
                running.remove(next)
                idle.signalAll()
            }
        }
        return failures
    }

    fun runAll(): List<RestoreFailure> = runDownTo(0)

    /** Says what could not be put back and how to do it by hand, as a71-agent.ps1's last lines do; each failure once. */
    fun report(failures: List<RestoreFailure>) = lock.withLock { warn(failures) }

    private fun warn(failures: List<RestoreFailure>) {
        for (failure in failures) {
            if (!reported.add(failure)) continue
            unreported.removeAll { it === failure }
            console.warn("Not put back: ${failure.what} (${failure.reason}). By hand: ${failure.byHand}")
        }
    }

    /**
     * The shutdown hook: no new change, the change under way awaited, every restore left, the main thread's restore
     * awaited, and every failure reported.
     */
    private fun stop() {
        stopping = true
        val unfinished = lock.withLock {
            await { changing.isEmpty() }
            changing.toList()
        }
        runAll()
        lock.withLock {
            await { running.isEmpty() }
            warn(unreported.toList())
            for (change in unfinished) {
                console.warn("Not put back: ${change.what} (it was still being made when :tools:eval stopped). By hand: ${change.byHand}")
            }
            for (restore in running) {
                console.warn("Not put back: ${restore.what} (it was still running when :tools:eval stopped). By hand: ${restore.byHand}")
            }
        }
    }

    /** Waits on [idle], with the lock held, until [done] or for at most [stopWait]. */
    private fun await(done: () -> Boolean) {
        var left = stopWait.inWholeNanoseconds
        while (!done() && left > 0) left = idle.awaitNanos(left)
    }

    private class Change(val what: String, val byHand: String)

    private companion object {
        val STOP_WAIT = 30.seconds
    }
}
