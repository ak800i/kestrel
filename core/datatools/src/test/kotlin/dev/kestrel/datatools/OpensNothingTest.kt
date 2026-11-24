package dev.kestrel.datatools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The data tools open nothing themselves (plan 6B-basefix): a tool that opens an app's screen returns the intent, and the
 * agent's launch check opens it. The port holds a Context for its providers, so this test keeps every way of starting an
 * activity out of the module's code.
 */
class OpensNothingTest {
    @Test
    fun noCodeOfTheDataToolsStartsAnActivity() {
        val sources = File("src/main/kotlin").walk().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the test runs in the module's folder", sources.size > 10)

        val starting = sources.filter { file -> STARTS.any { it in file.readText() } }.map { it.name }

        assertEquals(emptyList<String>(), starting)
    }

    private companion object {
        val STARTS = listOf("startActivity", "startActivities", "openIntent", "PendingIntent", "startService", "startForegroundService")
    }
}
