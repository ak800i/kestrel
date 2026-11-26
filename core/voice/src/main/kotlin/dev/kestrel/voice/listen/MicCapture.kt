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
import kotlin.math.abs

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
 * r12). When the earbuds go away, or a call takes the audio over, the phone's microphone records until the next start.
 * [stop] withdraws the request. Without earbuds, when Android refuses them, or when Android lists no microphone for an
 * LE Audio headset, the phone's microphone records as before.
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
    private val level = LevelMeter()

    private val threads = MicrophoneThread("Kestrel microphone") {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    }

    @Volatile
    private var record: AudioRecord? = null

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
     * recording's input, the loudest sample since the last call, in percent, and the RMS levels of [LevelMeter] since then,
     * in dBFS (plan 4D, H7). Types and levels only, never audio. Any thread may call it.
     */
    fun routeReport(): String {
        val recorder = record
        val current = route
        val routed = runCatching { recorder?.routedDevice?.type }.getOrNull()
        return "microphone=${if (recorder != null) "open" else "closed"} holding=${current?.holding == true} " +
            "headset=$headsetKind linkedMillis=${current?.linkedMillis ?: "none"} " +
            "communication=${typeName(audio.communicationDevice?.type)} routed=${typeName(routed)} " +
            "loudest=${loudest.getAndSet(0)} ${level.report()}"
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
     * process, or with one from a stop's `afterEnd` that ran late ([MicrophoneThread]). [onError] itself must not
     * throw. After an error nothing reads the microphone any more, but the recording stays open, and Android keeps
     * showing that Kestrel uses the microphone, until [stop] closes it. [start] and [stop] must not be called from
     * [onFrame] or [onError], because [stop] waits for the thread; they throw [IllegalStateException] there. The caller
     * hands the error to another thread, which then stops the microphone.
     *
     * Throws [SecurityException] without the microphone permission and [IllegalStateException] when the microphone does
     * not start, also while the thread of the last stop still lingers, before anything opens; then it has released
     * everything, also the request for the earbuds, so that a later call can try again.
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
        // Before anything opens: never two threads (MicrophoneThread).
        threads.checkCanStart()
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
        // Reads without blocking (MicrophoneThread): a stop() of the recording does not always wake a blocking read.
        val frames = RecordFrames { samples, offset, count ->
            recorder.read(samples, offset, count, AudioRecord.READ_NON_BLOCKING)
        }
        threads.start(
            source = frames,
            onFrame = { frame ->
                measure(frame)
                onFrame(frame)
            },
            onError = onError,
        )
    }

    /**
     * Stops recording, and waits until the microphone's thread has finished, for at most [MicrophoneThread.STOP_MILLIS];
     * [afterEnd] runs after the last frame ([MicrophoneThread.stop]). It then releases the recording, the callback, the
     * echo canceller and the request for the earbuds, every one also when an earlier one failed, and also a recording
     * whose thread ended with an error. Throws [IllegalStateException] when called from [start]'s callbacks, where the
     * thread would wait for itself. After the release it throws the first failure: [IllegalStateException] when the
     * thread did not end in time, which then lingers until its [onFrame] returns, without another frame, or when this
     * stop did not wait because a thread of an earlier stop still lingers, or else the failure of a step, with the later
     * ones suppressed.
     */
    override fun stop(afterEnd: () -> Unit) {
        checkNotOnTheMicrophonesThread()
        synchronized(this) { close(afterEnd) }
    }

    private fun close(afterEnd: () -> Unit) {
        val recorder = record
        val steps = Steps()
        // The thread first. No read blocks, so it ends within a frame, and it never reads again once it was told to stop,
        // also when it ends only after the recording was released.
        steps.attempt { threads.stop(afterEnd) }

        if (recorder != null) {
            steps.attempt { recorder.stop() }
            steps.attempt { audio.unregisterAudioRecordingCallback(callback) }
            echoCancelling = false
            steps.attempt { canceller?.release() }
            canceller = null
            // Before the release, as research r12 advises, so that the request for the earbuds never outlives the
            // recording.
            steps.attempt { route?.close() }
            route = null
            synchronized(lock) {
                record = null
                silencedState.value = false
            }
            steps.attempt { recorder.release() }
        }
        steps.throwFirst()
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
        check(!threads.isCurrent) { "start() and stop() must not be called from the microphone's thread" }
    }

    // For routeReport: the loudest sample in percent, and the levels.
    private fun measure(frame: FloatArray) {
        var peak = 0f
        for (sample in frame) peak = maxOf(peak, abs(sample))
        loudest.accumulateAndGet((peak * 100).toInt()) { a, b -> maxOf(a, b) }
        level.add(frame)
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

    // Runs each step of a release, also after one failed, and keeps the first failure with the others suppressed.
    private class Steps {
        private var failure: Exception? = null

        fun attempt(step: () -> Unit) {
            try {
                step()
            } catch (e: Exception) {
                val first = failure
                if (first == null) failure = e else first.addSuppressed(e)
            }
        }

        fun throwFirst() {
            failure?.let { throw it }
        }
    }

    private companion object {
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
