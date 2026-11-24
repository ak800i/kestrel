package dev.kestrel.conversation.model

/**
 * The owner's answer to a question (design 5.3 of sub-project 7), as a channel's card or its reading of his words gave it.
 * [Text.leftScreen] is true when he typed it on Kestrel's own screen, which leaves before the goal looks again (design
 * 10.3). [Pick.number] is the number that he said or the choice that he tapped, from 1. toString prints no words.
 */
sealed interface ChannelAnswer {
    data object Yes : ChannelAnswer

    data object No : ChannelAnswer

    data class Text(val text: String, val leftScreen: Boolean) : ChannelAnswer {
        override fun toString(): String = "Text(${text.length} chars, leftScreen=$leftScreen)"
    }

    data class Pick(val number: Int) : ChannelAnswer

    data object Silence : ChannelAnswer

    /** A pause or a touch withdrew the question. */
    data object Interrupted : ChannelAnswer
}
