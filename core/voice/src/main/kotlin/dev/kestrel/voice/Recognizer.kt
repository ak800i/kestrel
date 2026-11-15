package dev.kestrel.voice

/**
 * What the recogniser heard: the [text], the number of word [pieces] in it, and the time in seconds at which the last
 * word piece ends ([lastWordEnd]), which is 0 when it heard nothing.
 */
data class Transcript(val text: String, val pieces: Int, val lastWordEnd: Float) {
    // The text is the owner's words, which must never reach a log or an exception message (design spec, section 13.5).
    override fun toString() = "Transcript(${text.length} characters, pieces=$pieces, lastWordEnd=$lastWordEnd)"
}

/**
 * Transcribes utterances of 16 kHz audio (design spec, sections 8.2 and 8.3). Not thread-safe: use one instance from one
 * thread at a time, and close it when it is no longer needed, because it holds about 700 MB of model in native memory.
 */
interface Recognizer : AutoCloseable {
    /** Transcribes one utterance. [boostWords] are words that the speaker is likely to say, such as labels on the screen. */
    fun transcribe(samples: FloatArray, boostWords: List<String> = emptyList()): Transcript
}
