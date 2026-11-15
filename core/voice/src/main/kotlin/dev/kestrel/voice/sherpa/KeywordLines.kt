package dev.kestrel.voice.sherpa

import dev.kestrel.voice.Keyword

/**
 * The keyword spotter's keywords, written in the word pieces of its model, one keyword spelling to a line:
 * `<pieces> :<boost> #<threshold> @<name>` (design spec, section 8.2, B37). With a single spelling, the spotter never heard
 * "Kestrel" in tests, so it knows nine. Every piece must be in the model's tokens.txt: one unknown piece makes the whole
 * spotter fail to load.
 */
internal object KeywordLines {
    private val KESTREL = listOf(
        "▁K EST RE L", "▁K ES T RE L", "▁K EST R EL", "▁K ES T R EL", "▁K E S T R E L",
        "▁K EST R A L", "▁K ES T R AL", "▁C EST RE L", "▁K EST R OL",
    )
    private const val STOP = "▁ST O P"
    private const val CANCEL = "▁CAN CE L"
    private const val TUNING = ":2.0 #0.15"

    /** The text of the spotter's keywords file. */
    fun text(): String = buildString {
        for (spelling in KESTREL) append("$spelling $TUNING @kestrel\n")
        append("$STOP $TUNING @stop\n")
        append("$CANCEL $TUNING @cancel\n")
    }

    /** The keyword that a line's name stands for; null for an empty or unknown name. */
    fun keyword(name: String): Keyword? = when (name) {
        "kestrel" -> Keyword.KESTREL
        "stop" -> Keyword.STOP
        "cancel" -> Keyword.CANCEL
        else -> null
    }
}
