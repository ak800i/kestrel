package dev.kestrel.screen.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import dev.kestrel.screen.model.IntRect

/** A short text drawn at the top-left corner of an element. */
internal data class Tag(val text: String, val bounds: IntRect)

/** Draws tags over a full-screen overlay. The element bounds are in screen coordinates. */
internal class TagsView(context: Context) : View(context) {
    var tags: List<Tag> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var fillColor: Int
        get() = fill.color
        set(value) {
            fill.color = value
            invalidate()
        }

    private val padding = dp(4f)
    private val corner = dp(4f)
    private val origin = IntArray(2)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(origin)
        for (tag in tags) {
            val left = (tag.bounds.left - origin[0]).toFloat()
            val top = (tag.bounds.top - origin[1]).toFloat()
            val right = left + text.measureText(tag.text) + 2 * padding
            val bottom = top + text.descent() - text.ascent() + 2 * padding
            canvas.drawRoundRect(left, top, right, bottom, corner, corner, fill)
            canvas.drawText(tag.text, left + padding, top + padding - text.ascent(), text)
        }
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
