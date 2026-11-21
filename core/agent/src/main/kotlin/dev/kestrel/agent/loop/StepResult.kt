package dev.kestrel.agent.loop

import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.agent.observe.TableEntry
import dev.kestrel.agent.tools.Tools
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSignature
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTitle
import dev.kestrel.screen.model.ScreenTree

/** What a step did, computed by code (design D6 of sub-project 5); [words] is its form in the result line. */
sealed interface Outcome {
    val words: String

    /** The form in the `Last:` line, which leaves out another screen's title. */
    val short: String get() = words

    /** [title] is the new observation's title, already redacted, which the loop fills in; it is quoted here. */
    data class AnotherScreen(val title: String?) : Outcome {
        override val words: String get() = "another screen" + (title?.let { ": " + ScreenText.quote(it, StepResult.LABEL_CUT) } ?: "")
        override val short: String get() = "another screen"

        override fun toString(): String = "AnotherScreen(title=${title?.length ?: 0} chars)"
    }

    data object TargetChanged : Outcome {
        override val words: String get() = "the target changed"
    }

    data object ChangedElsewhere : Outcome {
        override val words: String get() = "the screen changed elsewhere"
    }

    data object NothingChanged : Outcome {
        override val words: String get() = "nothing changed"
    }

    /** [reason] is Kestrel's sentence, which may quote the model's app name or an app's label; its own full stop goes. */
    data class Failed(val reason: String) : Outcome {
        override val words: String get() = "failed: ${reason.trimEnd('.')}"

        override fun toString(): String = "Failed(reason=${reason.length} chars)"
    }

    data object Uncertain : Outcome {
        override val words: String get() = "uncertain"
    }

    /** [reason] is Kestrel's sentence, which may quote the model's app name or an app's label; its own full stop goes. */
    data class Refused(val reason: String) : Outcome {
        override val words: String get() = "refused: ${reason.trimEnd('.')}"

        override fun toString(): String = "Refused(reason=${reason.length} chars)"
    }

    /** [what] is Kestrel's sentence with the field's text already redacted, quoted and escaped (design D14). */
    data class CheckFailed(val what: String) : Outcome {
        override val words: String get() = "check failed: $what"

        override fun toString(): String = "CheckFailed(what=${what.length} chars)"
    }

    data class DataOk(val count: Int) : Outcome {
        override val words: String get() = "ok (${items(count)})"
    }

    data object DataEmpty : Outcome {
        override val words: String get() = "empty"
    }

    data object PermissionRefused : Outcome {
        override val words: String get() = "permission refused"
    }

    data object Answered : Outcome {
        override val words: String get() = "answered"
    }

    data object NoAnswer : Outcome {
        override val words: String get() = "no answer"
    }

    companion object {
        fun items(count: Int): String = if (count == 1) "1 item" else "$count items"
    }
}

/**
 * D6's comparison of the snapshots before and after an action, over the app's windows only: SystemUI, the status bar,
 * the keyboard, Kestrel's own windows and the windows that [outcome]'s `hidden` names (agent-free apps and Play services,
 * which the model never sees) never change a result. Within one step a new front window is another screen, so the
 * window's ID counts here, while `Signature.sameScreen` leaves it out for routines (review 1-2 N1). The title rule leaves
 * out rule 3, which a scroll would change, and rule 4, because `Changes` knows no launcher label and a new window title
 * comes with a new window, which the ID already catches; `AnotherScreen`'s title is left for the loop to fill from the new
 * observation. The target itself is compared wherever it lies, also a Quick Settings tile in SystemUI's shade (review 3 M1).
 */
object Changes {
    private data class State(
        val text: String?,
        val description: String?,
        val stateDescription: String?,
        val checked: Boolean,
        val selected: Boolean,
        val enabled: Boolean,
        val focused: Boolean,
    )

    fun outcome(before: ScreenSnapshot, after: ScreenSnapshot, targetKey: String?, hidden: (String?) -> Boolean = { false }): Outcome {
        val beforeWindow = ScreenTree.frontWindow(before)
        val afterWindow = ScreenTree.frontWindow(after)
        if (beforeWindow?.packageName != afterWindow?.packageName || beforeWindow?.id != afterWindow?.id ||
            !ScreenSignature.of(before).sameScreen(ScreenSignature.of(after))
        ) {
            return Outcome.AnotherScreen(null)
        }
        val beforeTitle = beforeWindow?.let { ScreenTitle.of(before, it, null, withRule3 = false) }
        val afterTitle = afterWindow?.let { ScreenTitle.of(after, it, null, withRule3 = false) }
        if (beforeTitle != afterTitle) return Outcome.AnotherScreen(null)

        val old = states(before, hidden)
        val new = states(after, hidden)
        if (targetKey != null) {
            val index = before.elements.indexOfFirst { it.key == targetKey }
            if (index >= 0) {
                val afterIndex = after.elements.indexOfFirst { it.key == targetKey }
                if (afterIndex < 0 || state(before.elements[index]) != state(after.elements[afterIndex]) || subtree(before, index) != subtree(after, afterIndex)) {
                    return Outcome.TargetChanged
                }
            }
        }
        return if (old != new) Outcome.ChangedElsewhere else Outcome.NothingChanged
    }

    private fun appWindows(snapshot: ScreenSnapshot, hidden: (String?) -> Boolean): Set<Int> =
        ScreenTree.appWindows(snapshot).filterNot { hidden(it.packageName) }.map { it.id }.toSet()

    private fun state(element: ScreenElement) =
        State(element.text, element.contentDescription, element.stateDescription, element.checked, element.selected, element.enabled, element.focused)

    private fun states(snapshot: ScreenSnapshot, hidden: (String?) -> Boolean): Map<String, State> {
        val windows = appWindows(snapshot, hidden)
        return snapshot.elements.filter { it.windowId in windows }.associate { it.key to state(it) }
    }

    /** The descendants of element [index] with their states: rows that scrolled in, a field's new text. */
    private fun subtree(snapshot: ScreenSnapshot, index: Int): Map<String, State> =
        snapshot.elements.indices.filter { index in ScreenTree.ancestors(snapshot, it) }.associate { snapshot.elements[it].key to state(snapshot.elements[it]) }
}

/**
 * One step's result (design D6): the result line that opens the tool result, the one-line summary that replaces the
 * step later, and the `Last:` line of the next observation. The target is named by the label that the observation
 * showed ([TableEntry.shownLabel], already redacted, quoted and escaped), never by its number; [appLabel] is the front
 * app's launcher label, and [detail] is a data tool's short argument text, what an `open_*` tool opened, or the direction
 * of a `scroll` or `swipe`, already redacted; both are escaped here. The texts depend only on the step's data.
 */
data class StepResult(
    val step: Int,
    val tool: String,
    val outcome: Outcome,
    val target: TableEntry? = null,
    val appLabel: String? = null,
    val detail: String? = null,
) {
    /** "Result: tapped "Send" (Button) → another screen: "Ana Petrović"." */
    fun resultLine(): String = "Result: ${action()} → ${outcome.words}."

    /** "Step 3: tapped "Send" (Button) in Viber → another screen: "Ana Petrović"."; at most 160 characters. */
    fun summary(): String {
        val app = appLabel?.trim()?.takeIf { it.isNotEmpty() && tool !in DATA_TOOLS }?.let { " in " + ScreenText.escape(ScreenText.cut(it, LABEL_CUT)) }.orEmpty()
        val result = if (outcome is Outcome.DataOk) Outcome.items(outcome.count) else outcome.words
        val line = "Step $step: ${action()}$app → $result."
        if (line.length <= SUMMARY_LIMIT) return line
        // A cut inside quoted text closes the quote, so that the next line can never read as screen text.
        val cut = safeCut(line, SUMMARY_LIMIT - 2) + "…"
        return if (openQuote(cut)) "$cut\"" else cut
    }

    /** "tapped "Send" (Button) → another screen" */
    fun lastLine(): String = "${action()} → ${outcome.short}"

    private fun action(): String {
        val verb = VERBS[tool] ?: tool
        val named = target?.let { " " + cutShown(it.shownLabel) + " (" + (if (it.ocr) "OCR" else ScreenText.shortClass(it.className)) + ")" }.orEmpty()
        // The detail can hold an app's label or the model's words, so it is escaped like screen text (review 3 M3).
        val more = detail?.trim()?.takeIf { it.isNotEmpty() }?.let { " " + ScreenText.escape(it) }.orEmpty()
        return verb + named + more
    }

    override fun toString(): String = "StepResult(step=$step, tool=$tool, outcome=${outcome::class.simpleName}, target=${target != null})"

    companion object {
        const val SUMMARY_LIMIT = 160
        const val LABEL_CUT = 40

        private val VERBS = mapOf(
            Tools.TAP to "tapped",
            Tools.LONG_PRESS to "long-pressed",
            Tools.TYPE_TEXT to "typed into",
            Tools.SCROLL to "scrolled",
            Tools.SWIPE to "swiped",
            Tools.BACK to "went back",
            Tools.HOME to "went home",
            Tools.OPEN_APP to "opened",
            Tools.OPEN_URL to "opened a link in",
            Tools.OPEN_DEEP_LINK to "opened a link in",
            Tools.OPEN_SETTINGS to "opened settings:",
            Tools.WAIT to "waited",
            Tools.LOOK_CLOSER to "looked closer",
            Tools.ASK_USER to "asked the owner",
            Tools.SAY to "said something",
        )

        private val DATA_TOOLS = setOf(
            Tools.FIND_CONTACT, Tools.FIND_MEDIA, Tools.SHARE_MEDIA, Tools.LIST_EVENTS, Tools.CREATE_EVENT, Tools.SET_ALARM, Tools.SET_TIMER,
            Tools.LIST_NOTIFICATIONS, Tools.LIST_SMS, Tools.COMPOSE_SMS, Tools.LIST_CALLS, Tools.GET_LOCATION, Tools.MEDIA_CONTROL, Tools.NAVIGATE,
        )

        /** A shown label cut at 40 characters inside its quotes, never inside an escape. */
        private fun cutShown(shown: String): String {
            val open = shown.indexOf('"')
            if (open < 0 || !shown.endsWith('"') || open == shown.length - 1) return shown
            val inner = shown.substring(open + 1, shown.length - 1)
            if (inner.length <= LABEL_CUT) return shown
            return shown.substring(0, open + 1) + safeCut(inner, LABEL_CUT).trimEnd() + "…\""
        }

        /** Whether [text] ends inside a quote: an odd number of quotes that no backslash escapes. */
        private fun openQuote(text: String): Boolean {
            var open = false
            var index = 0
            while (index < text.length) {
                when (text[index]) {
                    '\\' -> index++
                    '"' -> open = !open
                }
                index++
            }
            return open
        }

        /** The first [limit] characters, without a lone backslash or half a surrogate pair at the end. */
        private fun safeCut(text: String, limit: Int): String {
            var end = limit.coerceAtMost(text.length)
            if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
            var slashes = 0
            while (end - slashes > 0 && text[end - slashes - 1] == '\\') slashes++
            if (slashes % 2 == 1) end--
            return text.substring(0, end)
        }
    }
}
