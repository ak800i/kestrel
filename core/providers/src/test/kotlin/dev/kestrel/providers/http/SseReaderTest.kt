package dev.kestrel.providers.http

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SseReaderTest {
    private fun read(text: String): List<SseEvent> = runBlocking {
        val events = mutableListOf<SseEvent>()
        SseReader.read(Buffer().writeUtf8(text)) { events += it }
        events
    }

    @Test
    fun joinsDataLinesAndNamesEvents() {
        assertEquals(
            listOf(SseEvent("message_start", "{\"a\":1}\n{\"b\":2}"), SseEvent(null, "[DONE]")),
            read("event: message_start\ndata: {\"a\":1}\ndata: {\"b\":2}\n\ndata: [DONE]\n\n"),
        )
    }

    @Test
    fun skipsCommentsIdsRetriesAndEventsWithoutData() {
        assertEquals(
            listOf(SseEvent(null, "x")),
            read(": keep-alive\n\nid: 7\nretry: 1000\nevent: ping\n\ndata:x\n\n"),
        )
    }

    @Test
    fun readsCrLfLinesAndAnEventThatTheBodyEndsWithoutABlankLine() {
        assertEquals(listOf(SseEvent("a", "1"), SseEvent(null, "2")), read("event: a\r\ndata: 1\r\n\r\ndata: 2"))
    }

    @Test
    fun keepsAllButOneLeadingSpaceOfAValue() {
        assertEquals(listOf(SseEvent(null, "  two spaces")), read("data:   two spaces\n\n"))
    }

    @Test
    fun dropsOneLeadingByteOrderMark() {
        assertEquals(listOf(SseEvent("a", "1"), SseEvent(null, "\uFEFF2")), read("\uFEFFevent: a\ndata: 1\n\ndata: \uFEFF2\n\n"))
    }

    @Test
    fun aLineLongerThanTheLimitIsMalformed() {
        val longest = "data: " + "x".repeat(SseReader.MAX_LINE_BYTES.toInt() - 6)

        assertEquals(SseReader.MAX_LINE_BYTES.toInt() - 6, read("$longest\n\n").single().data.length)
        val error = assertThrows(ProviderException::class.java) { read("${longest}x\n\n") }
        assertEquals(ProviderError.Malformed, error.error)
    }
}
