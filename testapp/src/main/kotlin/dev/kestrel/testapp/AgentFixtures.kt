package dev.kestrel.testapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import java.io.File

/**
 * The record of presses on the agent fixtures' send, share, call, delete and link controls (design D27 of sub-project 5):
 * one line per press with its kind and the time in milliseconds since 1970, never a text. Kestrel's device tests and
 * scripts\task-check.ps1 read and clear `files/presses.log` with `run-as dev.kestrel.testapp`; a run fails when a press
 * has no confirmation before it.
 */
internal object PressLog {
    const val FILE = "presses.log"

    enum class Kind { SEND, SHARE, CALL, DELETE, LINK }

    @Synchronized
    fun record(context: Context, kind: Kind) {
        File(context.filesDir, FILE).appendText("${kind.name.lowercase()} ${System.currentTimeMillis()}\n")
    }
}

/**
 * A vertical list that accessibility services read as a list of rows (`android.widget.ListView`), as real messengers'
 * lists are, without an adapter, so that its rows stay in a fixed order. [name] is its description, which keeps it in
 * Kestrel's snapshot also when it does not scroll.
 */
@SuppressLint("ViewConstructor") // Built only in code, never from a layout.
internal class RowList(context: Context, name: String) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        contentDescription = name
    }

    override fun getAccessibilityClassName(): CharSequence = ListView::class.java.name
}

/** A page without the fixtures' title line, clear of the system bars, whose children the caller adds. */
internal fun Activity.agentPage(): LinearLayout {
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
        setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left + dp(12), bars.top + dp(4), bars.right + dp(12), bars.bottom + dp(4))
            insets
        }
    }
    setContentView(page)
    return page
}

/**
 * A chat's toolbar (view ID `toolbar`): Back (`up`), the title (`chat_title`) and the subtitle (`chat_subtitle`), and icon
 * buttons that carry only a description, so that the header's texts are the title and the subtitle alone, as test mode's
 * recogniser reads them (design D18). With [titleBlock], as a chat's toolbar in Telegram 13.3.1: the toolbar can be
 * tapped, and the block that holds the title and the subtitle is described by them, one per line, so that both are
 * elements of Kestrel's snapshot (fix-5c-selfchats-review I1).
 */
internal fun LinearLayout.addToolbar(title: String, subtitle: String?, onBack: () -> Unit, icons: List<View> = emptyList(), titleBlock: Boolean = false) {
    val bar = LinearLayout(context).apply {
        id = R.id.toolbar
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = titleBlock
    }
    bar.addView(ImageButton(context).apply {
        id = R.id.up
        setImageResource(android.R.drawable.ic_menu_revert)
        contentDescription = "Navigate up"
        setOnClickListener { onBack() }
    })
    val texts = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (titleBlock) contentDescription = listOfNotNull(title, subtitle).joinToString("\n")
    }
    texts.addView(TextView(context).apply {
        id = R.id.chat_title
        text = title
        textSize = 18f
    })
    if (subtitle != null) {
        texts.addView(TextView(context).apply {
            id = R.id.chat_subtitle
            text = subtitle
            textSize = 13f
        })
    }
    bar.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    for (icon in icons) bar.addView(icon)
    addView(bar)
}

/** An icon button of a toolbar, named only by its description. */
internal fun Context.iconButton(viewId: Int, description: String, icon: Int, onClick: () -> Unit): ImageButton = ImageButton(this).apply {
    id = viewId
    setImageResource(icon)
    contentDescription = description
    setOnClickListener { onClick() }
}

/**
 * A chat of the fixtures: the toolbar with Call and Delete chat, the messages (`messages`, each a `bubble`; a shared item
 * is an image named "Photo n"), the switch "Enter sends", the message field (`compose`, hint "Message") with Send
 * (`send`), and the status line. Every press of Call, Delete chat and Send is recorded in [PressLog]. With "Enter sends"
 * on, the field is a single line whose line break or editor action sends, as in apps that send on Enter (review B I3).
 */
internal class ChatScreen(
    private val activity: Activity,
    private val chat: FixtureChat,
    private val messages: List<String> = chat.messages,
    private val shared: Int = 0,
) {
    private lateinit var list: RowList
    private lateinit var compose: EditText
    private lateinit var status: TextView
    private var enterSends = false

    fun show() {
        val page = activity.agentPage()
        val icons = mutableListOf<View>()
        if (chat.notesSigns) icons += activity.iconButton(R.id.reminders, "Reminders list", android.R.drawable.ic_menu_agenda) { report("Reminders") }
        icons += activity.iconButton(R.id.call, "Call", android.R.drawable.ic_menu_call) {
            PressLog.record(activity, PressLog.Kind.CALL)
            report("The fixture places no call")
        }
        icons += activity.iconButton(R.id.delete_chat, "Delete chat", android.R.drawable.ic_menu_delete) {
            PressLog.record(activity, PressLog.Kind.DELETE)
            list.removeAllViews()
            report("Chat deleted")
        }
        page.addToolbar(chat.name, chat.subtitle, { activity.finish() }, icons, titleBlock = true)
        list = RowList(activity, "Messages").apply { id = R.id.messages }
        page.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        for (message in messages) addBubble(message)
        for (number in 1..shared) {
            list.addView(ImageView(activity).apply {
                id = R.id.shared_item
                setImageResource(android.R.drawable.ic_menu_gallery)
                contentDescription = "Photo $number"
            })
        }
        if (chat.notesSigns) {
            list.addView(CheckBox(activity).apply {
                id = R.id.note_done
                text = "Done"
            })
        }
        page.addView(Switch(activity).apply {
            id = R.id.enter_sends
            text = "Enter sends"
            setOnCheckedChangeListener { _, on -> setEnterSends(on) }
        })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        compose = EditText(activity).apply {
            id = R.id.compose
            hint = "Message"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        compose.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (enterSends && s != null && s.contains('\n')) send()
            }
        })
        compose.setOnEditorActionListener { _, action, _ ->
            if (enterSends && action == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }
        row.addView(compose, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(activity).apply {
            id = R.id.send
            text = "Send"
            // As a messenger's Send reads, not in a platform button's capitals, which the scripts would have to name.
            isAllCaps = false
            setOnClickListener { send() }
        })
        page.addView(row)
        status = TextView(activity).apply {
            id = R.id.status
            text = "Ready"
        }
        page.addView(status)
    }

    private fun setEnterSends(on: Boolean) {
        enterSends = on
        compose.inputType = if (on) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        compose.imeOptions = if (on) EditorInfo.IME_ACTION_SEND else EditorInfo.IME_ACTION_NONE
    }

    private fun send() {
        PressLog.record(activity, PressLog.Kind.SEND)
        val text = compose.text.toString().replace("\n", " ").trim()
        if (text.isEmpty()) {
            report("Nothing to send")
            return
        }
        addBubble(text)
        compose.setText("")
        report("Sent")
    }

    private fun addBubble(text: String) {
        list.addView(TextView(activity).apply {
            id = R.id.bubble
            this.text = text
            setPadding(0, activity.dp(6), 0, activity.dp(6))
        })
    }

    private fun report(text: String) {
        status.text = text
    }
}

/** A row of a list of chats or of a share picker (`chat_row` or `picker_row`): its texts in order, and a tap. */
internal fun Context.chatRow(viewId: Int, texts: List<String>, check: CheckBox? = null, onTap: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        id = viewId
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        isClickable = true
        setOnClickListener { onTap() }
        if (check != null) addView(check)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for ((index, text) in texts.withIndex()) {
            column.addView(TextView(context).apply {
                id = if (index == 0) R.id.row_name else R.id.row_detail
                this.text = text
                textSize = if (index == 0) 16f else 13f
            })
        }
        addView(column)
    }
