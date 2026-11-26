package dev.kestrel.screen.overlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.kestrel.screen.model.IntRect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Kestrel's card at the bottom of the screen (design 10.3 of sub-project 7; spec 12.4): a question, its buttons and,
 * for a pick over many numbers, a compact keypad, which gives only a number of the pick's range and whose Move key
 * takes the card to the other end of the screen, so that it never hides a number that the owner needs. Unlike the other
 * overlays it takes touches, but only on itself, and it drops a touch that passed through another app's window above it
 * (design 12.2). A card ignores taps for its first [CARD_ARMING_MILLIS], and again after its Move key took it to its
 * new place, and a new card appears no sooner than [CARD_ARMING_MILLIS] after the previous one closed, so that a tap
 * aimed at an old card's button never answers a new one (design D32 item 21 of sub-project 5); that wait runs in
 * [scope], so that it depends on no single caller. A new question replaces the card, whose caller gets
 * [CardEnd.Withdrawn]: `:app`'s card desk gives every question one card, whichever channel asks it. Use it on the main
 * thread.
 */
internal class QuestionCard(
    private val host: OverlayHost,
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    private val presence = CardPresence(clock)

    // The card of the newest question, from its ask until its end, also while it waits out the arming delay.
    private var current: Card? = null

    /**
     * Whether a card is on the screen, or ended less than one settle ago ([CardPresence]); `ScreenActions` acts on
     * nothing while it is (design D32 item 20 of sub-project 5).
     */
    val isShowing: Boolean get() = presence.isShowing

    /**
     * The card's bounds on the screen once Android has laid it out, also while it closes, or null while no card counts
     * as shown.
     */
    val bounds: IntRect? get() = presence.bounds

    /**
     * Shows [question] and returns the owner's tap or number, or [CardEnd.Withdrawn] when another question replaces the
     * card, [dismiss] ends it, or no card can show because the session has closed. Cancelling the call removes the
     * card.
     */
    suspend fun ask(question: CardQuestion): CardEnd {
        current?.end(CardEnd.Withdrawn)
        val card = Card(question)
        current = card
        appear(card)
        try {
            return card.answer.await()
        } finally {
            card.end(CardEnd.Withdrawn)
        }
    }

    /** Ends the card, also one that still waits out the arming delay, without the owner's answer. */
    fun dismiss() {
        current?.end(CardEnd.Withdrawn)
    }

    // The card shows once the arming delay after the previous card has passed, unless it ended meanwhile. A wait that
    // the session's end cancels gives the card's caller no answer.
    private fun appear(card: Card) {
        scope.launch {
            presence.closedAt?.let { closed ->
                val wait = closed + CARD_ARMING_MILLIS - clock()
                if (wait > 0) delay(wait)
            }
            if (card.open) card.attach()
        }.invokeOnCompletion { if (!card.attached) card.end(CardEnd.Withdrawn) }
    }

    // A key of the card's keypad.
    private sealed interface PadKey {
        data class Digit(val digit: Char) : PadKey

        data object Delete : PadKey

        data object Enter : PadKey

        data object Move : PadKey
    }

    private inner class Card(val question: CardQuestion) {
        val answer = CompletableDeferred<CardEnd>()
        var attached = false
            private set
        private var shownAt = 0L
        private val digits = StringBuilder()
        private var entered: TextView? = null
        private var enter: Button? = null
        private var atTop = false

        val open: Boolean get() = !answer.isCompleted

        private val view: LinearLayout by lazy {
            build().apply {
                addOnLayoutChangeListener { laid, _, _, _, _, _, _, _, _ ->
                    if (current === this@Card && attached) {
                        val at = IntArray(2)
                        laid.getLocationOnScreen(at)
                        presence.laidOut(IntRect(at[0], at[1], at[0] + laid.width, at[1] + laid.height))
                    }
                }
            }
        }

        fun attach() {
            if (!host.show(view, params(atTop))) {
                end(CardEnd.Withdrawn)
                return
            }
            attached = true
            shownAt = clock()
            presence.shown()
        }

        /** Removes the card and gives its caller [end], unless it has an answer already. */
        fun end(end: CardEnd) {
            if (!answer.complete(end)) return
            if (current === this) current = null
            if (attached) {
                host.hide(view)
                presence.closed()
            }
        }

        // A tap in the card's first CARD_ARMING_MILLIS may have been aimed at the card before it.
        private fun armed(): Boolean = attached && clock() - shownAt >= CARD_ARMING_MILLIS

        private fun tapped(button: Int) {
            if (armed()) end(CardEnd.Tapped(button))
        }

        private fun pressed(key: PadKey) {
            if (!armed()) return
            when (key) {
                is PadKey.Digit -> if (digits.length < MAX_DIGITS) digits.append(key.digit)
                PadKey.Delete -> if (digits.isNotEmpty()) digits.deleteCharAt(digits.lastIndex)
                PadKey.Enter -> {
                    entered()?.let { end(CardEnd.Number(it)) }
                    return
                }
                PadKey.Move -> {
                    move()
                    return
                }
            }
            entered?.text = digits.toString()
            enter?.isEnabled = entered() != null
        }

        // The number typed so far, when the keypad gives it: a number outside the pick's range gives nothing.
        private fun entered(): Int? = digits.toString().toIntOrNull()?.takeIf { question.pad?.takes(it) == true }

        // The card goes to the other end of the screen and keeps its number. It arms again there, because a tap may
        // have been aimed at what was under the card's new place (review L2).
        private fun move() {
            host.hide(view)
            atTop = !atTop
            if (!host.show(view, params(atTop))) {
                end(CardEnd.Withdrawn)
                return
            }
            shownAt = clock()
        }

        private fun build(): LinearLayout {
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
                // Design 12.2 of sub-project 7: a touch that passed through another app's window above the card answers
                // nothing.
                filterTouchesWhenObscured = true
                addView(TextView(context).apply {
                    text = question.text
                    setTextColor(Color.BLACK)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                })
                question.pad?.let { pad -> addView(keypad(pad)) }
                addView(LinearLayout(context).apply {
                    // More than three buttons stack, so that a pick's four choices and Cancel keep their whole labels.
                    orientation = if (question.buttons.size > ROW_BUTTONS) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    question.buttons.forEachIndexed { index, label -> addView(button(label) { tapped(index) }) }
                })
            }
        }

        // Low, so that it covers little of the numbers: the number as the owner types it with the deletion, the entry
        // and the move, and then the digits, five to a row.
        private fun keypad(pad: NumberPad): LinearLayout {
            val context = host.context
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(context).apply {
                        setTextColor(Color.BLACK)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                        gravity = Gravity.CENTER
                        entered = this
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    for (key in listOf(PadKey.Delete, PadKey.Enter, PadKey.Move)) addView(padButton(pad, key))
                })
                for (row in listOf("12345", "67890")) {
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        for (digit in row) addView(padButton(pad, PadKey.Digit(digit)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    })
                }
            }
        }

        private fun padButton(pad: NumberPad, key: PadKey): Button {
            val label = when (key) {
                is PadKey.Digit -> key.digit.toString()
                PadKey.Delete -> pad.delete
                PadKey.Enter -> pad.enter
                PadKey.Move -> pad.move
            }
            return button(label) { pressed(key) }.also { button ->
                if (key == PadKey.Enter) {
                    button.isEnabled = false
                    enter = button
                }
            }
        }

        private fun button(label: String, onTap: () -> Unit): Button = Button(host.context).apply {
            text = label
            filterTouchesWhenObscured = true
            setOnClickListener { onTap() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }

    // At the bottom, or at the top once the keypad's Move key took the card there.
    private fun params(top: Boolean) = LayoutParams(
        host.context.resources.displayMetrics.widthPixels - 2 * dp(16f).toInt(),
        LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Touches outside the card go to the app below; Kestrel's touch watcher reports them (section 14.1).
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        fitInsetsTypes = WindowInsets.Type.systemBars()
        y = dp(16f).toInt()
        setTitle(CARD_TITLE)
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, host.context.resources.displayMetrics)

    private companion object {
        /** The keypad's longest number: the numbered grid has at most a few hundred numbers. */
        const val MAX_DIGITS = 3

        const val ROW_BUTTONS = 3
    }
}
