package dev.kestrel.app.debug

import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.WordErrors
import java.util.Collections

/**
 * One expectation of hook H2 (plan 4D): the text [expected] that a clip played through the air should become, waited for
 * until [until] on the phone's clock. [offer] compares each transcript with voice-eval's comparison (WordErrors): the first
 * one with at most half the expected words wrong matches; every other one is a stray, and a stray whose words are a run
 * of the expected words, in order, is also a split part. [noRun]: the match is only resolved, never run, and every
 * transcript is held back until [until]. The texts stay in memory; it reports counts and times only.
 */
internal class Expectation(val tag: String, val expected: String, val noRun: Boolean, val until: Long) {
    /** The match: its word errors, its times on the phone's clock, and with [noRun] its command's kind and whether it is right. */
    data class Match(
        val errors: Int,
        val speechEnded: Long,
        val closed: Long,
        val transcribed: Long,
        val kind: String = "none",
        val right: String = "-",
    )

    val words: Int = WordErrors.count(expected, "").words

    var match: Match? = null
        private set
    var stray = 0
        private set
    var split = 0
        private set

    // The fewest errors of a stray, for an expectation without a match.
    private var best = words

    /** The errors of the match, or of the closest stray, or [words] when nothing came. */
    val errors: Int get() = match?.errors ?: best

    /** Whether it still waits for its match at [now]. */
    fun waiting(now: Long): Boolean = match == null && now < until

    /** Whether transcripts stay away from Kestrel at [now]: until the match, and with [noRun] until [until]. */
    fun holds(now: Long): Boolean = now < until && (match == null || noRun)

    /**
     * Compares [heard], whose speech ended at [speechEnded] and whose pause ended at [closed], and which came at [now]:
     * true when it is the match, false when it is a stray. [judge] gives the match's kind and whether it is right.
     */
    fun offer(
        heard: String,
        speechEnded: Long,
        closed: Long,
        now: Long,
        judge: (String) -> Pair<String, String> = { "none" to "-" },
    ): Boolean {
        val errors = WordErrors.count(expected, heard).errors
        if (match == null && words > 0 && errors * 2 <= words) {
            val (kind, right) = judge(heard)
            match = Match(errors, speechEnded, closed, now, kind, right)
            return true
        }
        stray++
        best = minOf(best, errors)
        if (isRun(Normalizer.words(heard), Normalizer.words(expected))) split++
        return false
    }

    private fun isRun(part: List<String>, whole: List<String>): Boolean =
        part.isNotEmpty() && part.size < whole.size && Collections.indexOfSubList(whole, part) >= 0
}
