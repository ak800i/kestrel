package dev.kestrel.voice.listen

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * The threads of one [Microphone], one rule for every microphone (design spec, section 8.1). Each [start] runs a thread
 * named [name] that runs [prepare], such as raising its priority, and then asks a [FrameSource] for frames, which never
 * blocks. While no frame is there, the thread waits until the next one can be complete, and it hands each frame to the
 * start's `onFrame`. It checks for a [stop] before each frame and each wait, and [stop] wakes it, so it ends within one
 * call of the source or of `onFrame`.
 *
 * It never relies on a stop to wake a read: the stop of an `AudioRecord` does not always wake a blocking read (the call
 * of 2026-11-26 on kestrel-api37), and that read then waited for ever, and so did the stop.
 *
 * [stop] waits for the thread for at most [stopMillis]. A thread that is still in a call then lingers until that call
 * returns, and while it lingers, [start] refuses to run another one, so that no two threads ever feed the same
 * listener, and at most one thread can linger, however often a stop comes. What the stop's caller wanted done after the
 * last frame runs as that thread's last act.
 *
 * [start], [stop] and [checkCanStart] may be called from any thread except the microphone's own, one at a time; the
 * owner serialises them, as [MicCapture] does with its monitor.
 */
class MicrophoneThread internal constructor(
    private val name: String,
    private val prepare: () -> Unit,
    private val stopMillis: Long,
    private val park: (Long) -> Unit,
) {
    constructor(name: String, prepare: () -> Unit = {}) : this(name, prepare, STOP_MILLIS, LockSupport::parkNanos)

    // The thread that records, until a stop; then the thread that a stop waited for, until it has ended.
    @Volatile
    private var current: Run? = null

    @Volatile
    private var lingering: Run? = null

    /** Whether the calling thread is one of this microphone's threads, also one that lingers after a stop. */
    val isCurrent: Boolean
        get() = Thread.currentThread().let { it === current?.thread || it === lingering?.thread }

    /**
     * Throws [IllegalStateException] when a [start] now would be refused: while a thread records, or while the thread of
     * an earlier stop still lingers. A microphone calls it before it opens anything for a start.
     */
    fun checkCanStart() {
        check(current == null) { "The microphone is already recording" }
        val earlier = lingering ?: return
        check(earlier.ended) { "The microphone's thread from the last stop has not ended yet" }
        lingering = null
    }

    /**
     * Starts a thread that reads [source]. [onFrame] gets each frame on that thread. [onError] is called at most once, on
     * that thread, and then the thread ends: with the exception that [prepare], [source] or [onFrame] threw while the
     * microphone recorded (not one after a stop, such as a read of a released recording), or with one from a stop's
     * `afterEnd` that ran late. Throws [IllegalStateException] where [checkCanStart] does, and what [Thread.start]
     * throws, such as an [OutOfMemoryError].
     */
    fun start(source: FrameSource, onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
        checkNotOnTheMicrophonesThread()
        checkCanStart()
        val run = Run(source, onFrame, onError)
        // Known before it runs, so that a start() or stop() from its callbacks is recognised.
        current = run
        try {
            run.thread.start()
        } catch (e: Throwable) {
            current = null
            throw e
        }
    }

    /**
     * Stops the recording thread, and waits until it has ended, for at most [stopMillis], also when the calling thread is
     * interrupted; the interrupt is kept for the caller. Then [afterEnd] runs once, after the microphone's last frame:
     * here, before stop() returns, when the thread has ended or none ran; and otherwise on the lingering thread, as its
     * last act once its call returns. Returns whether the thread has ended.
     *
     * A stop while a thread lingers, with nothing recording, does not wait again: it adds its [afterEnd] to that thread's
     * last acts and returns false at once. An exception from [afterEnd] goes to the caller when it runs here, and to the
     * start's `onError` when it runs late.
     */
    fun stop(afterEnd: () -> Unit = {}): Boolean {
        checkNotOnTheMicrophonesThread()
        val recording = current
        val run = recording ?: lingering
        if (run == null) {
            afterEnd()
            return true
        }
        if (recording != null) {
            current = null
            lingering = recording
            recording.running = false
            LockSupport.unpark(recording.thread)
            if (recording.awaitEnd(TimeUnit.MILLISECONDS.toNanos(stopMillis))) {
                lingering = null
                afterEnd()
                return true
            }
        }
        if (run.defer(afterEnd)) return false
        // It ended just now, and runs nothing more.
        lingering = null
        afterEnd()
        return true
    }

    private fun checkNotOnTheMicrophonesThread() {
        check(!isCurrent) { "start() and stop() must not be called from the microphone's thread" }
    }

    private inner class Run(
        private val source: FrameSource,
        private val onFrame: (FloatArray) -> Unit,
        private val onError: (Exception) -> Unit,
    ) {
        val thread = Thread(::loop, name)

        @Volatile
        var running = true

        // The stops' afterEnd that wait for this thread, and whether it has run its last one. Guarded by this.
        private val lastActs = ArrayDeque<() -> Unit>()
        private var finished = false
        private var reported = false

        val ended: Boolean get() = synchronized(this) { finished }

        /** Adds [action] to the thread's last acts, unless it has run them all; returns whether it added it. */
        fun defer(action: () -> Unit): Boolean = synchronized(this) {
            if (finished) return false
            lastActs.addLast(action)
            true
        }

        fun awaitEnd(timeoutNanos: Long): Boolean {
            val deadline = System.nanoTime() + timeoutNanos
            var interrupted = false
            try {
                while (thread.isAlive) {
                    val left = deadline - System.nanoTime()
                    if (left <= 0) return false
                    try {
                        TimeUnit.NANOSECONDS.timedJoin(thread, left)
                    } catch (e: InterruptedException) {
                        interrupted = true
                    }
                }
                return true
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }

        private fun loop() {
            try {
                prepare()
                while (running) {
                    val frame = source.next()
                    if (frame == null) {
                        park(source.nanosToNextFrame().coerceIn(MIN_WAIT_NANOS, MAX_WAIT_NANOS))
                        continue
                    }
                    if (!running) break
                    onFrame(frame)
                }
            } catch (e: Exception) {
                // A failure after a stop, such as a read of a released recording, is not the microphone's failure.
                // Errors, such as OutOfMemoryError, are not caught.
                if (running) report(e)
            } finally {
                runLastActs()
            }
        }

        private fun runLastActs() {
            while (true) {
                val action = synchronized(this) {
                    lastActs.removeFirstOrNull().also { if (it == null) finished = true }
                } ?: return
                try {
                    action()
                } catch (e: Exception) {
                    report(e)
                }
            }
        }

        private fun report(e: Exception) {
            if (reported) return
            reported = true
            onError(e)
        }
    }

    companion object {
        /**
         * How long [stop] waits for the thread. A frame takes a few milliseconds of `onFrame`'s work, so the thread
         * normally ends within one frame; the limit stays well below Android's ANR timeouts.
         */
        const val STOP_MILLIS = 2_000L

        // A wait is at least 1 ms, so that a source that is a few samples short is not asked again at once, and at most
        // one frame, so that a source that cannot tell when its next frame comes is asked once a frame.
        internal const val MIN_WAIT_NANOS = 1_000_000L
        internal const val MAX_WAIT_NANOS = 20_000_000L
    }
}
