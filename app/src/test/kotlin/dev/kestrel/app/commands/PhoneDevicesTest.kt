package dev.kestrel.app.commands

import android.media.AudioManager
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Direction
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.RadioKind
import dev.kestrel.commands.StreamKind
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDevicesTest {
    private val panel = WindowSummary(1, WindowKind.SYSTEM, packageName = "com.android.systemui", bounds = IntRect(0, 0, 1080, 2400))
    private val bounds = IntRect(0, 0, 100, 100)

    private fun element(key: String, parent: Int? = null, label: String? = null, description: String? = null, checkable: Boolean = false) =
        ScreenElement(
            key = key, windowId = 1, parent = parent, label = label, contentDescription = description, bounds = bounds,
            checkable = checkable, clickable = checkable,
        )

    @Test
    fun aRadioSwitchIsNamedByItselfItsRowOrWhatIsInsideIt() {
        val elements = listOf(
            element("tile", checkable = true),
            element("tile-name", parent = 0, description = "Wi-Fi,AndroidWifi"),
            element("bt-row", label = "Use Bluetooth"),
            element("bt-switch", parent = 2, checkable = true),
            element("panel", description = "Wi\u2011Fi", checkable = true),
            element("flashlight", checkable = true, label = "Flashlight"),
            element("wifi-calling", checkable = true, label = "Wi-Fi calling preferences"),
            element("bt-tile", checkable = true),
            element("bt-tile-name", parent = 7, description = "Bluetooth, Pixel Buds"),
        )
        val snapshot = ScreenSnapshot(0, listOf(panel), elements)
        fun names(key: String, radio: RadioKind) = DeviceCommands.namesRadio(snapshot, snapshot.element(key)!!, radio)

        assertTrue(names("tile", RadioKind.WIFI))
        assertTrue(names("bt-switch", RadioKind.BLUETOOTH))
        assertTrue(names("panel", RadioKind.WIFI))
        assertFalse(names("tile", RadioKind.BLUETOOTH))
        assertFalse(names("flashlight", RadioKind.WIFI))
        assertTrue(names("bt-tile", RadioKind.BLUETOOTH))
        assertFalse(names("wifi-calling", RadioKind.WIFI))
    }

    @Test
    fun aVolumeWithoutAStreamIsTheCallsDuringACallAndTheMusicsOtherwise() {
        assertEquals(AudioManager.STREAM_MUSIC, DeviceCommands.streamOf(null, AudioManager.MODE_NORMAL))
        assertEquals(AudioManager.STREAM_VOICE_CALL, DeviceCommands.streamOf(null, AudioManager.MODE_IN_CALL))
        assertEquals(AudioManager.STREAM_VOICE_CALL, DeviceCommands.streamOf(null, AudioManager.MODE_IN_COMMUNICATION))
        assertEquals(AudioManager.STREAM_RING, DeviceCommands.streamOf(StreamKind.RING, AudioManager.MODE_IN_CALL))
        assertEquals(AudioManager.STREAM_ALARM, DeviceCommands.streamOf(StreamKind.ALARM, AudioManager.MODE_NORMAL))
    }

    @Test
    fun aVolumeStepIsReadBackAndAnUnchangedOneSaysWhy() {
        assertEquals(CommandResult(Outcome.DONE, repeatable = true), DeviceCommands.volumeResult(3, 7, 1, 7, up = true))
        assertEquals(CommandResult(Outcome.DONE, show = PhoneCommandTexts.HIGHEST), DeviceCommands.volumeResult(15, 15, 0, 15, up = true))
        assertEquals(CommandResult(Outcome.DONE, show = PhoneCommandTexts.LOWEST), DeviceCommands.volumeResult(1, 1, 1, 7, up = false))
        assertEquals(CommandResult(Outcome.FAILED, show = PhoneCommandTexts.VOLUME_NOT_CHANGED), DeviceCommands.volumeResult(5, 5, 0, 15, up = true))
    }

    @Test
    fun theAlarmAndTheCallVolumeAreNeverMuted() {
        assertEquals(PhoneCommandTexts.MUTE_ALARM, DeviceCommands.muteRefusal(StreamKind.ALARM))
        assertEquals(PhoneCommandTexts.MUTE_CALL, DeviceCommands.muteRefusal(StreamKind.CALL))
        assertNull(DeviceCommands.muteRefusal(StreamKind.RING))
        assertNull(DeviceCommands.muteRefusal(null))
    }

    @Test
    fun panMovesAThirdOfTheVisibleArea() {
        assertEquals(-180 to 0, DeviceCommands.panBy(Direction.LEFT, 1080, 2400, 2f))
        assertEquals(0 to 400, DeviceCommands.panBy(Direction.DOWN, 1080, 2400, 2f))
        assertEquals(0 to -267, DeviceCommands.panBy(Direction.UP, 1080, 2400, 3f))
    }
}
