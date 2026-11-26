package dev.kestrel.voice.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

class MicrophoneThreadTest {
    private val prepares = AtomicInteger()
    private val frames = AtomicInteger()
    private val errors = CopyOnWriteArrayList<Exception>()
    private val unreported = CopyOnWriteArrayList<Exception>()
    private val logged = CountDownLatch(1)

    private fun microphone(stopMillis: Long = STOP_MILLIS, park: (Long) -> Unit = LockSupport::parkNanos) =
        MicrophoneThread("test microphone", { prepares.incrementAndGet() }, stopMillis, park) {
            unreported += it
            logged.countDown()
        }

    private fun MicrophoneThread.start(source: FrameSource, onFrame: (FloatArray) -> Unit = { frames.incrementAndGet() }) =
        start(source, onFrame) { errors += it }

    // Runs stop() on another thread, so that a stop that waits for ever fails the test instead of hanging it. Returns
    // the message of the stop's IllegalStateException, or null when the thread ended.
    private fun MicrophoneThread.stopWithin(millis: Long, afterEnd: () -> Unit = {}): String? {
        var failure: Throwable? = null
        val stopper = thread { failure = runCatching { stop(afterEnd) }.exceptionOrNull() }
        stopper.join(millis)
        assertFalse("stop() still waits after $millis ms", stopper.isAlive)
        return failure?.let { (it as IllegalStateException).message }
    }

    // Like AudioRecord.read in the call of 2026-11-26, which went on waiting after AudioRecord.stop(): it returns only
    // when the test releases it, with a frame or with [failure].
    private class StuckSource(private val failure: Exception? = null) : FrameSource {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun next(): FloatArray {
            reading.countDown()
            release.await()
            failure?.let { throw it }
            return FloatArray(FRAME)
        }

        override fun nanosToNextFrame() = 0L
    }

    @Test
    fun stopReturnsInTimeWhenTheSourceNeverReturns() {
        val source = StuckSource()
        val microphone = microphone()
        val ended = CountDownLatch(1)
        microphone.start(source)
        assertTrue(source.reading.await(5, TimeUnit.SECONDS))
        try {
            assertEquals(TIMED_OUT, microphone.stopWithin(STOP_MILLIS + 2_000) { ended.countDown() })
        } finally {
            source.release.countDown()
        }
        // Once the source returns, the thread ends without handing its frame on.
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(0, frames.get())
        assertEquals(emptyList<Exception>(), errors)
    }

    @Test
    fun aLingeringThreadRefusesAStartAndRunsEachStopsAfterEndOnceWhenItEnds() {
        val source = StuckSource()
        val microphone = microphone()
        microphone.start(source)
        assertTrue(source.reading.await(5, TimeUnit.SECONDS))
        val firstEnds = AtomicInteger()
        val secondEnds = AtomicInteger()
        val bothRan = CountDownLatch(2)
        val ranOn = CopyOnWriteArrayList<String>()
        try {
            assertEquals(TIMED_OUT, microphone.stopWithin(STOP_MILLIS + 2_000) {
                ranOn += Thread.currentThread().name
                firstEnds.incrementAndGet()
                bothRan.countDown()
            })
            val refused = runCatching { microphone.start(StuckSource()) }.exceptionOrNull()
            assertTrue("$refused", refused is MicrophoneBusyException)
            // A second stop does not wait for the stuck thread again, says so, and starts nothing.
            assertEquals(DID_NOT_WAIT, microphone.stopWithin(STOP_MILLIS / 2) {
                secondEnds.incrementAndGet()
                bothRan.countDown()
            })
            assertEquals(0, firstEnds.get() + secondEnds.get())
            assertEquals(1, prepares.get())
        } finally {
            source.release.countDown()
        }
        assertTrue(bothRan.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("test microphone"), ranOn)
        // Once it has ended, a start runs a new thread, and nothing of the old one runs again.
        val next = CountDownLatch(1)
        microphone.start(source) { next.countDown() }
        assertTrue(next.await(5, TimeUnit.SECONDS))
        assertNull(microphone.stopWithin(STOP_MILLIS + 2_000))
        assertEquals(1, firstEnds.get())
        assertEquals(1, secondEnds.get())
        assertEquals(2, prepares.get())
        assertEquals(emptyList<Exception>(), errors)
    }

    @Test
    fun aFailureAfterTheOneThatOnErrorGotIsLoggedInstead() {
        val source = StuckSource()
        val microphone = microphone()
        microphone.start(source)
        assertTrue(source.reading.await(5, TimeUnit.SECONDS))
        val first = IllegalArgumentException("first pause")
        val second = IllegalArgumentException("second pause")
        try {
            assertEquals(TIMED_OUT, microphone.stopWithin(STOP_MILLIS + 2_000) { throw first })
            assertEquals(DID_NOT_WAIT, microphone.stopWithin(STOP_MILLIS / 2) { throw second })
        } finally {
            source.release.countDown()
        }
        assertTrue(logged.await(5, TimeUnit.SECONDS))
        assertEquals(listOf<Exception>(first), errors)
        assertEquals(listOf<Exception>(second), unreported)
    }

    @Test
    fun aFailureOfTheSourceAfterTheStopIsNotReported() {
        // A read of the recording that the stop released.
        val source = StuckSource(failure = IllegalStateException("The microphone failed with error -3"))
        val microphone = microphone()
        val ended = CountDownLatch(1)
        microphone.start(source)
        assertTrue(source.reading.await(5, TimeUnit.SECONDS))
        try {
            assertEquals(TIMED_OUT, microphone.stopWithin(STOP_MILLIS + 2_000) { ended.countDown() })
        } finally {
            source.release.countDown()
        }
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(emptyList<Exception>(), errors)
    }

    @Test
    fun whileNoFrameIsThereTheThreadWaitsAsLongAsTheSourceSaysWithinOneMillisecondAndOneFrame() {
        // A recording that delivers nothing, as the emulator's input during a call.
        val waits = CopyOnWriteArrayList<Long>()
        val threeWaits = CountDownLatch(3)
        val untilNext = longArrayOf(0L, 5_000_000L, 1_000_000_000L)
        val polls = AtomicInteger()
        val source = object : FrameSource {
            override fun next(): FloatArray? = null.also { polls.incrementAndGet() }
            override fun nanosToNextFrame() = untilNext[minOf(polls.get(), untilNext.size) - 1]
        }
        val microphone = microphone(park = { nanos ->
            waits += nanos
            threeWaits.countDown()
            LockSupport.parkNanos(nanos)
        })
        microphone.start(source)
        assertTrue(threeWaits.await(5, TimeUnit.SECONDS))
        assertNull(microphone.stopWithin(STOP_MILLIS + 2_000))
        assertEquals(listOf(1_000_000L, 5_000_000L, 20_000_000L), waits.take(3))
        assertEquals(0, frames.get())
    }

    @Test
    fun anExceptionFromOnFrameGoesToOnErrorOnceAndEndsTheThread() {
        val source = object : FrameSource {
            override fun next() = FloatArray(FRAME)
            override fun nanosToNextFrame() = 0L
        }
        val failed = CountDownLatch(1)
        val microphone = microphone()
        microphone.start(source, {
            frames.incrementAndGet()
            throw IllegalArgumentException("pipeline")
        }) {
            errors += it
            failed.countDown()
        }
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertNull(microphone.stopWithin(STOP_MILLIS + 2_000))
        assertEquals(1, frames.get())
        assertEquals(1, errors.size)
    }

    private companion object {
        const val FRAME = 320

        // Short, so that the cases with a stuck source take little time; the product's limit is MicrophoneThread.STOP_MILLIS.
        const val STOP_MILLIS = 200L
        const val TIMED_OUT = "The microphone's thread did not end within $STOP_MILLIS ms of the stop"
        const val DID_NOT_WAIT = "The stop did not wait: the microphone's thread from an earlier stop has not ended yet"
    }
}
