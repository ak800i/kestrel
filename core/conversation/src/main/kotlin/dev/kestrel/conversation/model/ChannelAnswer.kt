package dev.kestrel.conversation.model

/**
 * The owner's answer to a question (design 5.3 of sub-project 7), as a channel's card or its reading of his words gave it.
 * Whether he gave it on Kestrel's own screen is not the answer's but its arrival's: a typed input's, whatever the answer's
 * kind (design 10.3). [Pick.number] is the number that he said or the choice that he tapped, from 1. toString prints no
 * words.
 */
sealed interface ChannelAnswer {
    data object Yes : ChannelAnswer

    data object No : ChannelAnswer

    data class Text(val text: String) : ChannelAnswer {
        override fun toString(): String = "Text(${text.length} chars)"
    }

    data class Pick(val number: Int) : ChannelAnswer

    data object Silence : ChannelAnswer

    /** A pause or a touch withdrew the question. */
    data object Interrupted : ChannelAnswer
}
