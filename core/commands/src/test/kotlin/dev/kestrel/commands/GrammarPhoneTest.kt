package dev.kestrel.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun openTakesTheAppsNameWithItsCaseAndTheWorkProfile() {
        assertParses(PhoneCommand.OpenApp("Chrome", work = false), "Open Chrome.", "Launch Chrome", "open Chrome please")
        assertParses(PhoneCommand.OpenApp("the Play Store", work = false), "Open the Play Store.")
        assertParses(PhoneCommand.OpenApp("Gmail", work = true), "Open work Gmail.", "Open Gmail in the work profile.")
        assertParses(PhoneCommand.OpenApp("Gmail", work = true), "Open work Gmail in the work profile.", "Launch work Gmail from the work profile")
        assertParses(PhoneCommand.Global(GlobalCommand.ALL_APPS), "Open apps.")
        assertParses(Command.Help(HelpKind.TUTORIAL), "Open tutorial.")
    }

    @Test
    fun openNeverOpensTheSpokenPunctuationOfDictation() {
        for (phrase in listOf("open quote", "Open quote hello close quote", "open parenthesis", "open bracket one", "open double quote")) {
            assertFalse(phrase, CommandParser.parse(phrase) is PhoneCommand.OpenApp)
        }
    }

    @Test
    fun wifiAndBluetoothTurnOnAndOffAndOtherSwitchesStayScreenCommands() {
        assertParses(PhoneCommand.Radio(RadioKind.WIFI, on = true), "Turn on Wi-Fi.", "Turn the WiFi on", "Enable wifi", "Wi-Fi on.")
        assertParses(PhoneCommand.Radio(RadioKind.WIFI, on = false), "Turn off Wi‑Fi.", "Switch off the Wi-Fi", "Disable Wi Fi")
        assertParses(PhoneCommand.Radio(RadioKind.BLUETOOTH, on = true), "Turn on Bluetooth.", "Switch Bluetooth on.", "Bluetooth on")
        assertParses(PhoneCommand.Radio(RadioKind.BLUETOOTH, on = false), "Turn off Bluetooth.", "Turn Bluetooth off", "disable bluetooth")
        assertParses(PhoneCommand.Radio(RadioKind.WIFI, on = true), "Turn on Wi-Fi please.", "turn on wifi now")
        assertParses(PhoneCommand.Radio(RadioKind.BLUETOOTH, on = false), "Turn off Bluetooth now.", "Turn off blue tooth.")
        assertParses(Command.Switch(Target.Label("the flashlight"), on = true), "Turn on the flashlight.")
        assertParses(Command.Switch(null, on = false), "Switch off.")
        // "Turn off" alone is Kestrel's own verb, whose name the recogniser may lose; it is no command.
        assertBare("Turn off.")
        assertParses(Command.Check(Target.Label("Wi-Fi"), on = true), "check Wi-Fi")
    }

    // Tasks 2 to 6 of plan 3C add their tests above this line.
}
