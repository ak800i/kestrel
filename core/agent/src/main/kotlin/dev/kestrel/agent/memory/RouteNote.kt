package dev.kestrel.agent.memory

import kotlinx.serialization.Serializable

/** One step of a route note's path: a fixed verb and, except for `back`, the label of the element it acts on. */
data class RouteStep(val verb: String, val label: String?) {
    override fun toString(): String = "RouteStep(verb=$verb, labelLength=${label?.length})"
}

/**
 * A route note (design D22; r61): a short path of fixed verbs and quoted labels, such as
 * `open "Chats" → tap "New chat" → tap "Search"`, kept per app [packageName] with the app's [version] and the phone's
 * [language] as data. A note goes into prompts only once two verified tasks proposed the same path ([active]), and two
 * [strikes] remove it. Its labels are screen content, so toString prints only counts (D32 item 42).
 */
@Serializable
data class RouteNote(
    val packageName: String,
    val path: String,
    val version: String? = null,
    val language: String? = null,
    val proposals: Int = 1,
    val strikes: Int = 0,
    val created: Long = 0,
) {
    val active: Boolean get() = proposals >= 2

    override fun toString(): String =
        "RouteNote(packageLength=${packageName.length}, pathLength=${path.length}, proposals=$proposals, strikes=$strikes)"

    companion object {
        val VERBS = listOf("long press", "type into", "open", "tap", "scroll", "back")
        const val MAX_STEPS = 8
        const val MAX_LABEL = 40
        const val MAX_LENGTH = 200
        private const val ARROW = " → "
        private val STEP = Regex("""^(long press|type into|open|tap|scroll)\s+"([^"\n]{1,40})"$|^(back)$""")

        /** The steps of [text], or null when it is not a path of the fixed verbs with quoted labels. */
        fun parse(text: String): List<RouteStep>? {
            if (text.isBlank() || text.length > MAX_LENGTH) return null
            val parts = text.trim().split(Regex("""\s*(?:→|->)\s*"""))
            if (parts.size > MAX_STEPS) return null
            return parts.map { part ->
                val match = STEP.matchEntire(part.trim()) ?: return null
                if (match.groupValues[3].isNotEmpty()) RouteStep("back", null)
                else RouteStep(match.groupValues[1], match.groupValues[2].trim().ifEmpty { return null })
            }
        }

        /** The one written form of [steps], which two tasks must both propose before the note counts. */
        fun format(steps: List<RouteStep>): String =
            steps.joinToString(ARROW) { if (it.label == null) it.verb else "${it.verb} \"${it.label}\"" }
    }
}
