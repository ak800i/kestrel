package dev.kestrel.voice.listen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records the microphone for listening (design spec, section 8.1): source VOICE_RECOGNITION, 16 kHz, mono, 16-bit, in
 * 20 ms frames, and never privacy-sensitive, so that other apps that listen, such as Voice Access or a call, keep their
 * audio (B2). It attaches Android's echo canceller where the device has one. [silenced] is true while Android gives
 * Kestrel silence: when another app has priority on the microphone, when the microphone's privacy toggle is on, or when
 * Android counts Kestrel as idle, such as in the background without a foreground service.
 *
 * When [earbud] returns true at a start, the microphone of connected Bluetooth earbuds records instead (section 8.8): a
 * [HeadsetRoute] asks Android for the earbuds before the recording exists, and waits up to 3 s until Android names them
 * its communication device, so that Android routes the recording there from its first frame (on Android 13 a classic
 * headset is named only once its link is up), and for an LE Audio headset the recording also names the headset's
 * microphone as its preferred device, because Android gives that microphone to VOICE_RECOGNITION only then (research
 * r12). When the earbuds go away,
 * or a call takes the audio over, the phone's microphone records until the next start. [stop] withdraws the request.
 * Without earbuds, when Android refuses them, or when Android lists no microphone for an LE Audio headset, the phone's
 * microphone records as before.
 *
 * Any thread may call [start] and [stop], except the microphone's own thread.
 */
class MicCapture(private val context: Context, private val earbud: () -> Boolean = { false }) : Microphone {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val devices = AndroidCommunicationDevices(audio, context.mainExecutor)
    private val silencedState = MutableStateFlow(false)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var canceller: AcousticEchoCanceler? = null

    @Volatile
    private var route: HeadsetRoute? = null

    // For routeReport: the kind of the headset that the latest start asked for, and the loudest sample since its last
    // call, in percent.
    @Volatile
    private var headsetKind = "none"
    private val loudest = AtomicInteger()

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
    override val silenced: StateFlow<Boolean> = silencedState.asStateFlow()

    /** Whether Android's echo canceller works on the current recording. */
    @Volatile
    override var echoCancelling = false
        private set

    /**
     * The route of the current recording, for the debug hook voice-route (plan 2E; A71 session of sub-project 2, item 6):
     * whether a recording is open, whether the request for the earbuds holds, the headset's kind (sco, le-audio or none),
     * how long the start waited until Android named the headset, the types of Android's communication device and of the
     * recording's input, and the loudest sample since the last call, in percent. Types and levels only, never audio. Any
     * thread may call it.
     */
    fun routeReport(): String {
        val recorder = record
        val current = route
        val routed = runCatching { recorder?.routedDevice?.type }.getOrNull()
        return "microphone=${if (recorder != null) "open" else "closed"} holding=${current?.holding == true} " +
            "headset=$headsetKind linkedMillis=${current?.linkedMillis ?: "none"} " +
            "communication=${typeName(audio.communicationDevice?.type)} routed=${typeName(routed)} " +
            "loudest=${loudest.getAndSet(0)}"
    }

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
     * not start; then it has released everything, also the request for the earbuds, so that a later call can try again.
     * Only when Android's audio service fails after the microphone opened, or no thread can be created, does [start]
     * throw with the microphone still open; then [stop] releases it.
     *
     * With the earbud microphone allowed and earbuds connected, [start] waits up to 3 s for the earbuds' link
     * ([HeadsetRoute.open]), so it must not run on the main thread, which delivers Android's reports.
     */
    override fun start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
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
        // Before the recording exists, so that Android routes it to the earbuds from its first frame (research r12).
        var earbuds = if (earbud()) HeadsetRoute(devices) else null
        val headset = earbuds?.open(onLost = ::usePhoneMicrophone)
        headsetKind = when {
            headset == null -> "none"
            headset.leAudio -> "le-audio"
            else -> "sco"
        }
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum, BUFFER_BYTES))
                .setPrivacySensitive(false)
                .build()
        } catch (e: Exception) {
            earbuds?.close()
            throw if (e is UnsupportedOperationException) IllegalStateException("The microphone could not be opened", e) else e
        }
        var effect: AcousticEchoCanceler? = null
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be opened" }
            // Android gives an LE Audio headset's microphone to VOICE_RECOGNITION only as a preferred device (r12, item 4).
            // Without such a microphone, the request would only move other apps' audio, so it is withdrawn.
            if (headset?.leAudio == true) {
                val input = devices.input(headset)
                if (input != null) {
                    recorder.setPreferredDevice(input)
                } else {
                    earbuds?.close()
                    earbuds = null
                }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                effect = AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.setEnabled(true) }
            }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone did not start" }
        } catch (e: Exception) {
            effect?.release()
            recorder.release()
            earbuds?.close()
            throw e
        }
        canceller = effect
        echoCancelling = effect?.enabled == true
        synchronized(lock) { record = recorder }
        // A loss of the earbuds that Android reported before [record] was set found no recording to give the phone's
        // microphone back to, and the route then holds nothing. From here on a loss finds the recording itself.
        if (earbuds != null && !earbuds.holding) {
            recorder.setPreferredDevice(null)
            earbuds = null
        }
        route = earbuds
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
     * ended with an error, and withdraws the request for the earbuds. Throws [IllegalStateException] when called from
     * [start]'s callbacks, where it would wait for ever.
     */
    override fun stop() {
        checkNotOnTheMicrophonesThread()
        synchronized(this) { close() }
    }

    private fun close() {
        val recorder = record ?: return
        running = false
        try {
            recorder.stop()
            reader?.let(::joinUninterruptibly)
            reader = null
            audio.unregisterAudioRecordingCallback(callback)
            echoCancelling = false
            canceller?.release()
            canceller = null
        } finally {
            // Before the release, as research r12 advises, and also when the stop failed, so that the request for the
            // earbuds never outlives the recording.
            route?.close()
            route = null
        }
        synchronized(lock) {
            record = null
            silencedState.value = false
        }
        recorder.release()
    }

    // The earbuds went away, or a call took the audio over, and the route has withdrawn its request. Runs on the main
    // thread, before the route's close returns, so [record] is still the recording of that route, or null; open() checks
    // the route once [record] is set, for a loss that came before. Without a preferred device, Android records from the
    // phone's microphone again.
    private fun usePhoneMicrophone() {
        synchronized(lock) { record?.setPreferredDevice(null) }
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
                var peak = 0
                val frame = FloatArray(count) { index ->
                    val sample = buffer[index].toInt()
                    if (sample > peak) peak = sample else if (-sample > peak) peak = -sample
                    sample / 32_768f
                }
                loudest.accumulateAndGet(peak * 100 / 32_768) { a, b -> maxOf(a, b) }
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

        /** The name of an audio device's type for routeReport, such as "bluetooth-sco", or "none". */
        fun typeName(type: Int?): String = when (type) {
            null -> "none"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin-mic"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth-sco"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble-headset"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired-headset"
            else -> "type-$type"
        }

        // 200 ms of 16-bit samples, so that a short stall of the microphone's thread loses nothing.
        const val BUFFER_BYTES = SAMPLE_RATE / 5 * 2
    }
}
