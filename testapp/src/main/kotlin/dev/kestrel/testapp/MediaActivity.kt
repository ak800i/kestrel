package dev.kestrel.testapp

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

/**
 * A player for "media play" and "media pause" (design spec, Appendix A; prototype P4): a quiet 440 Hz tone with an active
 * media session, which Android's media keys reach also while the fixture is in the background. It starts playing, and the
 * status line says "Playing" or "Paused".
 */
class MediaActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var tone: AudioTrack
    private lateinit var session: MediaSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Media fixture")
        status = page.addStatus()
        tone = toneTrack()
        session = MediaSession(this, "KestrelFixture").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()

                override fun onPause() = pause()
            })
            isActive = true
        }
        play()
    }

    override fun onDestroy() {
        session.release()
        tone.release()
        super.onDestroy()
    }

    private fun play() {
        tone.play()
        session.setPlaybackState(state(PlaybackState.STATE_PLAYING))
        status.text = "Playing"
    }

    private fun pause() {
        tone.pause()
        session.setPlaybackState(state(PlaybackState.STATE_PAUSED))
        status.text = "Paused"
    }

    private fun state(state: Int): PlaybackState = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
        .setState(state, 0, 1f)
        .build()

    // One second of the tone, 440 whole waves, which the track plays in a loop.
    private fun toneTrack(): AudioTrack {
        val samples = ShortArray(RATE) { (sin(2 * PI * 440 * it / RATE) * 0.05 * Short.MAX_VALUE).toInt().toShort() }
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
            .apply {
                write(samples, 0, samples.size)
                setLoopPoints(0, samples.size, -1)
            }
    }

    private companion object {
        const val RATE = 44_100
    }
}
