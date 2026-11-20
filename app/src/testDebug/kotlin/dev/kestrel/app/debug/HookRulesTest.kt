package dev.kestrel.app.debug

import dev.kestrel.app.listen.ProblemNotifications
import dev.kestrel.app.listen.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of plan 4D's hooks that need no device. */
class HookRulesTest {
    @Test
    fun yesCallIsRefusedOnAPhoneAndSentOnAnEmulator() {
        for (text in listOf("Yes, call.", "yes call", "Yes, call, Kestrel.")) {
            assertTrue(text, VoiceHooks.refused(text, "qcom"))
            assertFalse(text, VoiceHooks.refused(text, "ranchu"))
            assertFalse(text, VoiceHooks.refused(text, "goldfish"))
        }
        assertFalse(VoiceHooks.refused("Yes.", "qcom"))
        assertFalse(VoiceHooks.refused("Call 1234.", "qcom"))
    }

    @Test
    fun theNoticesKindsComeFromTheirIdsAndTheStopNoticesReason() {
        assertEquals("none", SessionHooks.noticeKinds(emptyList()))
        assertEquals(
            "accessibility,battery",
            SessionHooks.noticeKinds(
                listOf(ProblemNotifications.STOPPED_ID to StopReason.BATTERY_RESTRICTED.text, ProblemNotifications.ACCESSIBILITY_ID to "x"),
            ),
        )
        assertEquals(
            "settings,stopped",
            SessionHooks.noticeKinds(listOf(ProblemNotifications.SETTINGS_ID to null, ProblemNotifications.STOPPED_ID to "Other")),
        )
    }

    @Test
    fun aTasksOptionsAreTheGivenExtrasAndAreReadOnce() {
        assertEquals(emptyMap<String, String>(), VoiceHooks.taskOptions(model = null, live = null))
        assertEquals(mapOf("model" to "test-model", "live" to "false"), VoiceHooks.taskOptions(model = "test-model", live = false))

        val store = TaskOptions()
        store.put(3, mapOf("live" to "true"))
        assertEquals(mapOf("live" to "true"), store.take(3))
        assertEquals(emptyMap<String, String>(), store.take(3))
        // It keeps only the newest tasks' options.
        for (task in 1..TaskOptions.MAX_TASKS + 1) store.put(task, mapOf("model" to "m$task"))
        assertEquals(emptyMap<String, String>(), store.take(1))
        assertEquals(mapOf("model" to "m2"), store.take(2))
    }
}
