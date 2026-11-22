package dev.kestrel.perception

/** What a recogniser read in one box: its text and the mean score of the characters that it kept. */
data class Reading(val text: String, val score: Double) {
    override fun toString(): String = "Reading(text=${text.length} chars, score=$score)"
}

/**
 * CTC's greedy decoding (design D20 of sub-project 5; r48): the best class of each step, repeats collapsed, blanks
 * dropped. The score is the mean of the kept steps' best values, or 0 when no step is kept.
 */
object CtcDecoder {
    /** [scores] holds [steps] rows of the recogniser's classes, one after the other. */
    fun decode(scores: FloatArray, steps: Int, dictionary: Dictionary): Reading {
        val classes = dictionary.classes
        require(scores.size == steps * classes) { "Expected $steps steps of $classes classes" }
        val text = StringBuilder()
        var sum = 0.0
        var kept = 0
        var previous = -1
        for (step in 0 until steps) {
            var best = 0
            var bestScore = scores[step * classes]
            for (index in 1 until classes) {
                val score = scores[step * classes + index]
                if (score > bestScore) {
                    best = index
                    bestScore = score
                }
            }
            if (best != previous && best != 0) {
                text.append(dictionary.label(best))
                sum += bestScore
                kept++
            }
            previous = best
        }
        return Reading(text.toString(), if (kept == 0) 0.0 else sum / kept)
    }
}
