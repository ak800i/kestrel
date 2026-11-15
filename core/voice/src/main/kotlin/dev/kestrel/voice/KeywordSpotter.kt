package dev.kestrel.voice

/** The words that the keyword spotter listens for (design spec, section 8.2). */
enum class Keyword { KESTREL, STOP, CANCEL }

/** A keyword that the spotter heard, [seconds] after the start of its audio, which is shortly after the keyword ended. */
data class KeywordHit(val keyword: Keyword, val seconds: Float)

/**
 * Listens for "Kestrel", "stop" and "cancel" in a stream of 16 kHz audio (design spec, sections 8.2–8.4). Not
 * thread-safe: feed it from one thread, and close it when it is no longer needed.
 */
interface KeywordSpotter : AutoCloseable {
    /** Feeds 16 kHz samples, any number at a time, and returns the keywords that it heard in them. */
    fun accept(samples: FloatArray): List<KeywordHit>
}
