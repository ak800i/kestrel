package dev.kestrel.voice.sherpa

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Utterance
import java.io.File

/**
 * Silero VAD through sherpa-onnx. The model takes the audio in windows of 512 samples, so this collects them.
 *
 * sherpa-onnx's own `maxSpeechDuration` does not cut an utterance; after 30 s it only lets a 0.1 s pause end it. So this
 * class cuts an utterance itself before it reaches 30 s. When the speech ends less than 0.25 s after a cut, it drops that
 * rest, so that every utterance lasts at least 0.25 s. sherpa-onnx also counts samples in 32 bits, so during silence
 * this class restarts sherpa-onnx's count once an hour and keeps its own count in a `Long`.
 *
 * sherpa-onnx starts an utterance only after 0.25 s of windows in a row that sound like speech, so a short first word
 * followed by a pause, as in "Tap | number twelve", falls before its start. So this class keeps the last second of the
 * audio, copies it at the first window of each utterance, and gives each utterance up to 0.3 s of the audio before its
 * speech and after it (design spec, section 8.3). It hands each utterance out in the window that ends it, while the ring
 * still holds the audio after it.
 *
 * A call after [close] throws [IllegalStateException]; [close] waits for a call that is going on. Close the detector only
 * after the last call has returned.
 */
internal class SherpaSpeechDetector(model: File) : SpeechDetector {
    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model.path,
                minSilenceDuration = END_SILENCE_SECONDS,
                minSpeechDuration = MIN_SPEECH_SECONDS,
                maxSpeechDuration = MAX_SPEECH_SECONDS,
                windowSize = WINDOW,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )
    private val window = FloatArray(WINDOW)
    private var filled = 0
    private var closed = false

    // The samples of the utterance that is going on, as far as sherpa-onnx has seen them.
    private var speechSamples = 0

    // The samples that sherpa-onnx took since its last reset, and the samples before that reset since the last finish().
    private var sinceReset = 0L
    private var beforeReset = 0L

    // The last second of the audio that sherpa-onnx took, its copy at the first window of the utterance going on, and
    // where the audio of the last utterance handed out ends, before which no lead reaches back.
    private val recent = AudioRing(RING_SAMPLES)
    private var atStart: AudioRing? = null
    private var handedOut = 0L

    override val hearsSpeech: Boolean
        get() = synchronized(this) {
            check(!closed) { "The speech detector is closed" }
            vad.isSpeechDetected()
        }

    override fun accept(samples: FloatArray): List<Utterance> = synchronized(this) {
        check(!closed) { "The speech detector is closed" }
        val utterances = mutableListOf<Utterance>()
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(WINDOW - filled, samples.size - offset)
            samples.copyInto(window, filled, offset, offset + count)
            filled += count
            offset += count
            if (filled == WINDOW) {
                acceptWindow(utterances)
                filled = 0
            }
        }
        utterances
    }

    private fun acceptWindow(utterances: MutableList<Utterance>) {
        vad.acceptWaveform(window)
        recent.add(window)
        sinceReset += WINDOW
        if (!vad.isSpeechDetected()) {
            speechSamples = 0
            utterances += utterances()
            if (sinceReset >= RESET_SAMPLES) {
                // reset() drops the utterances that sherpa-onnx has not handed out yet; utterances() took them.
                vad.reset()
                beforeReset += sinceReset
                sinceReset = 0
            }
            return
        }
        // The first window of an utterance: the ring still holds the audio before its start.
        if (speechSamples == 0) atStart = recent.copy()
        speechSamples += WINDOW
        // sherpa-onnx starts an utterance two windows and the minimum speech duration before the window in which it
        // noticed the speech. After flush(), the next window starts a new utterance exactly at the cut. The cut comes
        // 0.3 s earlier for the audio before the speech.
        if (speechSamples + 2 * WINDOW + MIN_SPEECH_SAMPLES + PAD_SAMPLES >= MAX_SPEECH_SAMPLES) {
            vad.flush()
            speechSamples = 0
            utterances += utterances()
        }
    }

    override fun finish(): List<Utterance> = synchronized(this) {
        check(!closed) { "The speech detector is closed" }
        vad.flush()
        val utterances = utterances()
        vad.reset()
        filled = 0
        speechSamples = 0
        sinceReset = 0
        beforeReset = 0
        recent.clear()
        atStart = null
        handedOut = 0
        utterances
    }

    private fun utterances(): List<Utterance> {
        val utterances = mutableListOf<Utterance>()
        while (!vad.empty()) {
            val segment = vad.front()
            // Only the rest after a cut can be shorter than the minimum: when the speech ends just after the cut,
            // sherpa-onnx queues a segment of a few windows of silence, or one without samples. This drops it.
            if (segment.samples.size >= MIN_SPEECH_SAMPLES) {
                val utterance = AudioRing.padded(
                    beforeReset + segment.start, segment.samples, atStart ?: recent, recent, handedOut, PAD_SAMPLES, MAX_SPEECH_SAMPLES,
                )
                handedOut = utterance.endSample + utterance.trail
                utterances += utterance
            }
            vad.pop()
        }
        return utterances
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            vad.release()
        }
    }

    private companion object {
        const val WINDOW = 512
        // Design spec, section 8.3.
        const val END_SILENCE_SECONDS = 0.7f
        const val MAX_SPEECH_SECONDS = 30f
        const val MAX_SPEECH_SAMPLES = 30 * SAMPLE_RATE
        // sherpa-onnx's default, passed explicitly because the cut at 30 s counts on it.
        const val MIN_SPEECH_SECONDS = 0.25f
        val MIN_SPEECH_SAMPLES = (MIN_SPEECH_SECONDS * SAMPLE_RATE).toInt()
        // An hour of audio, far below the 2^31 samples (about 37 hours) at which sherpa-onnx's count overflows.
        const val RESET_SAMPLES = 3_600L * SAMPLE_RATE

        // Design spec, section 8.3: up to 0.3 s of the audio before an utterance's speech and after it. The ring holds a
        // second: at an utterance's first window, its start lies about 0.32 s back.
        const val PAD_SAMPLES = 3 * SAMPLE_RATE / 10
        const val RING_SAMPLES = SAMPLE_RATE
    }
}

/**
 * The newest [capacity] samples of the audio, by their position in it, so that an utterance can take the audio around
 * its speech after the speech detector has found it.
 */
internal class AudioRing(private val capacity: Int) {
    private val samples = FloatArray(capacity)

    /** The position after the newest sample. */
    var end = 0L
        private set

    /** The position of the oldest sample that the ring holds. */
    val start: Long get() = maxOf(0L, end - capacity)

    fun add(audio: FloatArray) {
        for (sample in audio) {
            samples[(end % capacity).toInt()] = sample
            end++
        }
    }

    /** The samples from [from] until [to], which lie between [start] and [end]. */
    fun copy(from: Long, to: Long): FloatArray {
        require(from in start..to && to <= end) { "The ring does not hold that audio" }
        return FloatArray((to - from).toInt()) { samples[((from + it) % capacity).toInt()] }
    }

    fun copy(): AudioRing = AudioRing(capacity).also { copy ->
        samples.copyInto(copy.samples)
        copy.end = end
    }

    fun clear() {
        end = 0
    }

    companion object {
        /**
         * The utterance whose [speech] starts at [start], with up to [pad] samples of the audio before it, from [before],
         * the ring as it was at the utterance's first window, and up to [pad] after it, from [after], the ring now. The
         * audio before it never reaches back before [from], where the audio of the utterance before it ended, and all the
         * samples stay within [longest].
         */
        fun padded(start: Long, speech: FloatArray, before: AudioRing, after: AudioRing, from: Long, pad: Int, longest: Int): Utterance {
            val room = maxOf(0, longest - speech.size)
            val leadFrom = maxOf(start - minOf(pad, room), from, before.start)
            val lead = if (start <= before.end) (start - leadFrom).coerceAtLeast(0L).toInt() else 0
            val end = start + speech.size
            val trailTo = minOf(end + minOf(pad, room - lead), after.end)
            val trail = if (end >= after.start) (trailTo - end).coerceAtLeast(0L).toInt() else 0
            val leading = if (lead > 0) before.copy(start - lead, start) else FloatArray(0)
            val trailing = if (trail > 0) after.copy(end, end + trail) else FloatArray(0)
            return Utterance(start, leading + speech + trailing, lead, trail)
        }
    }
}
