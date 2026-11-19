package dev.kestrel.voice.sherpa

import dev.kestrel.voice.Keyword
import dev.kestrel.voice.KeywordTuning

/**
 * The keyword spotter's keywords, written in the word pieces of its model, one keyword spelling to a line:
 * `<pieces> :<boost> #<threshold> @<name>` (design spec, section 8.2, B37). With a single spelling, the spotter never heard
 * "Kestrel" in tests, so it knows nine. Every piece must be in the model's tokens.txt: with one unknown piece, sherpa-onnx
 * ends Kestrel's process (`_Exit`) while the spotter loads.
 */
internal object KeywordLines {
    private val KESTREL = listOf(
        "▁K EST RE L", "▁K ES T RE L", "▁K EST R EL", "▁K ES T R EL", "▁K E S T R E L",
        "▁K EST R A L", "▁K ES T R AL", "▁C EST RE L", "▁K EST R OL",
    )
    private const val STOP = "▁ST O P"
    private const val CANCEL = "▁CAN CE L"

    /** The text of the spotter's keywords file, with [tuning]'s boost and threshold on every line. */
    fun text(tuning: KeywordTuning = KeywordTuning()): String = buildString {
        val suffix = ":${tuning.boost} #${tuning.threshold}"
        for (spelling in KESTREL) append("$spelling $suffix @kestrel\n")
        append("$STOP $suffix @stop\n")
        append("$CANCEL $suffix @cancel\n")
    }

    /** The keyword that a line's name stands for; null for an empty or unknown name. */
    fun keyword(name: String): Keyword? = when (name) {
        "kestrel" -> Keyword.KESTREL
        "stop" -> Keyword.STOP
        "cancel" -> Keyword.CANCEL
        else -> null
    }
}
