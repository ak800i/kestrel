package dev.kestrel.app.debug

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A screen with two buttons named "Delete", for the check "Which one?" of scripts\a71-commands.ps1 (design spec, section
 * 9.6), so that the owner need not look for a screen where two buttons share a name: "tap Delete" asks which one, and the
 * screen then says which button Kestrel tapped. Only the shell may start it, as only the shell may send the test hooks.
 */
class TwinButtonsActivity : Activity() {
    // Fixed English texts, as the session's prompts name them.
    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val said = TextView(this).apply { text = "No button tapped yet." }
        fun button(which: String) = Button(this).apply {
            text = "Delete"
            setOnClickListener { said.text = "You tapped the $which button." }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // Below the status bar also where Android draws apps edge to edge (Android 15 and later).
                fitsSystemWindows = true
                addView(button("first"))
                addView(button("second"))
                addView(said)
            },
        )
    }
}
