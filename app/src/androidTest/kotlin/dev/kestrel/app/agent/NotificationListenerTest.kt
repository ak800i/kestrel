package dev.kestrel.app.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.orchestrator.TaskPhase
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.datatools.AndroidDataPort
import dev.kestrel.datatools.notifications.KestrelNotificationListener
import dev.kestrel.safety.HandOverKind
import dev.kestrel.screen.model.ScreenTree
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Kestrel's notification listener with `:testapp`'s own notification, "Fixture reminder" (design D9 and D12 of
 * sub-project 5): with notification access, `list_notifications` reads it; without it, a task hands over to Android's
 * page for the listener, and the hand-over ends by itself once access is on and Android's page has left the front, so
 * that the task reads the notification. The test turns access on and off through the shell, as an owner would on
 * Android's page, leaves the page with Back, and puts back the state that it found, also after a failure.
 */
@RunWith(AndroidJUnit4::class)
class NotificationListenerTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val component by lazy { KestrelNotificationListener.component(context).flattenToString() }
    private var hadAccess = false

    @Before
    fun start() {
        hadAccess = KestrelNotificationListener.hasAccess(context)
        AgentTests.clearPresses(screen)
        screen.shell("pm grant $FIXTURES android.permission.POST_NOTIFICATIONS")
    }

    @After
    fun stop() {
        try {
            AgentTests.stop()
        } finally {
            AgentTests.clearPresses(screen)
            screen.shell("am start -W -n $FIXTURES/.NotificationActivity --ez cancel true")
            screen.shell("cmd notification ${if (hadAccess) "allow_listener" else "disallow_listener"} $component")
            screen.shell("am force-stop $FIXTURES")
        }
    }

    private fun access(on: Boolean) {
        screen.shell("cmd notification ${if (on) "allow_listener" else "disallow_listener"} $component")
        check(VoiceTests.waitUntil(10_000) { KestrelNotificationListener.connected.value == on }) { "The listener did not ${if (on) "connect" else "disconnect"}" }
    }

    private fun frontPackage(): String? = runBlocking { ScreenTree.frontWindow(screen.session.snapshots.current().snapshot)?.packageName }

    private fun postFixture() {
        screen.shell("am start -W -n $FIXTURES/.NotificationActivity")
    }

    @Test
    fun listNotificationsReadsTheFixturesNotification() = runBlocking {
        access(true)
        postFixture()
        var result: DataResult? = null
        val port = AndroidDataPort(context, open = { false }, isAgentFree = { false })

        val found = VoiceTests.waitUntil(10_000) {
            result = runBlocking { port.listNotifications() }
            (result as? DataResult.Ok)?.lines.orEmpty().any { line -> line.fields.any { it.name == "title" && it.value == FIXTURE_TITLE } }
        }

        assertTrue("list_notifications gave ${result}", found)
    }

    @Test
    fun withoutAccessATaskHandsOverAndReadsTheNotificationOnceAccessIsOn() = runBlocking {
        access(false)
        // Before the notification: open's `am start -S` force-stops the fixture app, which takes its notifications away.
        screen.open("MessengerActivity", "Chats")
        postFixture()
        AgentTests.start()
        val model = ScriptedModel(listOf(
            call("list_notifications"),
            call("fail", null, null, null, "reason" to "The test stops after the notifications."),
        ))

        val task = AgentTests.run("read my notifications", model)
        check(VoiceTests.waitUntil(30_000) {
            AgentTests.graph.agent.handOver(task) == HandOverKind.ACCESS && AgentTests.graph.listening.state.value.taskPhase == TaskPhase.HANDED_OVER
        }) { "The task did not hand over to Android's page for notification access" }
        check(VoiceTests.waitUntil(10_000) { frontPackage() == SETTINGS }) { "Android's page for notification access did not open" }
        access(true)
        // While Android's page is in front, the hand-over stays open, so that the owner's Back or Home there does not
        // cancel the task (final review of plan 5B, part B, M1).
        Thread.sleep(2_000)
        assertEquals(TaskPhase.HANDED_OVER, AgentTests.graph.listening.state.value.taskPhase)
        screen.shell("input keyevent KEYCODE_BACK")
        val ended = AgentTests.ended(task)

        assertEquals(EndReason.FAILED_BY_MODEL, ended.report.end)
        assertTrue(AgentTests.texts(model).contains(FIXTURE_TITLE))
        assertEquals(emptyList<String>(), AgentTests.presses(screen))
    }

    private companion object {
        // NotificationActivity.TITLE of :testapp, which this test cannot import.
        const val FIXTURE_TITLE = "Fixture reminder"
        const val SETTINGS = "com.android.settings"
    }
}
