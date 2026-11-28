package dev.kestrel.app.debug

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dev.kestrel.app.goal.ForegroundType
import dev.kestrel.app.goal.GoalForeground
import dev.kestrel.app.goal.GoalService

/** What a request for the audio focus gave: [granted] or not, whether it ran [speaking], and the service's types then. */
internal data class FocusResult(val granted: Boolean, val speaking: Boolean, val types: Set<ForegroundType>?)

/**
 * Spike S9's check of audio in the background (main spec B5; design 10.6 of sub-project 7; plan 7E): asks Android for the
 * audio focus as Kestrel's voice does before it speaks (usage assistant, speech), either inside
 * `GoalForeground.whileSpeaking` (speaking) or alone, the control. Android 17 refuses the focus to an app that has neither
 * a visible activity nor a foreground service with while-in-use, so the two requests and a request after the goal's end
 * tell which of those Android asks of Kestrel. It gives the focus back at once and keeps the requests and the newest result
 * for goal-service-state.
 */
internal object FocusProbe {
    /** How long after the hook's answer the request waits, so that the standing that the hook's call gives Kestrel has ended. */
    const val DELAY_MILLIS = 1_000L

    val requests = FocusRequests()

    private val SPEECH: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    suspend fun request(context: Context, foreground: GoalForeground, speaking: Boolean): FocusResult {
        val audio = context.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(SPEECH).build()
        val ask = {
            val granted = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            audio.abandonAudioFocusRequest(focus)
            FocusResult(granted, speaking, GoalService.runningTypes())
        }
        return if (speaking) foreground.whileSpeaking { ask() } else ask()
    }
}
