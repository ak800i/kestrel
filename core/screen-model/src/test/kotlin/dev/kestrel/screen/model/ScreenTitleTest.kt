package dev.kestrel.screen.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenTitleTest {
    private fun title(screen: TestScreen, appLabel: String?, withRule3: Boolean = true) =
        ScreenTitle.of(screen.snapshot(), screen.window(), appLabel, withRule3)

    @Test
    fun theToolbarNamesTheChatWhenTheWindowTitleIsTheAppsName() {
        val screen = TestScreen(windowTitle = "Kestrel fixtures")
        val bar = screen.add(80, id = "toolbar")
        screen.add(80, description = "Navigate up", parent = bar, right = 100, clickable = true)
        screen.add(90, text = "Ana Petrović", parent = bar, left = 120)
        val list = screen.add(300, scrollable = true, height = 1500)
        screen.add(400, text = "See you tomorrow!", parent = list)

        assertEquals("Ana Petrović", title(screen, "Kestrel fixtures"))
        assertEquals("Ana Petrović", title(screen, "Kestrel fixtures", withRule3 = false))
    }

    @Test
    fun telegramsHeaderIsTheFirstTextInItsBandWithoutViewIds() {
        val screen = TestScreen(packageName = "org.telegram.messenger", windowTitle = "Telegram")
        screen.add(100, description = "Go back", right = 120, clickable = true, className = "android.widget.ImageView")
        screen.add(100, text = "Saved Messages", left = 200, height = 50)
        screen.add(160, text = "forward here to save", left = 200, height = 40)
        val list = screen.add(260, scrollable = true, height = 1500)
        screen.add(300, text = "Shopping list", parent = list)

        assertEquals("Saved Messages", title(screen, "Telegram", withRule3 = false))
    }

    @Test
    fun theMessengerFixtureNamesItsChatByItsViewId() {
        val screen = TestScreen(packageName = "dev.kestrel.testapp", windowTitle = "Kestrel fixtures")
        val bar = screen.add(0, id = "toolbar")
        screen.add(10, text = "Messenger", parent = bar)
        screen.add(500, text = "Ana", id = "chat_title")

        assertEquals("Ana", title(screen, "Kestrel fixtures"))
    }

    @Test
    fun aTitleIdCountsOnlyInTheTopBandOutsideLists() {
        val screen = TestScreen(windowTitle = "Settings")
        val list = screen.add(100, scrollable = true, height = 1800)
        val row = screen.add(150, parent = list, clickable = true)
        screen.add(160, text = "Wi-Fi", id = "android:id/title", parent = row)

        // Rule 3 would take the row's text, so a recipient that leaves rule 3 out has no title here.
        assertEquals("Wi-Fi", title(screen, "Settings"))
        assertNull(title(screen, "Settings", withRule3 = false))

        screen.add(120, text = "Marko", id = "conversation_title", left = 200, height = 50)
        assertEquals("Marko", title(screen, "Settings", withRule3 = false))
    }

    @Test
    fun theWindowTitleCountsOnlyWhenItDiffersFromAKnownAppLabel() {
        val screen = TestScreen(packageName = "com.google.android.gm", windowTitle = "Compose")
        screen.add(1500, text = "Send", clickable = true)

        assertEquals("Compose", title(screen, "Gmail"))
        assertNull(title(screen, "compose"))
        assertNull(title(screen, null))
    }

    @Test
    fun aViberLikeChatIsNamedByItsToolbarOrItsWindowTitle() {
        val toolbar = TestScreen(packageName = "com.viber.voip", windowTitle = "Viber")
        val bar = toolbar.add(80, id = "toolbar")
        toolbar.add(90, text = "My Notes", parent = bar)
        assertEquals("My Notes", title(toolbar, "Viber", withRule3 = false))

        val window = TestScreen(packageName = "com.viber.voip", windowTitle = "Ana")
        window.add(1500, text = "Hello", clickable = true)
        assertEquals("Ana", title(window, "Viber", withRule3 = false))
    }
}
