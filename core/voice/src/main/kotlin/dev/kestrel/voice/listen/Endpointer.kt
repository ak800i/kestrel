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
 * pause while dictating a task. A held utterance goes out at the latest 30 s after its start, also while the owner still
 * speaks, and a joined utterance lasts at most 30 s. What comes after that, such as the next part of long speech that
 * the detector cut at 30 s, is a separate utterance. Not thread-safe: feed it from one thread.
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
            val joined = held?.let { join(it, speech) }
            if (joined != null) {
                held = joined
            } else {
                held?.let { ready += it }
                held = if (speech.named) speech else null
                if (!speech.named) ready += speech
            }
        }
        val waiting = held
        if (waiting != null) {
            val pauseIsOver = !hearsSpeech && position - waiting.endSample >= RELEASE_AFTER
            val tooLong = position - waiting.startSample >= LONGEST
            if (pauseIsOver || tooLong) {
                ready += waiting
                held = null
            }
        }
        while (names.isNotEmpty() && names.first() < position - KEEP_NAMES) names.removeFirst()
        return ready
    }

    /**
     * Ends the audio: returns the utterance that the endpointer still holds, if any, and forgets the name hits, so that
     * positions may start again from 0.
     */
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

        // The speech detector dates an utterance's start two windows of 512 samples and its minimum speech duration of
        // 0.25 s (0.314 s in all) before the window in which it reports speech. So speech that starts less than 1.5 s
        // after a held utterance's end shows in hearsSpeech less than 1.814 s after that end.
        val RELEASE_AFTER = NAMED_PAUSE + samples(0.35f)

        // The name may follow a word and a short pause ("Hey, Kestrel, ..."). "Kestrel" takes about half a second, and
        // the spotter reports it 0.3-0.55 s after the word ends, so its hit comes up to about 1.8 s after the start.
        val NAME_WINDOW = samples(2f)

        // An utterance arrives at most 30 s and the detector's 0.7 s of silence (plus the window that ends it) after its
        // start, so a hit more than 31 s old can no longer name one.
        val KEEP_NAMES = samples(31f)

        fun samples(seconds: Float): Long = (seconds * SAMPLE_RATE).roundToLong()
    }
}
