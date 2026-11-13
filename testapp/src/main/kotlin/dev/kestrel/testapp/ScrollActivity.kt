package dev.kestrel.testapp

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A long list of rows. With the extra `swipeOnly`, the list offers no scroll actions to accessibility services, so
 * that Kestrel has to scroll it with a swipe gesture, and it fills only the upper half of the page. With the extra
 * `pages`, the list is the first of two pages side by side in a horizontal scroll view, like tabs. With the extra
 * `opaquePager`, the same pager hides its axis from accessibility services, as Flutter's pagers do.
 */
class ScrollActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val swipeOnly = intent.getBooleanExtra(EXTRA_SWIPE_ONLY, false)
        val pages = intent.getBooleanExtra(EXTRA_PAGES, false)
        val opaquePager = intent.getBooleanExtra(EXTRA_OPAQUE_PAGER, false)
        val page = fixturePage(
            when {
                swipeOnly -> "Swipe-only scroll fixture"
                pages -> "Pager scroll fixture"
                opaquePager -> "Opaque pager scroll fixture"
                else -> "Scroll fixture"
            },
        )
        when {
            swipeOnly -> {
                page.addView(SwipeOnlyScrollView(this).apply { addView(rows("Row")) }, restOfThePage())
                // Below the list, so that a swipe across the whole window would start outside the list.
                page.addView(TextView(this).apply { text = "Below the list" }, restOfThePage())
            }
            pages || opaquePager -> {
                val strip = LinearLayout(this)
                for (prefix in listOf("Row", "Second page row")) {
                    // Each page is as wide as the screen, so the second page starts outside the screen.
                    val width = resources.displayMetrics.widthPixels
                    strip.addView(ScrollView(this).apply { addView(rows(prefix)) }, LinearLayout.LayoutParams(width, MATCH))
                }
                val pager = if (opaquePager) OpaquePager(this) else HorizontalScrollView(this)
                page.addView(pager.apply { addView(strip, FrameLayout.LayoutParams(WRAP, MATCH)) }, restOfThePage())
            }
            else -> page.addView(ScrollView(this).apply { addView(rows("Row")) }, restOfThePage())
        }
    }

    /** Shares the rest of the page equally with the page's other children that use it. */
    private fun restOfThePage() = LinearLayout.LayoutParams(MATCH, 0, 1f)

    private fun rows(prefix: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        for (row in 1..ROWS) {
            addView(TextView(context).apply {
                text = "$prefix $row"
                setPadding(0, dp(12), 0, dp(12))
            })
        }
    }

    companion object {
        const val EXTRA_SWIPE_ONLY = "swipeOnly"
        const val EXTRA_PAGES = "pages"
        const val EXTRA_OPAQUE_PAGER = "opaquePager"
        private const val ROWS = 60
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/**
 * Scrolls with touch but hides its scroll actions from accessibility services and refuses them. It still says that it
 * can scroll, as such lists do, so Kestrel knows where to swipe.
 */
class SwipeOnlyScrollView(context: Context) : ScrollView(context) {
    private val scrollActions = listOf(
        AccessibilityAction.ACTION_SCROLL_FORWARD,
        AccessibilityAction.ACTION_SCROLL_BACKWARD,
        AccessibilityAction.ACTION_SCROLL_DOWN,
        AccessibilityAction.ACTION_SCROLL_UP,
    )

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        scrollActions.forEach(info::removeAction)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        scrollActions.none { it.id == action } && super.performAccessibilityAction(action, arguments)
}

/**
 * A horizontal pager that hides its axis from accessibility services, as Flutter's pagers do: it reports a plain view
 * that offers only the forward and backward scroll actions.
 */
class OpaquePager(context: Context) : HorizontalScrollView(context) {
    override fun getAccessibilityClassName(): CharSequence = View::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.removeAction(AccessibilityAction.ACTION_SCROLL_LEFT)
        info.removeAction(AccessibilityAction.ACTION_SCROLL_RIGHT)
    }
}
