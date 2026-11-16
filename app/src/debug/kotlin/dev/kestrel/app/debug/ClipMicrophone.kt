package dev.kestrel.app.debug

import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.listen.Microphone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport

/**
 * A stand-in for the microphone, for the device tests and the debug hooks (design spec, section 16.1). It delivers the
 * clips that [play] queues, in 20 ms frames of 16 kHz samples, in real time: one frame every 20 ms on its own thread,
 * and silence between the clips. A clip that is still playing when [stop] comes is dropped; the clips after it wait for
 * the next [start]. [silence] stands in for Android silencing the recording.
 */
class ClipMicrophone : Microphone {
    private val clips = ConcurrentLinkedQueue<FloatArray>()
    private val silencedState = MutableStateFlow(false)

    @Volatile
    private var reader: Thread? = null

    @Volatile
    private var running = false

    override val silenced: StateFlow<Boolean> = silencedState.asStateFlow()

    override val echoCancelling = false

    /** Whether the microphone records: from [start] until [stop], also after an error. */
    val recording: Boolean get() = reader != null

    /** Queues [samples] of 16 kHz audio, which the microphone delivers after the clips before them. Any thread. */
    fun play(samples: FloatArray) {
        clips.add(samples)
    }

    /** Silences the recording, or ends the silencing, as Android does when another app takes the microphone. */
    fun silence(on: Boolean) {
        silencedState.value = on
    }

    /** Drops the clips that wait to be played. Any thread. */
    fun clear() {
        clips.clear()
    }

    override fun start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
        checkNotOnTheMicrophonesThread()
        synchronized(this) {
            check(reader == null) { "The microphone is already recording" }
            running = true
            val thread = Thread({ deliver(onFrame, onError) }, "Kestrel clip microphone")
            reader = thread
            thread.start()
        }
    }

    override fun stop() {
        checkNotOnTheMicrophonesThread()
        synchronized(this) {
            val thread = reader ?: return
            running = false
            LockSupport.unpark(thread)
            joinUninterruptibly(thread)
            reader = null
            silencedState.value = false
        }
    }

    // Checked before taking the monitor: a stop() on another thread holds it while it waits for the microphone's thread.
    private fun checkNotOnTheMicrophonesThread() {
        check(Thread.currentThread() !== reader) { "start() and stop() must not be called from the microphone's thread" }
    }

    // As MicCapture does: an interrupt must not leave the thread running, so the wait goes on, and the interrupt is kept
    // for the caller.
    private fun joinUninterruptibly(thread: Thread) {
        var interrupted = false
        while (true) {
            try {
                thread.join()
                break
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun deliver(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
        var clip: FloatArray? = null
        var offset = 0
        var next = System.nanoTime()
        while (running) {
            val current = clip ?: clips.poll()
            val frame = FloatArray(FRAME)
            if (current != null) {
                val count = minOf(FRAME, current.size - offset)
                current.copyInto(frame, 0, offset, offset + count)
                offset += count
                if (offset >= current.size) {
                    clip = null
                    offset = 0
                } else {
                    clip = current
                }
            }
            try {
                onFrame(frame)
            } catch (e: Exception) {
                onError(e)
                return
            }
            // A slow frame is made up by the next ones, as a real microphone's buffer would.
            next += FRAME_NANOS
            while (running) {
                val wait = next - System.nanoTime()
                if (wait <= 0) break
                LockSupport.parkNanos(wait)
            }
        }
    }

    companion object {
        private const val FRAME = SAMPLE_RATE / 50
        private const val FRAME_NANOS = 20_000_000L

        /**
         * [text] in [voice], at 16 kHz, with 0.5 s of silence before it and 1 s after it, as the microphone hears an
         * utterance.
         */
        fun speech(voice: Synthesizer, text: String): FloatArray =
            FloatArray(SAMPLE_RATE / 2) + resample(voice.synthesize(text), voice.sampleRate) + FloatArray(SAMPLE_RATE)

        // Linear interpolation is enough for test speech.
        private fun resample(samples: FloatArray, from: Int): FloatArray {
            if (samples.isEmpty()) return samples
            val count = (samples.size.toLong() * SAMPLE_RATE / from).toInt()
            return FloatArray(count) { index ->
                val position = index.toDouble() * from / SAMPLE_RATE
                val before = position.toInt()
                val after = minOf(before + 1, samples.size - 1)
                val fraction = (position - before).toFloat()
                samples[before] + (samples[after] - samples[before]) * fraction
            }
        }
    }
}
