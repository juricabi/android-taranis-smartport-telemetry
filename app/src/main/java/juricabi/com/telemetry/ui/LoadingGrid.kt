package juricabi.com.telemetry.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A tiny grid of tiles under the heading readout that fills, cell by
 * cell, as the 3D ground dresses — the pager's own count, drawn as the
 * thing it counts. A cell flipping on is the smallest visual event there
 * is, where a sweeping arc was motion the eye kept catching; at rest the
 * grid is a fully lit block that reads as texture. It fades in once when
 * the view opens, stands for the whole session, and leaves with the view.
 */
class LoadingGrid(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    companion object {
        private const val SIDE = 5
        private const val CELLS = SIDE * SIDE
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** A dark echo under each cell, so the grid reads over sky and snow. */
    private val seat = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }

    private val pending = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF }

    /** The live arrow's blue: the ground loads in the flight's own color. */
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF29B6F6.toInt() }

    private val cell = RectF()
    private var filled = CELLS
    private var shown = false

    init {
        alpha = 0f
        visibility = GONE
    }

    /** The pager's count after a pass; whole ground lights every cell. */
    fun show(done: Int, total: Int) {
        val now = if (total <= 0) CELLS
            else Math.round(CELLS.toFloat() * done.coerceIn(0, total) / total)
        if (now != filled) {
            filled = now
            invalidate()
        }
        if (!shown) {
            shown = true
            visibility = VISIBLE
            animate().alpha(1f).setDuration(250).start()
        }
    }

    /** Leaving the 3D view; nothing to ease, the whole screen is changing. */
    fun hide() {
        if (!shown) return
        shown = false
        animate().cancel()
        alpha = 0f
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        val pitch = width.toFloat() / SIDE
        val gap = dp(1.2f)
        val round = dp(1f)
        val drop = dp(0.8f)
        for (i in 0 until CELLS) {
            val x = (i % SIDE) * pitch
            val y = (i / SIDE) * pitch
            cell.set(x + gap, y + gap, x + pitch - gap, y + pitch - gap)
            cell.offset(drop, drop)
            canvas.drawRoundRect(cell, round, round, seat)
            cell.offset(-drop, -drop)
            canvas.drawRoundRect(cell, round, round, if (i < filled) lit else pending)
        }
    }
}
