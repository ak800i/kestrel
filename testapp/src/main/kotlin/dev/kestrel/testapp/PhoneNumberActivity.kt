package dev.kestrel.testapp

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * Phone numbers for "copy this phone number" (design spec, Appendix A): an office number, a mobile number (left out with
 * the extra `single`) and a date that is no phone number. Kestrel cannot read the clipboard, so the "Paste" button reads
 * it here, while the fixture is in front, and the status line shows "Pasted" and the number. The "Emergency call" button
 * calls no one: it only shows "Emergency call pressed", for the owner's "yes, call" (design spec, section 8.7). "Rearrange"
 * puts another "Emergency call" button, with another view ID, in its place, so that the screen changes under the question.
 */
class PhoneNumberActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Phone numbers fixture")
        val status = page.addStatus()
        page.addView(TextView(this).apply {
            id = R.id.office_number
            text = "Office: +381 11 234 5678"
        })
        if (!intent.getBooleanExtra(EXTRA_SINGLE, false)) {
            page.addView(TextView(this).apply {
                id = R.id.mobile_number
                text = "Mobile: 064 123 4567"
            })
        }
        page.addView(TextView(this).apply {
            id = R.id.meeting_date
            text = "Meeting on 17.11.2026"
        })
        page.addView(Button(this).apply {
            id = R.id.paste_button
            text = "Paste"
            isAllCaps = false
            setOnClickListener {
                val clip = getSystemService(ClipboardManager::class.java).primaryClip
                status.text = "Pasted ${clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text ?: ""}".trim()
            }
        })
        val emergency = Button(this).apply {
            id = R.id.emergency_button
            text = "Emergency call"
            isAllCaps = false
            setOnClickListener { status.text = "Emergency call pressed" }
        }
        val moved = Button(this).apply {
            id = R.id.emergency_button_moved
            text = "Emergency call"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { status.text = "Emergency call pressed" }
        }
        page.addView(emergency)
        page.addView(moved)
        page.addView(Button(this).apply {
            id = R.id.rearrange_button
            text = "Rearrange"
            isAllCaps = false
            setOnClickListener {
                emergency.visibility = View.GONE
                moved.visibility = View.VISIBLE
            }
        })
    }

    private companion object {
        const val EXTRA_SINGLE = "single"
    }
}
