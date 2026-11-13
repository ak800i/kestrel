package dev.kestrel.screen.overlay

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind

/** Which elements get numbers, as in Voice Access (design spec, sections 9.4 and 9.8). */
enum class NumbersMode { ALL, UNLABELED }

/** A number on an element. */
data class Badge(val number: Int, val key: String, val bounds: IntRect)

object Numbering {
    /** Numbers the actionable elements, except the keyboard's keys, in reading order: top to bottom, then left to right. */
    fun forScreen(snapshot: ScreenSnapshot, mode: NumbersMode): List<Badge> {
        val keyboard = snapshot.windows.filter { it.kind == WindowKind.INPUT_METHOD }.map { it.id }.toSet()
        return number(
            snapshot.elements.filter { element ->
                element.isActionable && element.windowId !in keyboard && (mode == NumbersMode.ALL || element.label == null)
            },
        )
    }

    /** Numbers only [candidates], for "Which one?" (design spec, section 9.4). */
    fun forCandidates(candidates: List<ScreenElement>): List<Badge> = number(candidates)

    private fun number(elements: List<ScreenElement>): List<Badge> =
        elements.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .mapIndexed { index, element -> Badge(index + 1, element.key, element.bounds) }
}
