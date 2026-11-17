package dev.kestrel.commands.edit

/**
 * Texts with their selection marked, for the tests of the editing model: "|" is the cursor, and "[" and "]" enclose a
 * selection, as in "hello [world]". The tests' texts contain none of these characters.
 */
internal object Marks {
    fun state(marked: String): TextState {
        val text = marked.replace("[", "").replace("]", "").replace("|", "")
        if ('[' in marked) return TextState(text, marked.indexOf('['), marked.indexOf(']') - 1)
        val cursor = marked.indexOf('|')
        require(cursor >= 0) { "The text marks no cursor" }
        return TextState(text, cursor, cursor)
    }

    fun mark(text: String, start: Int, end: Int): String =
        if (start == end) {
            text.substring(0, start) + "|" + text.substring(start)
        } else {
            text.substring(0, start) + "[" + text.substring(start, end) + "]" + text.substring(end)
        }

    /** What [step] makes of [marked]: the new text with its selection marked, or "Fail" with the reason. */
    fun after(step: EditStep, marked: String): String {
        val text = state(marked).text
        return when (step) {
            is EditStep.Change -> mark(step.applyTo(text), step.selectionStart, step.selectionEnd)
            is EditStep.Select -> mark(text, step.start, step.end)
            is EditStep.Fail -> "Fail ${step.reason}"
        }
    }
}
