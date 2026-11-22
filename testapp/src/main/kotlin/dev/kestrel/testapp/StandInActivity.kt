package dev.kestrel.testapp

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

/**
 * The stand-ins of plan 5B's dry runs (design D28 of sub-project 5): screens for the D3 tasks that need no real app or
 * account, chosen by `--es screen hotspot|browser|inbox`. `hotspot` has the switch "Wi-Fi hotspot"; `browser` a search
 * field "Search Wikipedia" whose Search shows the heading "Common kestrel"; `inbox` a list of e-mails whose first row,
 * "Kestrel test <marker>" with `--es marker <word>`, opens the e-mail with the marker in its text.
 */
class StandInActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_SCREEN)) {
            "browser" -> browser()
            "inbox" -> inbox(intent.getStringExtra(EXTRA_MARKER) ?: "marker")
            else -> hotspot()
        }
    }

    private fun hotspot() {
        val page = agentPage()
        page.addToolbar("Hotspot", null, { finish() })
        page.addView(Switch(this).apply {
            id = R.id.hotspot_switch
            text = "Wi-Fi hotspot"
        })
    }

    private fun browser() {
        val page = agentPage()
        page.addToolbar("Wikipedia", null, { finish() })
        val field = EditText(this).apply {
            id = R.id.search_field
            hint = "Search Wikipedia"
        }
        page.addView(field)
        page.addView(Button(this).apply {
            id = R.id.search_button
            text = "Search"
            isAllCaps = false
            setOnClickListener {
                page.addView(TextView(context).apply {
                    text = if (field.text.isBlank()) "Wikipedia" else "Common kestrel"
                    textSize = 22f
                })
                page.addView(TextView(context).apply { text = "The common kestrel is a bird of prey of the falcon family." })
            }
        })
    }

    private fun inbox(marker: String) {
        val page = agentPage()
        page.addToolbar("Inbox", null, { finish() })
        val list = RowList(this, "Messages").apply { id = R.id.chat_list }
        list.addView(chatRow(R.id.chat_row, listOf("Kestrel test $marker", "Test sender", "12:00")) { open(marker) })
        list.addView(chatRow(R.id.chat_row, listOf("Weekly digest", "Club news", "09:30")) { })
        page.addView(list)
    }

    private fun open(marker: String) {
        val page = agentPage()
        page.addToolbar("Kestrel test $marker", "Test sender", { finish() })
        page.addView(TextView(this).apply { text = "This is a test e-mail for Kestrel: $marker." })
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_MARKER = "marker"
    }
}
