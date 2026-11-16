package dev.kestrel.voice.listen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper
import android.os.Process
import dev.kestrel.voice.SAMPLE_RATE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records the microphone for listening (design spec, section 8.1): source VOICE_RECOGNITION, 16 kHz, mono, 16-bit, in
 * 20 ms frames, and never privacy-sensitive, so that other apps that listen, such as Voice Access or a call, keep their
 * audio (B2). It attaches Android's echo canceller where the device has one. [silenced] is true while Android gives
 * Kestrel silence: when another app has priority on the microphone, when the microphone's privacy toggle is on, or when
 * Android counts Kestrel as idle, such as in the background without a foreground service.
 *
 * Any thread may call [start] and [stop], except the microphone's own thread.
 */
class MicCapture(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val silencedState = MutableStateFlow(false)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var canceller: AcousticEchoCanceler? = null

    @Volatile
    private var reader: Thread? = null

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var running = false

    /**
     * True while Android silences Kestrel's recording, for any of the reasons above: "mic busy" on the feedback bar
     * (section 8.1).
     */
    val silenced: StateFlow<Boolean> = silencedState.asStateFlow()

    /** Whether Android's echo canceller works on the current recording. */
    @Volatile
    var echoCancelling = false
        private set

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) = update(configs)
    }

    /**
     * Starts recording. [onFrame] gets each 20 ms frame of samples between -1 and 1. Both callbacks run on the
     * microphone's own thread.
     *
     * [onError] is called at most once, and then the thread ends: with an [IllegalStateException] when reading the
     * microphone fails, or with the exception that [onFrame] threw, so that such an exception does not end Kestrel's
     * process. [onError] itself must not throw. After an error nothing reads the microphone any more, but the recording
     * stays open, and Android keeps showing that Kestrel uses the microphone, until [stop] closes it. [start] and [stop]
     * must not be called from [onFrame] or [onError], because [stop] waits for the thread; they throw
     * [IllegalStateException] there. The caller hands the error to another thread, which then stops the microphone.
     *
     * Throws [SecurityException] without the microphone permission and [IllegalStateException] when the microphone does
     * not start; then it has released everything, so that a later call can try again.
     */
    fun start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
        checkNotOnTheMicrophonesThread()
        synchronized(this) { open(onFrame, onError) }
    }

    private fun open(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
        check(record == null) { "The microphone is already recording" }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Kestrel may not record audio")
        }
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum, BUFFER_BYTES))
                .setPrivacySensitive(false)
                .build()
        } catch (e: UnsupportedOperationException) {
            throw IllegalStateException("The microphone could not be opened", e)
        }
        var effect: AcousticEchoCanceler? = null
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be opened" }
            if (AcousticEchoCanceler.isAvailable()) {
                effect = AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.setEnabled(true) }
            }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone did not start" }
        } catch (e: Exception) {
            effect?.release()
            recorder.release()
            throw e
        }
        canceller = effect
        echoCancelling = effect?.enabled == true
        record = recorder
        audio.registerAudioRecordingCallback(callback, main)
        // The callback reports only changes. Reading the current recordings on the callback's thread keeps this reading
        // in order with the reports, so that it cannot replace a newer one.
        main.post { update(audio.activeRecordingConfigurations) }
        running = true
        // The thread is known before it runs, so that stop() can recognise a call from [onFrame] or [onError].
        val thread = Thread({ read(recorder, onFrame, onError) }, "Kestrel microphone")
        reader = thread
        thread.start()
    }

    /**
     * Stops recording, and waits until the microphone's thread has finished. It also closes a recording whose thread
     * ended with an error. Throws [IllegalStateException] when called from [start]'s callbacks, where it would wait
     * for ever.
     */
    fun stop() {
        checkNotOnTheMicrophonesThread()
        synchronized(this) { close() }
    }

    private fun close() {
        val recorder = record ?: return
        running = false
        recorder.stop()
        reader?.let(::joinUninterruptibly)
        reader = null
        audio.unregisterAudioRecordingCallback(callback)
        echoCancelling = false
        canceller?.release()
        canceller = null
        synchronized(lock) {
            record = null
            silencedState.value = false
        }
        recorder.release()
    }

    // Checked before taking the monitor: a stop() on another thread holds it while it waits for the microphone's thread.
    private fun checkNotOnTheMicrophonesThread() {
        check(Thread.currentThread() !== reader) { "start() and stop() must not be called from the microphone's thread" }
    }

    // An interrupt must not leave the microphone open, so the wait goes on, and the interrupt is kept for the caller.
    private fun joinUninterruptibly(thread: Thread) {
        var interrupted = false
        while (true) {
            try {
                thread.join()
                break
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    // A report that does not list the current recording, such as one that Android queued before the last stop(),
    // changes nothing. The lock keeps a report from landing after stop() has reset the value.
    private fun update(configs: List<AudioRecordingConfiguration>) {
        synchronized(lock) {
            val session = record?.audioSessionId ?: return
            val mine = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
            silencedState.value = mine.isClientSilenced
        }
    }

    private fun read(recorder: AudioRecord, onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ShortArray(FRAME)
        while (running) {
            val count = recorder.read(buffer, 0, FRAME, AudioRecord.READ_BLOCKING)
            // A read returns fewer samples when stop() interrupts it, or when an error follows some samples; then the next
            // read reports the error. The rest is dropped.
            if (count == FRAME) {
                val frame = FloatArray(count) { buffer[it] / 32_768f }
                // Errors, such as OutOfMemoryError, are not caught.
                try {
                    onFrame(frame)
                } catch (e: Exception) {
                    onError(e)
                    return
                }
            } else if (count < 0 && running) {
                onError(IllegalStateException("The microphone failed with error $count"))
                return
            }
        }
    }

    private companion object {
        const val FRAME = SAMPLE_RATE / 50

        // 200 ms of 16-bit samples, so that a short stall of the microphone's thread loses nothing.
        const val BUFFER_BYTES = SAMPLE_RATE / 5 * 2
    }
}
