package dev.kestrel.perception

/**
 * A recogniser's characters (design D20 of sub-project 5; r48): class 0 is CTC's blank, classes 1 to N are the
 * dictionary's lines in file order, and class N + 1 is a space. Lines may repeat (the Latin dictionary has 836 lines, of
 * which 772 differ), so decoding goes by index and never by character.
 */
class Dictionary(private val lines: List<String>) {
    /** How many classes the recogniser's output must have. */
    val classes: Int get() = lines.size + 2

    /** The text of [index], an index of the recogniser's output; the blank is empty. */
    fun label(index: Int): String = when (index) {
        0 -> ""
        lines.size + 1 -> " "
        else -> lines[index - 1]
    }

    /** Fails when a recogniser with [modelClasses] classes does not belong to this dictionary. */
    fun check(modelClasses: Long) {
        if (modelClasses != classes.toLong()) {
            throw IllegalStateException("The recogniser has $modelClasses classes, but its dictionary needs $classes")
        }
    }

    override fun toString(): String = "Dictionary(${lines.size} lines)"

    companion object {
        /** The dictionary of a file or of a model's `character` metadata: one character per line, LF line ends. */
        fun parse(text: String): Dictionary = Dictionary(text.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it })
    }
}
