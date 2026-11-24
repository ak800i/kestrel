package dev.kestrel.agent.observe

import dev.kestrel.agent.port.Expect
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScrollAxis
import dev.kestrel.screen.model.Signature

/**
 * What [ObservationBuilder] needs for one observation. [last] is the text of the "Last:" line without "Last: ", built
 * from texts that are already redacted; [previous] is the observation before, for the stars; [lastTargetKey] the key
 * that the last action targeted, which the cap never drops; [ocr] the OCR lines of `look_closer` (design D20).
 */
data class ObservationInput(
    val step: Int,
    val snapshot: ScreenSnapshot,
    val appLabel: String?,
    val last: String? = null,
    val previous: Observation? = null,
    val lastTargetKey: String? = null,
    val ocr: List<OcrLine> = emptyList(),
) {
    override fun toString(): String =
        "ObservationInput(step=$step, elements=${snapshot.elements.size}, last=${last?.length ?: 0} chars, previous=${previous?.step}, ocr=${ocr.size})"
}

/**
 * One numbered line of an observation (design D8): what the tools need to act on it. [label] is the raw label of the
 * snapshot, which never goes into a request; [shownLabel] is the label as the observation showed it (redacted, quoted,
 * escaped and cut), which the result lines and summaries use, and `(no label)` without one. An OCR line has no [key].
 * [redacted] is true when the redactor changed any text of the line, so that a screenshot blacks it out (design D20;
 * review 3 M6).
 */
data class TableEntry(
    val number: Int,
    val key: String?,
    val label: String?,
    val shownLabel: String,
    val className: String?,
    val packageName: String?,
    val windowId: Int,
    val bounds: IntRect,
    val editable: Boolean,
    val password: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val scrollable: Boolean,
    val scrollAxis: ScrollAxis?,
    val ocr: Boolean,
    val redacted: Boolean = false,
    /** Whether the element is a slider, which set_slider may move, and its value in percent (design D19 of sub-project 6). */
    val slider: Boolean = false,
    val sliderPercent: Int? = null,
) {
    /** What the element must still look like when the agent acts on it (design D32 item 27). */
    val expect: Expect get() = Expect(label, className, packageName, editable, password, checkable)

    override fun toString(): String =
        "TableEntry(number=$number, label=${label?.length ?: 0} chars, class=${className?.substringAfterLast('.')}, ocr=$ocr)"
}

/**
 * The numbers of one observation; they are valid only while it is the newest. [redactedBounds] holds the bounds of every
 * element and OCR line whose text the redactor changed, also of those that the cap left out, so that a screenshot's
 * masks cover every one of them (design D20; review 3-2 N1, review 4-2 I4). [frontWindowId] is the ID of the window that
 * the observation treats as in front, such as a dialog or a picker, or null when it shows none.
 */
class ObservationTable(
    val entries: List<TableEntry>,
    val redactedBounds: List<IntRect> = entries.filter { it.redacted }.map { it.bounds },
    val frontWindowId: Int? = null,
) {
    private val byNumber = entries.associateBy { it.number }
    private val byKeys = entries.filter { it.key != null }.associateBy { it.key!! }

    val size: Int get() = entries.size

    fun entry(number: Int): TableEntry? = byNumber[number]

    fun byKey(key: String): TableEntry? = byKeys[key]

    override fun toString(): String = "ObservationTable(size=$size)"
}

/**
 * An observation (design D8): its [text] as the model reads it, its numbers, and what the loop needs from it. [title]
 * is the screen's title, already redacted, without quotes; [keys] are the keys of the elements that it showed, for the
 * next observation's stars; [notifications] the number of hidden notifications (design D9); [shownAppLabel] is the
 * launcher label as the header showed it, redacted.
 */
class Observation(
    val step: Int,
    val text: String,
    val table: ObservationTable,
    val packageName: String?,
    val appLabel: String?,
    val title: String?,
    val signature: Signature,
    val keys: Set<String>,
    val notifications: Int,
    val shownAppLabel: String? = appLabel,
) {
    /**
     * The text once a newer observation exists (design D3): the first line starts "Earlier screen (step N); its numbers
     * are no longer valid.", and the numbers at the start of each line, in "Focus:" and in the "Last:" line become `(n)`,
     * while a `[n]` inside quoted screen text stays.
     */
    fun earlier(): String {
        val lines = text.split('\n')
        return lines.mapIndexed { index, line ->
            when {
                index == 0 -> relabel(line.replaceFirst(Regex("^Observation \\d+"), "Earlier screen (step $step); its numbers are no longer valid."))
                line.startsWith("Last: ") -> relabel(line)
                else -> LINE_NUMBER.replace(line) { "(${it.groupValues[1]})${it.groupValues[2]}" }
            }
        }.joinToString("\n")
    }

    /** The one-line form of a task's first observation (design D3). */
    fun firstScreen(): String {
        val app = (shownAppLabel ?: packageName)?.let { ScreenText.escape(ScreenText.cut(it, ScreenText.LABEL_LIMIT)) } ?: "an unknown app"
        return if (title == null) "First screen: $app." else "First screen: $app, ${ScreenText.quote(title, ScreenText.LABEL_LIMIT)}."
    }

    override fun toString(): String = "Observation(step=$step, length=${text.length}, entries=${table.size}, notifications=$notifications)"

    private companion object {
        val LINE_NUMBER = Regex("^\\[(\\d+)](\\*?)")

        /**
         * `[n]` → `(n)` outside the quotes of [line]. An escaped `\"` neither starts nor ends a quote; the app's label in
         * the header is escaped without quotes.
         */
        fun relabel(line: String): String {
            val out = StringBuilder(line.length)
            var quoted = false
            var index = 0
            while (index < line.length) {
                val char = line[index]
                when {
                    char == '\\' && index + 1 < line.length -> {
                        out.append(char).append(line[index + 1])
                        index += 2
                        continue
                    }
                    char == '"' -> quoted = !quoted
                    !quoted && char == '[' -> {
                        val match = Regex("\\[(\\d+)]").matchAt(line, index)
                        if (match != null) {
                            out.append('(').append(match.groupValues[1]).append(')')
                            index += match.value.length
                            continue
                        }
                    }
                }
                out.append(char)
                index++
            }
            return out.toString()
        }
    }
}
