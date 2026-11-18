package dev.kestrel.app.commands

import dev.kestrel.commands.RadioKind
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import org.junit.Assert.assertFalse
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

    // Task 4 of plan 3C adds its tests above this line.
}
