package dev.kestrel.eval.run

import kotlin.random.Random

/**
 * The run's marker (design D4 of sub-project 6; task-check.ps1's `New-RunMarker`): two different words of [WORDS], which
 * no earlier run of the task used. The words have no digits, because 4 to 8 digits near "code" would be redacted.
 */
class Markers(private val random: Random = Random.Default) {
    fun next(used: Collection<String>): String {
        repeat(TRIES) {
            val pair = WORDS.shuffled(random).take(2)
            val marker = "${pair[0]} ${pair[1]}"
            if (marker !in used) return marker
        }
        throw IllegalStateException("No unused marker is left.")
    }

    companion object {
        private const val TRIES = 100
        val WORDS = listOf(
            "maple", "heron", "cedar", "otter", "amber", "willow", "falcon", "birch", "coral", "lynx", "meadow", "pebble",
            "raven", "thistle", "walnut", "harbor", "juniper", "lantern", "orchid", "quartz",
        )
    }
}
