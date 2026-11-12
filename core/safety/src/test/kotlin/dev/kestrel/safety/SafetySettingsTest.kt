package dev.kestrel.safety

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SafetySettingsTest {
    @Test
    fun settingsSavedWithoutTestModeKeepTestModeOn() {
        assertEquals(SafetySettings(testMode = true), Json.decodeFromString(SafetySettings.serializer(), "{}"))
    }

    @Test
    fun testModeOffSurvivesSaving() {
        val json = Json.encodeToString(SafetySettings.serializer(), SafetySettings(testMode = false))

        assertEquals(SafetySettings(testMode = false), Json.decodeFromString(SafetySettings.serializer(), json))
    }
}
