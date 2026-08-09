package juricabi.com.telemetry.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A small ring that fills as the 3D ground dresses, sitting under the
 * heading readout. Twenty seconds of a world quietly rebuilding read as a
 * hang; this is the pager's own count, drawn instead of said. It fades in
 * when there is ground on the way and fades itself out when the ground is
 * whole — nothing is shown over a world that is already there.
 */
class LoadingRing(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** A dark seat under the strokes, so the ring reads over bright sky. */
    private val seat = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
        color = 0x38000000
    }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = 0x4DFFFFFF
    }

    /** The live arrow's blue: the ground loads in the flight's own color. */
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        color = 0xFF29B6F6.toInt()
    }

    private val arc = RectF()
    private var sweep = 0f
    private var shown = false

    init {
        alpha = 0f
        visibility = GONE
    }

    /** The pager's count after a pass; decides for itself whether to show. */
    fun show(done: Int, total: Int) {
        if (total <= 0 || done >= total) {
            hide()
            return
        }
        sweep = 360f * done / total
        invalidate()
        if (!shown) {
            shown = true
            visibility = VISIBLE
            animate().alpha(1f).setDuration(250).start()
        }
    }

    fun hide() {
        if (!shown) return
        shown = false
        animate().alpha(0f).setDuration(450)
            .withEndAction { if (!shown) visibility = GONE }
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = seat.strokeWidth / 2 + dp(1f)
        arc.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(arc, 0f, 360f, false, seat)
        canvas.drawArc(arc, 0f, 360f, false, track)
        canvas.drawArc(arc, -90f, sweep, false, fill)
    }
}
