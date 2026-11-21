package dev.kestrel.commands.text

/** What the owner named: one item, several close ones for "Which one?", or none. */
sealed interface Pick<out T> {
    data class One<T>(val item: T) : Pick<T>

    data class Several<T>(val items: List<T>) : Pick<T>

    data object None : Pick<Nothing>
}

/**
 * The matcher of "open <app>" and of the agent's `open_app` (design D1 and D11 of sub-project 5), moved unchanged from
 * `:app`'s `AppCommands.kt` so that both share one rule (design spec, section 9.6).
 */
object NamePick {
    /** How many names "Which one?" offers at most. */
    const val MAX_CHOICES = 4

    /**
     * The item whose [name] the owner said in [spoken], with the matcher's normal limits. Among items with the same name,
     * the [preferred] ones stand for the others, as an app of the owner's profile does for the same app in the work
     * profile; two preferred items with the same name both stay, for "Which one?". A single near miss gets "Which one?"
     * too, with its one name.
     */
    fun <T> pick(spoken: String, items: List<T>, name: (T) -> String, preferred: (T) -> Boolean): Pick<T> {
        val distinct = items.filter { Normalizer.normalize(name(it)).isNotEmpty() }
            .groupBy { Normalizer.normalize(name(it)) }
            .values.flatMap { same -> same.filter(preferred).ifEmpty { same } }
        val candidates = distinct.mapIndexed { index, item -> MatchCandidate(index.toString(), listOf(name(item))) }
        return when (val decision = LabelMatcher.decide(spoken, candidates, Strictness.NORMAL)) {
            is MatchDecision.Found -> Pick.One(distinct[decision.id.toInt()])
            is MatchDecision.Ambiguous -> Pick.Several(decision.ids.take(MAX_CHOICES).map { distinct[it.toInt()] })
            MatchDecision.NotFound -> Pick.None
        }
    }
}
