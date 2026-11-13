package dev.kestrel.testapp

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A long list of rows. With the extra `swipeOnly`, the list offers no scroll actions to accessibility services,
 * so that Kestrel has to scroll it with a swipe gesture.
 */
class ScrollActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val swipeOnly = intent.getBooleanExtra(EXTRA_SWIPE_ONLY, false)
        val page = fixturePage(if (swipeOnly) "Swipe-only scroll fixture" else "Scroll fixture")
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (row in 1..ROWS) {
            rows.addView(TextView(this).apply {
                text = "Row $row"
                setPadding(0, dp(12), 0, dp(12))
            })
        }
        val list = if (swipeOnly) SwipeOnlyScrollView(this) else ScrollView(this)
        list.addView(rows)
        page.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    companion object {
        const val EXTRA_SWIPE_ONLY = "swipeOnly"
        private const val ROWS = 60
    }
}

/** Scrolls with touch but hides its scroll actions from accessibility services and refuses them. */
class SwipeOnlyScrollView(context: Context) : ScrollView(context) {
    private val scrollActions = listOf(
        AccessibilityAction.ACTION_SCROLL_FORWARD,
        AccessibilityAction.ACTION_SCROLL_BACKWARD,
        AccessibilityAction.ACTION_SCROLL_DOWN,
        AccessibilityAction.ACTION_SCROLL_UP,
    )

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = false
        scrollActions.forEach(info::removeAction)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        scrollActions.none { it.id == action } && super.performAccessibilityAction(action, arguments)
}
