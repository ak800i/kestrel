package dev.kestrel.app.settings

import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.voice.VoiceTests
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/**
 * The setup checklist's reader sees Kestrel's battery use as Restricted (design spec, section 14.6). The test sets
 * Kestrel's app op RUN_ANY_IN_BACKGROUND, which no script puts back if the test's process dies between two commands, so it
 * runs only on an emulator. It needs no screen session, and on a phone it changes nothing before it is skipped.
 */
class BatteryRestrictionTest {
    @get:Rule(order = 0)
    val emulatorOnly = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                VoiceTests.assumeEmulator()
                base.evaluate()
            }
        }
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = SetupStatusReader(context)

    @Test
    fun seesRestrictedBatteryUse() {
        try {
            // What the battery setting "Restricted" sets (prototype P2).
            VoiceTests.shell("cmd appops set ${context.packageName} RUN_ANY_IN_BACKGROUND ignore")
            assertTrue(reader.read().batteryRestricted)

            // The op's normal mode; "default" would still count as restricted (prototype P2).
            VoiceTests.shell("cmd appops set ${context.packageName} RUN_ANY_IN_BACKGROUND allow")
            assertFalse(reader.read().batteryRestricted)
        } finally {
            VoiceTests.shell("cmd appops set ${context.packageName} RUN_ANY_IN_BACKGROUND allow")
        }
    }
}
