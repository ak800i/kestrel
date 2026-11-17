package dev.kestrel.app.listen

import dev.kestrel.voice.listen.Recognition

/**
 * When the owner began each utterance, for the transcript that ends it, in [clock]'s time (design spec, section 9.4).
 * The speech detector reports speech about 0.3 s after it starts (section 8.3), so the start lies that much earlier.
 * When the next utterance starts before the transcript of the one before it, its start is known only from the report
 * that follows that transcript, which is later: a start that is too late can only miss a change of the numbers, and it
 * never ends a command whose numbers did not change. An utterance can also end without any event: while Kestrel speaks,
 * while paused recognition skips it because it does not start with the name, and when the microphone stops. Its start is
 * dropped when recognition takes every utterance again and when the microphone starts again, so that it never goes to a
 * later utterance's transcript, where it would be too early. The collector of the pipeline's events calls
 * [speechStarted] and [ended] in the order of the events; the other functions may come from other threads.
 */
internal class UtteranceStarts(private val clock: () -> Long) {
    private var start: Long? = null

    /** The pipeline heard speech start, or still heard speech when a transcript arrived. */
    @Synchronized
    fun speechStarted() {
        if (start == null) start = clock() - DETECTION_MILLIS
    }

    /** The utterance ended with a transcript or without one: its start, or null when it is not known. */
    @Synchronized
    fun ended(): Long? = start.also { start = null }

    /**
     * Recognition changes from [before] to [after]. Call it before the pipeline reads [after], so that the report of
     * speech that the change brings comes after it.
     */
    @Synchronized
    fun recognitionChanged(before: Recognition, after: Recognition) {
        if (after != before && (before == Recognition.NONE || before == Recognition.NAMED)) start = null
    }

    /** The microphone starts again: the utterance that it stopped in has ended without a transcript. */
    @Synchronized
    fun microphoneStarted() {
        start = null
    }

    companion object {
        /** How long after the start of speech the speech detector reports it (design spec, section 8.3). */
        const val DETECTION_MILLIS = 300L
    }
}
