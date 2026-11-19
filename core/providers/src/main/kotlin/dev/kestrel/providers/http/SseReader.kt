package dev.kestrel.providers.http

import okio.BufferedSource

/**
 * Reads a body of server-sent events (the WHATWG rules, except that a bare CR does not end a line): `data:` lines are
 * joined with a newline, `event:` names the event, a blank line ends it, lines that start with `:` are comments, and
 * `id:` and `retry:` are ignored. An event that the body ends without a blank line is still delivered, because some
 * servers end that way; an adapter decides whether its final event came (design D4).
 */
object SseReader {
    suspend fun read(source: BufferedSource, onEvent: suspend (SseEvent) -> Unit) {
        var event: String? = null
        val data = StringBuilder()
        var hasData = false
        while (true) {
            val line = source.readUtf8Line() ?: break
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
}
