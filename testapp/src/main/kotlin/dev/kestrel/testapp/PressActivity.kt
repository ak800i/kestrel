package dev.kestrel.testapp

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView

/**
 * Two buttons for Kestrel's own actions and its press policy (design D19 and D32 item 29 of sub-project 5): "Stubborn"
 * acts on a click but answers the click action with false, as some apps do, and "Deaf" refuses the click action and acts
 * only on a touch. The status says how often they were pressed.
 */
class PressActivity : Activity() {
    private lateinit var status: TextView
    private var presses = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Press fixture")
        status = page.addStatus()
        page.addView(StubbornButton(this).apply {
            text = "Stubborn"
            setOnClickListener { pressed() }
        })
        page.addView(DeafButton(this).apply {
            text = "Deaf"
            setOnClickListener { pressed() }
        })
    }

    private fun pressed() {
        presses++
        report("Pressed $presses")
    }

    private fun report(text: String) {
        status.text = text
    }
}

/** Performs the click, but tells accessibility services that it did not. */
class StubbornButton(context: Context) : Button(context) {
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action != AccessibilityNodeInfo.ACTION_CLICK) return super.performAccessibilityAction(action, arguments)
        performClick()
        return false
    }
}

/** Refuses the click action, so that only a touch presses it. */
class DeafButton(context: Context) : Button(context) {
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        action != AccessibilityNodeInfo.ACTION_CLICK && super.performAccessibilityAction(action, arguments)
}
