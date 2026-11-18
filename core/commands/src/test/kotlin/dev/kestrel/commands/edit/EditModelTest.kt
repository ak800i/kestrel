package dev.kestrel.commands.edit

import dev.kestrel.commands.Place
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.TextCommand.Delete
import dev.kestrel.commands.TextCommand.MoveCursor
import dev.kestrel.commands.TextCommand.Select
import dev.kestrel.commands.TextScope
import dev.kestrel.commands.TextUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The cursor, selection and deletion commands (design spec, section 9.7, and Appendix A). */
class EditModelTest {
    private fun edit(command: TextCommand, marked: String): String = Marks.after(EditModel.plan(command, Marks.state(marked)), marked)

    @Test
    fun aBareDeleteDeletesTheSelectionOrTheWordBeforeTheCursor() {
        assertEquals("hello | foo", edit(Delete(null), "hello [world] foo"))
        assertEquals("hello|", edit(Delete(null), "hello world|"))
        assertEquals("hello|", edit(Delete(null), "hello world |"))
        assertEquals("Fail NOTHING_THERE", edit(Delete(null), "|hello"))
    }

    @Test
    fun deleteAllToTheStartAndToTheEnd() {
        assertEquals("|", edit(Delete(TextScope.All), "one two| three"))
        assertEquals("| three", edit(Delete(TextScope.ToStart), "one two| three"))
        assertEquals("one two|", edit(Delete(TextScope.ToEnd), "one two| three"))
        assertEquals("Fail NOTHING_THERE", edit(Delete(TextScope.ToStart), "|one"))
        assertEquals("Fail NOTHING_THERE", edit(Delete(TextScope.All), "|"))
    }

    @Test
    fun deleteSelectedTextNeedsASelection() {
        assertEquals("one | three", edit(Delete(TextScope.Selection), "one [two] three"))
        assertEquals("Fail NOTHING_SELECTED", edit(Delete(TextScope.Selection), "one two|"))
    }

    @Test
    fun deletingAPhraseTakesTheNearestHitAndOneSpaceNextToIt() {
        assertEquals("see you| at noon", edit(Delete(TextScope.Phrase("tomorrow")), "see you tomorrow at noon|"))
        assertEquals("cat dog| dog cat", edit(Delete(TextScope.Phrase("cat")), "cat dog cat dog| cat"))
        assertEquals("|there", edit(Delete(TextScope.Phrase("hello")), "Hello there|"))
        assertEquals("Hi|, how", edit(Delete(TextScope.Phrase("Ana")), "Hi Ana, how|"))
        assertEquals(
            EditStep.Fail(EditFailure.NOT_FOUND, "dinner"),
            EditModel.plan(Delete(TextScope.Phrase("dinner")), Marks.state("see you tomorrow|")),
        )
    }

    @Test
    fun deleteFromToKeepsBothEndsAndBetweenLeavesThemOut() {
        val text = "say hello big world now|"

        assertEquals("say| now", edit(Delete(TextScope.FromTo("hello", "world")), text))
        assertEquals("say hello| world now", edit(Delete(TextScope.Between("hello", "world")), text))
        // The second phrase may also come first.
        assertEquals("|", edit(Delete(TextScope.FromTo("hello", "world")), "world then hello|"))
        assertEquals(
            EditStep.Fail(EditFailure.NOT_FOUND, "moon"),
            EditModel.plan(Delete(TextScope.FromTo("hello", "moon")), Marks.state(text)),
        )
    }

    @Test
    fun deleteUnitsBackwardsAndForwards() {
        assertEquals("one two|", edit(Delete(TextScope.Units(2, TextUnit.WORD, forward = false)), "one two three four|"))
        assertEquals("|three", edit(Delete(TextScope.Units(2, TextUnit.WORD, forward = true)), "|one two three"))
        assertEquals("abc|", edit(Delete(TextScope.Units(5, TextUnit.CHARACTER, forward = false)), "abcdefgh|"))
        assertEquals("One.|", edit(Delete(TextScope.Units(1, TextUnit.SENTENCE, forward = false)), "One. Two.|"))
        assertEquals("first|", edit(Delete(TextScope.Units(1, TextUnit.LINE, forward = false)), "first\nsecond|"))
        assertEquals("A.|", edit(Delete(TextScope.Units(2, TextUnit.PARAGRAPH, forward = false)), "A.\n\nB.\n\nC.|"))
        assertEquals("Fail NOTHING_THERE", edit(Delete(TextScope.Units(3, TextUnit.WORD, forward = false)), "|abc"))
    }

    @Test
    fun selectAndUnselect() {
        assertEquals("[one two]", edit(Select(TextScope.All), "one two|"))
        assertEquals("[one two] three", edit(Select(TextScope.ToStart), "one two| three"))
        assertEquals("one [two three]", edit(Select(TextScope.ToEnd), "one |two three"))
        assertEquals("one [two] three", edit(Select(TextScope.Phrase("two")), "one two three|"))
        assertEquals("[one two] three", edit(Select(TextScope.FromTo("one", "two")), "one two three|"))
        assertEquals("one [two three]", edit(Select(TextScope.Units(2, TextUnit.WORD, forward = false)), "one two three|"))
        assertEquals("[One. Two.] Three.", edit(Select(TextScope.Units(2, TextUnit.SENTENCE, forward = true)), "|One. Two. Three."))
        assertEquals("one two| three", edit(TextCommand.Unselect, "one [two] three"))
    }

    @Test
    fun theCursorMoves() {
        assertEquals("|one two", edit(MoveCursor(Place.Start), "one two|"))
        assertEquals("one two|", edit(MoveCursor(Place.End), "|one two"))
        assertEquals("one |two three", edit(MoveCursor(Place.Before("two")), "one two three|"))
        assertEquals("one two| three", edit(MoveCursor(Place.After("two")), "one two three|"))
        assertEquals("salt| pepper", edit(MoveCursor(Place.Between("salt", "pepper")), "salt pepper|"))
        assertEquals("one two| three", edit(MoveCursor(Place.Units(2, TextUnit.WORD, forward = true)), "|one two three"))
        assertEquals("one |two", edit(MoveCursor(Place.Units(3, TextUnit.CHARACTER, forward = false)), "one two|"))
        assertEquals("ab|\ncd", edit(MoveCursor(Place.Units(1, TextUnit.LINE, forward = true)), "|ab\ncd"))
        // From a selection, a move forwards starts at its end.
        assertEquals("one two three|", edit(MoveCursor(Place.Units(1, TextUnit.WORD, forward = true)), "one [two] three"))
        assertEquals("Fail NOTHING_THERE", edit(MoveCursor(Place.Units(1, TextUnit.CHARACTER, forward = false)), "|abc"))
    }

    @Test
    fun cyrillicAndDiacriticsInTheField() {
        assertEquals("Zdravo, [Đorđe]!", edit(Select(TextScope.Phrase("djordje")), "Zdravo, Đorđe!|"))
        assertEquals("Meet at the| today", edit(Delete(TextScope.Phrase("cafe")), "Meet at the café today|"))
    }

    @Test
    fun betweenGivesOnlyTheRangeThatDiffers() {
        assertEquals(EditStep.Change(6, 6, "there ", 11, 11), EditModel.between("hello world", TextState("hello there world", 11, 11)))
        assertEquals(EditStep.Change(0, 5, "", 0, 0), EditModel.between("hello", TextState("", 0, 0)))
    }

    @Test
    fun betweenNeverCutsACharacterInTwo() {
        // Undoing the deletion of 😀 from 😁😀😂: all three start with the same half.
        assertEquals(
            EditStep.Change(2, 2, "\uD83D\uDE00", 4, 4),
            EditModel.between("\uD83D\uDE01\uD83D\uDE02", TextState("\uD83D\uDE01\uD83D\uDE00\uD83D\uDE02", 4, 4)),
        )
        // U+1F600 and U+1FA00 end with the same half.
        assertEquals(EditStep.Change(1, 3, "\uD83E\uDE00", 3, 3), EditModel.between("a\uD83D\uDE00", TextState("a\uD83E\uDE00", 3, 3)))
    }

    @Test
    fun fromToPrefersThePairInTheSpokenOrderNearestTheCursor() {
        assertEquals("[hello A world] B hello", edit(Select(TextScope.FromTo("hello", "world")), "hello A world B hello|"))
        assertEquals("|B hello", edit(Delete(TextScope.FromTo("hello", "world")), "hello A world B hello|"))
        assertEquals("hello| world B hello", edit(MoveCursor(Place.Between("hello", "world")), "hello world B hello|"))
        // Of several pairs in the spoken order, the one nearest the cursor, from the last "hello" before its "world".
        assertEquals("hello A world [hello B world]", edit(Select(TextScope.FromTo("hello", "world")), "hello A world hello B world|"))
        assertEquals("hello [hello world] then world", edit(Select(TextScope.FromTo("hello", "world")), "hello hello world then world|"))
        assertEquals("[hello A world] hello B world", edit(Select(TextScope.FromTo("hello", "world")), "|hello A world hello B world"))
    }

    @Test
    fun deleteTheArticleAndTheNextWordWithItsAccentOrACharacterThatIsAnEmoji() {
        assertEquals("Is that| on", edit(Delete(TextScope.Phrase("the meeting")), "Is that meeting on|"))
        assertEquals("|ok", edit(Delete(TextScope.Units(1, TextUnit.WORD, forward = true)), "|cafe\u0301 ok"))
        assertEquals("ok |", edit(Delete(TextScope.Units(1, TextUnit.CHARACTER, forward = false)), "ok \uD83C\uDDF7\uD83C\uDDF8|"))
        assertEquals("ok |", edit(Delete(TextScope.Units(1, TextUnit.CHARACTER, forward = false)), "ok \uD83D\uDC4D\uD83C\uDFFD|"))
    }

    @Test
    fun anEmptyLineIsALineToDelete() {
        assertEquals("first\n|third", edit(Delete(TextScope.Units(1, TextUnit.LINE, forward = false)), "first\n\n|third"))
        assertEquals("first\n|\nthird", edit(MoveCursor(Place.Units(1, TextUnit.LINE, forward = true)), "first|\n\nthird"))
        // From an empty line, only the line that the owner names goes, and the empty line stays.
        assertEquals("x\n|", edit(Delete(TextScope.Units(1, TextUnit.LINE, forward = false)), "x\nhello\n|"))
        assertEquals("a\n|\nb", edit(Delete(TextScope.Units(1, TextUnit.LINE, forward = false)), "a\nprev\n|\nb"))
        assertEquals("a\n|", edit(Delete(TextScope.Units(1, TextUnit.LINE, forward = true)), "a\n|\nb"))
        assertEquals("|\nPara two.", edit(Delete(TextScope.Units(1, TextUnit.LINE, forward = false)), "Para one.\n|\nPara two."))
        // A paragraph goes with the blank lines that separated it from the next.
        assertEquals("|B.", edit(Delete(TextScope.Units(1, TextUnit.PARAGRAPH, forward = false)), "A.\n|\nB."))
    }

    @Test
    fun aFieldsReportedSelectionIsMadeValid() {
        assertEquals(TextState("hello", 5, 5), TextState.of("hello", -1, -1))
        assertEquals(TextState("hello", 5, 5), TextState.of("hello", 2, -1))
        assertEquals(TextState("hello", 1, 4), TextState.of("hello", 4, 1))
        assertEquals(TextState("hello", 3, 5), TextState.of("hello", 3, 9))
        assertEquals(TextState("hello", 5, 5), TextState.of("hello", 9, 7))
    }

    @Test
    fun nothingPrintsTheText() {
        val texts = listOf(
            TextState("secret", 0, 0).toString(),
            EditStep.Change(0, 0, "secret", 6, 6).toString(),
            EditStep.Fail(EditFailure.NOT_FOUND, "secret").toString(),
        )

        for (text in texts) assertFalse(text, "secret" in text)
    }

    @Test
    fun aFailPrintsItsReasonAndOnlyThePhrasesLength() {
        assertEquals("Fail(NOT_FOUND, phrase of 6 characters)", EditStep.Fail(EditFailure.NOT_FOUND, "secret").toString())
        assertEquals("Fail(NOTHING_THERE, no phrase)", EditStep.Fail(EditFailure.NOTHING_THERE).toString())
    }
}
