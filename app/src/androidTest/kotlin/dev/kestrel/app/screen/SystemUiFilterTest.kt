package dev.kestrel.app.screen

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.agent.observe.ObservationBuilder
import dev.kestrel.agent.observe.ObservationInput
import dev.kestrel.agent.observe.SystemUiParts
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.Redactor
import dev.kestrel.safety.Vault
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SystemUI's parts in an observation (design D9 of sub-project 5): Quick Settings' tiles stay, and a notification, also
 * a heads-up one, is only counted, so that its text, such as a one-time code, never reaches a prompt. The heads-up
 * notification of `:testapp` (`NotificationActivity` with the extra `headsUp`) carries the text [MARKER].
 * [recordsTheSystemUiParts] writes the view IDs and classes of SystemUI's elements to logcat (tag `KestrelSystemUi`, one
 * line per element:
 * `part=<tile|notification|other> id=<name|none> class=<simple name>`), from which the `a71` source of
 * `systemui-parts.json` is filled after the owner's first sitting.
 */
@RunWith(AndroidJUnit4::class)
class SystemUiFilterTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val parts = SystemUiParts.DEFAULT

    @Before
    fun post() {
        screen.shell("pm grant $FIXTURES android.permission.POST_NOTIFICATIONS")
        screen.shell("am start -W -n $FIXTURES/.NotificationActivity")
        screen.shell("am start -W -n $FIXTURES/.NotificationActivity --ez ${HEADS_UP} true")
    }

    @After
    fun cancel() = runBlocking<Unit> {
        screen.shell("am start -W -n $FIXTURES/.NotificationActivity --ez ${CANCEL} true")
        screen.session.actions.global(GlobalAction.DISMISS_SHADE)
    }

    @Test
    fun theShadesNotificationsAreLeftOutAndTheTilesStay() = runBlocking<Unit> {
        screen.session.actions.global(GlobalAction.NOTIFICATIONS)
        // 15 s, as in SystemCommandsTest: SystemUI's windows were slow to show while the host ran short of memory.
        screen.awaitElement(15_000) { it.packageName == SystemUiParts.SYSTEM_UI && shows(it) }
        val shade = screen.session.snapshots.current().snapshot
        assertTrue("No notification row was recognised", shade.elements.any(parts::isNotification))
        assertFalse("The observation holds the notification's text", observe(shade).contains(MARKER))
        screen.session.actions.global(GlobalAction.DISMISS_SHADE)

        screen.session.actions.global(GlobalAction.QUICK_SETTINGS)
        // One snapshot for both steps, because a tile's key holds its state, and an actionable tile, which always gets a
        // line of its own (task 5B-8's review, M1).
        val (qs, tile) = withTimeout(15_000) {
            var found: Pair<ScreenSnapshot, ScreenElement>? = null
            while (found == null) {
                val snapshot = screen.session.snapshots.current().snapshot
                found = snapshot.elements.firstOrNull { it.packageName == SystemUiParts.SYSTEM_UI && parts.isTile(it) && it.isActionable }?.let { snapshot to it }
                if (found == null) delay(100)
            }
            found
        }
        assertTrue("The observation left out a tile", build(qs).table.byKey(tile.key) != null)
    }

    @Test
    fun aHeadsUpNotificationOverAnAppIsLeftOut() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")
        screen.shell("am start -W -n $FIXTURES/.NotificationActivity --ez ${HEADS_UP} true")
        screen.awaitElement(15_000) { it.packageName == SystemUiParts.SYSTEM_UI && shows(it) }

        assertFalse("The observation holds the heads-up's text", observe(screen.session.snapshots.current().snapshot).contains(MARKER))
    }

    @Test
    fun recordsTheSystemUiParts() = runBlocking<Unit> {
        for (global in listOf(GlobalAction.NOTIFICATIONS, GlobalAction.QUICK_SETTINGS)) {
            screen.session.actions.global(global)
            screen.awaitElement(15_000) { it.packageName == SystemUiParts.SYSTEM_UI && it.isActionable }
            for (element in screen.session.snapshots.current().snapshot.elements.filter { it.packageName == SystemUiParts.SYSTEM_UI }) {
                val part = when {
                    parts.isTile(element) -> "tile"
                    parts.isNotification(element) -> "notification"
                    else -> "other"
                }
                val id = element.viewId?.substringAfter(":id/", missingDelimiterValue = "")?.ifEmpty { null } ?: "none"
                Log.i(TAG, "part=$part id=$id class=${element.className?.substringAfterLast('.') ?: "none"}")
            }
            screen.session.actions.global(GlobalAction.DISMISS_SHADE)
        }
    }

    private fun observe(snapshot: ScreenSnapshot): String = build(snapshot).text

    private fun build(snapshot: ScreenSnapshot) =
        ObservationBuilder(Redactor(Vault()), AgentFreeApps(), parts).build(ObservationInput(1, snapshot, "System UI"))

    private fun shows(element: ScreenElement): Boolean = element.text?.contains(MARKER) == true || element.label?.contains(MARKER) == true

    private companion object {
        const val TAG = "KestrelSystemUi"

        // NotificationActivity's extras and the text of its heads-up notification.
        const val HEADS_UP = "headsUp"
        const val CANCEL = "cancel"
        const val MARKER = "KX4711"
    }
}
