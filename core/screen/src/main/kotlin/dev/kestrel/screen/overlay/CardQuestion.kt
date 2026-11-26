package dev.kestrel.screen.overlay

/**
 * A question on Kestrel's card (design 10.3 of sub-project 7): [key] names it, [text] is what the card says, [buttons] are
 * its buttons' labels, in order, and [pad], when it is set, puts a number keypad above them. toString prints no words.
 */
data class CardQuestion(val key: Int, val text: String, val buttons: List<String>, val pad: NumberPad? = null) {
    init {
        require(buttons.isNotEmpty()) { "A card has at least one button" }
    }

    override fun toString(): String = "CardQuestion(key=$key, text=${text.length} chars, buttons=${buttons.size}, pad=${pad != null})"
}

/** The question card's window title in Android's window list (plan 7F). */
const val CARD_TITLE = "Kestrel question"

/**
 * How long a new question card ignores taps, and how long after a card closed the next one may appear (design D32 item 21
 * of sub-project 5); the conversation screen's copy of a card waits as long (plan 7F).
 */
const val CARD_ARMING_MILLIS = 600L

/**
 * A card's keypad: the labels of the key that deletes the last digit, of the key that gives the number, and of the key that
 * moves the card to the other end of the screen, off the numbers that it covers; and the [highest] number that it gives,
 * from 1 (0: any number).
 */
data class NumberPad(val delete: String, val enter: String, val move: String, val highest: Int = 0) {
    /** Whether the keypad gives [value]. */
    fun takes(value: Int): Boolean = value >= 1 && (highest == 0 || value <= highest)
}

/**
 * How a card's question ended for its caller. An answer carries [Answered.stamp], what the caller's stamp gave on the
 * main thread at the owner's tap, of the caller's own type [S], so that the caller can judge the tap by what the main
 * thread knew at that moment (plan 7F: the screen's lock at the tap).
 */
sealed interface CardEnd<out S> {
    /** An answer of the owner's, with its [stamp]. */
    sealed interface Answered<out S> : CardEnd<S> {
        val stamp: S
    }

    /** The owner tapped the question's button number [button], from 0. */
    data class Tapped<out S>(val button: Int, override val stamp: S) : Answered<S>

    /** The owner entered [value] on the card's keypad. */
    data class Number<out S>(val value: Int, override val stamp: S) : Answered<S>

    /** The card gave no answer: a different question replaced it, the session closed or dismissed it, or no card could show. */
    data object Withdrawn : CardEnd<Nothing>
}
