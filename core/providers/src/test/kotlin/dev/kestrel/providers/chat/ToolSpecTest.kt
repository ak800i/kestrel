package dev.kestrel.providers.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolSpecTest {
    private fun schema(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun theSubsetIsAccepted() {
        val tool = ToolSpec(
            name = "tap",
            description = "Taps an element.",
            parameters = schema(
                """
                {"type": "object", "description": "The element.",
                 "properties": {
                   "label": {"type": "string", "description": "Its label."},
                   "kind": {"type": "string", "enum": ["button", "link"]},
                   "index": {"type": "integer"}, "weight": {"type": "number"}, "long": {"type": "boolean"},
                   "path": {"type": "array", "items": {"type": "object", "properties": {"x": {"type": "number"}}, "required": ["x"]}}
                 },
                 "required": ["label"]}
                """,
            ),
        )

        assertEquals("tap", tool.name)
    }

    @Test
    fun aTopLevelObjectMayHaveNoProperties() {
        ToolSpec("go_home", "Goes to the home screen.", schema("""{"type": "object"}"""))
    }

    @Test
    fun everythingOutsideTheSubsetIsRefused() {
        val refused = listOf(
            """{"type": "string"}""",
            """{"type": "object", "additionalProperties": false}""",
            """{"type": "object", "properties": {"n": {"type": "integer", "enum": [1, 2]}}}""",
            """{"type": "object", "properties": {"inner": {"type": "object"}}}""",
            """{"type": "object", "properties": {"list": {"type": "array"}}}""",
            """{"type": "object", "properties": {"x": {"type": ["string", "null"]}}}""",
            """{"type": "object", "properties": {"x": {"type": "string"}}, "required": ["y"]}""",
            """{"type": "object", "properties": {"x": {"type": "string", "format": "date"}}}""",
            """{"type": "object", "properties": {"x": {"type": "string", "items": {"type": "string"}}}}""",
        )

        for (text in refused) {
            assertThrows(text, IllegalArgumentException::class.java) { ToolSpec("t", "d", schema(text)) }
        }
        assertThrows(IllegalArgumentException::class.java) { ToolSpec("two words", "d", schema("""{"type": "object"}""")) }
    }

    @Test
    fun aToolPrintsItsNameAndTheSizesOnly() {
        val tool = ToolSpec("tap", "secret description", schema("""{"type": "object", "properties": {"secret": {"type": "string"}}}"""))

        val printed = tool.toString()

        assertFalse(printed, printed.contains("secret"))
        assertEquals("ToolSpec(name=tap, descriptionLength=18, schemaLength=59)", printed)
    }
}
