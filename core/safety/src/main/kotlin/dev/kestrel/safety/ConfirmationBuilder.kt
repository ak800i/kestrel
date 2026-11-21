package dev.kestrel.safety

import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTitle
import dev.kestrel.screen.model.ScreenTree

/**
 * Builds each confirmation from the screen, never from the model's words (design D14 of sub-project 5; spec 12.4). A
 * question names the app by its launcher label (or its package's last part), the content as the screen shows it, and a
 * placeholder by its kind; a long content is cut on the voice and shown whole on the card. When the code cannot fill a
 * question, it hands over instead.
 */
object ConfirmationBuilder {
    private const val SPOKEN_LIMIT = 200
    private const val TAIL = " Say confirm or cancel."

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
            ActionKind.TYPE -> {
                val text = Placeholders.forConfirmation(proposal.text.orEmpty())
                val field = element?.let { it.hint ?: it.contentDescription ?: it.label } ?: "the field"
                return ask("Type '${text}' into '$field' in $app?", text)
            }
            else -> Unit
        }
        val name = element?.let { ScreenTree.name(it) }
        val pressed = index ?: point?.let { ContextRules.underPoint(screen, it) }
        if (task.shareFlow == null && ContextRules.startsShare(screen, pressed, proposal.pointText)) {
            return ask("Share this from $app? You'll choose the chat next.", null)
        }
        if (category == Category.DELETING) {
            val selected = window?.let { w -> screen.elements.count { it.windowId == w.id && it.checkable && it.checked } } ?: 0
            val title = window?.let { ScreenTitle.of(screen, it, proposal.frontAppLabel, withRule3 = false) }
                ?.takeIf { !it.equals(proposal.frontAppLabel, ignoreCase = true) }
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

    /** The app's launcher label, or the last part of its package. */
    fun appName(label: String?, packageName: String?): String =
        label?.takeIf { it.isNotBlank() } ?: packageName?.substringAfterLast('.') ?: "this app"

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
