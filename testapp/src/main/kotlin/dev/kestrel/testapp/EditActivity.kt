package dev.kestrel.testapp

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The text fields of the editing tests in Android's own views (design spec, section 9.7): a search field with one line
 * and the search action, a notes field with more lines, a password field with a "Show password" check box, a field
 * to paste into, a phone number field, as a dialpad has, and a draft field that fills in a draft when it gets the focus
 * while it is empty, as some apps do. The line under each field reports its text and selection, as `Notes: "hello" 5-5`, with each line break
 * as "\n", because Kestrel's labels join lines; the password line reports only the length. The top status line reports
 * the editor actions and the check box.
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
        page.addField(R.id.text_phone, "Phone", R.id.text_phone_state, InputType.TYPE_CLASS_PHONE) { field ->
            "Phone: \"${field.text}\""
        }
        val draft = page.addField(R.id.text_draft, "Draft", R.id.text_draft_state, InputType.TYPE_CLASS_TEXT) { field ->
            "Draft: \"${field.text}\" ${field.selectionStart}-${field.selectionEnd}"
        }
        draft.setOnFocusChangeListener { _, focused -> if (focused && draft.text.isEmpty()) draft.setText("Dear team,") }
    }

    /** Adds a field with [hint] and, under it, the line that [report] fills whenever its text or selection changes. */
    private fun LinearLayout.addField(id: Int, hint: String, stateId: Int, type: Int, report: (EditText) -> String): EditText {
        val state = TextView(context).apply { this.id = stateId }
        val field = ReportingField(context).apply {
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
}

/** A text field that reports each change of its text or selection. */
private class ReportingField(context: Context) : EditText(context) {
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
}
