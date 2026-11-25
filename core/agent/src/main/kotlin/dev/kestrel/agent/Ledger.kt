package dev.kestrel.agent

import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistText

/**
 * What a task did that the owner can see, in Kestrel's words, written by code from each step's data and never by the
 * model (design D24): "opened Viber", "typed the message", "sent the message", "turned on "Wi-Fi hotspot"". A cancelled,
 * declined or failed task's report names these lines (spec 14.5). The lines hold labels from the screen, which the
 * observation redacted, so toString prints only the count (D25).
 */
class Ledger {
    private val entries = mutableListOf<String>()

    val lines: List<String> get() = entries.toList()

    val isEmpty: Boolean get() = entries.isEmpty()

    fun add(line: String) {
        if (entries.lastOrNull() != line) entries += line
    }

    override fun toString(): String = "Ledger(lines=${entries.size})"

    companion object {
        /** The owner's sentence for [lines]: "I opened Viber, typed the message and scrolled down."; null for none (D24). */
        fun sentence(lines: List<String>): String? = lines.takeIf { it.isNotEmpty() }?.let { "I ${SpokenList.of(it)}." }

        /**
         * What a run has done so far, for the owner's ears (D24; design 7.4 of sub-project 7): the [checklist]'s progress
         * when the model kept one, else the sentence of the ledger's [lines], of only the [newest] of them when it holds
         * more ("Most recently, I …"); null when there is neither.
         */
        fun soFar(checklist: Checklist?, lines: List<String>, newest: Int = Int.MAX_VALUE): String? {
            checklist?.let { return ChecklistText.progress(it) }
            val sentence = sentence(lines.takeLast(newest)) ?: return null
            return if (lines.size > newest) "Most recently, $sentence" else sentence
        }
    }
}

