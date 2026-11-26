package dev.kestrel.voice.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MicrophoneThreadTest {
    private val frames = AtomicInteger()
    private val errors = AtomicInteger()

    private fun start(source: FrameSource, onFrame: (FloatArray) -> Unit = { frames.incrementAndGet() }) =
        MicrophoneThread.start("test microphone", source, onFrame, { errors.incrementAndGet() })

    // Runs stop() on another thread, so that a stop that waits for ever fails the test instead of hanging it.
    private fun stopWithin(microphone: MicrophoneThread, millis: Long): Boolean? {
        var ended: Boolean? = null
        val stopper = thread { ended = microphone.stop() }
        stopper.join(millis)
        assertFalse("stop() still waits after $millis ms", stopper.isAlive)
        return ended
    }

    @Test
    fun stopReturnsInTimeWhenTheSourceNeverReturns() {
        // Like AudioRecord.read in the call of 2026-11-26, which went on waiting after AudioRecord.stop().
        val release = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val source = object : FrameSource {
            override fun next(): FloatArray? {
                reading.countDown()
                release.await()
                return FloatArray(FRAME)
            }

            override fun nanosToNextFrame() = 0L
        }
        val microphone = start(source) { frames.incrementAndGet() }
        assertTrue(reading.await(1, TimeUnit.SECONDS))
        try {
            assertEquals(false, stopWithin(microphone, MicrophoneThread.STOP_MILLIS + 1_000))
        } finally {
            release.countDown()
        }
        // Once the source returns, the thread ends without handing its frame on.
        thread { microphone.stop(); ended.countDown() }
        assertTrue(ended.await(1, TimeUnit.SECONDS))
        assertEquals(0, frames.get())
        assertEquals(0, errors.get())
    }

    @Test
    fun stopEndsTheThreadAtOnceWhileNoFrameArrives() {
        // A recording that delivers nothing, as the emulator's input during a call.
        val polls = AtomicInteger()
        val source = object : FrameSource {
            override fun next(): FloatArray? {
                polls.incrementAndGet()
                return null
            }

            override fun nanosToNextFrame() = 20_000_000L
        }
        val microphone = start(source)
        Thread.sleep(100)
        val before = System.nanoTime()
        assertEquals(true, stopWithin(microphone, 1_000))
        assertTrue(System.nanoTime() - before < 100_000_000L)
        // It waited for the frames instead of asking for them without a pause.
        assertTrue("${polls.get()} polls in 100 ms", polls.get() in 2..20)
        assertEquals(0, frames.get())
    }

    @Test
    fun anExceptionFromOnFrameGoesToOnErrorOnceAndEndsTheThread() {
        val source = object : FrameSource {
            override fun next() = FloatArray(FRAME)
            override fun nanosToNextFrame() = 0L
        }
        val microphone = start(source) {
            frames.incrementAndGet()
            throw IllegalArgumentException("pipeline")
        }
        Thread.sleep(100)
        assertEquals(true, stopWithin(microphone, 1_000))
        assertEquals(1, frames.get())
        assertEquals(1, errors.get())
    }

    private companion object {
        const val FRAME = 320
    }
}
