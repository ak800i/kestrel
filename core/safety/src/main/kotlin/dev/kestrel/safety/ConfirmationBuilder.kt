package dev.kestrel.safety

import dev.kestrel.commands.text.Normalizer
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTitle
import dev.kestrel.screen.model.ScreenTree
import dev.kestrel.screen.model.WindowSummary
import java.net.URI
import java.net.URISyntaxException

/**
 * Builds each confirmation from the screen, never from the model's words (design D14 of sub-project 5; spec 12.4). A
 * question names the recipient as the screen shows it (a chat's title by the title's rules 1, 2 and 4 only, because rule 3
 * could be a message that an attacker wrote; in a share flow the rows, the chat or the dialog that the screen shows at
 * the moment of the send; in an e-mail every address and name of To, Cc and Bcc), the app by its launcher label (or its
 * package's last part), and the content as the screen shows it, a placeholder or a vault value that the task typed by its
 * kind; a long content is cut on the voice and shown whole on the card. A link that needs the owner's
 * yes names its host and the app that opens it, both read from the link and the activity that `:app` resolved for it,
 * never from the model's words. When the code cannot fill a
 * question (no title, a title that is the app's name, a field that it cannot read, a recipient that it cannot parse), it
 * hands over instead of asking.
 */
object ConfirmationBuilder {
    private const val SPOKEN_LIMIT = 200
    private const val TAIL = " Say confirm or cancel."
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val RECIPIENT_FIELDS = setOf("to", "cc", "bcc")
    private val COPY_FIELDS = setOf("cc", "bcc")
    private const val LINK_UNKNOWN = "I can't tell where this link goes. Please open it yourself if you want it, then say 'Kestrel, continue'."

    fun build(proposal: Proposal, screen: ScreenSnapshot, category: Category, task: TaskSafety, why: String? = null): Decision {
        val index = proposal.targetKey?.let { key -> screen.elements.indexOfFirst { it.key == key }.takeIf { it >= 0 } }
        val element = index?.let { screen.elements[it] }
        val point = proposal.point.takeIf { element == null }
        val window = screen.windows.firstOrNull { it.id == element?.windowId }
            ?: point?.let { ContextRules.windowAt(screen, it) } ?: ScreenTree.frontWindow(screen)
        val app = appName(proposal.frontAppLabel, window?.packageName)
        val prefix = why?.let { "$it " }.orEmpty()
        fun ask(body: String, content: String?, recipients: List<String> = emptyList()): Decision {
            val question = prefix + body + TAIL
            return Decision.Confirm(
                Confirmation(question, spoken(prefix, body, content), category, recipients, app, content, proposal.targetKey, element?.bounds),
            )
        }
        when (proposal.kind) {
            ActionKind.SHARE_MEDIA -> {
                val receiver = appName(proposal.receivingAppLabel, proposal.receivingPackage)
                return ask("Open $receiver's share screen with ${items(proposal.shareCount)}?", null)
            }
            ActionKind.COMPOSE_SMS -> return ask("Open the messages app with a text message?", null)
            ActionKind.OPEN_URL, ActionKind.OPEN_DEEP_LINK -> return link(proposal, prefix, category)
            ActionKind.TYPE -> {
                val text = Placeholders.forConfirmation(proposal.text.orEmpty())
                val field = element?.let { it.hint ?: it.contentDescription ?: it.label } ?: "the field"
                return ask("Type '${text}' into '$field' in $app?", text)
            }
            else -> Unit
        }
        val name = element?.let { ScreenTree.name(it) }
        val pressed = index ?: point?.let { ContextRules.underPoint(screen, it) }
        val flow = task.shareFlow
        if (flow == null && ContextRules.startsShare(screen, pressed, proposal.pointText)) {
            // In a chat, some such buttons share with that chat at once (review 2 M7).
            val chat = window?.takeIf { ContextRules.messageField(screen, it.id) != null }
                ?.let { ScreenTitle.of(screen, it, proposal.frontAppLabel, withRule3 = false) }
                ?.takeIf { !it.equals(proposal.frontAppLabel?.trim(), ignoreCase = true) }
            if (chat != null) return ask("Share this from $app? It may go to $chat at once.", null, listOf(chat))
            return ask("Share this from $app? You'll choose the chat next.", null)
        }
        if (category == Category.SENDING && window != null) {
            val handOver = Decision.HandOver(HandOver.of(HandOverKind.RECIPIENT))
            if (flow != null) {
                val recipients = flowRecipients(screen, index, window, flow, proposal.receivingAppLabel ?: proposal.frontAppLabel) ?: return handOver
                val what = flow.count?.let { items(it) } ?: "this"
                // A target of Android's share sheet is an app or a direct-share chat (review 2 M9).
                if (ContextRules.isShareSheet(screen, window)) return ask("Share $what with ${recipients.joinToString(" and ")}?", what, recipients)
                val receiver = appName(proposal.receivingAppLabel ?: proposal.frontAppLabel, flow.receivingPackage)
                return ask("Send $what to ${recipients.joinToString(" and ")} on $receiver?", what, recipients)
            }
            val fields = screen.elements.indices.filter { screen.elements[it].windowId == window.id && recipientField(screen.elements[it]) }
            if (fields.isNotEmpty()) return email(proposal, screen, window, fields, element, prefix, category, app, task) ?: handOver
            val title = ScreenTitle.of(screen, window, proposal.frontAppLabel, withRule3 = false)
                ?.takeIf { !it.equals(proposal.frontAppLabel?.trim(), ignoreCase = true) } ?: return handOver
            val text = composeField(screen, window, element)?.text?.trim().orEmpty()
            if (text.isEmpty()) return handOver
            val shown = Placeholders.forConfirmation(hide(text, task))
            return ask("Send to $title on $app: '$shown'?", shown, listOf(title))
        }
        if (category == Category.DELETING) {
            // Only the selected rows of a list count, never a checked switch beside it (review 2 M8).
            val selected = window?.let { ContextRules.checkedRows(screen, it.id).size } ?: 0
            val title = window?.let { ScreenTitle.of(screen, it, proposal.frontAppLabel, withRule3 = false) }
                ?.takeIf { !it.equals(proposal.frontAppLabel, ignoreCase = true) }
                ?.let { Placeholders.forConfirmation(hide(it, task)) }
            return when {
                selected > 0 -> ask("Delete $selected ${if (selected == 1) "item" else "items"} in $app?", "$selected")
                title != null -> ask("Delete '$title' in $app?", title)
                else -> Decision.HandOver(
                    HandOver(HandOverKind.RECIPIENT, "I can't see what this deletes. Please check it and do it yourself, then say 'Kestrel, continue'."),
                )
            }
        }
        val target = name ?: proposal.pointText ?: "this"
        return ask("Tap '$target' in $app?", null)
    }

    /**
     * A link's question: "Open <host> in <app>? It sends what the link holds." The host comes from the link itself (for a
     * link without one, such as `mailto:`, "a mailto link"), the app from [Proposal.linkTargetPackage] with its label in
     * [Proposal.receivingAppLabel]; the content is the whole link, so that a changed link needs a new yes. Without a
     * resolved app or a readable link, it hands over.
     */
    private fun link(proposal: Proposal, prefix: String, category: Category): Decision {
        val link = proposal.link?.trim().orEmpty()
        val target = proposal.linkTargetPackage
        val place = linkPlace(link)
        if (target == null || place == null) return Decision.HandOver(HandOver(HandOverKind.RECIPIENT, LINK_UNKNOWN))
        val app = appName(proposal.receivingAppLabel, target)
        val body = "Open $place in $app? It sends what the link holds."
        return Decision.Confirm(Confirmation(prefix + body + TAIL, spoken(prefix, body, null), category, listOf(place), app, link, null, null))
    }

    /** An https link's host; for another scheme "a <scheme> link", because `whatsapp://send` names an action, not a host. */
    private fun linkPlace(link: String): String? {
        val uri = try {
            URI(link)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme == "https") return uri.host?.takeIf { it.isNotEmpty() }?.lowercase()
        return "a $scheme link"
    }

    /**
     * Whom a send in a share flow goes to, as the screen shows it now, never the rows that the flow selected earlier
     * (review 2 C1 and I1): in a list of rows, the tapped row and every row with a checked element, each by its row's
     * name; in a chat (a window with a message field, empty or not), its title by the title's rules 1, 2 and 4, when it is
     * a row that the flow selected; in a dialog without rows or a message field (an app's own "Send to …?"), the selected
     * rows when the dialog shows each of their names as whole words outside every list of rows. Null when the screen does
     * not show whom it sends to, or a row has no name.
     */
    private fun flowRecipients(screen: ScreenSnapshot, index: Int?, window: WindowSummary, flow: ShareFlow, appLabel: String?): List<String>? {
        // Rows that the screen shows as tapped or checked name the recipients, also beside a comment field.
        val rows = (listOfNotNull(index?.let { ContextRules.row(screen, it) }) + ContextRules.checkedRows(screen, window.id)).distinct()
        if (rows.isNotEmpty()) return rows.map { ContextRules.rowName(screen, it) ?: return null }.distinct()
        // A chat counts by its message field, also an empty one, so that a chat is never read as a dialog (review 2-2 New 1).
        if (ContextRules.messageField(screen, window.id) == null) {
            return flow.selected.takeIf { selected -> selected.isNotEmpty() && selected.all { ContextRules.showsName(screen, window.id, it) } }
        }
        val title = ScreenTitle.of(screen, window, appLabel, withRule3 = false)
            ?.takeIf { !it.equals(appLabel?.trim(), ignoreCase = true) } ?: return null
        return listOf(title).takeIf { flow.selected.any { Normalizer.normalize(it) == Normalizer.normalize(title) } }
    }

    /**
     * [text] with every vault value that the task typed replaced by the words for its kind, matched as the redactor
     * matches a known value, with or without spaces or dashes between its characters (design D14; review 2 I3).
     */
    private fun hide(text: String, task: TaskSafety): String {
        var result = text
        for ((value, words) in task.secrets.entries.sortedByDescending { it.key.length }) {
            val compact = value.filter { it != ' ' && it != '-' }
            if (compact.isEmpty()) continue
            val pattern = Regex(compact.map { Regex.escape(it.toString()) }.joinToString("[ -]?"), RegexOption.IGNORE_CASE)
            result = pattern.replace(result) { words }
        }
        return result
    }

    /** The app's launcher label, or the last part of its package. */
    fun appName(label: String?, packageName: String?): String =
        label?.takeIf { it.isNotBlank() } ?: packageName?.substringAfterLast('.') ?: "this app"

    /**
     * An e-mail's question: every address and name in the full texts and descriptions of To, Cc and Bcc, also text that
     * has not become a chip yet; the subject; the body; attachments or a forwarded message. Null when a part of a
     * recipient field cannot be read as an address.
     */
    private fun email(
        proposal: Proposal,
        screen: ScreenSnapshot,
        window: WindowSummary,
        fields: List<Int>,
        element: ScreenElement?,
        prefix: String,
        category: Category,
        app: String,
        task: TaskSafety,
    ): Decision? {
        val to = mutableListOf<String>()
        val copies = mutableListOf<String>()
        for (field in fields) {
            val named = screen.elements[field]
            val kind = ScreenTree.idName(named.viewId)?.lowercase()?.takeIf { it in RECIPIENT_FIELDS } ?: (named.hint ?: named.label)?.trim()?.lowercase()
            val pieces = screen.elements.indices.filter { it == field || field in ScreenTree.ancestors(screen, it) }
                .flatMap { listOfNotNull(screen.elements[it].text, screen.elements[it].contentDescription) }
            for (part in pieces.flatMap { it.split(',', ';') }) {
                val found = EMAIL.findAll(part).map { it.value }.toList()
                val rest = EMAIL.replace(part, "").split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() && it.lowercase() !in RECIPIENT_FIELDS }
                if (found.isEmpty() && rest.isNotEmpty()) return null
                (if (kind in COPY_FIELDS) copies else to) += found
            }
        }
        val recipients = (to + copies).distinct()
        if (recipients.isEmpty()) return null
        val inWindow = screen.elements.filter { it.windowId == window.id }
        fun isSubject(it: ScreenElement) = ScreenTree.idName(it.viewId)?.contains("subject", ignoreCase = true) == true || it.hint.equals("Subject", ignoreCase = true)
        val subject = Placeholders.forConfirmation(hide(inWindow.firstOrNull { it.editable && isSubject(it) }?.text?.trim().orEmpty(), task))
        val body = hide(inWindow.firstOrNull { it.editable && !recipientField(it) && !isSubject(it) }?.text?.trim().orEmpty(), task)
        val attachments = inWindow.count { ScreenTree.idName(it.viewId)?.contains("attachment", ignoreCase = true) == true }
        val forwarded = inWindow.any { it.text?.contains("Forwarded message", ignoreCase = true) == true }
        val extra = when {
            forwarded -> " with the forwarded message"
            attachments > 0 -> " with $attachments ${if (attachments == 1) "attachment" else "attachments"}"
            else -> ""
        }
        val main = to.distinct().ifEmpty { copies.distinct() }
        val copyText = copies.distinct().filter { it !in main }.takeIf { it.isNotEmpty() }?.let { " and, as a copy, ${it.joinToString(" and ")}" }.orEmpty()
        val shown = Placeholders.forConfirmation(body)
        val sentence = "Send an e-mail to ${main.joinToString(" and ")}$copyText, subject '$subject'$extra: '$shown'?"
        return Decision.Confirm(
            Confirmation(prefix + sentence + TAIL, spoken(prefix, sentence, shown), category, recipients, app, shown, proposal.targetKey, element?.bounds),
        )
    }

    private fun recipientField(element: ScreenElement): Boolean {
        val id = ScreenTree.idName(element.viewId)?.lowercase()
        val hint = (element.hint ?: element.label)?.trim()?.lowercase()
        return element.editable && (id in RECIPIENT_FIELDS || hint in RECIPIENT_FIELDS)
    }

    /** The editable field beside the send button: the one nearest to the target in the same window. */
    private fun composeField(screen: ScreenSnapshot, window: WindowSummary, target: ScreenElement?): ScreenElement? {
        val fields = screen.elements.filter { it.windowId == window.id && it.editable && !it.password }
        if (target == null) return fields.singleOrNull()
        return fields.minByOrNull { kotlin.math.abs(it.bounds.centerY - target.bounds.centerY) }
    }


    private fun items(count: ShareCount?): String {
        count ?: return "the items"
        val parts = listOfNotNull(
            count.photos.takeIf { it > 0 }?.let { "$it ${if (it == 1) "photo" else "photos"}" },
            count.videos.takeIf { it > 0 }?.let { "$it ${if (it == 1) "video" else "videos"}" },
        )
        return parts.joinToString(" and ").ifEmpty { "the items" }
    }

    /** The spoken question: a content over 200 characters is cut at its last word before 200, with how much is left. */
    fun spoken(prefix: String, body: String, content: String?): String {
        if (content == null || content.length <= SPOKEN_LIMIT) return prefix + body + TAIL
        val cut = content.substring(0, SPOKEN_LIMIT).substringBeforeLast(' ').ifEmpty { content.substring(0, SPOKEN_LIMIT) }
        val left = content.length - cut.length
        return prefix + body.replace(content, "$cut… and $left more characters") + TAIL
    }
}
