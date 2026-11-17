package dev.kestrel.testapp

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.view.isVisible
import java.util.Locale

/**
 * The fixture of the instant commands (design spec, section 16.3): similar labels, two "Delete" buttons in two rows, a
 * full-screen dialog with one "Delete" button, a button that covers Dan's row with a window that has nothing to tap, a
 * check box, a switch named "Wi‑Fi" with a non-breaking hyphen, a View slider without a name, a Compose slider, a
 * determinate progress bar, an expandable row, a drag source and target, a horizontal pager, a long-press counter and a
 * Cyrillic label. The status line reports each action. The buttons show their texts as they are, so that the labels in
 * Kestrel's snapshots equal the texts.
 */
class CommandsActivity : ComponentActivity() {
    private lateinit var status: TextView
    private var longPresses = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Commands fixture")
        status = page.addStatus()
        page.addView(
            row(
                button("Save") { report("Clicked Save") },
                button("Safe") { report("Clicked Safe") },
                button("Send") { report("Clicked Send") },
                button("Send later") { report("Clicked Send later") },
            ),
        )
        page.addView(row(text("Message from Ana"), button("Delete") { report("Deleted Ana") }))
        val dan = row(text("Message from Dan"), button("Delete") { report("Deleted Dan") })
        page.addView(dan)
        page.addView(row(button("Open dialog") { openDialog() }, counter(), button("Ђорђе") { report("Clicked Ђорђе") }))
        page.addView(
            row(
                CheckBox(this).apply {
                    text = "Subscribe"
                    setOnCheckedChangeListener { _, checked -> report(if (checked) "Checked Subscribe" else "Unchecked Subscribe") }
                },
                // On purpose a non-breaking hyphen (U+2011), as Android's own Wi-Fi labels have.
                Switch(this).apply {
                    text = "Wi\u2011Fi"
                    setOnCheckedChangeListener { _, on -> report(if (on) "Wi\u2011Fi on" else "Wi\u2011Fi off") }
                },
                button("Cover Dan") { cover(dan) },
            ),
        )
        page.addView(
            SeekBar(this).apply {
                // No name on purpose: Kestrel numbers it, and "increment N" moves it.
                id = R.id.seek_bar
                max = 100
                progress = 50
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = report("Seek bar $value")

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit

                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            },
        )
        page.addView(
            ComposeView(this).apply {
                setContent {
                    MaterialTheme {
                        var value by remember { mutableFloatStateOf(0.5f) }
                        Slider(
                            value = value,
                            onValueChange = {
                                value = it
                                report("Slider ${String.format(Locale.ROOT, "%.2f", it)}")
                            },
                            steps = 19,
                            modifier = Modifier.semantics { contentDescription = "Speed slider" },
                        )
                    }
                }
            },
        )
        page.addView(
            // Determinate, so it has a range but offers no action: Kestrel must not number it.
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                id = R.id.progress_bar
                isIndeterminate = false
                max = 100
                progress = 40
            },
        )
        page.addView(detailsRow())
        val target = text("Drop here").apply {
            id = R.id.drop_target
            setBackgroundColor(0xFFC8E6C9.toInt())
        }
        val source = DragSourceView(this).apply {
            id = R.id.drag_source
            text = "Drag me"
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFFFFE0B2.toInt())
            this.target = target
            onDropped = { report("Dropped on Drop here") }
        }
        page.addView(row(source, target))
        page.addView(pager(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
    }

    private fun report(text: String) {
        status.text = text
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (view in views) addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun text(label: String) = TextView(this).apply {
        text = label
        setPadding(0, dp(12), 0, dp(12))
    }

    private fun counter() = button("Counter") { report("Clicked Counter") }.apply {
        setOnLongClickListener {
            longPresses++
            report(if (longPresses == 1) "Long-pressed 1 time" else "Long-pressed $longPresses times")
            true
        }
    }

    // Covers [row] with a window of its own that has nothing to tap, as a progress window does. The "Which one?" numbers
    // stay, because no actionable element changes, but a finger can no longer reach the row's "Delete".
    private fun cover(row: View) {
        val cover = text("Covered").apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        val left = IntArray(2).also(row::getLocationOnScreen)[0]
        // Three rows high and the screen's width, so that the window covers the row whole.
        PopupWindow(cover, resources.displayMetrics.widthPixels, row.height * 3, false).showAsDropDown(row, -left, -2 * row.height)
        report("Covered Dan")
    }

    // A full-screen dialog covers the page, so its "Delete" is the only one that a finger, and Kestrel, can reach.
    private fun openDialog() {
        val dialog = Dialog(this, android.R.style.Theme_Material_Light_NoActionBar)
        var deletes = 0
        val dialogStatus = text("Draft").apply { id = R.id.dialog_status }
        dialog.setContentView(
            LinearLayout(this).apply {
                id = R.id.commands_dialog
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(48), dp(16), dp(16))
                addView(dialogStatus)
                addView(
                    button("Delete") {
                        deletes++
                        dialogStatus.text = if (deletes == 1) "Deleted 1 time" else "Deleted $deletes times"
                    },
                )
                addView(button("Close") { dialog.dismiss() })
            },
        )
        dialog.show()
    }

    // A row that offers expand or collapse to accessibility services, as a notification does. Only the row is visible
    // to them, named "Details".
    private fun detailsRow(): View {
        val more = text("Saved at noon").apply {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return LinearLayout(this).apply {
            id = R.id.details_row
            orientation = LinearLayout.VERTICAL
            contentDescription = "Details"
            addView(text("Details").apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            addView(more)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    val expanded = more.isVisible
                    info.addAction(
                        if (expanded) AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE else AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND,
                    )
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    val expand = when (action) {
                        AccessibilityNodeInfo.ACTION_EXPAND -> true
                        AccessibilityNodeInfo.ACTION_COLLAPSE -> false
                        else -> return super.performAccessibilityAction(host, action, args)
                    }
                    more.visibility = if (expand) View.VISIBLE else View.GONE
                    report(if (expand) "Expanded Details" else "Collapsed Details")
                    return true
                }
            }
        }
    }

    // Three pages side by side; the status names the page that is in view once a scroll has moved.
    private fun pager() = HorizontalScrollView(this).apply {
        id = R.id.pager
        val width = resources.displayMetrics.widthPixels - dp(32)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (number in 1..3) {
                    addView(text("Page $number"), LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.MATCH_PARENT))
                }
            },
        )
        setOnScrollChangeListener { _, x, _, _, _ -> report("Showing page ${(x + width / 2) / width + 1}") }
    }
}

/**
 * Reports a long press that moved onto [target] and was lifted there, as a launcher's icon is dragged onto a folder.
 * It reads raw touches, so Kestrel must drag it with a gesture.
 */
class DragSourceView(context: Context) : TextView(context) {
    var target: View? = null
    var onDropped: () -> Unit = {}
    private var downAt = 0L

    // On purpose: the source reads raw touches and offers no click action.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downAt = event.eventTime
            MotionEvent.ACTION_UP -> {
                val drop = target ?: return true
                val origin = IntArray(2).also(drop::getLocationOnScreen)
                val inside = event.rawX.toInt() in origin[0] until origin[0] + drop.width &&
                    event.rawY.toInt() in origin[1] until origin[1] + drop.height
                if (inside && event.eventTime - downAt >= LONG_PRESS_MILLIS) onDropped()
            }
        }
        return true
    }

    private companion object {
        const val LONG_PRESS_MILLIS = 500L
    }
}
