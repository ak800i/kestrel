package dev.kestrel.agent.tools

import dev.kestrel.agent.memory.RouteNote
import dev.kestrel.agent.prompt.SystemPrompt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design D10 of sub-project 5: the tools, their order and their schemas, fixed by a golden file (r51). */
class ToolsTest {
    @Test
    fun theToolsAreTheDesignsInTheirOrder() {
        assertEquals(
            listOf(
                "tap", "long_press", "type_text", "scroll", "swipe", "back", "home", "open_app", "open_url", "open_deep_link", "open_settings", "wait",
                "look_closer", "find_contact", "find_media", "share_media", "list_events", "create_event", "set_alarm", "set_timer",
                "list_notifications", "list_sms", "compose_sms", "list_calls", "get_location", "media_control", "navigate", "ask_user", "say",
                "finish", "fail", "remember", "forget", "set_slider", "add_contact", "delete_contact", "update_checklist",
            ),
            Tools.ALL.map { it.name },
        )
        assertEquals("set_timer", Tools.spec(Tools.SET_TIMER)!!.name)
        assertNull(Tools.spec("make_routine"))
    }

    @Test
    fun onlyTheToolsThatActOnAnElementDeclareACategoryAndEveryScreenToolAReason() {
        val screenTools = Tools.ALL.map { it.name }.takeWhile { it != Tools.FIND_CONTACT }
        for (tool in Tools.ALL) {
            val properties = tool.parameters["properties"] as JsonObject
            val required = (tool.parameters["required"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
            assertEquals(tool.name, tool.name in Tools.WITH_CATEGORY, "category" in properties)
            assertEquals(tool.name, tool.name in Tools.WITH_CATEGORY, "category" in required)
            // set_slider acts on an element but comes last, because a new tool goes at the end of the list (spec 11.4).
            assertEquals(tool.name, tool.name in screenTools || tool.name == Tools.FAIL || tool.name == Tools.SET_SLIDER, "reason" in required)
        }
    }

    @Test
    fun theFinishNotesShowARouteNoteThatKestrelKeepsAndThePromptGivesTheSame() {
        val properties = Tools.spec(Tools.FINISH)!!.parameters["properties"] as JsonObject
        val notes = ((properties["notes"] as JsonObject)["description"] as JsonPrimitive).content
        val example = notes.substringAfter("such as: ").substringBefore(". ")

        assertNotNull(example, RouteNote.parse(example))
        assertTrue(SystemPrompt.TEXT.contains(example))
    }

    @Test
    fun theToolsJsonMatchesItsGoldenFile() {
        val json = Tools.ALL.joinToString("\n") { tool ->
            buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parameters)
            }.toString()
        }
        val golden = javaClass.getResourceAsStream("/golden/tools.jsonl")!!.bufferedReader().use { it.readText() }.trimEnd('\n')

        assertEquals(golden, json)
    }
}
