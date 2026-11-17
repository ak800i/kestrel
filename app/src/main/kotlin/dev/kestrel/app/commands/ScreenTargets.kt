package dev.kestrel.app.commands

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
import dev.kestrel.screen.model.WindowKind
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
    ): ScreenElement? {
        val session = screen() ?: return null
        return check(session.snapshots.current().snapshot, chosen, again, kind, strictness)
    }

    /** Where a gesture on [element] starts or ends in the current snapshot (see the companion's [gesturePoint]). */
    suspend fun gesturePoint(element: ScreenElement): ScreenPoint? {
        val session = screen() ?: return null
        return gesturePoint(session.snapshots.current().snapshot, element)
    }

    companion object {
        private const val MAX_DEPTH = 20

        /**
         * The candidates of [kind] in [snapshot], in the snapshot's order. The keyboard's window never gives candidates,
         * and an element that a window above covers whole is no candidate, because a finger could not reach it.
         */
        fun candidates(snapshot: ScreenSnapshot, kind: TargetKind): List<ScreenElement> {
            val shown = snapshot.elements.filter { reachable(snapshot, it) }
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
        ): ScreenElement? {
            snapshot.element(chosen.key)?.takeIf { same(chosen, it) && reachable(snapshot, it) }?.let { return it }
            if (again == null) return null
            val decided = label(snapshot, again.spoken, candidates(snapshot, kind), strictness) as? Resolution.One ?: return null
            return snapshot.element(decided.element.key)
        }

        /**
         * Where a swipe, a pinch or a drag on [element] starts or ends: its centre, or, when windows above it in [snapshot],
         * such as the keyboard or a popup, cover the centre, the point that a fallback tap would use (section 9.3). Null
         * when they cover every point of it, because the gesture would then act on the window above.
         */
        fun gesturePoint(snapshot: ScreenSnapshot, element: ScreenElement): ScreenPoint? {
            val window = snapshot.windows.firstOrNull { it.id == element.windowId }
            val covers = if (window == null) emptyList() else snapshot.windows.filter { it.layer > window.layer }.map { it.bounds }
            return GesturePaths.pointOn(element.bounds, covers)
        }

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
            val named = candidates.map { MatchCandidate(it.key, names(snapshot, it)) }
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
                kind == "RadioButton" -> "a radio button"
                element.checkable -> "a check box"
                element.scrollable -> "a list"
                element.clickable || element.longClickable -> "a button"
                else -> "a text"
            }
            return if (element.checkable) "$noun, ${if (element.checked) "on" else "off"}" else noun
        }

        /** What the owner can say for [element], for "Show commands for X", from its actions and its kind. */
        fun sayable(element: ScreenElement): List<String> = buildList {
            if (element.clickable || !element.scrollable) add("tap")
            if (element.longClickable || ElementAction.LONG_CLICK in element.actions) add("long press")
            if (element.checkable) addAll(listOf("check", "uncheck"))
            if (element.slider) addAll(listOf("increment", "decrement"))
            if (ElementAction.EXPAND in element.actions) add("expand")
            if (ElementAction.COLLAPSE in element.actions) add("collapse")
            if (ElementAction.DISMISS in element.actions) add("dismiss")
            if (element.scrollable) add("scroll")
            if (element.editable) add("start editing")
        }

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

        // A finger reaches an element outside the keyboard's window that no window above covers whole.
        private fun reachable(snapshot: ScreenSnapshot, element: ScreenElement): Boolean {
            val window = snapshot.windows.firstOrNull { it.id == element.windowId } ?: return true
            if (window.kind == WindowKind.INPUT_METHOD) return false
            return snapshot.windows.none { it.layer > window.layer && it.bounds.contains(element.bounds) }
        }

        private fun IntRect.contains(other: IntRect): Boolean =
            left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
    }
}
