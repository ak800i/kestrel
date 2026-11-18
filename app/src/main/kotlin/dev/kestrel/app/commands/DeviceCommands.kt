package dev.kestrel.app.commands

import android.bluetooth.BluetoothManager
import android.content.Intent
import android.provider.Settings
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.RadioKind
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's settings (design spec, Appendix A; prototype P4): Wi-Fi through the Internet panel's switch, and Bluetooth
 * through its Quick Settings tile or its settings page.
 */
internal class DeviceCommands(environment: CommandEnvironment) {
    private val bluetooth: BluetoothManager? = environment.context.getSystemService(BluetoothManager::class.java)
    private val resolver = environment.context.contentResolver
    private val targets = environment.targets

    suspend fun radio(screen: ScreenSession, radio: RadioKind, on: Boolean): CommandResult =
        if (radio == RadioKind.WIFI) wifi(screen, on) else bluetooth(screen, on)

    /**
     * Wi-Fi through the switch of Android's Internet panel, which turns the radio off and on; the Quick Settings tile only
     * disconnects (prototype P4). Kestrel reads Wi-Fi's state from Android and opens the panel only when it differs. It
     * acts only in the panel's own window: a window that was not on the screen before, of SystemUI or Settings, that shows
     * the switch; so never on Quick Settings' tile or on a switch of the app in front. It clicks the switch, waits up to
     * 3 s for Android's state to change, and closes the panel with its "Done" button, or Back while the panel still shows,
     * also when the command is cancelled.
     */
    private suspend fun wifi(screen: ScreenSession, on: Boolean): CommandResult {
        if (wifiOn() == on) {
            return CommandResult(Outcome.DONE, show = if (on) PhoneCommandTexts.WIFI_ALREADY_ON else PhoneCommandTexts.WIFI_ALREADY_OFF)
        }
        val before = screen.snapshots.current().snapshot.windows.map { it.id }.toSet()
        // The start is inside the try, so that a cancel during its wait closes the panel too; until the start fails, the
        // panel may still be on its way.
        var opening = true
        try {
            if (screen.system.start(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) is ActionResult.Failed) {
                opening = false
                return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_WIFI_SWITCH)
            }
            val toggle = await(screen) { snapshot, element ->
                element.windowId !in before && element.packageName in PANEL_PACKAGES && element.checkable && element.enabled &&
                    element.viewId?.contains(":id/qs_tile") != true &&
                    (element.viewId?.endsWith(":id/wifi_toggle") == true || namesRadio(snapshot, element, RadioKind.WIFI))
            } ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_WIFI_SWITCH)
            // Checked again right before the click: the panel may have changed since the snapshot.
            val element = targets.still(ScreenTargets.chosen(toggle))
            if (element != null && element.checked != on) screen.actions.tap(element.key)
            val changed = awaitState(on) { wifiOn() }
            return if (changed) {
                CommandResult(Outcome.DONE, repeatable = true)
            } else {
                CommandResult(Outcome.FAILED, show = PhoneCommandTexts.WIFI_NOT_CHANGED)
            }
        } finally {
            withContext(NonCancellable) { closePanel(screen, before, opening) }
        }
    }

    // Closes the panel that the command opened, with its "Done" button, or with Back while its window still shows, so that
    // Back never acts on the owner's app.
    private suspend fun closePanel(screen: ScreenSession, before: Set<Int>, opening: Boolean) {
        val panel = newWindow(screen, before, PANEL_PACKAGES, opening) ?: return
        val snapshot = screen.snapshots.current().snapshot
        val done = snapshot.elements.firstOrNull { element ->
            element.windowId == panel.id && element.enabled && element.clickable &&
                (element.viewId?.endsWith(":id/done_button") == true ||
                    ScreenTargets.names(snapshot, element).any { Normalizer.normalize(it) == "done" })
        }
        if (done == null || screen.actions.tap(done.key) is ActionResult.Failed) screen.actions.global(GlobalAction.BACK)
    }

    /**
     * Bluetooth through its Quick Settings tile, whose switch turns the radio off and on (prototype P4), or, when Quick
     * Settings shows no tile, through the main switch of the Bluetooth settings page that Kestrel opens. Kestrel acts only
     * on SystemUI's tile or on that page's window, never on a switch of the app in front, and the state is Android's, read
     * for up to 3 s. The shade and the page close also when the command is cancelled.
     */
    private suspend fun bluetooth(screen: ScreenSession, on: Boolean): CommandResult {
        if (bluetoothOn() == on) {
            return CommandResult(Outcome.DONE, show = if (on) PhoneCommandTexts.BLUETOOTH_ALREADY_ON else PhoneCommandTexts.BLUETOOTH_ALREADY_OFF)
        }
        try {
            screen.actions.global(GlobalAction.QUICK_SETTINGS)
            val tile = await(screen) { snapshot, element ->
                element.packageName == SYSTEM_UI && element.checkable && element.enabled && namesRadio(snapshot, element, RadioKind.BLUETOOTH)
            }
            if (tile != null) {
                val element = targets.still(ScreenTargets.chosen(tile))
                if (element != null && element.checked != on) screen.actions.tap(element.key)
                return bluetoothResult(awaitState(on) { bluetoothOn() })
            }
        } finally {
            withContext(NonCancellable) { screen.actions.global(GlobalAction.DISMISS_SHADE) }
        }
        return bluetoothPage(screen, on)
    }

    // The main switch of the Bluetooth settings page, in the window of Settings that the command opened.
    private suspend fun bluetoothPage(screen: ScreenSession, on: Boolean): CommandResult {
        val before = screen.snapshots.current().snapshot.windows.map { it.id }.toSet()
        var opening = true
        try {
            if (screen.system.start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) is ActionResult.Failed) {
                opening = false
                return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_BLUETOOTH_SWITCH)
            }
            val main = await(screen) { snapshot, element ->
                element.windowId !in before && element.packageName == SETTINGS && element.checkable && element.enabled &&
                    snapshot.windows.any { it.id == element.windowId && it.kind == WindowKind.APPLICATION } &&
                    namesRadio(snapshot, element, RadioKind.BLUETOOTH)
            } ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_BLUETOOTH_SWITCH)
            val element = targets.still(ScreenTargets.chosen(main))
            if (element != null && element.checked != on) screen.actions.tap(element.key)
            return bluetoothResult(awaitState(on) { bluetoothOn() })
        } finally {
            withContext(NonCancellable) {
                // Back only while the page that the command opened shows.
                if (newWindow(screen, before, setOf(SETTINGS), opening) != null) screen.actions.global(GlobalAction.BACK)
            }
        }
    }

    private fun bluetoothResult(changed: Boolean) = if (changed) {
        CommandResult(Outcome.DONE, repeatable = true)
    } else {
        CommandResult(Outcome.FAILED, show = PhoneCommandTexts.BLUETOOTH_NOT_CHANGED)
    }

    // Android's own states; no permission is needed to read them at Kestrel's target SDK. Wi-Fi's global setting is 1 when
    // Wi-Fi is on, and 2 when it is on while airplane mode is on.
    private fun wifiOn(): Boolean? = try {
        Settings.Global.getInt(resolver, Settings.Global.WIFI_ON).let { it == 1 || it == 2 }
    } catch (e: Settings.SettingNotFoundException) {
        null
    }

    private fun bluetoothOn(): Boolean? = bluetooth?.adapter?.isEnabled

    // Waits up to 3 s for Android's [state] of a radio to become [on].
    private suspend fun awaitState(on: Boolean, state: () -> Boolean?): Boolean =
        withTimeoutOrNull(RADIO_MILLIS) {
            while (state() != on) delay(POLL_MILLIS)
            true
        } ?: false

    // A window of [packages] that was not on the screen [before]; while it may still be [opening], after a cancel in the
    // first moments of its start, Kestrel waits up to 1 s for it.
    private suspend fun newWindow(screen: ScreenSession, before: Set<Int>, packages: Set<String>, opening: Boolean): WindowSummary? {
        suspend fun find() = screen.snapshots.current().snapshot.windows.firstOrNull { it.id !in before && it.packageName in packages }
        if (!opening) return find()
        return withTimeoutOrNull(APPEAR_MILLIS) {
            var window = find()
            while (window == null) {
                delay(POLL_MILLIS)
                window = find()
            }
            window
        }
    }

    // Waits up to 3 s for an element of the current snapshot that [accept] takes.
    private suspend fun await(screen: ScreenSession, accept: (ScreenSnapshot, ScreenElement) -> Boolean): ScreenElement? =
        withTimeoutOrNull(PANEL_MILLIS) {
            var found: ScreenElement? = null
            while (found == null) {
                val snapshot = screen.snapshots.current().snapshot
                found = snapshot.elements.firstOrNull { accept(snapshot, it) }
                if (found == null) delay(POLL_MILLIS)
            }
            found
        }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val SETTINGS = "com.android.settings"
        private const val PANEL_MILLIS = 3_000L
        private const val RADIO_MILLIS = 3_000L
        private const val POLL_MILLIS = 100L
        private const val APPEAR_MILLIS = 1_000L

        // The Internet panel: SystemUI's dialog on the emulator's Android 17 (prototype P4), a panel of Settings elsewhere.
        private val PANEL_PACKAGES = setOf(SYSTEM_UI, SETTINGS)

        /**
         * Whether [element] is named after [radio]: one of its names, those of its nearest labelled ancestor or those of
         * the elements inside it, is the radio's name, alone or after "use", in folded words, before a comma that may add
         * a detail, as the "Wi-Fi,AndroidWifi" inside a Quick Settings tile and "Bluetooth, Pixel Buds" do. So "Wi-Fi
         * calling" is no Wi-Fi switch.
         */
        fun namesRadio(snapshot: ScreenSnapshot, element: ScreenElement, radio: RadioKind): Boolean {
            val index = snapshot.elements.indexOf(element)
            val inside = snapshot.elements.filter { candidate -> index >= 0 && within(snapshot, candidate, index) }
            val names = ScreenTargets.names(snapshot, element) +
                inside.flatMap { listOfNotNull(it.label, it.text.takeUnless { _ -> it.editable }, it.contentDescription) }
            val radioNames = if (radio == RadioKind.WIFI) setOf(listOf("wi", "fi"), listOf("wifi")) else setOf(listOf("bluetooth"), listOf("blue", "tooth"))
            return names.any { name ->
                Normalizer.words(name.substringBefore(',')).let { if (it.firstOrNull() == "use") it.drop(1) else it } in radioNames
            }
        }

        // Whether [candidate] lies inside the element at [index], at most 20 levels down.
        private fun within(snapshot: ScreenSnapshot, candidate: ScreenElement, index: Int): Boolean {
            var parent = candidate.parent
            var depth = 0
            while (parent != null && depth < 20) {
                if (parent == index) return true
                parent = snapshot.elements.getOrNull(parent)?.parent
                depth++
            }
            return false
        }
    }
}
