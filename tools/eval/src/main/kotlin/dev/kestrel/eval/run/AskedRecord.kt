package dev.kestrel.eval.run

/**
 * The run's record of the task's questions and hand-overs (plan 6B-basefix T1), in their order, as the runner's reads of
 * agent-state met them. A question lasts from the read that first sees it open until a read sees it closed, or until an
 * answer to it reaches Kestrel, which closes it: so two questions of the same kind, one after the other, are two
 * entries, while an answer sent again to a question that is still open, after Kestrel refused the first, is one. A
 * question that opens and closes between two reads is not seen.
 *
 * An answer whose hook reply was lost may have reached Kestrel or not, so the next read decides: it reached Kestrel when
 * that read finds the question closed or the task ended, and it did not when the same question is still open, which the
 * runner then answers again. An answer that Kestrel refused is never recorded. The shutdown hook's thread may read the
 * record while the run writes it, so it is synchronized.
 */
class AskedRecord {
    private val entries = mutableListOf<Asked>()

    // The kind of the question that the last read saw open, and whether its entry says that the run left it open.
    private var open: String? = null
    private var keptOpen = false

    // The answer to the open question whose hook reply was lost.
    private var lost: String? = null

    /** The answers that reached Kestrel, and the questions that the run left open on purpose, in their order. */
    val asked: List<Asked>
        @Synchronized get() = entries.toList()

    /** A read of agent-state found a question of [kind] open, or none (null). */
    @Synchronized
    fun saw(kind: String?) {
        val reply = lost
        lost = null
        if (reply != null && kind != open) close(reply)
        if (kind != open) {
            open = kind
            keptOpen = false
        }
    }

    /** The run's [reply] to the open question reached Kestrel, which closes the question. */
    @Synchronized
    fun answered(reply: String) = close(reply)

    /** The hook's reply to the run's [reply] to the open question was lost: the next read or the task's end decides. */
    @Synchronized
    fun lost(reply: String) {
        lost = reply
    }

    /** The run leaves the open question unanswered on purpose, such as "Which number should I use?" (the owner's P1). */
    @Synchronized
    fun keepOpen() {
        val kind = checkNotNull(open) { "No question is open." }
        if (!keptOpen) entries.add(Asked(kind, null))
        keptOpen = true
    }

    /** The task ended, so an answer whose hook reply was lost had reached Kestrel. */
    @Synchronized
    fun ended() {
        lost?.let(::close)
        lost = null
    }

    /** The kinds of the questions and hand-overs whose answer by the run ended the task, each once. */
    @Synchronized
    fun cancelled(): List<String> = entries.filter { it.endsTask }.map { it.kind }.distinct()

    private fun close(reply: String) {
        entries.add(Asked(checkNotNull(open) { "No question is open." }, reply))
        open = null
        keptOpen = false
    }
}
