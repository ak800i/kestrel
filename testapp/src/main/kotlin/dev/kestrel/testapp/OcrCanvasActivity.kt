package dev.kestrel.testapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout

/**
 * Text that only OCR can read (design D28 of sub-project 5): English, Serbian Latin and Serbian Cyrillic drawn on a
 * Canvas, large and small, dark on light and light on dark, a drawn button "Show details", and an icon button without a
 * label. The canvas has no text in the accessibility tree: it is one clickable element without a label. A tap inside the
 * drawn button shows "Details shown" in the status line, and a tap on the icon "Icon pressed".
 */
class OcrCanvasActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("OCR canvas")
        val status = page.addStatus()
        page.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setOnClickListener { status.text = "Icon pressed" }
        })
        page.addView(
            TextCanvas(this) { status.text = "Details shown" },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(340)),
        )
    }

    private class TextCanvas(context: Context, private val onButton: () -> Unit) : View(context) {
        private val button = RectF()
        private var downX = -1f
        private var downY = -1f
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isClickable = true
            setOnClickListener { if (button.contains(downX, downY)) onButton() }
        }

        // super.onTouchEvent calls performClick on a tap, which the click listener reads together with the touch's point.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.x
                downY = event.y
            }
            return super.onTouchEvent(event)
        }

        // The click goes through performClick, so that a click of Android's accessibility reaches the listener too.
        override fun performClick(): Boolean = super.performClick()

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            val left = dp(8f)
            button.set(left, dp(8f), left + dp(180f), dp(60f))
            fill.color = Color.rgb(26, 115, 232)
            canvas.drawRoundRect(button, dp(8f), dp(8f), fill)
            line(canvas, "Show details", left + dp(16f), dp(43f), 20f, Color.WHITE)
            line(canvas, "\u010ca\u010dak i \u0160abac", left, dp(110f), 22f, Color.rgb(32, 33, 36))
            line(canvas, "\u0402\u043e\u0440\u0452\u0435 \u0448\u0430\u0459\u0435 \u043f\u043e\u0440\u0443\u043a\u0443", left, dp(160f), 22f, Color.rgb(32, 33, 36))
            line(canvas, "Version 2.4.1", left, dp(200f), 12f, Color.rgb(95, 99, 104))
            fill.color = Color.rgb(18, 18, 18)
            canvas.drawRect(0f, dp(240f), width.toFloat(), dp(320f), fill)
            line(canvas, "Archived chats", left, dp(290f), 20f, Color.rgb(232, 234, 237))
        }

        private fun line(canvas: Canvas, value: String, x: Float, y: Float, sp: Float, color: Int) {
            text.color = color
            text.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
            canvas.drawText(value, x, y, text)
        }

        private fun dp(value: Float): Float = value * resources.displayMetrics.density
    }
}
