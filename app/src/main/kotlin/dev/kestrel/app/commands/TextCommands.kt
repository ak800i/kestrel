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
import kotlinx.coroutines.NonCancellable
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
    // built and what Kestrel's input method holds, never the screen (design D2). A password that already shows has lost
    // its node's flag, but its input keeps the password's type.
    override fun privateInput(): Boolean {
        val screen = environment.screen() ?: return false
        val snapshot = screen.snapshots.latest()?.snapshot ?: return false
        val field = TextFields.focused(snapshot) ?: return false
        return editing.hides(field, screen.text.typingPassword(field.packageName))
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
        val state = focusedState(screen, field) ?: return gone()
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
        var wentIn = false
        try {
            for ((index, part) in parts.withIndex()) {
                val result = when (part) {
                    is DictationPart.Text -> if (index == 0) apply(screen, field, part.change) else atCursor(screen, field, part.change.replacement)
                    DictationPart.NewLine -> screen.text.newLine(field.key)
                }
                if (result is ActionResult.Failed) return failed(result)
                wentIn = true
            }
            return CommandResult(Outcome.DONE, repeatable = true)
        } finally {
            // What went in before a failure, or before "stop" cancelled the command between two parts, can still be undone.
            // While the first part never went in, as when the text changed while the field connected, the field shows
            // no edit of Kestrel's, and a record would let "undo" take back the app's change.
            val first = when (val part = parts.first()) {
                is DictationPart.Text -> part.change.applyTo(before.text)
                DictationPart.NewLine -> lineBreak(before)
            }
            withContext(NonCancellable) { record(screen, field, before) { wentIn || sameText(it, first) } }
        }
    }

    /** A phone number replaces the selection as it is, without the spaces and capitals of dictation. */
    private suspend fun typeNumber(screen: ScreenSession, field: ScreenElement, spoken: String, state: FieldState): CommandResult {
        val number = TextFields.phoneNumber(spoken) ?: return CommandResult(Outcome.FAILED, say = TextCommandTexts.PHONE_FIELD)
        val end = state.selectionStart + number.length
        val before = state.toTextState()
        // The field may format the number, so any change after a cancel counts as the edit.
        val result = recorded(screen, field, before, took = { it != before.text }) {
            screen.text.replace(field.key, state.selectionStart, state.selectionEnd, number, end, end)
        }
        if (result is ActionResult.Failed) return failed(result)
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
                if (it.editable) screen.actions.tap(it.key, ScreenTargets.sameAs(it)) else ActionResult.Failed(TextCommandTexts.NOT_A_TEXT_FIELD)
            }
            if (result.outcome == Outcome.DONE) editing.resume()
            return result
        }
        val field = TextFields.toStart(screen.snapshots.current().snapshot)
            ?: return CommandResult(Outcome.FAILED, say = TextCommandTexts.NO_TEXT_FIELD)
        tap(screen, field)?.let { return it }
        editing.resume()
        return CommandResult(Outcome.DONE, repeatable = true)
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
        val state = focusedState(screen, field) ?: return gone()
        if (editing.password(field, state.password)) return CommandResult(Outcome.FAILED, say = TextCommandTexts.PASSWORD_FIELD)
        val before = state.toTextState()
        val result = recorded(screen, field, before, took = { sameText(it, lineBreak(before)) }) { screen.text.newLine(field.key) }
        if (result is ActionResult.Failed) return failed(result)
        return CommandResult(Outcome.DONE, repeatable = true)
    }

    /**
     * "Undo" puts back the text from before Kestrel's last edit of the field, and "redo" the text after it, but only while
     * the field still shows what Kestrel left (design spec, section 9.7).
     */
    private suspend fun undo(screen: ScreenSession, field: ScreenElement, redo: Boolean): CommandResult {
        val state = focusedState(screen, field) ?: return gone()
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
            if (!took) took = withContext(NonCancellable) { screen.text.read(field.key)?.text == target.text }
            if (took) history.step(id, redo)
        }
    }

    /** The editing commands, planned by the editing model on the field's text and selection. */
    private suspend fun edit(screen: ScreenSession, field: ScreenElement, command: TextCommand): CommandResult {
        val state = focusedState(screen, field) ?: return gone()
        if (editing.password(field, state.password)) return passwordEdit(screen, field, command, state)
        val before = state.toTextState()
        return when (val step = EditModel.plan(command, before)) {
            is EditStep.Change -> {
                val after = step.applyTo(before.text)
                when (val result = recorded(screen, field, before, took = { sameText(it, after) }) { apply(screen, field, step) }) {
                    is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = true)
                    is ActionResult.Failed -> failed(result)
                }
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
        val result = if (kind == ClipboardKind.COPY) {
            screen.text.clipboard(field.key, kind)
        } else {
            recorded(screen, field, before, took = { it != before.text }) { screen.text.clipboard(field.key, kind) }
        }
        if (result == TextEditor.SELECT_FIRST) return CommandResult(Outcome.FAILED, say = TextEditor.SELECT_FIRST.reason)
        if (result is ActionResult.Failed) return failed(result)
        return CommandResult(Outcome.DONE, repeatable = true)
    }

    /** "Show keyboard" taps the focused field; "hide keyboard" goes back, but only while the keyboard shows. */
    private suspend fun keyboard(screen: ScreenSession, show: Boolean): CommandResult {
        if (show) {
            val field = TextFields.focused(screen.snapshots.current().snapshot)
                ?: return CommandResult(Outcome.FAILED, say = TextCommandTexts.NO_FIELD)
            tap(screen, field)?.let { return it }
            return CommandResult(Outcome.DONE, repeatable = true)
        }
        // Read on the live screen right before the press, as for "stop editing": Back without the keyboard would leave
        // the screen.
        screen.snapshots.invalidate()
        if (!TextFields.keyboardShows(screen.snapshots.current().snapshot)) {
            return CommandResult(Outcome.FAILED, show = TextCommandTexts.KEYBOARD_NOT_SHOWING)
        }
        return when (val result = screen.actions.global(GlobalAction.BACK)) {
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
        tap(screen, toggle)?.let { return it }
        return CommandResult(Outcome.DONE)
    }

    /**
     * "Search for X": the focused field when it is a search field (by its input's search action, its names or its view
     * ID), else the first search field, else a "search" button, which Kestrel taps before it waits up to 3 s for a new
     * search field to get the focus. A password field, a phone number field, or a field with more lines and no search
     * action, such as a message, is never used, and neither is the field that had the focus before the tap. Kestrel
     * replaces the field's text with X, records it so that "undo" brings back what was there, and submits it. In a phone
     * app's window, a query with any digit is refused, because a number there could start a call.
     */
    private suspend fun search(screen: ScreenSession, query: String): CommandResult {
        val text = Dictation.phrase(query)
        // A line break goes only through the Enter key, and a search field has one line (design D8).
        if ('\n' in text) return CommandResult(Outcome.FAILED, say = TextCommandTexts.LINE_BREAK)
        val snapshot = screen.snapshots.current().snapshot
        // The controller's decision on Task 6: the emergency guard never sees "search for", so a phone app's search never
        // gets a number, which could start a call there; a name is searched for.
        val phoneApps = EmergencyGuard.phoneApps(environment.context)
        val numberInPhoneApp = CommandResult(Outcome.FAILED, say = PhoneCommandTexts.SEARCH_NAMES_ONLY)
        if (EmergencyGuard.refusesSearch(text, snapshot, null, phoneApps)) return numberInPhoneApp
        val focused = TextFields.focused(snapshot)
        val focusedSearches = focused?.let { screen.text.searches(it.key) } == true
        val field = TextFields.searchField(snapshot, focusedSearches) ?: run {
            val button = TextFields.searchButton(snapshot) ?: return CommandResult(Outcome.FAILED, say = TextCommandTexts.NO_SEARCH_FIELD)
            if (EmergencyGuard.refusesSearch(text, snapshot, button, phoneApps)) return numberInPhoneApp
            tap(screen, button)?.let { return it }
            openedSearch(screen, before = focused) ?: return CommandResult(Outcome.FAILED, say = TextCommandTexts.NO_SEARCH_FIELD)
        }
        if (EmergencyGuard.refusesSearch(text, screen.snapshots.current().snapshot, field, phoneApps)) return numberInPhoneApp
        var state = screen.text.read(field.key) ?: return gone()
        // Checked on the live field, also for one that a search button opened.
        val password = editing.password(field, state.password, TextFields.focused(screen.snapshots.current().snapshot))
        if (password || screen.text.phone(field.key) || (state.multiLine && !screen.text.searches(field.key))) {
            return CommandResult(Outcome.FAILED, say = TextCommandTexts.NO_SEARCH_FIELD)
        }
        var replaced = replaceAll(screen, field, state, text)
        if (replaced == TextEditor.CHANGED) {
            // The field has the focus now, and may show another text than before, as a browser's address bar shows the
            // whole address once it has the focus: Kestrel replaces what it shows now.
            state = screen.text.read(field.key) ?: return gone()
            replaced = replaceAll(screen, field, state, text)
        }
        if (replaced is ActionResult.Failed) return failed(replaced)
        return when (val submitted = screen.text.submit(field.key)) {
            is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = true)
            is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = submitted.reason)
        }
    }

    /** Replaces the whole text of [field], which showed [state], with [text], and records it. */
    private suspend fun replaceAll(screen: ScreenSession, field: ScreenElement, state: FieldState, text: String): ActionResult =
        recorded(screen, field, state.toTextState(), took = { sameText(it, text) }) {
            screen.text.replace(field.key, 0, state.text.length, text, text.length, text.length)
        }

    /**
     * Waits up to 3 s, after the tap on a search button, for a new search field to get the focus: never [before], the
     * field that had the focus before the tap, which the snapshot right after the tap may still show.
     */
    private suspend fun openedSearch(screen: ScreenSession, before: ScreenElement?): ScreenElement? =
        withTimeoutOrNull(SEARCH_FIELD_MILLIS) {
            var field: ScreenElement? = null
            while (field == null) {
                val snapshot = screen.snapshots.current().snapshot
                val searches = TextFields.focused(snapshot)?.let { screen.text.searches(it.key) } == true
                field = TextFields.openedSearchField(snapshot, before, searches)
                if (field == null) delay(POLL_MILLIS)
            }
            field
        }

    /**
     * Taps [element] once it is checked again on the current screen, as the screen commands tap (design spec, section
     * 9.6): null once it is tapped, otherwise the failure, with "The screen changed. Say it again." when it changed and
     * "Other windows cover it." when windows above now cover it.
     */
    private suspend fun tap(screen: ScreenSession, element: ScreenElement): CommandResult? {
        val found = when (val checked = environment.targets.checked(ScreenTargets.chosen(element))) {
            is Checked.Found -> checked.element
            Checked.Covered -> return CommandResult(Outcome.FAILED, show = ActionResult.COVERED.reason)
            Checked.Changed -> return CommandResult(Outcome.FAILED, say = CommandTexts.SCREEN_CHANGED)
        }
        return when (val result = screen.actions.tap(found.key, ScreenTargets.sameAs(found))) {
            is ActionResult.Done -> null
            ActionResult.CHANGED -> CommandResult(Outcome.FAILED, say = CommandTexts.SCREEN_CHANGED)
            is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = result.reason)
        }
    }

    /** Applies [change] to the field with the input method first and set-text as the fallback. */
    private suspend fun apply(screen: ScreenSession, field: ScreenElement, change: EditStep.Change): ActionResult =
        screen.text.replace(field.key, change.start, change.end, change.replacement, change.selectionStart, change.selectionEnd)

    /** Inserts [text] at the field's current selection, as a part of a dictation after a new line. */
    private suspend fun atCursor(screen: ScreenSession, field: ScreenElement, text: String): ActionResult {
        val state = focusedState(screen, field) ?: return ActionResult.Failed(TextCommandTexts.FIELD_GONE)
        val end = state.selectionStart + text.length
        return screen.text.replace(field.key, state.selectionStart, state.selectionEnd, text, end, end)
    }

    /**
     * The live state of [field], or null when it has gone or no longer has the focus: a key names a position, so after a
     * change of the screen it may name a look-alike field that Kestrel would otherwise focus and type into.
     */
    private suspend fun focusedState(screen: ScreenSession, field: ScreenElement): FieldState? {
        val state = screen.text.read(field.key) ?: return null
        if (state.focused) return state
        // A field that TextFields found inside the focused container gets the focus first (inv-oneui-a71, 4). A field that
        // the snapshot showed with the focus and has lost it may be a look-alike now, so it stays untouched.
        if (field.focused || !screen.text.focusField(field.key)) return null
        return screen.text.read(field.key)?.takeIf { it.focused }
    }

    /**
     * Runs [edit] and records Kestrel's edit of [field] from [before] once it is done. When "stop" cancels the command, the
     * edit is recorded only when [took] says that the field shows its result. A failed edit records nothing, so that
     * "undo" never takes back a change of the app's, as after `TextEditor.CHANGED`.
     */
    private suspend fun recorded(
        screen: ScreenSession,
        field: ScreenElement,
        before: TextState,
        took: (String) -> Boolean,
        edit: suspend () -> ActionResult,
    ): ActionResult {
        var result: ActionResult? = null
        try {
            return edit().also { result = it }
        } finally {
            when (result) {
                is ActionResult.Done -> withContext(NonCancellable) { record(screen, field, before) }
                null -> withContext(NonCancellable) { record(screen, field, before, took) }
                is ActionResult.Failed -> Unit
            }
        }
    }

    /** Records Kestrel's edit of [field] from [before] to what the field shows now, when [took] accepts that text. */
    private suspend fun record(screen: ScreenSession, field: ScreenElement, before: TextState, took: (String) -> Boolean = { true }) {
        val after = screen.text.read(field.key) ?: return
        if (after.text != before.text && took(after.text)) editing.history.record(editing.id(field), before, after.toTextState())
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

    /** [state]'s text with a line break in place of its selection, as the Enter key leaves it. */
    private fun lineBreak(state: TextState) = state.text.substring(0, state.selectionStart) + "\n" + state.text.substring(state.selectionEnd)

    // Web editors turn a space next to new words into a no-break space, or back, as they balance whitespace.
    private fun sameText(shown: String, expected: String) = shown.replace('\u00A0', ' ') == expected.replace('\u00A0', ' ')

    private companion object {
        const val SEARCH_FIELD_MILLIS = 3_000L
        const val POLL_MILLIS = 100L
    }
}
