package dev.kestrel.app.listen

/**
 * When the owner began each utterance, for the transcript that ends it, in [clock]'s time (design spec, section 9.4).
 * The speech detector reports speech about 0.3 s after it starts (section 8.3), so the start lies that much earlier.
 * When the next utterance starts before the transcript of the one before it, its start is known only from the report
 * that follows that transcript, which is later: a start that is too late can only miss a change of the numbers, and it
 * never ends a command whose numbers did not change. One thread calls it at a time, in the order of the pipeline's events.
 */
internal class UtteranceStarts(private val clock: () -> Long) {
    private var start: Long? = null

    /** The pipeline heard speech start, or still heard speech when a transcript arrived. */
    fun speechStarted() {
        if (start == null) start = clock() - DETECTION_MILLIS
    }

    /** The utterance ended with a transcript or without one: its start, or null when it is not known. */
    fun ended(): Long? = start.also { start = null }

    companion object {
        /** How long after the start of speech the speech detector reports it (design spec, section 8.3). */
        const val DETECTION_MILLIS = 300L
    }
}
