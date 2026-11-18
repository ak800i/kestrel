package dev.kestrel.app.commands

import android.bluetooth.BluetoothManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.provider.Settings
import android.view.KeyEvent
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Direction
import dev.kestrel.commands.MagnifyAction
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.commands.RadioKind
import dev.kestrel.commands.StreamKind
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * The phone's settings, media and magnification (design spec, Appendix A; prototype P4): Wi-Fi through the Internet
 * panel's switch, Bluetooth through its Quick Settings tile or its settings page, volumes through Android's audio API, the
 * media keys, and Android's magnification.
 */
internal class DeviceCommands(environment: CommandEnvironment) {
    private val audio: AudioManager? = environment.context.getSystemService(AudioManager::class.java)
    // Built at the first Bluetooth command, on the command's thread, because building it makes binder calls.
    private val bluetooth: BluetoothManager? by lazy { environment.context.getSystemService(BluetoothManager::class.java) }
    private val resolver = environment.context.contentResolver
    private val targets = environment.targets

    /** The commands that work without Kestrel's accessibility service: volume, mute, silence and the media keys. */
    suspend fun withoutScreen(command: Command): CommandResult? = when (command) {
        is PhoneCommand.Volume -> volume(command.stream, command.up)
        is PhoneCommand.Mute -> mute(command.stream, command.on)
        PhoneCommand.Silence -> silence()
        is PhoneCommand.Media -> media(command.play)
        else -> null
    }

    suspend fun radio(screen: ScreenSession, radio: RadioKind, on: Boolean): CommandResult =
        if (radio == RadioKind.WIFI) wifi(screen, on) else bluetooth(screen, on)

    /**
     * Wi-Fi through the switch of Android's Internet panel, which turns the radio off and on; the Quick Settings tile only
     * disconnects (prototype P4). Kestrel reads Wi-Fi's state from Android and opens the panel only when it differs. It
     * acts only in the panel's own window: a window that was not on the screen before, of SystemUI or Settings, that shows
     * the switch; so never on Quick Settings' tile or on a switch of the app in front. It clicks the switch, waits up to
     * 6 s for Android's state to change, and closes the panel with its "Done" button, or Back while the panel's window, the
     * one where the switch was found, still shows, also when the command is cancelled.
     */
    private suspend fun wifi(screen: ScreenSession, on: Boolean): CommandResult {
        if (wifiOn() == on) {
            return CommandResult(Outcome.DONE, show = if (on) PhoneCommandTexts.WIFI_ALREADY_ON else PhoneCommandTexts.WIFI_ALREADY_OFF)
        }
        val before = screen.snapshots.current().snapshot.windows.map { it.id }.toSet()
        // The start is inside the try, so that a cancel during its wait closes the panel too; until the start fails, the
        // panel may still be on its way.
        var opening = true
        var switchWindow: Int? = null
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
            switchWindow = toggle.windowId
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
            withContext(NonCancellable) { closePanel(screen, before, opening, switchWindow) }
        }
    }

    // Closes the panel that the command opened, with its "Done" button, or with Back while its window still shows, so that
    // Back never acts on the owner's app. Once the switch was found, the panel is the switch's window, so that another new
    // window, such as the volume dialog, never counts as the panel after the owner closed it. When Kestrel's start failed
    // ([opening] false), it opened no panel, and closes nothing, as leavePage does.
    private suspend fun closePanel(screen: ScreenSession, before: Set<Int>, opening: Boolean, switchWindow: Int?) {
        if (!opening) return
        val panel = newWindow(screen, before, PANEL_PACKAGES, switchWindow == null, switchWindow) ?: return
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
     * Settings shows no tile, through the Bluetooth settings page that Kestrel opens: its switch named Bluetooth, or else
     * the page's one main switch. Kestrel acts only on SystemUI's tile or on that page's window, never on a switch of the
     * app in front, and the state is Android's, read for up to 6 s. The shade and the page close also when the command is
     * cancelled.
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
        // A cancel that came while the shade closed, which NonCancellable held back, ends the command before Settings opens.
        currentCoroutineContext().ensureActive()
        return bluetoothPage(screen, on)
    }

    // The Bluetooth switch of the settings page, in the window of Settings that the command opened: a switch named
    // Bluetooth, or the page's one main switch, which on AOSP is named only by its view ID (android:id/switch_widget),
    // while the page's title or its switch bar names Bluetooth. Kestrel looks for the switch only once the page shows,
    // which it waits up to 8 s for, because a cold start of Settings took 4.3 to 5.0 s on kestrel-api37.
    private suspend fun bluetoothPage(screen: ScreenSession, on: Boolean): CommandResult {
        val before = screen.snapshots.current().snapshot.windows.map { it.id }.toSet()
        var opening = true
        var awaited = false
        try {
            if (screen.system.start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) is ActionResult.Failed) {
                opening = false
                return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_BLUETOOTH_SWITCH)
            }
            val page = awaitPage({ screen.snapshots.current().snapshot }, before)
            awaited = true
            page ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_BLUETOOTH_SWITCH)
            val main = await(screen) { snapshot, element ->
                element.windowId !in before && element.packageName == SETTINGS && element.checkable && element.enabled &&
                    snapshot.windows.any { it.id == element.windowId && it.kind == WindowKind.APPLICATION } &&
                    (namesRadio(snapshot, element, RadioKind.BLUETOOTH) || mainSwitch(snapshot, element.windowId, RadioKind.BLUETOOTH) == element)
            } ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_BLUETOOTH_SWITCH)
            val element = targets.still(ScreenTargets.chosen(main))
            if (element != null && element.checked != on) screen.actions.tap(element.key)
            return bluetoothResult(awaitState(on) { bluetoothOn() })
        } finally {
            withContext(NonCancellable) { leavePage(screen, before, opening, awaited) }
        }
    }

    /**
     * Presses Back while the Settings page that the command opened is the app window in front, until the app that was in
     * front before the command is in front again, at most 3 times, so that Back never goes past that app; when Kestrel's
     * start failed, it opened no page and presses nothing. One Back is not always enough: when Settings already had a task
     * in the background, Android puts the page on top of that task. [awaited] tells whether the command's wait for the
     * page has ended (see [backUntilLeft]).
     */
    private suspend fun leavePage(screen: ScreenSession, before: Set<Int>, opening: Boolean, awaited: Boolean) {
        if (!opening) return
        backUntilLeft({ screen.snapshots.current().snapshot.windows }, before, awaited) { screen.actions.global(GlobalAction.BACK) }
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

    // A window of [packages] that was not on the screen [before], or the window [switchWindow] where the command found its
    // switch (see [ownWindow]); while it may still be [opening], after a cancel in the first moments of its start, Kestrel
    // waits up to 1 s for it.
    private suspend fun newWindow(
        screen: ScreenSession,
        before: Set<Int>,
        packages: Set<String>,
        opening: Boolean,
        switchWindow: Int? = null,
    ): WindowSummary? {
        suspend fun find() = ownWindow(screen.snapshots.current().snapshot.windows, before, packages, switchWindow)
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

    /**
     * Volume up or down by one step of the stream (design D10): the stream that the owner names, else the call stream
     * during another app's call and the media stream otherwise. Android's own volume panel shows the change.
     */
    private suspend fun volume(kind: StreamKind?, up: Boolean): CommandResult {
        val audio = audio ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.VOLUME_NOT_CHANGED)
        val stream = streamOf(kind, audio.mode)
        val before = audio.getStreamVolume(stream)
        try {
            audio.adjustStreamVolume(stream, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        } catch (e: SecurityException) {
            return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.RINGER_WHILE_SILENT)
        }
        delay(READ_BACK_MILLIS)
        val after = audio.getStreamVolume(stream)
        return volumeResult(before, after, audio.getStreamMinVolume(stream), audio.getStreamMaxVolume(stream), up)
    }

    /**
     * "Mute" and "unmute": the media stream, or the ring stream when the owner names it. Android silently ignores a mute of
     * the alarm and the call volume (prototype P4), so Kestrel says so and changes nothing (decision 5).
     */
    private suspend fun mute(kind: StreamKind?, on: Boolean): CommandResult {
        if (on) muteRefusal(kind)?.let { return CommandResult(Outcome.FAILED, say = it) }
        val audio = audio ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.VOLUME_NOT_CHANGED)
        val stream = streamOf(kind ?: StreamKind.MEDIA, AudioManager.MODE_NORMAL)
        if (audio.isStreamMute(stream) == on) {
            return CommandResult(Outcome.DONE, show = if (on) PhoneCommandTexts.ALREADY_MUTED else PhoneCommandTexts.NOT_MUTED)
        }
        try {
            audio.adjustStreamVolume(stream, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        } catch (e: SecurityException) {
            return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.RINGER_WHILE_SILENT)
        }
        delay(READ_BACK_MILLIS)
        return if (audio.isStreamMute(stream) == on) {
            CommandResult(Outcome.DONE)
        } else {
            CommandResult(Outcome.FAILED, show = PhoneCommandTexts.VOLUME_NOT_CHANGED)
        }
    }

    /**
     * "Silence" puts the phone on vibrate. The true silent mode needs notification-policy access, which Kestrel never asks
     * for, and Android also refuses to leave it without that access.
     */
    private suspend fun silence(): CommandResult {
        val audio = audio ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.VOLUME_NOT_CHANGED)
        when (audio.ringerMode) {
            AudioManager.RINGER_MODE_VIBRATE -> return CommandResult(Outcome.DONE, show = PhoneCommandTexts.ALREADY_VIBRATE)
            AudioManager.RINGER_MODE_SILENT -> return CommandResult(Outcome.DONE, show = PhoneCommandTexts.ALREADY_SILENT)
        }
        try {
            audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        } catch (e: SecurityException) {
            return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.RINGER_WHILE_SILENT)
        }
        delay(READ_BACK_MILLIS)
        return if (audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            CommandResult(Outcome.DONE)
        } else {
            CommandResult(Outcome.FAILED, show = PhoneCommandTexts.VOLUME_NOT_CHANGED)
        }
    }

    /**
     * "Media play" and "media pause": the media key itself, not a toggle, through Android's audio API, which reaches the
     * player of the media session that played last, also while Kestrel speaks (prototype P4). After 1 s Kestrel checks
     * whether a player took the key: for "play", music plays and either none played before or a music player started after
     * the key, so that another app that was already playing does not count; for "pause", music stopped. Kestrel's own
     * replies do not count as music.
     */
    private suspend fun media(play: Boolean): CommandResult {
        val audio = audio ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_MEDIA)
        val playing = audio.isMusicActive
        val players = musicPlayers(audio)
        val key = if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        delay(MEDIA_MILLIS)
        val reacted = if (play) {
            playTookKey(playing, audio.isMusicActive, players, musicPlayers(audio))
        } else {
            playing && !audio.isMusicActive
        }
        return if (reacted) CommandResult(Outcome.DONE, repeatable = true) else CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_MEDIA)
    }

    // The players that play music now: Android lists only the active ones to an app such as Kestrel.
    private fun musicPlayers(audio: AudioManager): List<AudioPlaybackConfiguration> =
        audio.activePlaybackConfigurations.filter { it.audioAttributes.usage in MUSIC_USAGES }

    /**
     * Magnification (design D10): "zoom in" magnifies 2 times at the screen's centre, and one step more, up to 8 times,
     * while the screen is magnified; "zoom out" goes one step back and ends at 1; "stop magnification" ends it.
     */
    suspend fun magnify(screen: ScreenSession, action: MagnifyAction): CommandResult {
        val scale = screen.actions.zoomScale
        val result = when (action) {
            MagnifyAction.IN -> when {
                scale <= 1f -> screen.screenBounds.let { screen.actions.zoom(FIRST_SCALE, it.centerX, it.centerY) }
                scale >= MAX_SCALE -> return CommandResult(Outcome.DONE, show = PhoneCommandTexts.MOST_MAGNIFIED)
                else -> zoomAround(screen, minOf(MAX_SCALE, scale.toInt() + 1f))
            }
            MagnifyAction.OUT -> when {
                scale <= 1f -> return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NOT_MAGNIFIED)
                scale - 1f <= 1f -> screen.actions.resetZoom()
                else -> zoomAround(screen, scale - 1f)
            }
            MagnifyAction.STOP -> if (scale <= 1f) return CommandResult(Outcome.DONE) else screen.actions.resetZoom()
        }
        return done(result)
    }

    // A new scale around the centre of the magnified area, so that the place that the owner looks at stays.
    private suspend fun zoomAround(screen: ScreenSession, scale: Float): ActionResult {
        val (x, y) = screen.system.magnifiedCentre() ?: screen.screenBounds.let { it.centerX.toFloat() to it.centerY.toFloat() }
        return screen.actions.zoom(scale, x.roundToInt(), y.roundToInt())
    }

    /** "Pan left", and "scroll" without a target while magnified: a third of the visible width or height (design D10). */
    suspend fun pan(screen: ScreenSession, direction: Direction): CommandResult {
        val scale = screen.actions.zoomScale
        if (scale <= 1f) return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NOT_MAGNIFIED)
        val (dx, dy) = panBy(direction, screen.screenBounds.width, screen.screenBounds.height, scale)
        return done(screen.system.pan(dx, dy))
    }

    private fun done(result: ActionResult): CommandResult = when (result) {
        is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = true)
        // The reasons of the screen layer are fixed texts without the screen's words.
        is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = result.reason)
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val SETTINGS = "com.android.settings"
        private const val PANEL_MILLIS = 3_000L
        private const val PAGE_MILLIS = 8_000L
        private const val RADIO_MILLIS = 6_000L
        private const val POLL_MILLIS = 100L
        private const val APPEAR_MILLIS = 1_000L
        private const val LEAVE_MILLIS = 2_000L
        private const val MAX_BACKS = 3
        private const val READ_BACK_MILLIS = 300L
        private const val MEDIA_MILLIS = 1_000L
        // The usages of Android's music stream, which isMusicActive counts; Kestrel's replies use USAGE_ASSISTANT.
        private val MUSIC_USAGES = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)
        private const val FIRST_SCALE = 2f
        private const val MAX_SCALE = 8f

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
            return names.any { isRadioName(it, radio) }
        }

        // Whether [name] is [radio]'s name, alone or after "use", in folded words, before a comma that may add a detail.
        private fun isRadioName(name: String, radio: RadioKind): Boolean {
            val radioNames = if (radio == RadioKind.WIFI) setOf(listOf("wi", "fi"), listOf("wifi")) else setOf(listOf("bluetooth"), listOf("blue", "tooth"))
            return Normalizer.words(name.substringBefore(',')).let { if (it.firstOrNull() == "use") it.drop(1) else it } in radioNames
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

        /**
         * The one main switch of the settings page of [radio] in the window [windowId]: an enabled, checkable element whose
         * own name comes only from its view ID, such as AOSP's `android:id/switch_widget`, inside the page's switch bar, an
         * element whose view ID names a switch bar. Null when the window has none, or more than one, and when neither the
         * window's title, a title in the page's toolbar or action bar, nor the switch bar's text names [radio], so that the
         * page of another setting, such as NFC's on top of an old Settings task that the start brought to the front, never
         * counts. Kestrel takes it only on the radio's settings page that it opened itself, whose switch bar may read "On"
         * or "Off" rather than the radio's name.
         */
        fun mainSwitch(snapshot: ScreenSnapshot, windowId: Int, radio: RadioKind): ScreenElement? {
            val switch = snapshot.elements.filter { element ->
                element.windowId == windowId && element.enabled && element.checkable && element.viewId != null &&
                    listOfNotNull(element.label, element.text, element.contentDescription, element.hint, element.supplementalDescription)
                        .all { it.isBlank() } &&
                    switchBar(snapshot, element) != null
            }.singleOrNull() ?: return null
            return switch.takeIf { pageNamesRadio(snapshot, it, radio) }
        }

        // Whether the page of [switch] names [radio]: the window's title, a text in the page's toolbar or action bar, or the
        // text of the switch bar around [switch], such as "Use Bluetooth".
        private fun pageNamesRadio(snapshot: ScreenSnapshot, switch: ScreenElement, radio: RadioKind): Boolean {
            val title = snapshot.windows.firstOrNull { it.id == switch.windowId }?.title
            if (title != null && isRadioName(title, radio)) return true
            val bar = switchBar(snapshot, switch)
            return snapshot.elements.withIndex().any { (index, element) ->
                element.windowId == switch.windowId && element != switch &&
                    (bar != null && (index == bar || within(snapshot, element, bar)) || inToolbar(snapshot, index)) &&
                    listOfNotNull(element.label, element.text, element.contentDescription).any { isRadioName(it, radio) }
            }
        }

        // The index of [element]'s nearest ancestor, at most 20 levels up, with a view ID such as switch_bar or
        // main_switch_bar, or null.
        private fun switchBar(snapshot: ScreenSnapshot, element: ScreenElement): Int? = ancestor(snapshot, element.parent) {
            it.viewId?.substringAfter(":id/")?.contains("switch_bar") == true
        }

        // Whether the element at [index], or an ancestor of it at most 20 levels up, has a view ID that names a toolbar or
        // an action bar.
        private fun inToolbar(snapshot: ScreenSnapshot, index: Int): Boolean = ancestor(snapshot, index) { element ->
            element.viewId?.substringAfter(":id/")?.lowercase()?.let { "toolbar" in it || "action_bar" in it } == true
        } != null

        // The index of the first element from [start] up its parents, at most 20 levels, that [accept] takes.
        private fun ancestor(snapshot: ScreenSnapshot, start: Int?, accept: (ScreenElement) -> Boolean): Int? {
            var index = start
            var depth = 0
            while (index != null && depth < 20) {
                val element = snapshot.elements.getOrNull(index) ?: return null
                if (accept(element)) return index
                index = element.parent
                depth++
            }
            return null
        }

        /**
         * The window that the command opened: the window [switchWindow] where it found its switch, while that window still
         * shows, or, before it found one, a window of [packages] that was not on the screen [before]. So another new window,
         * such as the volume dialog after the owner closed the panel, never counts as the command's window.
         */
        fun ownWindow(windows: List<WindowSummary>, before: Set<Int>, packages: Set<String>, switchWindow: Int?): WindowSummary? =
            if (switchWindow != null) {
                windows.firstOrNull { it.id == switchWindow }
            } else {
                windows.firstOrNull { it.id !in before && it.packageName in packages }
            }

        /**
         * The app window in front: the active one. While no app window is active, as while the shade, a system dialog or an
         * app that is still starting has the focus, none is, so that Back never goes to an app that only lies on top.
         */
        fun frontApp(windows: List<WindowSummary>): WindowSummary? =
            windows.firstOrNull { it.kind == WindowKind.APPLICATION && it.active }

        /**
         * The page of Settings in front that Back may close after a settings page's command: the app window in front, when
         * it is Settings' and was not on the screen [before] the command. Null once the app that was in front before is in
         * front again, so that Back never goes past it.
         */
        fun settingsPageInFront(windows: List<WindowSummary>, before: Set<Int>): WindowSummary? =
            frontApp(windows)?.takeIf { it.packageName == SETTINGS && it.id !in before }

        /**
         * The page of Settings that a settings page's command opened, once it shows: a Settings page in front that was
         * not on the screen [before] the command (see [settingsPageInFront]), with at least one element that has a name,
         * so that a window of Settings that is still starting never counts.
         */
        fun shownPage(snapshot: ScreenSnapshot, before: Set<Int>): WindowSummary? =
            settingsPageInFront(snapshot.windows, before)?.takeIf { page ->
                snapshot.elements.any { it.windowId == page.id && !it.label.isNullOrBlank() }
            }

        /** Waits up to 8 s for the [shownPage] of the snapshots that [snapshot] reads; null when none shows. */
        suspend fun awaitPage(snapshot: suspend () -> ScreenSnapshot, before: Set<Int>): WindowSummary? =
            withTimeoutOrNull(PAGE_MILLIS) {
                var page = shownPage(snapshot(), before)
                while (page == null) {
                    delay(POLL_MILLIS)
                    page = shownPage(snapshot(), before)
                }
                page
            }

        /**
         * Waits up to 6 s for Android's [state] of a radio to become [on]: Bluetooth needed 3.7 s to turn on when its stack
         * restarted on a loaded kestrel-api37.
         */
        suspend fun awaitState(on: Boolean, state: () -> Boolean?): Boolean =
            withTimeoutOrNull(RADIO_MILLIS) {
                while (state() != on) delay(POLL_MILLIS)
                true
            } ?: false

        /**
         * Presses [back] while a Settings page that was not on the screen [before] is the app window in front of [windows],
         * at most 3 times. Before the first Back it waits for the page to come to the front, and presses nothing when none
         * does: up to 8 s when the command was cancelled before its own wait for the page ([awaited] false), because a
         * cold start of Settings took 4.3 to 5.0 s on kestrel-api37, and only up to 1 s once that wait has ended, so that a
         * page that never showed in 8 s does not keep the command for another 8 s. After each Back it waits until another
         * app window is in front, so that it never sees the closing page, and stops at once when that window is no such
         * page, as when the app that was in front before is in front again.
         */
        suspend fun backUntilLeft(
            windows: suspend () -> List<WindowSummary>,
            before: Set<Int>,
            awaited: Boolean,
            back: suspend () -> Unit,
        ) {
            var page = withTimeoutOrNull(if (awaited) APPEAR_MILLIS else PAGE_MILLIS) {
                var page = settingsPageInFront(windows(), before)
                while (page == null) {
                    delay(POLL_MILLIS)
                    page = settingsPageInFront(windows(), before)
                }
                page
            } ?: return
            repeat(MAX_BACKS) {
                back()
                val closing = page.id
                withTimeoutOrNull(LEAVE_MILLIS) {
                    while (frontApp(windows()).let { it == null || it.id == closing }) delay(POLL_MILLIS)
                } ?: return
                page = settingsPageInFront(windows(), before) ?: return
            }
        }

        /** The stream of [kind], or without one the call stream during another app's call and the media stream otherwise. */
        fun streamOf(kind: StreamKind?, mode: Int): Int = when (kind) {
            null -> if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                AudioManager.STREAM_VOICE_CALL
            } else {
                AudioManager.STREAM_MUSIC
            }
            StreamKind.MEDIA -> AudioManager.STREAM_MUSIC
            StreamKind.RING -> AudioManager.STREAM_RING
            StreamKind.ALARM -> AudioManager.STREAM_ALARM
            StreamKind.CALL -> AudioManager.STREAM_VOICE_CALL
            StreamKind.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        }

        /** What a volume step ended with, read back from Android: one stream's step can jump several indices. */
        fun volumeResult(before: Int, after: Int, min: Int, max: Int, up: Boolean): CommandResult = when {
            after != before -> CommandResult(Outcome.DONE, repeatable = true)
            up && before >= max -> CommandResult(Outcome.DONE, show = PhoneCommandTexts.HIGHEST)
            !up && before <= min -> CommandResult(Outcome.DONE, show = PhoneCommandTexts.LOWEST)
            // Do Not Disturb, or Android 17's limits on volume changes from the background (research r16).
            else -> CommandResult(Outcome.FAILED, show = PhoneCommandTexts.VOLUME_NOT_CHANGED)
        }

        /** Why Kestrel does not mute the stream of [kind], or null when it may. */
        fun muteRefusal(kind: StreamKind?): String? = when (kind) {
            StreamKind.ALARM -> PhoneCommandTexts.MUTE_ALARM
            StreamKind.CALL -> PhoneCommandTexts.MUTE_CALL
            else -> null
        }

        /**
         * Whether a player took "media play": music plays after the key, and either none played before it ([wasPlaying])
         * or a player of [after] was not among [before], the music players before the key.
         */
        fun <T> playTookKey(wasPlaying: Boolean, playing: Boolean, before: List<T>, after: List<T>): Boolean =
            playing && (!wasPlaying || after.any { it !in before })

        /** How far "pan" moves the magnified area: a third of the visible width or height, in screen pixels. */
        fun panBy(direction: Direction, width: Int, height: Int, scale: Float): Pair<Int, Int> {
            val dx = (width / scale / 3).roundToInt()
            val dy = (height / scale / 3).roundToInt()
            return when (direction) {
                Direction.UP -> 0 to -dy
                Direction.DOWN -> 0 to dy
                Direction.LEFT -> -dx to 0
                Direction.RIGHT -> dx to 0
            }
        }
    }
}
