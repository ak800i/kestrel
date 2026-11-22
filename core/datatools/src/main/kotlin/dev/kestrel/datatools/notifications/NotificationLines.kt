package dev.kestrel.datatools.notifications

import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.datatools.DataLines
import java.time.ZoneId

/**
 * What `list_notifications` reads of one notification (design D9 of sub-project 5): its app, the time it was posted, and
 * its texts as Android's extras give them. Every text is untrusted; nothing of it is logged.
 */
internal data class NotificationFacts(
    val packageName: String,
    val appLabel: String?,
    val postTime: Long,
    val title: String?,
    val messages: List<Pair<String?, String?>>,
    val bigText: String?,
    val textLines: List<String>,
    val text: String?,
    val subText: String?,
) {
    override fun toString() = "NotificationFacts(messages=${messages.size}, lines=${textLines.size})"
}

/**
 * The lines of `list_notifications` (design D9 of sub-project 5; research r54): at most [MAX], newest first, without
 * Kestrel's own and those of agent-free apps; each with its time in Kestrel's words and the app, the title, the text and
 * the sub-text as untrusted fields. Pure, for the JVM tests.
 */
object NotificationLines {
    const val MAX = 20

    /** The newest messages of a messaging notification that a line holds. */
    const val MESSAGES = 5

    /** The text that Android 15 to 17 give an untrusted listener for a notification that they mark as sensitive. */
    const val HIDDEN_TEXT = "Sensitive notification content hidden"
    const val HIDDEN_NOTE = "(Android hides this notification's content)"

    internal fun lines(all: List<NotificationFacts>, ownPackage: String, isAgentFree: (String?) -> Boolean, sdk: Int, zone: ZoneId): List<DataLine> =
        all.filter { it.packageName != ownPackage && !isAgentFree(it.packageName) }
            .sortedByDescending { it.postTime }
            .take(MAX)
            .map { line(it, sdk, zone) }

    private fun line(facts: NotificationFacts, sdk: Int, zone: ZoneId): DataLine {
        val hidden = sdk >= 35 && (facts.text == HIDDEN_TEXT || facts.messages.any { it.second == HIDDEN_TEXT })
        val fixed = "notification · ${DataLines.time(facts.postTime, zone)}" + if (hidden) " $HIDDEN_NOTE" else ""
        val texts = if (facts.messages.isNotEmpty()) {
            facts.messages.takeLast(MESSAGES).mapNotNull { (sender, text) ->
                text?.let { DataField("message", if (sender.isNullOrBlank()) it else "$sender: $it") }
            }
        } else {
            listOfNotNull(
                (facts.bigText?.takeIf { it.isNotBlank() } ?: facts.textLines.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: facts.text)
                    ?.takeIf { it.isNotBlank() }?.let { DataField("text", it) },
            )
        }
        val head = listOfNotNull(
            DataField("app", facts.appLabel ?: facts.packageName),
            facts.title?.takeIf { it.isNotBlank() }?.let { DataField("title", it) },
        )
        return DataLine(fixed, head + texts + listOfNotNull(facts.subText?.takeIf { it.isNotBlank() }?.let { DataField("sub", it) }))
    }
}
