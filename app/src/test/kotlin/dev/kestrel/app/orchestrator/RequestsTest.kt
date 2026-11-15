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
    }

    @Test
    fun stopAndCancelCountWithFillerWords() {
        assertEquals(Request.Stop(named = false), Requests.read("Stop, stop!", spotted = false))
        assertEquals(Request.Stop(named = false), Requests.read("Cancel that.", spotted = false))
        assertEquals(Request.Stop(named = false), Requests.read("Okay, stop.", spotted = false))
        assertEquals(Request.Other("Stop the timer"), Requests.read("Stop the timer", spotted = false))
    }

    @Test
    fun aTranscriptWithoutWordsIsNothing() {
        assertNull(Requests.read("", spotted = false))
        assertNull(Requests.read(" ... ", spotted = true))
    }

    @Test
    fun theOwnersWordsAreNotPrinted() {
        assertEquals("Task(17 characters)", Request.Task("open the settings").toString())
        assertEquals("Other(11 characters)", Request.Other("Scroll down").toString())
    }
}
