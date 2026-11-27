package dev.kestrel.app.debug

import dev.kestrel.app.debug.TestHooks.HookError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The hook agent-script's reading of a script (plan 7G): the scripted model's own rules, and an error that names its kind
 * and the reply's number and keys, never a text of the script.
 */
class AgentScriptsTest {
    @Test
    fun aScriptIsReadByTheScriptedModelsRulesAndItsErrorsCarryNoText() {
        assertEquals(2, AgentScripts.model("""[{"tool": "wait"}, {"tool": "finish", "requires": ["secret words"]}]""").left)

        assertEquals(
            "the script is refused (InvalidScript): Reply 2 of the script has a \"requires\" that is not a list of texts.",
            assertThrows(HookError::class.java) { AgentScripts.model("""[{"tool": "wait"}, {"tool": "wait", "requires": "secret words"}]""") }.message,
        )
        assertEquals(
            "the script is refused (InvalidScript): Reply 1 of the script has keys that the scripted model does not know: require.",
            assertThrows(HookError::class.java) { AgentScripts.model("""[{"tool": "wait", "require": ["secret words"]}]""") }.message,
        )
        // A JSON error would quote the script, so its answer names only the form that it lacks.
        for (text in listOf("""[{"tool": "wait", "text": "secret words"""", """{"replies": [{"text": "secret words"}]}""", """["secret words"]""")) {
            assertEquals("the script is not a JSON array of replies", assertThrows(HookError::class.java) { AgentScripts.model(text) }.message)
        }
    }
}
