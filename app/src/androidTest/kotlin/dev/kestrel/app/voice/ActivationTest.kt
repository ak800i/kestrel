package dev.kestrel.app.voice

import android.accessibilityservice.AccessibilityService
import android.os.ParcelFileDescriptor
import android.service.quicksettings.Tile
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.voice.models.Engine
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The ways into listening without a word (design spec, section 8.6): the accessibility shortcut and the accessibility
 * button, which both reach KestrelAccessibilityService's button callback, and the Quick Settings tile. Kestrel is in the
 * background, as when the owner uses them. The test process is instrumented, which Android may count as a reason to let
 * Kestrel start a foreground service from the background, so these tests check the wiring; scripts\listen-check.ps1
 * checks the start paths themselves. Each test puts back the settings that it changes, and leaves Kestrel off and its
 * accessibility service off, as the voice tests after it expect. The tile's test runs only on an emulator, because no
 * script puts the list of tiles back if the test's process dies.
 */
class ActivationTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val listening = VoiceTests.graph.listening
    private val clip = ClipMicrophone()

    @Before
    fun setUp() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
        VoiceTests.useMicrophone(clip)
        VoiceTests.shell("input keyevent KEYCODE_HOME")
    }

    @After
    fun tearDown() {
        try {
            VoiceTests.stopListening()
        } finally {
            turnOffTheAccessibilityService()
        }
    }

    // The voice tests after this one run without Kestrel's accessibility service, as they did before this test existed. Its
    // binding alone lets Kestrel record and play in the background while the screen is on (prototype P1, Q1), which would
    // hide a ListeningService that does not give Kestrel these rights (ConversationTest, B5).
    private fun turnOffTheAccessibilityService() {
        VoiceTests.shell("settings delete secure enabled_accessibility_services")
        check(VoiceTests.waitUntil(10_000) { VoiceTests.graph.screen.value == null }) { "Kestrel's accessibility service is still connected" }
    }

    @Test
    fun theShortcutAndTheButtonToggleListening() {
        val saved = TARGETS.associateWith { secure(it) }
        try {
            putSecure(SHORTCUT, SERVICE)
            // Android reads the setting a moment after it changes.
            assertTrue(
                "Android does not name Kestrel as the shortcut's target",
                VoiceTests.waitUntil(5_000) { SHORTCUT_KEY.containsMatchIn(VoiceTests.shell("dumpsys accessibility")) },
            )

            assertTrue("The shortcut's action failed", press(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT))
            awaitListening()
            assertTrue("The shortcut's action failed", press(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT))
            awaitState { it.mode == Mode.PAUSED }

            // With gestural navigation, as on kestrel-api37, Android offers the button's action only for a gesture target;
            // with the navigation bar's buttons, for a button target in the navigation bar (prototype P1, research R14).
            putSecure(BUTTON, SERVICE)
            putSecure(GESTURE, SERVICE)
            putSecure(BUTTON_MODE, "0")
            // SystemUI offers the action a moment after it has seen the targets. Until then the call returns false and does
            // nothing, so only the call that returns true presses the button.
            assertTrue(
                "Android did not offer the accessibility button's action",
                VoiceTests.waitUntil(5_000) { press(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_BUTTON) },
            )
            awaitState { it.mode == Mode.LISTENING }
        } finally {
            // Each setting is written back even when another write fails.
            val failures = saved.map { (name, value) -> runCatching { putSecure(name, value) } }
            failures.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        }
    }

    @Test
    fun theTileTogglesListening() {
        // It changes the list of tiles, which no script puts back if the test's process dies.
        VoiceTests.assumeEmulator()
        // Without Kestrel's tile, also if an earlier run's test process died before it removed the tile.
        val tiles = secure(TILES).let { list ->
            if (list == "null") list else list.split(',').filterNot { TILE_NAME in it }.joinToString(",")
        }
        try {
            // First in the list, so that the tile is on the first page of Quick Settings, whose tiles SystemUI follows while
            // the panel is open. A device without a list of its own gets the tile at the end.
            if (tiles == "null" || tiles.isEmpty()) {
                VoiceTests.shell("cmd statusbar add-tile $TILE")
            } else {
                VoiceTests.shell("cmd statusbar set-tiles $TILE_SPEC,$tiles")
                // A SystemUI without set-tiles follows the setting itself.
                if (!VoiceTests.waitUntil(5_000) { secure(TILES).contains(TILE_NAME) }) putSecure(TILES, "$TILE_SPEC,$tiles")
            }
            assertTrue("Quick Settings does not list Kestrel's tile", VoiceTests.waitUntil(5_000) { secure(TILES).contains(TILE_NAME) })
            VoiceTests.shell("cmd statusbar expand-settings")
            // SystemUI makes and binds the tile a moment after it lists it, and a click before that is lost.
            awaitTile("Off", Tile.STATE_INACTIVE, timeoutMillis = 15_000)

            VoiceTests.shell("cmd statusbar click-tile $TILE")
            awaitListening()
            awaitTile("Listening", Tile.STATE_ACTIVE)
            VoiceTests.shell("cmd statusbar click-tile $TILE")
            awaitState { it.mode == Mode.PAUSED }
            awaitTile("Paused", Tile.STATE_INACTIVE)
        } finally {
            VoiceTests.shell("cmd statusbar collapse")
            // SystemUI removes only a tile that it has made, so not the tile of a Kestrel without it.
            VoiceTests.shell("cmd statusbar remove-tile $TILE")
            // SystemUI writes the list a moment later. A list that does not come back as it was is written back: through
            // SystemUI, which since Android 14 keeps the list itself and overwrites other writes to the setting, or else
            // to the setting, which an older SystemUI follows.
            if (!VoiceTests.waitUntil(5_000) { secure(TILES) == tiles }) {
                if (tiles != "null" && tiles.isNotEmpty()) VoiceTests.shell("cmd statusbar set-tiles $tiles")
                if (!VoiceTests.waitUntil(5_000) { secure(TILES) == tiles }) putSecure(TILES, tiles)
                check(VoiceTests.waitUntil(5_000) { secure(TILES) == tiles }) { "The list of Quick Settings tiles is not as it was" }
            }
        }
    }

    /** Waits until SystemUI's dump shows Kestrel's tile with [subtitle] and in [state], as the owner sees it. */
    private fun awaitTile(subtitle: String, state: Int, timeoutMillis: Long = 5_000) {
        val look = Regex("""spec=custom\([^)]*KestrelTileService\).*,secondaryLabel=$subtitle,.*,state=$state,""")
        assertTrue(
            "SystemUI does not show Kestrel's tile as $subtitle",
            VoiceTests.waitUntil(timeoutMillis) { look.containsMatchIn(VoiceTests.shell("dumpsys activity service com.android.systemui/.SystemUIService")) },
        )
    }

    /** Asks Android for a system action, as the accessibility shortcut or button does. Returns whether Android ran it. */
    private fun press(action: Int): Boolean = screen.automation.performGlobalAction(action)

    /** Waits until Kestrel listens, with its voice session open and the test's microphone recording. */
    private fun awaitListening() {
        // Loading the models takes several seconds.
        assertTrue(
            "Kestrel does not listen; the state is ${listening.state.value}",
            VoiceTests.waitUntil(60_000) { listening.state.value.mode == Mode.LISTENING && listening.sessionOpen.value && clip.recording },
        )
    }

    private fun awaitState(condition: (State) -> Boolean) {
        assertTrue("The state is ${listening.state.value}", VoiceTests.waitUntil(10_000) { condition(listening.state.value) })
    }

    /** A secure setting as the shell prints it: "null" when it is not set. */
    private fun secure(name: String): String = VoiceTests.shell("settings get secure $name").trimEnd('\r', '\n')

    /** Writes a secure setting through the device's shell, which reads quotes, so an empty value too; "null" deletes it. */
    private fun putSecure(name: String, value: String) {
        val command = if (value == "null") "settings delete secure $name" else "settings put secure $name '${value.replace("'", "'\\''")}'"
        val (output, input) = screen.automation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write("$command\n".toByteArray()) }
        // The shell ends when its input ends, and only then its output, so the setting is written when this returns.
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private companion object {
        const val SERVICE = "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService"
        const val SHORTCUT = "accessibility_shortcut_target_service"
        const val BUTTON = "accessibility_button_targets"
        const val GESTURE = "accessibility_gesture_targets"
        const val BUTTON_MODE = "accessibility_button_mode"
        val TARGETS = listOf(SHORTCUT, BUTTON, GESTURE, BUTTON_MODE)

        // How `dumpsys accessibility` names the shortcut's targets, as in "shortcut key:{dev.kestrel/...}".
        val SHORTCUT_KEY = Regex("""shortcut key:\{[^}]*dev\.kestrel/""")

        const val TILES = "sysui_qs_tiles"
        const val TILE = "dev.kestrel/dev.kestrel.app.listen.KestrelTileService"

        // SystemUI lists the tile as custom(dev.kestrel/.app.listen.KestrelTileService), as add-tile writes it.
        const val TILE_SPEC = "custom(dev.kestrel/.app.listen.KestrelTileService)"
        const val TILE_NAME = "KestrelTileService"
    }
}
