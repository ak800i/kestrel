package dev.kestrel.app.voice

import android.Manifest
import android.app.UiAutomation
import android.media.AudioManager
import android.media.MediaRecorder
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.voice.listen.MicCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    // Revoking a permission would stop the app's process, and the tests with it, so these tests only grant it.
    @Before
    fun grantTheMicrophone() {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun recordsTwentyMillisecondFramesFromTheVoiceRecognitionSource() {
        val frames = CopyOnWriteArrayList<FloatArray>()
        val errors = CopyOnWriteArrayList<Exception>()

        // Android lets an app record only while it is visible or runs a foreground service of the type microphone.
        ActivityScenario.launch(MainActivity::class.java).use {
            val capture = MicCapture(context)
            capture.start(onFrame = { frames += it }, onError = { errors += it })
            try {
                Thread.sleep(1_000)
                // Another app may record too, such as a hotword service, so this looks only at the source Kestrel uses.
                val recordings = context.getSystemService(AudioManager::class.java).activeRecordingConfigurations
                    .filter { it.clientAudioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION }
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
    }

    @Test
    fun theMicrophoneStartsAgainAfterAStop() {
        val frames = CopyOnWriteArrayList<FloatArray>()

        ActivityScenario.launch(MainActivity::class.java).use {
            val capture = MicCapture(context)
            capture.start(onFrame = {}, onError = {})
            capture.stop()
            capture.start(onFrame = { frames += it }, onError = {})
            try {
                Thread.sleep(300)
            } finally {
                capture.stop()
            }
        }

        assertTrue("${frames.size} frames in 0.3 s", frames.isNotEmpty())
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
