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
    private var pending: Question? = null

    /**
     * Shows [question] and returns true when the owner taps Confirm, or false for Cancel. It also returns false when a
     * newer question replaces this one or [dismiss] ends it, and its card then goes at once. Cancelling the call
     * removes the card too.
     */
    suspend fun ask(question: String): Boolean = suspendCancellableCoroutine { continuation ->
        dismiss()
        val open = Question(question, continuation)
        // Cancellation can arrive on any thread, and the window must be removed on the main thread.
        continuation.invokeOnCancellation { open.card.post { open.end(false) } }
        pending = open
        host.show(open.card, params())
    }

    /** Ends an open question without the owner's answer: its card goes at once, and its [ask] returns false. */
    fun dismiss() {
        pending?.end(false)
    }

    private inner class Question(question: String, private val continuation: CancellableContinuation<Boolean>) {
        val card = build(question, onConfirm = { end(true) }, onCancel = { end(false) })

        /** Removes the card and returns [answer] to the caller, unless the caller already has an answer or has cancelled. */
        fun end(answer: Boolean) {
            host.hide(card)
            // A late tap on this card, or its cancellation, may arrive after a newer question has replaced it.
            if (pending === this) pending = null
            if (continuation.isActive) continuation.resume(answer)
        }
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
