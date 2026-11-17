package dev.kestrel.voice.speak

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** Android's audio focus as Kestrel's player uses it. [AndroidAudioFocus] is the real one; the JVM tests use a fake. */
internal interface AudioFocus {
    /** Asks Android for the focus, and returns whether Android granted it. */
    fun request(): Boolean

    /** Gives the focus up. */
    fun abandon()
}

/**
 * When Kestrel holds the audio focus for its replies (design spec, sections 8.4 and 14.6). A reply asks for the focus
 * when its turn comes ([take]). The focus stays while more replies wait, so that an app whose volume Android lowered
 * does not get louder for a moment between them, and [finish] gives it up once no reply waits. [changed] learns when
 * Android takes the focus away, as for a ringing call or an app that asks for exclusive focus, and the player then
 * stops speaking; or when another app, such as a notification's sound or a navigation prompt, only lets Kestrel lower
 * its volume, and the player then speaks on at [volume]. Any thread may call it.
 */
internal class ReplyFocus(private val focus: AudioFocus) {
    private val lock = Any()
    private var held = false
    private var replies = 0

    // Another app speaks over Kestrel, which plays more quietly until Android gives the focus back.
    private var lowered = false

    /** A reply was asked for. Call [finish] once for it when it ends, however it ends. */
    fun asked() {
        synchronized(lock) { replies++ }
    }

    /** A reply's turn has come. Returns whether Kestrel holds the focus, and asks Android for it when it does not. */
    fun take(): Boolean {
        synchronized(lock) {
            if (!held) held = focus.request()
            return held
        }
    }

    /** A reply ended. Gives the focus up when no other reply waits. */
    fun finish() {
        synchronized(lock) {
            replies--
            if (replies == 0 && held) {
                held = false
                lowered = false
                focus.abandon()
            }
        }
    }

    /** The volume at which the player plays now: [QUIET_VOLUME] while another app speaks over Kestrel, and 1 otherwise. */
    fun volume(): Float = synchronized(lock) { if (lowered) QUIET_VOLUME else 1f }

    /**
     * Android changed Kestrel's focus to [change], one of AudioManager's `AUDIOFOCUS_` values, and returns what the
     * player does. A loss that lets Kestrel lower its volume, as for a notification's sound or a navigation prompt, keeps
     * the reply going more quietly ([FocusChange.LOWER]) until the focus comes back ([FocusChange.RESTORE]). A transient
     * or lasting loss, as for a call, an app that asks for exclusive focus or another assistant, stops the reply
     * ([FocusChange.STOP]); Kestrel gives the focus up then, so that Android does not give it back in the middle of a
     * later reply.
     */
    fun changed(change: Int): FocusChange {
        synchronized(lock) {
            if (!held) return FocusChange.NONE
            return when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    lowered = true
                    FocusChange.LOWER
                }
                AudioManager.AUDIOFOCUS_GAIN -> if (lowered) {
                    lowered = false
                    FocusChange.RESTORE
                } else {
                    FocusChange.NONE
                }
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    held = false
                    lowered = false
                    focus.abandon()
                    FocusChange.STOP
                }
                else -> FocusChange.NONE
            }
        }
    }

    internal companion object {
        // About 14 dB quieter, as Android lowers other apps' audio.
        const val QUIET_VOLUME = 0.2f
    }
}

/** What the player does after a change of its audio focus (design spec, section 8.4). */
internal enum class FocusChange { NONE, STOP, LOWER, RESTORE }

/**
 * Transient focus that lets other apps lower their volume, for audio with [attributes]. Each instance has its own
 * request with its own listener, because Android tells requests apart by their listener: without one, every request
 * of Kestrel's would have the same client ID, and one player's abandon would drop another's focus (plan 2B, Task 5,
 * review N5). Android calls [onChange] with each change, on the main thread.
 */
internal class AndroidAudioFocus(context: Context, attributes: AudioAttributes, onChange: (Int) -> Unit) : AudioFocus {
    private val audio = context.getSystemService(AudioManager::class.java)
    // Android does not lower the volume of speech by itself, because that would make it hard to understand; it reports
    // a "may duck" loss to Kestrel instead, and ReplyFocus lowers the volume.
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> onChange(change) }
        .build()

    override fun request(): Boolean {
        if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return true
        // Android keeps a refused request's listener until it is abandoned, which would keep this player reachable.
        audio.abandonAudioFocusRequest(request)
        return false
    }

    override fun abandon() {
        audio.abandonAudioFocusRequest(request)
    }
}
