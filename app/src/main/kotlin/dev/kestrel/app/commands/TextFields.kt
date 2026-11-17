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

    /** A search field by its names (its hint among them) or its view ID, as `SearchView`'s box `search_src_text`. */
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
