package dev.kestrel.app.commands

import android.media.AudioManager
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Direction
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.RadioKind
import dev.kestrel.commands.StreamKind
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import dev.kestrel.screen.read.Labels
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDevicesTest {
    private val panel = WindowSummary(1, WindowKind.SYSTEM, packageName = "com.android.systemui", bounds = IntRect(0, 0, 1080, 2400))
    private val bounds = IntRect(0, 0, 100, 100)

    private fun element(
        key: String,
        parent: Int? = null,
        label: String? = null,
        description: String? = null,
        checkable: Boolean = false,
        windowId: Int = 1,
        viewId: String? = null,
    ) = ScreenElement(
        key = key, windowId = windowId, parent = parent, label = label, contentDescription = description, bounds = bounds,
        checkable = checkable, clickable = checkable, viewId = viewId, suggestedName = if (label == null) Labels.suggestedName(viewId) else null,
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
    fun oneUisBluetoothTileIsItsInnerButtonNeverItsOuterLayoutOrItsLabelGroup() {
        val ui = "com.android.systemui"
        val click = setOf(ElementAction.CLICK, ElementAction.LONG_CLICK)
        val focus = setOf(ElementAction.FOCUS, ElementAction.CLICK, ElementAction.LONG_CLICK)
        fun node(key: String, parent: Int?, className: String, actions: Set<ElementAction>, label: String? = null, description: String? = null) =
            ScreenElement(
                key = key, windowId = 1, parent = parent, packageName = ui, className = className, label = label ?: description,
                contentDescription = description, bounds = bounds, clickable = true, actions = actions,
            )
        // One UI 5.1's Quick Settings on the A71 (inv-oneui-a71, 1), with Kestrel's own tile, the only checkable one.
        val elements = listOf(
            node("kestrel", null, "android.widget.Switch", click, label = "Kestrel,On,Button").copy(checkable = true),
            node("root", null, "android.widget.LinearLayout", click, label = "Bluetooth,Off,Button Bluetooth").copy(stateDescription = "Off"),
            node("frame", 1, "android.widget.FrameLayout", click, label = "Bluetooth,Off,Button"),
            node("icon", 2, "android.view.ViewGroup", focus, description = "Bluetooth,Off,Button"),
            node("labels", 1, "android.widget.LinearLayout", focus, label = "Bluetooth").copy(viewId = "$ui:id/label_group"),
            ScreenElement(
                key = "label", windowId = 1, parent = 4, packageName = ui, className = "android.widget.TextView", text = "Bluetooth",
                label = "Bluetooth", viewId = "$ui:id/tile_label", bounds = bounds,
            ),
        )
        val oneUi = ScreenSnapshot(0, listOf(panel), elements)

        assertEquals(listOf("icon"), elements.filter { DeviceCommands.bluetoothTile(oneUi, it) }.map { it.key })
        // AOSP's checkable tile is still taken.
        val tile = ScreenElement(key = "aosp", windowId = 1, packageName = ui, className = "android.widget.Switch", bounds = bounds, clickable = true, checkable = true)
        val name = ScreenElement(key = "aosp-name", windowId = 1, parent = 0, packageName = ui, contentDescription = "Bluetooth", label = "Bluetooth", bounds = bounds)
        assertTrue(DeviceCommands.bluetoothTile(ScreenSnapshot(0, listOf(panel), listOf(tile, name)), tile))
    }

    @Test
    fun aVolumeChangeGoesWithoutThePanelsFlagAndOnlyTheSecondCallShowsThePanel() {
        assertEquals(
            listOf(AudioManager.ADJUST_RAISE to 0, AudioManager.ADJUST_SAME to AudioManager.FLAG_SHOW_UI),
            DeviceCommands.adjustments(AudioManager.ADJUST_RAISE),
        )
        assertEquals(
            listOf(AudioManager.ADJUST_UNMUTE to 0, AudioManager.ADJUST_SAME to AudioManager.FLAG_SHOW_UI),
            DeviceCommands.adjustments(AudioManager.ADJUST_UNMUTE),
        )
    }

    @Test
    fun theSettingsPagesOneMainSwitchNamedOnlyByItsViewIdIsTheSwitch() {
        val page = WindowSummary(
            2, WindowKind.APPLICATION, packageName = "com.android.settings", title = "Bluetooth", bounds = IntRect(0, 0, 1080, 2400),
        )
        // As on AOSP's Bluetooth page: the switch bar's text, and inside the bar a switch without a name of its own.
        val bar = element("bar", windowId = 2, label = "On", viewId = "com.android.settings:id/switch_bar")
        val widget = element("widget", parent = 0, checkable = true, windowId = 2, viewId = "android:id/switch_widget")
        val snapshot = ScreenSnapshot(0, listOf(page), listOf(bar, widget))
        fun main(snapshot: ScreenSnapshot, windowId: Int) = DeviceCommands.mainSwitch(snapshot, windowId, RadioKind.BLUETOOTH)

        assertFalse(DeviceCommands.namesRadio(snapshot, widget, RadioKind.BLUETOOTH))
        assertEquals(widget, main(snapshot, 2))
        // Not in another window, not with a second such switch, not outside a switch bar, and not with a name of its own.
        assertNull(main(snapshot, 1))
        val second = element("second", parent = 0, checkable = true, windowId = 2, viewId = "android:id/switch_widget")
        assertNull(main(ScreenSnapshot(0, listOf(page), listOf(bar, widget, second)), 2))
        val row = element("row", windowId = 2, label = "Notifications", viewId = "com.android.settings:id/row")
        val loose = element("loose", parent = 0, checkable = true, windowId = 2, viewId = "android:id/switch_widget")
        assertNull(main(ScreenSnapshot(0, listOf(page), listOf(row, loose)), 2))
        val named = element("named", parent = 0, label = "Airplane mode", checkable = true, windowId = 2, viewId = "android:id/switch_widget")
        assertNull(main(ScreenSnapshot(0, listOf(page), listOf(bar, named)), 2))
    }

    @Test
    fun theMainSwitchCountsOnlyOnAPageWhoseTitleOrSwitchBarNamesTheRadio() {
        fun page(title: String?) =
            WindowSummary(2, WindowKind.APPLICATION, packageName = "com.android.settings", title = title, bounds = IntRect(0, 0, 1080, 2400))
        fun main(window: WindowSummary, vararg elements: ScreenElement) =
            DeviceCommands.mainSwitch(ScreenSnapshot(0, listOf(window), elements.toList()), 2, RadioKind.BLUETOOTH)
        fun bar(text: String) = element("bar", windowId = 2, label = text, viewId = "com.android.settings:id/switch_bar")
        val widget = element("widget", parent = 0, checkable = true, windowId = 2, viewId = "android:id/switch_widget")

        // An old Settings task that comes to the front with NFC's page on top: its switch is never taken.
        assertNull(main(page("NFC"), bar("Use NFC"), widget))
        assertNull(main(page(null), bar("Use NFC"), widget))
        assertNull(main(page("Wi-Fi"), bar("On"), widget))
        assertNull(main(page(null), bar("Use Wi-Fi"), widget))
        // The Bluetooth page, by the window's title or by the switch bar's text.
        assertEquals(widget, main(page("Bluetooth"), bar("On"), widget))
        assertEquals(widget, main(page(null), bar("Use Bluetooth"), widget))
        // By the toolbar's title, a text inside the page's action bar.
        val toolbar = element("toolbar", windowId = 2, viewId = "com.android.settings:id/action_bar")
        val title = element("title", parent = 2, label = "Bluetooth", windowId = 2)
        val inBar = element("widget", parent = 0, checkable = true, windowId = 2, viewId = "android:id/switch_widget")
        assertEquals(inBar, main(page(null), bar("Off"), inBar, toolbar, title))
        assertNull(main(page(null), bar("Off"), inBar, toolbar, title.copy(label = "NFC")))
    }

    @Test
    fun theCommandsWindowIsTheSwitchsOnceItWasFound() {
        val volume = WindowSummary(3, WindowKind.SYSTEM, packageName = "com.android.systemui", bounds = bounds)

        // The owner closed the panel and the volume dialog showed: no window of the command is left to close.
        assertNull(DeviceCommands.ownWindow(listOf(volume), setOf(5), setOf("com.android.systemui"), switchWindow = 1))
        assertEquals(panel, DeviceCommands.ownWindow(listOf(volume, panel), setOf(5), setOf("com.android.systemui"), switchWindow = 1))
        // Before a switch was found, a new window of the panel's packages.
        assertEquals(volume, DeviceCommands.ownWindow(listOf(volume), setOf(5), setOf("com.android.systemui"), switchWindow = null))
        assertNull(DeviceCommands.ownWindow(listOf(volume), setOf(3), setOf("com.android.systemui"), switchWindow = null))
    }

    @Test
    fun backClosesOnlyNewSettingsPagesInFront() {
        fun app(id: Int, packageName: String, active: Boolean = true) =
            WindowSummary(id, WindowKind.APPLICATION, packageName = packageName, bounds = bounds, active = active)
        val shade = WindowSummary(9, WindowKind.SYSTEM, packageName = "com.android.systemui", bounds = bounds, active = true)
        val before = setOf(1, 2)

        assertEquals(app(5, "com.android.settings"), DeviceCommands.settingsPageInFront(listOf(app(5, "com.android.settings")), before))
        // While a system window such as the shade is active, no app window is in front, also not Settings' below it.
        val behindTheShade = app(6, "com.android.settings", active = false)
        assertNull(DeviceCommands.settingsPageInFront(listOf(shade, behindTheShade), before))
        // The app that was in front before is in front again, or it was Settings itself.
        assertNull(DeviceCommands.settingsPageInFront(listOf(app(1, "dev.kestrel.testapp")), before))
        assertNull(DeviceCommands.settingsPageInFront(listOf(app(2, "com.android.settings")), before))
        assertNull(DeviceCommands.settingsPageInFront(listOf(app(7, "com.android.launcher3")), before))
        assertNull(DeviceCommands.settingsPageInFront(listOf(shade), before))
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
    fun mediaPlayReactedOnlyWhenAPlayerStartedAfterTheKey() {
        // Nothing played, and music plays after the key.
        assertTrue(DeviceCommands.playTookKey(wasPlaying = false, playing = true, before = emptyList(), after = listOf("player")))
        // Another app was already playing, and no player started: no player took the key.
        assertFalse(DeviceCommands.playTookKey(wasPlaying = true, playing = true, before = listOf("video"), after = listOf("video")))
        // Another app was already playing, and a second player started after the key.
        assertTrue(DeviceCommands.playTookKey(wasPlaying = true, playing = true, before = listOf("video"), after = listOf("video", "player")))
        assertFalse(DeviceCommands.playTookKey(wasPlaying = false, playing = false, before = emptyList<String>(), after = emptyList()))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun leavingThePageEndsAsSoonAsTheAppFromBeforeIsInFront() = runTest {
        fun app(id: Int, packageName: String) = WindowSummary(id, WindowKind.APPLICATION, packageName = packageName, bounds = bounds, active = true)
        val before = setOf(1)
        var windows = listOf(app(5, "com.android.settings"))
        var backs = 0

        DeviceCommands.backUntilLeft({ windows }, before, awaited = true) {
            backs++
            windows = listOf(app(1, "dev.kestrel.testapp"))
        }

        assertEquals(1, backs)
        // No second wait for a page after the last Back.
        assertTrue("Took ${testScheduler.currentTime} ms", testScheduler.currentTime < 500)

        // A Settings task in the background: its own page comes to the front after the first Back, and a second Back closes it.
        windows = listOf(app(5, "com.android.settings"))
        backs = 0
        DeviceCommands.backUntilLeft({ windows }, before, awaited = true) {
            backs++
            windows = if (backs == 1) listOf(app(6, "com.android.settings")) else listOf(app(1, "dev.kestrel.testapp"))
        }
        assertEquals(2, backs)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun backWaitsUpToEightSecondsForThePageAndPressesNothingWhileItIsNotTheActiveWindow() = runTest {
        fun app(id: Int, packageName: String, active: Boolean) =
            WindowSummary(id, WindowKind.APPLICATION, packageName = packageName, bounds = bounds, active = active)
        val before = setOf(1)
        // Settings is still starting: its window lies on top, and no app window is active yet, so a Back would go to the
        // owner's app, which still has the focus.
        val starting = listOf(app(5, "com.android.settings", active = false), app(1, "dev.kestrel.testapp", active = false))
        var backs = 0
        // A cancel that came before the command's own wait for the page ended.
        val waited = testScheduler.currentTime
        DeviceCommands.backUntilLeft({ starting }, before, awaited = false) { backs++ }
        assertEquals(0, backs)
        assertEquals(8_000L, testScheduler.currentTime - waited)

        // A cold start: the page comes to the front after 5 s, and one Back brings the owner's app back.
        val shown = listOf(app(5, "com.android.settings", active = true), app(1, "dev.kestrel.testapp", active = false))
        val left = listOf(app(1, "dev.kestrel.testapp", active = true))
        var closed = false
        val cold = testScheduler.currentTime
        DeviceCommands.backUntilLeft({
            when {
                closed -> left
                testScheduler.currentTime - cold >= 5_000 -> shown
                else -> starting
            }
        }, before, awaited = false) {
            backs++
            closed = true
        }
        assertEquals(1, backs)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun afterAPageThatNeverShowedInTheCommandsOwnWaitBackWaitsOnlyOneSecondMore() = runTest {
        fun app(id: Int, packageName: String, active: Boolean) =
            WindowSummary(id, WindowKind.APPLICATION, packageName = packageName, bounds = bounds, active = active)
        // The command already waited 8 s for the page, and it never came to the front.
        val starting = listOf(app(5, "com.android.settings", active = false), app(1, "dev.kestrel.testapp", active = false))
        var backs = 0
        val waited = testScheduler.currentTime

        DeviceCommands.backUntilLeft({ starting }, setOf(1), awaited = true) { backs++ }

        assertEquals(0, backs)
        assertEquals(1_000L, testScheduler.currentTime - waited)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun thePageCountsOnceSettingsShowsItWithinEightSeconds() = runTest {
        val page = WindowSummary(5, WindowKind.APPLICATION, packageName = "com.android.settings", bounds = bounds, active = true)
        val title = element("title", windowId = 5, label = "Connected devices")
        val before = setOf(1)
        // A window of Settings that is still starting has no element with a name yet.
        assertNull(DeviceCommands.shownPage(ScreenSnapshot(0, listOf(page), listOf(element("icon", windowId = 5))), before))
        assertEquals(page, DeviceCommands.shownPage(ScreenSnapshot(0, listOf(page), listOf(title)), before))

        // A cold start of Settings: the page shows after 5 s.
        val started = testScheduler.currentTime
        val shown = DeviceCommands.awaitPage({
            if (testScheduler.currentTime - started < 5_000) ScreenSnapshot(0, listOf(page), emptyList()) else ScreenSnapshot(0, listOf(page), listOf(title))
        }, before)
        assertEquals(page, shown)

        // A page that never shows ends the wait after 8 s.
        val waited = testScheduler.currentTime
        assertNull(DeviceCommands.awaitPage({ ScreenSnapshot(0, listOf(page), emptyList()) }, before))
        assertEquals(8_000L, testScheduler.currentTime - waited)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theBluetoothTileGetsThreeSecondsOnceQuickSettingsShowsTilesAndEightWhileItShowsNone() = runTest {
        val systemUi = "com.android.systemui"
        val wifi = element("wifi-tile", checkable = true, description = "Internet").copy(packageName = systemUi)
        val bluetooth = element("bt-tile", checkable = true, description = "Bluetooth").copy(packageName = systemUi)
        fun isBluetooth(snapshot: ScreenSnapshot, element: ScreenElement) = snapshot.elements.isNotEmpty() && element.key == "bt-tile"

        // On a loaded kestrel-api37, Quick Settings showed its tiles only after more than 3 s; here after 4 s.
        var started = testScheduler.currentTime
        val late = DeviceCommands.awaitTile({
            val tiles = if (testScheduler.currentTime - started < 4_000) emptyList() else listOf(wifi, bluetooth)
            ScreenSnapshot(0, listOf(panel), tiles)
        }, ::isBluetooth)
        assertEquals(bluetooth, late)

        // Tiles without Bluetooth's end the wait 3 s after they show, so that the settings page comes soon.
        started = testScheduler.currentTime
        assertNull(DeviceCommands.awaitTile({ ScreenSnapshot(0, listOf(panel), listOf(wifi)) }, ::isBluetooth))
        assertEquals(3_000L, testScheduler.currentTime - started)

        // The 3 s count from the first tile, not from the start: tiles without Bluetooth's that show after 4 s end the
        // wait at 7 s.
        started = testScheduler.currentTime
        val lateWithout = DeviceCommands.awaitTile({
            val tiles = if (testScheduler.currentTime - started < 4_000) emptyList() else listOf(wifi)
            ScreenSnapshot(0, listOf(panel), tiles)
        }, ::isBluetooth)
        assertNull(lateWithout)
        assertEquals(7_000L, testScheduler.currentTime - started)

        // No tile at all ends the wait after 8 s.
        started = testScheduler.currentTime
        assertNull(DeviceCommands.awaitTile({ ScreenSnapshot(0, listOf(panel), emptyList()) }, ::isBluetooth))
        assertEquals(8_000L, testScheduler.currentTime - started)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aRadioCountsAsChangedWhenAndroidReportsItsStateWithinSixSeconds() = runTest {
        // Bluetooth needed 3.7 s to turn on when its stack restarted on a loaded kestrel-api37; here the radio needs 5 s.
        val started = testScheduler.currentTime
        assertTrue(DeviceCommands.awaitState(on = true) { testScheduler.currentTime - started >= 5_000 })

        // A radio that never changes ends the wait after 6 s.
        val waited = testScheduler.currentTime
        assertFalse(DeviceCommands.awaitState(on = true) { false })
        assertEquals(6_000L, testScheduler.currentTime - waited)
    }

    @Test
    fun panMovesAThirdOfTheVisibleArea() {
        assertEquals(-180 to 0, DeviceCommands.panBy(Direction.LEFT, 1080, 2400, 2f))
        assertEquals(0 to 400, DeviceCommands.panBy(Direction.DOWN, 1080, 2400, 2f))
        assertEquals(0 to -267, DeviceCommands.panBy(Direction.UP, 1080, 2400, 3f))
    }
}
