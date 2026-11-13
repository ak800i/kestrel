package dev.kestrel.testapp

import android.app.Activity
import android.content.Context
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Shows a vertical page with a [title]. The page stays clear of the system bars, so that a tap at the centre of
 * one of its elements reaches that element.
 */
internal fun Activity.fixturePage(title: String): LinearLayout {
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left + dp(16), bars.top + dp(8), bars.right + dp(16), bars.bottom + dp(8))
            insets
        }
    }
    page.addView(TextView(this).apply {
        text = title
        textSize = 22f
    })
    setContentView(page)
    return page
}

/** Adds the status line, where a fixture reports what happened to it. */
internal fun LinearLayout.addStatus(): TextView = TextView(context).apply {
    id = R.id.status
    text = "Ready"
}.also(::addView)

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
