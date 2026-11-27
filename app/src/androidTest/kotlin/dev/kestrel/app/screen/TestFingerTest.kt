package dev.kestrel.app.screen

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestFingerTest {
    @get:Rule
    val screen = ScreenServiceRule()

    @Test
    fun aTapCarriesTheTimeAtWhichItTouches() = runBlocking {
        TestFinger(screen.automation).use { finger ->
            screen.open("TouchTimesActivity", "Touch times fixture")
            val pad = screen.awaitElement { it.label == "Time pad" }.bounds

            val start = SystemClock.uptimeMillis()
            finger.tap(pad.centerX, pad.centerY)
            val end = SystemClock.uptimeMillis()

            val times = screen.awaitElement { it.label?.startsWith("Down ") == true }.label!!
            val (down, up) = Regex("""Down (\d+), up (\d+)""").matchEntire(times)!!.destructured.toList().map(String::toLong)
            assertTrue("The tap from $start to $end ms touched at $down ms", down in start..start + MAX_LAG_MILLIS)
            assertTrue("The tap from $start to $end ms touched at $down ms and lifted at $up ms", up in down..end + MAX_LAG_MILLIS)
        }
        Unit
    }

    private companion object {
        const val MAX_LAG_MILLIS = 500L
    }
}
