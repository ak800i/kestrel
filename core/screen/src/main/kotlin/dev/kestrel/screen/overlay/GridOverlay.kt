package dev.kestrel.screen.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View

/** The grid overlay (design spec, section 9.4). Use it on the main thread. */
internal class GridOverlay(private val host: OverlayHost) {
    private val view = GridView(host.context)

    /** The grid on the screen now, or null while it is hidden. */
    var grid: Grid? = null
        private set

    fun show(grid: Grid) {
        this.grid = grid
        view.grid = grid
        host.show(view, OverlayHost.fullScreen("Kestrel grid"))
    }

    fun hide() {
        grid = null
        host.hide(view)
    }
}

/** Draws the grid's lines and a number in the middle of each cell. */
internal class GridView(context: Context) : View(context) {
    var grid: Grid? = null
        set(value) {
            field = value
            invalidate()
        }

    private val origin = IntArray(2)
    private val line = Paint().apply {
        color = 0xCC1565C0.toInt()
        strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        val grid = grid ?: return
        getLocationOnScreen(origin)
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        for (cell in 1..grid.cellCount) {
            val bounds = grid.cell(cell)
            canvas.drawLine(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.top.toFloat(), line)
            canvas.drawLine(bounds.left.toFloat(), bounds.top.toFloat(), bounds.left.toFloat(), bounds.bottom.toFloat(), line)
            val baseline = bounds.centerY - (number.ascent() + number.descent()) / 2
            canvas.drawText(cell.toString(), bounds.centerX.toFloat(), baseline, number)
        }
    }
}
