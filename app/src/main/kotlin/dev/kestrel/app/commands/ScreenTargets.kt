package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.SpokenNumbers
import dev.kestrel.commands.Target
import dev.kestrel.commands.text.LabelMatcher
import dev.kestrel.commands.text.MatchCandidate
import dev.kestrel.commands.text.MatchDecision
import dev.kestrel.commands.text.MatchRules
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Strictness
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.GesturePaths
import dev.kestrel.screen.act.ScreenPoint
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.overlay.Badge
import dev.kestrel.screen.overlay.Grid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a command acts on, which decides its candidates (design spec, sections 9.5 and 9.6). */
internal enum class TargetKind {
    /** Tap and long press: the actionable elements, and named elements with nothing actionable around or inside them. */
    TAP,

    /** A bare label or number: the actionable elements only. */
    BARE,
    CHECK,
    EXPAND,
    COLLAPSE,
    DISMISS,
    SCROLL,
    EDIT,

    /** "Increment" and "decrement": the sliders. */
    SLIDER,
}

/** The element that Kestrel chose, as it was then. [ScreenTargets.still] checks it again right before the action. */
internal data class ChosenElement(val key: String, val className: String?, val label: String?, val bounds: IntRect) {
    override fun toString() =
        "ChosenElement(key of ${key.length} characters, ${if (label == null) "no label" else "label of ${label.length} characters"})"
}

/** What a command sees: the snapshot, the numbers on the screen and the grid. */
internal data class ScreenView(val snapshot: ScreenSnapshot, val badges: List<Badge>, val grid: Grid?) {
    override fun toString() = "ScreenView(${snapshot.elements.size} elements, ${badges.size} numbers, grid=${grid != null})"
}

/** How a target was found. */
internal sealed interface Resolution {
    data class One(val element: ChosenElement) : Resolution

    /** A cell of the grid. */
    data class Cell(val number: Int, val bounds: IntRect) : Resolution

    /** Several close candidates, for "Which one?". */
    data class Several(val elements: List<ChosenElement>) : Resolution

    data object NotFound : Resolution

    /** A number while neither numbers nor the grid show, and no element is named with it. */
    data object NeedNumbers : Resolution

    data class NoSuchNumber(val number: Int) : Resolution

    /** A badge names an element that is no longer on the screen. */
    data object Changed : Resolution

    /** Kestrel's accessibility service is off. */
    data object NoScreen : Resolution
}

/**
 * Finds the elements that commands act on (design spec, sections 9.2, 9.4 and 9.6). The functions of the companion are
 * pure, so that JVM tests check the decisions; the others read the current snapshot.
 */
internal class ScreenTargets(private val screen: () -> ScreenSession?) {
    /** The current snapshot with the numbers and the grid on the screen, or null without the accessibility service. */
    suspend fun view(): ScreenView? {
        val session = screen() ?: return null
        val snapshot = session.snapshots.current().snapshot
        // The overlays change only on the main thread.
        return withContext(Dispatchers.Main) { ScreenView(snapshot, session.numberBadges, session.grid) }
    }

    /** Finds [target] among the candidates of [kind]. With [cells], a number names a grid cell while the grid shows. */
    suspend fun resolve(target: Target?, kind: TargetKind, strictness: Strictness = Strictness.NORMAL, cells: Boolean = false): Resolution {
        val view = view() ?: return Resolution.NoScreen
        return decide(view, target, kind, strictness, cells)
    }

    /**
     * The element that [chosen] names in the current snapshot, when it is still the element that Kestrel chose and a
     * finger can still reach it. Otherwise a label that Kestrel decided, given as [again], is decided once more on the new
     * screen among the candidates of [kind] (section 9.6).
     */
    suspend fun still(
        chosen: ChosenElement,
        again: Target.Label? = null,
        kind: TargetKind = TargetKind.TAP,
        strictness: Strictness = Strictness.NORMAL,
    ): ScreenElement? = (checked(chosen, again, kind, strictness) as? Checked.Found)?.element

    /**
     * As [still], and tells a chosen element that windows above now cover whole from one that changed, and gives the
     * snapshot that the check read, so that a gesture's point comes from the same snapshot as its element.
     */
    suspend fun checked(
        chosen: ChosenElement,
        again: Target.Label? = null,
        kind: TargetKind = TargetKind.TAP,
        strictness: Strictness = Strictness.NORMAL,
    ): Checked {
        val session = screen() ?: return Checked.Changed
        return checkIn(session.snapshots.current().snapshot, chosen, again, kind, strictness)
    }

    companion object {
        private const val MAX_DEPTH = 20

        /**
         * The candidates of [kind] in [snapshot], in the snapshot's order. Only elements that a finger can reach are
         * candidates ([ScreenSnapshot.reachable]): the keyboard's window never gives candidates, and neither does an
         * element that a window above covers whole.
         */
        fun candidates(snapshot: ScreenSnapshot, kind: TargetKind): List<ScreenElement> {
            val shown = snapshot.elements.filter { snapshot.reachable(it) }
            return when (kind) {
                TargetKind.TAP -> {
                    val near = nearActionable(snapshot)
                    shown.filter { it.isActionable || (it.enabled && ownNames(it).isNotEmpty() && it.key !in near) }
                }
                TargetKind.BARE -> shown.filter { it.isActionable }
                TargetKind.CHECK -> shown.filter { it.enabled && it.checkable }
                // Both commands take a row in either state, so that one that is already open or closed gets "It's already
                // expanded." or "It's already collapsed." rather than "I can't find it".
                TargetKind.EXPAND, TargetKind.COLLAPSE ->
                    shown.filter { it.enabled && (ElementAction.EXPAND in it.actions || ElementAction.COLLAPSE in it.actions) }
                TargetKind.DISMISS -> shown.filter { it.enabled && ElementAction.DISMISS in it.actions }
                TargetKind.SCROLL -> shown.filter { it.enabled && it.scrollable }
                TargetKind.EDIT -> shown.filter { it.enabled && it.editable }
                TargetKind.SLIDER -> shown.filter { it.enabled && it.slider }
            }
        }

        /** The names of [element], or of its nearest ancestor that has names. A field's typed text is never a name. */
        fun names(snapshot: ScreenSnapshot, element: ScreenElement): List<String> {
            var current: ScreenElement? = element
            var depth = 0
            while (current != null && depth <= MAX_DEPTH) {
                val names = ownNames(current)
                if (names.isNotEmpty()) return names
                current = current.parent?.let { snapshot.elements.getOrNull(it) }
                depth++
            }
            return emptyList()
        }

        fun decide(view: ScreenView, target: Target?, kind: TargetKind, strictness: Strictness, cells: Boolean): Resolution {
            val candidates = candidates(view.snapshot, kind)
            return when (target) {
                null -> only(preferred(candidates, kind))
                is Target.Number -> number(view, target.number, candidates, cells)
                is Target.Label -> label(view.snapshot, target.spoken, candidates, strictness)
            }
        }

        fun chosen(element: ScreenElement) = ChosenElement(element.key, element.className, element.label, element.bounds)

        /**
         * The element that [chosen] names in [snapshot], when it is still the same (see [same]), lies outside the
         * keyboard's window and no window above covers it whole. Otherwise the label [again] decided once more among the
         * candidates of [kind] in [snapshot]; without [again], as for a number or an answer to "Which one?", null.
         */
        fun check(
            snapshot: ScreenSnapshot,
            chosen: ChosenElement,
            again: Target.Label?,
            kind: TargetKind,
            strictness: Strictness,
        ): ScreenElement? = (checkIn(snapshot, chosen, again, kind, strictness) as? Checked.Found)?.element

        /**
         * As [check], but an element that is still the same and that a finger cannot reach any more, because windows
         * above now cover it whole, is [Checked.Covered] and not decided once more: "The screen changed" would be untrue,
         * and saying the command again would give the same answer.
         */
        fun checkIn(
            snapshot: ScreenSnapshot,
            chosen: ChosenElement,
            again: Target.Label?,
            kind: TargetKind,
            strictness: Strictness,
        ): Checked {
            snapshot.element(chosen.key)?.takeIf { same(chosen, it) }?.let {
                return if (snapshot.reachable(it)) Checked.Found(it, snapshot) else Checked.Covered
            }
            if (again == null) return Checked.Changed
            val decided = label(snapshot, again.spoken, candidates(snapshot, kind), strictness) as? Resolution.One ?: return Checked.Changed
            return snapshot.element(decided.element.key)?.let { Checked.Found(it, snapshot) } ?: Checked.Changed
        }

        /**
         * Where a swipe, a pinch or a drag on [element] starts or ends: its centre, or, when windows above it in [snapshot],
         * such as the keyboard or a popup, cover the centre, the point that a fallback tap would use (section 9.3). Null
         * when they cover every point of it, because the gesture would then act on the window above.
         */
        fun gesturePoint(snapshot: ScreenSnapshot, element: ScreenElement): ScreenPoint? =
            GesturePaths.pointOn(element.bounds, snapshot.windowsAbove(element.windowId))

        /**
         * What the actions of `ScreenActions` check, on every snapshot that they act on, before they act on the element
         * that a key names: that it is still [element] (see [same]).
         */
        fun sameAs(element: ScreenElement): (ScreenElement) -> Boolean {
            val chosen = chosen(element)
            return { same(chosen, it) }
        }

        /**
         * Why "expand" ([expand]) or "collapse" cannot act on [element], or null when it can: an element that offers
         * neither action, such as a button that a number names, is refused, because a tap would do whatever it does.
         */
        fun cannotExpand(element: ScreenElement, expand: Boolean): String? =
            if (ElementAction.EXPAND in element.actions || ElementAction.COLLAPSE in element.actions) {
                null
            } else if (expand) {
                CommandTexts.CANT_EXPAND
            } else {
                CommandTexts.CANT_COLLAPSE
            }

        /**
         * Whether [command] names a number that may name another element by now: the owner began to say it at [spokeAt]
         * (in `SystemClock.uptimeMillis()`, null when it is not known), and the numbers or the grid changed at
         * [changedAt], after that (design spec, section 9.4).
         */
        fun numbersChanged(command: Command, spokeAt: Long?, changedAt: Long): Boolean =
            spokeAt != null && changedAt > spokeAt && SpokenNumbers.namesNumber(command)

        /**
         * The label that [act][ScreenCommands.act] and a bare label decide once more when their element has changed: a
         * label that was found at once. A number, a target that is no label, and an answer to "Which one?" ([found] is
         * then [Resolution.Several]) may name another element by now, so they give null (section 9.6).
         */
        fun again(target: Target?, found: Resolution): Target.Label? = (target as? Target.Label)?.takeIf { found is Resolution.One }

        /** Whether [element] is still [chosen]: the same class and label, and bounds that cover at least half the chosen ones. */
        fun same(chosen: ChosenElement, element: ScreenElement?): Boolean {
            if (element == null || element.className != chosen.className || element.label != chosen.label) return false
            val overlap = chosen.bounds.intersect(element.bounds) ?: return chosen.bounds.isEmpty && chosen.bounds == element.bounds
            return 2L * overlap.width * overlap.height >= chosen.bounds.width.toLong() * chosen.bounds.height
        }

        // Without a name, "expand" takes the rows that can expand and "collapse" the ones that can collapse; a row that
        // already is as asked is the target only when no row can, so that the bar can say so.
        private fun preferred(candidates: List<ScreenElement>, kind: TargetKind): List<ScreenElement> {
            val action = when (kind) {
                TargetKind.EXPAND -> ElementAction.EXPAND
                TargetKind.COLLAPSE -> ElementAction.COLLAPSE
                else -> return candidates
            }
            return candidates.filter { action in it.actions }.ifEmpty { candidates }
        }

        // Without a target: the only candidate, else the focused one.
        private fun only(candidates: List<ScreenElement>): Resolution {
            candidates.singleOrNull()?.let { return Resolution.One(chosen(it)) }
            candidates.singleOrNull { it.focused }?.let { return Resolution.One(chosen(it)) }
            if (candidates.isEmpty()) return Resolution.NotFound
            return Resolution.Several(candidates.take(MatchRules.MAX_AMBIGUOUS).map(::chosen))
        }

        private fun number(view: ScreenView, number: Int, candidates: List<ScreenElement>, cells: Boolean): Resolution {
            val grid = view.grid
            if (cells && grid != null) {
                return if (number in 1..grid.cellCount) Resolution.Cell(number, grid.cell(number)) else Resolution.NoSuchNumber(number)
            }
            if (view.badges.isNotEmpty()) {
                val badge = view.badges.firstOrNull { it.number == number } ?: return Resolution.NoSuchNumber(number)
                val element = view.snapshot.element(badge.key) ?: return Resolution.Changed
                return Resolution.One(ChosenElement(badge.key, element.className, element.label, badge.bounds))
            }
            // An actionable element named with its number, such as a key of the dialer or of a calculator (design D6).
            val named = candidates.filter { candidate ->
                candidate.isActionable && names(view.snapshot, candidate).any { Normalizer.normalize(it) == number.toString() }
            }
            return when (named.size) {
                0 -> Resolution.NeedNumbers
                1 -> Resolution.One(chosen(named[0]))
                else -> Resolution.Several(named.map(::chosen))
            }
        }

        private fun label(snapshot: ScreenSnapshot, spoken: String, candidates: List<ScreenElement>, strictness: Strictness): Resolution {
            val named = candidates.map { MatchCandidate(it.key, matchNames(snapshot, it)) }
            return when (val decision = LabelMatcher.decide(spoken, named, strictness)) {
                is MatchDecision.Found -> snapshot.element(decision.id)?.let { Resolution.One(chosen(it)) } ?: Resolution.NotFound
                is MatchDecision.Ambiguous -> Resolution.Several(decision.ids.mapNotNull { snapshot.element(it) }.map(::chosen))
                MatchDecision.NotFound -> Resolution.NotFound
            }
        }

        /** The class names of switches, which "What is N?" calls "a switch" rather than "a check box". */
        private val SWITCHES = setOf("Switch", "SwitchCompat", "SwitchMaterial", "MaterialSwitch", "ToggleButton")

        /** What [element] is, for "What is N?": "a button", or "a check box, on" with the state of an element that checks. */
        fun role(element: ScreenElement): String {
            val kind = element.className?.substringAfterLast('.').orEmpty()
            val noun = when {
                element.slider -> "a slider"
                element.editable -> "a text field"
                kind in SWITCHES -> "a switch"
                isRadioButton(element) -> "a radio button"
                element.checkable -> "a check box"
                element.scrollable -> "a list"
                element.clickable || element.longClickable -> "a button"
                else -> "a text"
            }
            return if (element.checkable) "$noun, ${if (element.checked) "on" else "off"}" else noun
        }

        /** Whether [element] is a radio button, which a tap turns on but never off. */
        fun isRadioButton(element: ScreenElement): Boolean =
            element.className?.substringAfterLast('.').orEmpty().endsWith("RadioButton")

        /**
         * Why "check" ([on]) or "uncheck" cannot change [element], or null when it can or already is as asked. Only another
         * radio button of its group turns a radio button off, so a tap on one that is on would change nothing.
         */
        fun cannotCheck(element: ScreenElement, on: Boolean): String? = when {
            !element.checkable -> CommandTexts.NOT_CHECKABLE
            !on && element.checked && isRadioButton(element) -> CommandTexts.CANT_UNCHECK_RADIO
            else -> null
        }

        /** What the owner can say for [element], for "Show commands for X", from its actions and its kind. */
        fun sayable(element: ScreenElement): List<String> = buildList {
            if (element.clickable || !element.scrollable) add("tap")
            if (element.longClickable || ElementAction.LONG_CLICK in element.actions) add("long press")
            if (element.checkable) addAll(if (isRadioButton(element)) listOf("check") else listOf("check", "uncheck"))
            if (element.slider) addAll(listOf("increment", "decrement"))
            if (ElementAction.EXPAND in element.actions) add("expand")
            if (ElementAction.COLLAPSE in element.actions) add("collapse")
            if (ElementAction.DISMISS in element.actions) add("dismiss")
            if (element.scrollable) add("scroll")
            if (element.editable) add("start editing")
        }

        // The title of an actionable element that has no text, description, hint or supplemental description of its own, and
        // so is named after the texts inside it: the text of the first element inside it that takes no touch. The snapshot
        // lists the elements inside an element right after it. A summary or a later text is no name of its own, so that a
        // bare "On" never taps the one row whose summary says it.
        private fun title(snapshot: ScreenSnapshot, element: ScreenElement): String? {
            if (!element.isActionable || element.text != null || element.contentDescription != null || element.hint != null ||
                element.supplementalDescription != null
            ) {
                return null
            }
            val index = snapshot.elements.indexOfFirst { it.key == element.key }
            if (index < 0) return null
            for (inner in index + 1 until snapshot.elements.size) {
                if (!inside(snapshot, inner, index)) return null
                val candidate = snapshot.elements[inner]
                if (!candidate.isActionable) candidate.text?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return null
        }

        // Whether the element at [index] lies inside the element at [ancestor], at most MAX_DEPTH levels down.
        private fun inside(snapshot: ScreenSnapshot, index: Int, ancestor: Int): Boolean {
            var parent = snapshot.elements[index].parent
            var depth = 0
            while (parent != null && depth <= MAX_DEPTH) {
                if (parent == ancestor) return true
                parent = snapshot.elements.getOrNull(parent)?.parent
                depth++
            }
            return false
        }

        // The names that a spoken label is matched with: the names of [element], and, for a row that is named after the texts
        // inside it, its title, so that a bare title taps a settings row (plan 4D; inv-commands-a71). Only the match of a
        // spoken label uses it; every other user of names() sees the names as before.
        private fun matchNames(snapshot: ScreenSnapshot, element: ScreenElement): List<String> =
            (names(snapshot, element) + listOfNotNull(title(snapshot, element))).distinct()

        private fun ownNames(element: ScreenElement): List<String> = listOfNotNull(
            element.label,
            element.text.takeUnless { element.editable },
            element.contentDescription,
            element.hint,
            element.supplementalDescription,
            element.suggestedName,
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        // The keys of the elements that have an actionable element around them or inside them.
        private fun nearActionable(snapshot: ScreenSnapshot): Set<String> {
            val near = HashSet<String>()
            for (element in snapshot.elements) {
                var ancestor = element.parent?.let { snapshot.elements.getOrNull(it) }
                var depth = 0
                while (ancestor != null && depth < MAX_DEPTH) {
                    if (element.isActionable) near += ancestor.key
                    if (ancestor.isActionable) near += element.key
                    ancestor = ancestor.parent?.let { snapshot.elements.getOrNull(it) }
                    depth++
                }
            }
            return near
        }
    }
}

/** What the check right before an action found (see [ScreenTargets.checkIn]). */
internal sealed interface Checked {
    /** The element to act on, in the [snapshot] that the check read. */
    data class Found(val element: ScreenElement, val snapshot: ScreenSnapshot) : Checked {
        override fun toString() = "Found(${snapshot.elements.size} elements)"
    }

    /** The chosen element is still there, but windows above now cover it whole. */
    data object Covered : Checked

    /** The chosen element has changed or gone, and no label decided another one. */
    data object Changed : Checked
}
