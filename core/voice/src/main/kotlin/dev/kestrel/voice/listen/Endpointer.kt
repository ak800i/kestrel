package dev.kestrel.voice.listen

import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Utterance
import kotlin.math.roundToLong

/**
 * An utterance that is ready to transcribe. [startSample] counts samples since the audio started, and [named] is true
 * when the keyword spotter heard "Kestrel" at its start.
 */
class Speech(val startSample: Long, val samples: FloatArray, val named: Boolean) {
    val endSample: Long get() = startSample + samples.size
    val seconds: Float get() = samples.size / SAMPLE_RATE.toFloat()
}

/**
 * Decides when an utterance has ended (design spec, section 8.3). The speech detector ends an utterance after 0.7 s of
 * silence. When the keyword spotter heard "Kestrel" at its start, the limit is 1.5 s instead: the endpointer holds the
 * utterance, and joins the next one to it when that one starts less than 1.5 s after its end, so that the owner can
 * pause while dictating a task. A joined utterance lasts at most 30 s, so the next part of speech that the detector cut
 * at 30 s stays separate. Not thread-safe: feed it from one thread.
 */
class Endpointer {
    private val names = ArrayDeque<Long>()
    private var held: Speech? = null

    /**
     * Takes the utterances that the speech detector returned for the audio up to [position], the positions at which the
     * keyword spotter heard "Kestrel" in the same audio, and whether the detector hears speech now, and returns the
     * utterances that are complete. All positions count samples since the audio started.
     */
    fun accept(utterances: List<Utterance>, nameHits: List<Long>, hearsSpeech: Boolean, position: Long): List<Speech> {
        names.addAll(nameHits)
        val ready = mutableListOf<Speech>()
        for (utterance in utterances) {
            val speech = Speech(utterance.startSample, utterance.samples, startsWithName(utterance))
            val earlier = held
            val joined = earlier?.let { join(it, speech) }
            held = when {
                joined != null -> joined
                speech.named -> speech
                else -> null
            }
            if (joined == null) {
                if (earlier != null) ready += earlier
                if (!speech.named) ready += speech
            }
        }
        val waiting = held
        if (waiting != null &&
            (!hearsSpeech && position - waiting.endSample >= RELEASE_AFTER || position - waiting.startSample >= LONGEST)
        ) {
            ready += waiting
            held = null
        }
        while (names.isNotEmpty() && names.first() < position - LONGEST - RELEASE_AFTER) names.removeFirst()
        return ready
    }

    /** Ends the audio, and returns the utterance that the endpointer still holds, if any. */
    fun finish(): List<Speech> {
        val last = listOfNotNull(held)
        held = null
        names.clear()
        return last
    }

    private fun startsWithName(utterance: Utterance): Boolean =
        names.any { it >= utterance.startSample && it <= utterance.startSample + NAME_WINDOW }

    /** [earlier], silence for the pause and [later] in one utterance, or null when the pause or the result is too long. */
    private fun join(earlier: Speech, later: Speech): Speech? {
        val pause = maxOf(0L, later.startSample - earlier.endSample)
        val size = earlier.samples.size + pause + later.samples.size
        if (pause >= NAMED_PAUSE || size > LONGEST) return null
        val samples = FloatArray(size.toInt())
        earlier.samples.copyInto(samples)
        later.samples.copyInto(samples, (earlier.samples.size + pause).toInt())
        return Speech(earlier.startSample, samples, named = true)
    }

    private companion object {
        // Design spec, section 8.3.
        val NAMED_PAUSE = samples(1.5f)
        val LONGEST = samples(30f)

        // The speech detector reports speech only after 0.25 s of it (sherpa-onnx's minimum speech duration) and up to
        // two windows of 32 ms, so a held utterance waits that much longer than the pause that still joins utterances.
        val RELEASE_AFTER = NAMED_PAUSE + samples(0.35f)

        // "Kestrel" takes about half a second, and the spotter reports it a little after the word ends.
        val NAME_WINDOW = samples(1.5f)

        fun samples(seconds: Float): Long = (seconds * SAMPLE_RATE).roundToLong()
    }
}
