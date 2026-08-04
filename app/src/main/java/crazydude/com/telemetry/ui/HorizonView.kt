package crazydude.com.telemetry.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import crazydude.com.telemetry.utils.GeoUtils

class HorizonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The attitude as last reported, and the attitude as drawn. */
    private var roll: Float = 0f
    private var pitch: Float = 0f
    private var shownRoll: Float = 0f
    private var shownPitch: Float = 0f
    private var easing = false

    private var size: Float = 0f
    private var center: Float = 0f

    private val leftLinePath = Path()
    private val rightLinePath = Path()
    private val circlePath = Path()

    private val planeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        .apply {
            strokeWidth = 5f
            style = Paint.Style.STROKE
        }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        size = w.toFloat() //336
        center = size / 2 //128

        planeLinePaint.strokeWidth = size / 67

        leftLinePath.reset()
        leftLinePath.apply {
            moveTo(center - (size / 3), center)
            lineTo(center - (size / 20), center)
            lineTo(center - (size / 20), center + (size / 33))
        }
        rightLinePath.reset()
        rightLinePath.apply {
            moveTo(center + (size / 3), center)
            lineTo(center + (size / 20), center)
            lineTo(center + (size / 20), center + (size / 33))
        }
        circlePath.reset()
        circlePath.apply {
            addCircle(center, center, center, Path.Direction.CW)
        }
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)

        canvas?.let {
            it.save()
            it.clipPath(circlePath, Region.Op.INTERSECT)
            it.rotate(-shownRoll, center, center)
            paint.color = Color.parseColor("#5BCBD3")
            it.drawRect(0f, 0f, size, center - shownPitch * (size / 67), paint)
            paint.color = Color.parseColor("#D38700")
            it.drawRect(0f, center - shownPitch * (size / 67), size, size, paint)
            paint.strokeWidth = size / 84
            paint.color = Color.BLACK
            for (i in 1..18) {
                val lineLength = if (i.rem(2) == 0) size / 6 else size / 12
                it.drawLine(
                    center - lineLength,
                    (center - shownPitch * (size / 67)) + i * (size / 13),
                    center + lineLength,
                    (center - shownPitch * (size / 67)) + i * (size / 13),
                    paint
                )
                it.drawLine(
                    center - lineLength,
                    (center - shownPitch * (size / 67)) - i * (size / 13),
                    center + lineLength,
                    (center - shownPitch * (size / 67)) - i * (size / 13),
                    paint
                )
            }
            it.restore()
            it.drawPath(leftLinePath, planeLinePaint)
            it.drawPath(rightLinePath, planeLinePaint)

            // The numbers off the same values the ball is drawn at, so the two
            // agree: read from the raw ones they ran ahead of the horizon they
            // are written across.
            drawRP( canvas, 0.16f, "Roll", this.shownRoll)
            drawRP( canvas, 0.82f, "Pitch", this.shownPitch)
        }
    }

    private fun drawRP( canvas: Canvas, pos: Float, label: String, value: Float) {
        val textBounds = Rect()

        textPaint.setTextSize(this.width/244f * 20f)

        textPaint.getTextBounds(label, 0, label.length, textBounds)
        var x = width * pos - textBounds.width() /2.0f
        var y = center * 0.92f
        canvas.drawText(label, x, y, textPaint)

        textPaint.setTextSize(this.width/244f * 30f)

        val ps = String.format("%.1f", value);
        textPaint.getTextBounds(ps, 0, ps.length, textBounds)
        x = width * pos - textBounds.width() / 2.0f
        y = center * 1.01f  + textBounds.height()

        canvas.drawText(ps, x, y, textPaint)
    }

    fun setRoll(roll: Float) {
        this.roll = roll
        keepEasing()
    }

    fun setPitch(pitch: Float) {
        this.pitch = pitch
        keepEasing()
    }

    /** Level, at once. A reset is not the aircraft rolling out. */
    fun snapLevel() {
        roll = 0f
        pitch = 0f
        shownRoll = 0f
        shownPitch = 0f
        invalidate()
    }

    /**
     * How much of the way to the last reported attitude the ball moves each
     * frame — the share the map's marker and the model in three dimensions are
     * both moved by, so all three are drawn at one pace.
     */
    private val EASE = 0.18f

    /** Between -180 and 180, which is where a roll is read from. */
    private fun wrap180(angle: Float): Float =
        ((angle + 180f) % 360f + 360f) % 360f - 180f

    /**
     * A step towards the last reported attitude, at each frame the screen draws.
     *
     * An attitude lands a few times a second and the screen draws sixty. Set
     * straight onto each reading the ball stepped, while the model, the marker
     * and the camera beside it glided — and the horizon was the one thing on
     * the screen that looked like it was falling behind the flight.
     */
    private val step = object : Runnable {
        override fun run() {
            easing = false

            // Switched off in the settings. Kept up to date, so it is right the
            // moment it is switched back on, but without a frame callback
            // running for the whole of a flight over a ball nobody can see.
            if (visibility != View.VISIBLE) {
                shownRoll = roll
                shownPitch = pitch
                return
            }

            var moving = false

            // the short way round: inverted flight crosses ±180, and lerping
            // through it takes the ball a whole lap the wrong way
            val turn = GeoUtils.shortestTurn(shownRoll, roll)
            if (Math.abs(turn) > 0.05f) {
                shownRoll = wrap180(shownRoll + turn * EASE)
                moving = true
            } else {
                shownRoll = roll
            }

            val climb = pitch - shownPitch
            if (Math.abs(climb) > 0.05f) {
                shownPitch += climb * EASE
                moving = true
            } else {
                shownPitch = pitch
            }

            invalidate()
            if (moving) keepEasing()
        }
    }

    private fun keepEasing() {
        if (easing) return
        easing = true
        postOnAnimation(step)
    }
}