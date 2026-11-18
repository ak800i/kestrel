package dev.kestrel.commands.text

import org.junit.Assert.assertEquals
import org.junit.Test

class WordErrorsTest {
    @Test
    fun aTranscriptThatMatchesHasNoErrorsWhateverItsCaseAndPunctuation() {
        assertEquals(WordErrors(3, 0, 0, 0), WordErrors.count("Kestrel, open settings.", "kestrel open settings"))
    }

    @Test
    fun substitutionsDeletionsAndInsertionsAreCounted() {
        assertEquals(WordErrors(4, 1, 1, 0), WordErrors.count("Open the settings now.", "open a settings"))
        assertEquals(WordErrors(3, 0, 0, 1), WordErrors.count("Open the settings.", "please open the settings"))
    }

    @Test
    fun nothingHeardIsADeletionOfEveryWord() {
        val errors = WordErrors.count("Stop listening.", "")

        assertEquals(WordErrors(2, 0, 2, 0), errors)
        assertEquals(2, errors.errors)
    }

    @Test
    fun anApostropheStaysInItsWordAndAHyphenSplitsWords() {
        assertEquals(WordErrors(3, 0, 0, 0), WordErrors.count("It's top-rated.", "its top rated"))
    }

    @Test
    fun numbersCountTheSameInWordsAndInDigits() {
        assertEquals(WordErrors(6, 0, 0, 0), WordErrors.count("Set an alarm for 6:45.", "set an alarm for six forty-five"))
        assertEquals(WordErrors(3, 0, 0, 0), WordErrors.count("Tap number 12.", "Tap number twelve."))
        val timer = WordErrors.count("Set a timer for twenty five minutes.", "set a timer for 25 minutes")
        assertEquals(WordErrors(6, 0, 0, 0), timer)
    }
}
