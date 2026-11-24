package dev.kestrel.app.screen

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.agent.port.IntentSpec
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.datatools.IntentSpecs
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.model.WindowKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemActionsTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val actions get() = screen.session.actions

    /** Waits until no app window of the fixture app is on the screen. */
    private suspend fun fixturesAreGone() = withTimeout(5_000) {
        while (screen.session.snapshots.current().snapshot.windows.any { it.kind == WindowKind.APPLICATION && it.packageName == FIXTURES }) {
            delay(100)
        }
    }

    /**
     * Waits for the first button of the fixture app's launcher screen. The screen's title is also the app's name, and an
     * icon on the home screen can show that name too.
     */
    private suspend fun launcherScreenIsShown() {
        screen.awaitElement { it.label == "VIEWS" }
    }

    @Test
    fun backClosesADialog() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        actions.tap(screen.awaitElement { it.label == "SHOW DIALOG" }.key)
        screen.awaitElement { it.label == "Delete draft?" }

        assertEquals(ActionResult.Done(ActionMethod.GLOBAL_ACTION), actions.global(GlobalAction.BACK))
        screen.awaitElement { it.label == "Views fixture" }
        assertTrue(screen.session.snapshots.current().snapshot.elements.none { it.label == "Delete draft?" })
    }

    @Test
    fun homeLeavesTheApp() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertEquals(ActionResult.Done(ActionMethod.GLOBAL_ACTION), actions.global(GlobalAction.HOME))
        fixturesAreGone()
    }

    @Test
    fun openAppStartsAnAppAtItsLauncherScreen() = runBlocking {
        screen.shell("am force-stop $FIXTURES")
        fixturesAreGone()

        assertEquals(ActionResult.Done(ActionMethod.INTENT), actions.openApp(FIXTURES))
        launcherScreenIsShown()
    }

    @Test
    fun aLinkOpensTheAppThatTakesIt() = runBlocking {
        screen.shell("am force-stop $FIXTURES")
        fixturesAreGone()

        assertEquals(ActionResult.Done(ActionMethod.INTENT), actions.openIntent(link("kestrel-fixtures://start")))
        launcherScreenIsShown()
    }

    @Test
    fun aLinkKeptToOneAppOpensOnlyThereAndAnIntentStartsAsItIs() = runBlocking {
        screen.shell("am force-stop $FIXTURES")
        fixturesAreGone()

        assertEquals(ActionResult.Done(ActionMethod.INTENT), actions.openIntent(link("kestrel-fixtures://start", FIXTURES)))
        launcherScreenIsShown()
        // A link kept to an app that cannot open it opens nothing, not the app that would otherwise take it (D32 item 12).
        assertEquals(ActionResult.Failed("No app can open it."), actions.openIntent(link("kestrel-fixtures://start", "dev.kestrel.missing")))

        screen.shell("am force-stop $FIXTURES")
        fixturesAreGone()
        assertEquals(ActionResult.Done(ActionMethod.INTENT), actions.openIntent(Intent(Intent.ACTION_VIEW, Uri.parse("kestrel-fixtures://start")).setPackage(FIXTURES)))
        launcherScreenIsShown()
        assertEquals(ActionResult.Failed("No app can open it."), actions.openIntent(Intent("dev.kestrel.NO_SUCH_ACTION")))
    }

    @Test
    fun aFileLinkFailsInsteadOfThrowing() = runBlocking {
        // Android does not let an app hand a file:// link to another app.
        assertEquals(ActionResult.Failed("Android does not let Kestrel open it."), actions.openIntent(link("file:///sdcard/Download/note.txt")))
    }

    // A link in the form of the agent's links: a VIEW intent with CATEGORY_BROWSABLE, kept to [packageName] when one is given.
    private fun link(uri: String, packageName: String? = null): Intent =
        IntentSpecs.toIntent(IntentSpec(IntentSpec.VIEW, uri, packageName, browsable = true))

    @Test
    fun openAppFailsForAnAppThatIsNotInstalled() = runBlocking {
        assertTrue(actions.openApp("dev.kestrel.missing") is ActionResult.Failed)
    }
}
