package dev.kestrel.app.agent

/**
 * What the debug hooks read of the newest [MAX] rounds, by the command that started each (plan 6B-basefix; design 14.4
 * of sub-project 7): the owner's touches that the watcher reported during the round, how many of them were signals
 * without a finger (branch B's, or a gesture of Kestrel's that a touch cancelled), and what the round's witness
 * observed. A release build reads none of it. toString prints only counts.
 */
internal class RoundEvidences {
    /** Command [command]'s round as the hooks read it. */
    data class Evidence(val touches: Int = 0, val signals: Int = 0, val witness: TaskEvidence = TaskEvidence())

    /** One round while it runs: its touch collector counts, and its witness observes. */
    class Round internal constructor(private val witness: TaskWitness) {
        @Volatile
        var touches = 0
            private set

        @Volatile
        var signals = 0
            private set

        fun touched(signal: Boolean) {
            touches++
            if (signal) signals++
        }

        fun evidence(): Evidence = Evidence(touches, signals, witness.evidence)
    }

    private val rounds = LinkedHashMap<Int, Round>()

    /** Command [command]'s round starts with [witness]; a resumed goal's next round has its own command. */
    @Synchronized
    fun start(command: Int, witness: TaskWitness): Round {
        val round = Round(witness)
        rounds.remove(command)
        rounds[command] = round
        while (rounds.size > MAX) rounds.remove(rounds.keys.first())
        return round
    }

    /** Command [command]'s round, among the newest [MAX], or null. */
    @Synchronized
    fun of(command: Int): Evidence? = rounds[command]?.evidence()

    override fun toString(): String = "RoundEvidences(${rounds.size})"

    companion object {
        const val MAX = 16
    }
}
