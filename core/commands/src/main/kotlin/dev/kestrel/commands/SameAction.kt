package dev.kestrel.commands

import dev.kestrel.commands.edit.Dictation
import dev.kestrel.commands.edit.DictationPart
import dev.kestrel.commands.edit.TextState
import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.ContactMatch
import dev.kestrel.commands.phone.ContactMatcher
import dev.kestrel.commands.text.LabelMatcher
import dev.kestrel.commands.text.MatchCandidate
import dev.kestrel.commands.text.MatchDecision
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Strictness
import dev.kestrel.commands.text.WordErrors
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Compares what a corpus line asks for with what Kestrel heard (design spec, section 16.4): whether the heard command
 * leads to the same action as the expected one.
 */
object Commands {
    /**
     * Whether [heard] is the same command as [expected]: the same variant with the same fields. A label or an app's name
     * counts as the same when the matcher finds the same one of the scene's [labels] for both, and a contact's name when
     * the contact matcher finds the same one of the [contacts]; when it finds none for the expected words, the folded
     * words must have no word errors. A number to call counts as the same only with the same digits, "+", "*" and "#".
     * Any other text, such as a typed text or a search, counts as the same when it has no word errors. A bare text counts
     * as the same when both find the same of the [labels] with the strict limits of bare labels, or else when its words
     * have no errors.
     */
    fun sameAction(expected: Command, heard: Command, labels: List<String>, contacts: List<String> = labels): Boolean =
        same(expected, heard, labels, contacts)

    /**
     * Whether dictating [heard] into an empty field types what dictating [expected] types: no word errors, and the same
     * punctuation marks in the same order.
     */
    fun sameDictation(expected: String, heard: String): Boolean {
        val want = dictated(expected)
        val got = dictated(heard)
        return WordErrors.count(want, got).errors == 0 && marks(want) == marks(got)
    }

    private fun same(expected: Any?, heard: Any?, labels: List<String>, contacts: List<String>): Boolean = when {
        expected == null || heard == null -> expected == heard
        expected.javaClass != heard.javaClass -> false
        expected is Target.Label -> sameName(expected.spoken, (heard as Target.Label).spoken, labels)
        expected is Command.Bare -> sameBare(expected, heard as Command.Bare, labels)
        expected is PhoneCommand.OpenApp ->
            expected.work == (heard as PhoneCommand.OpenApp).work && sameName(expected.name, heard.name, labels)
        expected is CallTarget.Name -> sameContact(expected, heard as CallTarget.Name, contacts)
        // The normaliser drops "+", "*" and "#", which make another number.
        expected is CallTarget.Number -> expected.digits == (heard as CallTarget.Number).digits
        expected is String -> sameWords(expected, heard as String)
        expected is Number || expected is Boolean || expected is Char || expected is Enum<*> -> expected == heard
        else -> fields(expected.javaClass).all { same(it.get(expected), it.get(heard), labels, contacts) }
    }

    private fun sameName(expected: String, heard: String, labels: List<String>): Boolean {
        val found = found(expected, labels, Strictness.NORMAL) ?: return sameWords(expected, heard)
        return found == found(heard, labels, Strictness.NORMAL)
    }

    private fun sameBare(expected: Command.Bare, heard: Command.Bare, labels: List<String>): Boolean {
        if (expected.number != heard.number) return false
        val found = found(expected.text, labels, Strictness.STRICT) ?: return sameWords(expected.text, heard.text)
        return found == found(heard.text, labels, Strictness.STRICT)
    }

    // The label that the matcher finds for [spoken] among [labels], or null.
    private fun found(spoken: String, labels: List<String>, strictness: Strictness): String? {
        val candidates = labels.map { MatchCandidate(it, listOf(it)) }
        return (LabelMatcher.decide(spoken, candidates, strictness) as? MatchDecision.Found)?.id
    }

    // The words after folding, so that "Petrović" and "Petrovic" are the same word, as the matcher takes them.
    private fun sameWords(expected: String, heard: String): Boolean =
        WordErrors.count(Normalizer.normalize(expected), Normalizer.normalize(heard)).errors == 0

    // The contact that Kestrel would call among [contacts], or else the folded words.
    private fun sameContact(expected: CallTarget.Name, heard: CallTarget.Name, contacts: List<String>): Boolean {
        if (expected.kind?.let(Normalizer::normalize) != heard.kind?.let(Normalizer::normalize)) return false
        val known = contacts.mapIndexed { index, name -> Contact(index.toLong(), listOf(name), emptyList(), starred = false) }
        val wanted = (ContactMatcher.find(expected.name, known) as? ContactMatch.One)?.contact?.id
            ?: return sameWords(expected.name, heard.name)
        return wanted == (ContactMatcher.find(heard.name, known) as? ContactMatch.One)?.contact?.id
    }

    // The text that dictating [spoken] types into an empty field; a new line is a line break.
    private fun dictated(spoken: String): String {
        var state = TextState("", 0, 0)
        for (part in Dictation.insert(state, spoken, password = false)) {
            state = when (part) {
                is DictationPart.Text -> part.change.let { change ->
                    TextState(state.text.replaceRange(change.start, change.end, change.replacement), change.selectionStart, change.selectionEnd)
                }
                DictationPart.NewLine -> {
                    val text = state.text.substring(0, state.selectionEnd) + "\n" + state.text.substring(state.selectionEnd)
                    TextState(text, state.selectionEnd + 1, state.selectionEnd + 1)
                }
            }
        }
        return state.text
    }

    private fun marks(text: String): String = text.filter { !it.isLetterOrDigit() && !it.isWhitespace() && it != '\'' }

    // The fields of a command's class, which are its properties; the classes come from this module's model.
    private fun fields(type: Class<*>): List<Field> =
        type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.onEach { it.isAccessible = true }
}
