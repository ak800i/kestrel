package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TouchWatcherTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    @Test
    fun aFingerOnAnotherAppIsReportedAndStillReachesIt() = runBlocking {
        TestFinger(screen.automation).use { finger ->
            screen.open("ViewsActivity", "Views fixture")
            val target = screen.awaitElement { it.label == "Touch target" }.bounds
            session.startTouchWatcher()
            screen.awaitInputWindow(WATCHER)
            val touch = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3_000) { session.ownerTouches.first() } }

            finger.tap(target.centerX, target.centerY)

            touch.await()
            screen.awaitElement { it.label == "Touched target" }
        }
        Unit
    }

    @Test
    fun kestrelsOwnGesturesAreNotReported() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val target = screen.awaitElement { it.label == "Touch target" }.bounds
        session.startTouchWatcher()
        screen.awaitInputWindow(WATCHER)
        val touches = mutableListOf<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { session.ownerTouches.collect { touches += it } }

        session.actions.tapAt(target.centerX, target.centerY)
        screen.awaitElement { it.label == "Touched target" }
        delay(500)

        collector.cancel()
        assertTrue("touches: ${touches.size}", touches.isEmpty())
    }

    @Test
    fun aFingerDuringKestrelsGestureIsReported() = runBlocking {
        TestFinger(screen.automation).use { finger ->
            screen.open("GesturesActivity", "Gestures fixture")
            val pad = screen.awaitElement { it.label == "Gesture pad" }.bounds
            session.startTouchWatcher()
            screen.awaitInputWindow(WATCHER)
            val touches = Channel<Unit>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { session.ownerTouches.collect { touches.send(it) } }
            val drag = async { session.actions.drag(pad.centerX - 300, pad.centerY + 200, pad.centerX + 300, pad.centerY + 200) }
            delay(400)
            assertTrue("Kestrel's own drag was reported", touches.tryReceive().isFailure)

            finger.tap(pad.centerX, pad.centerY - 300)

            withTimeout(3_000) { touches.receive() }
            drag.await()
            collector.cancel()
        }
        Unit
    }

    private companion object {
        const val WATCHER = "Kestrel touch watcher"
    }
}
