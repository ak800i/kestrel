package dev.kestrel.screen.overlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.kestrel.screen.R
import dev.kestrel.screen.model.IntRect
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A card at the bottom of the screen with a question and Confirm and Cancel buttons (design spec, section 12.4).
 * Unlike the other overlays it takes touches, but only on itself. A card ignores taps for its first [ARMING_MILLIS], and
 * a new card appears no sooner than [ARMING_MILLIS] after the previous one closed, so that a tap aimed at an old card's
 * button never answers a new one (design D32 item 21 of sub-project 5). Use it on the main thread.
 */
internal class ConfirmationCard(private val host: OverlayHost, private val clock: () -> Long = SystemClock::uptimeMillis) {
    private var pending: Question? = null
    private val presence = CardPresence(clock)

    // Every ask and dismiss increases it, so that a question that waits out the arming delay knows when a newer question
    // or a dismiss came meanwhile.
    private var questions = 0L

    /**
     * Whether a card is on the screen, or ended less than one settle ago ([CardPresence]); `ScreenActions` acts on nothing
     * while it is (design D32 item 20 of sub-project 5).
     */
    val isShowing: Boolean get() = presence.isShowing

    /** The card's bounds on the screen once Android has laid it out, also while it closes, or null while no card counts as shown. */
    val bounds: IntRect? get() = presence.bounds

    /**
     * Shows [question] and returns true when the owner taps Confirm, or false for Cancel. It also returns false when a
     * newer question replaces this one or [dismiss] ends it, also while it still waits out the arming delay, and its card
     * then goes at once or never shows. Cancelling the call removes the card too. When the card cannot be shown, because
     * the session has closed, it returns false at once.
     */
    suspend fun ask(question: String): Boolean {
        dismiss()
        val number = questions
        presence.closedAt?.let { closed ->
            val wait = closed + ARMING_MILLIS - clock()
            if (wait > 0) delay(wait)
        }
        if (questions != number) return false
        return suspendCancellableCoroutine { continuation ->
            val open = Question(question, continuation)
            // Cancellation can arrive on any thread, and the window must be removed on the main thread.
            continuation.invokeOnCancellation { open.card.post { open.end(false) } }
            pending = open
            if (host.show(open.card, params())) presence.shown() else open.end(false)
        }
    }

    /**
     * Ends an open question, also one that still waits out the arming delay, without the owner's answer: its card goes at
     * once or never shows, and its [ask] returns false.
     */
    fun dismiss() {
        questions++
        pending?.end(false)
    }

    private inner class Question(question: String, private val continuation: CancellableContinuation<Boolean>) {
        private val shownAt = clock()

        val card = build(question, onConfirm = { tapped(true) }, onCancel = { tapped(false) }).apply {
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                if (pending === this@Question) {
                    val at = IntArray(2)
                    view.getLocationOnScreen(at)
                    presence.laidOut(IntRect(at[0], at[1], at[0] + view.width, at[1] + view.height))
                }
            }
        }

        // A tap in the card's first ARMING_MILLIS may have been aimed at the card before it.
        private fun tapped(answer: Boolean) {
            if (clock() - shownAt >= ARMING_MILLIS) end(answer)
        }

        /** Removes the card and returns [answer] to the caller, unless the caller already has an answer or has cancelled. */
        fun end(answer: Boolean) {
            host.hide(card)
            // A late tap on this card, or its cancellation, may arrive after a newer question has replaced it.
            if (pending === this) {
                pending = null
                presence.closed()
            }
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

    companion object {
        /** How long a new card ignores taps, and how long after a card closed the next one may appear. */
        const val ARMING_MILLIS = 600L
    }
}
