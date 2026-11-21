package dev.kestrel.agent

import dev.kestrel.agent.loop.Redacted
import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.agent.port.DataResult
import dev.kestrel.safety.Redactor

/**
 * The text of a data tool's result (design D3, D12 and D17; review A3-I1). `:core:datatools` returns each item as one
 * [dev.kestrel.agent.port.DataLine] whose field values are unquoted and unescaped; here each value is redacted with the
 * other values of its line as its neighbours (an SMS's sender and body, a notification's title and messages), and only
 * then quoted and escaped like screen text, so that a line break inside an SMS is still a line break when the redactor
 * reads it. The line's fixed part and the note are Kestrel's own words.
 */
object DataResultText {
    const val NO_ITEMS = "No items."

    fun of(result: DataResult.Ok, redactor: Redactor): Redacted {
        val lines = result.lines.map { line ->
            val fields = line.fields.mapIndexed { index, field ->
                val neighbours = line.fields.filterIndexed { other, _ -> other != index }.map { it.value }
                val value = redactor.redact(field.value, neighbours)
                "${field.name}: ${ScreenText.quote(value, ScreenText.VALUE_LIMIT)}"
            }
            (listOf(line.fixed).filter { it.isNotBlank() } + fields).joinToString(" · ")
        }
        val body = lines.ifEmpty { listOf(NO_ITEMS) } + listOfNotNull(result.note)
        return Redacted.kestrel(body.joinToString("\n"))
    }
}
