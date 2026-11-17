package dev.kestrel.commands

import dev.kestrel.commands.text.Normalizer

/** What a command acts on: the owner's words as heard, or a number of a badge or a grid cell (design spec, section 9.4). */
sealed interface Target {
    /** [spoken] keeps the recogniser's case, for "I can't find X". */
    data class Label(val spoken: String) : Target {
        override fun toString() = "Label(${spoken.length} characters)"
    }

    data class Number(val number: Int) : Target
}

enum class Direction { UP, DOWN, LEFT, RIGHT }

enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

enum class HelpKind { SUMMARY, ALL, TUTORIAL }

/**
 * An instant command (design spec, section 9.5, and Appendix A). The variants of the screen commands are nested here;
 * the text commands are [TextCommand]s and the phone commands [PhoneCommand]s. The model is frozen: plans 3B and 3C
 * change none of its three files.
 */
sealed interface Command {
    data class Tap(val target: Target) : Command

    data class LongPress(val target: Target) : Command

    /**
     * Speech that no rule takes, as the owner said it: a bare label, a bare number ([number] when the whole text is a
     * spoken number) or dictation. The executor decides which.
     */
    data class Bare(val text: String, val number: Int?) : Command {
        override fun toString() = "Bare(${text.length} characters, number=${number != null})"

        companion object {
            fun of(text: String): Bare = Bare(text, SpokenNumbers.number(Normalizer.words(text)))
        }
    }

    /** "Check" or "uncheck" ([on] false); without a target, the only checkable element or the focused one. */
    data class Check(val target: Target?, val on: Boolean) : Command

    data class Switch(val target: Target?, val on: Boolean) : Command

    data class Expand(val target: Target?) : Command

    data class Collapse(val target: Target?) : Command

    data class Dismiss(val target: Target) : Command

    /** "Drag X to Y"; without [to], Kestrel asks "Drag it where?". */
    data class Drag(val from: Target, val to: Target?) : Command

    /** [direction] is the content's direction, as in "scroll down". */
    data class Scroll(val direction: Direction, val target: Target?) : Command

    data class ScrollToEnd(val top: Boolean, val target: Target?) : Command

    /** "Swipe forwards" shows the next page of a horizontal list or pager. */
    data class SwipePage(val forward: Boolean) : Command

    data class SwipeFromEdge(val edge: Edge) : Command

    /** [direction] is the finger's direction. */
    data class Swipe(val direction: Direction, val target: Target?) : Command

    data class Pinch(val out: Boolean, val target: Target?) : Command

    data class Slider(val increase: Boolean, val target: Target?) : Command

    data object ShowNumbers : Command

    data object HideNumbers : Command

    data object ShowLabels : Command

    data object HideLabels : Command

    data object ShowGrid : Command

    data object HideGrid : Command

    data object MoreSquares : Command

    data object FewerSquares : Command

    data class WhatIs(val number: Int) : Command

    data class CommandsFor(val target: Target) : Command

    data class Help(val kind: HelpKind) : Command

    /** "Again": the Rules replace it with the last repeatable command; the executor never sees it. */
    data object Again : Command
}
