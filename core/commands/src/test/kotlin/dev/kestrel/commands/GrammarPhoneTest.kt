package dev.kestrel.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarPhoneTest {
    private fun assertParses(expected: Command, vararg phrases: String) {
        for (phrase in phrases) assertEquals(phrase, expected, CommandParser.parse(phrase))
    }

    private fun assertBare(vararg phrases: String) {
        for (phrase in phrases) assertTrue(phrase, CommandParser.parse(phrase) is Command.Bare)
    }

    @Test
    fun theGlobalActionsHaveSeveralPhrasingsAndBackAndHomeAloneAreGlobalActions() {
        assertParses(PhoneCommand.Global(GlobalCommand.BACK), "Back.", "Go back.", "go back please", "Navigate back")
        assertParses(PhoneCommand.Global(GlobalCommand.HOME), "Home", "Go home.", "Go to the home screen.")
        assertParses(PhoneCommand.Global(GlobalCommand.NOTIFICATIONS), "Show notifications.", "Open the notifications.")
        assertParses(PhoneCommand.Global(GlobalCommand.QUICK_SETTINGS), "Show Quick Settings.", "Open quick settings")
        assertParses(PhoneCommand.Global(GlobalCommand.RECENT_APPS), "Show recent apps.", "Recent apps.", "Show recents")
        assertParses(PhoneCommand.Global(GlobalCommand.LOCK_SCREEN), "Lock screen.", "Lock the phone.")
        assertParses(PhoneCommand.Global(GlobalCommand.POWER_MENU), "Turn off device.", "Show the power menu.")
        assertParses(PhoneCommand.Global(GlobalCommand.SCREENSHOT), "Take a screenshot.", "Screenshot", "Take screenshot")
        assertParses(PhoneCommand.Global(GlobalCommand.ALL_APPS), "Open apps.", "Show all apps.", "Open the app drawer.")
        assertParses(PhoneCommand.PreviousApp, "Show previous app.", "Previous app.", "Go to the previous app.", "Switch apps")
        assertParses(PhoneCommand.CircleToSearch, "Circle to search.", "Start Circle to Search.")
    }

    @Test
    fun tapHomeTapsALabelAndLongerSentencesStayBare() {
        assertEquals(Command.Tap(Target.Label("home")), CommandParser.parse("tap home"))
        assertBare("I think we should go back later.", "Back to the drawing board.", "Home sweet home.")
    }

    // Tasks 2 to 6 of plan 3C add their tests above this line.
}
