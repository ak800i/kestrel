package dev.kestrel.app.voice

import android.Manifest
import android.app.UiAutomation
import android.hardware.SensorPrivacyManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.voice.listen.MicCapture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Kestrel's microphone on the emulator (design spec, section 8.1). */
class MicCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val audio = context.getSystemService(AudioManager::class.java)

    // Revoking a permission would stop the app's process, and the tests with it, so these tests only grant it.
    @Before
    fun grantTheMicrophone() {
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    // Another app may record too, such as a hotword service, so the tests look only at the source that Kestrel uses.
    private fun voiceRecognitionRecordings(): List<AudioRecordingConfiguration> =
        audio.activeRecordingConfigurations.filter { it.clientAudioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) return false
            Thread.sleep(20)
        }
        return true
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Test
    fun recordsTwentyMillisecondFramesFromTheVoiceRecognitionSource() {
        val frames = CopyOnWriteArrayList<FloatArray>()
        val errors = CopyOnWriteArrayList<Exception>()
        val before = voiceRecognitionRecordings().map { it.clientAudioSessionId }.toSet()
        var kestrels = emptySet<Int>()

        // Android lets an app record only while it is visible or runs a foreground service of the type microphone.
        ActivityScenario.launch(MainActivity::class.java).use {
            val capture = MicCapture(context)
            capture.start(onFrame = { frames += it }, onError = { errors += it })
            try {
                Thread.sleep(1_000)
                // Kestrel's recording is the one that was not there before the start.
                val recordings = voiceRecognitionRecordings().filter { it.clientAudioSessionId !in before }
                kestrels = recordings.map { it.clientAudioSessionId }.toSet()
                assertTrue("No recording from the voice recognition source", recordings.isNotEmpty())
                assertFalse(recordings.any { it.isClientSilenced })
                assertFalse(capture.silenced.value)
                VoiceTests.log("Microphone: ${frames.size} frames in 1 s, echo canceller ${capture.echoCancelling}")
            } finally {
                capture.stop()
            }
        }

        assertEquals(emptyList<Exception>(), errors)
        assertTrue("${frames.size} frames in 1 s", frames.size in 30..70)
        assertTrue(frames.all { it.size == 320 })
        // stop() released the microphone: Android's report that the recording ended arrives shortly after it.
        assertTrue(
            "The recording is still active after stop()",
            waitUntil(2_000) { voiceRecognitionRecordings().none { it.clientAudioSessionId in kestrels } },
        )
    }

    @Test
    fun theMicrophoneStartsAgainAfterAStop() {
        val framed = CountDownLatch(1)

        ActivityScenario.launch(MainActivity::class.java).use {
            val capture = MicCapture(context)
            capture.start(onFrame = {}, onError = {})
            capture.stop()
            capture.start(onFrame = { framed.countDown() }, onError = {})
            try {
                assertTrue("No frame within 2 s of the second start", framed.await(2, TimeUnit.SECONDS))
            } finally {
                capture.stop()
            }
        }
    }

    @Test
    fun stopFromOnFrameThrowsInsteadOfWaitingForEver() {
        val errors = CopyOnWriteArrayList<Exception>()
        val reported = CountDownLatch(1)

        ActivityScenario.launch(MainActivity::class.java).use {
            val capture = MicCapture(context)
            capture.start(
                onFrame = { capture.stop() },
                onError = {
                    errors += it
                    reported.countDown()
                },
            )
            try {
                assertTrue("onError was not called", reported.await(2, TimeUnit.SECONDS))
            } finally {
                capture.stop()
            }
        }

        assertTrue("${errors.single()}", errors.single() is IllegalStateException)
    }

    @Test
    fun silencedFollowsTheMicrophonesPrivacyToggle() {
        VoiceTests.assumeEmulator()
        assumeTrue(
            "This device has no privacy toggle for the microphone",
            context.getSystemService(SensorPrivacyManager::class.java)
                .supportsSensorToggle(SensorPrivacyManager.Sensors.MICROPHONE),
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            val capture = MicCapture(context)
            capture.start(onFrame = {}, onError = {})
            var switched = false
            try {
                // A toggle that someone switched on silences the new recording at once. The test leaves such a toggle
                // alone, because Android offers no public way to read the toggle, and it switches off only its own.
                assertFalse(
                    "The recording is silenced before the test switches the toggle; is the microphone's privacy toggle on?",
                    waitUntil(1_000) { capture.silenced.value },
                )
                runBlocking {
                    // Switched on after the start, so that Android silences the running recording instead of refusing it.
                    switched = true
                    shell("cmd sensor_privacy enable 0 microphone")
                    withTimeout(5_000) { capture.silenced.first { it } }
                    shell("cmd sensor_privacy disable 0 microphone")
                    withTimeout(5_000) { capture.silenced.first { !it } }
                }
            } finally {
                // A toggle that stayed on would silence every later recording on this device.
                if (switched) shell("cmd sensor_privacy disable 0 microphone")
                capture.stop()
            }
        }
    }

    @Test
    fun anExceptionFromOnFrameGoesToOnErrorAndEndsTheReading() {
        val failure = IllegalStateException("The pipeline is closed")
        val frames = AtomicInteger()
        val errors = CopyOnWriteArrayList<Exception>()
        val reported = CountDownLatch(1)
        // An exception that escaped the microphone's thread would end the app's process, so this test records it.
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }

        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val capture = MicCapture(context)
                capture.start(
                    onFrame = {
                        frames.incrementAndGet()
                        throw failure
                    },
                    onError = {
                        errors += it
                        reported.countDown()
                    },
                )
                try {
                    assertTrue("onError was not called", reported.await(2, TimeUnit.SECONDS))
                    // Long enough for several more frames, had the reading gone on.
                    Thread.sleep(200)
                } finally {
                    capture.stop()
                }
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }

        assertEquals(emptyList<Throwable>(), uncaught)
        assertSame(failure, errors.single())
        assertEquals(1, frames.get())
    }
}
