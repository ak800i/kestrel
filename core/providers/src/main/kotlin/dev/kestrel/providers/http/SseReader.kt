package dev.kestrel.providers.http

import okio.BufferedSource

/**
 * Reads a body of server-sent events (the WHATWG rules, except that a bare CR does not end a line): one leading byte order
 * mark is dropped, `data:` lines are joined with a newline, `event:` names the event, a blank line ends it, lines that
 * start with `:` are comments, and `id:` and `retry:` are ignored. An event that the body ends without a blank line is
 * still delivered, because some servers end that way; an adapter decides whether its final event came (design D4). A line
 * longer than [MAX_LINE_BYTES] throws ProviderException with Malformed, and the rest of the body is not read.
 */
object SseReader {
    /** The longest line that the reader keeps in memory, 1 MiB. */
    internal const val MAX_LINE_BYTES = 1024L * 1024

    suspend fun read(source: BufferedSource, onEvent: suspend (SseEvent) -> Unit) {
        var event: String? = null
        val data = StringBuilder()
        var hasData = false
        var first = true
        while (true) {
            var line = readLine(source) ?: break
            if (first) {
                line = line.removePrefix("\uFEFF")
                first = false
            }
            if (line.isEmpty()) {
                if (hasData) onEvent(SseEvent(event, data.toString()))
                event = null
                data.setLength(0)
                hasData = false
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
            when (field) {
                "event" -> event = value
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
            }
        }
        if (hasData) onEvent(SseEvent(event, data.toString()))
    }

    /** As BufferedSource.readUtf8Line, but a line of more than [MAX_LINE_BYTES] is Malformed. */
    private fun readLine(source: BufferedSource): String? {
        val newline = source.indexOf('\n'.code.toByte(), 0, MAX_LINE_BYTES + 1)
        if (newline >= 0) {
            val line = source.readUtf8(newline)
            source.skip(1)
            return line.removeSuffix("\r")
        }
        if (source.request(MAX_LINE_BYTES + 1)) throw ProviderException(ProviderError.Malformed)
        return if (source.exhausted()) null else source.readUtf8()
    }
}
