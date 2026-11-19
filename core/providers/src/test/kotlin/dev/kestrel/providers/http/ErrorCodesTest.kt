package dev.kestrel.providers.http

import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorCodesTest {
    private fun code(body: String, vararg headers: String) = ErrorCodes.from(HttpReply(400, headersOf(*headers), body.encodeToByteArray()))

    @Test
    fun readsTheCodesOfEachStyleInOrder() {
        assertEquals("rate_limit_exceeded", code("""{"error": {"code": "rate_limit_exceeded", "type": "requests"}}"""))
        assertEquals("insufficient_quota", code("""{"error": {"code": null, "type": "insufficient_quota"}}"""))
        assertEquals("overloaded_error", code("""{"type": "error", "error": {"type": "overloaded_error", "message": "Busy"}}"""))
        assertEquals("invalid_grant", code("""{"error": "invalid_grant", "error_description": "Bad code"}"""))
        assertEquals("RESOURCE_EXHAUSTED", code("""{"error": {"code": 429, "message": "Quota", "status": "RESOURCE_EXHAUSTED"}}"""))
        assertEquals("ValidationException", code("""{"__type": "com.amazon.coral.validate#ValidationException", "message": "Bad"}"""))
    }

    @Test
    fun awssHeaderWinsAndLosesItsNamespace() {
        assertEquals(
            "ExpiredTokenException",
            code("""{"error": {"code": "other"}}""", "x-amzn-ErrorType", "ExpiredTokenException:http://internal.amazon.com/coral/com.amazon.coral.service/"),
        )
    }

    @Test
    fun aMessageNeverPassesAsACode() {
        assertEquals("fallback", code("""{"error": {"code": "Rate limit exceeded, try later", "type": "fallback"}}"""))
        assertNull(code("""{"detail": "Usage limit reached"}"""))
        assertNull(code("<html>Bad gateway</html>"))
        assertNull(code("""{"error": {"code": "${"x".repeat(65)}"}}"""))
    }
}
