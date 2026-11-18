package dev.kestrel.commands

/**
 * The screen phrases of plan 3A (design spec, Appendix A: help, numbers and labels, touch and swipe gestures, the grid,
 * sliders). "Back" and "home" alone, "zoom in" and "zoom out" and every phrase with "open" except "open tutorial" belong
 * to [GrammarPhone].
 */
internal object GrammarScreen {
    private val DIRECTIONS = mapOf("up" to Direction.UP, "down" to Direction.DOWN, "left" to Direction.LEFT, "right" to Direction.RIGHT)
    private val EDGES = mapOf("top" to Edge.TOP, "bottom" to Edge.BOTTOM, "left" to Edge.LEFT, "right" to Edge.RIGHT)
    private val TAP_VERBS = listOf("tap", "click", "press", "touch", "hit")
    private val LONG_PRESS_VERBS = listOf("long press", "long tap", "long click", "press and hold", "touch and hold")
    private val PLACES = setOf("on", "in", "of")

    private val FIXED: Map<String, Command> = buildMap {
        for (phrase in listOf("show numbers", "show the numbers", "show all numbers", "numbers on")) put(phrase, Command.ShowNumbers)
        for (phrase in listOf("hide numbers", "hide the numbers", "remove numbers", "numbers off")) put(phrase, Command.HideNumbers)
        for (phrase in listOf("show labels", "show the labels", "show names")) put(phrase, Command.ShowLabels)
        for (phrase in listOf("hide labels", "hide the labels", "hide names")) put(phrase, Command.HideLabels)
        for (phrase in listOf("show grid", "show the grid", "grid on")) put(phrase, Command.ShowGrid)
        for (phrase in listOf("hide grid", "hide the grid", "close the grid", "grid off")) put(phrase, Command.HideGrid)
        for (phrase in listOf("more squares", "more grid squares")) put(phrase, Command.MoreSquares)
        for (phrase in listOf("fewer squares", "less squares", "fewer grid squares")) put(phrase, Command.FewerSquares)
        put("what can i say", Command.Help(HelpKind.SUMMARY))
        for (phrase in listOf("show all commands", "show commands", "show me the commands")) put(phrase, Command.Help(HelpKind.ALL))
        put("open tutorial", Command.Help(HelpKind.TUTORIAL))
        for (phrase in listOf("again", "repeat that", "do it again", "do that again")) put(phrase, Command.Again)
        for ((word, direction) in DIRECTIONS) {
            put("scroll $word", Command.Scroll(direction, null))
            put("swipe $word", Command.Swipe(direction, null))
        }
        for (phrase in listOf("scroll to top", "scroll to the top")) put(phrase, Command.ScrollToEnd(top = true, target = null))
        for (phrase in listOf("scroll to bottom", "scroll to the bottom")) put(phrase, Command.ScrollToEnd(top = false, target = null))
        for (phrase in listOf("swipe forwards", "swipe forward")) put(phrase, Command.SwipePage(forward = true))
        for (phrase in listOf("swipe backwards", "swipe backward")) put(phrase, Command.SwipePage(forward = false))
        for ((word, edge) in EDGES) {
            for (phrase in listOf("swipe from the $word edge", "swipe from $word edge", "swipe from the $word", "swipe from $word")) {
                put(phrase, Command.SwipeFromEdge(edge))
            }
        }
        put("pinch in", Command.Pinch(out = false, target = null))
        put("pinch out", Command.Pinch(out = true, target = null))
        for (phrase in listOf("increment", "increase")) put(phrase, Command.Slider(increase = true, target = null))
        for (phrase in listOf("decrement", "decrease")) put(phrase, Command.Slider(increase = false, target = null))
        put("check", Command.Check(null, on = true))
        // The recogniser may write "check" as "check it" (plan 3C's corpus run).
        put("check it", Command.Check(null, on = true))
        put("uncheck", Command.Check(null, on = false))
        // Not "turn on" or "turn off" alone: "turn off" is also Kestrel's own verb, whose name the recogniser may lose.
        // "Switch on" and "switch off" alone stay, because Appendix A lists them, although "Kestrel, switch off" that
        // loses the name switches the only switch on the screen too.
        put("switch on", Command.Switch(null, on = true))
        put("switch off", Command.Switch(null, on = false))
        put("expand", Command.Expand(null))
        put("collapse", Command.Collapse(null))
    }

    fun fixed(words: List<String>, text: String): Command? = FIXED[words.joinToString(" ")]

    fun targeted(words: List<String>, text: String): Command? {
        fun target(from: Int, to: Int = words.size) = TargetWords.read(words, text, from, to)

        // A verb whose target may be left out takes no polite words as its target: "check please" is left to the parser's
        // second try, which finds "check" (design D5). "Tap now" keeps "now", because a tap always names its target.
        fun optional(from: Int) = if (CommandParser.politeOnly(words, from, words.size)) null else target(from)

        for (verb in LONG_PRESS_VERBS) {
            val from = after(words, verb)
            if (from > 0) return target(from)?.let { Command.LongPress(it) }
        }
        for (verb in TAP_VERBS) {
            var from = after(words, verb)
            if (from < 0) continue
            if (from < words.size - 1 && words[from] == "on") from++
            return target(from)?.let { Command.Tap(it) }
        }
        after(words, "check").takeIf { it > 0 }?.let { from -> return optional(from)?.let { Command.Check(it, on = true) } }
        after(words, "uncheck").takeIf { it > 0 }?.let { from -> return optional(from)?.let { Command.Check(it, on = false) } }
        switch(words, text)?.let { return it }
        after(words, "expand").takeIf { it > 0 }?.let { from -> return optional(from)?.let { Command.Expand(it) } }
        after(words, "collapse").takeIf { it > 0 }?.let { from -> return optional(from)?.let { Command.Collapse(it) } }
        after(words, "dismiss").takeIf { it > 0 }?.let { from -> return target(from)?.let { Command.Dismiss(it) } }
        if (words.size >= 2 && words[0] == "drag") {
            // "Drag three to two" comes as "drag 3 to to": the place is the last "to".
            val doubled = words.size >= 4 && words[words.size - 1] == "to" && words[words.size - 2] == "to"
            val to = if (doubled) words.size - 2 else words.lastIndexOf("to")
            if (to in 2 until words.size - 1) {
                // Polite words alone are no place: "drag 3 to please" is left to the parser's second try, which reads
                // "drag 3 to", and "drag 3 to to please" the same way (design D5).
                if (CommandParser.politeOnly(words, to + 1, words.size)) return null
                val from = target(1, to) ?: return null
                return Command.Drag(from, target(to + 1))
            }
            // "Drag 3 to" stops before its place, so Kestrel asks "Drag it where?".
            val end = if (to >= 2 && to == words.size - 1) to else words.size
            return target(1, end)?.let { Command.Drag(it, null) }
        }
        scroll(words, text)?.let { return it }
        swipe(words, text)?.let { return it }
        pinch(words, text)?.let { return it }
        for ((verb, increase) in listOf("increment" to true, "increase" to true, "decrement" to false, "decrease" to false)) {
            val from = after(words, verb)
            if (from > 0) return optional(from)?.let { Command.Slider(increase, it) }
        }
        for (prefix in listOf("what is", "whats")) {
            val from = after(words, prefix)
            if (from > 0) return SpokenNumbers.number(words.subList(from, words.size), lone = true)?.let { Command.WhatIs(it) }
        }
        for (prefix in listOf("show commands for", "commands for", "what can i say for")) {
            val from = after(words, prefix)
            if (from > 0) return target(from)?.let { Command.CommandsFor(it) }
        }
        return null
    }

    // "switch on X", "turn off X", "switch X on", "turn X off". Polite words alone are no X, as in [targeted].
    private fun switch(words: List<String>, text: String): Command? {
        if (words.size < 3 || words[0] !in setOf("switch", "turn")) return null
        val state = mapOf("on" to true, "off" to false)
        fun target(from: Int, to: Int) =
            if (CommandParser.politeOnly(words, from, to)) null else TargetWords.read(words, text, from, to)
        state[words[1]]?.let { on -> return target(2, words.size)?.let { Command.Switch(it, on) } }
        state[words.last()]?.let { on -> return target(1, words.size - 1)?.let { Command.Switch(it, on) } }
        return null
    }

    // "scroll down on X", "scroll up in X", "scroll to the top of X".
    private fun scroll(words: List<String>, text: String): Command? {
        if (words.size < 3 || words[0] != "scroll") return null
        DIRECTIONS[words[1]]?.let { direction ->
            if (words[2] !in PLACES) return null
            return TargetWords.read(words, text, 3)?.let { Command.Scroll(direction, it) }
        }
        for (start in listOf(listOf("to", "the"), listOf("to"))) {
            val end = 1 + start.size
            if (words.size <= end + 1 || words.subList(1, end) != start) continue
            val top = when (words[end]) {
                "top" -> true
                "bottom" -> false
                else -> return null
            }
            if (words[end + 1] !in PLACES) return null
            return TargetWords.read(words, text, end + 2)?.let { Command.ScrollToEnd(top, it) }
        }
        return null
    }

    // "swipe up on X", "swipe X up".
    private fun swipe(words: List<String>, text: String): Command? {
        if (words.size < 3 || words[0] != "swipe") return null
        DIRECTIONS[words[1]]?.let { direction ->
            if (words[2] !in PLACES) return null
            return TargetWords.read(words, text, 3)?.let { Command.Swipe(direction, it) }
        }
        DIRECTIONS[words.last()]?.let { direction ->
            return TargetWords.read(words, text, 1, words.size - 1)?.let { Command.Swipe(direction, it) }
        }
        return null
    }

    // "pinch out 5", "pinch in on X". Without "on", only a number follows, so that "pinch out please" stays bare.
    private fun pinch(words: List<String>, text: String): Command? {
        if (words.size < 3 || words[0] != "pinch" || words[1] !in setOf("in", "out")) return null
        val out = words[1] == "out"
        if (words[2] in PLACES) return TargetWords.read(words, text, 3)?.let { Command.Pinch(out, it) }
        return SpokenNumbers.number(words.subList(2, words.size), lone = true)?.let { Command.Pinch(out, Target.Number(it)) }
    }

    /** When [words] start with the words of [phrase], the index after them; otherwise -1. */
    private fun after(words: List<String>, phrase: String): Int {
        val parts = phrase.split(" ")
        return if (words.size >= parts.size && words.subList(0, parts.size) == parts) parts.size else -1
    }
}
