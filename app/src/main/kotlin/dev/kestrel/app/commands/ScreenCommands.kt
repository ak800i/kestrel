package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.SpokenNumbers
import dev.kestrel.commands.Target
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Strictness
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

internal fun screenCommands(environment: CommandEnvironment): CommandPart = ScreenCommands(environment)

/** How "Which one?" ended: with the element that the owner chose, or with the command's result. */
internal sealed interface Choice {
    data class Chosen(val element: ChosenElement) : Choice

    data class Ends(val result: CommandResult) : Choice
}

/**
 * The screen commands (design spec, sections 9.2 to 9.6, and Appendix A): tap and long press by label, by number and on
 * the grid, bare labels and bare numbers.
 */
internal class ScreenCommands(private val environment: CommandEnvironment) : CommandPart {
    private val targets: ScreenTargets get() = environment.targets

    override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult? {
        val screen = environment.screen() ?: return null
        return when (command) {
            is Command.Tap -> act(screen, command.target, TargetKind.TAP, conversation, onCell = { tapAt(screen, it) }) {
                screen.actions.tap(it.key)
            }
            is Command.LongPress -> act(
                screen,
                command.target,
                TargetKind.TAP,
                conversation,
                onCell = { screen.actions.longPressAt(it.centerX, it.centerY) },
            ) { screen.actions.longPress(it.key) }
            is Command.Bare -> bare(screen, command)
            // Tasks 5 to 9 add their commands above this line.
            else -> null
        }
    }

    /**
     * Finds [target] among the candidates of [kind], asks "Which one?" when several fit, checks right before the action
     * that the chosen element is still the one that Kestrel chose, and runs [onElement] on it, or [onCell] on a grid cell
     * while the grid shows (without [onCell], a number names a badge). A label that nothing matches makes Kestrel say
     * "I can't find X" and show "Which one?" numbers on all actionable elements.
     */
    suspend fun act(
        screen: ScreenSession,
        target: Target?,
        kind: TargetKind,
        conversation: CommandConversation,
        onCell: (suspend (IntRect) -> ActionResult)? = null,
        onElement: suspend (ScreenElement) -> ActionResult,
    ): CommandResult {
        val found = targets.resolve(target, kind, cells = onCell != null)
        val chosen = when (found) {
            is Resolution.One -> found.element
            is Resolution.Cell -> return done(checkNotNull(onCell)(found.bounds))
            is Resolution.Several -> when (val choice = choose(screen, found.elements, conversation)) {
                is Choice.Chosen -> choice.element
                is Choice.Ends -> return choice.result
            }
            Resolution.NotFound -> return notFound(screen, target)
            Resolution.NeedNumbers -> return CommandResult(Outcome.FAILED, say = CommandTexts.SHOW_NUMBERS_FIRST)
            is Resolution.NoSuchNumber -> return CommandResult(Outcome.FAILED, show = CommandTexts.noNumber(found.number))
            Resolution.Changed -> return changed()
            Resolution.NoScreen -> return CommandResult(Outcome.FAILED, say = CommandTexts.SERVICE_OFF)
        }
        // A key names a position (section 9.2). A label is decided once more on the new screen; a number or an answer may
        // name another element by now.
        val element = targets.still(chosen)
            ?: (if (target is Target.Label && found is Resolution.One) targets.resolve(target, kind) else null)
                ?.let { (it as? Resolution.One)?.element }?.let { targets.still(it) }
            ?: return changed()
        return done(onElement(element))
    }

    /**
     * Shows "Which one?" numbers on [choices] and asks "Which one?" (section 9.4). The answer is a number; an answer that
     * is none ends the command and is read as a new utterance, and the question ends when its numbers go because the
     * screen changed. The numbers go when the question ends.
     */
    suspend fun choose(screen: ScreenSession, choices: List<ChosenElement>, conversation: CommandConversation): Choice {
        try {
            screen.showWhichOne(choices.map { it.key })
            val badges = withContext(Dispatchers.Main) { screen.numberBadges }
            // None of the chosen elements is in the newest snapshot, so there is nothing to choose from.
            if (badges.isEmpty()) return Choice.Ends(changed())
            val answer = when (val asked = askWhileShown(screen, conversation)) {
                Asked.ScreenChanged -> return Choice.Ends(changed())
                is Asked.Answer -> asked.text ?: return Choice.Ends(CommandResult(Outcome.CANCELLED))
            }
            val number = SpokenNumbers.answer(answer) ?: return Choice.Ends(CommandResult(Outcome.CANCELLED, passOn = answer))
            val badge = badges.firstOrNull { it.number == number }
                ?: return Choice.Ends(CommandResult(Outcome.FAILED, show = CommandTexts.noNumber(number)))
            val element = choices.firstOrNull { it.key == badge.key } ?: return Choice.Ends(changed())
            return Choice.Chosen(element)
        } finally {
            withContext(NonCancellable) { screen.endWhichOne() }
        }
    }

    private sealed interface Asked {
        data class Answer(val text: String?) : Asked {
            override fun toString() = "Answer(${text?.length} characters)"
        }

        data object ScreenChanged : Asked
    }

    private suspend fun askWhileShown(screen: ScreenSession, conversation: CommandConversation): Asked = coroutineScope {
        val answer = async { Asked.Answer(conversation.ask(CommandTexts.WHICH_ONE)) }
        val gone = async {
            screen.whichOneShown.first { !it }
            Asked.ScreenChanged
        }
        select<Asked> {
            answer.onAwait { it }
            gone.onAwait { it }
        }.also {
            answer.cancel()
            gone.cancel()
        }
    }

    // "I can't find X", with "Which one?" numbers on all actionable elements, which stay until the screen changes, so that
    // "tap 5" works next.
    private suspend fun notFound(screen: ScreenSession, target: Target?): CommandResult {
        val view = targets.view()
        if (view != null) {
            val keys = ScreenTargets.candidates(view.snapshot, TargetKind.BARE).map { it.key }
            if (keys.isNotEmpty()) screen.showWhichOne(keys)
        }
        val spoken = (target as? Target.Label)?.spoken
        return CommandResult(Outcome.FAILED, say = if (spoken != null) CommandTexts.cantFind(spoken) else CommandTexts.NOTHING_HERE)
    }

    // Design D6: a bare number taps like "tap N" while numbers or the grid show, and otherwise only an element whose whole
    // name is that number; a bare label taps only a clear, strict match. Anything else is ignored without a word, because
    // bare speech may be conversation.
    private suspend fun bare(screen: ScreenSession, command: Command.Bare): CommandResult {
        val number = command.number
        // A bare "okay" is conversation far more often than an answer to a dialog; "tap OK" still taps an "OK" button.
        if (number == null && Normalizer.words(command.text).all { it == "ok" }) return CommandResult(Outcome.IGNORED)
        val found = if (number != null) {
            targets.resolve(Target.Number(number), TargetKind.BARE, cells = true)
        } else {
            targets.resolve(Target.Label(command.text), TargetKind.BARE, Strictness.STRICT)
        }
        return when (found) {
            is Resolution.Cell -> done(tapAt(screen, found.bounds))
            is Resolution.One -> {
                val element = targets.still(found.element) ?: return changed()
                done(screen.actions.tap(element.key))
            }
            else -> CommandResult(Outcome.IGNORED)
        }
    }

    private suspend fun tapAt(screen: ScreenSession, bounds: IntRect): ActionResult = screen.actions.tapAt(bounds.centerX, bounds.centerY)

    // The reasons of ScreenActions are fixed texts without the screen's words, which the bar may show.
    private fun done(result: ActionResult): CommandResult = when (result) {
        is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = true)
        is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = result.reason)
    }

    private fun changed() = CommandResult(Outcome.FAILED, say = CommandTexts.SCREEN_CHANGED)
}
