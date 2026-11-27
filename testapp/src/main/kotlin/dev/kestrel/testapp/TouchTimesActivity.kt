package dev.kestrel.testapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

/**
 * A pad whose status line gives the event times of the last touch on it, in milliseconds of `SystemClock.uptimeMillis()`,
 * as "Down <time>, up <time>", so that a test can check the times that its virtual touchscreen gives its touches.
 */
class TouchTimesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Touch times fixture")
        val status = page.addStatus()
        page.addView(
            TouchTimesView(this).apply {
                contentDescription = "Time pad"
                onTouch = { down, up -> status.text = "Down $down, up $up" }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }
}

/** Reports the event times of the down and the up of each touch on it. */
class TouchTimesView(context: Context) : View(context) {
    var onTouch: (down: Long, up: Long) -> Unit = { _, _ -> }

    init {
        setBackgroundColor(0xFFE8F5E9.toInt())
    }

    // On purpose: the pad reads raw touches and offers no click action.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) onTouch(event.downTime, event.eventTime)
        return true
    }
}
