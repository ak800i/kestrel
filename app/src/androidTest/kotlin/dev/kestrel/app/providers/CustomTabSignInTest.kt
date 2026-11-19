package dev.kestrel.app.providers

import android.content.Intent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.privacy.network.LoopbackServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The Custom Tab of browser sign-in with the device's own Chrome (design D10 and D19; prototype P5): the tab opens in
 * Kestrel's task, SignInService runs in the foreground while the tab is in front, the loopback server receives the
 * callback, and the settings come back on top. Android holds an instrumented process at the foreground-service level
 * itself, so the test checks the service rather than Kestrel's importance; the freezer and the return to the front of a
 * real sign-in are checked by P5 and by the session's sign-in on the A71. The redirect case through postman-echo.com,
 * which repeats P5's q1 and q3, runs only with the instrumentation argument `kestrelRedirectCheck=true`, so that normal
 * runs never depend on an outside site. Chrome must have finished its first-run screens.
 */
@RunWith(AndroidJUnit4::class)
class CustomTabSignInTest {
    // Started with an intent like SignInService's return to Kestrel, without the launcher's action: MainActivity takes each
    // new intent as its own, and ActivityScenario follows the activity, and closes it after the test, only while its intent
    // has the action of the intent that started it.
    val activity = ActivityScenarioRule<MainActivity>(Intent(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java))

    /** The redirect case is skipped before MainActivity starts, so that a skipped test does nothing on the device. */
    private val redirectOnlyWhenAsked = TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                if (description.methodName == REDIRECT_TEST) {
                    assumeTrue("Runs only with kestrelRedirectCheck=true", InstrumentationRegistry.getArguments().getString("kestrelRedirectCheck") == "true")
                }
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(redirectOnlyWhenAsked).around(activity)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val providers get() = (context.applicationContext as dev.kestrel.app.KestrelApplication).graph.providers
    private val executor = Executors.newSingleThreadExecutor()
    private val sessions = mutableListOf<LoopbackServer.Session>()

    /** Whether a test opened a tab, so that Chrome is ended only then. */
    private var tabOpened = false

    @Before
    fun needsChrome() {
        assertNotNull("Chrome with its Custom Tabs service is needed on this device", CustomTabs.chrome(context))
    }

    /** Each test starts from Idle, whatever an earlier test in the same process left in the shared graph. */
    @Before
    fun startIdle() {
        providers.cancelSignIn()
    }

    @After
    fun cleanUp() {
        sessions.forEach { it.close() }
        executor.shutdownNow()
        SignInService.stop(context)
        if (tabOpened) shell("am force-stop com.android.chrome")
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).let { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes().decodeToString() }.also { descriptor.close() }
        }

    /** The component of the top resumed activity, as `package/.Class`, without anything that the screen shows. */
    private fun topActivity(): String =
        shell("dumpsys activity activities").lines().firstOrNull { "topResumedActivity" in it }
            ?.let { Regex("""ActivityRecord\{\S+ u\d+ (\S+/\S+)""").find(it)?.groupValues?.get(1) } ?: "none"

    /**
     * The loopback callback's parameters, waited for [seconds]: a cold Chrome, as on the first run after a boot, can take
     * long. A timeout names the activity in front, so that a failure shows whether Chrome was stuck on a page or a dialog.
     */
    private fun callbackOf(callback: Future<Map<String, String>?>, seconds: Long): Map<String, String>? =
        try {
            callback.get(seconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            fail("No callback within $seconds s; the activity in front was ${topActivity()}")
            null
        }

    /** The task of each activity record in `dumpsys activity activities`, by its component. */
    private fun tasks(): Map<String, String> =
        Regex("""ActivityRecord\{\S+ u\d+ (\S+/\S+) t(\d+)""").findAll(shell("dumpsys activity activities"))
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(250)
        }
        fail("$what did not happen within 20 s")
    }

    private fun waitForTab() = waitFor("Chrome's Custom Tab") {
        val dump = shell("dumpsys activity activities")
        if ("FirstRunActivity" in dump) fail("Chrome shows its first-run screens: open Chrome on this device once and finish them")
        dump.lines().any { "topResumedActivity" in it && CUSTOM_TAB in it }
    }

    private fun open(url: String) {
        tabOpened = true
        activity.scenario.onActivity { assertTrue("no browser", CustomTabs.open(it, url)) }
    }

    private fun listen(ipv6: Boolean, port: Int? = null): Pair<LoopbackServer.Session, java.util.concurrent.Future<Map<String, String>?>> {
        val session = LoopbackServer().open(ipv6, port, "/auth/callback") ?: error("the loopback port is busy")
        sessions += session
        // The server outlives the test's wait for the callback, which starts later.
        return session to executor.submit<Map<String, String>?> { session.await("test-state", 120_000) }
    }

    @Test
    fun theTabOpensInKestrelsTaskKestrelStaysInTheForegroundAndTheSettingsComeBack() {
        SignInService.start(context, "Test")
        val (session, callback) = listen(ipv6 = false)

        open("http://127.0.0.1:${session.port}/auth/callback?code=test-code&state=test-state")
        waitForTab()

        val tasks = tasks()
        assertNotNull("Chrome's Custom Tab in dumpsys", tasks[CUSTOM_TAB])
        assertEquals("the Custom Tab is in Kestrel's task", tasks[MAIN], tasks[CUSTOM_TAB])
        // Android holds an instrumented process at the foreground-service level itself, so the test checks the service.
        val service = shell("dumpsys activity services dev.kestrel/.app.providers.SignInService")
        assertTrue("SignInService runs in the foreground", "isForeground=true" in service)
        assertEquals("test-code", callbackOf(callback, 90)?.get("code"))

        SignInService.returnToKestrel(context)
        waitFor("Kestrel's settings on top") { shell("dumpsys activity activities").lines().any { "topResumedActivity" in it && MAIN in it } }
    }

    @Test
    fun aSignInThatEndsBeforeTheServiceSawItWaitStopsTheService() {
        val service = { shell("dumpsys activity services dev.kestrel/.app.providers.SignInService") }
        SignInService.start(context, "Test")
        waitFor("SignInService in the foreground") { "isForeground=true" in service() }
        // A GHE.com tenant that is not one fails at once, without a request (design D10).
        providers.startSignIn("github-copilot", "github-copilot-enterprise", tenant = "not a tenant")
        try {
            waitFor("SignInService to stop") { "isForeground=true" !in service() }
        } finally {
            providers.cancelSignIn()
        }
    }

    @Test
    fun aStopThatComesBeforeANewStartLeavesTheServiceToTheNewStart() {
        val service = { shell("dumpsys activity services dev.kestrel/.app.providers.SignInService") }
        SignInService.start(context, "Test")
        waitFor("SignInService in the foreground") { "isForeground=true" in service() }

        // As a cancel and a new sign-in right after it: cancelSignIn asks the service to stop, then startSignIn starts it.
        SignInService.stop(context)
        SignInService.start(context, "Test")

        // Android lists a start until the service has handled it, so after this wait both commands have been handled; a
        // stop that ended the service would leave no record of it.
        waitFor("SignInService to handle the stop and the new start") {
            val dump = service()
            "Pending Starts" !in dump && "Delivered Starts" !in dump
        }
        repeat(8) {
            assertTrue("SignInService runs in the foreground after the new start", "isForeground=true" in service())
            Thread.sleep(250)
        }
    }

    // The rule redirectOnlyWhenAsked skips this test unless kestrelRedirectCheck=true.
    @Test
    fun aRedirectFromAPublicPageReachesTheLoopbackServerWithoutAPrompt() {
        SignInService.start(context, "Test")
        for ((ipv6, host) in listOf(false to "127.0.0.1", true to "localhost")) {
            val (session, callback) = listen(ipv6)
            val target = "http://$host:${session.port}/auth/callback?code=test-code&state=test-state"
            open("https://postman-echo.com/redirect-to?url=" + URLEncoder.encode(target, "UTF-8"))
            // Chrome's first-run screens give a clear message here too, not a timeout.
            waitForTab()

            assertEquals("the redirect to $host", "test-code", callbackOf(callback, 90)?.get("code"))
            shell("am force-stop com.android.chrome")
        }
    }

    private companion object {
        const val MAIN = "dev.kestrel/.app.MainActivity"
        const val CUSTOM_TAB = "com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity"
        const val REDIRECT_TEST = "aRedirectFromAPublicPageReachesTheLoopbackServerWithoutAPrompt"
    }
}
