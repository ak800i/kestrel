package dev.kestrel.app.voice

import android.Manifest
import android.app.UiAutomation
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.AppGraph
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * What the voice tests share: the app's own engines, and synthetic speech in Kestrel's voice, which stands in for the
 * owner's voice until plan 2C adds recordings (design spec, section 16.4). The tests skip themselves when the models are
 * missing; scripts\voice-test.ps1 copies the models first and fails on a skipped test.
 */
internal object VoiceTests {
    val graph: AppGraph
        get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    /** The microphone delivers 20 ms frames (design spec, section 8.1). */
    const val FRAME = SAMPLE_RATE / 50

    private const val PACKAGE = "dev.kestrel"

    // How dumpsys names ListeningService.
    private const val LISTENING_SERVICE = "dev.kestrel/.app.listen.ListeningService"

    // Kept open for the whole test run, because loading the voice takes more than a second.
    private var synthesizer: Synthesizer? = null
    private val spoken = mutableMapOf<String, FloatArray>()

    // Kestrel's own microphone, which [stopListening] puts back after a test replaced it.
    private var realMicrophones: (() -> Microphone)? = null

    private val automation: UiAutomation
        get() = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    /** Skips the test unless every model of [engines] is on the device and intact. */
    fun assumeModels(vararg engines: Engine) {
        val notReady = runBlocking { graph.models.check() }.filter { it.model.engine in engines && !it.ready }
        assumeTrue("Models missing or damaged: ${notReady.map { it.model.id }}. Run scripts\\voice-test.ps1.", notReady.isEmpty())
    }

    /**
     * Skips the test on a phone. A test that changes a setting of the whole device runs only on an emulator, because a
     * test process that dies would leave the setting changed.
     */
    fun assumeEmulator() = assumeTrue("Runs only on an emulator", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")

    /** [text] in Kestrel's voice without variation, so that it is the same in every run, at 16 kHz. */
    @Synchronized
    fun voice(text: String): FloatArray = spoken.getOrPut(text) {
        val active = synthesizer ?: runBlocking { graph.voice.synthesizer(VoiceOptions(variation = 0f)) }.also { synthesizer = it }
        resample(active.synthesize(text), active.sampleRate, SAMPLE_RATE)
    }

    /** [voice] with 0.5 s of silence before it and 1 s after it, as the microphone hears an utterance. */
    fun speech(text: String): FloatArray = silence(0.5f) + voice(text) + silence(1f)

    fun silence(seconds: Float) = FloatArray((seconds * SAMPLE_RATE).toInt())

    /** Feeds [samples] to [feed] in 20 ms frames, as the microphone delivers them, and collects what it returns. */
    fun <T> frames(samples: FloatArray, feed: (FloatArray) -> List<T>): List<T> =
        (samples.indices step FRAME).flatMap { start -> feed(samples.copyOfRange(start, minOf(start + FRAME, samples.size))) }

    /** Logs a measurement; scripts\voice-test.ps1 prints these lines after the tests. Never log what was said. */
    fun log(message: String) {
        Log.i("KestrelVoiceTest", message)
    }

    /** The memory that the app's process uses (PSS), in MB, as the design spec's memory target counts it (section 5.3). */
    fun pssMegabytes(): Long = Debug.getPss() / 1024

    /** Runs [command] in the device's shell and returns its output. The command is split at spaces; quotes do not group. */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    /** Waits up to [timeoutMillis] until [condition] is true, and returns whether it became true. */
    fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) return false
            Thread.sleep(20)
        }
        return true
    }

    /**
     * Grants what listening needs: the microphone, without which Android refuses a foreground service of the type
     * microphone, and notifications, so that a test can read the service's notification. Like the other device tests,
     * the tests never revoke a permission, because that would end Kestrel's process and the tests with it.
     */
    fun grantListeningPermissions() {
        automation.grantRuntimePermission(PACKAGE, Manifest.permission.RECORD_AUDIO)
        automation.grantRuntimePermission(PACKAGE, Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Whether Kestrel's ListeningService runs. */
    fun listeningServiceRuns(): Boolean = shell("dumpsys activity services $PACKAGE").contains(LISTENING_SERVICE)

    /** Makes [microphone] the microphone of Kestrel's next voice session, until [stopListening]. */
    fun useMicrophone(microphone: Microphone) {
        val listening = graph.listening
        if (realMicrophones == null) realMicrophones = listening.microphones
        listening.microphones = { microphone }
    }

    /**
     * Turns listening on with [microphone] as Kestrel's microphone, and waits until the voice session is open and the
     * microphone records. Kestrel must be off, and in front, so that Android lets it start a foreground service of the
     * type microphone.
     */
    fun startListening(microphone: ClipMicrophone) {
        val listening = graph.listening
        check(listening.state.value.mode == Mode.OFF) { "Listening is already on" }
        useMicrophone(microphone)
        listening.send(Event.Toggle)
        // Loading the models takes several seconds.
        check(waitUntil(60_000) { listening.sessionOpen.value }) {
            "The voice session did not open; the mode is ${listening.state.value.mode}"
        }
        check(waitUntil(5_000) { microphone.recording }) { "The microphone does not record" }
    }

    /**
     * Turns Kestrel off, and waits until its voice session has closed, with every engine, and ListeningService has
     * stopped. Puts back Kestrel's own microphone.
     */
    fun stopListening() {
        val listening = graph.listening
        listening.send(Event.TurnOff)
        try {
            check(waitUntil(30_000) { listening.state.value.mode == Mode.OFF && !listening.sessionOpen.value }) {
                "The voice session did not close"
            }
            check(waitUntil(10_000) { !listeningServiceRuns() }) { "ListeningService still runs" }
        } finally {
            realMicrophones?.let { listening.microphones = it }
            realMicrophones = null
        }
    }

    // Linear interpolation is enough for test speech; Kestrel's microphone records at 16 kHz itself.
    private fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        val count = (samples.size.toLong() * to / from).toInt()
        return FloatArray(count) { index ->
            val position = index.toDouble() * from / to
            val before = position.toInt()
            val after = minOf(before + 1, samples.size - 1)
            val fraction = (position - before).toFloat()
            samples[before] + (samples[after] - samples[before]) * fraction
        }
    }
}
