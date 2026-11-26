package dev.kestrel.voice.listen

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * The thread of a [Microphone], one rule for every microphone (design spec, section 8.1). It asks [source] for frames,
 * which never blocks, waits until the next frame can be complete while none is there, and hands each frame to [onFrame].
 * It checks for a [stop] before each frame and each wait, and [stop] wakes it, so it ends within one call of the source
 * or of [onFrame].
 *
 * It never relies on a stop to wake a read: the stop of an `AudioRecord` does not always wake a blocking read (the call
 * of 2026-11-26 on kestrel-api37), and that read then waited for ever, and so did the stop.
 *
 * [onError] is called at most once, and then the thread ends: with the exception that [source] or [onFrame] threw, but
 * not for a failure of [source] after a stop, such as a read of a released recording.
 */
class MicrophoneThread private constructor(
    private val source: FrameSource,
    private val onFrame: (FloatArray) -> Unit,
    private val onError: (Exception) -> Unit,
    private val prepare: () -> Unit,
) {
    @Volatile
    private var running = true

    private lateinit var thread: Thread

    /** Whether the calling thread is this microphone's thread. */
    val isCurrent: Boolean get() = Thread.currentThread() === thread

    /**
     * Stops the thread, and waits until it has ended, for at most [STOP_MILLIS], also when the calling thread is
     * interrupted; the interrupt is kept for the caller. Returns whether the thread ended. When it did not, because
     * [source] or [onFrame] was still busy, the thread ends as soon as that call returns, without another frame. Any
     * thread may call it, also more than once, except the microphone's own thread.
     */
    fun stop(): Boolean {
        check(!isCurrent) { "The microphone's thread cannot stop itself" }
        running = false
        LockSupport.unpark(thread)
        return awaitEnd(TimeUnit.MILLISECONDS.toNanos(STOP_MILLIS))
    }

    private fun awaitEnd(timeoutNanos: Long): Boolean {
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

    private fun run() {
        prepare()
        while (running) {
            val frame = try {
                source.next()
            } catch (e: Exception) {
                if (running) onError(e)
                return
            }
            if (frame == null) {
                LockSupport.parkNanos(source.nanosToNextFrame().coerceIn(MIN_WAIT_NANOS, MAX_WAIT_NANOS))
                continue
            }
            if (!running) return
            // Errors, such as OutOfMemoryError, are not caught.
            try {
                onFrame(frame)
            } catch (e: Exception) {
                onError(e)
                return
            }
        }
    }

    companion object {
        /**
         * How long [stop] waits for the thread. A frame takes a few milliseconds of [onFrame]'s work, so the thread
         * normally ends within one frame; the limit stays well below Android's ANR timeouts.
         */
        const val STOP_MILLIS = 2_000L

        // A wait is at least 1 ms, so that a source that is a few samples short is not asked again at once, and at most
        // one frame, so that a source that cannot tell when its next frame comes is asked once a frame.
        private const val MIN_WAIT_NANOS = 1_000_000L
        private const val MAX_WAIT_NANOS = 20_000_000L

        /**
         * Starts a thread named [name] that runs [prepare], such as raising its priority, and then reads [source]. Throws
         * when the thread cannot be created, such as an [OutOfMemoryError].
         */
        fun start(
            name: String,
            source: FrameSource,
            onFrame: (FloatArray) -> Unit,
            onError: (Exception) -> Unit,
            prepare: () -> Unit = {},
        ): MicrophoneThread {
            val microphone = MicrophoneThread(source, onFrame, onError, prepare)
            // Known before it runs, so that a stop() from onFrame or onError is recognised.
            microphone.thread = Thread(microphone::run, name)
            microphone.thread.start()
            return microphone
        }
    }
}
