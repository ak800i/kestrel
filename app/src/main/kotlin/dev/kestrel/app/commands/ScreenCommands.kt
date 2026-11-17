package dev.kestrel.app.commands

import dev.kestrel.app.settings.SettingsPage
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Direction
import dev.kestrel.commands.Edge
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.SpokenNumbers
import dev.kestrel.commands.Target
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Strictness
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GesturePaths
import dev.kestrel.screen.act.ScreenEdge
import dev.kestrel.screen.act.ScreenPoint
import dev.kestrel.screen.act.ScrollDirection
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.overlay.Grid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
            is Command.Check -> check(screen, command.target, command.on, conversation)
            is Command.Switch -> check(screen, command.target, command.on, conversation)
            is Command.Expand -> expandOrCollapse(screen, command.target, expand = true, conversation)
            is Command.Collapse -> expandOrCollapse(screen, command.target, expand = false, conversation)
            is Command.Dismiss -> act(screen, command.target, TargetKind.DISMISS, conversation) {
                screen.actions.dismiss(it.key)
            }
            is Command.Slider -> act(screen, command.target, TargetKind.SLIDER, conversation) {
                screen.actions.adjust(it.key, command.increase)
            }
            is Command.Scroll -> scroll(screen, command, conversation)
            is Command.ScrollToEnd -> scrollToEnd(screen, command, conversation)
            is Command.SwipePage -> swipePage(screen, command.forward)
            is Command.SwipeFromEdge -> swipeFromEdge(screen, command.edge)
            is Command.Swipe -> swipe(screen, command, conversation)
            is Command.Pinch -> pinch(screen, command, conversation)
            is Command.Drag -> drag(screen, command, conversation)
            Command.ShowNumbers -> shown { screen.showNumbers(environment.options.value.numbers) }
            Command.HideNumbers -> shown { screen.hideNumbers() }
            Command.ShowLabels -> shown { screen.showLabels() }
            Command.HideLabels -> shown { screen.hideLabels() }
            Command.ShowGrid -> shown { screen.showGrid() }
            Command.HideGrid -> shown { screen.hideGrid() }
            Command.MoreSquares -> squares(screen) { it.more() }
            Command.FewerSquares -> squares(screen) { it.fewer() }
            is Command.WhatIs -> whatIs(screen, command.number)
            is Command.CommandsFor -> commandsFor(screen, command.target, conversation)
            is Command.Help -> help()
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
        val element = targets.still(chosen, ScreenTargets.again(target, found), kind) ?: return changed()
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

    internal sealed interface Asked {
        data class Answer(val text: String?) : Asked {
            override fun toString() = if (text == null) "Answer(no answer)" else "Answer(${text.length} characters)"
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
        // With "require verbs" on, a bare label or number never taps (design spec, section 9.5).
        if (environment.options.value.requireVerbs) return CommandResult(Outcome.IGNORED)
        val number = command.number
        // A bare "okay" is conversation far more often than an answer to a dialog; "tap OK" still taps an "OK" button.
        if (number == null && Normalizer.words(command.text).all { it == "ok" }) return CommandResult(Outcome.IGNORED)
        val target = if (number != null) Target.Number(number) else Target.Label(command.text)
        val found = if (number != null) {
            targets.resolve(target, TargetKind.BARE, cells = true)
        } else {
            targets.resolve(target, TargetKind.BARE, Strictness.STRICT)
        }
        return when (found) {
            is Resolution.Cell -> done(tapAt(screen, found.bounds))
            is Resolution.One -> {
                // As in act: a clear label is decided once more, as strictly, when its element has changed (section 9.6).
                val again = ScreenTargets.again(target, found)
                val element = targets.still(found.element, again, TargetKind.BARE, Strictness.STRICT) ?: return changed()
                done(screen.actions.tap(element.key))
            }
            else -> CommandResult(Outcome.IGNORED)
        }
    }

    /**
     * "Check", "uncheck", "switch on" and "switch off" (design spec, Appendix A): a click only when the element's checked
     * state differs from the one asked for; otherwise nothing is clicked, and the bar says that it is already on or off.
     */
    private suspend fun check(screen: ScreenSession, target: Target?, on: Boolean, conversation: CommandConversation): CommandResult {
        var already = false
        val result = act(screen, target, TargetKind.CHECK, conversation) { element ->
            // A number can name an element that does not check, such as a settings row with its switch inside it. A tap
            // would toggle that switch whatever its state. A radio button that is on stays on when it is tapped.
            val refused = ScreenTargets.cannotCheck(element, on)
            when {
                refused != null -> ActionResult.Failed(refused)
                element.checked == on -> {
                    already = true
                    // Nothing to click: the element is as the owner wants it.
                    ActionResult.Done(ActionMethod.ACTION)
                }
                else -> screen.actions.tap(element.key)
            }
        }
        if (!already || result.outcome != Outcome.DONE) return result
        return result.copy(show = if (on) CommandTexts.ALREADY_ON else CommandTexts.ALREADY_OFF)
    }

    /**
     * "Expand" and "collapse". The target may offer only the other action, whether the owner names it, gives its number or
     * names none: it already is as the owner asks, so nothing is done, and the bar says so; a tap would toggle it back.
     */
    private suspend fun expandOrCollapse(
        screen: ScreenSession,
        target: Target?,
        expand: Boolean,
        conversation: CommandConversation,
    ): CommandResult {
        var already = false
        val (wanted, other) = if (expand) ElementAction.EXPAND to ElementAction.COLLAPSE else ElementAction.COLLAPSE to ElementAction.EXPAND
        val result = act(screen, target, if (expand) TargetKind.EXPAND else TargetKind.COLLAPSE, conversation) { element ->
            when {
                wanted !in element.actions && other in element.actions -> {
                    already = true
                    ActionResult.Done(ActionMethod.ACTION)
                }
                expand -> screen.actions.expand(element.key)
                else -> screen.actions.collapse(element.key)
            }
        }
        if (!already || result.outcome != Outcome.DONE) return result
        return result.copy(show = if (expand) CommandTexts.ALREADY_EXPANDED else CommandTexts.ALREADY_COLLAPSED)
    }

    /** "Scroll down", and "scroll down on Messages" with a list that the owner names (design spec, Appendix A). */
    private suspend fun scroll(screen: ScreenSession, command: Command.Scroll, conversation: CommandConversation): CommandResult {
        val direction = scrollDirection(command.direction)
        val target = command.target ?: return done(screen.actions.scroll(direction))
        return act(screen, target, TargetKind.SCROLL, conversation) { screen.actions.scroll(direction, it.key) }
    }

    /**
     * "Scroll to top" and "scroll to bottom": scrolls until the list stops moving, that is until the action fails or the
     * screen's content stays the same, at most [ScrollToEnd.MAX_SCROLLS] times and for [ScrollToEnd.MILLIS].
     */
    private suspend fun scrollToEnd(
        screen: ScreenSession,
        command: Command.ScrollToEnd,
        conversation: CommandConversation,
    ): CommandResult {
        val direction = if (command.top) ScrollDirection.UP else ScrollDirection.DOWN
        val target = command.target ?: return done(scrollAllTheWay(screen, direction, null))
        return act(screen, target, TargetKind.SCROLL, conversation) { scrollAllTheWay(screen, direction, it) }
    }

    // The list that the owner named is checked again before each scroll after the first, because a key names a position
    // (section 9.2): a list that loads a new view or an app that navigates can give the key to another element.
    private suspend fun scrollAllTheWay(screen: ScreenSession, direction: ScrollDirection, list: ScreenElement?): ActionResult {
        val chosen = list?.let(ScreenTargets::chosen)
        return ScrollToEnd.scroll(
            scroll = { screen.actions.scroll(direction, list?.key) },
            content = { content(screen) },
            stillThere = { chosen == null || targets.still(chosen) != null },
        )
    }

    /** What moves when a list scrolls: the names on the screen and where they are. */
    private suspend fun content(screen: ScreenSession) = screen.snapshots.current().snapshot.elements.map { it.label to it.bounds }

    /**
     * "Swipe forwards" shows the next page of a list or pager that moves sideways, and "swipe backwards" the one before.
     * When nothing moves sideways the bar says so; any other failure, such as a touch that cancelled the swipe, shows its
     * own reason.
     */
    private suspend fun swipePage(screen: ScreenSession, forward: Boolean): CommandResult =
        when (val result = screen.actions.scroll(if (forward) ScrollDirection.RIGHT else ScrollDirection.LEFT)) {
            ActionResult.NOTHING_SCROLLS -> CommandResult(Outcome.FAILED, show = CommandTexts.NOTHING_SWIPES_SIDEWAYS)
            else -> done(result)
        }

    /** "Swipe from the bottom edge" and the other edges: from one pixel inside the edge, a third of the screen inwards. */
    private suspend fun swipeFromEdge(screen: ScreenSession, edge: Edge): CommandResult {
        val line = GesturePaths.fromEdge(screen.screenBounds, screenEdge(edge))
        return done(screen.actions.swipe(line.fromX, line.fromY, line.toX, line.toY))
    }

    /**
     * "Swipe up", "swipe up on 5" and "swipe 5 up": from the screen's centre, or from the centre of a grid's square or of
     * an element, a third of the screen in the direction that the owner names, which is the finger's direction. The owner
     * may name a sideways swipe across a list; spec 9.3's rule against them binds Kestrel's own choices. On an element
     * that the keyboard or a popup covers in part, the swipe starts where a fallback tap would go.
     */
    private suspend fun swipe(screen: ScreenSession, command: Command.Swipe, conversation: CommandConversation): CommandResult {
        val direction = scrollDirection(command.direction)
        val swipeFrom: suspend (Int, Int) -> ActionResult = { x, y ->
            val line = GesturePaths.limit(screen.screenBounds, x, y, direction)
            if (line == null) OFF_SCREEN else screen.actions.swipe(line.fromX, line.fromY, line.toX, line.toY)
        }
        val target = command.target ?: return screen.screenBounds.let { done(swipeFrom(it.centerX, it.centerY)) }
        return act(screen, target, TargetKind.TAP, conversation, onCell = { swipeFrom(it.centerX, it.centerY) }) { element ->
            targets.gesturePoint(element)?.let { swipeFrom(it.x, it.y) } ?: COVERED
        }
    }

    /**
     * "Pinch out" and "pinch in", around the screen's centre or the centre of a grid's square or of an element; on an
     * element that the keyboard or a popup covers in part, around the point where a fallback tap would go.
     */
    private suspend fun pinch(screen: ScreenSession, command: Command.Pinch, conversation: CommandConversation): CommandResult {
        val pinchAround: suspend (Int, Int) -> ActionResult = { x, y ->
            val line = GesturePaths.limit(screen.screenBounds, x, y, command.out)
            if (line == null) OFF_SCREEN else screen.actions.pinch(line.centerX, line.centerY, line.startSpan, line.endSpan)
        }
        val target = command.target ?: return screen.screenBounds.let { done(pinchAround(it.centerX, it.centerY)) }
        return act(screen, target, TargetKind.TAP, conversation, onCell = { pinchAround(it.centerX, it.centerY) }) { element ->
            targets.gesturePoint(element)?.let { pinchAround(it.x, it.y) } ?: COVERED
        }
    }

    /**
     * "Drag Drag me to Drop here" or "drag 5 to 9": presses and holds the centre of the first element or square and moves
     * to the second's; on an element that the keyboard or a popup covers in part, the point where a fallback tap would
     * go. "Drag 5" asks "Drag it where?" and takes the answer as the second.
     */
    private suspend fun drag(screen: ScreenSession, command: Command.Drag, conversation: CommandConversation): CommandResult {
        var source: ChosenElement? = null
        var square: IntRect? = null
        // This act only finds where the drag starts; the finger moves once the target is known.
        val found = act(
            screen,
            command.from,
            TargetKind.TAP,
            conversation,
            onCell = { cell ->
                square = cell
                FOUND
            },
        ) { element ->
            source = ScreenTargets.chosen(element)
            FOUND
        }
        if (source == null && square == null) return found
        val to = command.to ?: dragTarget(conversation.ask(CommandTexts.DRAG_WHERE) ?: return CommandResult(Outcome.CANCELLED))
        var moved = false
        // The question, or "Which one?" for the target, can take many seconds, so the start is checked again right before
        // the finger moves, as the target is (section 9.2). A square of the grid is a fixed point of the screen. The start
        // is not decided once more: a start that has changed ends the drag with "The screen changed" (section 9.6).
        val dragTo: suspend (Int, Int) -> ActionResult = dragTo@{ toX, toY ->
            val cell = square
            val from = if (cell != null) {
                ScreenPoint(cell.centerX, cell.centerY)
            } else {
                val start = source?.let { targets.still(it) }
                if (start == null) {
                    moved = true
                    return@dragTo FOUND
                }
                targets.gesturePoint(start) ?: return@dragTo COVERED
            }
            screen.actions.drag(from.x, from.y, toX, toY)
        }
        val result = act(screen, to, TargetKind.TAP, conversation, onCell = { dragTo(it.centerX, it.centerY) }) { element ->
            targets.gesturePoint(element)?.let { dragTo(it.x, it.y) } ?: COVERED
        }
        return if (moved) changed() else result
    }

    /** The answer to "Drag it where?": a number, a name, or either after "to". A lone "to" is the number 2. */
    private fun dragTarget(answer: String): Target {
        val rest = answer.replace(LEADING_TO, "").trim().ifEmpty { answer }
        return SpokenNumbers.answer(rest)?.let { Target.Number(it) } ?: Target.Label(rest)
    }

    private fun scrollDirection(direction: Direction): ScrollDirection = when (direction) {
        Direction.UP -> ScrollDirection.UP
        Direction.DOWN -> ScrollDirection.DOWN
        Direction.LEFT -> ScrollDirection.LEFT
        Direction.RIGHT -> ScrollDirection.RIGHT
    }

    private fun screenEdge(edge: Edge): ScreenEdge = when (edge) {
        Edge.TOP -> ScreenEdge.TOP
        Edge.BOTTOM -> ScreenEdge.BOTTOM
        Edge.LEFT -> ScreenEdge.LEFT
        Edge.RIGHT -> ScreenEdge.RIGHT
    }

    private companion object {
        /** "to" at the start of an answer, as in "to Drop here". */
        val LEADING_TO = Regex("^\\s*to\\b[\\s,]*", RegexOption.IGNORE_CASE)

        /** A gesture whose point lies outside the screen, or too close to its edge to move. */
        val OFF_SCREEN = ActionResult.Failed("The gesture would leave the screen")

        /** A gesture on an element that windows above, such as the keyboard or a popup, cover everywhere. */
        val COVERED = ActionResult.Failed("Other windows cover it.")

        /** What a step that only finds its element gives `act`: nothing has moved yet. */
        val FOUND = ActionResult.Done(ActionMethod.ACTION)
    }

    /** Shows or hides numbers, labels or the grid (design spec, section 9.4). */
    private suspend fun shown(change: suspend () -> Unit): CommandResult {
        change()
        return CommandResult(Outcome.DONE, repeatable = true)
    }

    /** "More squares" and "fewer squares" change the grid that shows. */
    private suspend fun squares(screen: ScreenSession, change: (Grid) -> Grid): CommandResult {
        // The overlays change only on the main thread.
        val grid = withContext(Dispatchers.Main) { screen.grid }
            ?: return CommandResult(Outcome.FAILED, say = CommandTexts.SHOW_GRID_FIRST)
        screen.showGrid(change(grid))
        return CommandResult(Outcome.DONE, repeatable = true)
    }

    /**
     * "What is 5?" says the name and the kind of the element with that number, or, while the grid shows, which square it
     * is (design spec, Appendix A).
     */
    private suspend fun whatIs(screen: ScreenSession, number: Int): CommandResult {
        val found = targets.resolve(Target.Number(number), TargetKind.TAP, cells = true)
        WhatIs.answer(found)?.let { return it }
        // WhatIs answers every resolution but one element.
        val chosen = (found as Resolution.One).element
        // One snapshot for the check and the name: an element's parent is an index into its own snapshot.
        val snapshot = screen.snapshots.current().snapshot
        val element = snapshot.element(chosen.key)?.takeIf { ScreenTargets.same(chosen, it) } ?: return changed()
        val answer = CommandTexts.whatIs(number, ScreenTargets.names(snapshot, element).firstOrNull(), ScreenTargets.role(element))
        return CommandResult(Outcome.DONE, say = answer, repeatable = true)
    }

    /** "Show commands for Send" says what the owner can say for that element, from its actions and its kind. */
    private suspend fun commandsFor(screen: ScreenSession, target: Target, conversation: CommandConversation): CommandResult {
        var phrases: List<String>? = null
        val found = act(screen, target, TargetKind.TAP, conversation) { element ->
            phrases = ScreenTargets.sayable(element)
            FOUND
        }
        val sayable = phrases ?: return found
        return found.copy(say = CommandTexts.youCanSay(sayable))
    }

    /**
     * "What can I say?", "show all commands", "show commands" and "open tutorial" open the list of commands on the settings
     * page "Instant commands" and say a short summary (design spec, Appendix A). "Again" never repeats it.
     */
    private fun help(): CommandResult {
        environment.openSettings(SettingsPage.COMMANDS, null)
        return CommandResult(Outcome.DONE, say = CommandTexts.HELP)
    }

    private suspend fun tapAt(screen: ScreenSession, bounds: IntRect): ActionResult = screen.actions.tapAt(bounds.centerX, bounds.centerY)

    // The reasons of ScreenActions are fixed texts without the screen's words, which the bar may show.
    private fun done(result: ActionResult): CommandResult = when (result) {
        is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = true)
        is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = result.reason)
    }

    private fun changed() = CommandResult(Outcome.FAILED, say = CommandTexts.SCREEN_CHANGED)
}

/**
 * The answers of "What is N?" that need no element, apart from the screen so that JVM tests check them (design spec,
 * Appendix A). Each failure has its own text, shown or spoken as `ScreenCommands.act` does.
 */
internal object WhatIs {
    /** The answer for [found], or null when [found] is one element, whose name and kind the answer needs. */
    fun answer(found: Resolution): CommandResult? = when (found) {
        is Resolution.One -> null
        is Resolution.Cell -> CommandResult(Outcome.DONE, say = CommandTexts.square(found.number), repeatable = true)
        is Resolution.NoSuchNumber -> CommandResult(Outcome.FAILED, show = CommandTexts.noNumber(found.number))
        Resolution.Changed -> CommandResult(Outcome.FAILED, say = CommandTexts.SCREEN_CHANGED)
        Resolution.NoScreen -> CommandResult(Outcome.FAILED, say = CommandTexts.SERVICE_OFF)
        // Without numbers on the screen, and also when several elements are named with the number: numbers tell them apart.
        Resolution.NeedNumbers, is Resolution.Several, Resolution.NotFound ->
            CommandResult(Outcome.FAILED, say = CommandTexts.SHOW_NUMBERS_FIRST)
    }
}

/** The loop of "scroll to top" and "scroll to bottom", apart from the screen so that JVM tests check it. */
internal object ScrollToEnd {
    const val MAX_SCROLLS = 30
    const val MILLIS = 15_000L

    /**
     * Calls [scroll] until it fails, until [content] stays the same after a scroll, or until [stillThere] says before a
     * scroll after the first that the list has gone, at most [MAX_SCROLLS] times and for [MILLIS]. Gives the first
     * scroll's failure, or else the last scroll that was done.
     */
    suspend fun scroll(
        scroll: suspend () -> ActionResult,
        content: suspend () -> Any?,
        stillThere: suspend () -> Boolean,
    ): ActionResult {
        var before = content()
        var last = scroll()
        if (last is ActionResult.Failed) return last
        withTimeoutOrNull(MILLIS) {
            repeat(MAX_SCROLLS - 1) {
                val now = content()
                if (now == before || !stillThere()) return@withTimeoutOrNull
                before = now
                val next = scroll()
                if (next is ActionResult.Failed) return@withTimeoutOrNull
                last = next
            }
        }
        return last
    }
}
