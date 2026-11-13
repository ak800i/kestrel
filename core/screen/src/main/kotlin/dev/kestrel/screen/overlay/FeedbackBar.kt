package dev.kestrel.screen.overlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager.LayoutParams
import android.widget.TextView

/**
 * A thin bar at the top of the screen with what Kestrel heard and does. It never takes touches, and its text
 * fades after a few seconds without a new one (design spec, section 8.9). Use it on the main thread.
 */
internal class FeedbackBar(private val host: OverlayHost) {
    private val handler = Handler(Looper.getMainLooper())
    private val fade = Runnable { view.animate().alpha(0f).setDuration(FADE_MILLIS).withEndAction(::hide) }
    private val view = TextView(host.context).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(0xCC000000.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
        setPadding(padding, padding, padding, padding)
    }

    /** The text on the bar now, or null while the bar is hidden. */
    @Volatile
    var text: String? = null
        private set

    fun show(text: String) {
        this.text = text
        handler.removeCallbacks(fade)
        view.animate().cancel()
        view.alpha = 1f
        view.text = text
        host.show(view, params())
        handler.postDelayed(fade, FADE_AFTER_MILLIS)
    }

    fun hide() {
        handler.removeCallbacks(fade)
        text = null
        host.hide(view)
    }

    private fun params() = LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP
        setTitle("Kestrel feedback bar")
    }

    private companion object {
        const val FADE_AFTER_MILLIS = 4_000L
        const val FADE_MILLIS = 300L
    }
}
