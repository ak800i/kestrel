package dev.kestrel.app.debug

import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.listen.FrameSource
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.listen.MicrophoneThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * A stand-in for the microphone, for the device tests and the debug hooks (design spec, section 16.1). It delivers the
 * clips that [play] queues, in 20 ms frames of 16 kHz samples, in real time: one frame every 20 ms on its own thread,
 * and silence between the clips. A clip that is still playing when [stop] comes is dropped; the clips after it wait for
 * the next [start]. [silence] stands in for Android silencing the recording: the clips go on, but the microphone
 * delivers zeros.
 */
class ClipMicrophone : Microphone {
    private val clips = ConcurrentLinkedQueue<FloatArray>()
    private val silencedState = MutableStateFlow(false)

    private val threads = MicrophoneThread("Kestrel clip microphone")

    @Volatile
    private var recordingNow = false

    override val silenced: StateFlow<Boolean> = silencedState.asStateFlow()

    override val echoCancelling = false

    /** Whether the microphone records: from [start] until [stop], also after an error. */
    val recording: Boolean get() = recordingNow

    /** Queues [samples] of 16 kHz audio, which the microphone delivers after the clips before them. Any thread. */
    fun play(samples: FloatArray) {
        clips.add(samples)
    }

    /**
     * Silences the recording, or ends the silencing, as Android does when another app takes the microphone: while it
     * is silenced, the microphone delivers zeros, and the clips go on as sound in the room does. As with Android,
     * only a recording is silenced: `silence(true)` throws [IllegalStateException] while the microphone does not
     * record, and [stop] ends the silencing. Any thread.
     */
    fun silence(on: Boolean) {
        synchronized(this) {
            check(!on || recordingNow) { "The microphone does not record" }
            silencedState.value = on
        }
    }

    /** Drops the clips that wait to be played. Any thread. */
    fun clear() {
        clips.clear()
    }

    override fun start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
        checkNotOnTheMicrophonesThread()
        synchronized(this) {
            threads.start(ClipFrames(), onFrame, onError)
            recordingNow = true
        }
    }

    override fun stop(afterEnd: () -> Unit) {
        checkNotOnTheMicrophonesThread()
        synchronized(this) {
            recordingNow = false
            silencedState.value = false
            threads.stop(afterEnd)

        }
    }

    // Checked before taking the monitor: a stop() on another thread holds it while it waits for the microphone's thread.
    private fun checkNotOnTheMicrophonesThread() {
        check(!threads.isCurrent) { "start() and stop() must not be called from the microphone's thread" }
    }

    // The queued clips in real time, one frame every 20 ms from the start. A slow frame is made up by the next ones, as a
    // real microphone's buffer would. Used on the microphone's thread only.
    private inner class ClipFrames : FrameSource {
        private var clip: FloatArray? = null
        private var offset = 0
        private var due = System.nanoTime()

        override fun next(): FloatArray? {
            if (System.nanoTime() < due) return null
            due += FRAME_NANOS
            val frame = FloatArray(FRAME)
            val current = clip ?: clips.poll()
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
            if (silencedState.value) frame.fill(0f)
            return frame
        }

        override fun nanosToNextFrame(): Long = due - System.nanoTime()
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

        // The filter's cutoff as a share of the lower Nyquist frequency (7.6 kHz for 16 kHz), and its half width in
        // zero crossings of the sinc.
        private const val CUTOFF = 0.95
        private const val ZERO_CROSSINGS = 32

        /**
         * [samples] of [from] Hz at 16 kHz, through a windowed-sinc low-pass filter (a Blackman window), so that the sound
         * of the voices' 22.05 or 24 kHz audio above 8 kHz does not fold back into the speech band, as it did with linear
         * interpolation (plan 3C's corpus run). Each output sample weighs the input samples around its position; the
         * weights depend only on the position's fraction, of which a rational rate has few, so they are computed once.
         */
        internal fun resample(samples: FloatArray, from: Int): FloatArray {
            if (samples.isEmpty() || from == SAMPLE_RATE) return samples
            // In cycles per input sample.
            val cutoff = CUTOFF * minOf(from, SAMPLE_RATE) / from / 2
            val halfWidth = ZERO_CROSSINGS / (2 * cutoff)
            val reach = ceil(halfWidth).toInt()
            val phases = HashMap<Long, DoubleArray>()
            val count = (samples.size.toLong() * SAMPLE_RATE / from).toInt()
            return FloatArray(count) { index ->
                val scaled = index.toLong() * from
                val base = (scaled / SAMPLE_RATE).toInt()
                val weights = phases.getOrPut(scaled % SAMPLE_RATE) {
                    val fraction = (scaled % SAMPLE_RATE).toDouble() / SAMPLE_RATE
                    val raw = DoubleArray(2 * reach + 1) { kernel(fraction - (it - reach), cutoff, halfWidth) }
                    // The weights add up to 1, so that the level stays.
                    val sum = raw.sum()
                    DoubleArray(raw.size) { raw[it] / sum }
                }
                var sum = 0.0
                for (offset in -reach..reach) {
                    val input = base + offset
                    if (input >= 0 && input < samples.size) sum += samples[input] * weights[offset + reach]
                }
                sum.toFloat()
            }
        }

        // The low-pass filter at [distance] input samples from the output's position.
        private fun kernel(distance: Double, cutoff: Double, halfWidth: Double): Double {
            if (abs(distance) >= halfWidth) return 0.0
            val x = 2 * PI * cutoff * distance
            val sinc = if (x == 0.0) 1.0 else sin(x) / x
            val window = 0.42 + 0.5 * cos(PI * distance / halfWidth) + 0.08 * cos(2 * PI * distance / halfWidth)
            return 2 * cutoff * sinc * window
        }
    }
}
