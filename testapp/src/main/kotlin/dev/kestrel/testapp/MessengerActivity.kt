package dev.kestrel.testapp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.edit

/** A chat of the fixture messenger (design D18 and D28 of sub-project 5). */
internal data class FixtureChat(
    val id: String,
    val name: String,
    val subtitle: String?,
    val preview: String,
    val time: String?,
    val messages: List<String>,
    val notesSigns: Boolean = false,
)

/**
 * The chats of the fixture messenger: the owner's self-chats in the forms of Telegram ("Saved Messages" with no line below,
 * as Telegram 13.3.1 shows it), WhatsApp ("<name> (You)", "Message yourself") and Viber ("My Notes" with the "Reminders
 * list" icon), ordinary chats and groups, and the look-alikes of design D18, which test mode's recogniser must refuse.
 * Every name, number and address is fictional.
 */
internal object FixtureChats {
    /** The owner's name in the WhatsApp-like self-chat, which the tests give as `whatsappOwnName`. */
    const val OWNER_NAME = "Test Owner"

    val ALL: List<FixtureChat> = listOf(
        // Its list row shows the newest saved message, as Telegram's does; only the share picker says "Forward here to save.".
        FixtureChat("saved", "Saved Messages", null, "Bread and milk", null, listOf("Bread and milk")),
        FixtureChat("ana", "Ana Petrović", "last seen recently", "See you at 5", "12:30", listOf("Hi", "See you at 5")),
        FixtureChat("john", "John Smith", "online", "Thanks!", "11:02", listOf("Thanks!")),
        FixtureChat("close-friends", "Close friends", "12 members", "Pizza tonight?", "10:40", listOf("Pizza tonight?")),
        FixtureChat("back-to-school", "Back to school", "30 members", "Books are ready", "10:20", listOf("Books are ready")),
        // The look-alikes: a contact, a group, a user and a bot named "Saved Messages", each with the line that Telegram
        // shows below every other chat's name, and a chat whose message is the self-chat's old subtitle.
        FixtureChat("saved-contact", "Saved Messages", "last seen a long time ago", "Hello", "10:15", listOf("Hello")),
        FixtureChat("saved-group", "Saved Messages", "3 members", "Meeting at 6", "09:40", listOf("Meeting at 6")),
        FixtureChat("saved-user", "Saved Messages", "last seen recently", "Forward here to save.", "09:12", listOf("Forward here to save.")),
        FixtureChat("saved-bot", "Saved Messages", "bot", "Forward here to save.", "08:55", listOf("Forward here to save.")),
        FixtureChat("forward-text", "Mira Jović", "online", "forward here to save", "08:30", listOf("forward here to save")),
        FixtureChat("whatsapp-self", "$OWNER_NAME (You)", "Message yourself", "Note to self", null, listOf("Note to self")),
        FixtureChat("whatsapp-lookalike", "$OWNER_NAME (You)", null, "Hi", "07:50", listOf("Hi")),
        FixtureChat("viber-notes", "My Notes", null, "Buy milk", null, listOf("Buy milk"), notesSigns = true),
        FixtureChat("viber-lookalike", "My Notes", null, "Buy milk", "07:10", listOf("Buy milk")),
    )

    fun byId(id: String?): FixtureChat? = ALL.firstOrNull { it.id == id }
}

/**
 * The fixture messenger (design D28 of sub-project 5), opened with am start, not from the launcher: the list of chats
 * (`chat_list`, rows `chat_row`) or, with `--es chat <id>`, one chat of [FixtureChats]. `--ei shared n` shows n shared
 * photos at the chat's end, as after a share; `--es shareForm tap|select` chooses the form of [ShareActivity] for the
 * next shares and is kept in the app's preferences. The e-mail screen of design D18 opens with `--es mail self|two|name`.
 */
class MessengerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_SHARE_FORM)?.let { form ->
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit { putString(KEY_SHARE_FORM, form) }
        }
        val mail = intent.getStringExtra(EXTRA_MAIL)
        val chat = FixtureChats.byId(intent.getStringExtra(EXTRA_CHAT))
        when {
            mail != null -> showMail(mail)
            chat != null -> ChatScreen(this, chat, shared = intent.getIntExtra(EXTRA_SHARED, 0)).show()
            else -> showChats()
        }
    }

    private fun showChats() {
        val page = agentPage()
        page.addToolbar("Chats", null, { finish() })
        val list = RowList(this, "Chats").apply { id = R.id.chat_list }
        for (chat in FixtureChats.ALL) {
            list.addView(chatRow(R.id.chat_row, listOfNotNull(chat.name, chat.preview, chat.time)) { open(chat) })
        }
        page.addView(list)
    }

    private fun open(chat: FixtureChat) {
        startActivity(Intent(this, MessengerActivity::class.java).putExtra(EXTRA_CHAT, chat.id))
    }

    /**
     * An e-mail's compose screen in Gmail's form for test mode's recogniser (design D18): To, Cc and Bcc fields, a subject
     * and a body, and Send. `self` writes to the owner's fictional address only, `two` to it and another address, `name`
     * to it and a name without an address.
     */
    private fun showMail(kind: String) {
        val page = agentPage()
        page.addToolbar("Compose", null, { finish() })
        val to = when (kind) {
            "two" -> "$OWNER_MAIL, ana@example.com"
            "name" -> "$OWNER_MAIL, Ana Petrović"
            else -> OWNER_MAIL
        }
        page.addView(field(R.id.to, "To", to))
        page.addView(field(R.id.cc, "Cc", ""))
        page.addView(field(R.id.bcc, "Bcc", ""))
        page.addView(field(R.id.subject, "Subject", "Plan"))
        page.addView(field(R.id.body, "Compose email", "See you on Friday."))
        val status = TextView(this).apply { id = R.id.status; text = "Ready" }
        page.addView(Button(this).apply {
            id = R.id.send
            text = "Send"
            setOnClickListener {
                PressLog.record(this@MessengerActivity, PressLog.Kind.SEND)
                status.text = "Sent"
            }
        })
        page.addView(status)
    }

    private fun field(viewId: Int, name: String, value: String) = EditText(this).apply {
        id = viewId
        hint = name
        setText(value)
    }

    companion object {
        const val EXTRA_CHAT = "chat"
        const val EXTRA_SHARED = "shared"
        const val EXTRA_SHARE_FORM = "shareForm"
        const val EXTRA_MAIL = "mail"
        const val OWNER_MAIL = "owner@example.com"
        internal const val PREFERENCES = "fixtures"
        internal const val KEY_SHARE_FORM = "shareForm"
    }
}

/**
 * The fixture messenger's share picker for `ACTION_SEND` and `ACTION_SEND_MULTIPLE` (design D28; review B C1, B3-M1), in
 * the form that [MessengerActivity] stored: `tap` sends on a row's tap, as Telegram does, with "Saved Messages" and
 * "Forward here to save." as its first row; `select` checks rows and sends them with one Send button (`picker_send`), as
 * WhatsApp does, with the owner's "(You)" row first. Both have rows named "Close friends" and "Back to school", and a
 * search field (`picker_search`) whose results are no self-chat. Each send is recorded in [PressLog] and opens the chat
 * that it went to with the shared items, or the list of chats for several: `tap` in this activity, as Telegram does in its
 * single activity, and `select` in a new activity, as WhatsApp does, which a slow phone may draw only after Kestrel's
 * first look at the screen after the press.
 */
class ShareActivity : Activity() {
    private lateinit var rows: RowList
    private val checks = mutableListOf<Pair<FixtureChat, CheckBox>>()
    private var items = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items = sharedCount(intent)
        val select = getSharedPreferences(MessengerActivity.PREFERENCES, MODE_PRIVATE)
            .getString(MessengerActivity.KEY_SHARE_FORM, FORM_TAP) == FORM_SELECT
        val page = agentPage()
        page.addToolbar("Share with", null, { finish() })
        val search = EditText(this).apply {
            id = R.id.picker_search
            hint = "Search"
        }
        page.addView(search)
        rows = RowList(this, "Share targets").apply { id = R.id.picker_rows }
        page.addView(rows)
        val targets = targets(select)
        fill(targets, select, "")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) = fill(targets, select, s?.toString().orEmpty())
        })
        if (select) {
            page.addView(Button(this).apply {
                id = R.id.picker_send
                text = "Send"
                setOnClickListener { sendTo(checks.filter { it.second.isChecked }.map { it.first }, select) }
            })
        }
    }

    private fun targets(select: Boolean): List<FixtureChat> {
        val self = if (select) "whatsapp-self" else "saved"
        return listOf(self, "close-friends", "back-to-school", "ana", "john").mapNotNull(FixtureChats::byId)
    }

    // The search shows the rows whose name holds the text; a row of the results is never the first row of the list.
    private fun fill(targets: List<FixtureChat>, select: Boolean, query: String) {
        rows.removeAllViews()
        checks.clear()
        for (chat in targets.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }) {
            val texts = if (select) listOfNotNull(chat.name, chat.subtitle) else listOfNotNull(chat.name, "Forward here to save.".takeIf { chat.id == "saved" } ?: chat.subtitle)
            if (select) {
                val check = CheckBox(this).apply { id = R.id.picker_check }
                checks += chat to check
                rows.addView(chatRow(R.id.picker_row, texts, check) { check.isChecked = !check.isChecked })
            } else {
                rows.addView(chatRow(R.id.picker_row, texts) { sendTo(listOf(chat), select) })
            }
        }
    }

    private fun sendTo(chats: List<FixtureChat>, select: Boolean) {
        PressLog.record(this, PressLog.Kind.SHARE)
        if (chats.size == 1 && !select) {
            ChatScreen(this, chats.single(), shared = items).show()
            return
        }
        val next = Intent(this, MessengerActivity::class.java)
        if (chats.size == 1) next.putExtra(MessengerActivity.EXTRA_CHAT, chats.single().id).putExtra(MessengerActivity.EXTRA_SHARED, items)
        startActivity(next)
        finish()
    }

    private fun sharedCount(intent: Intent): Int {
        intent.clipData?.let { return it.itemCount }
        return when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.size ?: 0
            Intent.ACTION_SEND -> if (intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) != null) 1 else 0
            else -> 0
        }
    }

    private companion object {
        const val FORM_TAP = "tap"
        const val FORM_SELECT = "select"
    }
}
