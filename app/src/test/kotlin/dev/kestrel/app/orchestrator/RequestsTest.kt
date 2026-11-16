package dev.kestrel.app.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestsTest {
    @Test
    fun theNameStartsATask() {
        assertEquals(Request.Task("open the settings."), Requests.read("Kestrel, open the settings.", spotted = false))
    }

    @Test
    fun aMisspeltNameStillStartsATask() {
        assertEquals(Request.Task("open Viber"), Requests.read("Kestral open Viber", spotted = false))
        assertEquals(Request.Task("call Ana."), Requests.read("Castrel, call Ana.", spotted = false))
        assertEquals(Request.Task("call Ana"), Requests.read("Kes trel, call Ana", spotted = false))
        assertEquals(Request.Task("send it"), Requests.read("Kestrel's send it", spotted = false))
        // Without removing the apostrophe, "kestrals" is three letters away.
        assertEquals(Request.Task("open Viber"), Requests.read("Kestral's open Viber", spotted = false))
    }

    @Test
    fun aNameFurtherAwayCountsOnlyWhenTheSpotterHeardIt() {
        assertEquals(Request.Task("open the settings"), Requests.read("Castral, open the settings", spotted = true))
        assertEquals(Request.Other("Castral, open the settings"), Requests.read("Castral, open the settings", spotted = false))
    }

    @Test
    fun theNameMayComeSecondWhenTheSpotterHeardIt() {
        assertEquals(Request.Task("open the settings."), Requests.read("Hey Kestrel, open the settings.", spotted = true))
        assertEquals(Request.Name, Requests.read("Hey Kes trel?", spotted = true))
        assertEquals(Request.Other("Hey Kestrel, open the settings."), Requests.read("Hey Kestrel, open the settings.", spotted = false))
    }

    @Test
    fun wordsThatOnlyLookAlikeAreNotTheName() {
        assertEquals(Request.Other("Kettle on, please"), Requests.read("Kettle on, please", spotted = false))
        assertEquals(Request.Other("Nestle chocolate"), Requests.read("Nestle chocolate", spotted = true))
        assertEquals(Request.Other("Open the settings"), Requests.read("Open the settings", spotted = true))
    }

    @Test
    fun theNameOnItsOwn() {
        assertEquals(Request.Name, Requests.read("Kestrel.", spotted = false))
        assertEquals(Request.Name, Requests.read("Kestrel?", spotted = true))
    }

    @Test
    fun kestrelsOwnPhrasesWithTheName() {
        assertEquals(Request.WakeUp, Requests.read("Kestrel, wake up.", spotted = false))
        assertEquals(Request.Stop(named = true), Requests.read("Kestrel, stop.", spotted = true))
        assertEquals(Request.Stop(named = true), Requests.read("Kestrel cancel", spotted = false))
        assertEquals(Request.Stop(named = true), Requests.read("Kestrel, stop it.", spotted = false))
        assertEquals(Request.PauseListening, Requests.read("Kestrel, stop listening", spotted = false))
    }

    @Test
    fun kestrelsOwnPhrasesWithoutTheName() {
        assertEquals(Request.Stop(named = false), Requests.read("Stop.", spotted = false))
        assertEquals(Request.Stop(named = false), Requests.read("Cancel!", spotted = false))
        assertEquals(Request.PauseListening, Requests.read("Stop listening.", spotted = false))
        assertEquals(Request.TurnOff, Requests.read("Stop Kestrel.", spotted = false))
        assertEquals(Request.TurnOff, Requests.read("Stop Kestral", spotted = false))
        // The spotter also fires at the start of "Stop Kestrel".
        assertEquals(Request.TurnOff, Requests.read("Stop Kestrel.", spotted = true))
        assertEquals(Request.TurnOff, Requests.read("Stop Kes trel", spotted = true))
        assertEquals(Request.TurnOff, Requests.read("Stop Kes trel", spotted = false))
        assertEquals(Request.TurnOff, Requests.read("Stop Castral", spotted = true))
    }

    @Test
    fun stopAndCancelCountWithFillerWords() {
        assertEquals(Request.Stop(named = false), Requests.read("Stop, stop!", spotted = false))
        assertEquals(Request.Stop(named = false), Requests.read("Cancel that.", spotted = false))
        assertEquals(Request.Stop(named = false), Requests.read("Okay, stop.", spotted = false))
        assertEquals(Request.Stop(named = false), Requests.read("No, stop!", spotted = false))
        assertEquals(Request.Stop(named = false), Requests.read("Wait, stop!", spotted = false))
        assertEquals(Request.Stop(named = false), Requests.read("Cancel the task.", spotted = false))
        assertEquals(Request.Other("Stop the timer"), Requests.read("Stop the timer", spotted = false))
        assertEquals(Request.Other("Stop the music."), Requests.read("Stop the music.", spotted = false))
        assertEquals(Request.Other("Stop editing"), Requests.read("Stop editing", spotted = false))
        assertEquals(Request.Other("Cancel zooming."), Requests.read("Cancel zooming.", spotted = false))
        assertEquals(Request.Other("Don't stop"), Requests.read("Don't stop", spotted = false))
        assertEquals(Request.Other("Okay."), Requests.read("Okay.", spotted = false))
    }

    @Test
    fun theNameMayComeAnywhereInAStop() {
        assertEquals(Request.Stop(named = true), Requests.read("Stop it, Kestrel", spotted = false))
        assertEquals(Request.Stop(named = true), Requests.read("Cancel, Kestrel.", spotted = true))
        assertEquals(Request.Stop(named = true), Requests.read("Kestrel, cancel the task.", spotted = false))
    }

    @Test
    fun onlyAFewWordsMayComeBeforeTheName() {
        assertEquals(Request.Other("Thanks, Kestrel."), Requests.read("Thanks, Kestrel.", spotted = true))
        assertEquals(Request.Other("Open Kestrel."), Requests.read("Open Kestrel.", spotted = true))
        assertEquals(Request.Name, Requests.read("Okay Kestrel?", spotted = true))
        assertEquals(Request.Other("Chest roll, please"), Requests.read("Chest roll, please", spotted = true))
    }

    @Test
    fun aTaskAfterStopCancelsARunningTaskFirst() {
        assertEquals(Request.Task("open Viber.", stopFirst = true), Requests.read("Stop. Kestrel, open Viber.", spotted = true))
        assertEquals(Request.Task("open Viber.", stopFirst = true), Requests.read("Cancel, Kestrel, open Viber.", spotted = true))
        assertEquals(Request.Task("open Viber.", stopFirst = false), Requests.read("Hey Kestrel, open Viber.", spotted = true))
    }

    @Test
    fun theNameSaidAgainIsDropped() {
        assertEquals(Request.Name, Requests.read("Kestrel? Kestrel?", spotted = false))
        assertEquals(Request.Task("open Viber"), Requests.read("Kestrel, Kestrel, open Viber", spotted = false))
    }

    @Test
    fun kestrelsOwnPhrasesMayBePolite() {
        assertEquals(Request.WakeUp, Requests.read("Kestrel, wake up, please.", spotted = false))
        assertEquals(Request.WakeUp, Requests.read("Kestrel, please wake up.", spotted = false))
        assertEquals(Request.WakeUp, Requests.read("Kestrel, wakeup.", spotted = false))
        assertEquals(Request.PauseListening, Requests.read("Stop listening, please.", spotted = false))
        assertEquals(Request.PauseListening, Requests.read("Please stop listening.", spotted = false))
        assertEquals(Request.TurnOff, Requests.read("Stop Kestrel, please.", spotted = false))
        assertEquals(Request.TurnOff, Requests.read("Stop Kestrel, please.", spotted = true))
    }

    @Test
    fun kestrelsOwnPhrasesMayEndWithTheName() {
        assertEquals(Request.WakeUp, Requests.read("Wake up, Kestrel.", spotted = false))
        assertEquals(Request.PauseListening, Requests.read("Stop listening, Kestrel.", spotted = false))
        assertEquals(Request.Other("Wake up."), Requests.read("Wake up.", spotted = false))
    }

    @Test
    fun anAnswerToYesIsReadAsTheWordsAfterTheName() {
        assertEquals(Request.WakeUp, Requests.read("Wake up.", spotted = false, afterName = true))
        assertEquals(Request.Stop(named = true), Requests.read("Stop.", spotted = false, afterName = true))
        assertEquals(Request.PauseListening, Requests.read("Stop listening.", spotted = false, afterName = true))
        assertEquals(Request.Task("Open the settings."), Requests.read("Open the settings.", spotted = false, afterName = true))
        assertEquals(Request.TurnOff, Requests.read("Stop Kestrel.", spotted = false, afterName = true))
        assertEquals(Request.Task("open Viber"), Requests.read("Kestrel, open Viber", spotted = true, afterName = true))
        assertNull(Requests.read(" ... ", spotted = false, afterName = true))
    }

    @Test
    fun turnOffAndSwitchOffAfterTheNameTurnKestrelOff() {
        assertEquals(Request.TurnOff, Requests.read("Kestrel, turn off.", spotted = false))
        assertEquals(Request.TurnOff, Requests.read("Kestrel, switch off, please.", spotted = true))
        assertEquals(Request.Task("turn off the wifi."), Requests.read("Kestrel, turn off the wifi.", spotted = false))
        assertEquals(Request.Other("Turn off."), Requests.read("Turn off.", spotted = false))
    }

    @Test
    fun aBareTurnOffInAnAnswerToYesIsATask() {
        // The answer ends after 0.7 s of silence, so the owner may still be saying what to turn off.
        assertEquals(Request.Task("Turn off."), Requests.read("Turn off.", spotted = false, afterName = true))
        assertEquals(Request.Task("Switch off, please."), Requests.read("Switch off, please.", spotted = false, afterName = true))
        assertEquals(Request.TurnOff, Requests.read("Kestrel, turn off.", spotted = true, afterName = true))
        assertEquals(Request.TurnOff, Requests.read("Turn off, Kestrel.", spotted = false, afterName = true))
    }

    @Test
    fun turnOffMayEndWithTheName() {
        assertEquals(Request.TurnOff, Requests.read("Turn off, Kestrel.", spotted = false))
        assertEquals(Request.TurnOff, Requests.read("Switch off, please, Kestrel.", spotted = false))
        assertEquals(Request.Other("Turn off the wifi, Kestrel."), Requests.read("Turn off the wifi, Kestrel.", spotted = false))
    }

    @Test
    fun kestrelsSettingsOpenWithTheirOwnPhrases() {
        assertEquals(Request.OpenSettings(), Requests.read("Kestrel, open settings.", spotted = false))
        assertEquals(Request.OpenSettings(), Requests.read("Kestrel, open your settings, please.", spotted = true))
        assertEquals(Request.OpenSettings(), Requests.read("Kestrel settings", spotted = false))
        assertEquals(Request.OpenSettings(), Requests.read("Open settings.", spotted = false, afterName = true))
        assertEquals(Request.OpenSettings(stopFirst = true), Requests.read("Stop. Kestrel, open settings.", spotted = true))
        // The owner may mean Android's settings, so these stay a task and an instant command.
        assertEquals(Request.Task("open the settings."), Requests.read("Kestrel, open the settings.", spotted = false))
        assertEquals(Request.Other("Open settings."), Requests.read("Open settings.", spotted = false))
    }

    @Test
    fun anAnswerToYesMayCallItOff() {
        assertEquals(Request.Stop(named = true), Requests.read("Never mind.", spotted = false, afterName = true))
        assertEquals(Request.Stop(named = true), Requests.read("Forget it, please.", spotted = false, afterName = true))
        assertEquals(Request.Stop(named = true), Requests.read("Nothing.", spotted = false, afterName = true))
        assertEquals(Request.Stop(named = true), Requests.read("Kestrel, never mind.", spotted = true, afterName = true))
        assertEquals(Request.Stop(named = true), Requests.read("Never mind, Kestrel.", spotted = false, afterName = true))
        // "Kestrel, forget [fact]" is a command of its own (Appendix A).
        assertEquals(Request.Task("Forget the milk."), Requests.read("Forget the milk.", spotted = false, afterName = true))
        // Only an answer to "Yes?" is called off this way.
        assertEquals(Request.Task("never mind."), Requests.read("Kestrel, never mind.", spotted = false))
        assertEquals(Request.Other("Never mind."), Requests.read("Never mind.", spotted = false))
        assertEquals(Request.Other("Never mind, Kestrel."), Requests.read("Never mind, Kestrel.", spotted = false))
    }

    @Test
    fun anAnswerThatCallsItOffMayHaveAFewMoreWords() {
        for (answer in listOf("No, never mind.", "Oh, never mind.", "Ah, forget it.", "Never mind, thanks.", "Nothing, thanks.")) {
            assertEquals(answer, Request.Stop(named = true), Requests.read(answer, spotted = false, afterName = true))
        }
        assertEquals(Request.Stop(named = true), Requests.read("Oh no, nothing, thank you.", spotted = false, afterName = true))
        assertEquals(Request.Stop(named = true), Requests.read("No, never mind, Kestrel.", spotted = false, afterName = true))
        assertEquals(Request.Stop(named = true), Requests.read("Kestrel, no, forget it.", spotted = true, afterName = true))
        assertEquals(Request.Task("Forget the milk, thanks."), Requests.read("Forget the milk, thanks.", spotted = false, afterName = true))
        assertEquals(Request.Task("No."), Requests.read("No.", spotted = false, afterName = true))
        assertEquals(Request.Other("No, never mind."), Requests.read("No, never mind.", spotted = false))
    }

    @Test
    fun aTranscriptWithoutWordsIsNothing() {
        assertNull(Requests.read("", spotted = false))
        assertNull(Requests.read(" ... ", spotted = true))
        assertNull(Requests.read("'", spotted = false))
    }

    @Test
    fun theOwnersWordsAreNotPrinted() {
        assertEquals("Task(17 characters)", Request.Task("open the settings").toString())
        assertEquals("Other(11 characters)", Request.Other("Scroll down").toString())
    }
}
