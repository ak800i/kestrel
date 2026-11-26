package dev.kestrel.voice.listen

/**
 * Where a [MicrophoneThread] takes its 20 ms frames from: a recording, or the clips of the device tests. Both functions
 * run on the microphone's thread, and neither may block, so that a stop never waits for a source.
 */
interface FrameSource {
    /**
     * The next frame of samples between -1 and 1 when all of it is there, or null while it is not. Throws
     * [IllegalStateException] when the source failed.
     */
    fun next(): FloatArray?

    /** How long, in nanoseconds, until the next frame can be complete, after [next] returned null. */
    fun nanosToNextFrame(): Long
}
