package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(5_000) {
        while (!check()) delay(100)
    }

    private suspend fun keyOf(label: String) = screen.awaitElement { it.label == label }.key

    private suspend fun showDialog() {
        session.actions.tap(keyOf("SHOW DIALOG"))
        screen.awaitElement { it.label == "Delete draft?" }
    }

    @Test
    fun numbersGoOnTheActionableElementsInReadingOrder() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        session.showNumbers()

        val badges = session.numberBadges
        assertEquals(keyOf("SEND"), badges.first().key)
        assertEquals((1..badges.size).toList(), badges.map { it.number })
        eventually { screen.showsWindow("Kestrel numbers") }
        assertTrue(session.snapshots.current().snapshot.windows.none { it.packageName == KESTREL })
    }

    @Test
    fun numbersCanBeHiddenRightAfterTheyAreShown() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        // Android attaches a new overlay's view only on the next frame. Showing and hiding the numbers in one go on the
        // main thread hides them before that frame, and the window must still go.
        repeat(2) {
            withContext(Dispatchers.Main) {
                session.showNumbers()
                session.hideNumbers()
            }
        }
        eventually { !screen.showsWindow("Kestrel numbers") }
    }

    @Test
    fun unlabeledModeNumbersOnlyTheIconWithoutAName() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        session.showNumbers(NumbersMode.UNLABELED)

        val icon = screen.awaitElement { it.viewId == "$FIXTURES:id/compose_button" }
        assertEquals(listOf(icon.key), session.numberBadges.map { it.key })
    }

    @Test
    fun touchesReachTheAppWhileNumbersAreShown() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showNumbers()
        val target = screen.awaitElement { it.label == "Touch target" }

        assertEquals(ActionResult.Done(ActionMethod.GESTURE), session.actions.tapAt(target.bounds.centerX, target.bounds.centerY))
        screen.awaitElement { it.label == "Touched target" }
        Unit
    }

    @Test
    fun numbersFollowTheScreenUntilTheyAreHidden() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showNumbers()

        showDialog()

        val keep = keyOf("KEEP")
        eventually { session.numberBadges.any { it.key == keep } }
        session.hideNumbers()
        eventually { !screen.showsWindow("Kestrel numbers") }
    }

    @Test
    fun whichOneNumbersOnlyTheCandidatesAndGoesWhenTheScreenChanges() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = keyOf("SEND")
        val rebuild = keyOf("REBUILD")

        session.showWhichOne(listOf(rebuild, send))

        assertEquals(listOf(1 to send, 2 to rebuild), session.numberBadges.map { it.number to it.key })
        showDialog()
        eventually { session.numberBadges.isEmpty() && !screen.showsWindow("Kestrel numbers") }
    }

    @Test
    fun labelsGoWhenTheScreenChanges() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        session.showLabels()

        assertTrue(session.labelsShowing)
        eventually { screen.showsWindow("Kestrel labels") }
        showDialog()
        eventually { !session.labelsShowing && !screen.showsWindow("Kestrel labels") }
    }

    @Test
    fun turningTheServiceOffRemovesTheOverlays() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showNumbers()
        eventually { screen.showsWindow("Kestrel numbers") }

        screen.shell("settings delete secure enabled_accessibility_services")

        eventually { !screen.showsWindow("Kestrel numbers") }
    }
}
