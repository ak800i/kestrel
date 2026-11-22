package dev.kestrel.datatools.media

import android.media.AudioManager
import android.view.KeyEvent
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.MediaAction
import java.util.Locale

/** `media_control` (design D12 of sub-project 5): the media keys through Android's audio manager, as the instant commands press them. */
class MediaKeys(private val audio: AudioManager) {
    fun press(action: MediaAction): DataResult {
        val key = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return DataResult.Ok(listOf(DataLine("Kestrel pressed the media key ${action.name.lowercase(Locale.ROOT)}.", emptyList())))
    }
}
