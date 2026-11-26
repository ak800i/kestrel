package dev.kestrel.voice.listen

import kotlinx.coroutines.flow.StateFlow

/**
 * Where the listening pipeline's audio comes from (design spec, section 8.1): 20 ms frames of 16 kHz samples between -1
 * and 1. [MicCapture] records the microphone; the device tests and the debug hooks play clips instead.
 *
 * Any thread may call [start] and [stop], except the microphone's own thread.
 */
interface Microphone {
    /**
     * True while Android silences the recording: when another app has priority on the microphone, when the microphone's
     * privacy toggle is on, or when Android counts Kestrel as idle. "Mic busy" on the feedback bar (section 8.1). [stop]
     * sets it back to false.
     */
    val silenced: StateFlow<Boolean>

    /** Whether an echo canceller works on the current recording. */
    val echoCancelling: Boolean

    /**
     * Starts recording. [onFrame] gets each 20 ms frame. Both callbacks run on the microphone's own thread.
     *
     * [onError] is called at most once, and then the thread ends: when reading the microphone fails, or with the
     * exception that [onFrame] threw while the microphone recorded, or with one from a [stop]'s `afterEnd` that ran late.
     * [onError] itself must not throw. After an error nothing reads the microphone any more, but the recording stays open
     * until [stop]. [start] and [stop] throw [IllegalStateException] when they are called from the callbacks, because
     * [stop] waits for the thread; the caller hands the error to another thread, which then stops the microphone.
     *
     * Throws when the microphone does not start, for example [SecurityException] without the microphone permission; a
     * later [stop] releases whatever the start had opened. Throws [IllegalStateException] while the microphone records,
     * also after an error until [stop], and while the thread of an earlier stop still lingers, so that no two threads
     * ever feed [onFrame]'s receiver ([MicrophoneThread], the one rule for every microphone's thread).
     */
    fun start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit)

    /**
     * Stops recording, and waits until the microphone's thread has finished, also when the calling thread is
     * interrupted, but never longer than [MicrophoneThread.STOP_MILLIS]. [afterEnd] runs once after the microphone's last
     * frame, for work that needs the thread ended: before [stop] returns when the thread ended in time or none ran, and
     * otherwise on that thread as its last act, once its current callback returns. A stop also closes a recording whose
     * thread ended with an error. It releases every part of the recording, also when an earlier part fails, and then
     * throws the first failure, or [IllegalStateException] when the thread did not end in time; that thread then lingers
     * without another frame. A stop while it lingers neither waits nor starts anything, and throws an
     * [IllegalStateException] that says so. A start while it lingers throws [MicrophoneBusyException].
     */
    fun stop(afterEnd: () -> Unit = {})
}