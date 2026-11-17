package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.WakeUpMode
import kotlinx.serialization.Serializable

/**
 * The owner's choices for listening on the settings page "Listening and activation" (design spec, sections 8.5, 8.7,
 * 8.8 and 15). [onWakeUp]: what Kestrel does after an unlock. [pauseAfterSilence]: 30 s without speech pause listening.
 * [callCommands]: during a call, Kestrel listens for "Kestrel, hang up", "Kestrel, mute" and "Kestrel, speaker"; the
 * name must come first, because the caller's voice can come through the speaker. [earbudMicrophone]: Kestrel records
 * with a connected Bluetooth headset's microphone. A fresh install, and a value that this version cannot read, give the
 * defaults.
 */
@Serializable
data class ListeningOptions(
    val onWakeUp: WakeUpMode = WakeUpMode.RESUME,
    val pauseAfterSilence: Boolean = false,
    val callCommands: Boolean = false,
    val earbudMicrophone: Boolean = false,
)
