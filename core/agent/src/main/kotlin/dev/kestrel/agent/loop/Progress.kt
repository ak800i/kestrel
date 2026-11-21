package dev.kestrel.agent.loop

import dev.kestrel.screen.model.Signature

/**
 * Spec 11.7's progress, made exact (design D6; r61's rule that any success resets the count). A step makes progress when
 * its action's result is `another screen` or `the target changed`, or `the screen changed elsewhere` the first time on its
 * tool, target and screen; when a data tool returns a result that differs from every earlier result of the same tool; when
 * `ask_user` got an answer; when `look_closer` added elements; and when the owner finished a hand-over. Every other step
 * makes none, and [withoutProgress] counts those in a row. Invalid tool calls are counted apart (D7).
 */
class Progress {
    private val elsewhere = mutableSetOf<Triple<String, String?, Signature?>>()
    private val results = mutableMapOf<String, MutableSet<String>>()

    var withoutProgress = 0
        private set

    /**
     * Whether a step with [outcome] made progress, and counts it. [dataText] is a data tool's result text, [added] says
     * that `look_closer` added elements, and [ownerDidIt] that the owner finished a hand-over.
     */
    fun record(
        tool: String,
        outcome: Outcome?,
        targetKey: String? = null,
        signature: Signature? = null,
        dataText: String? = null,
        added: Boolean = false,
        ownerDidIt: Boolean = false,
    ): Boolean {
        val made = ownerDidIt || added || when (outcome) {
            is Outcome.AnotherScreen, Outcome.TargetChanged, Outcome.Answered -> true
            Outcome.ChangedElsewhere -> elsewhere.add(Triple(tool, targetKey, signature))
            is Outcome.DataOk, Outcome.DataEmpty -> dataText != null && results.getOrPut(tool) { mutableSetOf() }.add(dataText)
            else -> false
        }
        withoutProgress = if (made) 0 else withoutProgress + 1
        return made
    }

    /** After a switch of models or the owner's "keep trying", the count starts again (D7). */
    fun reset() {
        withoutProgress = 0
    }

    override fun toString(): String = "Progress(withoutProgress=$withoutProgress)"
}
