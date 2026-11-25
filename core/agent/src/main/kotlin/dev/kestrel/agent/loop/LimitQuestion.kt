package dev.kestrel.agent.loop

import dev.kestrel.agent.Ledger
import dev.kestrel.agent.goal.Checklist
import java.util.Locale

/**
 * The question that a round asks at its first limit (design 7.4 of sub-project 7), built by code from the totals, the
 * checklist and the ledger, never by the model: "I've worked for 60 minutes on this. Done: 4 of 7 steps. Left: reply to
 * Mila, archive the thread. Continue?". Without a checklist it names the newest [LEDGER_LINES] of Kestrel's ledger lines.
 */
internal object LimitQuestion {
    const val LEDGER_LINES = 3
    const val NOTHING_YET = "I haven't changed anything yet."

    fun text(limit: Limit, replies: Int, workMillis: Long, credits: Double, checklist: Checklist?, ledger: List<String>): String {
        val used = when (limit) {
            Limit.REPLIES -> "I've used ${number(replies.toLong())} model replies on this."
            Limit.TIME -> {
                val minutes = workMillis / Limits.MINUTE_MILLIS
                "I've worked for ${number(minutes)} ${if (minutes == 1L) "minute" else "minutes"} on this."
            }
            Limit.CREDITS -> "This has used ${number(credits.toLong())} AI Credits."
        }
        return "$used ${Ledger.soFar(checklist, ledger, LEDGER_LINES) ?: NOTHING_YET} Continue?"
    }

    private fun number(value: Long): String = String.format(Locale.ENGLISH, "%,d", value)
}
