package dev.kestrel.voice.sherpa

import dev.kestrel.voice.Transcript

/**
 * Decides whether a transcript that was decoded with boost words is kept (design spec, section 8.3). A strong boost once
 * cut a transcript short in tests, so when the last word ends more than [GAP_SECONDS] before the audio does, the audio is
 * decoded again without boost words, and the second transcript wins if it has more word pieces.
 */
internal object TranscriptChoice {
    const val GAP_SECONDS = 1.0f

    fun choose(audioSeconds: Float, boosted: Transcript, plain: () -> Transcript): Transcript {
        if (audioSeconds - boosted.lastWordEnd <= GAP_SECONDS) return boosted
        val second = plain()
        return if (second.pieces > boosted.pieces) second else boosted
    }
}
