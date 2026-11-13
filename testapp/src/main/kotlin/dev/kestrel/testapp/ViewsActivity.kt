package dev.kestrel.testapp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Classic Views: buttons, an unlabeled icon, a check box, a tappable row, text fields (one of them disabled), a dialog,
 * and a disabled button inside a tappable card.
 */
class ViewsActivity : Activity() {
    private lateinit var page: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = fixturePage("Views fixture")
        status = page.addStatus()
        page.addView(sendButton())
        page.addView(ImageButton(this).apply {
            id = R.id.compose_button
            setImageResource(android.R.drawable.ic_menu_edit)
            // No content description on purpose: Kestrel has to name this button from its view ID.
            setOnClickListener { report("Clicked compose") }
        })
        page.addView(CheckBox(this).apply {
            id = R.id.remember_me
            text = "Remember me"
            setOnCheckedChangeListener { _, checked -> report(if (checked) "Checked Remember me" else "Unchecked Remember me") }
        })
        page.addView(LinearLayout(this).apply {
            id = R.id.contact_row
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { report("Clicked contact row") }
            addView(TextView(context).apply { text = "Ana Petrović" })
            addView(TextView(context).apply {
                text = "Online"
                setPadding(dp(16), 0, 0, 0)
            })
        })
        page.addView(
            TouchOnlyView(this).apply {
                id = R.id.touch_target
                contentDescription = "Touch target"
                onTouched = { report("Touched target") }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)),
        )
        page.addView(EditText(this).apply {
            id = R.id.message_field
            hint = "Message"
        })
        page.addView(RejectingEditText(this).apply {
            id = R.id.strict_field
            hint = "Strict field"
        })
        page.addView(EditText(this).apply {
            id = R.id.password_field
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        })
        page.addView(EditText(this).apply {
            hint = "Disabled field"
            // Disabled on purpose: Kestrel must not type into it, nor switch the app to its input method because of it.
            isEnabled = false
        })
        page.addView(Button(this).apply {
            id = R.id.show_dialog
            text = "Show dialog"
            setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle("Delete draft?")
                    .setPositiveButton("Delete", null)
                    .setNegativeButton("Keep", null)
                    .show()
            }
        })
        page.addView(Button(this).apply {
            id = R.id.rebuild
            text = "Rebuild"
            setOnClickListener { replaceSendButton() }
        })
        page.addView(LinearLayout(this).apply {
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { report("Clicked product card") }
            addView(TextView(context).apply { text = "Blue mug" })
            addView(Button(context).apply {
                text = "Add to cart"
                // Disabled on purpose: a tap on it must not reach the card around it.
                isEnabled = false
            })
        })
    }

    private fun sendButton() = Button(this).apply {
        id = R.id.send_button
        text = "Send"
        setOnClickListener { report("Clicked Send") }
        setOnLongClickListener {
            report("Long-pressed Send")
            true
        }
    }

    /** Replaces the Send button with an identical new one, so that a test can hold the node of a view that is gone. */
    private fun replaceSendButton() {
        val index = page.indexOfChild(page.findViewById(R.id.send_button))
        page.removeViewAt(index)
        page.addView(sendButton(), index)
        report("Rebuilt")
    }

    private fun report(text: String) {
        status.text = text
    }
}

/** Reacts to touches but offers no click action, so that Kestrel has to tap it with a gesture. */
class TouchOnlyView(context: Context) : View(context) {
    var onTouched: () -> Unit = {}

    init {
        setBackgroundColor(0xFFE0E0E0.toInt())
    }

    // On purpose: this view must not offer a click action to accessibility services.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) onTouched()
        return true
    }
}

/** Refuses the set-text action, as some messaging apps do, so that Kestrel has to type through its input method. */
class RejectingEditText(context: Context) : EditText(context) {
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        action != AccessibilityNodeInfo.ACTION_SET_TEXT && super.performAccessibilityAction(action, arguments)
}
