package dev.kestrel.providers.http

import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun anErrorAboutTheToolChoiceIsFlaggedWithoutKeepingItsWords() {
        fun names(body: String) = ErrorCodes.namesToolChoice(HttpReply(400, headersOf(), body.encodeToByteArray()))

        assertTrue(names("""{"error": {"message": "Invalid value.", "param": "tool_choice", "code": null}}"""))
        assertTrue(names("""{"error": {"message": "Tool choice 'required' is not supported with thinking."}}"""))
        assertTrue(names("""{"message": "Malformed input request: toolChoice is not supported for this model"}"""))
        assertTrue(names("""{"error": {"status": "INVALID_ARGUMENT", "message": "function_calling_config.mode ANY is not supported"}}"""))
        assertTrue(names("""{"type": "error", "error": {"type": "invalid_request_error", "message": "disable_parallel_tool_use is not allowed"}}"""))
        assertFalse(names("""{"error": {"message": "The model does not exist.", "param": "model"}}"""))
        assertFalse(names("<html>tool_choice</html>"))
    }

    @Test
    fun aMessageNeverPassesAsACode() {
        assertEquals("fallback", code("""{"error": {"code": "Rate limit exceeded, try later", "type": "fallback"}}"""))
        assertNull(code("""{"detail": "Usage limit reached"}"""))
        assertNull(code("<html>Bad gateway</html>"))
        assertNull(code("""{"error": {"code": "${"x".repeat(65)}"}}"""))
    }
}
