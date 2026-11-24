package dev.kestrel.agent

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
        fun sentence(lines: List<String>): String? {
            if (lines.isEmpty()) return null
            val joined = if (lines.size == 1) lines.single() else lines.dropLast(1).joinToString(", ") + " and " + lines.last()
            return "I $joined."
        }
    }
}
