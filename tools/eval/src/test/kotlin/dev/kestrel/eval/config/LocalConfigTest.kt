package dev.kestrel.eval.config

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** The local configuration keeps four keys and never the PIN, and the console never sees a value (design D2 of sub-project 6). */
class LocalConfigTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun readsTheFourKeysAndNeverThePin() {
        val file = folder.newFile("a71-config.json").toPath()
        Files.writeString(
            file,
            """{"gmailAddress": "pera.test@example.com", "emulatorGmailAddress": "pera.second@example.com", "testLocation": "44.8100,20.4600",
               "homePlace": "Kalemegdan", "pin": "123456", "callNumber": "+381110000000", "whatsappOwnName": 7}""",
        )

        val config = LocalConfig.read(file)

        assertEquals("pera.test@example.com", config.address(DeviceKind.A71))
        assertEquals("pera.second@example.com", config.address(DeviceKind.EMULATOR))
        assertEquals("44.8100,20.4600", config.testLocation)
        assertEquals("Kalemegdan", config.homePlace)
        assertFalse(config.allValues.contains("123456"))
        assertEquals("LocalConfig(keys=[emulatorGmailAddress, gmailAddress, homePlace, testLocation])", config.toString())
    }

    @Test
    fun aMalformedFileIsARefusalThatHoldsNoneOfItsContent() {
        val file = folder.newFile("a71-config.json").toPath()
        Files.writeString(file, """{"gmailAddress": "pera.test@example.com", "homePlace": "Kalemegdan",}""")

        val refusal = assertThrows(Refusal::class.java) { LocalConfig.read(file) }

        assertEquals("corpus\\owner\\a71-config.json is not valid JSON (no comments or trailing commas).", refusal.message)
        assertNull(refusal.cause)
    }

    @Test
    fun aMissingFileHasNoValues() {
        val config = LocalConfig.read(folder.root.toPath().resolve("missing.json"))

        assertNull(config.gmailAddress)
        assertNull(config.address(DeviceKind.EMULATOR))
    }

    @Test
    fun theConsoleSeesNoAddressAndNoValue() {
        val redactions = Redactions(LocalConfig.of(mapOf("testLocation" to "44.8100,20.4600", "homePlace" to "Kalemegdan")))

        assertEquals(
            "Could not run 'am start -d 'mailto:<address>' -p com.google.android.gm' at <value> near <value>",
            redactions.forConsole("Could not run 'am start -d 'mailto:pera.test@example.com?subject=Kestrel%20test' -p com.google.android.gm' at 44.8100,20.4600 near kalemegdan\nsecond line"),
        )
        assertEquals("sent to <address>", redactions.forConsole("sent to pera.test@example.com"))
    }
}
