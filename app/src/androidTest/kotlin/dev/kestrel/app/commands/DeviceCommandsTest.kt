package dev.kestrel.app.commands

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * The phone's settings (design D10, prototype P4): Wi-Fi and Bluetooth on the emulator's own Internet panel, Quick
 * Settings and Bluetooth settings, the volumes, mute, silence, the media keys with the test app's player, and
 * magnification. command-test.ps1 puts back the radios, the volumes, the ringer mode, the magnification and the Quick
 * Settings tiles.
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

    private val audio = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AudioManager::class.java)

    @Test
    fun volumeStepsTheMusicStreamOrTheStreamThatTheOwnerNames() {
        // In the middle of each stream, so that a step can go both ways.
        for (stream in listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM)) {
            screen.shell("cmd media_session volume --stream $stream --set ${audio.getStreamMaxVolume(stream) / 2}")
        }
        val music = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val alarm = audio.getStreamVolume(AudioManager.STREAM_ALARM)

        assertEquals(Outcome.DONE, CommandTests.say("volume up").outcome)
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) > music)
        assertEquals(Outcome.DONE, CommandTests.say("turn the volume down").outcome)
        assertEquals(music, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(Outcome.DONE, CommandTests.say("turn the alarm volume down").outcome)
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_ALARM) < alarm)
        assertEquals(music, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun muteAndUnmuteTheMusicButNeverTheAlarm() {
        screen.shell("cmd media_session volume --stream 3 --set ${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2}")

        assertEquals(Outcome.DONE, CommandTests.say("mute").outcome)
        assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        assertEquals(Outcome.DONE, CommandTests.say("unmute").outcome)
        assertTrue(!audio.isStreamMute(AudioManager.STREAM_MUSIC))
        assertEquals(Outcome.FAILED, CommandTests.say("mute the alarm").outcome)
        CommandTests.awaitSaid(PhoneCommandTexts.MUTE_ALARM)
        assertTrue(!audio.isStreamMute(AudioManager.STREAM_ALARM))
    }

    @Test
    fun silencePutsThePhoneOnVibrateAndUnmuteTheRingerEndsIt() {
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)

        assertEquals(Outcome.DONE, CommandTests.say("silence").outcome)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        assertEquals(Outcome.DONE, CommandTests.say("unmute the ringer").outcome)

        assertTrue(VoiceTests.waitUntil(2_000) { audio.ringerMode == AudioManager.RINGER_MODE_NORMAL })
    }

    @Test
    fun mediaPauseAndPlayReachAPlayerInTheBackground() = runBlocking<Unit> {
        try {
            screen.open("MediaActivity", "Media fixture")
            screen.awaitElement { it.label == "Playing" }
            screen.shell("input keyevent KEYCODE_HOME")
            assertTrue(VoiceTests.waitUntil(3_000) { audio.isMusicActive })

            assertEquals(Outcome.DONE, CommandTests.say("media pause").outcome)
            assertTrue(!audio.isMusicActive)
            assertEquals(Outcome.DONE, CommandTests.say("play media").outcome)
            assertTrue(audio.isMusicActive)
            assertEquals(Outcome.DONE, CommandTests.say("pause the music").outcome)
            // Nothing plays now, so a pause reaches no player.
            assertEquals(Outcome.FAILED, CommandTests.say("media pause").outcome)
            awaitBar(PhoneCommandTexts.NO_MEDIA)
        } finally {
            screen.shell("am force-stop $FIXTURES")
        }
    }

    @Test
    fun magnificationZoomsPansWithScrollAndStops() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")

        assertEquals(Outcome.DONE, CommandTests.say("zoom in").outcome)
        assertEquals(2f, screen.session.actions.zoomScale)
        val (x, _) = checkNotNull(screen.session.system.magnifiedCentre())
        assertEquals(Outcome.DONE, CommandTests.say("pan left").outcome)
        val left = checkNotNull(screen.session.system.magnifiedCentre()).first
        assertTrue("The magnified area did not move left", left < x - 100)
        // While the screen is magnified, "scroll" without a target pans.
        assertEquals(Outcome.DONE, CommandTests.say("scroll right").outcome)
        assertTrue("The magnified area did not move right", checkNotNull(screen.session.system.magnifiedCentre()).first > left + 100)
        assertEquals(Outcome.DONE, CommandTests.say("magnify").outcome)
        assertEquals(3f, screen.session.actions.zoomScale)
        assertEquals(Outcome.DONE, CommandTests.say("zoom out").outcome)
        assertEquals(2f, screen.session.actions.zoomScale)
        assertEquals(Outcome.DONE, CommandTests.say("stop magnification").outcome)

        assertEquals(1f, screen.session.actions.zoomScale)
    }

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
