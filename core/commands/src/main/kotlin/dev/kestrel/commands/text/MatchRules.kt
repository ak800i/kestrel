package dev.kestrel.commands.text

/**
 * The matcher's limits and weights (design spec, section 9.6): research r23's starting points. Plan 3C's corpus run may
 * tune the values; nothing else changes them.
 */
object MatchRules {
    /** The best score that acts with `Strictness.NORMAL`, and how far it must beat the second best. */
    const val ACCEPT = 0.80
    const val MARGIN = 0.10

    /** The same for `Strictness.STRICT`, bare labels, contacts and every phrase or best name of 4 letters or fewer. */
    const val STRICT_ACCEPT = 0.90
    const val STRICT_MARGIN = 0.15

    /** A candidate at least this good can be offered in "Which one?"; below it for every candidate, nothing was found. */
    const val CLOSE = 0.60

    /** "Which one?" offers the candidates within this distance of the best, at most [MAX_AMBIGUOUS] of them. */
    const val NEAR = 0.10
    const val MAX_AMBIGUOUS = 20

    /** A spoken phrase or best name with at most this many letters and digits takes the strict limits. */
    const val SHORT_LETTERS = 4

    /** The least score of a phrase whose words are the first words of a name. */
    const val PREFIX_SCORE = 0.85

    /** Two words count as the same with at least this Jaro–Winkler similarity. */
    const val WORD_SIMILARITY = 0.92

    /**
     * With `Strictness.NORMAL` only, two words of at least this many letters count as the same when their phonetic keys
     * are equal and their respelled forms are at least [PHONETIC_WORD_SIMILARITY] similar, so that the key never accepts
     * a word on its own.
     */
    const val PHONETIC_WORD_LETTERS = 5
    const val PHONETIC_WORD_SIMILARITY = 0.80

    const val JOINED_WEIGHT = 0.40
    const val WORDS_WEIGHT = 0.35
    const val PHONETIC_WEIGHT = 0.25

    /** Doubles are not exact: 0.95 - 0.80 is 0.1499999999999999. */
    const val TOLERANCE = 1e-9
}
