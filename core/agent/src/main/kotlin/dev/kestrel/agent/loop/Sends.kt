package dev.kestrel.agent.loop

import dev.kestrel.agent.Ledger
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.safety.Category
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.ShareFlow

/** A send that the owner confirmed (design D14), kept in memory for the completion check; never logged. */
internal data class ConfirmedSend(val recipients: List<String>, val content: String?, val share: Boolean) {
    override fun toString(): String = "ConfirmedSend(recipients=${recipients.size}, content=${content?.length}, share=$share)"
}

/**
 * What a run sent, shared and deleted after the owner's yes (design D14, D21 and D24): the report's "Sent to …" [lines],
 * its [recipients] and [shareItems], the [ledger]'s lines of what went and what was only tried, and the [confirmed] sends
 * whose evidence the completion check asks for, also a share whose chat the run never saw; and which share flow and chats
 * the owner's last yes for a share's tap named, so that it covers the following taps of that flow. It holds names from
 * the screen and the contacts, so toString prints only counts.
 */
internal class Sends(private val ledger: Ledger) {
    private val sentLines = mutableListOf<String>()
    private val sentTo = mutableListOf<String>()
    private val confirmedSends = mutableListOf<ConfirmedSend>()

    // The share flow, by its serial, and the recipients whose send the report already holds (D14, D24).
    private var countedShare: Pair<Int, List<String>>? = null

    // The shares whose taps the owner confirmed and which have not been sent yet, by their flows' serials (D24).
    private val pendingShares = mutableMapOf<Int, Confirmation>()

    // The share flow at the owner's last yes for a share's tap, by its serial, and the folded names of the chats that the
    // yes named (D14).
    private var confirmedSerial: Int? = null
    private var confirmedChats: Set<String> = emptySet()

    /** The report's sentences of what was sent: "Sent to Ana on Viber." */
    val lines: List<String> get() = sentLines.toList()

    /** Everyone that a send went to, in order. */
    val recipients: List<String> get() = sentTo.toList()

    /** How many photos and videos the run shared. */
    var shareItems = 0
        private set

    /** Every send that the owner confirmed, with the shares that were confirmed but not seen to be sent (D21). */
    val confirmed: List<ConfirmedSend> get() = confirmedSends + pendingShares.values.map { ConfirmedSend(it.recipients, it.content, share = true) }

    /** A share that was confirmed but never seen to be sent, reported as tried (D24). */
    val pendingLines: List<String>
        get() = pendingShares.values.map { "tried to share the items with ${it.recipients.joinToString(" and ")}, but I couldn't see that they were sent" }

    /**
     * Whether the owner's last yes covers [confirmation], a tap of share [flow] that sends to chats (D14): one yes covers
     * the following taps of that flow that send to the same chats, in any order; a new flow or other chats ask again.
     */
    fun covers(flow: ShareFlow, confirmation: Confirmation): Boolean =
        flow.serial == confirmedSerial && chats(confirmation.recipients) == confirmedChats

    /**
     * The owner said yes to [confirmation]: for a tap of share [flow] that sends to chats, the yes covers that flow's
     * following taps to the same chats; any other yes covers nothing (D14).
     */
    fun confirmed(flow: ShareFlow?, confirmation: Confirmation) {
        confirmedSerial = flow?.serial
        confirmedChats = if (flow != null) chats(confirmation.recipients) else emptySet()
    }

    /**
     * The screen changed after a yes, or a press after it may not have worked, so a later send of the share flow needs a
     * new yes (D14, D32 item 29).
     */
    fun forgetConfirmedFlow() {
        confirmedSerial = null
    }

    /**
     * A send that the owner confirmed and Kestrel pressed (D14, D24). A tap that an earlier yes [covered] is the same send
     * (a row and then the app's own Send), so it is counted once. A confirmation without recipients (an app's own Share
     * button, whose chat comes next) sends nothing yet.
     */
    fun sent(confirmation: Confirmation, share: Boolean, items: Int, covered: Boolean) {
        if (covered) return
        if (confirmation.category == Category.DELETING) ledger.add("deleted ${deleted(confirmation.content)}")
        if (confirmation.category != Category.SENDING || confirmation.recipients.isEmpty()) return
        confirmedSends += ConfirmedSend(confirmation.recipients, confirmation.content, share)
        sentTo += confirmation.recipients
        val to = confirmation.recipients.joinToString(" and ")
        val app = confirmation.appLabel?.let { " on $it" }.orEmpty()
        sentLines += "Sent to $to$app."
        if (share) {
            shareItems += items
            ledger.add("shared $items ${if (items == 1) "item" else "items"} with $to")
        } else {
            ledger.add("sent the message to $to")
        }
    }

    /**
     * A confirmed tap of share flow [flow] that worked (D14, D24). The share is sent when the flow ended with the tap
     * ([ended]: the app shows the chat that it went to, or it left the flow's apps), as after the app's own Send or a row
     * that sends at once; it is counted once per flow and recipients. A tap that only picked a chat leaves the share
     * pending: the completion check asks for its evidence, and the report says that it was tried, never sent.
     */
    fun shareTapped(flow: ShareFlow, confirmation: Confirmation, ended: Boolean) {
        if (confirmation.category != Category.SENDING || confirmation.recipients.isEmpty()) return
        if (!ended) {
            pendingShares[flow.serial] = confirmation
            return
        }
        pendingShares -= flow.serial
        val share = flow.serial to chats(confirmation.recipients).sorted()
        if (countedShare == share) return
        countedShare = share
        sent(confirmation, share = true, items = flow.count?.let { it.photos + it.videos } ?: 0, covered = false)
    }

    /** Takes back the pending share of [flow]: a tap that only opened an e-mail's compose screen sent nothing yet (D14). */
    fun forgetPendingShare(flow: ShareFlow) {
        pendingShares -= flow.serial
    }

    /**
     * A confirmed press that may not have worked (D32 item 29), in share [flow] when the press was a share's: it does not
     * count as done, so it adds no "Sent to …" line and no recipient, and the ledger says that the tap may not have worked
     * (D24), which also takes the place of the flow's pending share. A send stays among the confirmed sends, so that the
     * completion check still asks for its evidence (D21); a press without recipients sent nothing.
     */
    fun unsure(confirmation: Confirmation, flow: ShareFlow?) {
        val share = flow != null
        val to = confirmation.recipients.joinToString(" and ")
        val what = when {
            confirmation.category == Category.DELETING -> "delete ${deleted(confirmation.content)}"
            confirmation.category != Category.SENDING || confirmation.recipients.isEmpty() -> return
            share -> "share the items with $to"
            else -> "send the message to $to"
        }
        if (confirmation.category == Category.SENDING) {
            confirmedSends += ConfirmedSend(confirmation.recipients, confirmation.content, share)
            // This line already says that the share was tried.
            flow?.let { pendingShares -= it.serial }
        }
        ledger.add("tried to $what, but the tap may not have worked")
    }

    // A delete's content is a count for a selection ("3") or the title of what it deletes.
    private fun deleted(content: String?): String = when {
        content == null -> "an item"
        content.all(Char::isDigit) -> if (content == "1") "1 item" else "$content items"
        else -> content
    }

    override fun toString(): String =
        "Sends(sent=${sentLines.size}, confirmed=${confirmedSends.size}, pending=${pendingShares.size}, shareItems=$shareItems)"

    // The folded names of [recipients], which a share's yes compares as a set (D14).
    private fun chats(recipients: List<String>): Set<String> = recipients.map(Normalizer::normalize).toSet()
}
