package dev.kestrel.app.screen

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.ExternalResource

/**
 * Gives each test a new screen session by turning Kestrel's accessibility service off and on again, so that
 * nothing one test leaves behind affects the next. The tests run only on the test emulator and the A71
 * (design spec, D20), where the service may stay on afterwards.
 */
class ScreenServiceRule : ExternalResource() {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    lateinit var session: ScreenSession
        private set

    val automation: UiAutomation get() = Companion.automation

    override fun before() {
        runBlocking {
            shell("settings delete secure enabled_accessibility_services")
            withTimeout(10_000) { graph.screen.first { it == null } }
            shell("settings put secure enabled_accessibility_services $SERVICE")
            shell("settings put secure accessibility_enabled 1")
            session = withTimeout(10_000) { graph.screen.filterNotNull().first() }
        }
    }

    /** Runs a command as the shell user, which may change secure settings and start other apps' activities. */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    /**
     * Starts an activity of the fixture app and waits until its [title] is on the screen. The fixture app is
     * stopped first, because Android would otherwise only bring its existing task to the front.
     */
    fun open(activity: String, title: String, extras: String = ""): ScreenElement {
        shell("am start -W -S -n $FIXTURES/.$activity $extras")
        return runBlocking { awaitElement { it.label == title } }
    }

    /** Whether Android's window list has a window with [title], such as "Kestrel numbers". */
    fun showsWindow(title: String): Boolean = shell("dumpsys window windows").contains("$title}")

    /** Waits until an element that matches [predicate] is on the screen. */
    suspend fun awaitElement(timeoutMillis: Long = 5_000, predicate: (ScreenElement) -> Boolean): ScreenElement {
        var labels: List<String?> = emptyList()
        return withTimeoutOrNull(timeoutMillis) {
            var found: ScreenElement? = null
            while (found == null) {
                val elements = session.snapshots.current().snapshot.elements
                labels = elements.map { it.label }
                found = elements.firstOrNull(predicate)
                if (found == null) delay(100)
            }
            found
        } ?: throw AssertionError("No matching element appeared within $timeoutMillis ms. The screen showed $labels")
    }

    companion object {
        const val FIXTURES = "dev.kestrel.testapp"
        const val KESTREL = "dev.kestrel"
        private const val SERVICE = "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService"

        // Connecting UiAutomation without this flag would turn off every accessibility service, Kestrel's included.
        private val automation: UiAutomation by lazy {
            InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        }
    }
}
