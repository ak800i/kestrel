package dev.kestrel.app.listen

import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot

/**
 * The boost words of one utterance (design spec, section 8.3): "Kestrel", then at most [MAX_SCREEN] labels from the
 * screen, then at most [MAX_VOCABULARY] phrases of the command vocabulary, then the session's app names. sherpa-onnx
 * gets at most 100 usable phrases, so the cap drops the system apps first, as before. Never log a boost word.
 */
internal object BoostWordBudget {
    const val MAX_SCREEN = 40
    const val MAX_VOCABULARY = 8

    /** At most this many words and characters in a label; a longer one is the screen's text, not a name. */
    private const val MAX_WORDS = 4
    private const val MAX_LENGTH = 40

    /** A name in capitals with at most this many letters is often an acronym, spoken letter by letter, such as "GPS". */
    private const val SPELLED_LETTERS = 3

    /**
     * Puts the lists together. [fixed] is the session's list as [BoostWordSource] reads it, "Kestrel" first and the app
     * names after it. A phrase that came earlier, ignoring case, counts once.
     */
    fun combine(fixed: List<String>, screen: List<String>, vocabulary: List<String>): List<String> =
        (listOf(BoostWordSource.NAME) + screen.take(MAX_SCREEN) + vocabulary.take(MAX_VOCABULARY) + fixed)
            .distinctBy { it.lowercase() }

    /**
     * The names that [snapshot] offers as boost words, from the top of the screen down: those of the actionable
     * elements that a finger can reach ([ScreenSnapshot.reachable]: outside the keyboard, and not covered whole by a
     * window above, as the commands' candidates), that is an element's label, description and suggested name, and for a
     * text field only its hint, never what was typed into it. Password fields and data-sensitive elements give none. A
     * name with fewer than [BoostWordSource.SHORTEST] letters or digits, more than [MAX_WORDS] words or [MAX_LENGTH]
     * characters, or no letter, such as a time or a phone number, is left out, and a repeated name counts once, ignoring
     * case. A name in capitals, as Android reports a button with `textAllCaps`, goes in small letters when it has four
     * letters or more, because the recogniser would otherwise favour it spelled letter by letter; a shorter one, such as
     * "USB", stays, because it is spoken letter by letter.
     */
    fun screenNames(snapshot: ScreenSnapshot): List<String> =
        snapshot.elements
            .filter { it.isActionable && snapshot.reachable(it) && !it.password && !it.dataSensitive }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .flatMap(::namesOf)
            .map { it.trim() }
            .filter(::fits)
            .map(::spoken)
            .distinctBy { it.lowercase() }

    private fun namesOf(element: ScreenElement): List<String> =
        if (element.editable) listOfNotNull(element.hint) else listOfNotNull(element.label, element.contentDescription, element.suggestedName)

    private fun spoken(name: String): String =
        if (name.count { it.isLetter() } > SPELLED_LETTERS && name.none { it.isLowerCase() }) name.lowercase() else name

    private fun fits(name: String): Boolean =
        name.count { it.isLetterOrDigit() } >= BoostWordSource.SHORTEST &&
            name.length <= MAX_LENGTH &&
            name.split(' ').count { it.isNotEmpty() } <= MAX_WORDS &&
            name.any { it.isLetter() }
}
