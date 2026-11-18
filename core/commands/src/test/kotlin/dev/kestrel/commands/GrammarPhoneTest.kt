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

    @Test
    fun theVolumeNeedsTheWordVolumeAndAtMostOneStream() {
        assertParses(PhoneCommand.Volume(null, up = true), "Volume up.", "Turn the volume up.", "Turn up the volume", "Increase the volume.")
        assertParses(PhoneCommand.Volume(null, up = false), "Volume down", "Lower the volume.", "turn the volume down a bit")
        assertParses(PhoneCommand.Volume(StreamKind.MEDIA, up = true), "Turn up the media volume.", "Music volume up.")
        assertParses(PhoneCommand.Volume(StreamKind.ALARM, up = false), "Turn the alarm volume down.", "Decrease the alarm volume")
        assertParses(PhoneCommand.Volume(StreamKind.RING, up = true), "Ring volume up.", "Increase the ringer volume.")
        assertParses(PhoneCommand.Volume(StreamKind.CALL, up = false), "Call volume down.", "Turn down the phone volume.")
        assertParses(PhoneCommand.Volume(StreamKind.NOTIFICATION, up = true), "Notification volume up.")
        // A polite word at the end, as the radio and "open" rules take it.
        assertParses(PhoneCommand.Volume(null, up = true), "Volume up please.", "Turn the volume up now.", "Increase the volume, please.")
        assertParses(PhoneCommand.Volume(null, up = false), "Lower the volume now.", "Decrease the volume please")
        assertParses(PhoneCommand.Volume(StreamKind.ALARM, up = false), "Turn the alarm volume down, please.")
        assertBare("Volume.", "Turn up the volume and the lights.", "The volume is too high.", "volume up down", "media alarm volume up")
        assertParses(Command.Slider(increase = true, target = null), "Increase.")
    }

    @Test
    fun muteSilenceAndMediaKeys() {
        assertParses(PhoneCommand.Mute(null, on = true), "Mute.", "Mute the sound.")
        assertParses(PhoneCommand.Mute(null, on = false), "Unmute.", "Un-mute.")
        assertParses(PhoneCommand.Mute(StreamKind.RING, on = true), "Mute the ringer.", "Mute the ringtone")
        assertParses(PhoneCommand.Mute(StreamKind.RING, on = false), "Unmute the ringer.", "Turn off vibrate.", "Vibrate off.")
        assertParses(PhoneCommand.Mute(StreamKind.RING, on = true), "Mute the phone.")
        assertParses(PhoneCommand.Mute(StreamKind.ALARM, on = true), "Mute the alarm.", "Mute the alarm volume.")
        assertParses(PhoneCommand.Mute(StreamKind.MEDIA, on = true), "Mute the music.")
        assertParses(PhoneCommand.Mute(null, on = true), "Mute please.", "Mute the sound now.", "Please mute please.")
        assertParses(PhoneCommand.Mute(StreamKind.RING, on = false), "Unmute the ringer, please.")
        assertParses(PhoneCommand.Silence, "Silence.", "Vibrate.", "Silent mode.", "Silence the phone.", "Turn on vibrate.")
        assertParses(PhoneCommand.Media(play = true), "Media play.", "Play media.", "Play the music.")
        assertParses(PhoneCommand.Media(play = false), "Media pause.", "Pause the music.", "Pause media.")
        assertBare("Play.", "Pause.", "Mute the TV.", "Play the next song for me.")
    }

    @Test
    fun magnificationZoomsPansAndStops() {
        assertParses(PhoneCommand.Magnify(MagnifyAction.IN), "Start magnification.", "Start zooming.", "Magnify.", "Zoom in.", "Enhance.")
        assertParses(PhoneCommand.Magnify(MagnifyAction.OUT), "Zoom out.")
        assertParses(PhoneCommand.Magnify(MagnifyAction.STOP), "Stop magnification.", "Stop zooming.", "Cancel zooming.")
        assertParses(PhoneCommand.Pan(Direction.LEFT), "Pan left.", "Move left.", "Go left.")
        assertParses(PhoneCommand.Pan(Direction.UP), "Pan up.", "Move up")
        assertParses(Command.Scroll(Direction.DOWN, null), "Scroll down.")
    }

    @Test
    fun callTakesANumberOrANameWithAKind() {
        assertParses(
            PhoneCommand.Call(CallTarget.Number("0641234567")),
            "Call 064 123 4567.", "Dial 064-123-4567", "call zero six four one two three four five six seven",
        )
        assertParses(
            PhoneCommand.Call(CallTarget.Number("+381641234567")),
            "Call +381 64 123 4567.", "call plus three eight one six four one two three four five six seven",
        )
        assertParses(PhoneCommand.Call(CallTarget.Number("*123#")), "Dial star one two three hash.")
        assertParses(PhoneCommand.Call(CallTarget.Number("*#06#")), "Dial *#06#.")
        assertParses(PhoneCommand.Call(CallTarget.Number("112")), "Call 112.")
        assertParses(PhoneCommand.Call(CallTarget.Name("Ana Petrović", null)), "Call Ana Petrović.")
        assertParses(PhoneCommand.Call(CallTarget.Name("Mom", null)), "Call Mom.", "Call Mom now.", "Call Mom please")
        assertParses(PhoneCommand.Call(CallTarget.Number("0641234567")), "Call 064 123 4567 now.")
        assertParses(PhoneCommand.Call(CallTarget.Name("Ana", "mobile")), "Call Ana mobile.", "Call Ana on mobile.", "Call Ana's mobile number.")
        assertParses(PhoneCommand.Call(CallTarget.Name("Ana", "work")), "call Ana's work number", "Dial Ana at work.")
        assertParses(PhoneCommand.Call(CallTarget.Name("Ana", "gym")), "Call Ana's gym number.")
        assertParses(PhoneCommand.Call(CallTarget.Name("Ana", null)), "Call Ana's number.")
        assertParses(PhoneCommand.Call(CallTarget.Name("home", null)), "Call home.")
        assertParses(PhoneCommand.Volume(StreamKind.CALL, up = true), "Call volume up.")
        assertBare("Call.", "I'll call you later, okay?", "Call please.", "Dial now.")
    }

    @Test
    fun copyThisPhoneNumber() {
        assertParses(PhoneCommand.CopyPhoneNumber, "Copy this phone number.", "Copy the phone number.", "Copy the number.")
    }
}
