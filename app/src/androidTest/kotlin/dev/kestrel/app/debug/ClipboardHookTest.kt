package dev.kestrel.app.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The clipboard hook of plan 4D. It runs only on an emulator and puts back the clip that the device had, so that no test
 * ever touches the owner's clipboard; it is not in dev.kestrel.app.voice, which scripts\voice-test.ps1 runs on a phone.
 */
class ClipboardHookTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = (context.applicationContext as KestrelApplication).graph

    @Test
    fun theClipboardIsSavedWhileKestrelIsInFrontAndPutBackAfterACheckChangedIt() {
        assumeTrue("Runs only on an emulator", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val clipboard = context.getSystemService(ClipboardManager::class.java)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Android lets Kestrel read the clipboard only while its window has the input focus.
            val focused = waitUntil(5_000) {
                var focus = false
                scenario.onActivity { focus = it.hasWindowFocus() }
                graph.timeline.settingsInFront && focus
            }
            assertTrue("Kestrel's settings do not have the focus", focused)
            var original: ClipData? = null
            scenario.onActivity { original = clipboard.primaryClip }
            try {
                scenario.onActivity { clipboard.setPrimaryClip(ClipData.newPlainText("test", "The test's first clip")) }
                assertEquals("clip=text saved=true", hook("save"))
                // A second save keeps the first copy.
                assertEquals("clip=held saved=false", hook("save"))
                // As a check's "copy" does.
                scenario.onActivity { clipboard.setPrimaryClip(ClipData.newPlainText("test", "The check's text")) }

                assertEquals("restored=true", hook("restore"))
                var text: String? = null
                scenario.onActivity { text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
                assertEquals("The test's first clip", text)
                assertEquals("restored=false", hook("restore"))
            } finally {
                scenario.onActivity {
                    val clip = original
                    if (clip != null) clipboard.setPrimaryClip(clip) else clipboard.clearPrimaryClip()
                }
            }
        }
    }

    private fun hook(flag: String): String =
        runBlocking { TestHooks(graph, context).run("CLIPBOARD", Bundle().apply { putBoolean(flag, true) }) }.text

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
