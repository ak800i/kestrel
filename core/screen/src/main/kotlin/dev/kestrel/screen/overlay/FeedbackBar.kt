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
 * fades after a few seconds without a new one (design spec, section 8.9), except a kept text, such as an instant
 * command's question, which stays until it is released or the next text replaces it. Use it on the main thread.
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

    // Whether the text on the bar stays until release().
    private var kept = false

    /** Shows [text]. With [keep], it stays until [release] or the next text; otherwise it fades after a few seconds. */
    fun show(text: String, keep: Boolean = false) {
        handler.removeCallbacks(fade)
        view.animate().cancel()
        // While a screenshot of the display runs, host.show makes the bar transparent again until it is taken.
        view.alpha = 1f
        view.text = text
        if (host.show(view, params())) {
            this.text = text
            kept = keep
            if (!keep) handler.postDelayed(fade, FADE_AFTER_MILLIS)
        } else {
            this.text = null
            kept = false
        }
    }

    /** A kept text fades from now on, as any other text does. */
    fun release() {
        if (!kept || text == null) return
        kept = false
        handler.postDelayed(fade, FADE_AFTER_MILLIS)
    }

    fun hide() {
        handler.removeCallbacks(fade)
        text = null
        kept = false
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
