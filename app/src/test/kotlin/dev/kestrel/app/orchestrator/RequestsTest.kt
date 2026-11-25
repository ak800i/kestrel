package dev.kestrel.app.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun wakeUpAfterTheNameMayHaveOneWrongWord() {
        // One word misheard, missing or extra, as the A71 heard "Kestrel, wake up" through the air (plan 4E).
        assertEquals(Request.WakeUp, Requests.read("Kestrel, make up.", spotted = true))
        assertEquals(Request.WakeUp, Requests.read("Kestrel, wake op.", spotted = true))
        assertEquals(Request.WakeUp, Requests.read("Kestrel, wake.", spotted = false))
        assertEquals(Request.WakeUp, Requests.read("Kestrel, wake me up, please.", spotted = true))
        assertEquals(Request.WakeUp, Requests.read("Woke up.", spotted = false, afterName = true))
        // Two words away is a near miss: a task that Kestrel does not start while it is paused (RulesTest).
        assertEquals(Request.Task("sign up.", nearWakeUp = true), Requests.read("Kestrel, sign up.", spotted = true))
        assertEquals(Request.Task("wake up the phone", nearWakeUp = true), Requests.read("Kestrel, wake up the phone", spotted = false))
        // Anything further away stays a plain task.
        assertEquals(Request.Task("make up a story."), Requests.read("Kestrel, make up a story.", spotted = true))
        assertEquals(Request.Task("call Mom."), Requests.read("Kestrel, call Mom.", spotted = true))
        assertEquals(Request.Task("No."), Requests.read("No.", spotted = false, afterName = true))
    }

    @Test
    fun stopKestrelAllowsOneMisheardWordWhenTheSpotterHeardStop() {
        // "Castral" is three letters away from "kestrel", and "stops" one letter away from "stop" (plan 4E).
        assertEquals(Request.TurnOff, Requests.read("Stop Castral.", spotted = false, stopSpotted = true))
        assertEquals(Request.TurnOff, Requests.read("Stops Kestrel.", spotted = false, stopSpotted = true))
        assertEquals(Request.Other("Stop Castral."), Requests.read("Stop Castral.", spotted = false))
        assertEquals(Request.Other("Stops Kestrel."), Requests.read("Stops Kestrel.", spotted = false))
        // A false "stop" of the spotter turns nothing off without a word like "stop" and the name.
        assertEquals(Request.Other("Hey Kestrel."), Requests.read("Hey Kestrel.", spotted = false, stopSpotted = true))
        assertEquals(Request.Stop(named = false), Requests.read("Stop.", spotted = false, stopSpotted = true))
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
    fun aRequestAfterStopCancelsATaskFirst() {
        assertEquals(Request.Task("open Viber."), Requests.read("Stop. Kestrel, open Viber.", spotted = true))
        assertTrue(Requests.stopsFirst("Stop. Kestrel, open Viber.", spotted = true))
        assertTrue(Requests.stopsFirst("Cancel, Kestrel, open Viber.", spotted = true))
        assertTrue(Requests.stopsFirst("Stop. Kestrel, forget the gate code.", spotted = true))
        assertTrue(Requests.stopsFirst("Stop. Kestrel, open settings.", spotted = true))
        assertFalse(Requests.stopsFirst("Hey Kestrel, open Viber.", spotted = true))
        assertFalse(Requests.stopsFirst("Kestrel, open Viber.", spotted = true))
        // Only the spotter's name allows a word before it, as in read().
        assertFalse(Requests.stopsFirst("Stop. Kestrel, open Viber.", spotted = false))
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
        // The answer is held for 1.5 s of silence, but a longer pause still splits it, so the owner may still be saying
        // what to turn off.
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
        assertEquals(Request.OpenSettings, Requests.read("Kestrel, open settings.", spotted = false))
        assertEquals(Request.OpenSettings, Requests.read("Kestrel, open your settings, please.", spotted = true))
        assertEquals(Request.OpenSettings, Requests.read("Kestrel settings", spotted = false))
        assertEquals(Request.OpenSettings, Requests.read("Open settings.", spotted = false, afterName = true))
        assertEquals(Request.OpenSettings, Requests.read("Stop. Kestrel, open settings.", spotted = true))
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
        // "Kestrel, forget [fact]" is a command of its own (Appendix A; design D2 of sub-project 5).
        assertEquals(Request.Forget("the milk"), Requests.read("Forget the milk.", spotted = false, afterName = true))
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
        assertEquals(Request.Forget("the milk, thanks"), Requests.read("Forget the milk, thanks.", spotted = false, afterName = true))
        assertEquals(Request.Task("No."), Requests.read("No.", spotted = false, afterName = true))
        assertEquals(Request.Other("No, never mind."), Requests.read("No, never mind.", spotted = false))
    }

    @Test
    fun theCommandsWhileACallRings() {
        assertEquals(Request.Answer(speaker = false), Requests.readCall("Answer.", spotted = false, ringing = true))
        assertEquals(Request.Answer(speaker = false), Requests.readCall("Kestrel, answer, please.", spotted = true, ringing = true))
        assertEquals(Request.Answer(speaker = true), Requests.readCall("Answer on speaker.", spotted = false, ringing = true))
        assertEquals(Request.Answer(speaker = true), Requests.readCall("Answer on the speaker, Kestrel.", spotted = false, ringing = true))
        assertEquals(Request.Decline, Requests.readCall("Decline now.", spotted = false, ringing = true))
        // As in read(): one short word may come before a name that the spotter heard, and a polite word after a name.
        assertEquals(Request.Answer(speaker = false), Requests.readCall("Hey Kestrel, answer.", spotted = true, ringing = true))
        assertEquals(Request.Decline, Requests.readCall("Decline, Kestrel, please.", spotted = false, ringing = true))
    }

    @Test
    fun theCommandsDuringACall() {
        assertEquals(Request.HangUp, Requests.readCall("Kestrel, hang up.", spotted = false, ringing = false))
        assertEquals(Request.Mute, Requests.readCall("Kestrel, mute.", spotted = true, ringing = false))
        assertEquals(Request.Speaker, Requests.readCall("Hey Kestrel, speaker, please.", spotted = true, ringing = false))
    }

    @Test
    fun duringACallTheCommandsMustStartWithTheName() {
        // The caller can be heard through the speaker, so a command without the name first is ignored (section 8.7).
        assertNull(Requests.readCall("Hang up.", spotted = false, ringing = false))
        assertNull(Requests.readCall("Mute.", spotted = false, ringing = false))
        assertNull(Requests.readCall("Speaker, please.", spotted = false, ringing = false))
        assertNull(Requests.readCall("Hang up, Kestrel.", spotted = false, ringing = false))
    }

    @Test
    fun everythingElseIsNoCallCommand() {
        assertNull(Requests.readCall("Hang up.", spotted = false, ringing = true))
        assertNull(Requests.readCall("Answer.", spotted = false, ringing = false))
        assertNull(Requests.readCall("Answer the phone and say that I'm busy.", spotted = false, ringing = true))
        assertNull(Requests.readCall("Kestrel.", spotted = true, ringing = true))
        assertNull(Requests.readCall("", spotted = false, ringing = true))
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

    @Test
    fun continueAfterTheNameEndsAHandOver() {
        assertEquals(Request.Continue, Requests.read("Kestrel, continue.", spotted = false))
        assertEquals(Request.Continue, Requests.read("Kestrel, continue, please", spotted = true))
        assertEquals(Request.Continue, Requests.read("Continue.", spotted = false, afterName = true))
        assertEquals(Request.Task("continue the video"), Requests.read("Kestrel, continue the video", spotted = false))
        assertEquals(Request.Other("Continue"), Requests.read("Continue", spotted = false))
    }


    @Test
    fun kestrelsOwnRequestsForTheHistoryAndTheMemory() {
        for (text in listOf("Kestrel, delete history", "Kestrel, delete the history.", "Kestrel, delete my history", "Kestrel, clear history")) {
            assertEquals(text, Request.DeleteHistory, Requests.read(text, spotted = false))
        }
        assertEquals(Request.Remember("my locker", "number 12"), Requests.read("Kestrel, remember that my locker is number 12.", spotted = false))
        assertEquals(Request.Forget("my locker"), Requests.read("Kestrel, forget my locker!", spotted = false))
        assertEquals(Request.SaveRoutine("Morning news"), Requests.read("Kestrel, save that as Morning news", spotted = false))
        assertEquals(Request.SaveRoutine("test routine"), Requests.read("Save this as test routine.", spotted = false, afterName = true))
        // Forms that are no memory request stay tasks.
        assertEquals(Request.Task("remember to buy milk"), Requests.read("Kestrel, remember to buy milk", spotted = false))
        assertEquals(Request.Task("remember that is fine"), Requests.read("Kestrel, remember that is fine", spotted = false))
        assertEquals(Request.Task("forget it"), Requests.read("Kestrel, forget it", spotted = false))
        assertEquals(Request.Task("delete the history of Chrome"), Requests.read("Kestrel, delete the history of Chrome", spotted = false))
    }

    @Test
    fun turnOffAloneTurnsKestrelOffInTheFollowUpWindowButNotAfterYes() {
        assertEquals(Request.TurnOff, Requests.read("Turn off.", spotted = false, afterName = true, followUp = true))
        assertEquals(Request.TurnOff, Requests.read("switch off", spotted = false, afterName = true, followUp = true))
        assertEquals(Request.PauseListening, Requests.read("Stop listening.", spotted = false, afterName = true, followUp = true))
        assertEquals(Request.Task("turn off the lights"), Requests.read("turn off the lights", spotted = false, afterName = true, followUp = true))
        // After "Yes?" the owner may not have finished (plan 4E).
        assertEquals(Request.Task("Turn off."), Requests.read("Turn off.", spotted = false, afterName = true))
    }
}
