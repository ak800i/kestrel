package dev.kestrel.safety

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun theModeAndTheOwnersNamesHaveDefaultsAndSurviveSaving() {
        val defaults = Json.decodeFromString(SafetySettings.serializer(), """{"testMode": true}""")
        assertEquals(Mode.STANDARD, defaults.mode)
        assertEquals(emptyList<String>(), defaults.ownerEmails)
        assertEquals("My Notes", defaults.viberSelfChatName)

        val owner = SafetySettings(mode = Mode.RELAXED, ownerEmails = listOf("owner@example.com"), whatsappOwnName = "Aleksa")
        assertEquals(owner, Json.decodeFromString(SafetySettings.serializer(), Json.encodeToString(SafetySettings.serializer(), owner)))
    }

    @Test
    fun theTextFormHoldsNoAddressOrName() {
        val printed = SafetySettings(ownerEmails = listOf("owner@example.com"), viberSelfChatName = "Secret notes", whatsappOwnName = "Aleksa").toString()

        assertFalse(printed, printed.contains("owner@") || printed.contains("Secret") || printed.contains("Aleksa"))
    }
}
