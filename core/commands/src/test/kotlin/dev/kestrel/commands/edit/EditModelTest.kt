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
    fun nothingPrintsTheText() {
        val texts = listOf(
            TextState("secret", 0, 0).toString(),
            EditStep.Change(0, 0, "secret", 6, 6).toString(),
            EditStep.Fail(EditFailure.NOT_FOUND, "secret").toString(),
        )

        for (text in texts) assertFalse(text, "secret" in text)
    }
}
