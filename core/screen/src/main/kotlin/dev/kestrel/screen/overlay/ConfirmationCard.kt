package dev.kestrel.screen.overlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.kestrel.screen.R
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A card at the bottom of the screen with a question and Confirm and Cancel buttons (design spec, section 12.4).
 * Unlike the other overlays it takes touches, but only on itself. Use it on the main thread.
 */
internal class ConfirmationCard(private val host: OverlayHost) {
    private var pending: CancellableContinuation<Boolean>? = null

    /** Shows [question] and returns true when the owner taps Confirm, or false for Cancel. */
    suspend fun ask(question: String): Boolean = suspendCancellableCoroutine { continuation ->
        dismiss()
        lateinit var card: LinearLayout
        fun answer(confirmed: Boolean) {
            host.hide(card)
            // A newer question may have replaced this one while its card was still on the screen.
            if (pending === continuation) pending = null
            if (continuation.isActive) continuation.resume(confirmed)
        }
        card = build(question, onConfirm = { answer(true) }, onCancel = { answer(false) })
        // Cancellation can arrive on any thread, and the window must be removed on the main thread.
        continuation.invokeOnCancellation {
            card.post {
                host.hide(card)
                if (pending === continuation) pending = null
            }
        }
        pending = continuation
        host.show(card, params())
    }

    /** Ends an open question without an answer; the caller's [ask] is cancelled. */
    fun dismiss() {
        pending?.cancel()
        pending = null
    }

    private fun build(question: String, onConfirm: () -> Unit, onCancel: () -> Unit): LinearLayout {
        val context = host.context
        val padding = dp(16f).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16f)
            }
            elevation = dp(8f)
            addView(TextView(context).apply {
                text = question
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                addView(Button(context).apply {
                    setText(R.string.screen_cancel)
                    setOnClickListener { onCancel() }
                })
                addView(Button(context).apply {
                    setText(R.string.screen_confirm)
                    setOnClickListener { onConfirm() }
                })
            })
        }
    }

    private fun params() = LayoutParams(
        host.context.resources.displayMetrics.widthPixels - 2 * dp(16f).toInt(),
        LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Touches outside the card go to the app below; Kestrel's touch watcher reports them (section 14.1).
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        fitInsetsTypes = WindowInsets.Type.systemBars()
        y = dp(16f).toInt()
        setTitle("Kestrel confirmation")
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, host.context.resources.displayMetrics)
}
