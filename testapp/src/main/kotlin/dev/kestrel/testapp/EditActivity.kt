package dev.kestrel.testapp

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.telephony.PhoneNumberFormattingTextWatcher
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The text fields of the editing tests in Android's own views (design spec, section 9.7):
 * - a search field with one line and the search action;
 * - a notes field with more lines;
 * - a password field with a "Show password" check box;
 * - a field to paste into;
 * - a phone number field that formats the number as it is typed, as a dialpad's does;
 * - a draft field that fills in a draft when it gets the focus while it is empty, as some apps do;
 * - a legacy field that ignores what an input method commits, so that only the set-text action changes its text.
 *
 * The line under each field reports its text and selection, as `Notes: "hello" 5-5`, with each line break as "\n",
 * because Kestrel's labels join lines; the password line reports only the length. The top status line reports the editor
 * actions and the check box.
 */
class EditActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Edit fixture")
        val status = page.addStatus()
        val search = page.addField(R.id.text_search, "Search", R.id.text_search_state, InputType.TYPE_CLASS_TEXT) { field ->
            "Search: \"${field.text}\" ${field.selectionStart}-${field.selectionEnd}"
        }
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.setOnEditorActionListener { field, action, _ ->
            status.text = "Action $action: \"${field.text}\""
            true
        }
        val notes = page.addField(R.id.text_notes, "Notes", R.id.text_notes_state, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) { field ->
            "Notes: \"${field.text.toString().replace("\n", "\\n")}\" ${field.selectionStart}-${field.selectionEnd}"
        }
        notes.minLines = 3
        notes.gravity = Gravity.TOP or Gravity.START
        val password = page.addField(
            R.id.text_password,
            "Password",
            R.id.text_password_state,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        ) { field -> "Password: ${field.text.length} characters" }
        page.addView(
            CheckBox(this).apply {
                id = R.id.text_show_password
                text = "Show password"
                setOnCheckedChangeListener { _, shown ->
                    password.transformationMethod =
                        if (shown) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                    status.text = if (shown) "Password shown" else "Password hidden"
                }
            },
        )
        page.addField(R.id.text_paste, "Paste here", R.id.text_paste_state, InputType.TYPE_CLASS_TEXT) { field ->
            "Paste: \"${field.text}\""
        }
        val phone = page.addField(R.id.text_phone, "Phone", R.id.text_phone_state, InputType.TYPE_CLASS_PHONE) { field ->
            "Phone: \"${field.text}\""
        }
        phone.addTextChangedListener(PhoneNumberFormattingTextWatcher("US"))
        val draft = page.addField(R.id.text_draft, "Draft", R.id.text_draft_state, InputType.TYPE_CLASS_TEXT) { field ->
            "Draft: \"${field.text}\" ${field.selectionStart}-${field.selectionEnd}"
        }
        draft.setOnFocusChangeListener { _, focused -> if (focused && draft.text.isEmpty()) draft.setText("Dear team,") }
        page.addField(R.id.text_legacy, "Legacy", R.id.text_legacy_state, InputType.TYPE_CLASS_TEXT, ignoresCommits = true) { field ->
            "Legacy: \"${field.text}\" ${field.selectionStart}-${field.selectionEnd}"
        }
    }
}

/**
 * The page of "search for X" through a search button, or with the extra `address` true, through a browser's address bar.
 * The button page has a one-line subject field that sends with its editor action, and a "Search" button that shows the
 * search field and gives it the focus 1.5 s later, as an app that loads its search page does, while the subject keeps
 * the focus until then. The address page has an
 * address bar that shows a short address, and the whole address once it has the focus, as Chrome's does. The status line
 * reports the editor actions; the line under the subject and the address reports its text.
 */
class SearchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("address", false)) address() else searchButton()
    }

    private fun searchButton() {
        val page = fixturePage("Search button fixture")
        val status = page.addStatus()
        val subject = page.addField(R.id.text_subject, "Subject", R.id.text_subject_state, InputType.TYPE_CLASS_TEXT) { field ->
            "Subject: \"${field.text}\""
        }
        subject.imeOptions = EditorInfo.IME_ACTION_SEND
        subject.setOnEditorActionListener { field, action, _ ->
            status.text = "Subject action $action: \"${field.text}\""
            true
        }
        // As on a phone, where a tap leaves the focus in the field: the emulator is not in touch mode, and a click would
        // give a focusable button the focus.
        val button = Button(this).apply {
            text = "Search"
            isFocusable = false
        }
        page.addView(button)
        val search = EditText(this).apply {
            id = R.id.text_mail_search
            hint = "Search mail"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            visibility = View.GONE
            setOnEditorActionListener { field, _, _ ->
                status.text = "Searched: \"${field.text}\""
                true
            }
        }
        page.addView(search)
        button.setOnClickListener {
            button.postDelayed({
                search.visibility = View.VISIBLE
                search.requestFocus()
            }, OPEN_MILLIS)
        }
    }

    private fun address() {
        val page = fixturePage("Address fixture")
        // The page takes the first focus, so that the bar shows the short address until something focuses it.
        page.isFocusableInTouchMode = true
        page.requestFocus()
        val status = page.addStatus()
        val bar = page.addField(R.id.text_address, "Search or type web address", R.id.text_address_state, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { field ->
            "Address: \"${field.text}\""
        }
        bar.setText(SHORT_ADDRESS)
        bar.imeOptions = EditorInfo.IME_ACTION_GO
        bar.setOnFocusChangeListener { _, focused -> if (focused && bar.text.toString() == SHORT_ADDRESS) bar.setText(FULL_ADDRESS) }
        bar.setOnEditorActionListener { field, _, _ ->
            status.text = "Went: \"${field.text}\""
            true
        }
    }

    private companion object {
        // Longer than an action's wait for a quiet screen after a tap that changes nothing else.
        const val OPEN_MILLIS = 1_500L
        const val SHORT_ADDRESS = "example.com/foo"
        const val FULL_ADDRESS = "https://example.com/foo"
    }
}

/**
 * Adds a field with [hint] and, under it, the line that [report] fills whenever its text or selection changes. With
 * [ignoresCommits], the field ignores the text that an input method commits.
 */
private fun LinearLayout.addField(
    id: Int,
    hint: String,
    stateId: Int,
    type: Int,
    ignoresCommits: Boolean = false,
    report: (EditText) -> String,
): EditText {
    val state = TextView(context).apply { this.id = stateId }
    val field = ReportingField(context, ignoresCommits).apply {
        this.id = id
        this.hint = hint
        // The input type alone makes the field one line or more, and keeps a password's dots.
        inputType = type
        onChange = { state.text = report(it) }
    }
    addView(field)
    addView(state)
    state.text = report(field)
    return field
}

/** A text field that reports each change of its text or selection, and may ignore what an input method commits. */
private class ReportingField(context: Context, private val ignoresCommits: Boolean) : EditText(context) {
    // EditText reports a selection while it is built, before this is set.
    var onChange: ((EditText) -> Unit)? = null

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(text: Editable?) {
                onChange?.invoke(this@ReportingField)
            }
        })
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onChange?.invoke(this)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        if (!ignoresCommits) return connection
        return object : InputConnectionWrapper(connection, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = true

            override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean = true
        }
    }
}
