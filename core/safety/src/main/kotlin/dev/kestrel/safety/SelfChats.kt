package dev.kestrel.safety

import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTree
import dev.kestrel.screen.model.WindowSummary

/**
 * Test mode's recogniser of the owner's own chats (design D18 of sub-project 5; research r63): the title alone never
 * decides, because a contact, a group or a bot can carry any name, so each app's rule needs signs that a stranger cannot
 * set. An app's rule is active only after a tree of the real app, recorded on a device, has pinned it in a reviewed fix
 * ([ACTIVE]); until then the recogniser answers no for that app, so test mode blocks every send there. The debug-only
 * aliases apply an app's rule to a fixture of `:testapp` (and only to `:testapp`) whether or not the rule is active. It
 * always reads what the screen shows at the moment of the send, never the rows that a share flow selected earlier.
 */
object SelfChats {
    const val TELEGRAM = "org.telegram.messenger"
    const val TELEGRAM_WEB = "org.telegram.messenger.web"
    const val WHATSAPP = "com.whatsapp"
    const val VIBER = "com.viber.voip"
    const val GMAIL = "com.google.android.gm"

    /** The only package that an alias may map to an app's rule: `:testapp`'s fixtures (review 2 n2). */
    const val FIXTURES = "dev.kestrel.testapp"

    /** The apps whose rule a reviewed fix has pinned from a real tree; none in plan 5A (design D18, review B3-N1). */
    val ACTIVE: Set<String> = setOf(
        // sub-project 5: the reviewed fix after the owner's first sitting adds Telegram's and WhatsApp's packages here.
    )

    /** The top part of a chat's window that holds its toolbar's title and subtitle. */
    private const val HEADER = 0.12
    private val TELEGRAM_OTHERS = listOf("member", "subscriber", "last seen", "online", "typing", "bot", "recently")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val RECIPIENT_FIELDS = setOf("to", "cc", "bcc")

    /**
     * Whether the action on [targetKey] sends to the owner's own chat: the chat on screen, the picker's row that it taps,
     * the checked rows that a picker's send button sends to, or the e-mail's recipients.
     */
    fun recognise(
        snapshot: ScreenSnapshot,
        targetKey: String?,
        settings: SafetySettings,
        flow: ShareFlow? = null,
        aliases: Map<String, String> = emptyMap(),
        active: Set<String> = ACTIVE,
    ): Boolean {
        val index = targetKey?.let { key -> snapshot.elements.indexOfFirst { it.key == key }.takeIf { it >= 0 } }
        val window = snapshot.windows.firstOrNull { it.id == index?.let { i -> snapshot.elements[i].windowId } }
            ?: ScreenTree.frontWindow(snapshot) ?: return false
        val pkg = window.packageName ?: return false
        val rule = aliases[pkg]?.takeIf { pkg == FIXTURES } ?: pkg.takeIf { it in active } ?: return false
        val rows = rowsTo(snapshot, index, window)
        // In a share flow, a send in a dialog without rows or a message field (an app's own "Send to …?") sends to the rows
        // that the flow selected: it passes only when each of them is the self-chat and the dialog itself shows that name.
        // A chat on the screen is checked by its app's own chat rule below (review 2 C1).
        if (rows == null && flow != null && flow.selected.isNotEmpty() && rule != GMAIL && ContextRules.messageField(snapshot, window.id) == null) {
            val self = when (rule) {
                WHATSAPP -> settings.whatsappOwnName?.let { "$it (You)" }
                VIBER -> settings.viberSelfChatName
                else -> "Saved Messages"
            } ?: return false
            return flow.selected.all { it == self } && ContextRules.showsName(snapshot, window.id, self)
        }
        return when (rule) {
            TELEGRAM, TELEGRAM_WEB -> if (rows != null) rows.isNotEmpty() && rows.all { telegramRow(snapshot, it, window) } else telegramChat(snapshot, window)
            WHATSAPP -> {
                val name = settings.whatsappOwnName ?: return false
                val title = "$name (You)"
                if (rows != null) rows.isNotEmpty() && rows.all { rowTexts(snapshot, it) == listOf(title, "Message yourself") }
                else header(snapshot, window) == listOf(title, "Message yourself")
            }
            VIBER -> {
                val name = settings.viberSelfChatName
                if (rows != null) rows.isNotEmpty() && rows.all { it == firstRow(snapshot, window) && rowTexts(snapshot, it).firstOrNull() == name }
                else header(snapshot, window).firstOrNull() == name && viberNotesSigns(snapshot, window)
            }
            GMAIL -> email(snapshot, window, settings.ownerEmails)
            else -> false
        }
    }

    /**
     * The picker's rows that the action sends to: the row that holds the target when it lies in a list of rows (whether
     * the list scrolls or not), with the rows that hold a checked element; else those checked rows (a selection's send
     * button); null when the action is no picker's.
     */
    private fun rowsTo(snapshot: ScreenSnapshot, index: Int?, window: WindowSummary): List<Int>? {
        val checked = ContextRules.checkedRows(snapshot, window.id)
        val tapped = index?.let { ContextRules.row(snapshot, it) }
        if (tapped != null) return (listOf(tapped) + checked).distinct()
        return checked.takeIf { it.isNotEmpty() }
    }

    private fun firstRow(snapshot: ScreenSnapshot, window: WindowSummary): Int? {
        val list = snapshot.elements.indices.firstOrNull { snapshot.elements[it].windowId == window.id && ContextRules.isRowsList(snapshot.elements[it]) }
            ?: return null
        return snapshot.elements.indices.filter { snapshot.elements[it].parent == list }.minByOrNull { snapshot.elements[it].bounds.top }
    }

    /** The texts of a row and of everything inside it, in reading order. */
    private fun rowTexts(snapshot: ScreenSnapshot, row: Int): List<String> =
        snapshot.elements.indices.filter { it == row || row in ScreenTree.ancestors(snapshot, it) }
            .sortedWith(compareBy({ snapshot.elements[it].bounds.top }, { snapshot.elements[it].bounds.left }))
            .mapNotNull { snapshot.elements[it].text?.trim()?.takeIf(String::isNotEmpty) }

    /** The texts of the chat's header: outside every scrollable container, in the top part of the window, in reading order. */
    private fun header(snapshot: ScreenSnapshot, window: WindowSummary): List<String> {
        val bottom = window.bounds.top + window.bounds.height * HEADER
        return snapshot.elements.indices.filter {
            val element = snapshot.elements[it]
            element.windowId == window.id && !element.editable && element.bounds.centerY < bottom && !ScreenTree.insideScrollable(snapshot, it)
        }.sortedWith(compareBy({ snapshot.elements[it].bounds.top }, { snapshot.elements[it].bounds.left }))
            .mapNotNull { snapshot.elements[it].text?.trim()?.takeIf(String::isNotEmpty) }
    }

    /** Telegram's chat: the title "Saved Messages", the subtitle "forward here to save", and nothing else in the header. */
    private fun telegramChat(snapshot: ScreenSnapshot, window: WindowSummary): Boolean {
        val texts = header(snapshot, window)
        return texts == listOf("Saved Messages", "forward here to save") &&
            texts.none { text -> TELEGRAM_OTHERS.any { it in text.lowercase() } && text != "forward here to save" }
    }

    /**
     * Telegram's picker: the first row of the list, "Saved Messages" with the preview "Forward here to save." and no time,
     * tick or counter, while no search shows results.
     */
    private fun telegramRow(snapshot: ScreenSnapshot, row: Int, window: WindowSummary): Boolean {
        val searching = snapshot.elements.any { it.windowId == window.id && it.editable && !it.text.isNullOrBlank() }
        return !searching && row == firstRow(snapshot, window) && rowTexts(snapshot, row) == listOf("Saved Messages", "Forward here to save.")
    }

    /** One sign of Viber's notes chat: a note's checkbox in the messages, the "Reminders list" icon or "Hide completed notes". */
    private fun viberNotesSigns(snapshot: ScreenSnapshot, window: WindowSummary): Boolean {
        val inWindow = snapshot.elements.indices.filter { snapshot.elements[it].windowId == window.id }
        val checkbox = inWindow.any { snapshot.elements[it].checkable && ScreenTree.insideScrollable(snapshot, it) }
        val names = inWindow.mapNotNull { ScreenTree.name(snapshot.elements[it]) }
        return checkbox || "Reminders list" in names || "Hide completed notes" in names
    }

    /**
     * Gmail's compose screen: every address in the full texts and descriptions of the To, Cc and Bcc fields is one of the
     * owner's, a part with letters but no address (a name that has not become an address) fails, and there is at least one.
     */
    private fun email(snapshot: ScreenSnapshot, window: WindowSummary, owner: List<String>): Boolean {
        val fields = snapshot.elements.indices.filter { index ->
            val element = snapshot.elements[index]
            element.windowId == window.id && recipientField(element)
        }
        if (fields.isEmpty()) return false
        val pieces = snapshot.elements.indices
            .filter { index -> fields.any { field -> index == field || field in ScreenTree.ancestors(snapshot, index) } }
            .flatMap { listOfNotNull(snapshot.elements[it].text, snapshot.elements[it].contentDescription) }
        val addresses = pieces.flatMap { piece -> EMAIL.findAll(piece).map { it.value.lowercase() }.toList() }
        // Each part between commas or semicolons must hold an address, or no letters beyond "To", "Cc" and "Bcc".
        val nameOnly = pieces.flatMap { it.split(',', ';') }.any { part ->
            !EMAIL.containsMatchIn(part) && part.split(Regex("[^\\p{L}]+")).any { it.isNotEmpty() && it.lowercase() !in RECIPIENT_FIELDS }
        }
        val mine = owner.map { it.trim().lowercase() }.toSet()
        return addresses.isNotEmpty() && !nameOnly && addresses.all { it in mine }
    }

    private fun recipientField(element: ScreenElement): Boolean {
        val id = ScreenTree.idName(element.viewId)?.lowercase()
        val hint = (element.hint ?: element.label)?.trim()?.lowercase()
        return element.editable && (id in RECIPIENT_FIELDS || hint in RECIPIENT_FIELDS)
    }
}
