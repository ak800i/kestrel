package dev.kestrel.eval.task

import java.util.Collections

/**
 * The elements of an app's screen, by their labels, matched as Kestrel's debug hooks match them: `screen-has` compares
 * words, with letter case and punctuation folded as Normalizer folds them (ScreenQuery), and `screen-tap` compares the
 * raw label, `contains` without letter case, and taps the first match whatever their number (AgentHookAnswers.tapMatches).
 */
object FakeScreen {
    fun words(text: String): List<String> = text.lowercase().replace(Regex("['\u2019\u2018\u02BC]"), "").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** The number of [labels] that screen-has counts for its extras. */
    fun has(labels: List<String>, extras: Map<String, String>): Int = labels.count { name ->
        val label = extras["label"]
        val contains = extras["contains"]
        (label != null || contains != null) &&
            (label == null || words(name) == words(label)) &&
            (contains == null || words(contains).let { wanted -> wanted.isNotEmpty() && Collections.indexOfSubList(words(name), wanted) >= 0 })
    }

    /** The indices of [labels] that screen-tap matches for its extras, in their order. */
    fun taps(labels: List<String>, extras: Map<String, String>): List<Int> = labels.indices.filter { index ->
        val contains = extras["contains"]
        if (contains != null) labels[index].contains(contains, ignoreCase = true) else labels[index] == extras["label"]
    }
}
