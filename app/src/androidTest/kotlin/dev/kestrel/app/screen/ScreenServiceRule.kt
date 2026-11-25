package dev.kestrel.app.screen

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.listen.ListeningSetting
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.TouchSource
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
 * nothing one test leaves behind affects the next. It replaces the whole list of enabled accessibility services, so
 * other services are off while the tests run, and Android clears their shortcuts; `scripts\connected-test.ps1` saves the
 * accessibility settings before the tests and puts them back afterwards.
 */
class ScreenServiceRule : ExternalResource() {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    lateinit var session: ScreenSession
        private set

    val automation: UiAutomation get() = Companion.automation

    override fun before() = runBlocking { start() }

    private suspend fun start() {
        // A mode that an interrupted run stored would turn Kestrel on when its service connects (section 8.5).
        graph.settings.set(SettingKeys.LISTENING, ListeningSetting())
        // Off as Android's accessibility manager applies it, not only as Kestrel's session ends. In a new test process the
        // session is gone already while the manager may still be binding the service; when the manager reads the setting
        // only after the next write, it never sees the service off, keeps that binding, which never completes, and never
        // connects the service again (plan 7E, Task 8).
        shell("settings delete secure enabled_accessibility_services")
        withTimeout(10_000) { while (managerHasService()) delay(100) }
        withTimeout(10_000) { graph.screen.first { it == null } }
        shell("settings put secure enabled_accessibility_services $SERVICE")
        shell("settings put secure accessibility_enabled 1")
        session = withTimeout(10_000) { graph.screen.filterNotNull().first() }
    }

    /**
     * Whether Android's accessibility manager has Kestrel's service enabled or is binding it. `dumpsys accessibility` names
     * the services by their components on these lines, and the rule reads nothing else of it.
     */
    private fun managerHasService(): Boolean = shell("dumpsys accessibility").lineSequence().map { it.trim() }
        .any { (it.startsWith("Enabled services:") || it.startsWith("Binding services:")) && SERVICE in it }

    /**
     * Runs [block] with [source] as this device's touch rule and branch (design D19 of sub-project 5), and puts back the
     * stored one afterwards. The source is stored, as the debug hook `agent-touch-source` stores it, and a new session
     * starts, because a session reads the stored source only after it is published, and that read would replace a
     * source that a test gave the session itself.
     */
    suspend fun <T> withTouchSource(source: TouchSource, block: suspend () -> T): T {
        val stored = graph.settings.get(SettingKeys.TOUCH_RULE)
        graph.settings.set(SettingKeys.TOUCH_RULE, source)
        try {
            start()
            withTimeout(5_000) { while (session.touchSource != source) delay(20) }
            return block()
        } finally {
            graph.settings.set(SettingKeys.TOUCH_RULE, stored)
            session.touchSource = stored
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

    /**
     * Waits until Android's input dispatcher knows the window with [title], such as "Kestrel touch watcher". A new
     * window gets touches only from then on, a moment after it was added.
     */
    suspend fun awaitInputWindow(title: String) {
        withTimeoutOrNull(5_000) {
            while (shell("dumpsys input").lineSequence().none { title in it && "displayId=" in it }) delay(50)
        } ?: throw AssertionError("Android's input dispatcher did not list the window $title within 5 s")
    }

    /**
     * The bounds of a button with [text] on one of Kestrel's touchable overlays, such as the confirmation card, as
     * another accessibility client sees it.
     */
    fun overlayButton(text: String): Rect? = overlayNode(text) { it.isClickable }?.let { Rect().also(it::getBoundsInScreen) }

    /** Whether one of Kestrel's touchable overlays shows [text] as a whole text, such as a card's question. */
    fun overlayShows(text: String): Boolean = overlayNode(text) { true } != null

    /**
     * Overlays that take no touches are not in the list of interactive windows. Android's search also finds texts that
     * only contain [text], such as a question that contains a button's label, so only a whole text counts; platform
     * buttons show their text in capitals.
     */
    private fun overlayNode(text: String, accept: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (window in automation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            window.root?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { accept(it) && it.text?.toString().equals(text, ignoreCase = true) }
                ?.let { return it }
        }
        return null
    }

    /**
     * Waits until an element that matches [predicate] is on the screen. The failure message lists only the fixture app's
     * labels, because on a phone the status bar's notification icons carry the text of the owner's notifications, such
     * as login codes, and the message goes to logcat and to the test reports.
     */
    suspend fun awaitElement(timeoutMillis: Long = 5_000, predicate: (ScreenElement) -> Boolean): ScreenElement {
        var labels: List<String?> = emptyList()
        return withTimeoutOrNull(timeoutMillis) {
            var found: ScreenElement? = null
            while (found == null) {
                val elements = session.snapshots.current().snapshot.elements
                labels = elements.filter { it.packageName == FIXTURES }.map { it.label }
                found = elements.firstOrNull(predicate)
                if (found == null) delay(100)
            }
            found
        } ?: throw AssertionError("No matching element appeared within $timeoutMillis ms. The fixture app showed $labels")
    }

    companion object {
        const val FIXTURES = "dev.kestrel.testapp"
        const val KESTREL = "dev.kestrel"
        private const val SERVICE = "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService"

        // Connecting UiAutomation without this flag would turn off every accessibility service, Kestrel's included.
        private val automation: UiAutomation by lazy {
            InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
                serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            }
        }
    }
}
