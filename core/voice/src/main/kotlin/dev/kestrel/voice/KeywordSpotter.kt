package dev.kestrel.voice

/** The words that the keyword spotter listens for (design spec, section 8.2). */
enum class Keyword { KESTREL, STOP, CANCEL }

/**
 * A keyword that the spotter heard. [seconds] is the end of the samples of the [KeywordSpotter.accept] call that reported
 * the hit, counted from the start of the spotter's audio: the audio that the spotter had received when it reported the
 * keyword, which is shortly after the keyword ended.
 */
data class KeywordHit(val keyword: Keyword, val seconds: Float)

/**
 * How eagerly the keyword spotter takes a keyword (design spec, section 8.2): sherpa-onnx's [boost] of each keyword's
 * score and its [threshold] for a keyword's probability. A higher boost and a lower threshold hear more keywords, true and
 * false ones. The defaults are Kestrel's since plan 2A; the A71 session tries others through a debug hook (plan 2E), and
 * only a setting that the A71 measured may replace them.
 */
data class KeywordTuning(val boost: Float = 2.0f, val threshold: Float = 0.15f) {
    init {
        require(boost in 0.5f..10f) { "The boost must lie between 0.5 and 10" }
        require(threshold in 0.01f..0.5f) { "The threshold must lie between 0.01 and 0.5" }
    }
}

/**
 * Listens for "Kestrel", "stop" and "cancel" in a stream of 16 kHz audio (design spec, sections 8.2–8.4). Not
 * thread-safe: feed it from one thread. Close it when it is no longer needed, and only after the last call has returned;
 * Kestrel's spotter throws [IllegalStateException] when it is called after [close].
 */
interface KeywordSpotter : AutoCloseable {
    /** Feeds 16 kHz samples, any number at a time, and returns the keywords that it heard in them. */
    fun accept(samples: FloatArray): List<KeywordHit>
}
