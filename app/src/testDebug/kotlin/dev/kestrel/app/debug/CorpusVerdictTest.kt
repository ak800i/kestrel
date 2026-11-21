package dev.kestrel.app.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/** The verdict of a corpus line that voice-eval and voice-expect share (plan 4E). */
class CorpusVerdictTest {
    private val labels = listOf("Save", "Safe", "Send", "Send later", "Delete")
    private val contacts = listOf("Ana Petrović", "Mom")

    @Test
    fun anInstantCommandCountsWithItsScenesLabelsAndTheContacts() {
        // A near miss of a label or a contact is right when the matcher finds the same one, as on the emulator.
        assertEquals("1", CorpusVerdict.command("appendix", "Tap Send.", "Tap Sends.", labels, contacts))
        assertEquals("0", CorpusVerdict.command("appendix", "Tap Send.", "Tap Sends.", emptyList(), emptyList()))
        assertEquals("1", CorpusVerdict.command("appendix", "Call Ana Petrović.", "Call Anna Petrovic.", labels, contacts))
        assertEquals("0", CorpusVerdict.command("appendix", "Call Ana Petrović.", "Call Anna Petrovic.", labels, emptyList()))
        assertEquals("0", CorpusVerdict.command("appendix", "Tap Send.", "Tap Delete.", labels, contacts))
        // Nothing heard is a wrong command.
        assertEquals("0", CorpusVerdict.command("appendix", "Go back.", "", labels, contacts))
    }

    @Test
    fun conversationMustStayBareDictationMustTypeTheSameAndOtherGroupsDoNotCount() {
        assertEquals("1", CorpusVerdict.command("conversation", "Tell her I said hello.", "Tell her I said hello.", emptyList(), contacts))
        assertEquals("1", CorpusVerdict.command("conversation", "Tell her I said hello.", "", emptyList(), contacts))
        assertEquals("0", CorpusVerdict.command("conversation", "Tell her I said hello.", "Go back.", emptyList(), contacts))
        assertEquals("1", CorpusVerdict.command("dictation", "see you at five period", "see you at five period", emptyList(), contacts))
        assertEquals("0", CorpusVerdict.command("dictation", "see you at five period", "see you at five", emptyList(), contacts))
        assertEquals("-", CorpusVerdict.command("confusable", "Type my email is anna at example dot com.", "", emptyList(), contacts))
        assertEquals("-", CorpusVerdict.command("own", "Kestrel, wake up.", "Kestrel, wake up.", emptyList(), contacts))
    }
}
