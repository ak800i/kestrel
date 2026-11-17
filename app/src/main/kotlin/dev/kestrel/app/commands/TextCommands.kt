package dev.kestrel.app.commands

import dev.kestrel.commands.ClipboardAction
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.Target
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.TextScope
import dev.kestrel.commands.edit.Dictation
import dev.kestrel.commands.edit.DictationPart
import dev.kestrel.commands.edit.EditFailure
import dev.kestrel.commands.edit.EditModel
import dev.kestrel.commands.edit.EditStep
import dev.kestrel.commands.edit.TextState
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.ClipboardKind
import dev.kestrel.screen.act.FieldState
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.act.TextEditor
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
            is TextCommand.Delete, is TextCommand.Select, TextCommand.Unselect, is TextCommand.MoveCursor, is TextCommand.Insert,
            is TextCommand.Replace, is TextCommand.ReplaceBetween, is TextCommand.ChangeCase, TextCommand.FormatEmail,
            -> inField(screen) { edit(screen, it, command as TextCommand) }
            is TextCommand.Clipboard -> inField(screen) { clipboard(screen, it, command.action) }
            is TextCommand.Keyboard -> keyboard(screen, command.show)
            TextCommand.ShowPassword -> showPassword(screen)
            is TextCommand.Search -> search(screen, command.query)
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
            val part = Dictation.insert(state.toTextState(), spoken, password = true).singleOrNull() as? DictationPart.Text
            if (part == null || part.change.replacement.isEmpty()) return CommandResult(Outcome.IGNORED)
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
        try {
            for ((index, part) in parts.withIndex()) {
                val result = when (part) {
                    is DictationPart.Text -> if (index == 0) apply(screen, field, part.change) else atCursor(screen, field, part.change.replacement)
                    DictationPart.NewLine -> screen.text.newLine(field.key)
                }
                if (result is ActionResult.Failed) return failed(result)
            }
            return CommandResult(Outcome.DONE, repeatable = true)
        } finally {
            // What went in before a failure, or before "stop" cancelled the command between two parts, can still be undone.
            withContext(kotlinx.coroutines.NonCancellable) { record(screen, field, before) }
        }
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
        val field = TextFields.focused(screen.snapshots.current().snapshot) ?: return CommandResult(Outcome.DONE)
        editing.stop(field)
        // Read on the live screen right before the press: Back without the keyboard would leave the screen.
        screen.snapshots.invalidate()
        if (TextFields.keyboardShows(screen.snapshots.current().snapshot)) screen.actions.global(GlobalAction.BACK)
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
        val target = history.peek(id, current, redo) ?: return CommandResult(Outcome.FAILED, say = TextCommandTexts.textChanged(redo))
        var took = false
        try {
            val result = apply(screen, field, EditModel.between(current.text, target))
            took = result is ActionResult.Done
            return when (result) {
                is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = true)
                is ActionResult.Failed -> failed(result)
            }
        } finally {
            // The history moves only when the field took the state: a failed or cancelled edit leaves it as it was, and
            // one cancelled after the field took it moves it, so that the history still fits the field.
            if (!took) took = withContext(kotlinx.coroutines.NonCancellable) { screen.text.read(field.key)?.text == target.text }
            if (took) history.step(id, redo)
        }
    }

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
                EditFailure.LINE_BREAK -> CommandResult(Outcome.FAILED, say = TextCommandTexts.LINE_BREAK)
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

    private companion object {
        const val SEARCH_FIELD_MILLIS = 2_000L
        const val POLL_MILLIS = 100L
    }
}
