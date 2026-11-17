# Instant Commands 3B: Dictation and Text Editing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the owner dictate and edit text by voice in any text field, in Android's views, in Compose and on web pages: dictation with spoken punctuation and spelling, "type X", new lines, Kestrel's own undo and redo, moving the cursor, selecting, deleting, inserting, replacing and changing case, "format email", the clipboard, the keyboard, password fields, "show password" and "search for X".

**Architecture:** A pure editing model in `:core:commands` (`dev.kestrel.commands.edit`) computes every offset, range and new text from a field's text and selection (`TextUnits`, `TextSearch`, `EditModel`), turns dictated words into text (`Dictation`) and keeps Kestrel's own history of its edits (`EditHistory`); `GrammarText` parses the text phrases. `TextEditor` in `:core:screen` applies each edit through Kestrel's accessibility input method first, with the set-text action as the fallback, and reads every edit back. The text part of the executor (`TextCommands` in `:app`) decides when dictation is on, finds the focused field, and, when no field has the focus, returns every command that needs one to the executor, which tries the words as a bare label.

**Tech Stack:** Kotlin 2.4.21 (the Kotlin standard library only in `:core:commands`, JUnit 4 for its tests), kotlinx.coroutines 1.11.0, Android's `AccessibilityService.getInputMethod()` and `InputMethod.AccessibilityInputConnection` (API 33), `AccessibilityNodeInfo` (`ACTION_SET_SELECTION`, `ACTION_SET_TEXT`, `ACTION_COPY`, `ACTION_CUT`, `ACTION_PASTE`, `ACTION_IME_ENTER`) at compileSdk 37, Jetpack Compose (`BasicTextField` with a `String`, a `TextFieldValue` and a `TextFieldState`) and a WebView in the test app's fixtures, JUnit 4 and AndroidX Test for the device tests on the `kestrel-api37` emulator through `scripts\command-test.ps1`.

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later; no new dependency that ships in the app (`:core:commands` uses only the Kotlin standard library; its tests use JUnit).
- `minSdk 33`, `targetSdk 37`, `compileSdk 37`. Every Android call that the A71 (Android 13) lacks has a check of the API level and a fallback.
- No dependency-injection framework; `AppGraph` wires by hand.
- New permissions: only `READ_CONTACTS` (runtime, asked at first use, plan 3C). Kestrel never requests `CALL_PHONE` or `ACCESS_NOTIFICATION_POLICY`, nor any permission on spec 13.6's "never" list.
- Never log audio, transcripts, replies, questions, boost words, app labels, screen text, contact names, phone numbers, field text, typed text, exception messages or an exit record's description (section 13.5); `toString` of anything that carries the owner's words or the screen's text prints only lengths, counts or kinds.
- Plans 3B and 3C create or change only the files of their own list in "Shared files and anchors" of the design (`.superpowers/sdd/plan-3-design.md`), at the places that it gives; any other file needs the controller.
- Test only on `kestrel-api37` (emulator-5554), and check it in the same command as each device command; never on the S23+ or the AVD `zoran`, and no command to any connected phone; the A71 only in the session with the owner. Gradle only through `scripts\gradle.ps1` (stopped with `--stop` at the end of each task); device tests only through `scripts\command-test.ps1`, `scripts\voice-test.ps1`, `scripts\connected-test.ps1` (with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`) or `scripts\listen-check.ps1`, as each task says; installs only through `scripts\install.ps1`. The command device tests grant what they need and never revoke a permission; `command-test.ps1` restores every setting, radio, volume, ringer mode, magnification, tile, lock-screen setting, contact, call-log entry, user profile, file and permission that they change, except Kestrel's microphone and notification permissions, which stay granted, and the APKs stay installed. A reboot of the emulator is a cold boot.
- Scripts run on Windows PowerShell 5.1 and PowerShell 7; `scripts\test-scripts.ps1` passes after every script change.
- The repository uses LF line endings.
- The plan file is named with the day it is written (`Get-Date -Format yyyy-MM-dd`), and it is frozen once committed; a later difference goes into `.superpowers/sdd/progress.md`.

## Before you start

- Plan 3A is complete; its last commit is in `.superpowers/sdd/progress.md`, section "Plan 3A". This plan was written against the code as plan 3A leaves it. Its "Changes from the plan" entries that touch this plan's files must be kept: this plan replaces only the stubs `GrammarText.kt`, `HelpText.kt`, `TextEditor.kt` and `TextCommands.kt` whole, and changes plan 3A's other files only at their markers (the test app's manifest and the README), and in the spec only the row of 9.3 that starts with `| Type |` and the bullets of section 9.7, as the design's "Shared files and anchors" allows.
- The totals at the start of this plan are the ones after plan 3A: JVM tests `:core:commands` 46, `:app` 314, `:core:screen` 55, `:core:voice` 140, `:core:privacy` 48, `:core:safety` 2; voice device tests 83; command device tests 36 (the package `dev.kestrel.app.commands` through `scripts\command-test.ps1`, among them `TouchCommandsTest` 20); other device tests 156 (through `scripts\connected-test.ps1`, which skips the 36 command tests there); Pester 144 in each shell. This plan changes only `:core:commands` (46 to 88), `:app` (314 to 323) and the command device tests (36 to 59, so that `connected-test.ps1` then runs 179 with 59 of them skipped). Each task gives the totals after it; before each task, compare the counts with the ones that the earlier tasks left. If a count differs, stop and ask the controller.
- Before each task, check that every text that the task replaces exists, and exists once, in its file. If a text is missing, stop and ask the controller.
- What this plan builds on (the code after plan 3A): the frozen model `TextCommand` with its variants, `TextScope`, `Place`, `TextUnit`, `TextCase` and `ClipboardAction` in `core/commands/src/main/kotlin/dev/kestrel/commands/TextCommandModel.kt`; `CommandParser` with its two phases, which asks `GrammarText.fixed` after `GrammarScreen.fixed` and `GrammarText.targeted` before `GrammarScreen.targeted`, and reads a `Bare` again without polite words around it; `cutWords(text, from, to)` and `TargetWords.read(words, text, from)`; `Normalizer.withOffsets` with `Folded.words` and `Folded.span`; `JaroWinkler.similarity`; `SpokenNumbers.number(words)`; the stub `TextEditor(service, snapshots, tracker)` with `FieldState` and `ClipboardKind`, reached as `ScreenSession.text`; `SnapshotCache.latest()`; `InstantCommands`, which asks the text part first, then the phone part, then the screen part, and runs `Command.Bare.of(heard)` through the screen part when none takes a command; `CommandPart.privateInput()`, which `Listening` asks before it shows a transcript; `ScreenCommands.act(screen, target, kind, conversation, onCell, onElement)` and `TargetKind.EDIT`; `ScreenTargets.candidates`, `names`, `chosen` and `still`; `CommandTexts.SCREEN_CHANGED`; the device-test helpers `CommandTests` (`only`, `start()`, `stop()`, `say`, `send`, `next`, `awaitSaid`, `graph`) and `ScreenServiceRule`; `CommandsActivity` with its dialog ("Open dialog", "Draft", one "Delete" button, "Deleted 1 time"); the markers `        <!-- Plan 3B: text fixtures -->` in the test app's manifest and `<!-- Plan 3B: dictation and editing -->` in `README.md`.
- Run Gradle only through `scripts\gradle.ps1`, and stop it with `scripts\gradle.ps1 --stop` at the end of each task.
- Run the command device tests only through `scripts\command-test.ps1 -Serial emulator-5554`, as each task says; they skip themselves without the instrumentation argument that only that script passes. Every command test class has `@get:Rule(order = 0) val only = CommandTests.only` before `@get:Rule(order = 1) val screen = ScreenServiceRule()`, as plan 3A's classes have, so that a skipped test changes nothing on the device.
- Every device command of this plan starts with a check of the emulator, in the same command: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' };` and then the command itself. If the check throws, or no emulator runs, start the emulator with `scripts\start-emulator.ps1`, check that the serial that it prints belongs to `kestrel-api37`, and use that serial in place of `emulator-5554`. `command-test.ps1` also refuses every device that is not an emulator. Never send a command to a connected phone.
- Before each task, run `git status --short`. If a file that the task changes (its **Files** list) already shows changes that are not the task's own, stop and ask the controller, because the task's `git add` would commit them too.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Not in this plan

Plan 3A (done before this plan): the command core, the Orchestrator's command rules (with "Still busy with the last command." for a `Bare` while a text command or dictation runs, and the neutral bar text "Typing in a password field."), the executor's frame, the screen commands, the stubs that this plan replaces, help (this plan only fills `HelpText`), the options and `command-test.ps1`.

Plan 3C (global actions, apps, phone settings, calls, the corpus and the A71 session): `GrammarPhone` and `HelpPhone`, `SystemControls`, the phone part, "back", "home", "open X", Wi-Fi, Bluetooth, volume, media, magnification, calls, contacts, "copy this phone number", the corpus with D8's text and dictation lines and `Commands.sameAction`, Appendix A's coverage table, the A71 session (its dictation step 9 uses this plan's commands with Samsung Keyboard). Plan 3C's corpus measures how well the recogniser hears this plan's phrases.

Sub-project 5: tasks, the agent and its whole-field typing (`ScreenActions.setText`, which this plan does not change; Task 7 only rewords spec 9.3's Type row to describe it). Later: languages other than English for commands and spoken punctuation; `SoftKeyboardController.setShowMode`; Android's movement actions; the app's own undo.

## Threads

| Call | Thread | Notes |
|---|---|---|
| `TextCommands.run` and every `TextEditor` function | The command's job (`Dispatchers.IO`) | The node actions and reads are blocking binder calls; the calls on the input connection and the reads of `currentInputEditorInfo` switch to the main thread |
| `TextCommands.privateInput()` | The controller's thread, before each transcript is shown | Reads only `SnapshotCache.latest()`, never the screen |
| `EditingState` | The command's job, and `password`/`follow` also from `privateInput` on the controller's thread | One command runs at a time; `stopped` is `@Volatile` and the password record's functions are `@Synchronized`, because `privateInput` may run beside a command |
| `EditHistory` | The command's job | Its functions are `@Synchronized` |
| `ScreenSession.numberBadges` and `grid` in `TextCommands.dictate` | The main thread (`withContext(Dispatchers.Main)`) | The overlays change only there |
| The fixtures' status lines | The test app's main thread | They report each change of a field's text or selection |

## File structure

| File | Responsibility |
|---|---|
| `core/commands/src/main/kotlin/dev/kestrel/commands/edit/TextState.kt`, `EditStep.kt` | Task 1: a field's text and selection; what an editing command does (`Change`, `Select`, `Fail` with `EditFailure`) |
| `core/commands/src/main/kotlin/dev/kestrel/commands/edit/TextUnits.kt`, `TextSearch.kt` | Task 1: characters, words, sentences, lines and paragraphs; finding a spoken phrase in a field's text with the normaliser's offsets |
| `core/commands/src/main/kotlin/dev/kestrel/commands/edit/EditModel.kt` | Task 1: the cursor, selection and deletion commands; Task 2: insert, replace, replace between, case and "format email" |
| `core/commands/src/main/kotlin/dev/kestrel/commands/edit/Dictation.kt`, `EditHistory.kt` | Task 2: dictated words into text (punctuation, spaces, capitals, spelling, passwords, parts at new lines); Kestrel's own undo and redo |
| `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarText.kt`, `HelpText.kt` | Task 3: the text phrases (replacing plan 3A's stubs) and their part of the command list |
| `core/screen/src/main/kotlin/dev/kestrel/screen/act/TextEditor.kt` | Task 4: reading a field and editing it through the input method with the set-text fallback (replacing plan 3A's stub) |
| `testapp/src/main/kotlin/dev/kestrel/testapp/EditActivity.kt`, `ComposeEditActivity.kt`, `WebEditActivity.kt`, `testapp/src/main/res/values/ids_text.xml`, `testapp/src/main/AndroidManifest.xml` (at the 3B marker) | Task 4: the text fixtures in views, Compose and a web page |
| `app/src/main/kotlin/dev/kestrel/app/commands/TextCommands.kt` | Task 5: the text part (replacing plan 3A's stub): dictation, start and stop editing, "type", new lines, undo and redo, `privateInput`; Task 6: the editing commands and password fields; Task 7: the clipboard, the keyboard, "show password" and "search for" |
| `app/src/main/kotlin/dev/kestrel/app/commands/TextCommandTexts.kt`, `EditingState.kt`, `TextFields.kt` | Task 5: the texts; when dictation is on and the history; which field a command acts on |
| `app/src/test/kotlin/dev/kestrel/app/commands/TextFieldsTest.kt` | Task 5: the field choices and dictation's on and off on the JVM |
| `app/src/androidTest/kotlin/dev/kestrel/app/commands/TextFieldTests.kt`, `TextEditorTest.kt`, `TextCommandsTest.kt` | Task 4: the helpers and the editor's device tests; Tasks 5 to 7: the text commands' device tests |
| `docs/superpowers/specs/2026-11-12-kestrel-design.md`, `README.md` | Task 7: spec 9.3's Type row and section 9.7; the README at the 3B marker |

---

### Task 1: The editing model, part 1: units, search, the cursor, selecting and deleting

**Files:**
- Create: `core/commands/src/main/kotlin/dev/kestrel/commands/edit/TextState.kt`, `EditStep.kt`, `TextUnits.kt`, `TextSearch.kt`, `EditModel.kt`
- Test: `core/commands/src/test/kotlin/dev/kestrel/commands/edit/Marks.kt` (a helper), `TextUnitsTest.kt`, `TextSearchTest.kt`, `EditModelTest.kt` (new)

**Interfaces:**
- Consumes: plan 3A's `TextCommand.Delete(scope: TextScope?)`, `Select(scope: TextScope)`, `Unselect`, `MoveCursor(place: Place)`, `TextScope` (`Selection`, `All`, `ToStart`, `ToEnd`, `Phrase(text)`, `FromTo(from, to)`, `Between(from, to)`, `Units(count, unit, forward)`), `Place` (`Start`, `End`, `Before(phrase)`, `After(phrase)`, `Between(first, second)`, `Units(count, unit, forward)`), `TextUnit`; `Normalizer.words(text)`, `Normalizer.withOffsets(text): Folded` with `Folded.text`, `Folded.words: List<IntRange>` and `Folded.span(fromWord, toWord): IntRange`; `JaroWinkler.similarity(a, b)`.
- Produces (package `dev.kestrel.commands.edit`): `data class TextState(val text: String, val selectionStart: Int, val selectionEnd: Int)` with `val hasSelection: Boolean` and `companion object { fun of(text: String, selectionStart: Int, selectionEnd: Int): TextState }` (a negative selection is the cursor at the end); `sealed interface EditStep { data class Change(val start: Int, val end: Int, val replacement: String, val selectionStart: Int, val selectionEnd: Int) { fun applyTo(text: String): String }; data class Select(val start: Int, val end: Int); data class Fail(val reason: EditFailure, val phrase: String? = null) }`; `enum class EditFailure { NOTHING_SELECTED, NOT_FOUND, NOTHING_THERE, NOT_AN_EDIT }`; `object TextUnits { fun segments(text: String, unit: TextUnit): List<IntRange>; fun move(text: String, from: Int, count: Int, unit: TextUnit, forward: Boolean): Int; fun span(state: TextState, count: Int, unit: TextUnit, forward: Boolean): IntRange? }`; `object TextSearch { const val SIMILAR = 0.85; fun find(text: String, phrase: String, cursor: Int): IntRange?; fun all(text: String, phrase: String): List<IntRange>; fun nearest(hits: List<IntRange>, cursor: Int): IntRange? }`; `object EditModel { fun plan(command: TextCommand, state: TextState): EditStep; fun between(from: String, to: TextState): EditStep.Change }`, which in this task plans `Delete`, `Select`, `Unselect` and `MoveCursor` and gives `Fail(NOT_AN_EDIT)` for the others. Every `toString` prints only lengths and offsets.

Design D8. Every offset, move and range is computed here from the field's text and selection, never by Android's movement actions, which WebView and Chrome take from their own cursor (prototype P4). A word is a run of letters and digits with apostrophes inside it; a sentence ends with ".", "!" or "?" (and the closing quotes or brackets after it) before whitespace or the end; a line ends at "\n", because Kestrel cannot see where the screen wraps a line; a paragraph ends at a blank line, and without one the whole text is one. A move forwards ends at the end of a unit and a move backwards at its start, as Ctrl and the arrow keys do; a bare count goes backwards from the cursor, "next" forwards. `TextSearch` finds a spoken phrase among the field's words after normalising both (so "djordje" finds "Đorđe" and "cafe" finds "Café"), maps the hit back to the field's own offsets, accepts a run of words whose average Jaro–Winkler similarity is at least 0.85 when there is no exact hit, and tries again without a leading "the", "a" or "an". Of several hits it takes the one that holds the cursor, else the one that ends nearest before it, else the nearest after it. "From X to Y" includes both, "between X and Y" leaves them out. A bare "delete" deletes the selection, or the word before the cursor, as Ctrl+Backspace does. A deleted word or phrase takes one space next to it along, so that no double space and no space before punctuation stays; a deleted line or paragraph takes the line break or the blank lines before it.

- [ ] **Step 1: Write the failing tests**

Create `core/commands/src/test/kotlin/dev/kestrel/commands/edit/Marks.kt`:

```kotlin
package dev.kestrel.commands.edit

/**
 * Texts with their selection marked, for the tests of the editing model: "|" is the cursor, and "[" and "]" enclose a
 * selection, as in "hello [world]". The tests' texts contain none of these characters.
 */
internal object Marks {
    fun state(marked: String): TextState {
        val text = marked.replace("[", "").replace("]", "").replace("|", "")
        if ('[' in marked) return TextState(text, marked.indexOf('['), marked.indexOf(']') - 1)
        val cursor = marked.indexOf('|')
        require(cursor >= 0) { "The text marks no cursor" }
        return TextState(text, cursor, cursor)
    }

    fun mark(text: String, start: Int, end: Int): String =
        if (start == end) {
            text.substring(0, start) + "|" + text.substring(start)
        } else {
            text.substring(0, start) + "[" + text.substring(start, end) + "]" + text.substring(end)
        }

    /** What [step] makes of [marked]: the new text with its selection marked, or "Fail" with the reason. */
    fun after(step: EditStep, marked: String): String {
        val text = state(marked).text
        return when (step) {
            is EditStep.Change -> mark(step.applyTo(text), step.selectionStart, step.selectionEnd)
            is EditStep.Select -> mark(text, step.start, step.end)
            is EditStep.Fail -> "Fail ${step.reason}"
        }
    }
}
```

Create `core/commands/src/test/kotlin/dev/kestrel/commands/edit/TextUnitsTest.kt`:

```kotlin
package dev.kestrel.commands.edit

import dev.kestrel.commands.TextUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextUnitsTest {
    private val text = "hello world foo"

    @Test
    fun aWordMoveEndsAtTheEdgeOfAWord() {
        assertEquals(5, TextUnits.move(text, 2, 1, TextUnit.WORD, forward = true))
        assertEquals(11, TextUnits.move(text, 6, 1, TextUnit.WORD, forward = true))
        assertEquals(15, TextUnits.move(text, 11, 1, TextUnit.WORD, forward = true))
        assertEquals(0, TextUnits.move(text, 6, 1, TextUnit.WORD, forward = false))
        assertEquals(6, TextUnits.move(text, 11, 1, TextUnit.WORD, forward = false))
        // A count beyond the text stops at its ends.
        assertEquals(0, TextUnits.move(text, 15, 5, TextUnit.WORD, forward = false))
        assertEquals(15, TextUnits.move(text, 0, 9, TextUnit.WORD, forward = true))
    }

    @Test
    fun aWordKeepsItsApostropheAndACharacterIsAWholeCharacter() {
        assertEquals(listOf(0..4, 6..9), TextUnits.segments("don't stop", TextUnit.WORD))
        val emoji = "a\uD83D\uDE00b"
        assertEquals(3, TextUnits.move(emoji, 1, 1, TextUnit.CHARACTER, forward = true))
        assertEquals(1, TextUnits.move(emoji, 3, 1, TextUnit.CHARACTER, forward = false))
        assertEquals(0, TextUnits.move("ab", 1, 5, TextUnit.CHARACTER, forward = false))
    }

    @Test
    fun aSentenceEndsWithItsMarkBeforeWhitespaceOrTheEnd() {
        val text = "One. Two? Three! It costs 1.5 euro. \"Done.\" Last"

        assertEquals(listOf(0..3, 5..8, 10..15, 17..34, 36..42, 44..47), TextUnits.segments(text, TextUnit.SENTENCE))
    }

    @Test
    fun aLineEndsAtANewlineAndAParagraphAtABlankLine() {
        val lines = "first line\nsecond\nthird"
        assertEquals(10, TextUnits.move(lines, 3, 1, TextUnit.LINE, forward = true))
        assertEquals(17, TextUnits.move(lines, 10, 1, TextUnit.LINE, forward = true))
        assertEquals(11, TextUnits.move(lines, 13, 1, TextUnit.LINE, forward = false))
        assertEquals(0, TextUnits.move(lines, 11, 1, TextUnit.LINE, forward = false))
        assertEquals(11, TextUnits.move(lines, 23, 2, TextUnit.LINE, forward = false))

        val paragraphs = "Para one.\n\nPara two line one\nline two\n\n\nPara three"
        assertEquals(listOf(0..8, 11..36, 40..49), TextUnits.segments(paragraphs, TextUnit.PARAGRAPH))
        // Without a blank line, the whole text is one paragraph.
        assertEquals(listOf(0..2), TextUnits.segments("a\nb", TextUnit.PARAGRAPH))
    }

    @Test
    fun aSpanGoesBackwardsFromTheSelectionsStartAndForwardsFromItsEnd() {
        assertEquals(6..14, TextUnits.span(TextState(text, 15, 15), 2, TextUnit.WORD, forward = false))
        assertEquals(0..4, TextUnits.span(TextState(text, 0, 0), 1, TextUnit.WORD, forward = true))
        assertEquals(11..14, TextUnits.span(TextState(text, 6, 11), 1, TextUnit.WORD, forward = true))
        assertNull(TextUnits.span(TextState(text, 15, 15), 1, TextUnit.WORD, forward = true))
    }
}
```

Create `core/commands/src/test/kotlin/dev/kestrel/commands/edit/TextSearchTest.kt`:

```kotlin
package dev.kestrel.commands.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextSearchTest {
    @Test
    fun aPhraseIsFoundAfterNormalisingAndMappedBackToTheFieldsOffsets() {
        val text = "Meet Đorđe at the Café, then lunch."

        assertEquals(5..9, TextSearch.find(text, "djordje", 0))
        assertEquals(18..21, TextSearch.find(text, "cafe", 0))
        assertEquals(14..21, TextSearch.find(text, "the café", 0))
        assertEquals(8..12, TextSearch.find("Здраво, Ђорђе!", "Djordje", 0))
    }

    @Test
    fun theHitNearestToTheCursorWins() {
        val text = "cat dog cat dog cat"

        // The one that ends nearest before the cursor.
        assertEquals(8..10, TextSearch.find(text, "cat", 11))
        // The one that holds the cursor.
        assertEquals(8..10, TextSearch.find(text, "cat", 9))
        // Else the nearest after it.
        assertEquals(0..2, TextSearch.find(text, "cat", 0))
        assertEquals(4..6, TextSearch.find(text, "dog", 3))
    }

    @Test
    fun aSimilarRunOfWordsCountsAndALeadingArticleMayBeMissing() {
        assertEquals(4..10, TextSearch.find("the meeting is at noon", "meting", 0))
        assertEquals(0..4, TextSearch.find("Lunch with Ana tomorrow", "the lunch", 23))
        assertNull(TextSearch.find("the meeting is at noon", "dinner", 0))
        assertNull(TextSearch.find("anything", "", 0))
    }
}
```

Create `core/commands/src/test/kotlin/dev/kestrel/commands/edit/EditModelTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'TextState'", "Unresolved reference 'EditStep'", "Unresolved reference 'TextUnits'", "Unresolved reference 'TextSearch'" and "Unresolved reference 'EditModel'".

- [ ] **Step 3: Add the state and the steps**

Create `core/commands/src/main/kotlin/dev/kestrel/commands/edit/TextState.kt`:

```kotlin
package dev.kestrel.commands.edit

/**
 * A text field's text and selection, as the editing model sees them (design spec, section 9.7). The selection runs from
 * [selectionStart] to [selectionEnd]; when they are equal, it is the cursor.
 */
data class TextState(val text: String, val selectionStart: Int, val selectionEnd: Int) {
    init {
        require(selectionStart in 0..selectionEnd && selectionEnd <= text.length) { "The selection lies outside the text" }
    }

    val hasSelection: Boolean get() = selectionStart < selectionEnd

    override fun toString() = "TextState(${text.length} characters, selection $selectionStart to $selectionEnd)"

    companion object {
        /**
         * The state of a field as Android reports it: a negative selection is a cursor at the end, a reversed one is
         * turned round, and offsets beyond the text end at the text's end.
         */
        fun of(text: String, selectionStart: Int, selectionEnd: Int): TextState {
            if (selectionStart < 0 || selectionEnd < 0) return TextState(text, text.length, text.length)
            val start = minOf(selectionStart, selectionEnd).coerceAtMost(text.length)
            val end = maxOf(selectionStart, selectionEnd).coerceAtMost(text.length)
            return TextState(text, start, end)
        }
    }
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/edit/EditStep.kt`:

```kotlin
package dev.kestrel.commands.edit

/** What an editing command does to a field (design spec, section 9.7). Every offset is computed by Kestrel itself. */
sealed interface EditStep {
    /** Replaces the text from [start] until [end] by [replacement], then selects [selectionStart] to [selectionEnd]. */
    data class Change(
        val start: Int,
        val end: Int,
        val replacement: String,
        val selectionStart: Int,
        val selectionEnd: Int,
    ) : EditStep {
        /** The text after this change. */
        fun applyTo(text: String): String = text.substring(0, start) + replacement + text.substring(end)

        override fun toString() =
            "Change($start to $end with ${replacement.length} characters, selection $selectionStart to $selectionEnd)"
    }

    /** Selects [start] to [end]; equal offsets put the cursor there. */
    data class Select(val start: Int, val end: Int) : EditStep

    /** The command cannot act on this text. [phrase] is the owner's words that the text does not contain. */
    data class Fail(val reason: EditFailure, val phrase: String? = null) : EditStep {
        override fun toString() = "Fail($reason, phrase of ${phrase?.length} characters)"
    }
}

/** Why an editing command cannot act. */
enum class EditFailure {
    /** "Delete selected text" while nothing is selected. */
    NOTHING_SELECTED,

    /** The text does not contain the phrase that the command names. */
    NOT_FOUND,

    /** There is nothing to delete, select or move over in that direction. */
    NOTHING_THERE,

    /** A text command that the editing model does not plan, such as "undo" or "copy". */
    NOT_AN_EDIT,
}
```

- [ ] **Step 4: Add the units and the search**

Create `core/commands/src/main/kotlin/dev/kestrel/commands/edit/TextUnits.kt`:

```kotlin
package dev.kestrel.commands.edit

import dev.kestrel.commands.TextUnit

/**
 * The units of the editing commands (design spec, section 9.7): a character; a word, a run of letters and digits with
 * apostrophes inside it; a sentence, up to and including ".", "!" or "?" before whitespace or the end; a line, between
 * newlines (Kestrel cannot see where the screen wraps a line); and a paragraph, between blank lines, or the whole text
 * without one. A move forwards ends at the end of a unit and a move backwards at its start, as Ctrl and the arrow keys
 * do.
 */
object TextUnits {
    /** The units of [unit] in [text], without what separates them, from the start. Characters have none. */
    fun segments(text: String, unit: TextUnit): List<IntRange> = when (unit) {
        TextUnit.CHARACTER -> emptyList()
        TextUnit.WORD -> words(text)
        TextUnit.SENTENCE -> sentences(text)
        TextUnit.LINE -> lines(text)
        TextUnit.PARAGRAPH -> paragraphs(text)
    }

    /** The offset that [count] units from [from] lead to, forwards or backwards; it stops at the text's ends. */
    fun move(text: String, from: Int, count: Int, unit: TextUnit, forward: Boolean): Int {
        if (unit == TextUnit.CHARACTER) {
            var offset = from
            repeat(count) {
                offset = when {
                    forward && offset < text.length -> text.offsetByCodePoints(offset, 1)
                    !forward && offset > 0 -> text.offsetByCodePoints(offset, -1)
                    else -> offset
                }
            }
            return offset
        }
        val segments = segments(text, unit)
        return if (forward) {
            val ends = segments.map { it.last + 1 }.filter { it > from }
            ends.getOrElse(count - 1) { text.length }
        } else {
            val starts = segments.map { it.first }.filter { it < from }
            starts.getOrElse(starts.size - count) { 0 }
        }
    }

    /**
     * The text that [count] units cover from the cursor: backwards from the selection's start, or forwards from its end.
     * Null when there is nothing in that direction.
     */
    fun span(state: TextState, count: Int, unit: TextUnit, forward: Boolean): IntRange? {
        val range = if (forward) {
            state.selectionEnd until move(state.text, state.selectionEnd, count, unit, forward = true)
        } else {
            move(state.text, state.selectionStart, count, unit, forward = false) until state.selectionStart
        }
        return range.takeUnless { it.isEmpty() }
    }

    private fun isWordChar(text: String, index: Int): Boolean {
        val char = text[index]
        if (char.isLetterOrDigit()) return true
        // An apostrophe inside a word, as in "don't".
        return char in APOSTROPHES && index > 0 && index < text.length - 1 &&
            text[index - 1].isLetterOrDigit() && text[index + 1].isLetterOrDigit()
    }

    private fun words(text: String): List<IntRange> = buildList {
        var start = -1
        for (index in 0..text.length) {
            val inWord = index < text.length && isWordChar(text, index)
            if (inWord && start < 0) start = index
            if (!inWord && start >= 0) {
                add(start until index)
                start = -1
            }
        }
    }

    private fun sentences(text: String): List<IntRange> = buildList {
        var start = -1
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (start < 0 && !char.isWhitespace()) start = index
            if (start >= 0 && char in ENDS) {
                // Closing quotes and brackets after the mark belong to the sentence.
                var end = index + 1
                while (end < text.length && text[end] in CLOSERS) end++
                if (end == text.length || text[end].isWhitespace()) {
                    add(start until end)
                    start = -1
                    index = end
                    continue
                }
            }
            index++
        }
        if (start >= 0) add(start until text.trimEnd().length)
    }

    private fun lines(text: String): List<IntRange> = buildList {
        var start = 0
        while (true) {
            val newline = text.indexOf('\n', start)
            if (newline < 0) {
                add(start until text.length)
                return@buildList
            }
            add(start until newline)
            start = newline + 1
        }
    }

    private fun paragraphs(text: String): List<IntRange> = buildList {
        var start = 0
        for (blank in BLANK_LINES.findAll(text)) {
            addTrimmed(text, start, blank.range.first)
            start = blank.range.last + 1
        }
        addTrimmed(text, start, text.length)
    }

    private fun MutableList<IntRange>.addTrimmed(text: String, from: Int, to: Int) {
        var start = from
        var end = to
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start < end) add(start until end)
    }

    private val APOSTROPHES = setOf('\'', '\u2019')
    private val ENDS = setOf('.', '!', '?')
    private val CLOSERS = setOf('"', '\'', '\u201D', '\u2019', ')', ']')
    private val BLANK_LINES = Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)*")
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/edit/TextSearch.kt`:

```kotlin
package dev.kestrel.commands.edit

import dev.kestrel.commands.text.JaroWinkler
import dev.kestrel.commands.text.Normalizer

/**
 * Finds a spoken phrase in a field's text (design spec, section 9.7). It compares the phrase's words with the text's
 * words after normalising both (section 9.6), and maps a hit back to the text's own offsets. Without an exact hit, a
 * run of as many words whose average Jaro–Winkler similarity is at least [SIMILAR] counts; without any hit, the phrase
 * is tried once more without a leading "the", "a" or "an".
 */
object TextSearch {
    const val SIMILAR = 0.85

    /**
     * The hit of [phrase] in [text] nearest to [cursor]: one that contains the cursor, else the one that ends nearest
     * before it, else the nearest after it. Null when the text does not contain the phrase.
     */
    fun find(text: String, phrase: String, cursor: Int): IntRange? = nearest(all(text, phrase), cursor)

    /** Every hit of [phrase] in [text], from the start, as ranges of [text]. */
    fun all(text: String, phrase: String): List<IntRange> {
        val words = Normalizer.words(phrase)
        if (words.isEmpty()) return emptyList()
        val hits = hits(text, words)
        if (hits.isNotEmpty() || words.size < 2 || words[0] !in ARTICLES) return hits
        return hits(text, words.drop(1))
    }

    /** Of [hits], the one that contains [cursor], else the one that ends nearest before it, else the nearest after it. */
    fun nearest(hits: List<IntRange>, cursor: Int): IntRange? =
        hits.firstOrNull { it.first < cursor && it.last + 1 > cursor }
            ?: hits.filter { it.last + 1 <= cursor }.maxByOrNull { it.last }
            ?: hits.filter { it.first >= cursor }.minByOrNull { it.first }

    private fun hits(text: String, words: List<String>): List<IntRange> {
        val folded = Normalizer.withOffsets(text)
        val fieldWords = folded.words.map { folded.text.substring(it.first, it.last + 1) }
        val starts = 0..fieldWords.size - words.size
        val exact = starts.filter { start -> words.indices.all { fieldWords[start + it] == words[it] } }
        val found = exact.ifEmpty {
            starts.filter { start ->
                words.indices.sumOf { JaroWinkler.similarity(fieldWords[start + it], words[it]) } / words.size >= SIMILAR
            }
        }
        return found.map { folded.span(it, it + words.size) }
    }

    private val ARTICLES = setOf("the", "a", "an")
}
```

- [ ] **Step 5: Add the editing model's cursor, selection and deletion commands**

Create `core/commands/src/main/kotlin/dev/kestrel/commands/edit/EditModel.kt`:

```kotlin
package dev.kestrel.commands.edit

import dev.kestrel.commands.Place
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.TextScope
import dev.kestrel.commands.TextUnit

/**
 * Plans the editing commands (design spec, section 9.7): every offset, move and range comes from the field's text and
 * selection, never from Android's movement actions, which WebView and Chrome take from their own cursor. A phrase is
 * found with [TextSearch], nearest to the cursor.
 */
object EditModel {
    fun plan(command: TextCommand, state: TextState): EditStep = when (command) {
        is TextCommand.Delete -> delete(command.scope, state)
        is TextCommand.Select -> select(command.scope, state)
        TextCommand.Unselect -> EditStep.Select(state.selectionEnd, state.selectionEnd)
        is TextCommand.MoveCursor -> move(command.place, state)
        else -> EditStep.Fail(EditFailure.NOT_AN_EDIT)
    }

    /** The change that turns [from] into [to]: only the range that differs, then [to]'s selection. Undo and redo use it. */
    fun between(from: String, to: TextState): EditStep.Change {
        val shorter = minOf(from.length, to.text.length)
        var prefix = 0
        while (prefix < shorter && from[prefix] == to.text[prefix]) prefix++
        var suffix = 0
        while (suffix < shorter - prefix && from[from.length - 1 - suffix] == to.text[to.text.length - 1 - suffix]) suffix++
        val replacement = to.text.substring(prefix, to.text.length - suffix)
        return EditStep.Change(prefix, from.length - suffix, replacement, to.selectionStart, to.selectionEnd)
    }

    private fun delete(scope: TextScope?, state: TextState): EditStep {
        val text = state.text
        if (scope == null) {
            if (state.hasSelection) return removal(state.selectionStart, state.selectionEnd)
            // A bare "delete" deletes the word before the cursor, as Ctrl+Backspace does.
            val start = TextUnits.move(text, state.selectionStart, 1, TextUnit.WORD, forward = false)
            if (start == state.selectionStart) return EditStep.Fail(EditFailure.NOTHING_THERE)
            return tidied(text, start, state.selectionStart)
        }
        val range = when (val found = range(scope, state)) {
            is Missing -> return found.step
            is Found -> found.range
        }
        if (range.isEmpty()) return EditStep.Fail(EditFailure.NOTHING_THERE)
        val end = range.last + 1
        return when {
            scope is TextScope.Phrase || scope is TextScope.FromTo || scope is TextScope.Between -> tidied(text, range.first, end)
            scope is TextScope.Units && (scope.unit == TextUnit.WORD || scope.unit == TextUnit.SENTENCE) ->
                tidied(text, range.first, end)
            scope is TextScope.Units && (scope.unit == TextUnit.LINE || scope.unit == TextUnit.PARAGRAPH) ->
                lineTidied(text, range.first, end, paragraph = scope.unit == TextUnit.PARAGRAPH)
            else -> removal(range.first, end)
        }
    }

    private fun select(scope: TextScope, state: TextState): EditStep = when (val found = range(scope, state)) {
        is Missing -> found.step
        is Found -> EditStep.Select(found.range.first, found.range.last + 1)
    }

    private fun move(place: Place, state: TextState): EditStep = when (val found = offset(place, state)) {
        is Missing -> found.step
        is Found -> EditStep.Select(found.range.first, found.range.first)
    }

    /** Where [place] is in [state]'s text, as an empty range at that offset. */
    private fun offset(place: Place, state: TextState): Lookup {
        val text = state.text
        val cursor = state.selectionEnd
        val offset = when (place) {
            Place.Start -> 0
            Place.End -> text.length
            is Place.Before -> (TextSearch.find(text, place.phrase, cursor) ?: return notFound(place.phrase)).first
            is Place.After -> (TextSearch.find(text, place.phrase, cursor) ?: return notFound(place.phrase)).last + 1
            is Place.Between -> when (val both = pair(text, place.first, place.second, cursor)) {
                is PairLookup.Missing -> return Missing(both.step)
                is PairLookup.Both -> both.first.last + 1
            }
            is Place.Units -> {
                val from = if (place.forward) state.selectionEnd else state.selectionStart
                val to = TextUnits.move(text, from, place.count, place.unit, place.forward)
                if (to == from && !state.hasSelection) return Missing(EditStep.Fail(EditFailure.NOTHING_THERE))
                to
            }
        }
        return Found(offset until offset)
    }

    /** The text that [scope] names in [state]. */
    private fun range(scope: TextScope, state: TextState): Lookup {
        val text = state.text
        val cursor = state.selectionEnd
        val range = when (scope) {
            TextScope.Selection -> if (state.hasSelection) {
                state.selectionStart until state.selectionEnd
            } else {
                return Missing(EditStep.Fail(EditFailure.NOTHING_SELECTED))
            }
            TextScope.All -> text.indices
            TextScope.ToStart -> 0 until state.selectionEnd
            TextScope.ToEnd -> state.selectionStart until text.length
            is TextScope.Phrase -> TextSearch.find(text, scope.text, cursor) ?: return notFound(scope.text)
            is TextScope.FromTo -> when (val both = pair(text, scope.from, scope.to, cursor)) {
                is PairLookup.Missing -> return Missing(both.step)
                is PairLookup.Both -> both.first.first..both.second.last
            }
            is TextScope.Between -> when (val both = pair(text, scope.from, scope.to, cursor)) {
                is PairLookup.Missing -> return Missing(both.step)
                is PairLookup.Both -> trimmed(text, both.first.last + 1, both.second.first)
            }
            is TextScope.Units -> TextUnits.span(state, scope.count, scope.unit, scope.forward)
                ?: return Missing(EditStep.Fail(EditFailure.NOTHING_THERE))
        }
        return Found(range)
    }

    /**
     * [first] nearest to the cursor, and [second] nearest after it (or else nearest before it), in the order in which
     * they stand in the text.
     */
    private fun pair(text: String, first: String, second: String, cursor: Int): PairLookup {
        val one = TextSearch.find(text, first, cursor)
            ?: return PairLookup.Missing(EditStep.Fail(EditFailure.NOT_FOUND, first))
        val others = TextSearch.all(text, second).filter { it != one }
        val after = others.filter { it.first > one.last }.minByOrNull { it.first }
        if (after != null) return PairLookup.Both(one, after)
        val before = others.filter { it.last < one.first }.maxByOrNull { it.last }
            ?: return PairLookup.Missing(EditStep.Fail(EditFailure.NOT_FOUND, second))
        return PairLookup.Both(before, one)
    }

    private fun removal(start: Int, end: Int) = EditStep.Change(start, end, "", start, start)

    /**
     * Deletes [start] until [end] with one space next to it, so that no double space and no space before punctuation
     * stays behind: the space before it, or at the start of a line the space after it.
     */
    private fun tidied(text: String, start: Int, end: Int): EditStep.Change {
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(end)
        return when {
            before == ' ' && (after == null || after.isWhitespace() || after in CLOSING) -> removal(start - 1, end)
            (before == null || before == '\n') && after == ' ' -> removal(start, end + 1)
            else -> removal(start, end)
        }
    }

    /** Deletes whole lines or paragraphs with the line break, or the blank lines, that separated them from the rest. */
    private fun lineTidied(text: String, start: Int, end: Int, paragraph: Boolean): EditStep.Change {
        var from = start
        var to = end
        val endsLine = to == text.length || text[to] == '\n'
        val startsLine = from == 0 || text[from - 1] == '\n'
        if (endsLine && from > 0 && text[from - 1] == '\n') {
            from--
            if (paragraph) while (from > 0 && text[from - 1].isWhitespace()) from--
        } else if (startsLine && to < text.length && text[to] == '\n') {
            to++
            if (paragraph) while (to < text.length && text[to].isWhitespace()) to++
        }
        return removal(from, to)
    }

    /** [start] until [end] without the whitespace at both ends. */
    private fun trimmed(text: String, start: Int, end: Int): IntRange {
        var from = start
        var to = end
        while (from < to && text[from].isWhitespace()) from++
        while (to > from && text[to - 1].isWhitespace()) to--
        return from until to
    }

    private fun notFound(phrase: String) = Missing(EditStep.Fail(EditFailure.NOT_FOUND, phrase))

    private sealed interface Lookup

    private class Found(val range: IntRange) : Lookup

    private class Missing(val step: EditStep.Fail) : Lookup

    private sealed interface PairLookup {
        class Both(val first: IntRange, val second: IntRange) : PairLookup

        class Missing(val step: EditStep.Fail) : PairLookup
    }

    private val CLOSING = setOf('.', ',', '!', '?', ';', ':', ')', ']')
}
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD SUCCESSFUL`, with 65 tests in `:core:commands` (46 + 19): `TextUnitsTest` 5, `TextSearchTest` 3 and `EditModelTest` 11.

- [ ] **Step 7: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` 65, `:app` 314, `:core:screen` 55, `:core:voice` 140, `:core:privacy` 48, `:core:safety` 2), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit.

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
git add core/commands/src/main/kotlin/dev/kestrel/commands/edit core/commands/src/test/kotlin/dev/kestrel/commands/edit
git commit -m "feat(commands): plan the cursor, selection and deletion commands from a field's text and selection" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: The editing model, part 2: inserting, replacing, case, "format email", dictation and the history

**Files:**
- Create: `core/commands/src/main/kotlin/dev/kestrel/commands/edit/Dictation.kt`, `EditHistory.kt`
- Modify: `core/commands/src/main/kotlin/dev/kestrel/commands/edit/EditModel.kt` (imports, `plan`, the new functions, `APOSTROPHES`)
- Test: `core/commands/src/test/kotlin/dev/kestrel/commands/edit/EditModelChangesTest.kt`, `DictationTest.kt`, `EditHistoryTest.kt` (new)

**Interfaces:**
- Consumes: Task 1's `TextState`, `EditStep`, `EditFailure`, `TextSearch.find`, `EditModel.plan` and its private `offset`, `pair`, `trimmed`, `removal` and `Lookup`; plan 3A's `TextCommand.Insert(text, place)`, `Replace(old, new)`, `ReplaceBetween(from, to, new)`, `ChangeCase(case, phrase)`, `FormatEmail`, `TextCase`.
- Produces (package `dev.kestrel.commands.edit`): `sealed interface DictationPart { data class Text(val change: EditStep.Change); data object NewLine }`; `object Dictation { fun insert(state: TextState, spoken: String, password: Boolean, startsUtterance: Boolean = true): List<DictationPart>; fun render(state: TextState, spoken: String, startsUtterance: Boolean): EditStep.Change; fun phrase(spoken: String): String; fun formatEmail(text: String): String }`; `class EditHistory(maxEdits: Int = 50, maxFields: Int = 20) { fun record(field: String, before: TextState, after: TextState); fun canUndo(field: String): Boolean; fun canRedo(field: String): Boolean; fun undo(field: String, current: TextState): TextState?; fun redo(field: String, current: TextState): TextState? }`; `EditModel.plan` now also plans `Insert`, `Replace`, `ReplaceBetween`, `ChangeCase` and `FormatEmail`.

Design D8 and the controller's decision 2. `Dictation` types only the punctuation that the owner says, and drops the recogniser's own, which adds a full stop to almost every short utterance; marks between digits stay ("3.5", "10:30"). The first word of an utterance loses its capital unless it is "I", one of its contractions, or written in capitals ("NASA"); the other words keep the recogniser's case. A word gets a capital at the start of an empty field and after ".", "?" or "!" before the insertion point, whether the owner says it now or the field had it already. No space goes before closing punctuation and one goes after it, and a closing mark takes the place of the one space that the field has before the cursor ("hello |" and "period" give "hello.", also after a web editor's no-break space); "dot" joins without a space and starts no sentence ("example dot com" gives "example.com"); a space goes between the text before the cursor and the new words when neither side is whitespace, and after the new words when they go in before a word of the field. Double quotes open and close by how many come before the cursor. "New line" and "new paragraph" split the result into parts, because a line break goes in with the Enter key (Task 4). Words that spell something ("capital p one two three", "a b c") are joined without spaces, and in a password field every word is, the names of letters ("bee", "double u") too, and spoken punctuation goes in as its mark ("hunter two exclamation mark" gives "hunter2!"), as do "hash", "dollar sign", "asterisk", "plus sign" and "equals sign", which only a password field takes. `phrase` gives the new words of "replace X with Y" with their punctuation and in the recogniser's case. "Insert X before Y" inserts X there as dictation. `EditHistory` keeps one entry per command, and undoes or redoes only while the field still shows the text that Kestrel left; otherwise it drops the field's history.

- [ ] **Step 1: Write the failing tests**

Create `core/commands/src/test/kotlin/dev/kestrel/commands/edit/EditModelChangesTest.kt`:

```kotlin
package dev.kestrel.commands.edit

import dev.kestrel.commands.Place
import dev.kestrel.commands.TextCase
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.TextCommand.ChangeCase
import dev.kestrel.commands.TextCommand.Insert
import dev.kestrel.commands.TextCommand.Replace
import dev.kestrel.commands.TextCommand.ReplaceBetween
import org.junit.Assert.assertEquals
import org.junit.Test

/** Inserting, replacing, changing case and "format email" (design spec, section 9.7, and Appendix A). */
class EditModelChangesTest {
    private fun edit(command: TextCommand, marked: String): String = Marks.after(EditModel.plan(command, Marks.state(marked)), marked)

    @Test
    fun insertPutsTheWordsAtThePlaceWithSpacesAround() {
        assertEquals("a really |nice day", edit(Insert("really", Place.Before("nice")), "a nice day|"))
        assertEquals("a really very| nice", edit(Insert("very", Place.After("really")), "a really nice|"))
        assertEquals("salt and| pepper", edit(Insert("and", Place.Between("salt", "pepper")), "salt pepper|"))
        assertEquals("it is too |late", edit(Insert("too", Place.Before("late")), "it is late|"))
        assertEquals("Hello |world", edit(Insert("hello", Place.Start), "world|"))
        // Spoken punctuation works in the inserted words.
        assertEquals("yes,| I can", edit(Insert("comma", Place.After("yes")), "yes I can|"))
        assertEquals("hello. |world", edit(Insert("period", Place.Before("world")), "hello world|"))
        assertEquals("Fail NOT_FOUND", edit(Insert("very", Place.Before("moon")), "a nice day|"))
    }

    @Test
    fun replaceTakesTheNearestHitAndKeepsTheNewWordsAsSaid() {
        assertEquals("See you on Tuesday|.", edit(Replace("Monday", "Tuesday"), "See you on Monday.|"))
        assertEquals("cat and dog|", edit(Replace("cat", "dog"), "cat and cat|"))
        assertEquals("okay!|", edit(Replace("ok", "okay exclamation mark"), "ok|"))
        assertEquals("Fail NOT_FOUND", edit(Replace("moon", "sun"), "cat and cat|"))
    }

    @Test
    fun replaceEverythingBetweenKeepsBothEnds() {
        assertEquals("hello hi| world", edit(ReplaceBetween("hello", "world", "hi"), "hello big old world|"))
        assertEquals("hello hi| world", edit(ReplaceBetween("hello", "world", "hi"), "hello world|"))
    }

    @Test
    fun capitalizeUppercaseAndLowercase() {
        assertEquals("i love Paris| and london", edit(ChangeCase(TextCase.CAPITALIZE, "paris"), "i love paris and london|"))
        assertEquals("in New York|", edit(ChangeCase(TextCase.CAPITALIZE, "new york"), "in new york|"))
        assertEquals("O'neil|", edit(ChangeCase(TextCase.CAPITALIZE, "oneil"), "o'neil|"))
        assertEquals("the NASA| team", edit(ChangeCase(TextCase.UPPER, "nasa"), "the nasa team|"))
        assertEquals("say hello|", edit(ChangeCase(TextCase.LOWER, "hello"), "say HELLO|"))
    }

    @Test
    fun formatEmailChangesTheSelectionOrElseTheWholeText() {
        assertEquals("john.smith@example.com|", edit(TextCommand.FormatEmail, "John dot Smith at example dot com|"))
        assertEquals("Mail: john@example.com|", edit(TextCommand.FormatEmail, "Mail: [john at example dot com]"))
        assertEquals("Fail NOTHING_THERE", edit(TextCommand.FormatEmail, "|"))
    }

    @Test
    fun commandsThatAreNoEditsArePlannedElsewhere() {
        assertEquals("Fail NOT_AN_EDIT", edit(TextCommand.Undo, "hello|"))
        assertEquals("Fail NOT_AN_EDIT", edit(TextCommand.Type("hi"), "hello|"))
    }
}
```

Create `core/commands/src/test/kotlin/dev/kestrel/commands/edit/DictationTest.kt`:

```kotlin
package dev.kestrel.commands.edit

import org.junit.Assert.assertEquals
import org.junit.Test

/** Dictation and spelling (design spec, section 9.7, and Appendix A: typing and punctuation). */
class DictationTest {
    /** What dictating [spoken] at [marked] leaves, with the parts joined at their line breaks. */
    private fun dictate(marked: String, spoken: String, password: Boolean = false): String {
        val state = Marks.state(marked)
        var text = state.text
        var cursor = state.selectionStart
        for (part in Dictation.insert(state, spoken, password)) {
            when (part) {
                is DictationPart.Text -> {
                    text = part.change.applyTo(text)
                    cursor = part.change.selectionEnd
                }
                DictationPart.NewLine -> {
                    text = text.substring(0, cursor) + "\n" + text.substring(cursor)
                    cursor++
                }
            }
        }
        return Marks.mark(text, cursor, cursor)
    }

    @Test
    fun theDictationLinesOfTheCorpusIntoAnEmptyField() {
        val lines = listOf(
            "hello comma how are you question mark" to "Hello, how are you?",
            "see you at five period" to "See you at five.",
            "the meeting is on Monday exclamation mark" to "The meeting is on Monday!",
            "capital p one two three" to "P123",
            "open quote hello close quote she said" to "\"Hello\" she said",
        )

        for ((spoken, expected) in lines) {
            val parts = Dictation.insert(TextState("", 0, 0), spoken, password = false)
            assertEquals(spoken, listOf(DictationPart.Text(EditStep.Change(0, 0, expected, expected.length, expected.length))), parts)
        }
    }

    @Test
    fun theRecognisersPunctuationGoesAndTheFirstWordLosesItsCapital() {
        assertEquals("We met yesterday|", dictate("We met |", "Yesterday."))
        assertEquals("Well, I think so|", dictate("Well, |", "I think so."))
        assertEquals("Well, NASA called|", dictate("Well, |", "NASA called."))
        assertEquals("Yes, I'm here|", dictate("Yes, |", "I'm here."))
        assertEquals("OK|", dictate("|", "OK."))
        assertEquals("It costs 3.5 euro at 10:30|", dictate("It costs |", "3.5 euro at 10:30."))
    }

    @Test
    fun aCapitalComesAfterASentenceEndInTheFieldOrSaidNow() {
        assertEquals("Done. See you|", dictate("Done.|", "see you"))
        assertEquals("Done. See you|", dictate("Done. |", "see you"))
        assertEquals("Done. \"Hello|", dictate("Done. \"|", "hello"))
        assertEquals("He said \"hello|", dictate("He said \"|", "hello"))
        assertEquals("How are you? Fine thanks|", dictate("|", "how are you question mark fine thanks"))
        assertEquals("ok so|", dictate("ok|", "so"))
    }

    @Test
    fun spacesGoBetweenWordsAndNotBeforeClosingMarks() {
        assertEquals("hello world|", dictate("hello|", "world"))
        assertEquals("hello world|", dictate("hello |", "world"))
        assertEquals("hello,|", dictate("hello|", "comma"))
        assertEquals("hello, world|", dictate("hello,|", "world"))
        // Before a word of the field, a space follows the new words.
        assertEquals("Hello |world", dictate("|world", "hello"))
        assertEquals("Ring me (maybe)|", dictate("|", "ring me open parenthesis maybe close parenthesis"))
        assertEquals("My_name@mail|", dictate("|", "my underscore name at sign mail"))
        // A closing mark takes the place of the space before the cursor, and "dot" joins without starting a sentence.
        assertEquals("hello.|", dictate("hello |", "period"))
        assertEquals("hello.|", dictate("hello\u00A0|", "period"))
        assertEquals("John.smith at example.com|", dictate("|", "john dot smith at example dot com"))
    }

    @Test
    fun doubleQuotesOpenAndCloseByHowManyComeBefore() {
        assertEquals("\"Hi\"|", dictate("|", "quote hi quote"))
        assertEquals("He said \"hi\"|", dictate("He said \"hi|", "quote"))
    }

    @Test
    fun aNewLineSplitsTheParts() {
        assertEquals(
            listOf(
                DictationPart.Text(EditStep.Change(0, 0, "Hello", 5, 5)),
                DictationPart.NewLine,
                DictationPart.Text(EditStep.Change(6, 6, "world", 11, 11)),
            ),
            Dictation.insert(TextState("", 0, 0), "hello new line world", password = false),
        )
        assertEquals(
            listOf(DictationPart.NewLine, DictationPart.NewLine, DictationPart.Text(EditStep.Change(7, 7, "Thanks", 13, 13))),
            Dictation.insert(TextState("Done.", 5, 5), "new paragraph thanks", password = false),
        )
        assertEquals("Hi.\nThere|", dictate("Hi.|", "new line there"))
    }

    @Test
    fun theSelectionIsReplaced() {
        assertEquals("hello there|", dictate("hello [world]", "there"))
    }

    @Test
    fun spelledWordsAreJoined() {
        assertEquals("Code: abc|", dictate("Code: |", "a b c"))
        assertEquals("Code: X2|", dictate("Code: |", "capital x 2"))
        assertEquals("Pin 123|", dictate("Pin|", "one two three"))
    }

    @Test
    fun inAPasswordFieldEveryWordIsSpelled() {
        assertEquals("hunter2b|", dictate("|", "Hunter two bee.", password = true))
        assertEquals("hunter2!|", dictate("|", "hunter two exclamation mark", password = true))
        assertEquals("P@ss_1|", dictate("|", "capital p at sign s s underscore 1", password = true))
        assertEquals("a#1$+=*|", dictate("|", "a hash one dollar sign plus sign equals sign asterisk", password = true))
        assertEquals("Pw9|", dictate("|", "capital p double u 9", password = true))
        assertEquals("ab|", dictate("a|", "bee", password = true))
    }

    @Test
    fun formatEmailAndPhrases() {
        assertEquals("john.smith@example-shop.com", Dictation.formatEmail("John Dot Smith AT example dash shop dot com"))
        assertEquals("Tuesday,", Dictation.phrase("Tuesday comma"))
        assertEquals("next week", Dictation.phrase("next week."))
    }
}
```

Create `core/commands/src/test/kotlin/dev/kestrel/commands/edit/EditHistoryTest.kt`:

```kotlin
package dev.kestrel.commands.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    private val empty = TextState("", 0, 0)
    private val hello = TextState("hello", 5, 5)
    private val helloWorld = TextState("hello world", 11, 11)

    @Test
    fun undoAndRedoPutBackTheStatesAroundEachEdit() {
        val history = EditHistory()
        history.record("app/field", empty, hello)
        history.record("app/field", hello, helloWorld)

        assertEquals(hello, history.undo("app/field", helloWorld))
        assertEquals(empty, history.undo("app/field", hello))
        assertFalse(history.canUndo("app/field"))
        assertEquals(hello, history.redo("app/field", empty))
        assertEquals(helloWorld, history.redo("app/field", hello))
        assertFalse(history.canRedo("app/field"))
    }

    @Test
    fun aNewEditAfterAnUndoDropsWhatRedoCouldRestore() {
        val history = EditHistory()
        history.record("app/field", empty, hello)
        history.record("app/field", hello, helloWorld)
        history.undo("app/field", helloWorld)

        history.record("app/field", hello, TextState("hello there", 11, 11))

        assertFalse(history.canRedo("app/field"))
        assertEquals(hello, history.undo("app/field", TextState("hello there", 11, 11)))
    }

    @Test
    fun aTextThatChangedSinceKestrelsEditDropsTheFieldsHistory() {
        val history = EditHistory()
        history.record("app/field", empty, hello)

        assertNull(history.undo("app/field", TextState("hello!", 6, 6)))
        assertFalse(history.canUndo("app/field"))
        // Only the text counts; the cursor may have moved.
        history.record("app/field", empty, hello)
        assertEquals(empty, history.undo("app/field", TextState("hello", 0, 0)))
    }

    @Test
    fun itKeepsFiftyEditsOfTwentyFields() {
        val history = EditHistory()
        val states = (0..51).map { TextState("a".repeat(it), it, it) }
        for (index in 0 until 51) history.record("app/field", states[index], states[index + 1])

        var current = states[51]
        repeat(50) { current = checkNotNull(history.undo("app/field", current)) }
        assertEquals(states[1], current)
        assertFalse(history.canUndo("app/field"))

        for (field in 1..21) history.record("app/$field", empty, hello)
        assertFalse(history.canUndo("app/1"))
        assertTrue(history.canUndo("app/21"))
        assertTrue(history.canUndo("app/2"))
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'Dictation'", "Unresolved reference 'DictationPart'" and "Unresolved reference 'EditHistory'".

- [ ] **Step 3: Add dictation**

Create `core/commands/src/main/kotlin/dev/kestrel/commands/edit/Dictation.kt`:

```kotlin
package dev.kestrel.commands.edit

import java.util.Locale

/** One part of a dictation: text that replaces the selection, or a line break, which goes through the Enter key. */
sealed interface DictationPart {
    data class Text(val change: EditStep.Change) : DictationPart

    data object NewLine : DictationPart
}

/**
 * Turns dictated words into the text to insert (design spec, section 9.7). Kestrel types only the punctuation that the
 * owner says ("comma", "question mark", "new line" and the others) and drops the recogniser's own, which adds a full stop
 * to almost every short utterance. The first word of an utterance loses its capital unless it is "I" or is written in
 * capitals; the other words keep the recogniser's case. A word gets a capital at the start of an empty field and after a
 * full stop, question mark or exclamation mark, whether the owner says it now or the field had it already; "dot" joins
 * without a space and starts no sentence, as in "example.com". Words that spell something, such as "capital p one two
 * three", are joined without spaces, and in a password field every word is, with its spoken punctuation as marks.
 */
object Dictation {
    /**
     * The parts that insert [spoken] at [state]'s selection, split at each spoken "new line" and "new paragraph". In a
     * [password] field the words are spelled and joined. [startsUtterance] is false for the words of "type X" and "insert
     * X", which do not start what the owner said.
     */
    fun insert(state: TextState, spoken: String, password: Boolean, startsUtterance: Boolean = true): List<DictationPart> {
        if (password) {
            val spelled = spell(words(spoken), password = true).orEmpty()
            val end = state.selectionStart + spelled.length
            return listOf(DictationPart.Text(EditStep.Change(state.selectionStart, state.selectionEnd, spelled, end, end)))
        }
        val change = render(state, spoken, startsUtterance)
        val pieces = change.replacement.split('\n')
        return buildList {
            var offset = change.start
            for ((index, piece) in pieces.withIndex()) {
                if (index > 0) {
                    add(DictationPart.NewLine)
                    offset++
                }
                // The first part also replaces the selection, even when it brings no text of its own.
                if (piece.isNotEmpty() || (index == 0 && change.end > change.start)) {
                    val end = if (index == 0) change.end else offset
                    add(DictationPart.Text(EditStep.Change(offset, end, piece, offset + piece.length, offset + piece.length)))
                }
                offset += piece.length
            }
        }
    }

    /** One change that inserts [spoken] at [state]'s selection, with each line break as "\n". */
    fun render(state: TextState, spoken: String, startsUtterance: Boolean): EditStep.Change {
        val before = state.text.substring(0, state.selectionStart)
        val after = state.text.substring(state.selectionEnd)
        val text = build(before, after, spoken, startsUtterance, capitals = true)
        // "Hello |" and "period": a closing mark takes the place of the one space that the field has before the cursor.
        val replacesSpace = text.firstOrNull()?.let { it in SPACELESS } == true && before.length >= 2 &&
            (before.last() == ' ' || before.last() == '\u00A0') && !before[before.length - 2].isWhitespace()
        val start = if (replacesSpace) state.selectionStart - 1 else state.selectionStart
        val end = start + text.length
        return EditStep.Change(start, state.selectionEnd, text, end, end)
    }

    /**
     * [spoken] with its spoken punctuation and without the recogniser's own, in the recogniser's case, for the new words
     * of "replace X with Y".
     */
    fun phrase(spoken: String): String = build("", "", spoken, startsUtterance = false, capitals = false)

    /** "Format email": lower case, "at" as "@", "dot" as ".", "underscore" as "_", "dash" and "hyphen" as "-", no spaces. */
    fun formatEmail(text: String): String = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString("") { word ->
        when (word.lowercase(Locale.ROOT).trim(',', ';', ':')) {
            "at" -> "@"
            "dot" -> "."
            "underscore" -> "_"
            "dash", "hyphen" -> "-"
            else -> word.lowercase(Locale.ROOT)
        }
    }

    /**
     * The text for [spoken] between [before] and [after]: a space before it when neither side is whitespace, a space
     * after it before a word, and with [capitals] a capital at the start of a sentence.
     */
    private fun build(before: String, after: String, spoken: String, startsUtterance: Boolean, capitals: Boolean): String {
        val words = words(spoken)
        val spelled = spell(words, password = false)
        val tokens = if (spelled != null) listOf(Token.Word(spelled)) else tokens(words)
        val text = StringBuilder()
        var quotes = before.count { it == '"' }
        var singles = before.indices.count { isLoneApostrophe(before, it) }
        var previous = when {
            before.isEmpty() || before.last().isWhitespace() -> Kind.BREAK
            before.last() in OPENERS || (before.last() == '"' && quotes % 2 == 1) -> Kind.OPENING
            else -> Kind.WORD
        }
        // Spelled words go in exactly as spelled.
        var sentenceStart = capitals && spelled == null && startsSentence(before)
        var first = startsUtterance && spelled == null
        for (token in tokens) {
            when (token) {
                is Token.Word -> {
                    var word = token.text
                    if (first && !keepsCapital(word)) word = word.replaceFirstChar { it.lowercase(Locale.ROOT) }
                    first = false
                    if (sentenceStart) word = word.replaceFirstChar { it.titlecase(Locale.ROOT) }
                    sentenceStart = false
                    if (previous == Kind.WORD || previous == Kind.CLOSING) text.append(' ')
                    text.append(word)
                    previous = Kind.WORD
                }
                is Token.Mark -> {
                    val kind = when (token.kind) {
                        Kind.QUOTE -> if (quotes % 2 == 0) Kind.OPENING else Kind.CLOSING
                        Kind.SINGLE_QUOTE -> if (singles++ % 2 == 0) Kind.OPENING else Kind.CLOSING
                        else -> token.kind
                    }
                    if (token.text == "\"") quotes++
                    val spaced = kind == Kind.WORD || (kind == Kind.OPENING && previous != Kind.OPENING && previous != Kind.JOINING)
                    if (spaced && (previous == Kind.WORD || previous == Kind.CLOSING)) text.append(' ')
                    text.append(token.text)
                    when (token.text) {
                        // "Dot" joins, as in "example.com", and starts no sentence.
                        ".", "!", "?" -> sentenceStart = capitals && kind == Kind.CLOSING
                        ",", ";", ":" -> sentenceStart = false
                    }
                    previous = kind
                }
                Token.Break -> {
                    text.append('\n')
                    previous = Kind.BREAK
                }
            }
        }
        // A space after the new words when they go in before a word of the field.
        if (after.firstOrNull()?.isLetterOrDigit() == true && (previous == Kind.WORD || previous == Kind.CLOSING)) text.append(' ')
        return text.toString()
    }

    /** The words of [spoken] without the recogniser's punctuation. Marks between digits stay, as in "3.5" and "10:30". */
    private fun words(spoken: String): List<String> = buildList {
        for (raw in spoken.split(WHITESPACE)) {
            val piece = StringBuilder()
            for ((index, char) in raw.withIndex()) {
                val betweenDigits = index > 0 && index < raw.length - 1 && raw[index - 1].isDigit() && raw[index + 1].isDigit()
                val dropped = (char in DROPPED && !(char in DIGIT_MARKS && betweenDigits)) ||
                    (char in DASHES && raw.length == 1) ||
                    (char in LEADING_QUOTES && index == 0)
                if (dropped) {
                    if (piece.isNotEmpty()) add(piece.toString())
                    piece.clear()
                } else {
                    piece.append(char)
                }
            }
            if (piece.isNotEmpty()) add(piece.toString())
        }
    }

    /** The words with spoken punctuation turned into marks. */
    private fun tokens(words: List<String>): List<Token> = buildList {
        val lower = words.map { it.lowercase(Locale.ROOT) }
        var index = 0
        while (index < words.size) {
            val match = MARKS.firstOrNull { (phrase, _) ->
                index + phrase.size <= words.size && phrase.indices.all { lower[index + it] == phrase[it] }
            }
            if (match == null) {
                add(Token.Word(words[index]))
                index++
            } else {
                addAll(match.second)
                index += match.first.size
            }
        }
    }

    /**
     * The words joined without spaces when they spell something: at least two words, and each one a single letter, a
     * number, a digit word or "capital" with a letter. In a [password] field every word counts, the names of letters
     * ("bee", "double u") too, spoken punctuation goes in as its mark ("exclamation mark" as "!"), and the words are in
     * lower case unless "capital" comes before them.
     */
    private fun spell(words: List<String>, password: Boolean): String? {
        if (!password && words.size < 2) return null
        val spelled = StringBuilder()
        var index = 0
        while (index < words.size) {
            // A password may hold any character, so its spoken punctuation goes in as marks.
            val mark = if (password) passwordMark(words, index) else null
            if (mark != null) {
                spelled.append(mark.second)
                index += mark.first
                continue
            }
            val word = words[index].lowercase(Locale.ROOT)
            val next = words.getOrNull(index + 1)?.lowercase(Locale.ROOT)
            val capital = if (word == "capital" && next != null) letter(next, password) else null
            when {
                capital != null -> {
                    spelled.append(capital.uppercase(Locale.ROOT))
                    index++
                }
                password && word == "double" && (next == "u" || next == "you") -> {
                    spelled.append('w')
                    index++
                }
                word.all { it.isDigit() } -> spelled.append(word)
                word in DIGITS -> spelled.append(DIGITS.getValue(word))
                letter(word, password) != null -> spelled.append(letter(word, password))
                password -> spelled.append(word)
                else -> return null
            }
            index++
        }
        return spelled.toString()
    }

    /**
     * The spoken mark at [index] of [words], or a symbol that only a password takes by name ("hash", "dollar sign"), as its
     * number of words and its characters; a line break is none.
     */
    private fun passwordMark(words: List<String>, index: Int): Pair<Int, String>? {
        val (phrase, tokens) = MARKS.firstOrNull { (phrase, tokens) ->
            tokens.all { it is Token.Mark } && index + phrase.size <= words.size &&
                phrase.indices.all { words[index + it].lowercase(Locale.ROOT) == phrase[it] }
        } ?: return PASSWORD_SYMBOLS.firstNotNullOfOrNull { (phrase, symbol) ->
            val fits = index + phrase.size <= words.size && phrase.indices.all { words[index + it].lowercase(Locale.ROOT) == phrase[it] }
            if (fits) phrase.size to symbol else null
        }
        return phrase.size to tokens.joinToString("") { (it as Token.Mark).text }
    }

    // Symbols that only a password field takes by name, where no word is meant as a word; elsewhere "plus" or "hash"
    // stay words.
    private val PASSWORD_SYMBOLS: List<Pair<List<String>, String>> = listOf(
        "dollar sign" to "$", "plus sign" to "+", "equals sign" to "=", "hash" to "#", "asterisk" to "*",
    ).map { (phrase, symbol) -> phrase.split(' ') to symbol }

    private fun letter(word: String, password: Boolean): String? = when {
        word.length == 1 && word[0].isLetter() -> word
        password -> LETTER_NAMES[word]
        else -> null
    }

    private fun keepsCapital(word: String): Boolean =
        word == "I" || I_CONTRACTIONS.matches(word) || (word.count { it.isLetter() } > 1 && word.none { it.isLowerCase() })

    /** Whether the text before the cursor is empty or ends a sentence, also before an opening quote, as in `Done. "`. */
    private fun startsSentence(before: String): Boolean {
        val trimmed = before.trimEnd().trimEnd(*CLOSERS).trimEnd()
        return trimmed.isEmpty() || trimmed.last() in ".!?"
    }

    private fun isLoneApostrophe(text: String, index: Int): Boolean =
        text[index] == '\'' &&
            !(index > 0 && text[index - 1].isLetterOrDigit() && index < text.length - 1 && text[index + 1].isLetterOrDigit())

    /** How a token joins its neighbours. A [QUOTE] or [SINGLE_QUOTE] opens or closes by how many came before it. */
    private enum class Kind { WORD, CLOSING, OPENING, JOINING, QUOTE, SINGLE_QUOTE, BREAK }

    private sealed interface Token {
        data class Word(val text: String) : Token

        data class Mark(val text: String, val kind: Kind) : Token

        data object Break : Token
    }

    private fun mark(text: String, kind: Kind) = listOf(Token.Mark(text, kind))

    // The spoken punctuation; the longest phrase that matches wins.
    private val MARKS: List<Pair<List<String>, List<Token>>> = listOf(
        "open double quote" to mark("\"", Kind.OPENING),
        "close double quote" to mark("\"", Kind.CLOSING),
        "new paragraph" to listOf(Token.Break, Token.Break),
        "new line" to listOf(Token.Break),
        "full stop" to mark(".", Kind.CLOSING),
        "question mark" to mark("?", Kind.CLOSING),
        "exclamation mark" to mark("!", Kind.CLOSING),
        "exclamation point" to mark("!", Kind.CLOSING),
        "double quote" to mark("\"", Kind.QUOTE),
        "open quote" to mark("\"", Kind.OPENING),
        "close quote" to mark("\"", Kind.CLOSING),
        "single quote" to mark("'", Kind.SINGLE_QUOTE),
        "open parenthesis" to mark("(", Kind.OPENING),
        "open bracket" to mark("(", Kind.OPENING),
        "close parenthesis" to mark(")", Kind.CLOSING),
        "close bracket" to mark(")", Kind.CLOSING),
        "at sign" to mark("@", Kind.JOINING),
        "percent sign" to mark("%", Kind.CLOSING),
        "comma" to mark(",", Kind.CLOSING),
        "period" to mark(".", Kind.CLOSING),
        "dot" to mark(".", Kind.JOINING),
        "colon" to mark(":", Kind.CLOSING),
        "semicolon" to mark(";", Kind.CLOSING),
        "quote" to mark("\"", Kind.QUOTE),
        "apostrophe" to mark("'", Kind.JOINING),
        "hyphen" to mark("-", Kind.JOINING),
        "dash" to mark("-", Kind.WORD),
        "slash" to mark("/", Kind.JOINING),
        "underscore" to mark("_", Kind.JOINING),
        "ampersand" to mark("&", Kind.WORD),
    ).map { (phrase, tokens) -> phrase.split(' ') to tokens }.sortedByDescending { it.first.size }

    private val DIGITS = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
        "eight" to "8", "nine" to "9",
    )

    // The names of letters count only in password fields, where no word is meant as a word.
    private val LETTER_NAMES = mapOf(
        "ay" to "a", "bee" to "b", "be" to "b", "see" to "c", "sea" to "c", "cee" to "c", "dee" to "d", "ef" to "f",
        "eff" to "f", "gee" to "g", "aitch" to "h", "eye" to "i", "jay" to "j", "kay" to "k", "el" to "l", "ell" to "l",
        "em" to "m", "en" to "n", "oh" to "o", "pee" to "p", "cue" to "q", "queue" to "q", "ar" to "r", "are" to "r",
        "ess" to "s", "tee" to "t", "tea" to "t", "you" to "u", "vee" to "v", "ex" to "x", "why" to "y", "wye" to "y",
        "zed" to "z", "zee" to "z",
    )

    private val WHITESPACE = Regex("\\s+")
    private val I_CONTRACTIONS = Regex("I['\u2019](m|ll|ve|d)")
    private val DROPPED = setOf('.', ',', '?', '!', ';', ':', '"', '\u201C', '\u201D', '\u201E', '\u2026', '\u00AB', '\u00BB')
    private val DIGIT_MARKS = setOf('.', ',', ':')
    private val DASHES = setOf('-', '\u2013', '\u2014')
    private val LEADING_QUOTES = setOf('\'', '\u2018')
    private val OPENERS = setOf('(', '[', '{')

    // The closing marks that take the place of a space before the cursor.
    private val SPACELESS = setOf('.', ',', '?', '!', ';', ':', ')', '%')
    private val CLOSERS = charArrayOf('"', '\'', '\u201D', '\u2019', ')', ']')
}
```

- [ ] **Step 4: Add the history**

Create `core/commands/src/main/kotlin/dev/kestrel/commands/edit/EditHistory.kt`:

```kotlin
package dev.kestrel.commands.edit

/**
 * Kestrel's own history of its edits, for "undo" and "redo" (design spec, section 9.7): for each field, the state before
 * and after each edit, one entry per command. It keeps at most [maxEdits] edits for each of the last [maxFields] fields.
 * "Undo" puts back the state before the last edit, and "redo" the state after it, but only while the field still shows
 * the text that Kestrel left; otherwise the field's history is dropped, because the app or the owner changed the text.
 * Edits of password fields are never recorded.
 */
class EditHistory(private val maxEdits: Int = 50, private val maxFields: Int = 20) {
    private class Field {
        val edits = ArrayList<Edit>()

        /** How many of [edits] are applied; the ones after it can be redone. */
        var applied = 0
    }

    private class Edit(val before: TextState, val after: TextState)

    // In order of use, so that the field used longest ago goes first.
    private val fields = LinkedHashMap<String, Field>(16, 0.75f, true)

    /** Records an edit of [field] (its app and key) from [before] to [after]. It drops the edits that "redo" could restore. */
    @Synchronized
    fun record(field: String, before: TextState, after: TextState) {
        val edits = fields.getOrPut(field) { Field() }
        while (edits.edits.size > edits.applied) edits.edits.removeAt(edits.edits.lastIndex)
        edits.edits += Edit(before, after)
        edits.applied++
        if (edits.edits.size > maxEdits) {
            edits.edits.removeAt(0)
            edits.applied--
        }
        while (fields.size > maxFields) fields.remove(fields.keys.first())
    }

    @Synchronized
    fun canUndo(field: String): Boolean = (fields[field]?.applied ?: 0) > 0

    @Synchronized
    fun canRedo(field: String): Boolean = fields[field]?.let { it.applied < it.edits.size } ?: false

    /**
     * The state before the last edit of [field], or null when there is none or when [current] does not show the text
     * after that edit; then the field's history is dropped.
     */
    @Synchronized
    fun undo(field: String, current: TextState): TextState? {
        val edits = fields[field] ?: return null
        if (edits.applied == 0) return null
        val edit = edits.edits[edits.applied - 1]
        if (current.text != edit.after.text) {
            fields.remove(field)
            return null
        }
        edits.applied--
        return edit.before
    }

    /** The state after the edit that "undo" took back last, in the same way as [undo]. */
    @Synchronized
    fun redo(field: String, current: TextState): TextState? {
        val edits = fields[field] ?: return null
        if (edits.applied == edits.edits.size) return null
        val edit = edits.edits[edits.applied]
        if (current.text != edit.before.text) {
            fields.remove(field)
            return null
        }
        edits.applied++
        return edit.after
    }

    override fun toString() = "EditHistory(${fields.size} fields)"
}
```

- [ ] **Step 5: Plan inserting, replacing, case and "format email"**

In `core/commands/src/main/kotlin/dev/kestrel/commands/edit/EditModel.kt`, replace

```kotlin
import dev.kestrel.commands.Place
import dev.kestrel.commands.TextCommand
```

with

```kotlin
import dev.kestrel.commands.Place
import dev.kestrel.commands.TextCase
import dev.kestrel.commands.TextCommand
```

Replace

```kotlin
import dev.kestrel.commands.TextUnit

/**
```

with

```kotlin
import dev.kestrel.commands.TextUnit
import java.util.Locale

/**
```

Replace

```kotlin
        is TextCommand.MoveCursor -> move(command.place, state)
        else -> EditStep.Fail(EditFailure.NOT_AN_EDIT)
```

with

```kotlin
        is TextCommand.MoveCursor -> move(command.place, state)
        is TextCommand.Insert -> insert(command.text, command.place, state)
        is TextCommand.Replace -> replace(command.old, command.new, state)
        is TextCommand.ReplaceBetween -> replaceBetween(command, state)
        is TextCommand.ChangeCase -> changeCase(command.case, command.phrase, state)
        TextCommand.FormatEmail -> formatEmail(state)
        else -> EditStep.Fail(EditFailure.NOT_AN_EDIT)
```

Replace

```kotlin
    private fun removal(start: Int, end: Int)
```

with

```kotlin
    private fun insert(spoken: String, place: Place, state: TextState): EditStep = when (val found = offset(place, state)) {
        is Missing -> found.step
        // The words go in as dictation at that place, with spoken punctuation and the spaces around them.
        is Found -> Dictation.render(TextState(state.text, found.range.first, found.range.first), spoken, startsUtterance = false)
    }

    private fun replace(old: String, new: String, state: TextState): EditStep {
        val range = TextSearch.find(state.text, old, state.selectionEnd) ?: return EditStep.Fail(EditFailure.NOT_FOUND, old)
        val replacement = Dictation.phrase(new)
        val end = range.first + replacement.length
        return EditStep.Change(range.first, range.last + 1, replacement, end, end)
    }

    private fun replaceBetween(command: TextCommand.ReplaceBetween, state: TextState): EditStep {
        val text = state.text
        val both = when (val found = pair(text, command.from, command.to, state.selectionEnd)) {
            is PairLookup.Missing -> return found.step
            is PairLookup.Both -> found
        }
        val replacement = Dictation.phrase(command.new)
        val inner = trimmed(text, both.first.last + 1, both.second.first)
        if (inner.isEmpty()) {
            // Only spaces lie between the two: the new words go between them with a space on each side.
            val start = both.first.last + 1
            val end = start + 1 + replacement.length
            return EditStep.Change(start, both.second.first, " $replacement ", end, end)
        }
        val end = inner.first + replacement.length
        return EditStep.Change(inner.first, inner.last + 1, replacement, end, end)
    }

    private fun changeCase(case: TextCase, phrase: String, state: TextState): EditStep {
        val text = state.text
        val range = TextSearch.find(text, phrase, state.selectionEnd) ?: return EditStep.Fail(EditFailure.NOT_FOUND, phrase)
        val old = text.substring(range.first, range.last + 1)
        val new = when (case) {
            TextCase.UPPER -> old.uppercase(Locale.ROOT)
            TextCase.LOWER -> old.lowercase(Locale.ROOT)
            TextCase.CAPITALIZE -> capitalized(old)
        }
        val end = range.first + new.length
        return EditStep.Change(range.first, range.last + 1, new, end, end)
    }

    // "Format email" changes the selection, or the whole text when nothing is selected.
    private fun formatEmail(state: TextState): EditStep {
        val range = if (state.hasSelection) state.selectionStart until state.selectionEnd else state.text.indices
        if (range.isEmpty()) return EditStep.Fail(EditFailure.NOTHING_THERE)
        val email = Dictation.formatEmail(state.text.substring(range.first, range.last + 1))
        val end = range.first + email.length
        return EditStep.Change(range.first, range.last + 1, email, end, end)
    }

    /** The first letter of each word in capitals. */
    private fun capitalized(text: String): String = buildString {
        for ((index, char) in text.withIndex()) {
            val startsWord = index == 0 || !(text[index - 1].isLetterOrDigit() || text[index - 1] in APOSTROPHES)
            append(if (startsWord && char.isLetter()) char.titlecaseChar() else char)
        }
    }

    private fun removal(start: Int, end: Int)
```

Replace

```kotlin
    private val CLOSING = setOf('.', ',', '!', '?', ';', ':', ')', ']')
}
```

with

```kotlin
    private val CLOSING = setOf('.', ',', '!', '?', ';', ':', ')', ']')
    private val APOSTROPHES = setOf('\'', '\u2019')
}
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD SUCCESSFUL`, with 85 tests in `:core:commands` (65 + 20): `EditModelChangesTest` 6, `DictationTest` 10 and `EditHistoryTest` 4.

- [ ] **Step 7: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` 85, `:app` 314, `:core:screen` 55, `:core:voice` 140, `:core:privacy` 48, `:core:safety` 2), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit.

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
git add core/commands/src/main/kotlin/dev/kestrel/commands/edit core/commands/src/test/kotlin/dev/kestrel/commands/edit
git commit -m "feat(commands): dictate with spoken punctuation and spelling, insert, replace and change case, and keep Kestrel's own edit history" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: The text phrases and their part of the command list

**Files:**
- Modify: `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarText.kt` (the whole file, replacing plan 3A's stub), `core/commands/src/main/kotlin/dev/kestrel/commands/HelpText.kt` (the whole file, replacing plan 3A's stub)
- Test: `core/commands/src/test/kotlin/dev/kestrel/commands/GrammarTextTest.kt` (new); plan 3A's `CommandHelpTest` now also checks the text examples

**Interfaces:**
- Consumes: plan 3A's `internal object GrammarText` with `fun fixed(words: List<String>, text: String): Command?` and `fun targeted(words: List<String>, text: String): Command?` (`words` are `Normalizer.words(text)`, `text` the original transcript), `cutWords(text, from, to)`, `TargetWords.read(words, text, from)`, `SpokenNumbers.number(words, lone)`, `CommandParser.POLITE_WORDS` (internal), `HelpGroup`, the text model of `TextCommandModel.kt`.
- Produces: `GrammarText.fixed` and `targeted` that parse every phrase of design D8's list to the variant given there; `HelpText.groups` (seven groups, every example a command). In the executor, every text phrase now reaches the parts as a `TextCommand`; until Task 5 the text part is plan 3A's stub, so "delete", "copy" and the other phrases still fall back to bare labels, as before this task.

Design D5 and D8. The fixed phrases are whole utterances ("undo", "delete all", "select to the end of the text", "go to the beginning", "copy", "show keyboard", "new line", "show password"); "copy", "cut" and "paste" are fixed phrases only, so that "copy this phone number" stays plan 3C's. The targeted rules start with their verb: "type X", "start editing X", "insert X before/after Y" (at the last "before" or "after"), "insert X between Y and Z" (at the last "between" and the first "and" after it), "insert X at the beginning/end", "replace X with Y" (at the first "with"), "replace everything between X and Y with Z", "capitalize/capitalise X", "uppercase X", "lowercase X" (also "upper case X" and "lower case X"), "delete X" and "select X" or "highlight X" with units ("three words", "the next two sentences", "the last line", "a word"), "from X to Y" (at the first "to" after X), "between X and Y" or a phrase, "move after/before X", "move between X and Y" (also with "cursor" or "the cursor" after "move"), "left" or "right" with units, and "search for X". No phrase starts with "open", "call" or "dial", none uses "volume", "back", "home", "pan" or "move" with a direction alone, all of which plan 3C's grammar takes. A count right after the verb takes design D5's homophones, so "right to words" and "delete to words" mean two words. Payloads keep the recogniser's case and the punctuation inside them; polite words stay in them ("type see you now", "delete now"), but after a whole command ("delete all please", "delete the last word please", "start editing please", "delete please") the rules leave them to plan 3A's second try, as "new line please" is "new line", so that they never become a phrase to find or a field's name. The five dictation lines of plan 3C's corpus stay `Bare`.

- [ ] **Step 1: Write the failing test**

Create `core/commands/src/test/kotlin/dev/kestrel/commands/GrammarTextTest.kt`:

```kotlin
package dev.kestrel.commands

import dev.kestrel.commands.TextCommand.Clipboard
import dev.kestrel.commands.TextCommand.Delete
import dev.kestrel.commands.TextCommand.Keyboard
import dev.kestrel.commands.TextCommand.MoveCursor
import dev.kestrel.commands.TextCommand.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text phrases of Appendix A (design spec, section 16.2). The first list is the text part of plan 3C's corpus,
 * exactly; none of the phrases is one that plan 3C's phone grammar takes.
 */
class GrammarTextTest {
    private fun units(count: Int, unit: TextUnit, forward: Boolean) = TextScope.Units(count, unit, forward)

    private val corpus: List<Pair<String, Command>> = listOf(
        "start editing" to TextCommand.StartEditing(null),
        "start editing the message" to TextCommand.StartEditing(Target.Label("the message")),
        "stop editing" to TextCommand.StopEditing,
        "stop typing" to TextCommand.StopEditing,
        "type hello there" to TextCommand.Type("hello there"),
        "type see you tomorrow" to TextCommand.Type("see you tomorrow"),
        "undo" to TextCommand.Undo,
        "undo that" to TextCommand.Undo,
        "redo" to TextCommand.Redo,
        "redo that" to TextCommand.Redo,
        "insert really before nice" to TextCommand.Insert("really", Place.Before("nice")),
        "insert very after really" to TextCommand.Insert("very", Place.After("really")),
        "insert and between salt and pepper" to TextCommand.Insert("and", Place.Between("salt", "pepper")),
        "insert too before late" to TextCommand.Insert("too", Place.Before("late")),
        "format email" to TextCommand.FormatEmail,
        "format as email address" to TextCommand.FormatEmail,
        "replace Monday with Tuesday" to TextCommand.Replace("Monday", "Tuesday"),
        "replace cat with dog" to TextCommand.Replace("cat", "dog"),
        "replace everything between hello and world with hi" to TextCommand.ReplaceBetween("hello", "world", "hi"),
        "replace everything between first and last with middle" to TextCommand.ReplaceBetween("first", "last", "middle"),
        "capitalize paris" to TextCommand.ChangeCase(TextCase.CAPITALIZE, "paris"),
        "capitalise london" to TextCommand.ChangeCase(TextCase.CAPITALIZE, "london"),
        "uppercase nasa" to TextCommand.ChangeCase(TextCase.UPPER, "nasa"),
        "uppercase hello" to TextCommand.ChangeCase(TextCase.UPPER, "hello"),
        "lowercase hello" to TextCommand.ChangeCase(TextCase.LOWER, "hello"),
        "lowercase world" to TextCommand.ChangeCase(TextCase.LOWER, "world"),
        "delete" to Delete(null),
        "delete that" to Delete(null),
        "delete all" to Delete(TextScope.All),
        "delete everything" to Delete(TextScope.All),
        "delete tomorrow" to Delete(TextScope.Phrase("tomorrow")),
        "delete the meeting" to Delete(TextScope.Phrase("the meeting")),
        "delete to the beginning" to Delete(TextScope.ToStart),
        "delete to the start" to Delete(TextScope.ToStart),
        "delete to the end" to Delete(TextScope.ToEnd),
        "delete to the end of the text" to Delete(TextScope.ToEnd),
        "delete selected text" to Delete(TextScope.Selection),
        "delete the selection" to Delete(TextScope.Selection),
        "delete from hello to world" to Delete(TextScope.FromTo("hello", "world")),
        "delete from first to last" to Delete(TextScope.FromTo("first", "last")),
        "delete three words" to Delete(units(3, TextUnit.WORD, false)),
        "delete the next two sentences" to Delete(units(2, TextUnit.SENTENCE, true)),
        "delete the last line" to Delete(units(1, TextUnit.LINE, false)),
        "delete two paragraphs" to Delete(units(2, TextUnit.PARAGRAPH, false)),
        "delete five characters" to Delete(units(5, TextUnit.CHARACTER, false)),
        "go to the beginning" to MoveCursor(Place.Start),
        "move to the start" to MoveCursor(Place.Start),
        "go to the end" to MoveCursor(Place.End),
        "move to the end" to MoveCursor(Place.End),
        "move after tomorrow" to MoveCursor(Place.After("tomorrow")),
        "move after the meeting" to MoveCursor(Place.After("the meeting")),
        "move before tomorrow" to MoveCursor(Place.Before("tomorrow")),
        "move before lunch" to MoveCursor(Place.Before("lunch")),
        "move between salt and pepper" to MoveCursor(Place.Between("salt", "pepper")),
        "move between hello and world" to MoveCursor(Place.Between("hello", "world")),
        "right two words" to MoveCursor(Place.Units(2, TextUnit.WORD, true)),
        "left three characters" to MoveCursor(Place.Units(3, TextUnit.CHARACTER, false)),
        "right one line" to MoveCursor(Place.Units(1, TextUnit.LINE, true)),
        "left one sentence" to MoveCursor(Place.Units(1, TextUnit.SENTENCE, false)),
        "right two paragraphs" to MoveCursor(Place.Units(2, TextUnit.PARAGRAPH, true)),
        "select all text" to Select(TextScope.All),
        "select all" to Select(TextScope.All),
        "unselect all text" to TextCommand.Unselect,
        "unselect" to TextCommand.Unselect,
        "select to the beginning" to Select(TextScope.ToStart),
        "select to the start" to Select(TextScope.ToStart),
        "select to the end" to Select(TextScope.ToEnd),
        "select to the end of the text" to Select(TextScope.ToEnd),
        "select tomorrow" to Select(TextScope.Phrase("tomorrow")),
        "select the meeting" to Select(TextScope.Phrase("the meeting")),
        "highlight meeting" to Select(TextScope.Phrase("meeting")),
        "highlight tomorrow" to Select(TextScope.Phrase("tomorrow")),
        "select from hello to world" to Select(TextScope.FromTo("hello", "world")),
        "select from first to last" to Select(TextScope.FromTo("first", "last")),
        "select three words" to Select(units(3, TextUnit.WORD, false)),
        "select the next two sentences" to Select(units(2, TextUnit.SENTENCE, true)),
        "copy" to Clipboard(ClipboardAction.COPY),
        "copy that" to Clipboard(ClipboardAction.COPY),
        "cut" to Clipboard(ClipboardAction.CUT),
        "cut that" to Clipboard(ClipboardAction.CUT),
        "paste" to Clipboard(ClipboardAction.PASTE),
        "paste that" to Clipboard(ClipboardAction.PASTE),
        "show keyboard" to Keyboard(show = true),
        "show the keyboard" to Keyboard(show = true),
        "hide keyboard" to Keyboard(show = false),
        "hide the keyboard" to Keyboard(show = false),
        "new line" to TextCommand.NewLine,
        "new line please" to TextCommand.NewLine,
        "show password" to TextCommand.ShowPassword,
        "show the password" to TextCommand.ShowPassword,
        "search for coffee near me" to TextCommand.Search("coffee near me"),
        "search for weather tomorrow" to TextCommand.Search("weather tomorrow"),
    )

    private val more: List<Pair<String, Command>> = listOf(
        "Type Hello, World." to TextCommand.Type("Hello, World"),
        "type see you now" to TextCommand.Type("see you now"),
        "Please type delete." to TextCommand.Type("delete"),
        "start editing 3" to TextCommand.StartEditing(Target.Number(3)),
        "stop dictating" to TextCommand.StopEditing,
        "undo it" to TextCommand.Undo,
        "insert hello at the end" to TextCommand.Insert("hello", Place.End),
        "insert Dear Ana at the beginning" to TextCommand.Insert("Dear Ana", Place.Start),
        "insert the cake before the tea before noon" to TextCommand.Insert("the cake before the tea", Place.Before("noon")),
        "replace with care with caution" to TextCommand.Replace("with care", "caution"),
        "replace between hello and world with hi" to TextCommand.ReplaceBetween("hello", "world", "hi"),
        "upper case the title" to TextCommand.ChangeCase(TextCase.UPPER, "the title"),
        "lower case NASA" to TextCommand.ChangeCase(TextCase.LOWER, "NASA"),
        "delete it" to Delete(null),
        "backspace" to Delete(null),
        "delete all the text" to Delete(TextScope.All),
        "delete the next word" to Delete(units(1, TextUnit.WORD, true)),
        "delete the previous word" to Delete(units(1, TextUnit.WORD, false)),
        "delete a word" to Delete(units(1, TextUnit.WORD, false)),
        "delete word" to Delete(units(1, TextUnit.WORD, false)),
        "delete the word" to Delete(TextScope.Phrase("the word")),
        "delete twenty one characters" to Delete(units(21, TextUnit.CHARACTER, false)),
        "delete between hello and world" to Delete(TextScope.Between("hello", "world")),
        "delete everything between hello and world" to Delete(TextScope.Between("hello", "world")),
        "move the cursor after lunch" to MoveCursor(Place.After("lunch")),
        "move cursor before lunch" to MoveCursor(Place.Before("lunch")),
        "go to start" to MoveCursor(Place.Start),
        "move the cursor to the end of the text" to MoveCursor(Place.End),
        "left a word" to MoveCursor(Place.Units(1, TextUnit.WORD, false)),
        "right 4 letters" to MoveCursor(Place.Units(4, TextUnit.CHARACTER, true)),
        "select everything" to Select(TextScope.All),
        "deselect" to TextCommand.Unselect,
        "select the previous three lines" to Select(units(3, TextUnit.LINE, false)),
        "select between hello and world" to Select(TextScope.Between("hello", "world")),
        "highlight from hello to world" to Select(TextScope.FromTo("hello", "world")),
        "Copy." to Clipboard(ClipboardAction.COPY),
        "paste it" to Clipboard(ClipboardAction.PASTE),
        "close the keyboard" to Keyboard(show = false),
        "newline" to TextCommand.NewLine,
        "reveal password" to TextCommand.ShowPassword,
        "format as email" to TextCommand.FormatEmail,
        // A lone "to" or "for" right after the verb is a number (design D5).
        "delete to words" to Delete(units(2, TextUnit.WORD, false)),
        "right to words" to MoveCursor(Place.Units(2, TextUnit.WORD, true)),
        "left for characters" to MoveCursor(Place.Units(4, TextUnit.CHARACTER, false)),
        // Polite words after a whole command go; a phrase or a payload keeps its words.
        "delete all please" to Delete(TextScope.All),
        "select all please" to Select(TextScope.All),
        "delete it now" to Delete(null),
        "start editing please" to TextCommand.StartEditing(null),
        "delete the last word please" to Delete(units(1, TextUnit.WORD, false)),
        "delete now" to Delete(TextScope.Phrase("now")),
        "delete please" to Delete(null),
    )

    @Test
    fun everyTextPhraseOfTheCorpusParsesToItsCommand() {
        for ((said, expected) in corpus) assertEquals(said, expected, CommandParser.parse(said))
    }

    @Test
    fun otherPhrasingsParseToo() {
        for ((said, expected) in more) assertEquals(said, expected, CommandParser.parse(said))
    }

    @Test
    fun theDictationLinesOfTheCorpusAndHalfCommandsStayBare() {
        for (said in listOf(
            "hello comma how are you question mark",
            "see you at five period",
            "the meeting is on Monday exclamation mark",
            "capital p one two three",
            "open quote hello close quote she said",
            "type",
            "select",
            "highlight",
            "insert hello",
            "replace cat",
            "replace cat with",
            "left",
            "right now",
            "left three",
            "search",
            "search the web",
            "move along",
            "start the car",
            "upper deck",
            "go to the beach",
            "I think we should delete it later",
        )) {
            val parsed = CommandParser.parse(said)
            assertTrue("$said gave $parsed", parsed is Command.Bare)
        }
    }
}
```

- [ ] **Step 2: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :core:commands:test --tests dev.kestrel.commands.GrammarTextTest`
Expected: `BUILD FAILED` with 3 tests, 2 failed: `everyTextPhraseOfTheCorpusParsesToItsCommand` ("start editing expected:<StartEditing(target=null)> but was:<Bare(13 characters, number=false)>") and `otherPhrasingsParseToo`, because plan 3A's stub takes nothing; `theDictationLinesOfTheCorpusAndHalfCommandsStayBare` passes already.

- [ ] **Step 3: Parse the text phrases**

Replace the whole file `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarText.kt` with:

```kotlin
package dev.kestrel.commands

/**
 * The text phrases of plan 3B (design spec, section 9.7, and Appendix A: typing, punctuation, replacing, deleting, the
 * cursor, selecting, the clipboard, the keyboard, passwords and search). "Copy", "cut" and "paste" are fixed phrases
 * only, so that "copy this phone number" stays plan 3C's; no phrase here starts with "open", "call" or "dial". A payload
 * keeps the recogniser's case and the punctuation inside it.
 */
internal object GrammarText {
    private val STARTS = listOf("the beginning", "the start", "beginning", "start", "the beginning of the text", "the start of the text")
    private val ENDS = listOf("the end", "end", "the end of the text")
    private val UNITS = mapOf(
        "character" to TextUnit.CHARACTER, "characters" to TextUnit.CHARACTER, "letter" to TextUnit.CHARACTER,
        "letters" to TextUnit.CHARACTER, "word" to TextUnit.WORD, "words" to TextUnit.WORD, "sentence" to TextUnit.SENTENCE,
        "sentences" to TextUnit.SENTENCE, "line" to TextUnit.LINE, "lines" to TextUnit.LINE, "paragraph" to TextUnit.PARAGRAPH,
        "paragraphs" to TextUnit.PARAGRAPH,
    )
    private val SINGULAR_UNITS = setOf("character", "letter", "word", "sentence", "line", "paragraph")

    private val FIXED: Map<String, Command> = buildMap {
        put("start editing", TextCommand.StartEditing(null))
        for (phrase in listOf("stop editing", "stop typing", "stop dictating", "stop dictation")) put(phrase, TextCommand.StopEditing)
        for (phrase in listOf("undo", "undo that", "undo it")) put(phrase, TextCommand.Undo)
        for (phrase in listOf("redo", "redo that", "redo it")) put(phrase, TextCommand.Redo)
        for (phrase in listOf("format email", "format as email", "format email address", "format as email address", "format as an email address")) {
            put(phrase, TextCommand.FormatEmail)
        }
        for (phrase in listOf("delete", "delete that", "delete it", "backspace")) put(phrase, TextCommand.Delete(null))
        for (phrase in listOf("delete all", "delete all text", "delete all the text", "delete everything")) {
            put(phrase, TextCommand.Delete(TextScope.All))
        }
        for (phrase in listOf("delete selected text", "delete the selected text", "delete selection", "delete the selection")) {
            put(phrase, TextCommand.Delete(TextScope.Selection))
        }
        for (phrase in listOf("select all", "select all text", "select all the text", "select everything")) {
            put(phrase, TextCommand.Select(TextScope.All))
        }
        for (phrase in listOf("unselect", "unselect all", "unselect all text", "unselect text", "deselect", "deselect all", "deselect all text")) {
            put(phrase, TextCommand.Unselect)
        }
        for (start in STARTS) {
            put("delete to $start", TextCommand.Delete(TextScope.ToStart))
            put("select to $start", TextCommand.Select(TextScope.ToStart))
            for (verb in listOf("go to", "move to", "move the cursor to", "move cursor to")) put("$verb $start", TextCommand.MoveCursor(Place.Start))
        }
        for (end in ENDS) {
            put("delete to $end", TextCommand.Delete(TextScope.ToEnd))
            put("select to $end", TextCommand.Select(TextScope.ToEnd))
            for (verb in listOf("go to", "move to", "move the cursor to", "move cursor to")) put("$verb $end", TextCommand.MoveCursor(Place.End))
        }
        for (phrase in listOf("copy", "copy that", "copy it")) put(phrase, TextCommand.Clipboard(ClipboardAction.COPY))
        for (phrase in listOf("cut", "cut that", "cut it")) put(phrase, TextCommand.Clipboard(ClipboardAction.CUT))
        for (phrase in listOf("paste", "paste that", "paste it", "paste here")) put(phrase, TextCommand.Clipboard(ClipboardAction.PASTE))
        for (phrase in listOf("show keyboard", "show the keyboard")) put(phrase, TextCommand.Keyboard(show = true))
        for (phrase in listOf("hide keyboard", "hide the keyboard", "close keyboard", "close the keyboard")) {
            put(phrase, TextCommand.Keyboard(show = false))
        }
        for (phrase in listOf("new line", "newline")) put(phrase, TextCommand.NewLine)
        for (phrase in listOf("show password", "show the password", "show my password", "reveal password", "reveal the password")) {
            put(phrase, TextCommand.ShowPassword)
        }
    }

    fun fixed(words: List<String>, text: String): Command? = FIXED[words.joinToString(" ")]

    fun targeted(words: List<String>, text: String): Command? {
        if (words.size < 2) return null
        // "Delete all please": polite words after a whole command are left to the parser's second try (design D5), so that
        // they never become a phrase to find or a field's name. "Type see you now" keeps them in its payload.
        if (words.last() in CommandParser.POLITE_WORDS) {
            val inner = words.dropLastWhile { it in CommandParser.POLITE_WORDS }
            val counted = inner.size >= 2 && inner[0] in setOf("delete", "select", "highlight") && units(inner, 1) != null
            // "Delete please" is "delete"; "delete now" may still name the word "now".
            val whole = inner.joinToString(" ") in FIXED && (inner.size >= 2 || words.last() == "please")
            if (whole || counted) return null
        }
        fun cut(from: Int, to: Int = words.size) = cutWords(text, from, to)

        return when (words[0]) {
            "type" -> TextCommand.Type(cut(1))
            "start" -> if (words[1] == "editing" && words.size > 2) TargetWords.read(words, text, 2)?.let { TextCommand.StartEditing(it) } else null
            "insert" -> insert(words, text)
            "replace" -> replace(words, text)
            "capitalize", "capitalise" -> TextCommand.ChangeCase(TextCase.CAPITALIZE, cut(1))
            "uppercase" -> TextCommand.ChangeCase(TextCase.UPPER, cut(1))
            "lowercase" -> TextCommand.ChangeCase(TextCase.LOWER, cut(1))
            "upper", "lower" -> if (words[1] == "case" && words.size > 2) {
                TextCommand.ChangeCase(if (words[0] == "upper") TextCase.UPPER else TextCase.LOWER, cut(2))
            } else {
                null
            }
            "delete" -> scope(words, text, 1)?.let { TextCommand.Delete(it) }
            "select", "highlight" -> scope(words, text, 1)?.let { TextCommand.Select(it) }
            "move" -> move(words, text)
            "left", "right" -> units(words, 1)?.takeIf { !it.directed }?.let {
                TextCommand.MoveCursor(Place.Units(it.count, it.unit, forward = words[0] == "right"))
            }
            "search" -> if (words[1] == "for" && words.size > 2) TextCommand.Search(cut(2)) else null
            else -> null
        }
    }

    /** What "delete" and "select" name from the word [from] on: some units, "from X to Y", "between X and Y" or a phrase. */
    private fun scope(words: List<String>, text: String, from: Int): TextScope? {
        units(words, from)?.let { return TextScope.Units(it.count, it.unit, it.forward) }
        if (words[from] == "from") {
            val to = words.find("to", from + 2)
            if (to > 0 && to < words.size - 1) return TextScope.FromTo(cutWords(text, from + 1, to), cutWords(text, to + 1, words.size))
        }
        val between = when {
            words[from] == "between" -> from
            words[from] == "everything" && words.getOrNull(from + 1) == "between" -> from + 1
            else -> -1
        }
        if (between >= 0) {
            val and = words.find("and", between + 2)
            if (and > 0 && and < words.size - 1) {
                return TextScope.Between(cutWords(text, between + 1, and), cutWords(text, and + 1, words.size))
            }
        }
        return TextScope.Phrase(cutWords(text, from, words.size))
    }

    private class Units(val count: Int, val unit: TextUnit, val forward: Boolean, val directed: Boolean)

    /**
     * Some units from the word [from] to the end: "three words", "the next two sentences", "the last line", "a word" or
     * "word". "The word" and "words" alone are phrases. "Next" goes forwards; a bare count, "previous" and "last" go
     * backwards.
     */
    private fun units(words: List<String>, from: Int): Units? {
        val unit = UNITS[words.last()] ?: return null
        var index = from
        val article = words.getOrNull(index) == "the"
        if (article) index++
        val direction = words.getOrNull(index)
        val directed = direction in setOf("next", "previous", "last")
        if (directed) index++
        val countWords = words.subList(index, words.size - 1)
        val count = when {
            countWords.isEmpty() -> if (directed || (!article && words.last() in SINGULAR_UNITS)) 1 else return null
            countWords == listOf("a") || countWords == listOf("one") -> 1
            // Right after the verb, a lone "to" is 2 and "for" is 4, as the recogniser writes them (design D5).
            else -> SpokenNumbers.number(countWords, lone = true)?.takeIf { it > 0 } ?: return null
        }
        return Units(count, unit, forward = direction == "next", directed = directed)
    }

    /** "Insert X before Y", "insert X after Y", "insert X between Y and Z", "insert X at the beginning" or "at the end". */
    private fun insert(words: List<String>, text: String): Command? {
        val between = words.lastIndexOf("between")
        if (between >= 2) {
            val and = words.find("and", between + 2)
            if (and > 0 && and < words.size - 1) {
                val place = Place.Between(cutWords(text, between + 1, and), cutWords(text, and + 1, words.size))
                return TextCommand.Insert(cutWords(text, 1, between), place)
            }
        }
        val split = words.indices.lastOrNull { it >= 2 && it < words.size - 1 && (words[it] == "before" || words[it] == "after") }
        if (split != null) {
            val phrase = cutWords(text, split + 1, words.size)
            val place = if (words[split] == "before") Place.Before(phrase) else Place.After(phrase)
            return TextCommand.Insert(cutWords(text, 1, split), place)
        }
        for ((places, place) in listOf(STARTS to Place.Start, ENDS to Place.End)) {
            for (ending in places) {
                val tail = listOf("at") + ending.split(' ')
                val at = words.size - tail.size
                if (at >= 2 && words.subList(at, words.size) == tail) return TextCommand.Insert(cutWords(text, 1, at), place)
            }
        }
        return null
    }

    /** "Replace X with Y" (at the first "with"), and "replace everything between X and Y with Z". */
    private fun replace(words: List<String>, text: String): Command? {
        val between = when {
            words[1] == "between" -> 1
            words[1] in setOf("everything", "all") && words.getOrNull(2) == "between" -> 2
            else -> -1
        }
        if (between > 0) {
            val and = words.find("and", between + 2)
            val with = if (and > 0) words.find("with", and + 2) else -1
            if (with > 0 && with < words.size - 1) {
                return TextCommand.ReplaceBetween(
                    cutWords(text, between + 1, and),
                    cutWords(text, and + 1, with),
                    cutWords(text, with + 1, words.size),
                )
            }
        }
        val with = words.find("with", 2)
        if (with < 0 || with == words.size - 1) return null
        return TextCommand.Replace(cutWords(text, 1, with), cutWords(text, with + 1, words.size))
    }

    /** "Move after X", "move before X", "move between X and Y", also with "the cursor" or "cursor" after "move". */
    private fun move(words: List<String>, text: String): Command? {
        var index = 1
        if (words.getOrNull(index) == "the" && words.getOrNull(index + 1) == "cursor") index += 2
        else if (words.getOrNull(index) == "cursor") index++
        if (index >= words.size - 1) return null
        val rest = index + 1
        return when (words[index]) {
            "after" -> TextCommand.MoveCursor(Place.After(cutWords(text, rest, words.size)))
            "before" -> TextCommand.MoveCursor(Place.Before(cutWords(text, rest, words.size)))
            "between" -> {
                val and = words.find("and", rest + 1)
                if (and > 0 && and < words.size - 1) {
                    TextCommand.MoveCursor(Place.Between(cutWords(text, rest, and), cutWords(text, and + 1, words.size)))
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /** The index of the first [word] at [from] or after it, or -1. */
    private fun List<String>.find(word: String, from: Int): Int = (from until size).firstOrNull { this[it] == word } ?: -1
}
```

- [ ] **Step 4: List the text commands**

Replace the whole file `core/commands/src/main/kotlin/dev/kestrel/commands/HelpText.kt` with:

```kotlin
package dev.kestrel.commands

/** The text commands of the command list. Every example must parse to a command (`CommandHelpTest`). */
internal object HelpText {
    val groups: List<HelpGroup> = listOf(
        HelpGroup(
            "Dictation (say the punctuation: comma, period, question mark)",
            listOf("start editing", "stop editing", "type see you soon", "new line"),
        ),
        HelpGroup(
            "Editing text",
            listOf("undo", "redo", "insert very before nice", "replace Monday with Tuesday", "capitalize paris", "uppercase nasa", "format email"),
        ),
        HelpGroup("Deleting text", listOf("delete", "delete all", "delete tomorrow", "delete three words", "delete from hello to world")),
        HelpGroup("Moving the cursor", listOf("go to the end", "move after tomorrow", "left three characters", "right two words")),
        HelpGroup("Selecting text", listOf("select all", "select tomorrow", "select the next two words", "unselect")),
        HelpGroup("Clipboard and keyboard", listOf("copy", "cut", "paste", "show keyboard", "hide keyboard")),
        HelpGroup("Passwords and search", listOf("show password", "search for coffee near me")),
    )
}
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD SUCCESSFUL`, with 88 tests in `:core:commands` (85 + 3, `GrammarTextTest` 3); `CommandHelpTest` passes with the text examples, and `GrammarScreenTest` and `CommandParserTest` still pass.

- [ ] **Step 6: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` 88, `:app` 314, `:core:screen` 55, `:core:voice` 140, `:core:privacy` 48, `:core:safety` 2; `RulesTest` among the `:app` tests), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 7: Run the touch commands' device tests**

"Delete" now parses to `TextCommand.Delete(null)`; the text part's stub takes nothing, so the executor still taps the dialog's "Delete" button as a bare label, and "again" taps it once more.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554 -Class TouchCommandsTest`
Expected: `BUILD SUCCESSFUL` and "Command tests: 20 run, 0 failed, 0 errors, 0 skipped." (`TouchCommandsTest` 20), with no warning from the restores at the end.

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
git add core/commands/src/main/kotlin/dev/kestrel/commands/GrammarText.kt core/commands/src/main/kotlin/dev/kestrel/commands/HelpText.kt core/commands/src/test/kotlin/dev/kestrel/commands/GrammarTextTest.kt
git commit -m "feat(commands): parse the typing, editing, cursor, selection, clipboard, keyboard, password and search phrases" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: The text editor and the text fixtures

**Files:**
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/act/TextEditor.kt` (the whole file, replacing plan 3A's stub with the same constructor and signatures)
- Create: `testapp/src/main/kotlin/dev/kestrel/testapp/EditActivity.kt`, `ComposeEditActivity.kt`, `WebEditActivity.kt`, `testapp/src/main/res/values/ids_text.xml`; Modify: `testapp/src/main/AndroidManifest.xml` (at the marker `        <!-- Plan 3B: text fixtures -->`)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/commands/TextFieldTests.kt` (helpers), `TextEditorTest.kt` (new)

**Interfaces:**
- Consumes: plan 3A's stub `class TextEditor(service: AccessibilityService, snapshots: SnapshotCache<LiveSnapshot>, tracker: ScreenChangeTracker)`, `FieldState`, `ClipboardKind`, `ScreenSession.text`; `LiveSnapshot.node(key)`, `shownText()`, `ActionResult`, `ActionMethod`, `ScreenChangeTracker.awaitQuiet`; the test helpers `CommandTests.only`, `ScreenServiceRule` (`open`, `awaitElement`, `session`), `fixturePage`, `addStatus`.
- Produces (`:core:screen`, package `dev.kestrel.screen.act`): `TextEditor` with the suspending `read(key: String): FieldState?`, `replace(key: String, start: Int, end: Int, replacement: String, selectionStart: Int, selectionEnd: Int): ActionResult`, `newLine(key: String): ActionResult`, `select(key: String, start: Int, end: Int): ActionResult`, `submit(key: String): ActionResult`, `clipboard(key: String, kind: ClipboardKind): ActionResult`, `typeAtCursor(key: String, text: String): ActionResult`, `deleteBeforeCursor(key: String, count: Int): ActionResult`, `searches(key: String): Boolean` (the field has the focus and its started input has the search action; Task 7's "search for" uses it), `phone(key: String): Boolean` (the node's input type or the started input has the phone class, `InputType.TYPE_CLASS_PHONE`; Task 5's dictation uses it), and in its companion the reasons that the text part tells the owner: `TextEditor.ONE_LINE` ("This field has only one line."), `NO_ACTION` ("This field has no action to submit."), `SELECT_FIRST` ("Select some text first."), `NOT_TAKEN` ("The text field didn't take the change."), each an `ActionResult.Failed`. `:testapp`: `EditActivity` (title "Edit fixture"; the fields `text_search`, hint "Search", one line, the search action; `text_notes`, hint "Notes", more lines; `text_password`, hint "Password"; the check box `text_show_password` "Show password"; `text_paste`, hint "Paste here"; `text_phone`, hint "Phone", the phone input type; the lines `Search: "<text>" <start>-<end>`, `Notes: "<text>" <start>-<end>` with "\n" for a line break, `Password: <n> characters`, `Paste: "<text>"`, `Phone: "<text>"`; the status line `Action <id>: "<text>"`, "Password shown", "Password hidden"), `ComposeEditActivity` (title "Compose edit fixture"; the test tags `compose_search` "Compose search", `compose_notes` "Compose notes", `compose_state` "Compose state"; the lines `Compose search: "<text>"`, `Compose notes: "<text>" <start>-<end>`, `Compose state: "<text>" <start>-<end>`; the status line `Searched: "<text>"` and `Searched state: "<text>"`), `WebEditActivity` (title "Web edit fixture"; the HTML IDs `web_search` "Web search", `web_notes` "Web notes", `web_rich` "Web rich text", `web_password` "Web password" with the button "Show password", which turns it into a plain text input; the lines `Web search: "<text>" <start>-<end>`, `Web notes: "<text>" <start>-<end>`, `Web rich text: "<text>"`, `Web password: <n> characters`; the status line `Submitted: "<text>"`, "Web password shown", "Web password hidden"). androidTest: `internal object TextFieldTests` with the extensions `ScreenServiceRule.awaitLine(text, timeoutMillis = 5_000)`, `field(id)`, `awaitFocus(id)`, `awaitKeyboard(shown)`, `awaitBar(text)`.

Design D8 and prototype P4. Before any call on the input connection, the editor focuses the field with the focus action (a click when that fails), waits up to 2 s until the field's app has started an input with a real type from that package (a window without a field also starts one, with the type 0 and no connection), and checks that the node has the focus, because Compose keeps the connection on the field that has it. `replace` selects the range on the connection, commits only the replacement, sets the selection afterwards and reads the text back for up to 1 s; the fallback sets the whole new text with the set-text action, then the selection, and reads it back too. A password field shows only dots, so there only the length is checked, and set-text only empties it. A field has more than one line when its node says so or the input that its app started has `TYPE_TEXT_FLAG_MULTI_LINE`, because Compose's nodes always say one line (`TYPE_TEXT_FLAG_IME_MULTI_LINE` marks a field with one line). A field is a password field when its node says so or its started input has a password type: "show password" removes the node's flag, but not the input type, so Kestrel keeps spelling and keeps the password out of its history. The read-back treats a no-break space and a space as the same, because web editors swap them. A new line is the Enter key, only in a field with more lines. `submit` sends the field's own editor action through the input method (search, go, send, done, next or previous; in a field with more lines only search, go or send, because Android gives every field without an action "next" or "done"), the Enter key in a field with one line and no action, and the IME-enter action only when the input does not start, never on a field with more lines. Cut, copy and paste focus the field first; cut and copy need a selection, and a cut, and a paste at the cursor, are read back. The fixtures report what the app saw in lines of their own, so that the tests check the field, not Kestrel's reading of it; the password line reports only the length.

- [ ] **Step 1: Write the failing tests**

Create `app/src/androidTest/kotlin/dev/kestrel/app/commands/TextFieldTests.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.WindowKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Helpers for the device tests of the text commands (design D15 of sub-project 3). The text fixtures report each field's
 * text and selection in a line of their own, such as `Notes: "Hello" 5-5`, so the tests check what the app saw, not
 * what Kestrel read. Their failure messages name only the fixture app's labels.
 */
internal object TextFieldTests {
    /** Waits until the fixture shows the line [text]. */
    fun ScreenServiceRule.awaitLine(text: String, timeoutMillis: Long = 5_000): ScreenElement =
        runBlocking { awaitElement(timeoutMillis) { it.packageName == FIXTURES && it.label == text } }

    /**
     * The text field of the fixture with the view ID [id]: `text_notes` for a view, the test tag `compose_notes` for
     * Compose, the HTML ID `web_notes` for the web page.
     */
    fun ScreenServiceRule.field(id: String): ScreenElement =
        runBlocking { awaitElement { it.editable && it.packageName == FIXTURES && it.viewId?.substringAfter(":id/") == id } }

    /** Waits until the field with the view ID [id] has the focus. */
    fun ScreenServiceRule.awaitFocus(id: String): ScreenElement =
        runBlocking { awaitElement { it.editable && it.focused && it.viewId?.substringAfter(":id/") == id } }

    /** Waits until the keyboard shows, or with [shown] false, until it has gone. */
    fun ScreenServiceRule.awaitKeyboard(shown: Boolean) = runBlocking {
        withTimeout(5_000) {
            while (session.snapshots.current().snapshot.windows.any { it.kind == WindowKind.INPUT_METHOD } != shown) delay(100)
        }
    }

    /** Waits until the feedback bar shows [text]. */
    fun ScreenServiceRule.awaitBar(text: String) = runBlocking {
        withTimeout(5_000) { while (session.feedbackText != text) delay(50) }
    }
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/commands/TextEditorTest.kt`:

```kotlin
package dev.kestrel.app.commands

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.commands.TextFieldTests.awaitLine
import dev.kestrel.app.commands.TextFieldTests.field
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.ClipboardKind
import dev.kestrel.screen.act.TextEditor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Kestrel's text editor in every kind of field (design spec, section 9.7, and prototype P4): `EditText`, the three
 * Compose text fields and the web page's input, textarea and contenteditable. It runs only through
 * scripts\command-test.ps1, like every test of this package; the first rule skips it before the second turns anything on.
 */
@RunWith(AndroidJUnit4::class)
class TextEditorTest {
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    private val editor: TextEditor get() = screen.session.text

    private val throughInputMethod = ActionResult.Done(ActionMethod.INPUT_METHOD)

    @Test
    fun readGivesAnEmptyFieldWithoutItsHintAndTheFieldsKind() = runBlocking<Unit> {
        screen.open("EditActivity", "Edit fixture")

        val search = checkNotNull(editor.read(screen.field("text_search").key))
        assertEquals("", search.text)
        assertEquals(0, search.selectionStart)
        assertFalse(search.password)
        assertFalse(search.multiLine)
        assertTrue(checkNotNull(editor.read(screen.field("text_notes").key)).multiLine)
        assertTrue(checkNotNull(editor.read(screen.field("text_password").key)).password)
    }

    @Test
    fun anEditTextTakesEditsAndSelectionsThroughTheInputMethod() = runBlocking<Unit> {
        screen.open("EditActivity", "Edit fixture")
        val search = screen.field("text_search").key

        assertEquals(throughInputMethod, editor.replace(search, 0, 0, "hello world", 11, 11))
        screen.awaitLine("Search: \"hello world\" 11-11")
        // Only the changed range goes in, and the selection afterwards is Kestrel's.
        assertEquals(throughInputMethod, editor.replace(search, 6, 11, "there", 6, 11))
        screen.awaitLine("Search: \"hello there\" 6-11")
        assertTrue(editor.select(search, 0, 5) is ActionResult.Done)
        screen.awaitLine("Search: \"hello there\" 0-5")
        assertEquals("hello there", checkNotNull(editor.read(search)).text)
    }

    @Test
    fun aNewLineNeedsAFieldWithMoreLinesAndSubmitUsesTheFieldsAction() = runBlocking<Unit> {
        screen.open("EditActivity", "Edit fixture")
        val search = screen.field("text_search").key
        val notes = screen.field("text_notes").key

        assertEquals(TextEditor.ONE_LINE, editor.newLine(search))
        editor.replace(notes, 0, 0, "first", 5, 5)
        // The notes field has the focus, and its input has no search action.
        assertFalse(editor.searches(notes))
        assertFalse(editor.phone(notes))
        assertTrue(editor.phone(screen.field("text_phone").key))
        assertTrue(editor.newLine(notes) is ActionResult.Done)
        editor.replace(notes, 6, 6, "second", 12, 12)
        screen.awaitLine("Notes: \"first\\nsecond\" 12-12")
        assertEquals(TextEditor.NO_ACTION, editor.submit(notes))

        editor.replace(search, 0, 0, "kestrel", 7, 7)
        assertTrue(editor.searches(search))
        assertTrue(editor.submit(search) is ActionResult.Done)
        // The app's listener gets the search action (3), not the action 0 that the IME-enter action sends (P4).
        screen.awaitLine("Action 3: \"kestrel\"")
    }

    @Test
    fun cutCopyAndPasteActOnTheFocusedField() = runBlocking<Unit> {
        screen.open("EditActivity", "Edit fixture")
        val search = screen.field("text_search").key
        val paste = screen.field("text_paste").key
        editor.replace(search, 0, 0, "copy me", 7, 7)

        assertEquals(TextEditor.SELECT_FIRST, editor.clipboard(search, ClipboardKind.COPY))
        editor.select(search, 0, 4)
        assertTrue(editor.clipboard(search, ClipboardKind.COPY) is ActionResult.Done)
        assertTrue(editor.clipboard(paste, ClipboardKind.PASTE) is ActionResult.Done)
        screen.awaitLine("Paste: \"copy\"")
        // The paste field has the focus now, and cut needs no more.
        editor.select(paste, 0, 2)
        assertTrue(editor.clipboard(paste, ClipboardKind.CUT) is ActionResult.Done)
        screen.awaitLine("Paste: \"py\"")
    }

    @Test
    fun aPasswordFieldTypesAndDeletesAtTheCursor() = runBlocking<Unit> {
        screen.open("EditActivity", "Edit fixture")
        val password = screen.field("text_password").key

        assertEquals(throughInputMethod, editor.typeAtCursor(password, "abc1"))
        screen.awaitLine("Password: 4 characters")
        assertEquals(throughInputMethod, editor.deleteBeforeCursor(password, 1))
        screen.awaitLine("Password: 3 characters")
        val dots = checkNotNull(editor.read(password)).text.length
        assertTrue(editor.replace(password, 0, dots, "", 0, 0) is ActionResult.Done)
        screen.awaitLine("Password: 0 characters")
    }

    @Test
    fun composeFieldsTakeEditsAndTheInputTellsWhichHasMoreLines() = runBlocking<Unit> {
        screen.open("ComposeEditActivity", "Compose edit fixture")
        val search = screen.field("compose_search").key
        val notes = screen.field("compose_notes").key
        val state = screen.field("compose_state").key

        assertEquals(throughInputMethod, editor.replace(notes, 0, 0, "one two", 7, 7))
        screen.awaitLine("Compose notes: \"one two\" 7-7")
        // Compose's node says one line for every field; the input that the app started knows better.
        assertTrue(checkNotNull(editor.read(notes)).multiLine)
        assertTrue(editor.newLine(notes) is ActionResult.Done)
        screen.awaitLine("Compose notes: \"one two\\n\" 8-8")
        assertTrue(editor.select(notes, 0, 3) is ActionResult.Done)
        screen.awaitLine("Compose notes: \"one two\\n\" 0-3")

        assertEquals(throughInputMethod, editor.replace(state, 0, 0, "state", 5, 5))
        screen.awaitLine("Compose state: \"state\" 5-5")
        assertEquals(throughInputMethod, editor.replace(search, 0, 0, "tea", 3, 3))
        screen.awaitLine("Compose search: \"tea\"")
        assertFalse(checkNotNull(editor.read(search)).multiLine)
        assertEquals(TextEditor.ONE_LINE, editor.newLine(search))
        assertTrue(editor.submit(search) is ActionResult.Done)
        screen.awaitLine("Searched: \"tea\"")
    }

    @Test
    fun webFieldsTakeEditsSelectionsLinesAndSubmit() = runBlocking<Unit> {
        screen.open("WebEditActivity", "Web edit fixture")
        val search = screen.field("web_search").key
        val notes = screen.field("web_notes").key
        val rich = screen.field("web_rich").key

        assertEquals(throughInputMethod, editor.replace(notes, 0, 0, "a", 1, 1))
        assertTrue(editor.newLine(notes) is ActionResult.Done)
        screen.awaitLine("Web notes: \"a\\n\" 2-2")
        assertEquals(throughInputMethod, editor.replace(rich, 0, 0, "rich", 4, 4))
        screen.awaitLine("Web rich text: \"rich\"")

        assertEquals(throughInputMethod, editor.replace(search, 0, 0, "hello", 5, 5))
        screen.awaitLine("Web search: \"hello\" 5-5")
        assertTrue(editor.select(search, 0, 2) is ActionResult.Done)
        screen.awaitLine("Web search: \"hello\" 0-2")
        assertTrue(editor.submit(search) is ActionResult.Done)
        screen.awaitLine("Submitted: \"hello\"")
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, because the device tests do not compile: "Unresolved reference 'ONE_LINE'", "Unresolved reference 'NO_ACTION'" and "Unresolved reference 'SELECT_FIRST'" (plan 3A's stub has no such reasons; its functions return "Not available yet").

- [ ] **Step 3: Add the text fixtures**

Create `testapp/src/main/res/values/ids_text.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- The view IDs of the text fixtures (EditActivity), which plan 3B's tests look for. -->
<resources>
    <item name="text_search" type="id" />
    <item name="text_search_state" type="id" />
    <item name="text_notes" type="id" />
    <item name="text_notes_state" type="id" />
    <item name="text_password" type="id" />
    <item name="text_password_state" type="id" />
    <item name="text_show_password" type="id" />
    <item name="text_paste" type="id" />
    <item name="text_paste_state" type="id" />
    <item name="text_phone" type="id" />
    <item name="text_phone_state" type="id" />
</resources>
```

Create `testapp/src/main/kotlin/dev/kestrel/testapp/EditActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The text fields of the editing tests in Android's own views (design spec, section 9.7): a search field with one line
 * and the search action, a notes field with more lines, a password field with a "Show password" check box, a field
 * to paste into, and a phone number field, as a dialpad has. The line under each field reports its text and selection, as `Notes: "hello" 5-5`, with each line break
 * as "\n", because Kestrel's labels join lines; the password line reports only the length. The top status line reports
 * the editor actions and the check box.
 */
class EditActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Edit fixture")
        val status = page.addStatus()
        val search = page.addField(R.id.text_search, "Search", R.id.text_search_state, InputType.TYPE_CLASS_TEXT) { field ->
            "Search: \"${field.text}\" ${field.selectionStart}-${field.selectionEnd}"
        }
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.setOnEditorActionListener { field, action, _ ->
            status.text = "Action $action: \"${field.text}\""
            true
        }
        val notes = page.addField(R.id.text_notes, "Notes", R.id.text_notes_state, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) { field ->
            "Notes: \"${field.text.toString().replace("\n", "\\n")}\" ${field.selectionStart}-${field.selectionEnd}"
        }
        notes.minLines = 3
        notes.gravity = Gravity.TOP or Gravity.START
        val password = page.addField(
            R.id.text_password,
            "Password",
            R.id.text_password_state,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        ) { field -> "Password: ${field.text.length} characters" }
        page.addView(
            CheckBox(this).apply {
                id = R.id.text_show_password
                text = "Show password"
                setOnCheckedChangeListener { _, shown ->
                    password.transformationMethod =
                        if (shown) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                    status.text = if (shown) "Password shown" else "Password hidden"
                }
            },
        )
        page.addField(R.id.text_paste, "Paste here", R.id.text_paste_state, InputType.TYPE_CLASS_TEXT) { field ->
            "Paste: \"${field.text}\""
        }
        page.addField(R.id.text_phone, "Phone", R.id.text_phone_state, InputType.TYPE_CLASS_PHONE) { field ->
            "Phone: \"${field.text}\""
        }
    }

    /** Adds a field with [hint] and, under it, the line that [report] fills whenever its text or selection changes. */
    private fun LinearLayout.addField(id: Int, hint: String, stateId: Int, type: Int, report: (EditText) -> String): EditText {
        val state = TextView(context).apply { this.id = stateId }
        val field = ReportingField(context).apply {
            this.id = id
            this.hint = hint
            // The input type alone makes the field one line or more, and keeps a password's dots.
            inputType = type
            onChange = { state.text = report(it) }
        }
        addView(field)
        addView(state)
        state.text = report(field)
        return field
    }
}

/** A text field that reports each change of its text or selection. */
private class ReportingField(context: Context) : EditText(context) {
    // EditText reports a selection while it is built, before this is set.
    var onChange: ((EditText) -> Unit)? = null

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(text: Editable?) {
                onChange?.invoke(this@ReportingField)
            }
        })
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onChange?.invoke(this)
    }
}
```

Create `testapp/src/main/kotlin/dev/kestrel/testapp/ComposeEditActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * The text fields of the editing tests in Jetpack Compose, as prototype P4 had them: a `BasicTextField` with a `String`
 * (one line, the search action and a placeholder), one with a `TextFieldValue` (more lines), and one with a
 * `TextFieldState` (one line and the search action). Their nodes all say "one line"; only the input that the app starts
 * tells the notes field apart. The test tags are the view IDs, and the line under each field reports its text and, where
 * Compose tells it, its selection.
 */
class ComposeEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var status by remember { mutableStateOf("Ready") }
                var search by remember { mutableStateOf("") }
                var notes by remember { mutableStateOf(TextFieldValue("")) }
                val state = rememberTextFieldState()
                Column(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).semantics { testTagsAsResourceId = true },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Compose edit fixture", style = MaterialTheme.typography.headlineSmall)
                    Text(status)
                    BasicTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth().testTag("compose_search").semantics { contentDescription = "Compose search" },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { status = "Searched: \"$search\"" }),
                        decorationBox = { field ->
                            Box {
                                if (search.isEmpty()) Text("Search")
                                field()
                            }
                        },
                    )
                    Text("Compose search: \"$search\"")
                    BasicTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        modifier = Modifier.fillMaxWidth().testTag("compose_notes").semantics { contentDescription = "Compose notes" },
                        minLines = 3,
                    )
                    Text("Compose notes: \"${notes.text.replace("\n", "\\n")}\" ${notes.selection.start}-${notes.selection.end}")
                    BasicTextField(
                        state = state,
                        modifier = Modifier.fillMaxWidth().testTag("compose_state").semantics { contentDescription = "Compose state" },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        onKeyboardAction = { status = "Searched state: \"${state.text}\"" },
                    )
                    Text("Compose state: \"${state.text}\" ${state.selection.start}-${state.selection.end}")
                }
            }
        }
    }
}
```

Create `testapp/src/main/kotlin/dev/kestrel/testapp/WebEditActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.widget.LinearLayout

/**
 * The text fields of the editing tests on a web page in a WebView, as prototype P4 had them: a search input in a form, a
 * textarea and a contenteditable, and a password input with a "Show password" button that turns it into a plain text
 * input, as login pages do. A script reports each field's text and the selection of the input and the textarea in a line
 * under the field (only the length for the password), and the form's submit and the button in the status line. The
 * page loads nothing from the network.
 */
class WebEditActivity : Activity() {
    // The script only reports what happens in the fields of this fixed page.
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Web edit fixture")
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        page.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        web.loadDataWithBaseURL(null, PAGE, "text/html", "utf-8", null)
    }

    private companion object {
        const val PAGE = """<!doctype html>
<html>
<head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body>
<p id="web_status">Ready</p>
<form id="web_form"><input type="search" id="web_search" placeholder="Web search" aria-label="Web search"></form>
<p id="web_search_state"></p>
<textarea id="web_notes" rows="3" aria-label="Web notes"></textarea>
<p id="web_notes_state"></p>
<div id="web_rich" contenteditable="true" aria-label="Web rich text" style="border: 1px solid gray; min-height: 3em"></div>
<p id="web_rich_state"></p>
<input type="password" id="web_password" aria-label="Web password">
<button type="button" id="web_show">Show password</button>
<p id="web_password_state"></p>
<script>
function show(id, name, field) {
  var text = (field.value !== undefined ? field.value : field.innerText).replace(/\n/g, "\\n");
  var selection = field.selectionStart !== undefined ? " " + field.selectionStart + "-" + field.selectionEnd : "";
  document.getElementById(id).textContent = name + ": \"" + text + "\"" + selection;
}
var fields = [["web_search", "Web search"], ["web_notes", "Web notes"], ["web_rich", "Web rich text"]];
fields.forEach(function (pair) {
  var field = document.getElementById(pair[0]);
  var report = function () { show(pair[0] + "_state", pair[1], field); };
  ["input", "select", "keyup", "focus"].forEach(function (type) { field.addEventListener(type, report); });
  report();
});
document.addEventListener("selectionchange", function () {
  fields.forEach(function (pair) { show(pair[0] + "_state", pair[1], document.getElementById(pair[0])); });
});
var password = document.getElementById("web_password");
var reportPassword = function () {
  document.getElementById("web_password_state").textContent = "Web password: " + password.value.length + " characters";
};
["input", "keyup", "focus"].forEach(function (type) { password.addEventListener(type, reportPassword); });
reportPassword();
document.getElementById("web_show").addEventListener("click", function () {
  password.type = password.type === "password" ? "text" : "password";
  document.getElementById("web_status").textContent = password.type === "text" ? "Web password shown" : "Web password hidden";
});
document.getElementById("web_form").addEventListener("submit", function (event) {
  event.preventDefault();
  document.getElementById("web_status").textContent = "Submitted: \"" + document.getElementById("web_search").value + "\"";
});
</script>
</body>
</html>"""
    }
}
```

In `testapp/src/main/AndroidManifest.xml`, replace

```xml
        <!-- Plan 3B: text fixtures -->
```

with

```xml
        <activity
            android:name=".EditActivity"
            android:exported="true" />
        <activity
            android:name=".ComposeEditActivity"
            android:exported="true" />
        <activity
            android:name=".WebEditActivity"
            android:exported="true" />
        <!-- Plan 3B: text fixtures -->
```

- [ ] **Step 4: Edit text fields through the input method**

Replace the whole file `core/screen/src/main/kotlin/dev/kestrel/screen/act/TextEditor.kt` with:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache
import dev.kestrel.screen.read.shownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Which clipboard action [TextEditor.clipboard] performs. */
enum class ClipboardKind { CUT, COPY, PASTE }

/**
 * A text field as the editing commands see it (design spec, section 9.7): its [text] (empty while it shows its hint), the
 * selection, whether it is a password field, has more than one line and has the focus, and its app.
 */
data class FieldState(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val password: Boolean,
    val multiLine: Boolean,
    val focused: Boolean,
    val packageName: String?,
) {
    override fun toString() =
        "FieldState(${text.length} characters, selection $selectionStart to $selectionEnd, password=$password, " +
            "multiLine=$multiLine, focused=$focused)"
}

/**
 * Edits text fields through Kestrel's accessibility input method, with set-text as the fallback (design spec, section
 * 9.7, and prototype P4). Before any call on the input connection, Kestrel focuses the field, waits until the field's app
 * has started the input, and checks that the field still has the focus, because Compose keeps the connection on the
 * field that has it. Every edit is read back. Kestrel never uses Android's movement actions or the app's own undo: it
 * computes every offset itself. The node actions are blocking binder calls on the caller's dispatcher, so callers run
 * them on `Dispatchers.IO`; the input connection is called on the main thread.
 */
class TextEditor(
    private val service: AccessibilityService,
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val tracker: ScreenChangeTracker,
) {
    /**
     * The live state of the field with [key], or null when it is not on the screen. A negative selection counts as the
     * cursor at the end. It has more than one line when its node says so or the input that its app started does, because
     * Compose's nodes always say one line. It is a password field when its node says so or the input does, because a
     * password that "show password" shows loses the node's flag but keeps its input type.
     */
    suspend fun read(key: String): FieldState? {
        val field = field(key) ?: return null
        val text = field.node.shownText().orEmpty()
        val (start, end) = selection(field.node, text)
        return FieldState(text, start, end, password(field), multiLine(field), field.node.isFocused, field.element.packageName)
    }

    /**
     * Replaces the text from [start] until [end] of the field with [key] by [replacement], and then selects
     * [selectionStart] to [selectionEnd]. The input method comes first: it replaces only that range, makes the events
     * that web pages expect, and lands in the field's undo history. When the input does not start or the field does not
     * show the result within 1 s, Kestrel sets the whole new text with the set-text action instead. A password field
     * shows only dots, so there only the length is checked, and set-text only empties it.
     */
    suspend fun replace(key: String, start: Int, end: Int, replacement: String, selectionStart: Int, selectionEnd: Int): ActionResult {
        val field = field(key) ?: return NOT_ON_SCREEN
        if (!field.element.editable) return NOT_A_FIELD
        if (!field.node.isEnabled) return DISABLED
        val before = field.node.shownText().orEmpty()
        if (start < 0 || start > end || end > before.length) return CHANGED
        val expected = before.substring(0, start) + replacement + before.substring(end)
        val password = password(field)
        val connection = connect(field)
        if (connection != null) {
            withContext(Dispatchers.Main) {
                connection.setSelection(start, end)
                connection.commitText(replacement, 1, null)
                connection.setSelection(selectionStart, selectionEnd)
            }
            if (shows(field.node, expected, password)) return settled(ActionMethod.INPUT_METHOD)
        }
        if (password && expected.isNotEmpty()) return NOT_TAKEN
        return setText(field, expected, selectionStart, selectionEnd)
    }

    /**
     * Starts a new line at the cursor with the Enter key, which every kind of multi-line field takes as a line break
     * (P4). A field with one line gets nothing, because the Enter key would submit it.
     */
    suspend fun newLine(key: String): ActionResult {
        val field = field(key) ?: return NOT_ON_SCREEN
        if (!field.node.isEnabled) return DISABLED
        val before = field.node.shownText().orEmpty()
        val (start, end) = selection(field.node, before)
        val connection = connect(field)
        if (!multiLine(field)) return ONE_LINE
        if (connection != null) {
            withContext(Dispatchers.Main) { connection.enter() }
            if (changes(field.node, before)) return settled(ActionMethod.INPUT_METHOD)
        }
        if (password(field)) return NOT_TAKEN
        return setText(field, before.substring(0, start) + "\n" + before.substring(end), start + 1, start + 1)
    }

    /**
     * Selects [start] to [end] of the field with [key] with one set-selection action. `EditText` and Compose answer false
     * when the selection is already there, which counts as done.
     */
    suspend fun select(key: String, start: Int, end: Int): ActionResult {
        val field = field(key) ?: return NOT_ON_SCREEN
        if (!field.node.isEnabled) return DISABLED
        val done = setSelection(field.node, start, end)
        if (selects(field.node, start, end)) return settled(ActionMethod.ACTION)
        return if (done) settled(ActionMethod.ACTION) else ActionResult.Failed("The text field refused the selection")
    }

    /**
     * Submits the field with [key] with its own editor action, which the input method sends as the app expects: search,
     * go, send, done, next or previous, and in a field with more lines only search, go or send. Without one, a field with
     * one line gets the Enter key, and a field with more lines has nothing to submit. When the input does not start, the
     * IME-enter action is the fallback, never on a field with more lines, where it moves the focus instead.
     */
    suspend fun submit(key: String): ActionResult {
        val field = field(key) ?: return NOT_ON_SCREEN
        if (!field.node.isEnabled) return DISABLED
        val connection = connect(field)
        if (connection != null) {
            val editor = withContext(Dispatchers.Main) { service.inputMethod?.currentInputEditorInfo }
            val action = (editor?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
            val multiLine = multiLine(field)
            // Android gives every field without an action of its own "next" or "done", also a field with more lines,
            // where Kestrel submits only with an action that sends something.
            if (action in (if (multiLine) SENDING_ACTIONS else REAL_ACTIONS)) {
                withContext(Dispatchers.Main) { connection.performEditorAction(action) }
                return settled(ActionMethod.INPUT_METHOD)
            }
            if (multiLine) return NO_ACTION
            withContext(Dispatchers.Main) { connection.enter() }
            return settled(ActionMethod.INPUT_METHOD)
        }
        if (field.node.isMultiLine) return NO_ACTION
        return if (field.node.performAction(IME_ENTER)) {
            settled(ActionMethod.ACTION)
        } else {
            ActionResult.Failed("The text field refused to submit")
        }
    }

    /**
     * Whether the field with [key] has the focus and its app started an input with the search action, as a search box
     * and a web page's search field do. It focuses nothing.
     */
    suspend fun searches(key: String): Boolean {
        val field = field(key) ?: return false
        val editor = withContext(Dispatchers.Main) { startedEditor(field) } ?: return false
        return editor.imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEARCH
    }

    /**
     * Whether the field with [key] takes a phone number, as a dialpad's number field does: its node's input type, or the
     * input that its app started for it, has the phone class. It focuses nothing.
     */
    suspend fun phone(key: String): Boolean {
        val field = field(key) ?: return false
        if (field.node.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_PHONE) return true
        val editor = withContext(Dispatchers.Main) { startedEditor(field) } ?: return false
        return editor.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_PHONE
    }

    /**
     * Cuts, copies or pastes in the field with [key]. These actions act only on the field that has the focus (research
     * r17), so Kestrel focuses it first. Cut and copy need a selection, because Chrome answers true for an empty one and
     * does nothing. A cut, and a paste at the cursor, are read back.
     */
    suspend fun clipboard(key: String, kind: ClipboardKind): ActionResult {
        val field = field(key) ?: return NOT_ON_SCREEN
        if (!field.node.isEnabled) return DISABLED
        if (!focus(field)) return ActionResult.Failed("The text field did not take the focus")
        val before = field.node.shownText().orEmpty()
        val (start, end) = selection(field.node, before)
        if (kind != ClipboardKind.PASTE && start == end) return SELECT_FIRST
        val action = when (kind) {
            ClipboardKind.CUT -> AccessibilityNodeInfo.ACTION_CUT
            ClipboardKind.COPY -> AccessibilityNodeInfo.ACTION_COPY
            ClipboardKind.PASTE -> AccessibilityNodeInfo.ACTION_PASTE
        }
        if (!field.node.performAction(action)) return ActionResult.Failed("The text field refused the action")
        // A cut, and a paste at the cursor, must change the text; a paste over a selection may put back the same text.
        val changing = kind == ClipboardKind.CUT || (kind == ClipboardKind.PASTE && start == end)
        if (changing && !changes(field.node, before)) return NOT_TAKEN
        return settled(ActionMethod.ACTION)
    }

    /**
     * Types [text] at the cursor of the field with [key] through the input method. Password fields need it, because
     * Kestrel cannot read their text to set it whole; the read-back checks only the length.
     */
    suspend fun typeAtCursor(key: String, text: String): ActionResult {
        val field = field(key) ?: return NOT_ON_SCREEN
        if (!field.node.isEnabled) return DISABLED
        val before = field.node.shownText().orEmpty()
        val (start, end) = selection(field.node, before)
        val connection = connect(field) ?: return NOT_CONNECTED
        withContext(Dispatchers.Main) { connection.commitText(text, 1, null) }
        return if (hasLength(field.node, before.length - (end - start) + text.length)) {
            settled(ActionMethod.INPUT_METHOD)
        } else {
            NOT_TAKEN
        }
    }

    /** Deletes the selection, or else [count] characters before the cursor, of the field with [key], through the input method. */
    suspend fun deleteBeforeCursor(key: String, count: Int): ActionResult {
        val field = field(key) ?: return NOT_ON_SCREEN
        if (!field.node.isEnabled) return DISABLED
        val before = field.node.shownText().orEmpty()
        val (start, end) = selection(field.node, before)
        val removed = if (end > start) end - start else minOf(count, start)
        if (removed == 0) return ActionResult.Failed("There is nothing before the cursor")
        val connection = connect(field) ?: return NOT_CONNECTED
        withContext(Dispatchers.Main) {
            if (end > start) connection.commitText("", 1, null) else connection.deleteSurroundingText(removed, 0)
        }
        return if (hasLength(field.node, before.length - removed)) settled(ActionMethod.INPUT_METHOD) else NOT_TAKEN
    }

    private class Field(val element: ScreenElement, val node: AccessibilityNodeInfo)

    /** The field with [key] in the current snapshot, with its live node. */
    private suspend fun field(key: String): Field? {
        val live = snapshots.current()
        val element = live.snapshot.element(key) ?: return null
        val node = live.node(key) ?: return null
        return if (node.refresh()) Field(element, node) else null
    }

    /**
     * Focuses [field] and waits until its app has started the input with Kestrel's input method. Null when the input does
     * not start within 2 s or the field does not have the focus then.
     */
    private suspend fun connect(field: Field): InputMethod.AccessibilityInputConnection? {
        val inputMethod = service.inputMethod ?: return null
        val packageName = field.element.packageName
        val before = withContext(Dispatchers.Main) { inputMethod.currentInputEditorInfo }
        val wasFocused = field.node.isFocused
        if (!wasFocused && !field.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            field.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        val connection = withContext(Dispatchers.Main) {
            withTimeoutOrNull(INPUT_START_MILLIS) {
                var ready: InputMethod.AccessibilityInputConnection? = null
                while (ready == null) {
                    val editor = inputMethod.currentInputEditorInfo
                    // A window without a text field also starts an input, but with the type 0 and no connection (P4).
                    val started = inputMethod.currentInputStarted && editor != null && editor.packageName == packageName &&
                        editor.inputType != InputType.TYPE_NULL && (wasFocused || editor !== before)
                    ready = if (started) inputMethod.currentInputConnection else null
                    if (ready == null) delay(POLL_MILLIS)
                }
                ready
            }
        } ?: return null
        return if (field.node.refresh() && field.node.isFocused) connection else null
    }

    /** Focuses [field] when it does not have the focus, and waits up to 2 s until it has. */
    private suspend fun focus(field: Field): Boolean {
        if (field.node.isFocused) return true
        if (!field.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) field.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return withTimeoutOrNull(INPUT_START_MILLIS) {
            while (!(field.node.refresh() && field.node.isFocused)) delay(POLL_MILLIS)
            true
        } ?: false
    }

    /**
     * Whether the field has more than one line: its node says so, or the input that its app started does, because Compose's
     * nodes always say one line (P4).
     */
    private suspend fun multiLine(field: Field): Boolean =
        field.node.isMultiLine || withContext(Dispatchers.Main) { startedEditor(field)?.let { multiLine(it) } } == true

    /**
     * Whether the field is a password field: its node says so, or the input that its app started does. A password that
     * "show password" shows loses the node's flag, but its input keeps the password's type.
     */
    private suspend fun password(field: Field): Boolean =
        field.node.isPassword || withContext(Dispatchers.Main) { startedEditor(field)?.let { isPassword(it) } } == true

    /** The input that the field's app started for it while it has the focus; null before that. */
    private fun startedEditor(field: Field): EditorInfo? {
        val inputMethod = service.inputMethod ?: return null
        if (!inputMethod.currentInputStarted || !field.node.isFocused) return null
        val editor = inputMethod.currentInputEditorInfo ?: return null
        if (editor.packageName != field.element.packageName || editor.inputType == InputType.TYPE_NULL) return null
        return editor
    }

    // Only the multi-line flag: the IME multi-line flag means that the field itself has one line (InputType).
    private fun multiLine(editor: EditorInfo): Boolean =
        editor.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            editor.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0

    // Not the visible-password variation: apps use it to turn suggestions off in fields that are no passwords.
    private fun isPassword(editor: EditorInfo): Boolean =
        editor.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION) in PASSWORD_TYPES

    /** Sets the whole [text] with the set-text action, then the selection, and reads it back. */
    private suspend fun setText(field: Field, text: String, selectionStart: Int, selectionEnd: Int): ActionResult {
        if (!field.node.refresh()) return NOT_ON_SCREEN
        // Compose reads the argument as a String, so it must be one (research r17).
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!field.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return NOT_TAKEN
        setSelection(field.node, selectionStart, selectionEnd)
        return if (shows(field.node, text, password(field))) settled(ActionMethod.ACTION) else NOT_TAKEN
    }

    private fun setSelection(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean {
        val arguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, minOf(start, end))
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, maxOf(start, end))
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
    }

    /** The node's selection, with a negative one as the cursor at the end, in order and within [text]. */
    private fun selection(node: AccessibilityNodeInfo, text: String): Pair<Int, Int> {
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start < 0 || end < 0) return text.length to text.length
        return minOf(start, end).coerceAtMost(text.length) to maxOf(start, end).coerceAtMost(text.length)
    }

    /** Whether the field shows [expected] within 1 s; for a password field, whether it has as many characters. */
    private suspend fun shows(node: AccessibilityNodeInfo, expected: String, password: Boolean): Boolean =
        if (password) hasLength(node, expected.length) else waitFor { node.refresh() && sameText(node.shownText().orEmpty(), expected) }

    // Web editors turn a space next to new words into a no-break space, or back, as they balance whitespace.
    private fun sameText(shown: String, expected: String): Boolean = shown.replace('\u00A0', ' ') == expected.replace('\u00A0', ' ')

    private suspend fun hasLength(node: AccessibilityNodeInfo, length: Int): Boolean =
        waitFor { node.refresh() && node.shownText().orEmpty().length == length }

    private suspend fun changes(node: AccessibilityNodeInfo, before: String): Boolean =
        waitFor { node.refresh() && node.shownText().orEmpty() != before }

    private suspend fun selects(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean =
        waitFor { node.refresh() && node.textSelectionStart == start && node.textSelectionEnd == end }

    /** Checks [condition] every 50 ms for up to 1 s: an edit shows within 300 to 400 ms (P4). */
    private suspend fun waitFor(condition: () -> Boolean): Boolean {
        repeat(READ_BACK_TRIES) {
            if (condition()) return true
            delay(POLL_MILLIS)
        }
        return condition()
    }

    private fun InputMethod.AccessibilityInputConnection.enter() {
        val now = SystemClock.uptimeMillis()
        sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
    }

    /** Waits until the screen has been quiet after the action, but never longer than 3 s (design spec, section 9.3). */
    private suspend fun settled(method: ActionMethod): ActionResult {
        tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = tracker.now())
        return ActionResult.Done(method)
    }

    companion object {
        /** The reasons that the text commands tell the owner (design spec, section 9.7). */
        val ONE_LINE = ActionResult.Failed("This field has only one line.")
        val NO_ACTION = ActionResult.Failed("This field has no action to submit.")
        val SELECT_FIRST = ActionResult.Failed("Select some text first.")
        val NOT_TAKEN = ActionResult.Failed("The text field didn't take the change.")

        private val NOT_ON_SCREEN = ActionResult.Failed("The text field is not on the screen")
        private val NOT_A_FIELD = ActionResult.Failed("The element is not a text field")
        private val DISABLED = ActionResult.Failed("The text field is disabled")
        private val CHANGED = ActionResult.Failed("The text changed before the edit")
        private val NOT_CONNECTED = ActionResult.Failed("The text field did not connect to Kestrel's input method")
        private val IME_ENTER = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        private val SENDING_ACTIONS = setOf(EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND)
        private val PASSWORD_TYPES = setOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        private val REAL_ACTIONS = setOf(
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_PREVIOUS,
        )
        private const val INPUT_START_MILLIS = 2_000L
        private const val READ_BACK_TRIES = 20
        private const val POLL_MILLIS = 50L
    }
}
```

- [ ] **Step 5: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin :testapp:assembleDebug`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` 88, `:app` 314, `:core:screen` 55, `:core:voice` 140, `:core:privacy` 48, `:core:safety` 2; unchanged by this task), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests and the test app compile.

- [ ] **Step 6: Run the editor's device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554 -Class TextEditorTest`
Expected: `BUILD SUCCESSFUL` and "Command tests: 7 run, 0 failed, 0 errors, 0 skipped." (`TextEditorTest` 7; the package now has 43 command tests), with no warning from the restores at the end. If a test fails on a line of the web page or of Compose that never shows the expected text, the field kind behaves differently from prototype P4 on this emulator: report it to the controller with the failure message, and do not change the tests.

- [ ] **Step 7: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 8: Commit**

```powershell
git add core/screen/src/main/kotlin/dev/kestrel/screen/act/TextEditor.kt testapp/src/main/kotlin/dev/kestrel/testapp/EditActivity.kt testapp/src/main/kotlin/dev/kestrel/testapp/ComposeEditActivity.kt testapp/src/main/kotlin/dev/kestrel/testapp/WebEditActivity.kt testapp/src/main/res/values/ids_text.xml testapp/src/main/AndroidManifest.xml app/src/androidTest/kotlin/dev/kestrel/app/commands/TextFieldTests.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/TextEditorTest.kt
git commit -m "feat(screen): edit text fields through Kestrel's input method with set-text as the fallback, and read every edit back" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: The text part, part 1: dictation, start and stop editing, "type", new lines, undo and redo

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/commands/TextCommands.kt` (the whole file, replacing plan 3A's stub)
- Create: `app/src/main/kotlin/dev/kestrel/app/commands/TextCommandTexts.kt`, `EditingState.kt`, `TextFields.kt`
- Test: `app/src/test/kotlin/dev/kestrel/app/commands/TextFieldsTest.kt` (new, JVM), `app/src/androidTest/kotlin/dev/kestrel/app/commands/TextCommandsTest.kt` (new, device)

**Interfaces:**
- Consumes: Tasks 1 and 2's `TextState.of`, `EditStep.Change`, `EditModel.between`, `Dictation.insert`, `DictationPart`, `EditHistory`; Task 4's `TextEditor` (`read`, `replace`, `newLine`, `typeAtCursor`, `ONE_LINE`, `NOT_TAKEN`), `FieldState`, `TextFieldTests` and the fixtures; plan 3A's `internal fun textCommands(environment: CommandEnvironment): CommandPart`, `CommandPart`, `CommandEnvironment` (`screen`, `targets`), `CommandConversation`, `ScreenCommands(environment).act`, `TargetKind.EDIT`, `ScreenTargets.candidates`, `names`, `chosen`, `still`, `CommandTexts.SCREEN_CHANGED`, `ScreenSession.snapshots` (`current()`, `latest()`), `numberBadges`, `grid`, `actions.tap`, `actions.global(GlobalAction.BACK)`, `actions.setText`, `Command.Bare(text, number)`, plan 2B's `Requests.read(transcript, spotted)` and `Request.Stop`, `ListeningTexts.PRIVATE_INPUT`, `AppGraph.commands`, `CommandTests`, `VoiceTests.waitUntil`.
- Produces (package `dev.kestrel.app.commands`): `internal class TextCommands(environment: CommandEnvironment) : CommandPart` behind `textCommands(environment)`, with `privateInput()`; `internal object TextCommandTexts` (`NO_FIELD`, `NO_TEXT_FIELD`, `FIELD_GONE`, `PASSWORD_FIELD`, `NOTHING_SELECTED`, `NOTHING_THERE`, `NOTHING_TO_UNDO`, `NOTHING_TO_REDO`, `KEYBOARD_NOT_SHOWING`, `NO_PASSWORD_TOGGLE`, `PASSWORD_SHOWING`, `NO_SEARCH_FIELD`, `PHONE_FIELD`, `NOT_A_TEXT_FIELD`, `fun textChanged(redo: Boolean)`, `fun notInText(phrase: String)`); `internal class EditingState { val history: EditHistory; fun dictating(snapshot: ScreenSnapshot): ScreenElement?; fun stop(field: ScreenElement); fun resume(); fun password(field: ScreenElement, password: Boolean, focused: ScreenElement? = field): Boolean; fun follow(focused: ScreenElement?); fun id(field: ScreenElement): String }`; `internal object TextFields { fun focused(snapshot): ScreenElement?; fun keyboardShows(snapshot): Boolean; fun toStart(snapshot): ScreenElement?; fun searchField(snapshot, focusedSearches: Boolean = false): ScreenElement?; fun searchButton(snapshot): ScreenElement?; fun passwordField(snapshot): ScreenElement?; fun passwordToggle(snapshot, field): ScreenElement?; fun namesOtherElement(view: ScreenView, target: Target): Boolean; fun phoneNumber(spoken: String): String? }`; the device test class `TextCommandsTest` with the line `    // Tasks 6 and 7 add their tests above this line.` before its last closing brace. Tasks 6 and 7 add their branches to `TextCommands.run` after the branch of `TextCommand.Redo`, and their functions before the function `apply`.

Design D8, D2 (the password field's neutral bar text) and D6 (the hand-back). Dictation is on while the current snapshot has a focused text field outside the keyboard's window (in the window that has the input focus, when one has it, so that a field in the other half of a split screen is not taken), unless the owner said "stop editing" for that field; a change of the focus ends "stop editing", and "start editing" does too. While dictation is on, a `Bare` is typed at the cursor, a bare number too, except while numbers or the grid show, when it goes to the screen part and taps. In a phone number field (`TextEditor.phone`, as a dialpad's number), dictation types only speech that reads as a phone number: digits, the spoken digits, "plus", "star" and "hash" and their marks, and dashes, without the spaces (`TextFields.phoneNumber`); it replaces the selection as it is and goes into the history. Any other speech is handed back to the other parts, as without a focused field, so that a bare "call" is never typed into the number, and "type X" says "This field takes only a phone number."; the editing commands work there as in any field. A lone "stop" or "cancel" is never typed: it may have silenced Kestrel or name a button (design D2), and "type stop" types the word. "Start editing X" taps the named field, with "Which one?" and "I can't find X" from the screen part. It acts only on an editable element: a number names the element of its badge whatever it is (plan 3A), such as the phone app's Call button, which plan 3C's guard cannot see because the text part runs first, so a number or a name that names anything but a text field makes the bar say "It isn't a text field." and taps nothing (`TextFields.namesOtherElement` before, and `editable` on the element itself in `act`); without a name it taps the focused field, the only one, or else the first from the top. "Stop editing" hides the keyboard with Back while it shows. "Type X" types X into the focused field also while dictation is off, through the same dictation rules, so that "type delete" types the word. A dictation goes in by parts: the first replaces the selection, each new line is the Enter key, and each later part goes in at the cursor that the field shows then. Kestrel records one history entry for the whole command, from the field's text before to what it shows afterwards, never for a password field. A field is a password field when `TextEditor` says so, or when Kestrel has seen it as one and no other text field has had the focus since that field had it (`EditingState.password`, by the field's app and key): a web page's "show password" turns the input into a plain text input, which neither the node nor the input calls a password any more, and the page's button may take the focus for a moment, so the record ends only when another text field has the focus; "undo" and "redo" apply the history's state through the same edit path. A password field gets only typing at its cursor, never repeated. A command that needs a text field returns null while no field has the focus, so that the executor tries the words as a bare label: "delete" then taps a "Delete" button, and conversation that sounds like a command does nothing. `privateInput()` reads only the newest snapshot that was built, and Kestrel's own record of the password field.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/dev/kestrel/app/commands/TextFieldsTest.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.Target
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import dev.kestrel.screen.overlay.Badge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which text field the text commands act on, and when dictation is on (design spec, section 9.7). */
class TextFieldsTest {
    @Test
    fun startEditingNamesOnlyATextField() {
        val notes = field("notes", "Notes", 300)
        val call = button("call", "Call", IntRect(400, 1400, 680, 1600))
        val shown = snapshot(notes, call)
        val numbers = ScreenView(shown, listOf(Badge(1, "notes", notes.bounds), Badge(2, "call", call.bounds)), grid = null)
        val plain = ScreenView(shown, badges = emptyList(), grid = null)

        // A number names the element of its badge, whatever it is.
        assertTrue(TextFields.namesOtherElement(numbers, Target.Number(2)))
        assertFalse(TextFields.namesOtherElement(numbers, Target.Number(1)))
        assertTrue(TextFields.namesOtherElement(plain, Target.Label("call")))
        assertFalse(TextFields.namesOtherElement(plain, Target.Label("notes")))
        // A name that nothing has gets "I can't find X", as for every command.
        assertFalse(TextFields.namesOtherElement(plain, Target.Label("subject")))
    }

    @Test
    fun onlySpeechThatReadsAsAPhoneNumberIsOne() {
        assertEquals("+447911123456", TextFields.phoneNumber("+44 7911 123456"))
        assertEquals("555-0100", TextFields.phoneNumber("555-0100."))
        assertEquals("+4479", TextFields.phoneNumber("Plus four four seven nine"))
        assertEquals("*#06#", TextFields.phoneNumber("star hash zero six hash"))
        assertNull(TextFields.phoneNumber("call"))
        assertNull(TextFields.phoneNumber("call 555 0100"))
        assertNull(TextFields.phoneNumber("five pm"))
        assertNull(TextFields.phoneNumber("plus"))
        assertNull(TextFields.phoneNumber(""))
    }

    private val app = WindowSummary(1, WindowKind.APPLICATION, bounds = IntRect(0, 0, 1080, 2400), layer = 0)
    private val keyboard = WindowSummary(2, WindowKind.INPUT_METHOD, bounds = IntRect(0, 1600, 1080, 2400), layer = 2)

    private fun field(key: String, hint: String?, top: Int, focused: Boolean = false, password: Boolean = false, window: Int = 1) =
        ScreenElement(
            key = key,
            windowId = window,
            packageName = "dev.app",
            className = "android.widget.EditText",
            hint = hint,
            label = hint,
            bounds = IntRect(0, top, 800, top + 100),
            editable = true,
            focused = focused,
            password = password,
        )

    private fun button(key: String, label: String?, bounds: IntRect, checkable: Boolean = false, viewId: String? = null) =
        ScreenElement(
            key = key,
            windowId = 1,
            packageName = "dev.app",
            className = "android.widget.Button",
            label = label,
            bounds = bounds,
            clickable = true,
            checkable = checkable,
            viewId = viewId,
        )

    private fun snapshot(vararg elements: ScreenElement, keyboardShows: Boolean = false) =
        ScreenSnapshot(0, if (keyboardShows) listOf(keyboard, app) else listOf(app), elements.toList())

    @Test
    fun theFocusedFieldOutsideTheKeyboardIsTheOneThatCommandsActOn() {
        val notes = field("notes", "Notes", 300, focused = true)
        val keyboardField = field("emoji", "Search emoji", 1700, focused = true, window = 2)

        assertEquals(notes, TextFields.focused(snapshot(keyboardField, notes, keyboardShows = true)))
        assertNull(TextFields.focused(snapshot(field("notes", "Notes", 300))))
        assertTrue(TextFields.keyboardShows(snapshot(notes, keyboardShows = true)))
        assertFalse(TextFields.keyboardShows(snapshot(notes)))
    }

    @Test
    fun startEditingWithoutANameTakesTheFocusedTheOnlyOrTheFirstFromTheTop() {
        val low = field("low", "Notes", 900)
        val high = field("high", "Subject", 300)

        assertEquals(high, TextFields.toStart(snapshot(low, high)))
        assertEquals(low, TextFields.toStart(snapshot(low)))
        assertEquals(low.copy(focused = true), TextFields.toStart(snapshot(low.copy(focused = true), high)))
        assertNull(TextFields.toStart(snapshot(button("send", "Send", IntRect(0, 0, 200, 100)))))
    }

    @Test
    fun searchUsesASearchFieldOrASearchButtonAndNeverAPasswordOrADraft() {
        val notes = field("notes", "Notes", 300)
        val search = field("search", "Search mail", 100)
        val icon = button("icon", null, IntRect(900, 100, 1000, 200), viewId = "dev.app:id/search_button")
        val find = button("find", "Search", IntRect(0, 0, 100, 100))

        assertEquals(search, TextFields.searchField(snapshot(notes, search)))
        assertEquals(search.copy(focused = true), TextFields.searchField(snapshot(notes, search.copy(focused = true))))
        // A focused field that is no search field, such as a message, keeps its draft, unless its input searches.
        assertEquals(search, TextFields.searchField(snapshot(notes.copy(focused = true), search)))
        assertEquals(notes.copy(focused = true), TextFields.searchField(snapshot(notes.copy(focused = true)), focusedSearches = true))
        assertEquals(search, TextFields.searchField(snapshot(field("pin", "Search PIN", 500, focused = true, password = true), search)))
        assertNull(TextFields.searchField(snapshot(notes)))
        // Android reports SearchView's box as an AutoCompleteTextView; its view ID names it.
        val box = field("box", null, 100, focused = true).copy(viewId = "android:id/search_src_text")
        assertEquals(box, TextFields.searchField(snapshot(box)))
        assertEquals(icon, TextFields.searchButton(snapshot(notes, icon)))
        assertEquals(find, TextFields.searchButton(snapshot(find)))
        assertNull(TextFields.searchButton(snapshot(button("clear", "Clear search history", IntRect(0, 0, 100, 100)))))
    }

    @Test
    fun aFieldKeepsItsFocusInAWindowWithoutTheInputFocusButIsNotTheFocusedOne() {
        val other = WindowSummary(3, WindowKind.APPLICATION, bounds = IntRect(0, 1200, 1080, 2400), layer = 1, focused = true)
        val top = field("top", "Top", 300, focused = true)
        val bottom = field("bottom", "Bottom", 1300, focused = true, window = 3)

        assertEquals(bottom, TextFields.focused(ScreenSnapshot(0, listOf(app, other), listOf(top, bottom))))
        assertNull(TextFields.focused(ScreenSnapshot(0, listOf(app, other), listOf(top))))
        assertEquals(top, TextFields.focused(ScreenSnapshot(0, listOf(app.copy(focused = true), other.copy(focused = false)), listOf(top, bottom))))
    }

    @Test
    fun showPasswordFindsANamedToggleOrALoneEyeIcon() {
        val password = field("password", "Password", 500, password = true)
        val named = button("show", "Show password", IntRect(0, 700, 400, 800), checkable = true)
        val far = button("far", "Show password", IntRect(0, 2000, 400, 2100))
        val eye = button("eye", null, IntRect(810, 500, 850, 600))
        val send = button("send", "Send", IntRect(0, 900, 200, 1000))

        assertEquals(password, TextFields.passwordField(snapshot(field("user", "User", 300), password)))
        assertEquals(named, TextFields.passwordToggle(snapshot(password, far, named, send), password))
        assertEquals(eye, TextFields.passwordToggle(snapshot(password, eye, send), password))
        // Two unnamed icons next to the field: Kestrel cannot tell which one shows the password.
        val other = button("other", null, IntRect(860, 500, 900, 600))
        assertNull(TextFields.passwordToggle(snapshot(password, eye, other, send), password))
    }

    @Test
    fun aFieldThatWasAPasswordFieldStaysOneUntilAnotherFieldHasTheFocus() {
        val editing = EditingState()
        val shown = field("password", "Password", 300, focused = true)
        val user = field("user", "User", 100, focused = true)

        assertFalse(editing.password(shown, password = false))
        assertTrue(editing.password(shown.copy(password = true), password = true))
        // "Show password" made it a plain field, and the page's button may hold the focus for a while.
        assertTrue(editing.password(shown, password = false))
        editing.follow(null)
        assertTrue(editing.password(shown, password = false))
        // Another text field takes the focus: the record goes.
        assertFalse(editing.password(user, password = false))
        assertFalse(editing.password(shown, password = false))
        // Seen without the focus, as by "show password", the field stays one until it has had the focus.
        assertTrue(editing.password(shown, password = true, focused = user))
        assertFalse(editing.password(user, password = false))
        assertTrue(editing.password(shown, password = false))
    }

    @Test
    fun dictationIsOnInTheFocusedFieldUntilStopEditingForItAndAChangeOfFocusEndsThat() {
        val editing = EditingState()
        val notes = field("notes", "Notes", 300, focused = true)
        val subject = field("subject", "Subject", 100, focused = true)

        assertEquals(notes, editing.dictating(snapshot(notes)))
        editing.stop(notes)
        assertNull(editing.dictating(snapshot(notes)))
        // Another field takes the focus: dictation is on there, and stays on when the focus comes back.
        assertEquals(subject, editing.dictating(snapshot(subject)))
        assertEquals(notes, editing.dictating(snapshot(notes)))
        editing.stop(notes)
        editing.resume()
        assertEquals(notes, editing.dictating(snapshot(notes)))
        assertNull(editing.dictating(snapshot(field("notes", "Notes", 300))))
        assertEquals("dev.app/notes", editing.id(notes))
    }
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/commands/TextCommandsTest.kt`:

```kotlin
package dev.kestrel.app.commands

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.commands.TextFieldTests.awaitBar
import dev.kestrel.app.commands.TextFieldTests.awaitFocus
import dev.kestrel.app.commands.TextFieldTests.awaitLine
import dev.kestrel.app.commands.TextFieldTests.field
import dev.kestrel.app.listen.ListeningTexts
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.act.TextEditor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dictation and the text commands (design spec, section 9.7, and Appendix A), through the same path as speech: each
 * text goes to Kestrel's Orchestrator as a transcript without the name. Like every command test, they run only through
 * scripts\command-test.ps1.
 */
@RunWith(AndroidJUnit4::class)
class TextCommandsTest {
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    @Before
    fun startListening() = CommandTests.start()

    @After
    fun stopListening() = CommandTests.stop()

    /** Says [text] and checks that its command acted. */
    private fun done(text: String) = assertEquals(text, Outcome.DONE, CommandTests.say(text).outcome)

    private fun editNotes() {
        screen.open("EditActivity", "Edit fixture")
        done("start editing Notes")
        screen.awaitFocus("text_notes")
    }

    @Test
    fun dictationTypesWhatIsSaidWithSpokenPunctuationAndSpelling() {
        editNotes()

        val report = CommandTests.say("hello comma how are you question mark")

        assertEquals(Outcome.DONE, report.outcome)
        assertEquals("Bare", report.kind)
        screen.awaitLine("Notes: \"Hello, how are you?\" 19-19")
        done("capital p one two three")
        screen.awaitLine("Notes: \"Hello, how are you? P123\" 24-24")

        // A phone number field takes only a phone number; other speech goes back to the other parts, and editing works.
        done("start editing Phone")
        screen.awaitFocus("text_phone")
        done("plus four four seven nine")
        screen.awaitLine("Phone: \"+4479\"")
        assertEquals(Outcome.IGNORED, CommandTests.say("hello there").outcome)
        done("undo")
        screen.awaitLine("Phone: \"\"")
    }

    @Test
    fun startEditingTakesTheFirstFieldAndStopEditingEndsDictation() {
        screen.open("EditActivity", "Edit fixture")

        done("start editing")
        screen.awaitFocus("text_search")
        done("falcon")
        screen.awaitLine("Search: \"Falcon\" 6-6")
        // "Stop" may have silenced Kestrel, so it is never typed; the screen has no button with that name.
        assertEquals(Outcome.IGNORED, CommandTests.say("stop").outcome)
        done("stop editing")
        // Dictation is off, so bare speech is ignored; "type" still types, also a word that is a command.
        assertEquals(Outcome.IGNORED, CommandTests.say("more words").outcome)
        done("type delete")
        screen.awaitLine("Search: \"Falcon delete\" 13-13")

        // "Start editing" with a number taps only a text field, never a button such as the phone app's Call.
        val box = runBlocking { screen.awaitElement { it.checkable } }
        done("show numbers")
        check(VoiceTests.waitUntil(5_000) { screen.session.numberBadges.any { it.key == box.key } }) { "The check box has no number" }
        val number = screen.session.numberBadges.first { it.key == box.key }.number
        assertEquals(Outcome.FAILED, CommandTests.say("start editing $number").outcome)
        screen.awaitBar(TextCommandTexts.NOT_A_TEXT_FIELD)
        done("hide numbers")
        assertFalse(runBlocking { screen.awaitElement { it.checkable } }.checked)
    }

    @Test
    fun aNewLineBreaksAFieldWithMoreLinesOnly() {
        editNotes()

        done("first line")
        done("new line")
        done("second line")
        screen.awaitLine("Notes: \"First line\\nsecond line\" 22-22")
        done("start editing Search")
        screen.awaitFocus("text_search")
        assertEquals(Outcome.FAILED, CommandTests.say("new line").outcome)
        screen.awaitBar(TextEditor.ONE_LINE.reason)
    }

    @Test
    fun undoAndRedoUseKestrelsOwnHistory() {
        editNotes()
        val notes = screen.field("text_notes")

        done("apples")
        done("pears")
        screen.awaitLine("Notes: \"Apples pears\" 12-12")
        done("undo")
        screen.awaitLine("Notes: \"Apples\" 6-6")
        done("undo that")
        screen.awaitLine("Notes: \"\" 0-0")
        done("redo")
        screen.awaitLine("Notes: \"Apples\" 6-6")
        // The text changes without Kestrel, so its history no longer fits the field.
        runBlocking { screen.session.actions.setText(notes.key, "changed") }
        screen.awaitLine("Notes: \"changed\" 7-7")
        assertEquals(Outcome.FAILED, CommandTests.say("undo").outcome)
        CommandTests.awaitSaid(TextCommandTexts.textChanged(redo = false))
    }

    @Test
    fun aPasswordFieldHidesTheTranscriptAndSpellsWhatIsDictated() {
        screen.open("EditActivity", "Edit fixture")
        done("start editing Password")
        screen.awaitFocus("text_password")
        check(VoiceTests.waitUntil(5_000) { CommandTests.graph.commands.privateInput() }) { "Kestrel did not see the password field" }

        CommandTests.send("capital a bee three")

        screen.awaitBar(ListeningTexts.PRIVATE_INPUT)
        assertEquals(Outcome.DONE, CommandTests.next().outcome)
        screen.awaitLine("Password: 3 characters")
    }

    @Test
    fun dictationWorksInComposeAndOnTheWeb() {
        screen.open("ComposeEditActivity", "Compose edit fixture")
        done("start editing Compose notes")
        screen.awaitFocus("compose_notes")
        done("see you at five period")
        screen.awaitLine("Compose notes: \"See you at five.\" 16-16")

        screen.open("WebEditActivity", "Web edit fixture")
        done("start editing Web notes")
        screen.awaitFocus("web_notes")
        done("the meeting is on Monday exclamation mark")
        screen.awaitLine("Web notes: \"The meeting is on Monday!\" 25-25")
    }

    @Test
    fun aWebPasswordThatShowPasswordShowsIsStillSpelled() {
        screen.open("WebEditActivity", "Web edit fixture")
        done("start editing Web password")
        screen.awaitFocus("web_password")
        done("a b")
        screen.awaitLine("Web password: 2 characters")

        done("show password")
        screen.awaitLine("Web password shown")
        done("start editing Web password")
        screen.awaitFocus("web_password")
        // The page made it a plain text field, but Kestrel remembers it: "three" is spelled "3", not typed as a word.
        done("three")
        screen.awaitLine("Web password: 3 characters")
    }

    // Tasks 6 and 7 add their tests above this line.
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 --continue :app:testDebugUnitTest --tests dev.kestrel.app.commands.TextFieldsTest :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, because the tests do not compile: "Unresolved reference 'TextFields'" and "Unresolved reference 'EditingState'" in the JVM tests, and "Unresolved reference 'TextCommandTexts'" in the device tests.

- [ ] **Step 3: Add the texts, the editing state and the field choices**

Create `app/src/main/kotlin/dev/kestrel/app/commands/TextCommandTexts.kt`:

```kotlin
package dev.kestrel.app.commands

/**
 * What the text commands say and show (design spec, section 9.7). Kestrel speaks only what the owner must act on; the
 * other texts go to the feedback bar. The reasons of `TextEditor` ("This field has only one line." and the others) are
 * fixed texts of the screen layer.
 */
internal object TextCommandTexts {
    const val NO_FIELD = "No text field has the focus."
    const val NO_TEXT_FIELD = "I can't find a text field here."
    const val FIELD_GONE = "The text field is no longer on the screen."
    const val PASSWORD_FIELD = "I can't edit a password field that way."
    const val NOTHING_SELECTED = "Nothing is selected."
    const val NOTHING_THERE = "There's no text there."
    const val NOTHING_TO_UNDO = "There's nothing to undo."
    const val NOTHING_TO_REDO = "There's nothing to redo."
    const val KEYBOARD_NOT_SHOWING = "The keyboard is not showing."
    const val NO_PASSWORD_TOGGLE = "I can't find a way to show the password here."
    const val PASSWORD_SHOWING = "The password is showing already."
    const val NO_SEARCH_FIELD = "I can't find a search field here."
    const val PHONE_FIELD = "This field takes only a phone number."
    const val NOT_A_TEXT_FIELD = "It isn't a text field."

    /** For "undo" and "redo" when the field no longer shows the text of Kestrel's last edit. */
    fun textChanged(redo: Boolean) =
        "The text changed since my last edit, so I can't ${if (redo) "redo" else "undo"} it."

    /** [phrase] is the owner's words as the recogniser wrote them. Kestrel says this text and never logs it. */
    fun notInText(phrase: String) = "I can't find $phrase in the text."
}
```

Create `app/src/main/kotlin/dev/kestrel/app/commands/TextFields.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.Target
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Strictness
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import kotlin.math.abs

/**
 * Finds the text fields that the text commands act on (design spec, section 9.7). The functions are pure, so that JVM
 * tests check them on made-up snapshots. The keyboard's window never counts.
 */
internal object TextFields {
    /**
     * The text field that has the focus, outside the keyboard's window, in the window that has the input focus when a
     * window has it. WebView marks its focused field this way too (P4).
     */
    fun focused(snapshot: ScreenSnapshot): ScreenElement? {
        val keyboard = keyboardWindows(snapshot)
        val fields = snapshot.elements.filter { it.editable && it.focused && it.enabled && it.windowId !in keyboard }
        // A field keeps its focus while another window has the input focus, as in split screen.
        val input = snapshot.windows.firstOrNull { it.focused }?.id ?: return fields.firstOrNull()
        return fields.firstOrNull { it.windowId == input }
    }

    /** Whether the keyboard shows. */
    fun keyboardShows(snapshot: ScreenSnapshot): Boolean = snapshot.windows.any { it.kind == WindowKind.INPUT_METHOD }

    /** "Start editing" without a name: the focused field, the only one, or else the first from the top. */
    fun toStart(snapshot: ScreenSnapshot): ScreenElement? {
        focused(snapshot)?.let { return it }
        val fields = ScreenTargets.candidates(snapshot, TargetKind.EDIT)
        return fields.singleOrNull() ?: fields.minWithOrNull(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    /**
     * "Search for X": the focused field when it is a search field, else the first search field; never a password field,
     * so that a password or a message draft is never replaced. [focusedSearches] is true when the focused field's input
     * has the search action (`TextEditor.searches`).
     */
    fun searchField(snapshot: ScreenSnapshot, focusedSearches: Boolean = false): ScreenElement? =
        focused(snapshot)?.takeIf { !it.password && (focusedSearches || isSearchField(snapshot, it)) }
            ?: ScreenTargets.candidates(snapshot, TargetKind.EDIT).firstOrNull { !it.password && isSearchField(snapshot, it) }

    /**
     * A clickable element whose name starts with "search", such as "Search" or "Search in mail" but not "Clear search
     * history", or with "search" in its view ID, that opens a search field.
     */
    fun searchButton(snapshot: ScreenSnapshot): ScreenElement? =
        ScreenTargets.candidates(snapshot, TargetKind.BARE).firstOrNull {
            !it.editable && it.clickable && (opensSearch(snapshot, it) || idSaysSearch(it))
        }

    /** The password field of "show password": the focused one, else the first on the screen. */
    fun passwordField(snapshot: ScreenSnapshot): ScreenElement? =
        focused(snapshot)?.takeIf { it.password } ?: ScreenTargets.candidates(snapshot, TargetKind.EDIT).firstOrNull { it.password }

    /**
     * What shows the password of [field]: the clickable or checkable element in its window, nearest to it, whose names
     * say "show", "reveal" or "visibility" with "password"; or else the only unnamed toggle at the field's right end, as
     * an eye icon is.
     */
    fun passwordToggle(snapshot: ScreenSnapshot, field: ScreenElement): ScreenElement? {
        val near = snapshot.elements.filter {
            it.windowId == field.windowId && it.key != field.key && it.enabled && !it.editable && (it.clickable || it.checkable)
        }
        val named = near.filter { element ->
            ScreenTargets.names(snapshot, element).any { name ->
                val words = Normalizer.words(name)
                "password" in words && words.any { it in SHOW_WORDS }
            }
        }
        named.minByOrNull { distance(it.bounds, field.bounds) }?.let { return it }
        val bounds = field.bounds
        return near.filter { element ->
            val own = listOfNotNull(element.label, element.contentDescription, element.text).filter { it.isNotBlank() }
            val icon = element.bounds
            own.isEmpty() && icon.top < bounds.bottom && icon.bottom > bounds.top &&
                icon.left >= bounds.centerX && icon.left <= bounds.right + bounds.height
        }.singleOrNull()
    }

    /** A search field by its names (its hint among them) or its view ID, as `SearchView`'s box `search_src_text`. */
    /**
     * Whether [target] names an element in [view] that is no text field, so that "start editing" taps nothing: a number
     * names the element of its badge, whatever it is (plan 3A), such as the phone app's Call button, and a name that no
     * text field has may still be a button's. A name that nothing has is left to the usual "I can't find X".
     */
    fun namesOtherElement(view: ScreenView, target: Target): Boolean {
        val field = ScreenTargets.decide(view, target, TargetKind.EDIT, Strictness.NORMAL, cells = false)
        return when (target) {
            is Target.Number -> field is Resolution.One && view.snapshot.element(field.element.key)?.editable != true
            is Target.Label -> field == Resolution.NotFound &&
                ScreenTargets.decide(view, target, TargetKind.TAP, Strictness.NORMAL, cells = false).let {
                    it is Resolution.One || it is Resolution.Several
                }
        }
    }

    /**
     * [spoken] as a phone number, for a field that takes only one: digits, the spoken digits, "plus", "star" and "hash"
     * and their marks, and dashes, with the spaces dropped; null when any word is something else, so that "call" never
     * goes into a dialpad's number.
     */
    fun phoneNumber(spoken: String): String? {
        val number = StringBuilder()
        for (word in spoken.trim().trimEnd('.', '?', '!').lowercase().split(Regex("\\s+"))) {
            val bare = word.trimEnd(',')
            when {
                bare.isNotEmpty() && bare.all { it in '0'..'9' || it in PHONE_MARKS } -> number.append(bare)
                bare in PHONE_WORDS -> number.append(PHONE_WORDS.getValue(bare))
                else -> return null
            }
        }
        return number.toString().takeIf { text -> text.any { it in '0'..'9' || it == '*' || it == '#' } }
    }

    private fun isSearchField(snapshot: ScreenSnapshot, element: ScreenElement): Boolean =
        ScreenTargets.names(snapshot, element).any { "search" in Normalizer.words(it) } || idSaysSearch(element)

    private fun opensSearch(snapshot: ScreenSnapshot, element: ScreenElement): Boolean =
        ScreenTargets.names(snapshot, element).any { Normalizer.words(it).firstOrNull() == "search" }

    private fun idSaysSearch(element: ScreenElement): Boolean =
        element.viewId?.substringAfter(":id/")?.contains("search", ignoreCase = true) == true

    private fun keyboardWindows(snapshot: ScreenSnapshot): Set<Int> =
        snapshot.windows.filter { it.kind == WindowKind.INPUT_METHOD }.mapTo(HashSet()) { it.id }

    private fun distance(a: IntRect, b: IntRect): Int = abs(a.centerX - b.centerX) + abs(a.centerY - b.centerY)

    private val SHOW_WORDS = setOf("show", "reveal", "visibility", "visible")

    private const val PHONE_MARKS = "+*#-"

    private val PHONE_WORDS = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6",
        "seven" to "7", "eight" to "8", "nine" to "9", "plus" to "+", "star" to "*", "hash" to "#",
    )
}
```

Create `app/src/main/kotlin/dev/kestrel/app/commands/EditingState.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.edit.EditHistory
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot

/**
 * When dictation is on (design spec, section 9.7): while a text field has the focus, unless the owner said "stop editing"
 * for that field. A change of the focus ends "stop editing", and so does "start editing". It also keeps Kestrel's own
 * history of its edits for "undo" and "redo", and which field it last saw as a password field. It holds the keys of
 * fields, never their text.
 */
internal class EditingState {
    private class Field(val packageName: String?, val key: String)

    @Volatile
    private var stopped: Field? = null

    val history = EditHistory()

    /** The field that dictation types into, or null while dictation is off. */
    fun dictating(snapshot: ScreenSnapshot): ScreenElement? {
        val field = TextFields.focused(snapshot)
        val stop = stopped
        if (stop != null && (field == null || field.key != stop.key || field.packageName != stop.packageName)) stopped = null
        return field?.takeIf { stopped == null }
    }

    /** "Stop editing" for [field]. */
    fun stop(field: ScreenElement) {
        stopped = Field(field.packageName, field.key)
    }

    /** "Start editing". */
    fun resume() {
        stopped = null
    }

    // The field that Kestrel last saw as a password field, and whether it has had the focus since. "Show password" clears
    // the node's flag, and a web page turns the input into a plain one, but the field still holds the password.
    private var passwordField: String? = null
    private var passwordFocused = false

    /**
     * Whether [field] holds a password: [password] says so now, or Kestrel saw it as a password field before and no other
     * text field has had the focus since that field had it. [focused] is the text field that has the focus now.
     */
    @Synchronized
    fun password(field: ScreenElement, password: Boolean, focused: ScreenElement? = field): Boolean {
        follow(focused)
        val id = id(field)
        if (password) {
            passwordField = id
            passwordFocused = focused?.let(::id) == id
        }
        return password || id == passwordField
    }

    /**
     * Forgets the password field once it has had the focus and another text field has it now. While no text field has
     * the focus, as when the page's own "show password" button took it, the record stays.
     */
    @Synchronized
    fun follow(focused: ScreenElement?) {
        val remembered = passwordField ?: return
        val now = focused?.let(::id) ?: return
        if (now == remembered) {
            passwordFocused = true
        } else if (passwordFocused) {
            passwordField = null
            passwordFocused = false
        }
    }

    /** The name of [field] in the history: its app and its key. */
    fun id(field: ScreenElement): String = "${field.packageName}/${field.key}"
}
```

- [ ] **Step 4: Add the text part**

Replace the whole file `app/src/main/kotlin/dev/kestrel/app/commands/TextCommands.kt` with:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.Target
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.edit.Dictation
import dev.kestrel.commands.edit.DictationPart
import dev.kestrel.commands.edit.EditModel
import dev.kestrel.commands.edit.EditStep
import dev.kestrel.commands.edit.TextState
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.FieldState
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.act.TextEditor
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun textCommands(environment: CommandEnvironment): CommandPart = TextCommands(environment)

/**
 * The text commands (design spec, section 9.7, and Appendix A): dictation into the focused field, "type X", new lines,
 * Kestrel's own undo and redo, the editing commands, the clipboard, the keyboard, "show password" and "search for X".
 * Every edit goes through `ScreenSession.text`, the input method first and set-text as the fallback, and is read back.
 *
 * A command that needs a text field returns null while no field has the focus, so that the executor tries it as a bare
 * label: "delete" then taps a "Delete" button, and conversation that sounds like a command does nothing.
 */
internal class TextCommands(private val environment: CommandEnvironment) : CommandPart {
    private val editing = EditingState()
    private val screens = ScreenCommands(environment)

    override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult? {
        val screen = environment.screen() ?: return null
        return when (command) {
            is Command.Bare -> dictate(screen, command)
            is TextCommand.StartEditing -> startEditing(screen, command.target, conversation)
            TextCommand.StopEditing -> stopEditing(screen)
            is TextCommand.Type -> inField(screen) { type(screen, it, command.text, startsUtterance = false) }
            TextCommand.NewLine -> inField(screen) { newLine(screen, it) }
            TextCommand.Undo -> inField(screen) { undo(screen, it, redo = false) }
            TextCommand.Redo -> inField(screen) { undo(screen, it, redo = true) }
            else -> null
        }
    }

    // The controller's thread asks before it shows each transcript, so this reads only the newest snapshot that was
    // built, never the screen (design D2).
    override fun privateInput(): Boolean {
        val snapshot = environment.screen()?.snapshots?.latest()?.snapshot ?: return false
        val field = TextFields.focused(snapshot) ?: return false
        return editing.password(field, field.password)
    }

    /** Runs [block] on the focused text field, or returns null without one. */
    private suspend fun inField(screen: ScreenSession, block: suspend (ScreenElement) -> CommandResult): CommandResult? {
        val field = TextFields.focused(screen.snapshots.current().snapshot) ?: return null
        return block(field)
    }

    /**
     * Speech that no rule takes is typed at the cursor while dictation is on. A bare number goes to the screen part while
     * numbers or the grid show, where it taps. A phone number field, such as a dialpad's, takes only speech that reads as
     * a phone number; any other speech goes back to the other parts, so that "call" is never typed into the number.
     */
    private suspend fun dictate(screen: ScreenSession, command: Command.Bare): CommandResult? {
        val field = editing.dictating(screen.snapshots.current().snapshot) ?: return null
        if (command.number != null && withContext(Dispatchers.Main) { screen.numberBadges.isNotEmpty() || screen.grid != null }) return null
        // A lone "stop" or "cancel" may have silenced Kestrel or name a button (design D2); "type stop" types the word.
        if (Normalizer.words(command.text).singleOrNull() in setOf("stop", "cancel")) return null
        if (TextFields.phoneNumber(command.text) == null && screen.text.phone(field.key)) return null
        return type(screen, field, command.text, startsUtterance = true)
    }

    /** "Type X" and dictation: spoken punctuation, spelling, and each new line through the Enter key. */
    private suspend fun type(screen: ScreenSession, field: ScreenElement, spoken: String, startsUtterance: Boolean): CommandResult {
        val state = screen.text.read(field.key) ?: return gone()
        if (editing.password(field, state.password)) {
            val part = Dictation.insert(state.toTextState(), spoken, password = true).single() as DictationPart.Text
            if (part.change.replacement.isEmpty()) return CommandResult(Outcome.IGNORED)
            // A password edit is never repeated and never in the history.
            return when (val result = screen.text.typeAtCursor(field.key, part.change.replacement)) {
                is ActionResult.Done -> CommandResult(Outcome.DONE)
                is ActionResult.Failed -> failed(result)
            }
        }
        if (screen.text.phone(field.key)) return typeNumber(screen, field, spoken, state)
        val before = state.toTextState()
        val parts = Dictation.insert(before, spoken, password = false, startsUtterance = startsUtterance)
        if (parts.isEmpty()) return CommandResult(Outcome.IGNORED)
        for ((index, part) in parts.withIndex()) {
            val result = when (part) {
                is DictationPart.Text -> if (index == 0) apply(screen, field, part.change) else atCursor(screen, field, part.change.replacement)
                DictationPart.NewLine -> screen.text.newLine(field.key)
            }
            if (result is ActionResult.Failed) {
                // What went in before the failure can still be undone.
                record(screen, field, before)
                return failed(result)
            }
        }
        record(screen, field, before)
        return CommandResult(Outcome.DONE, repeatable = true)
    }

    /** A phone number replaces the selection as it is, without the spaces and capitals of dictation. */
    private suspend fun typeNumber(screen: ScreenSession, field: ScreenElement, spoken: String, state: FieldState): CommandResult {
        val number = TextFields.phoneNumber(spoken) ?: return CommandResult(Outcome.FAILED, say = TextCommandTexts.PHONE_FIELD)
        val end = state.selectionStart + number.length
        val result = screen.text.replace(field.key, state.selectionStart, state.selectionEnd, number, end, end)
        if (result is ActionResult.Failed) return failed(result)
        record(screen, field, state.toTextState())
        return CommandResult(Outcome.DONE, repeatable = true)
    }

    /**
     * "Start editing" taps the named field, or the focused one, the only one, or else the first from the top. A name or
     * a number that names anything but a text field taps nothing ("It isn't a text field."): a number names any element,
     * such as the phone app's Call button, and the phone part's guard never sees this command.
     */
    private suspend fun startEditing(screen: ScreenSession, target: Target?, conversation: CommandConversation): CommandResult {
        if (target != null) {
            val view = environment.targets.view()
            if (view != null && TextFields.namesOtherElement(view, target)) {
                return CommandResult(Outcome.FAILED, show = TextCommandTexts.NOT_A_TEXT_FIELD)
            }
            val result = screens.act(screen, target, TargetKind.EDIT, conversation) {
                // Checked again on the element itself, because the screen may have changed since.
                if (it.editable) screen.actions.tap(it.key) else ActionResult.Failed(TextCommandTexts.NOT_A_TEXT_FIELD)
            }
            if (result.outcome == Outcome.DONE) editing.resume()
            return result
        }
        val field = TextFields.toStart(screen.snapshots.current().snapshot)
            ?: return CommandResult(Outcome.FAILED, say = TextCommandTexts.NO_TEXT_FIELD)
        val element = environment.targets.still(ScreenTargets.chosen(field))
            ?: return CommandResult(Outcome.FAILED, say = CommandTexts.SCREEN_CHANGED)
        return when (val result = screen.actions.tap(element.key)) {
            is ActionResult.Done -> {
                editing.resume()
                CommandResult(Outcome.DONE, repeatable = true)
            }
            is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = result.reason)
        }
    }

    /** "Stop editing" ends dictation into the focused field and hides the keyboard. */
    private suspend fun stopEditing(screen: ScreenSession): CommandResult {
        val snapshot = screen.snapshots.current().snapshot
        val field = TextFields.focused(snapshot) ?: return CommandResult(Outcome.DONE)
        editing.stop(field)
        if (TextFields.keyboardShows(snapshot)) screen.actions.global(GlobalAction.BACK)
        return CommandResult(Outcome.DONE)
    }

    private suspend fun newLine(screen: ScreenSession, field: ScreenElement): CommandResult {
        val state = screen.text.read(field.key) ?: return gone()
        if (editing.password(field, state.password)) return CommandResult(Outcome.FAILED, say = TextCommandTexts.PASSWORD_FIELD)
        val before = state.toTextState()
        val result = screen.text.newLine(field.key)
        if (result is ActionResult.Failed) return failed(result)
        record(screen, field, before)
        return CommandResult(Outcome.DONE, repeatable = true)
    }

    /**
     * "Undo" puts back the text from before Kestrel's last edit of the field, and "redo" the text after it, but only while
     * the field still shows what Kestrel left (design spec, section 9.7).
     */
    private suspend fun undo(screen: ScreenSession, field: ScreenElement, redo: Boolean): CommandResult {
        val state = screen.text.read(field.key) ?: return gone()
        if (editing.password(field, state.password)) return CommandResult(Outcome.FAILED, say = TextCommandTexts.PASSWORD_FIELD)
        val id = editing.id(field)
        val history = editing.history
        val possible = if (redo) history.canRedo(id) else history.canUndo(id)
        if (!possible) {
            return CommandResult(Outcome.FAILED, show = if (redo) TextCommandTexts.NOTHING_TO_REDO else TextCommandTexts.NOTHING_TO_UNDO)
        }
        val current = state.toTextState()
        val target = (if (redo) history.redo(id, current) else history.undo(id, current))
            ?: return CommandResult(Outcome.FAILED, say = TextCommandTexts.textChanged(redo))
        return when (val result = apply(screen, field, EditModel.between(current.text, target))) {
            is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = true)
            is ActionResult.Failed -> failed(result)
        }
    }

    /** Applies [change] to the field with the input method first and set-text as the fallback. */
    private suspend fun apply(screen: ScreenSession, field: ScreenElement, change: EditStep.Change): ActionResult =
        screen.text.replace(field.key, change.start, change.end, change.replacement, change.selectionStart, change.selectionEnd)

    /** Inserts [text] at the field's current selection, as a part of a dictation after a new line. */
    private suspend fun atCursor(screen: ScreenSession, field: ScreenElement, text: String): ActionResult {
        val state = screen.text.read(field.key) ?: return ActionResult.Failed(TextCommandTexts.FIELD_GONE)
        val end = state.selectionStart + text.length
        return screen.text.replace(field.key, state.selectionStart, state.selectionEnd, text, end, end)
    }

    /** Records Kestrel's edit of [field] from [before] to what the field shows now, once for the whole command. */
    private suspend fun record(screen: ScreenSession, field: ScreenElement, before: TextState) {
        val after = screen.text.read(field.key) ?: return
        if (after.text != before.text) editing.history.record(editing.id(field), before, after.toTextState())
    }

    // The editor's reasons are fixed texts without the field's words. Kestrel says the one that the owner must act on.
    private fun failed(result: ActionResult.Failed): CommandResult =
        if (result == TextEditor.NOT_TAKEN) {
            CommandResult(Outcome.FAILED, say = result.reason)
        } else {
            CommandResult(Outcome.FAILED, show = result.reason)
        }

    private fun gone() = CommandResult(Outcome.FAILED, show = TextCommandTexts.FIELD_GONE)

    private fun FieldState.toTextState() = TextState.of(text, selectionStart, selectionEnd)
}
```

- [ ] **Step 5: Run the JVM tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.commands.TextFieldsTest`
Expected: `BUILD SUCCESSFUL`, with 9 tests in `TextFieldsTest`.

- [ ] **Step 6: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` 88, `:app` 323 (314 + 9, `TextFieldsTest` 9), `:core:screen` 55, `:core:voice` 140, `:core:privacy` 48, `:core:safety` 2), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 7: Run the text commands' and the touch commands' device tests**

The touch commands' tests check that the text part hands "Delete" back to the bare labels where no field has the focus.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554 -Class TextCommandsTest,TouchCommandsTest`
Expected: `BUILD SUCCESSFUL` and "Command tests: 27 run, 0 failed, 0 errors, 0 skipped." (`TextCommandsTest` 7, `TouchCommandsTest` 20; the package now has 50 command tests), with no warning from the restores at the end.

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/commands/TextCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/TextCommandTexts.kt app/src/main/kotlin/dev/kestrel/app/commands/EditingState.kt app/src/main/kotlin/dev/kestrel/app/commands/TextFields.kt app/src/test/kotlin/dev/kestrel/app/commands/TextFieldsTest.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/TextCommandsTest.kt
git commit -m "feat(app): dictate into the focused text field, type, start and stop editing, new lines, and undo and redo Kestrel's own edits" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: The text part, part 2: the cursor, selecting, deleting, inserting, replacing, case and password fields

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/commands/TextCommands.kt` (imports, `run`, `edit`, `passwordEdit`)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/commands/TextCommandsTest.kt` (four tests)

**Interfaces:**
- Consumes: Task 5's `TextCommands` (`run` with the branch of `TextCommand.Redo`, `inField`, `apply`, `record`, `failed`, `gone`, `toTextState`, `editing`), `TextCommandTexts`, `TextCommandsTest` (`done`, `editNotes`, the marker line); Tasks 1 and 2's `EditModel.plan`, `EditStep`, `EditFailure`; Task 4's `TextEditor.select`, `replace`, `deleteBeforeCursor`; plan 3A's `TextScope.All`.
- Produces: `TextCommands.run` takes `Delete`, `Select`, `Unselect`, `MoveCursor`, `Insert`, `Replace`, `ReplaceBetween`, `ChangeCase` and `FormatEmail`; in a password field only "delete" (one character before the cursor) and "delete all". Task 7 adds its branches after the branch of these commands.

Design D8 and D6. The editing model plans each command on the field's live text and selection; a change goes through the editor's input-method path and is recorded in the history, a selection is one set-selection action. A phrase that the text does not contain makes Kestrel say "I can't find X in the text."; "Nothing is selected." is said, and "There's no text there." goes to the bar. Each of these commands is repeatable with "again". In a password field, which shows only dots, "delete" removes one character before the cursor through the input method, "delete all" empties the field, and every other editing command says "I can't edit a password field that way."; none of them is repeatable or recorded. Without a focused field, these commands go back to the screen part as bare labels (Task 5's `inField`), which the device test checks with the dialog's "Delete" button and "again".

- [ ] **Step 1: Write the failing tests**

In `app/src/androidTest/kotlin/dev/kestrel/app/commands/TextCommandsTest.kt`, replace

```kotlin
    // Tasks 6 and 7 add their tests above this line.
```

with

```kotlin
    @Test
    fun editingCommandsInAnEditText() {
        editNotes()

        done("type see you tomorrow at noon")
        screen.awaitLine("Notes: \"See you tomorrow at noon\" 24-24")
        done("go to the beginning")
        screen.awaitLine("Notes: \"See you tomorrow at noon\" 0-0")
        done("move after tomorrow")
        screen.awaitLine("Notes: \"See you tomorrow at noon\" 16-16")
        done("insert sharp after noon")
        screen.awaitLine("Notes: \"See you tomorrow at noon sharp\" 30-30")
        done("select tomorrow")
        screen.awaitLine("Notes: \"See you tomorrow at noon sharp\" 8-16")
        done("uppercase tomorrow")
        screen.awaitLine("Notes: \"See you TOMORROW at noon sharp\" 16-16")
        done("replace noon with one")
        screen.awaitLine("Notes: \"See you TOMORROW at one sharp\" 23-23")
        done("delete sharp")
        screen.awaitLine("Notes: \"See you TOMORROW at one\" 23-23")
        done("delete two words")
        screen.awaitLine("Notes: \"See you TOMORROW\" 16-16")
        done("left three characters")
        screen.awaitLine("Notes: \"See you TOMORROW\" 13-13")
        done("select all")
        screen.awaitLine("Notes: \"See you TOMORROW\" 0-16")
        done("delete selected text")
        screen.awaitLine("Notes: \"\" 0-0")
    }

    @Test
    fun editingCommandsInComposeAndOnTheWeb() {
        screen.open("ComposeEditActivity", "Compose edit fixture")
        done("start editing Compose notes")
        screen.awaitFocus("compose_notes")
        done("type hello there world")
        done("delete there")
        screen.awaitLine("Compose notes: \"Hello world\" 5-5")
        done("capitalize world")
        screen.awaitLine("Compose notes: \"Hello World\" 11-11")
        done("select hello")
        screen.awaitLine("Compose notes: \"Hello World\" 0-5")

        screen.open("WebEditActivity", "Web edit fixture")
        done("start editing Web notes")
        screen.awaitFocus("web_notes")
        done("type red green blue")
        done("replace green with yellow")
        screen.awaitLine("Web notes: \"Red yellow blue\" 10-10")
        done("go to the end")
        done("delete the last word")
        screen.awaitLine("Web notes: \"Red yellow\" 10-10")
        done("select red")
        screen.awaitLine("Web notes: \"Red yellow\" 0-3")
    }

    @Test
    fun theOneWordCommandsEditAFieldAndTapAButtonWithoutOne() {
        editNotes()
        done("type hello world")
        val delete = CommandTests.say("delete")
        assertEquals(Outcome.DONE, delete.outcome)
        assertEquals("Delete", delete.kind)
        screen.awaitLine("Notes: \"Hello\" 5-5")

        // Without a text field, "delete" taps the dialog's only "Delete" button, and "again" taps it once more.
        screen.open("CommandsActivity", "Commands fixture")
        done("tap Open dialog")
        screen.awaitLine("Draft")
        done("Delete")
        screen.awaitLine("Deleted 1 time")
        done("again")
        screen.awaitLine("Deleted 2 times")
    }

    @Test
    fun aPasswordFieldTakesOnlyDeleteAndDeleteAll() {
        screen.open("EditActivity", "Edit fixture")
        done("start editing Password")
        screen.awaitFocus("text_password")
        done("a b c d")
        screen.awaitLine("Password: 4 characters")

        done("delete")
        screen.awaitLine("Password: 3 characters")
        assertEquals(Outcome.FAILED, CommandTests.say("delete two words").outcome)
        CommandTests.awaitSaid(TextCommandTexts.PASSWORD_FIELD)
        done("delete all")
        screen.awaitLine("Password: 0 characters")
    }

    // Tasks 6 and 7 add their tests above this line.
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554 -Class TextCommandsTest`
Expected: the script ends with exit code 1 and "Command tests: 11 run, 4 failed, 0 errors, 0 skipped.": `editingCommandsInAnEditText`, `editingCommandsInComposeAndOnTheWeb`, `theOneWordCommandsEditAFieldAndTapAButtonWithoutOne` and `aPasswordFieldTakesOnlyDeleteAndDeleteAll` fail with an assertion such as "go to the beginning expected:<DONE> but was:<IGNORED>", because the text part does not take these commands yet; the restores at the end give no warning.

- [ ] **Step 3: Plan and apply the editing commands**

In `app/src/main/kotlin/dev/kestrel/app/commands/TextCommands.kt`, replace

```kotlin
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.edit.Dictation
import dev.kestrel.commands.edit.DictationPart
import dev.kestrel.commands.edit.EditModel
```

with

```kotlin
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.TextScope
import dev.kestrel.commands.edit.Dictation
import dev.kestrel.commands.edit.DictationPart
import dev.kestrel.commands.edit.EditFailure
import dev.kestrel.commands.edit.EditModel
```

Replace

```kotlin
            TextCommand.Redo -> inField(screen) { undo(screen, it, redo = true) }
```

with

```kotlin
            TextCommand.Redo -> inField(screen) { undo(screen, it, redo = true) }
            is TextCommand.Delete, is TextCommand.Select, TextCommand.Unselect, is TextCommand.MoveCursor, is TextCommand.Insert,
            is TextCommand.Replace, is TextCommand.ReplaceBetween, is TextCommand.ChangeCase, TextCommand.FormatEmail,
            -> inField(screen) { edit(screen, it, command as TextCommand) }
```

Replace

```kotlin
    /** Applies [change] to the field with the input method first and set-text as the fallback. */
```

with

```kotlin
    /** The editing commands, planned by the editing model on the field's text and selection. */
    private suspend fun edit(screen: ScreenSession, field: ScreenElement, command: TextCommand): CommandResult {
        val state = screen.text.read(field.key) ?: return gone()
        if (editing.password(field, state.password)) return passwordEdit(screen, field, command, state)
        val before = state.toTextState()
        return when (val step = EditModel.plan(command, before)) {
            is EditStep.Change -> when (val result = apply(screen, field, step)) {
                is ActionResult.Done -> {
                    record(screen, field, before)
                    CommandResult(Outcome.DONE, repeatable = true)
                }
                is ActionResult.Failed -> failed(result)
            }
            is EditStep.Select -> when (val result = screen.text.select(field.key, step.start, step.end)) {
                is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = true)
                is ActionResult.Failed -> failed(result)
            }
            is EditStep.Fail -> when (step.reason) {
                EditFailure.NOTHING_SELECTED -> CommandResult(Outcome.FAILED, say = TextCommandTexts.NOTHING_SELECTED)
                EditFailure.NOT_FOUND -> CommandResult(Outcome.FAILED, say = TextCommandTexts.notInText(step.phrase.orEmpty()))
                EditFailure.NOTHING_THERE -> CommandResult(Outcome.FAILED, show = TextCommandTexts.NOTHING_THERE)
                EditFailure.NOT_AN_EDIT -> CommandResult(Outcome.IGNORED)
            }
        }
    }

    /**
     * A password field shows only dots, so Kestrel never rewrites its text: "delete" removes one character before the
     * cursor, "delete all" empties it, and the other editing commands do nothing.
     */
    private suspend fun passwordEdit(screen: ScreenSession, field: ScreenElement, command: TextCommand, state: FieldState): CommandResult {
        val result = when (command) {
            TextCommand.Delete(null) -> screen.text.deleteBeforeCursor(field.key, 1)
            TextCommand.Delete(TextScope.All) -> screen.text.replace(field.key, 0, state.text.length, "", 0, 0)
            else -> return CommandResult(Outcome.FAILED, say = TextCommandTexts.PASSWORD_FIELD)
        }
        return when (result) {
            is ActionResult.Done -> CommandResult(Outcome.DONE)
            is ActionResult.Failed -> failed(result)
        }
    }

    /** Applies [change] to the field with the input method first and set-text as the fallback. */
```

- [ ] **Step 4: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` 88, `:app` 323, `:core:screen` 55, `:core:voice` 140, `:core:privacy` 48, `:core:safety` 2; unchanged by this task), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 5: Run the text commands' device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554 -Class TextCommandsTest`
Expected: `BUILD SUCCESSFUL` and "Command tests: 11 run, 0 failed, 0 errors, 0 skipped." (`TextCommandsTest` 7 + 4; the package now has 54 command tests), with no warning from the restores at the end.

- [ ] **Step 6: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/commands/TextCommands.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/TextCommandsTest.kt
git commit -m "feat(app): move the cursor, select, delete, insert, replace and change case by voice, and delete in password fields" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: The clipboard, the keyboard, "show password" and "search for", and the spec and the README

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/commands/TextCommands.kt` (imports, `run`, `clipboard`, `keyboard`, `showPassword`, `search`, `openSearch`, the companion object)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 9.3's row that starts with `| Type |`, and section 9.7), `README.md` (at the marker `<!-- Plan 3B: dictation and editing -->`)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/commands/TextCommandsTest.kt` (five tests)

**Interfaces:**
- Consumes: Tasks 5 and 6's `TextCommands` (`run` with the branch of the editing commands, `inField`, `apply`, `record`, `failed`, `gone`), `TextFields` (`focused`, `keyboardShows`, `searchField`, `searchButton`, `passwordField`, `passwordToggle`), `TextCommandTexts`; Task 4's `TextEditor.clipboard`, `replace`, `submit`, `searches`, `SELECT_FIRST`, `ClipboardKind`, `TextFieldTests.awaitKeyboard`; Task 2's `Dictation.phrase`; plan 3A's `ClipboardAction`, `GlobalAction.BACK`, `ScreenTargets.chosen`, `still`.
- Produces: `TextCommands.run` takes `Clipboard`, `Keyboard`, `ShowPassword` and `Search`; spec 9.3's Type row and section 9.7 as design D18 gives them to plan 3B; the README's paragraph on dictation and editing.

Design D8 and D18. "Copy", "cut" and "paste" act on the focused field, and without one they go back to the bare labels; copy and cut need a selection ("Select some text first."), and cut and paste are recorded in the history. "Show keyboard" taps the focused field after checking that it is still there ("No text field has the focus." without one); "hide keyboard" goes back only while the keyboard shows ("The keyboard is not showing." otherwise), because `SoftKeyboardController.setShowMode` would hide it for every field. "Show password" first records the field as a password field (Task 5's `EditingState.password`), and taps its show-password control: the clickable or checkable element in its window, nearest to it, whose names say "show", "reveal" or "visibility" with "password", or else the only unnamed toggle at the field's right end; otherwise Kestrel says "I can't find a way to show the password here.". "Search for X" uses the focused field only when it is a search field (its started input has the search action, or its names or its view ID say "search"), else the first search field on the screen, else taps a button whose name starts with "search" (or whose view ID has "search"), after checking that it is still the element chosen ("The screen changed. Say it again." otherwise), and waits up to 2 s for a field to get the focus. It never uses a password field (also one that Kestrel remembers as one), nor a field with more lines that has no search action, such as a message draft. It replaces the field's text with X, records the edit, so that "undo" brings back what was there, and submits it with the field's own action ("I can't find a search field here." without a search field). Spec 9.3's Type row then describes the agent's whole-field typing (`ScreenActions.setText`, unchanged), and spec 9.7 editing by voice.

- [ ] **Step 1: Write the failing tests**

In `app/src/androidTest/kotlin/dev/kestrel/app/commands/TextCommandsTest.kt`, replace

```kotlin
import dev.kestrel.app.commands.TextFieldTests.awaitFocus
```

with

```kotlin
import dev.kestrel.app.commands.TextFieldTests.awaitFocus
import dev.kestrel.app.commands.TextFieldTests.awaitKeyboard
```

Replace

```kotlin
    // Tasks 6 and 7 add their tests above this line.
```

with

```kotlin
    @Test
    fun copyCutAndPasteBetweenTwoFields() {
        screen.open("EditActivity", "Edit fixture")
        done("start editing Search")
        screen.awaitFocus("text_search")
        done("type copy me")
        assertEquals(Outcome.FAILED, CommandTests.say("copy").outcome)
        CommandTests.awaitSaid(TextEditor.SELECT_FIRST.reason)

        done("select copy")
        done("copy")
        done("start editing Paste here")
        screen.awaitFocus("text_paste")
        done("paste")
        screen.awaitLine("Paste: \"Copy\"")
        done("select all")
        done("cut")
        screen.awaitLine("Paste: \"\"")
    }

    @Test
    fun showAndHideTheKeyboard() {
        editNotes()
        screen.awaitKeyboard(shown = true)

        done("hide keyboard")
        screen.awaitKeyboard(shown = false)
        assertEquals(Outcome.FAILED, CommandTests.say("hide the keyboard").outcome)
        screen.awaitBar(TextCommandTexts.KEYBOARD_NOT_SHOWING)
        done("show keyboard")
        screen.awaitKeyboard(shown = true)
    }

    @Test
    fun showPasswordTicksTheCheckBoxNextToTheField() {
        screen.open("EditActivity", "Edit fixture")

        // "Show the password" is no bare label of the check box, so only the text part can take it.
        done("show the password")
        screen.awaitLine("Password shown")
        done("uncheck Show password")
        screen.awaitLine("Password hidden")
        done("show password")
        screen.awaitLine("Password shown")
        // The shown password is still a password field by its input type: dictation spells it.
        done("start editing Password")
        screen.awaitFocus("text_password")
        done("three")
        screen.awaitLine("Password: 1 characters")
    }

    @Test
    fun searchForTypesTheQueryAndSubmitsItInEveryKindOfField() {
        screen.open("EditActivity", "Edit fixture")
        done("search for coffee near me")
        screen.awaitLine("Action 3: \"coffee near me\"")

        screen.open("ComposeEditActivity", "Compose edit fixture")
        done("search for weather tomorrow")
        screen.awaitLine("Searched: \"weather tomorrow\"")

        screen.open("WebEditActivity", "Web edit fixture")
        done("search for coffee near me")
        screen.awaitLine("Submitted: \"coffee near me\"")
    }

    @Test
    fun withoutASearchFieldKestrelSaysSo() {
        screen.open("CommandsActivity", "Commands fixture")

        assertEquals(Outcome.FAILED, CommandTests.say("search for coffee").outcome)
        CommandTests.awaitSaid(TextCommandTexts.NO_SEARCH_FIELD)
    }

    // Tasks 6 and 7 add their tests above this line.
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554 -Class TextCommandsTest`
Expected: the script ends with exit code 1 and "Command tests: 16 run, 5 failed, 0 errors, 0 skipped.": `copyCutAndPasteBetweenTwoFields`, `showAndHideTheKeyboard`, `showPasswordTicksTheCheckBoxNextToTheField`, `searchForTypesTheQueryAndSubmitsItInEveryKindOfField` and `withoutASearchFieldKestrelSaysSo` fail with an assertion such as "expected:<FAILED> but was:<IGNORED>", because the text part does not take these commands yet; the restores at the end give no warning.

- [ ] **Step 3: Add the clipboard, the keyboard, "show password" and "search for"**

In `app/src/main/kotlin/dev/kestrel/app/commands/TextCommands.kt`, replace

```kotlin
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
```

with

```kotlin
import dev.kestrel.commands.ClipboardAction
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
```

Replace

```kotlin
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.FieldState
```

with

```kotlin
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.ClipboardKind
import dev.kestrel.screen.act.FieldState
```

Replace

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

with

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
```

Replace

```kotlin
            -> inField(screen) { edit(screen, it, command as TextCommand) }
```

with

```kotlin
            -> inField(screen) { edit(screen, it, command as TextCommand) }
            is TextCommand.Clipboard -> inField(screen) { clipboard(screen, it, command.action) }
            is TextCommand.Keyboard -> keyboard(screen, command.show)
            TextCommand.ShowPassword -> showPassword(screen)
            is TextCommand.Search -> search(screen, command.query)
```

Replace

```kotlin
    /** Applies [change] to the field with the input method first and set-text as the fallback. */
```

with

```kotlin
    /** "Cut", "copy" and "paste" in the focused field; cut and copy need a selection. */
    private suspend fun clipboard(screen: ScreenSession, field: ScreenElement, action: ClipboardAction): CommandResult {
        val state = screen.text.read(field.key) ?: return gone()
        if (editing.password(field, state.password)) return CommandResult(Outcome.FAILED, say = TextCommandTexts.PASSWORD_FIELD)
        val before = state.toTextState()
        val kind = when (action) {
            ClipboardAction.CUT -> ClipboardKind.CUT
            ClipboardAction.COPY -> ClipboardKind.COPY
            ClipboardAction.PASTE -> ClipboardKind.PASTE
        }
        val result = screen.text.clipboard(field.key, kind)
        if (result == TextEditor.SELECT_FIRST) return CommandResult(Outcome.FAILED, say = TextEditor.SELECT_FIRST.reason)
        if (result is ActionResult.Failed) return failed(result)
        if (action != ClipboardAction.COPY) record(screen, field, before)
        return CommandResult(Outcome.DONE, repeatable = true)
    }

    /** "Show keyboard" taps the focused field; "hide keyboard" goes back, but only while the keyboard shows. */
    private suspend fun keyboard(screen: ScreenSession, show: Boolean): CommandResult {
        val snapshot = screen.snapshots.current().snapshot
        val result = if (show) {
            val field = TextFields.focused(snapshot) ?: return CommandResult(Outcome.FAILED, say = TextCommandTexts.NO_FIELD)
            val element = environment.targets.still(ScreenTargets.chosen(field))
                ?: return CommandResult(Outcome.FAILED, say = CommandTexts.SCREEN_CHANGED)
            screen.actions.tap(element.key)
        } else {
            if (!TextFields.keyboardShows(snapshot)) return CommandResult(Outcome.FAILED, show = TextCommandTexts.KEYBOARD_NOT_SHOWING)
            screen.actions.global(GlobalAction.BACK)
        }
        return when (result) {
            is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = true)
            is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = result.reason)
        }
    }

    /** "Show password" taps what shows the password of the focused password field, or of the first on the screen. */
    private suspend fun showPassword(screen: ScreenSession): CommandResult {
        val snapshot = screen.snapshots.current().snapshot
        val field = TextFields.passwordField(snapshot) ?: return CommandResult(Outcome.FAILED, say = TextCommandTexts.NO_PASSWORD_TOGGLE)
        // Once its text shows, the field may no longer say that it is a password field.
        editing.password(field, password = true, focused = TextFields.focused(snapshot))
        val toggle = TextFields.passwordToggle(snapshot, field)
            ?: return CommandResult(Outcome.FAILED, say = TextCommandTexts.NO_PASSWORD_TOGGLE)
        if (toggle.checkable && toggle.checked) return CommandResult(Outcome.DONE, show = TextCommandTexts.PASSWORD_SHOWING)
        val element = environment.targets.still(ScreenTargets.chosen(toggle))
            ?: return CommandResult(Outcome.FAILED, say = CommandTexts.SCREEN_CHANGED)
        return when (val result = screen.actions.tap(element.key)) {
            is ActionResult.Done -> CommandResult(Outcome.DONE)
            is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = result.reason)
        }
    }

    /**
     * "Search for X": the focused field when it is a search field (by its input's search action, its names or its view
     * ID), else the first search field, else a "search" button, which Kestrel taps before it waits up to 2 s for a field
     * to get the focus. A password field, or a field with more lines and no search action, such as a message, is never
     * used. Kestrel replaces the field's text with X, records it so that "undo" brings back what was there, and submits
     * it.
     */
    private suspend fun search(screen: ScreenSession, query: String): CommandResult {
        val snapshot = screen.snapshots.current().snapshot
        val focusedSearches = TextFields.focused(snapshot)?.let { screen.text.searches(it.key) } == true
        val field = TextFields.searchField(snapshot, focusedSearches) ?: run {
            val button = TextFields.searchButton(snapshot) ?: return CommandResult(Outcome.FAILED, say = TextCommandTexts.NO_SEARCH_FIELD)
            val element = environment.targets.still(ScreenTargets.chosen(button))
                ?: return CommandResult(Outcome.FAILED, say = CommandTexts.SCREEN_CHANGED)
            openSearch(screen, element) ?: return CommandResult(Outcome.FAILED, say = TextCommandTexts.NO_SEARCH_FIELD)
        }
        val state = screen.text.read(field.key) ?: return gone()
        // Checked on the live field, also for one that a search button opened.
        val password = editing.password(field, state.password, TextFields.focused(screen.snapshots.current().snapshot))
        if (password || (state.multiLine && !screen.text.searches(field.key))) {
            return CommandResult(Outcome.FAILED, say = TextCommandTexts.NO_SEARCH_FIELD)
        }
        val text = Dictation.phrase(query)
        val replaced = screen.text.replace(field.key, 0, state.text.length, text, text.length, text.length)
        if (replaced is ActionResult.Failed) return failed(replaced)
        record(screen, field, state.toTextState())
        return when (val submitted = screen.text.submit(field.key)) {
            is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = true)
            is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = submitted.reason)
        }
    }

    /** Taps the search [button], which is still the element chosen, and waits up to 2 s for a field to get the focus. */
    private suspend fun openSearch(screen: ScreenSession, button: ScreenElement): ScreenElement? {
        if (screen.actions.tap(button.key) is ActionResult.Failed) return null
        return withTimeoutOrNull(SEARCH_FIELD_MILLIS) {
            var field: ScreenElement? = null
            while (field == null) {
                field = TextFields.focused(screen.snapshots.current().snapshot)
                if (field == null) delay(POLL_MILLIS)
            }
            field
        }
    }

    /** Applies [change] to the field with the input method first and set-text as the fallback. */
```

Replace

```kotlin
    private fun FieldState.toTextState() = TextState.of(text, selectionStart, selectionEnd)
}
```

with

```kotlin
    private fun FieldState.toTextState() = TextState.of(text, selectionStart, selectionEnd)

    private companion object {
        const val SEARCH_FIELD_MILLIS = 2_000L
        const val POLL_MILLIS = 100L
    }
}
```

- [ ] **Step 4: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 9.3, replace

```markdown
| Type | The set-text action (section 9.7) | The accessibility input method (API 33) |
```

with

```markdown
| Type, for the agent's whole-field typing (section 9.7 describes editing by voice) | The set-text action | The accessibility input method (API 33) |
```

In section 9.7, replace

```markdown
- "Start editing", or a focused text field, starts editing. "Stop editing" ends it.
- While editing, speech that is not a command is typed at the cursor. Spoken punctuation is converted ("comma", "new line", "exclamation mark"), and spoken characters work ("capital p", "one two three"), as in Voice Access.
- Kestrel types with the set-text action, computing the new text from the field's current text and selection. If an app rejects set-text, or the text does not read back correctly, Kestrel uses the accessibility input method for that app from then on. A field that is disabled or has gone does not count as a rejection.
- Undo and redo use Kestrel's own history of its edits in each field.
- "Show password" makes a password field readable, so the owner can check dictated characters.
```

with

```markdown
- "Start editing" (with a field's name, or without one for the focused field, the only one or the first from the top), or a focused text field, turns dictation on. It acts only on a text field: a number or a name of anything else, such as the phone app's Call button, makes the bar say "It isn't a text field.", and nothing happens. "Stop editing" turns it off for that field and hides the keyboard, until the owner says "start editing" again or Kestrel finds another field with the focus when the owner next speaks.
- While dictation is on, speech that is not a command is typed at the cursor; every command still runs as a command, and "type X" types X also while dictation is off, so that "type delete" types the word. Kestrel types only the punctuation that the owner says ("comma", "period", "question mark", "exclamation mark", "colon", "semicolon", the quotes, "new line", "new paragraph" and the others), and drops the recogniser's own, which adds a full stop to almost every short utterance. The first word of an utterance loses its capital unless it is "I" or written in capitals; a word gets a capital at the start of an empty field and after a full stop, question mark or exclamation mark, whether the owner says it now or the field has it already. Spoken characters are joined ("capital p one two three" types "P123"), and in a password field every word is spelled and spoken punctuation goes in as its mark ("hunter two exclamation mark" types "hunter2!"; there also "hash", "dollar sign", "asterisk", "plus sign" and "equals sign"), as in Voice Access. "Dot" joins without a space, as in "example.com", and a lone "stop" or "cancel" is never typed. In a phone number field, such as a dialpad's, Kestrel types only speech that reads as a phone number (digits, "plus", "star", "hash" and dashes) and hands any other speech to the other commands, so that "call" never goes into the number; the editing commands work there as anywhere.
- Kestrel computes every cursor move, selection and range itself from the field's text and selection, and never uses Android's movement actions, which WebView and Chrome take from their own cursor (prototype P4). A word is a run of letters and digits, with apostrophes inside it, a sentence ends with ".", "!" or "?", a line ends at a line break, because Kestrel cannot see where the screen wraps a line, and a paragraph ends at a blank line. A spoken phrase is found in the field's text after normalising both (section 9.6), nearest to the cursor.
- Kestrel edits through its accessibility input method first: it focuses the field, waits until the field's app has started the input, selects the range that changes and commits only that range, which web pages see as typing and which lands in the app's own undo history. When the input does not start or the field does not show the result, Kestrel sets the whole new text with the set-text action and then the selection. Every edit is read back; when neither way works, Kestrel says "The text field didn't take the change."
- A new line goes in with the Enter key, and only in a field with more than one line, which Kestrel learns from the node or from the input that the app started, because Compose's nodes always say one line. "Search for X" uses only a search field (the focused field only when it is one), never a password field or a message, records its edit for "undo", and submits through the field's own editor action (search, go, send and the others), which the input method sends as the app expects; the IME-enter action is only the fallback, never in a field with more lines.
- Undo and redo use Kestrel's own history of its edits in each field, one entry per command, and work only while the field still shows the text that Kestrel left. Kestrel does not use the app's undo, nor Ctrl+Z, which on a web page undoes edits in other fields too.
- Cut, copy and paste act on the focused field; cut and copy need a selection. "Show keyboard" taps the focused field, and "hide keyboard" goes back while the keyboard shows.
- A password field shows only dots, so Kestrel only types at its cursor, deletes before it and empties it; it stays a password field for Kestrel while "show password" shows its text: by the type of its input, and, where a web page turns it into a plain text field, because Kestrel remembers the field that it saw as a password field until another text field has the focus. While one has the focus, the feedback bar shows "Typing in a password field." instead of the transcript, and its edits are never repeated or kept in the history. "Show password" taps the field's show-password control, so the owner can check dictated characters.
- Without a focused field, "delete", "copy" and the other editing commands are bare labels, which tap a button with that name (section 9.6).
```

- [ ] **Step 5: Update the README**

In `README.md`, replace

```markdown
<!-- Plan 3B: dictation and editing -->
```

with

```markdown
You can dictate and edit text in any text field, also in Compose apps and on web pages. "Start editing", or "start editing Message" for a field by its name, taps a field and turns dictation on, and so does a field that has the focus: what you say that is not a command is typed at the cursor, with the punctuation that you say ("hello comma how are you question mark" types "Hello, how are you?"), and "capital p one two three" types "P123". Kestrel types only the punctuation that you say. "Type X" types X even when it is a command, as in "type delete", and "stop editing" turns dictation off for that field and hides the keyboard. You can move the cursor ("go to the end", "move after tomorrow", "left three characters", "right two words"), select ("select tomorrow", "select the next two words", "select all", "unselect"), delete ("delete", which deletes the selection or the word before the cursor, "delete three words", "delete from hello to world", "delete all"), insert ("insert very before nice"), replace ("replace Monday with Tuesday"), change the case ("capitalize paris", "uppercase nasa") and "format email". "Undo" and "redo" take back and redo Kestrel's own edits while the field still shows them, "new line" starts a line in a field with more than one line, "copy", "cut" and "paste" use the clipboard, "show keyboard" and "hide keyboard" do what they say, "show password" shows a password field's text so that you can check it, and "search for coffee" types into the search field and searches. While a password field has the focus, the feedback bar shows "Typing in a password field." instead of what Kestrel heard. Without a field that has the focus, "delete", "copy" and the other one-word commands tap a button with that name.

<!-- Plan 3B: dictation and editing -->
```

- [ ] **Step 6: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin :testapp:assembleDebug`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` 88, `:app` 323, `:core:screen` 55, `:core:voice` 140, `:core:privacy` 48, `:core:safety` 2; unchanged by this task), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests and the test app compile.

- [ ] **Step 7: Run all command device tests**

This run covers plan 3A's command tests with the text part in place, and every test of this plan.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554`
Expected: `BUILD SUCCESSFUL` and "Command tests: 59 run, 0 failed, 0 errors, 0 skipped." (`TextEditorTest` 7, `TextCommandsTest` 16, and plan 3A's 36), with no warning from the restores at the end.

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/commands/TextCommands.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/TextCommandsTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(app): copy, cut and paste, show and hide the keyboard, show passwords and search by voice, and describe editing by voice in the spec" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Self-review

**1. Design coverage.** Each item of design D8 and of the other decisions that "The plans" gives to plan 3B, and where it is:

| Design item | Task |
|---|---|
| D8 editing model: `TextState`, `EditStep` (`Change`, `Select`, `Fail`), every offset computed by Kestrel; the units (character, word with apostrophes, sentence, logical line, paragraph or the whole text); counts backwards, "next" forwards, "previous" and "last" backwards; word moves at a word's edge | 1 (`TextUnits`, `EditModel`, `TextUnitsTest`, `EditModelTest`) |
| D8 `TextSearch.find` with the normaliser's offsets, a window of words at Jaro–Winkler 0.85, the hit nearest before the cursor or else after it; "from X to Y" includes, "between X and Y" excludes | 1 (`TextSearch`, `TextSearchTest`, `EditModelTest`) |
| D8 `Delete(null)` (selection or word before the cursor), "delete selected text" with "Nothing is selected", "delete all", the selection and cursor commands | 1, and the executor in 6 |
| D8 insert (before, after, between, the parser's splits), replace (first "with"), replace between, capitalize, uppercase, lowercase, format email (selection or whole text) | 2 (model), 3 (parser), 6 (executor) |
| D8 `Dictation`: the recogniser's punctuation dropped, the first word's capital, the spoken punctuation list, no space before closing marks, the space between old and new text, capitals at an empty field's start and after ".", "?", "!", alternating double quotes, parts at "new line" and "new paragraph"; spelling and password spelling; D8's five dictation lines | 2 (`Dictation`, `DictationTest`) |
| D8 `EditHistory`: per field (app and key), 50 edits and 20 fields, one entry per command, undo and redo only while the field shows Kestrel's last text, "The text changed since my last edit…", never for password fields | 2 (`EditHistory`), 5 (`TextCommands.undo`, `record`) |
| D5 and D8: `GrammarText` with every phrase of D8's list, copy, cut and paste fixed only, the splits, polite words, phrases that stay `Bare` (D8's dictation lines among them), no phrase that `GrammarPhone` takes; `HelpText` | 3 |
| D8 `TextEditor.read` (no hint as text, negative selection at the end, multi-line from the node or the input), `replace` (focus, wait for the input of the field's package, the focus check, `setSelection`, `commitText`, the selection afterwards, read back 1 s; set-text and set-selection as the fallback), `newLine` (Enter), `select` (a false result with the selection already there counts), `submit` (the editor action, Enter, IME-enter as the fallback, never on a multi-line field), `clipboard` (focus first, a selection for copy and cut), `typeAtCursor`, `deleteBeforeCursor` | 4 (`TextEditor`, `TextEditorTest` in `EditText`, Compose and WebView) |
| D15 text fixtures: `EditActivity` (single-line search field with `IME_ACTION_SEARCH`, multi-line field, password field with a "Show password" check box, a paste field), `ComposeEditActivity` (P4's three fields), `WebEditActivity` (input, textarea, contenteditable), status lines for the text, the selection and the editor actions, `ids_text.xml`, the manifest's 3B marker | 4 |
| D8 when dictation is on, "start editing [X]" (named, focused, only, first from the top; clears "stop editing"), "stop editing" (hides the keyboard), a bare number typed only while no numbers or grid show, "type X" also while dictation is off | 5 (`EditingState`, `TextFields`, `TextCommands`, `TextFieldsTest`, `TextCommandsTest`) |
| The owner's rule after plan 3C's review: a phone number field takes only speech that reads as a phone number, and other speech is handed back | 4 (`TextEditor.phone`, the fixture's `text_phone`), 5 (`TextFields.phoneNumber`, `TextFieldsTest`, `dictationTypesWhatIsSaidWithSpokenPunctuationAndSpelling`) |
| The owner's rule after plan 3C's third review: "start editing" with a number or a name acts only on a text field ("It isn't a text field.") | 5 (`TextFields.namesOtherElement`, `TextFieldsTest`, `startEditingTakesTheFirstFieldAndStopEditingEndsDictation`) |
| D2 `privateInput()` from `SnapshotCache.latest()` | 5 (`aPasswordFieldHidesTheTranscriptAndSpellsWhatIsDictated`) |
| D8 and D17: a password field stays one for Kestrel while "show password" shows its text, also on a web page that makes it a plain text input (no spelling lost, nothing in the history) | 4 (the input's type), 5 (`EditingState.password`, `TextFieldsTest`, `aWebPasswordThatShowPasswordShowsIsStillSpelled`), 7 (`showPasswordTicksTheCheckBoxNextToTheField`) |
| D6 the one-word commands' hand-back without a focused field, and "again" after it | 5 (`inField`), 6 (`theOneWordCommandsEditAFieldAndTapAButtonWithoutOne`), 3 (the touch commands' tests still pass) |
| D8 password fields: typing and "delete" at the cursor, "delete all", "I can't edit a password field that way." | 5 (typing), 6 (`passwordEdit`) |
| D8 clipboard, show and hide keyboard (Back only while the keyboard shows), "show password" (and the field still a password field afterwards), "search for X" (a search field by its input, names or view ID, never a password field or a draft, a search button and a 2 s wait, replace, record and submit) | 4 (`TextEditor.searches`, the password type of the input), 5 (`TextFields`), 7 |
| D18 spec 9.3's Type row and 9.7; README at the 3B marker | 7 |
| D17 privacy: no log anywhere in the text code; `toString` of `TextState`, `EditStep.Change`, `EditStep.Fail`, `FieldState` and `EditHistory` print lengths and counts; the fixtures' password line holds only the length | 1, 2, 4 |

**2. Placeholder scan.** The plan has no "TBD", "TODO" or "similar to Task N"; every code step shows the whole code, and every replacement its exact old and new text. Every count is absolute, from the totals after plan 3A.

**3. Type consistency.** `TextEditor`'s functions keep plan 3A's names, parameters and return types; `EditStep.Change` has the same five fields wherever it is built; `Dictation.insert(state, spoken, password, startsUtterance)` and `DictationPart.Text(change)` and `NewLine` are used in the same form in Tasks 2 and 5; `EditHistory.record`, `canUndo`, `canRedo`, `undo` and `redo` keep one signature; `TextFields`' functions take a `ScreenSnapshot` everywhere; the fixtures' lines in the tests match the texts that the fixtures write (`Notes: "…" a-b`, `Password: n characters`, `Action 3: "…"`, `Searched: "…"`, `Submitted: "…"`).

**4. Counts.** `:core:commands` 46: Task 1 65 (`TextUnitsTest` 5, `TextSearchTest` 3, `EditModelTest` 11), Task 2 85 (`EditModelChangesTest` 6, `DictationTest` 10, `EditHistoryTest` 4), Task 3 88 (`GrammarTextTest` 3). `:app` JVM 314: Task 5 323 (`TextFieldsTest` 9). Command device tests 36: Task 4 43 (`TextEditorTest` 7), Task 5 50, Task 6 54, Task 7 59 (`TextCommandsTest` 16); `connected-test.ps1` then runs 179 device tests, with the 59 command tests skipped. No other count changes: `:core:screen` 55, `:core:voice` 140, `:core:privacy` 48, `:core:safety` 2, voice device tests 83, Pester 144 in each shell.
