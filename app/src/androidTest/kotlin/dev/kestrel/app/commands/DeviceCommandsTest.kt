package dev.kestrel.app.commands

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.RadioKind
import dev.kestrel.screen.act.GlobalAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wi-Fi and Bluetooth (design D10, prototype P4), on the emulator's own Internet panel, Quick Settings and Bluetooth
 * settings. command-test.ps1 puts back the radios, the volumes, the ringer mode and the Quick Settings tiles.
 */
@RunWith(AndroidJUnit4::class)
class DeviceCommandsTest {
    // Before ScreenServiceRule, as in plan 3A's classes: a run without kestrelCommands=true skips each test before the rule
    // switches the accessibility services, and before @Before and @After run.
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    @Before
    fun start() {
        CommandTests.start()
        screen.shell("input keyevent KEYCODE_HOME")
    }

    @After
    fun stop() {
        CommandTests.stop()
        screen.shell("input keyevent KEYCODE_HOME")
    }

    @Test
    fun wifiTurnsOffAndOnThroughTheInternetPanel() = runBlocking<Unit> {
        try {
            if (!radio("wifi_on")) assertEquals(Outcome.DONE, CommandTests.say("turn on Wi-Fi").outcome)

            assertEquals(Outcome.DONE, CommandTests.say("turn off Wi-Fi").outcome)
            assertTrue("Wi-Fi is still on", VoiceTests.waitUntil(5_000) { !radio("wifi_on") })
            awaitGone { it.viewId?.endsWith(":id/wifi_toggle") == true }
            assertEquals(Outcome.DONE, CommandTests.say("turn Wi-Fi off").outcome)
            awaitBar(PhoneCommandTexts.WIFI_ALREADY_OFF)
            assertEquals(Outcome.DONE, CommandTests.say("turn on Wi-Fi").outcome)

            assertTrue("Wi-Fi is still off", VoiceTests.waitUntil(5_000) { radio("wifi_on") })
        } finally {
            screen.shell("cmd wifi set-wifi-enabled enabled")
        }
    }

    @Test
    fun bluetoothTurnsOffAndOnThroughItsQuickSettingsTile() = runBlocking<Unit> {
        try {
            if (!radio("bluetooth_on")) screen.shell("cmd bluetooth_manager enable")
            assertTrue(VoiceTests.waitUntil(5_000) { radio("bluetooth_on") })
            // So that the test fails, instead of passing through the settings page, when Quick Settings has no tile.
            awaitBluetoothTile(shown = true)

            assertEquals(Outcome.DONE, CommandTests.say("turn off Bluetooth").outcome)
            assertTrue("Bluetooth is still on", VoiceTests.waitUntil(5_000) { !radio("bluetooth_on") })
            awaitGone { it.packageName == PhoneTests.SYSTEM_UI && it.viewId?.contains(":id/qs_tile") == true }
            assertEquals(Outcome.DONE, CommandTests.say("switch Bluetooth on").outcome)

            assertTrue("Bluetooth is still off", VoiceTests.waitUntil(5_000) { radio("bluetooth_on") })
        } finally {
            screen.shell("cmd bluetooth_manager enable")
        }
    }

    /**
     * Without a Quick Settings tile, Kestrel opens Android's Bluetooth settings. On the emulator's Android 17, they open
     * as "Connected devices", which has no Bluetooth switch, and the page with the switch needs BLUETOOTH_CONNECT, which
     * Kestrel does not ask for. So the command fails and says so, Bluetooth stays on, and the page closes again. The
     * fixture is in front before the command, so that a Back too many would show: it would close the fixture.
     */
    @Test
    fun bluetoothWithoutItsTileFailsWhenTheSettingsPageHasNoSwitchAndClosesThePage() = runBlocking<Unit> {
        val tiles = screen.shell("settings get secure sysui_qs_tiles").trim()
        assertTrue("Quick Settings has no Bluetooth tile to remove", "bt" in tiles.split(','))
        try {
            if (!radio("bluetooth_on")) screen.shell("cmd bluetooth_manager enable")
            assertTrue(VoiceTests.waitUntil(5_000) { radio("bluetooth_on") })
            // Through SystemUI, because on Android 17 SystemUI keeps the list itself and writes it back a moment after
            // a 'settings put'.
            screen.shell("cmd statusbar set-tiles ${tiles.split(',').filter { it != "bt" }.joinToString(",")}")
            // SystemUI applies the new list a moment later; until then the command would still find the tile.
            awaitBluetoothTile(shown = false)
            screen.open("CommandsActivity", "Commands fixture")
            assertTrue("The fixture is not in front", PhoneTests.awaitActiveApp(screen.session, FIXTURES))

            assertEquals(Outcome.FAILED, CommandTests.say("turn off Bluetooth").outcome)

            awaitBar(PhoneCommandTexts.NO_BLUETOOTH_SWITCH)
            assertTrue("Bluetooth turned off", radio("bluetooth_on"))
            awaitGone { it.packageName == PhoneTests.SETTINGS }
            assertTrue("The fixture is not in front again", PhoneTests.awaitActiveApp(screen.session, FIXTURES))
        } finally {
            screen.shell("cmd statusbar set-tiles $tiles")
            screen.shell("cmd bluetooth_manager enable")
        }
    }

    // Task 4 of plan 3C adds its tests above this line.

    // Opens Quick Settings, waits until it shows Bluetooth's tile or until it shows none, and closes it again.
    private suspend fun awaitBluetoothTile(shown: Boolean) {
        screen.session.actions.global(GlobalAction.QUICK_SETTINGS)
        try {
            screen.awaitElement { it.packageName == PhoneTests.SYSTEM_UI && it.viewId?.contains(":id/qs_tile") == true }
            withTimeout(5_000) {
                while (true) {
                    val snapshot = screen.session.snapshots.current().snapshot
                    val tile = snapshot.elements.any {
                        it.packageName == PhoneTests.SYSTEM_UI && it.checkable && DeviceCommands.namesRadio(snapshot, it, RadioKind.BLUETOOTH)
                    }
                    if (tile == shown) break
                    delay(100)
                }
            }
        } finally {
            screen.session.actions.global(GlobalAction.DISMISS_SHADE)
        }
    }

    // Whether the radio is on, from Android's global setting wifi_on or bluetooth_on, which is 0 when it is off.
    private fun radio(setting: String): Boolean = screen.shell("settings get global $setting").trim() != "0"

    private suspend fun awaitBar(text: String) = withTimeout(5_000) { while (screen.session.feedbackText != text) delay(50) }

    // Waits until no element on the screen matches [predicate], such as the closed panel's switch.
    private suspend fun awaitGone(predicate: (dev.kestrel.screen.model.ScreenElement) -> Boolean) = withTimeout(5_000) {
        while (screen.session.snapshots.current().snapshot.elements.any(predicate)) delay(100)
    }
}
