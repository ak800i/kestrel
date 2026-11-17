package dev.kestrel.commands.text

import dev.kestrel.commands.text.MatchDecision.Ambiguous
import dev.kestrel.commands.text.MatchDecision.Found
import dev.kestrel.commands.text.MatchDecision.NotFound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelMatcherTest {
    private fun alone(spoken: String, name: String, strictness: Strictness = Strictness.NORMAL) =
        LabelMatcher.decide(spoken, listOf(MatchCandidate("x", listOf(name))), strictness)

    private fun among(spoken: String, vararg names: String, strictness: Strictness = Strictness.NORMAL) =
        LabelMatcher.decide(spoken, names.map { MatchCandidate(it, listOf(it)) }, strictness)

    @Test
    fun thePairsOfResearchR23Match() {
        for ((spoken, name) in listOf(
            "wi fi" to "Wi-Fi",
            "wifi" to "Wi-Fi",
            "wi fi" to "Wi\u2011Fi",
            "setting" to "Settings",
            "color" to "Colour",
            "Giorgie" to "Đorđe",
            "Georgie" to "Đorđe",
            "Tonchi" to "Tonči",
            "viber" to "Viber",
            "veiber" to "Viber",
            "Delet" to "Delete",
            "djordje" to "Ђорђе",
        )) {
            assertEquals("$spoken to $name", Found("x"), alone(spoken, name))
        }
        assertEquals(Found("Delete"), among("delet", "Delete", "Send"))
    }

    @Test
    fun thePairsThatMustNotMatchDoNot() {
        for ((spoken, name) in listOf(
            "save" to "Safe",
            "back" to "Pack",
            "sound" to "Sand",
            "mail" to "Male",
            "on" to "Off",
            "call" to "Cell",
            "add" to "Odd",
            "dan" to "Dina",
            "ana" to "Anja",
            "ok" to "Oka",
            "no" to "Now",
            "no" to "Nor",
        )) {
            assertNotEquals("$spoken to $name", Found("x"), alone(spoken, name))
        }
    }

    @Test
    fun theExactLabelWinsOverASimilarOne() {
        assertEquals(Found("Save"), among("save", "Save", "Safe"))
        assertEquals(Found("Send"), among("send", "Send", "Send later"))
        assertEquals(Found("Send later"), among("send later", "Send", "Send later"))
    }

    @Test
    fun theOnlyExactNameActsWhateverIsNearIt() {
        assertEquals(Found("Ana"), among("ana", "Ana", "Anja"))
        assertEquals(Found("Mark"), among("mark", "Mark", "Marko", strictness = Strictness.STRICT))
        assertEquals(Found("Marija"), among("marija", "Maria", "Marija", strictness = Strictness.STRICT))
        assertEquals(Found("Đorđe"), among("djordje", "Đorđe", "Đorđa", strictness = Strictness.STRICT))
    }

    @Test
    fun twoExactNamesAskWithOnlyThem() {
        val candidates = listOf(
            MatchCandidate("first", listOf("Settings")),
            MatchCandidate("near", listOf("Setting")),
            MatchCandidate("second", listOf("Settings")),
        )

        assertEquals(Ambiguous(listOf("first", "second")), LabelMatcher.decide("settings", candidates, Strictness.NORMAL))
        assertEquals(Ambiguous(listOf("first", "second")), LabelMatcher.decide("the settings", candidates, Strictness.STRICT))
    }

    @Test
    fun withoutAnExactNameTheNearMissesAsk() {
        assertEquals(Ambiguous(listOf("x")), alone("ana", "Anja"))
        assertEquals(Ambiguous(listOf("Maria", "Marija")), among("mariya", "Maria", "Marija", "Send"))
    }

    @Test
    fun nothingCloseIsNotFound() {
        assertEquals(NotFound, among("weather", "Send", "Save"))
        assertEquals(NotFound, LabelMatcher.decide("send", emptyList(), Strictness.NORMAL))
    }

    @Test
    fun aPhraseThatStartsANameMatchesIt() {
        assertTrue(LabelMatcher.score("ana petrović", "Ana Petrović: See you at 5") >= MatchRules.PREFIX_SCORE)
        assertEquals(Found("x"), alone("Ana Petrović", "Ana Petrović: See you at 5"))
    }

    @Test
    fun leadingArticlesOfTheSpokenPhraseAreDropped() {
        assertEquals(1.0, LabelMatcher.score("the settings", "Settings"), 0.0)
        assertEquals(1.0, LabelMatcher.score("the", "The"), 0.0)
    }

    @Test
    fun aCandidateScoresItsBestName() {
        val candidates = listOf(MatchCandidate("row", listOf("Message from Ana", "Delete")), MatchCandidate("send", listOf("Send")))

        assertEquals(Found("row"), LabelMatcher.decide("delete", candidates, Strictness.NORMAL))
    }

    @Test
    fun aPhoneticKeyNeverAcceptsAWordOnItsOwn() {
        // Bare labels and contacts are strict: a word said to someone in the room taps nothing that only sounds like it.
        for ((spoken, name) in listOf(
            "black" to "Block",
            "dealt" to "Delete",
            "stare" to "Store",
            "shore" to "Share",
            "later" to "Letter",
            "class" to "Close",
            "council" to "Cancel",
            "roast" to "Reset",
        )) {
            assertNotEquals("$spoken to $name", Found("x"), alone(spoken, name, Strictness.STRICT))
        }
        // With a verb, the words must also be similar once respelled, as "veiber" and "Viber" are.
        for ((spoken, name) in listOf("later" to "Letter", "class" to "Close", "council" to "Cancel", "roast" to "Reset")) {
            assertNotEquals("$spoken to $name", Found("x"), alone(spoken, name))
        }
        assertEquals(Found("x"), alone("veiber", "Viber"))
        assertNotEquals(Found("x"), alone("veiber", "Viber", Strictness.STRICT))
    }

    @Test
    fun okayIsAnExactMatchForOk() {
        assertEquals(1.0, LabelMatcher.score("okay", "OK"), 0.0)
        assertEquals(Found("x"), alone("Okay.", "OK", Strictness.STRICT))
        assertEquals(Found("OK"), among("okay", "OK", "Okapi", strictness = Strictness.STRICT))
        assertEquals(Found("x"), alone("ok", "O.K."))
    }

    @Test
    fun aNameThatStartsWithAnArticleIsSaidExactly() {
        assertEquals(1.0, LabelMatcher.score("The Guardian", "The Guardian"), 0.0)
        assertEquals(Found("x"), alone("The Guardian", "The Guardian", Strictness.STRICT))
        assertTrue(LabelMatcher.score("the weather", "The Weather Channel") >= MatchRules.PREFIX_SCORE)
    }

    @Test
    fun aMarginWithinTheToleranceOfItsLimitPasses() {
        for (strict in listOf(true, false)) {
            val accept = if (strict) MatchRules.STRICT_ACCEPT else MatchRules.ACCEPT
            val margin = if (strict) MatchRules.STRICT_MARGIN else MatchRules.MARGIN
            val best = accept + 0.05
            // Doubles are not exact: 0.95 - 0.80 is 0.1499999999999999.
            assertTrue(LabelMatcher.accepts(best, best - margin + MatchRules.TOLERANCE / 2, strict))
            assertFalse(LabelMatcher.accepts(best, best - margin + MatchRules.TOLERANCE * 10, strict))
            assertTrue(LabelMatcher.accepts(accept - MatchRules.TOLERANCE / 2, null, strict))
            assertFalse(LabelMatcher.accepts(accept - MatchRules.TOLERANCE * 10, null, strict))
            assertFalse(LabelMatcher.accepts(best, best, strict))
        }
    }

    @Test
    fun printsNoNames() {
        assertFalse(MatchCandidate("key:Send", listOf("Send")).toString().contains("Send"))
        assertFalse(Found("key:Send").toString().contains("Send"))
        assertFalse(Ambiguous(listOf("key:Send")).toString().contains("Send"))
    }
}
