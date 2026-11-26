package dev.kestrel.app.conversation

import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.app.R
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer

/**
 * A button of Kestrel's card (design 10.3 of sub-project 7): the answer that a tap on it gives ("Reply…" gives none, because
 * it opens the field for the owner's words), and the string of its word ([label]; null for a pick, whose button says its
 * choice).
 */
internal sealed interface CardChoice {
    val answer: ChannelAnswer?
    val label: Int?

    data object Yes : CardChoice {
        override val answer: ChannelAnswer = ChannelAnswer.Yes
        override val label: Int = R.string.card_yes
    }

    data object No : CardChoice {
        override val answer: ChannelAnswer = ChannelAnswer.No
        override val label: Int = R.string.card_no
    }

    data object Confirm : CardChoice {
        override val answer: ChannelAnswer = ChannelAnswer.Yes
        override val label: Int = R.string.card_confirm
    }

    data object Cancel : CardChoice {
        override val answer: ChannelAnswer = ChannelAnswer.No
        override val label: Int = R.string.card_cancel
    }

    data object Continue : CardChoice {
        override val answer: ChannelAnswer = ChannelAnswer.Yes
        override val label: Int = R.string.card_continue
    }

    data object Stop : CardChoice {
        override val answer: ChannelAnswer = ChannelAnswer.No
        override val label: Int = R.string.card_stop
    }

    data object Reply : CardChoice {
        override val answer: ChannelAnswer? = null
        override val label: Int = R.string.card_reply
    }

    /** A pick's choice number [number], from 1, or the number that the owner entered on the card's keypad. */
    data class Pick(val number: Int) : CardChoice {
        override val answer: ChannelAnswer get() = ChannelAnswer.Pick(number)
        override val label: Int? get() = null
    }
}

/**
 * A card that Kestrel shows the owner (design 10.3 of sub-project 7): [key] names it, the conversation's question number or,
 * for a hand-over, a negative number of the text channel's; [text] says it; [choices] are its buttons, in order; [picks]
 * are a pick's choices, which its Pick buttons say; [question] is the question's number and [kind] its kind, both null for
 * a hand-over; [numberPad] puts the keypad on a pick that carries no choices, which gives a number from 1 to [highest]
 * (0: any number). toString prints no words.
 */
internal data class OwnerCard(
    val key: Int,
    val text: String,
    val choices: List<CardChoice>,
    val picks: List<String> = emptyList(),
    val question: Int? = null,
    val kind: QuestionKind? = null,
    val numberPad: Boolean = false,
    val highest: Int = 0,
) {
    init {
        require(choices.isNotEmpty()) { "A card has at least one button" }
    }

    /** Whether [choice] answers this card: one of its buttons, or a number of its keypad's range (review N1 of plan 7F). */
    fun takes(choice: CardChoice): Boolean =
        choice in choices || (numberPad && choice is CardChoice.Pick && choice.number >= 1 && (highest == 0 || choice.number <= highest))

    /**
     * Whether the conversation screen's copy of the card answers it (plan 7F). A confirmation acts on the app at once, so
     * only the card over that app answers it (design 12.2 and 12.3); a hand-over is the owner's step in the app; and a pick
     * names a number on the app's screen, which Kestrel's screen would cover.
     */
    val answersOnScreen: Boolean get() = kind != null && kind != QuestionKind.CONFIRM && kind != QuestionKind.PICK

    override fun toString(): String =
        "OwnerCard(key=$key, text=${text.length} chars, choices=$choices, picks=${picks.size}, question=$question, kind=$kind, numberPad=$numberPad, highest=$highest)"

    companion object {
        /**
         * [question]'s card with its kind's buttons (design 10.3): "Reply…" when [reply] and the kind takes the owner's
         * words, never for a confirmation, which is always a button. The text channel's cards have "Reply…"; the voice
         * channel's (a confirmation's and the screenshot question's) do not. A pick shows its choices, at most
         * `OwnerPort.MAX_CHOICES`, or a keypad when it has none, and Cancel; its number is entered on the card, never on
         * Kestrel's screen, which would cover the app's numbers. A button that resumes a goal comes last, as on the goal
         * notification (plan 7E).
         */
        fun of(question: ChannelQuestion, reply: Boolean): OwnerCard {
            val words = listOfNotNull(CardChoice.Reply.takeIf { reply })
            val picks = question.choices.take(OwnerPort.MAX_CHOICES).takeIf { question.kind == QuestionKind.PICK }.orEmpty()
            val choices = when (question.kind) {
                QuestionKind.YES_NO, QuestionKind.SCREENSHOT -> listOf(CardChoice.Yes, CardChoice.No) + words
                QuestionKind.CONFIRM -> listOf(CardChoice.Confirm, CardChoice.Cancel)
                QuestionKind.PICK -> picks.indices.map { CardChoice.Pick(it + 1) } + CardChoice.Cancel
                QuestionKind.FREE -> words + CardChoice.Cancel
                QuestionKind.CONTINUE_GOAL, QuestionKind.LIMIT -> listOf(CardChoice.Stop, CardChoice.Continue)
                QuestionKind.SWITCH, QuestionKind.DELETE_HISTORY -> listOf(CardChoice.Yes, CardChoice.No)
            }
            val numberPad = question.kind == QuestionKind.PICK && picks.isEmpty()
            return OwnerCard(question.number, question.text, choices, picks, question.number, question.kind, numberPad, question.highest.takeIf { numberPad } ?: 0)
        }

        /** A hand-over's card (design 10.3): its sentence, Cancel and Continue. */
        fun handOver(key: Int, text: String): OwnerCard = OwnerCard(key, text, listOf(CardChoice.Cancel, CardChoice.Continue))
    }
}
