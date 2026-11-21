package dev.kestrel.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One expectation of hook H2 (plan 4D): which transcript is the played clip's, and what the others count as. */
class ExpectationTest {
    @Test
    fun theFirstTranscriptWithAtMostHalfItsWordsWrongMatchesAndTheOthersAreStraysOrSplitParts() {
        val expectation = Expectation("t", "Scroll to the top.", noRun = false, until = 20_000L)

        assertEquals(4, expectation.words)
        assertFalse(expectation.offer("Scroll", speechEnded = 100, closed = 800, now = 1_200))
        assertFalse(expectation.offer("Hey, play some music.", speechEnded = 300, closed = 1_000, now = 1_500))
        assertTrue(expectation.waiting(1_600))
        assertTrue(expectation.offer("Scroll to top.", speechEnded = 900, closed = 1_600, now = 2_100))

        assertEquals(Expectation.Match(1, 900, 1_600, 2_100), expectation.match)
        assertEquals(1, expectation.errors)
        assertEquals(2, expectation.stray)
        assertEquals(1, expectation.split)
        // After the match, the next transcripts go to Kestrel as usual.
        assertFalse(expectation.waiting(2_200))
        assertFalse(expectation.holds(2_200))
    }

    @Test
    fun withoutRunningTheCorpusPassHoldsEveryTranscriptUntilTheEndAndANoMatchGivesTheClosestStray() {
        val corpus = Expectation("c", "Call Mom.", noRun = true, until = 5_000L)

        assertTrue(corpus.offer("Call mom", speechEnded = 100, closed = 200, now = 300) { "Call" to "1" })
        assertEquals(Expectation.Match(0, 100, 200, 300, kind = "Call", right = "1"), corpus.match)
        assertTrue(corpus.holds(4_999))
        assertFalse(corpus.holds(5_000))
        assertFalse(corpus.offer("Mom.", speechEnded = 400, closed = 500, now = 600))
        assertEquals(1, corpus.stray)

        val late = Expectation("l", "Open the settings.", noRun = false, until = 1_000L)
        assertFalse(late.offer("Hello there", speechEnded = 100, closed = 200, now = 300))
        assertFalse(late.offer("Open.", speechEnded = 400, closed = 500, now = 600))
        assertFalse(late.waiting(1_000))
        assertNull(late.match)
        assertEquals(2, late.errors)
        assertEquals(2, late.stray)
        assertEquals(1, late.split)
    }

    @Test
    fun aCorpusLineWithoutAMatchIsJudgedByItsClosestStrayAsVoiceEvalJudgesTheTranscript() {
        val text = "Tell her I said hello."
        val judge = { heard: String -> "none" to CorpusVerdict.command("conversation", text, heard, emptyList(), emptyList()) }
        val silence = CorpusVerdict.command("conversation", text, "", emptyList(), emptyList())

        // The air turned the line into a command: voice-eval says 0 for that transcript, not 1 as for silence.
        val command = Expectation("a", text, noRun = true, until = 5_000L)
        assertFalse(command.offer("Go back.", speechEnded = 100, closed = 200, now = 300, judge = judge))
        assertNull(command.match)
        assertEquals("0", command.right(silence))

        // A farther stray after the closest one does not change the verdict.
        val bare = Expectation("b", text, noRun = true, until = 5_000L)
        assertFalse(bare.offer("Tell her.", speechEnded = 100, closed = 200, now = 300, judge = judge))
        assertFalse(bare.offer("Go back.", speechEnded = 400, closed = 500, now = 600, judge = judge))
        assertEquals("1", bare.right(silence))

        // Only when nothing came is the line judged as an empty transcript.
        assertEquals("1", Expectation("c", text, noRun = true, until = 5_000L).right(silence))
    }
}
