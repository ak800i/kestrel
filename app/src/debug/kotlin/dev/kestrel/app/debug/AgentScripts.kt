package dev.kestrel.app.debug

import dev.kestrel.agent.testing.InvalidScript
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.app.debug.TestHooks.HookError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The scripts of the hook agent-script (design D28 of sub-project 5): a JSON array of replies, which the scripted model
 * reads by its own rules. A script that it refuses is a [HookError] that names the error's kind and only what is safe to
 * answer, the reply's number and the keys ([InvalidScript]), never a text of the script; a script that is no JSON array of
 * replies names only that. `:tools:eval` checks every script of a series this way before its first run (plan 7G).
 */
internal object AgentScripts {
    fun model(text: String): ScriptedModel {
        val replies = try {
            Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
        } catch (e: IllegalArgumentException) {
            throw HookError("the script is not a JSON array of replies")
        }
        return try {
            ScriptedModel(replies)
        } catch (e: InvalidScript) {
            throw HookError("the script is refused (InvalidScript): ${e.message}")
        }
    }
}
