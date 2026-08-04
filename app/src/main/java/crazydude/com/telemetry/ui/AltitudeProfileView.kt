package crazydude.com.telemetry.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import crazydude.com.telemetry.utils.Elevation
import crazydude.com.telemetry.utils.GeoUtils
import java.util.Arrays
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Flight altitude against distance flown, with the terrain drawn underneath it,
 * so the clearance over the ground is visible instead of a single altitude number.
 */
class AltitudeProfileView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // Picked to stay readable on white, black and mid grey backgrounds
        private val COLOR_TERRAIN_FILL = 0xFF7A5C3E.toInt()
        private val COLOR_TERRAIN_EDGE = 0xFFC79A62.toInt()
        private val COLOR_FLIGHT = 0xFF1E9BE0.toInt()
        private val COLOR_MARKER = 0xFFFF5252.toInt()
        private val COLOR_TEXT = 0xFF9E9E9E.toInt()
        private const val COLOR_GRID = 0x409E9E9E

        private const val MAX_COLUMNS = 1000
        private const val MIN_SPAN_METERS = 10f
        private const val MSG_NO_TRACK = "No flight loaded"
        private const val MSG_NO_POSITION = "No position in this flight"
        private const val MSG_NO_TERRAIN = "terrain not downloaded"
    }

    /** lat, lon, and altitude above sea level in metres, in flight order. */
    class Point(val lat: Double, val lon: Double, val altitudeMsl: Float)

    private val density = resources.displayMetrics.density

    private val terrainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_TERRAIN_FILL
    }
    private val ridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = COLOR_TERRAIN_EDGE
    }
    private val flightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = COLOR_FLIGHT
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = COLOR_MARKER
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
        color = COLOR_GRID
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        color = COLOR_TEXT
    }
    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
        color = COLOR_MARKER
    }
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
        color = COLOR_TEXT
    }

    private val padLeft = 44f * density
    private val padRight = 16f * density
    private val padTop = 8f * density
    private val padBottom = 18f * density

    private val terrainPath = Path()
    private val ridgePath = Path()
    private val flightPath = Path()

    private var points: List<Point> = emptyList()
    private var trackValid = false
    private var totalDistance = 0f
    private var cumulative = FloatArray(0)

    private var columnCount = 0
    private var columnDistance = FloatArray(0)
    private var columnFlight = FloatArray(0)
    private var columnGroundMin = FloatArray(0)
    private var columnGroundMax = FloatArray(0)
    private var columnHasGround = BooleanArray(0)

    private var flightLo = 0f
    private var flightHi = 0f
    private var bottomMsl = 0f
    private var topMsl = 0f

    private var terrainDirty = true
    private var hasTerrain = false
    private var minClearanceValue = Float.NaN
    private var minClearanceDistance = 0f
    private var minClearanceGround = 0f
    private var minClearanceFlight = 0f
    private var clearanceLabel: String? = null

    private var gridValues = FloatArray(0)
    private var gridLabels = emptyArray<String>()
    private var distanceValues = FloatArray(0)
    private var distanceLabels = emptyArray<String>()

    private var plotLeft = 0f
    private var plotTop = 0f
    private var plotRight = 0f
    private var plotBottom = 0f
    private var xScale = 0f
    private var yScale = 0f

    fun setTrack(points: List<Point>) {
        this.points = points
        val n = points.size
        columnCount = min(n, MAX_COLUMNS)
        terrainDirty = true
        hasTerrain = false
        minClearanceValue = Float.NaN
        clearanceLabel = null
        totalDistance = 0f
        trackValid = false

        if (n < 2) {
            invalidate()
            return
        }
        if (cumulative.size != n) {
            cumulative = FloatArray(n)
        }
        if (columnDistance.size != columnCount) {
            columnDistance = FloatArray(columnCount)
            columnFlight = FloatArray(columnCount)
            columnGroundMin = FloatArray(columnCount)
            columnGroundMax = FloatArray(columnCount)
            columnHasGround = BooleanArray(columnCount)
        }
        Arrays.fill(columnFlight, Float.MAX_VALUE)

        val sums = DoubleArray(columnCount)
        val counts = IntArray(columnCount)
        flightLo = Float.MAX_VALUE
        flightHi = -Float.MAX_VALUE
        var cum = 0f
        var i = 0
        var previous: Point? = null
        for (p in points) {
            if (previous != null) {
                val step = GeoUtils.computeDistanceBetween(previous.lat, previous.lon, p.lat, p.lon)
                if (!step.isNaN() && step > 0) cum += step.toFloat()
            }
            cumulative[i] = cum
            val column = ((i.toLong() * columnCount) / n).toInt()
            sums[column] += cum.toDouble()
            counts[column]++
            val alt = p.altitudeMsl
            // lowest sample of the column: downsampling must not hide the moment we were closest to the ground
            if (alt < columnFlight[column]) columnFlight[column] = alt
            if (alt < flightLo) flightLo = alt
            if (alt > flightHi) flightHi = alt
            previous = p
            i++
        }
        totalDistance = cum

        for (j in 0 until columnCount) {
            columnDistance[j] = when {
                counts[j] > 0 -> (sums[j] / counts[j]).toFloat()
                j > 0 -> columnDistance[j - 1]
                else -> 0f
            }
            if (columnFlight[j] == Float.MAX_VALUE) {
                columnFlight[j] = if (j > 0) columnFlight[j - 1] else flightLo
            }
        }

        trackValid = totalDistance > 0f && flightLo <= flightHi
        if (trackValid) {
            buildDistanceLabels()
            updateScale(Float.MAX_VALUE, -Float.MAX_VALUE)
        }
        invalidate()
    }

    // Terrain is resampled only when the track or the loaded tiles change, never per frame
    private fun sampleTerrain() {
        terrainDirty = false
        hasTerrain = false
        minClearanceValue = Float.NaN
        clearanceLabel = null
        if (!trackValid || columnCount == 0) return

        Arrays.fill(columnHasGround, false)
        Arrays.fill(columnGroundMin, Float.MAX_VALUE)
        Arrays.fill(columnGroundMax, -Float.MAX_VALUE)
        var terrainLo = Float.MAX_VALUE
        var terrainHi = -Float.MAX_VALUE
        val n = points.size
        var i = 0
        for (p in points) {
            val column = ((i.toLong() * columnCount) / n).toInt()
            i++
            // no tile yet: the column stays a gap, a guessed height would be worse than none
            val ground = Elevation.elevationAt(p.lat, p.lon) ?: continue
            if (ground.isNaN()) continue
            columnHasGround[column] = true
            hasTerrain = true
            if (ground < columnGroundMin[column]) columnGroundMin[column] = ground
            if (ground > columnGroundMax[column]) columnGroundMax[column] = ground
            if (ground < terrainLo) terrainLo = ground
            if (ground > terrainHi) terrainHi = ground
            val clearance = p.altitudeMsl - ground
            if (minClearanceValue.isNaN() || clearance < minClearanceValue) {
                minClearanceValue = clearance
                minClearanceDistance = cumulative[i - 1]
                minClearanceGround = ground
                minClearanceFlight = p.altitudeMsl
            }
        }
        if (!minClearanceValue.isNaN()) {
            clearanceLabel = minClearanceValue.roundToInt().toString() + " m AGL"
        }
        updateScale(terrainLo, terrainHi)
    }

    private fun updateScale(terrainLo: Float, terrainHi: Float) {
        var lo = flightLo
        var hi = flightHi
        if (hasTerrain) {
            lo = min(lo, terrainLo)
            hi = max(hi, terrainHi)
        }
        if (hi - lo < MIN_SPAN_METERS) {
            val center = (hi + lo) / 2f
            lo = center - MIN_SPAN_METERS / 2f
            hi = center + MIN_SPAN_METERS / 2f
        }
        val span = hi - lo
        bottomMsl = lo - span * 0.05f
        topMsl = hi + span * 0.15f
        buildAltitudeLabels()
    }

    private fun buildAltitudeLabels() {
        val step = niceStep((topMsl - bottomMsl) / 4f)
        val values = ArrayList<Float>(8)
        val labels = ArrayList<String>(8)
        var v = ceil(bottomMsl / step) * step
        while (v <= topMsl && values.size < 8) {
            values.add(v)
            labels.add(v.roundToInt().toString() + " m")
            v += step
        }
        gridValues = FloatArray(values.size) { values[it] }
        gridLabels = labels.toTypedArray()
    }

    private fun buildDistanceLabels() {
        val kilometres = totalDistance >= 2000f
        val step = niceStep(totalDistance / 4f)
        val values = ArrayList<Float>(8)
        val labels = ArrayList<String>(8)
        var v = 0f
        while (v <= totalDistance && values.size < 8) {
            values.add(v)
            labels.add(
                if (kilometres) String.format(Locale.US, "%.1f km", v / 1000f)
                else v.roundToInt().toString() + " m"
            )
            v += step
        }
        distanceValues = FloatArray(values.size) { values[it] }
        distanceLabels = labels.toTypedArray()
    }

    private fun niceStep(raw: Float): Float {
        if (raw.isNaN() || raw <= 0f) return 1f
        val base = 10.0.pow(floor(log10(raw.toDouble())))
        val scaled = raw / base
        val nice = when {
            scaled <= 1.0 -> 1.0
            scaled <= 2.0 -> 2.0
            scaled <= 5.0 -> 5.0
            else -> 10.0
        }
        return (nice * base).toFloat()
    }

    private fun yOf(msl: Float): Float {
        val y = plotBottom - (msl - bottomMsl) * yScale
        return if (y < plotTop) plotTop else if (y > plotBottom) plotBottom else y
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (canvas == null) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        try {
            if (!trackValid) {
                val message = if (points.isEmpty()) MSG_NO_TRACK else MSG_NO_POSITION
                canvas.drawText(message, w / 2f, h / 2f + messagePaint.textSize / 3f, messagePaint)
                return
            }
            if (terrainDirty) sampleTerrain()

            plotLeft = padLeft
            plotTop = padTop
            plotRight = w - padRight
            plotBottom = h - padBottom
            if (plotRight - plotLeft < 1f || plotBottom - plotTop < 1f) return
            xScale = (plotRight - plotLeft) / totalDistance
            yScale = (plotBottom - plotTop) / (topMsl - bottomMsl)

            drawGrid(canvas)
            drawTerrain(canvas)
            drawFlight(canvas)
            drawClearance(canvas)
            drawDistanceLabels(canvas)
            if (!hasTerrain) {
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(
                    MSG_NO_TERRAIN, (plotLeft + plotRight) / 2f, plotBottom - 3f * density, textPaint
                )
            }
        } catch (e: Exception) {
            // a broken profile must never take the flight view down with it
        }
    }

    private fun drawGrid(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.RIGHT
        for (k in gridValues.indices) {
            val y = yOf(gridValues[k])
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            canvas.drawText(gridLabels[k], plotLeft - 3f * density, y + textPaint.textSize / 3f, textPaint)
        }
    }

    private fun drawTerrain(canvas: Canvas) {
        terrainPath.rewind()
        ridgePath.rewind()
        var open = false
        var lastX = plotLeft
        for (j in 0 until columnCount) {
            val x = plotLeft + columnDistance[j] * xScale
            if (columnHasGround[j]) {
                val yFill = yOf(columnGroundMin[j])
                // the highest sample of the column is stroked on top so a downsampled peak still shows
                val yRidge = yOf(columnGroundMax[j])
                if (open) {
                    terrainPath.lineTo(x, yFill)
                    ridgePath.lineTo(x, yRidge)
                } else {
                    terrainPath.moveTo(x, plotBottom)
                    terrainPath.lineTo(x, yFill)
                    ridgePath.moveTo(x, yRidge)
                    open = true
                }
                lastX = x
            } else if (open) {
                // unknown elevation ends the filled run instead of bridging it
                terrainPath.lineTo(lastX, plotBottom)
                terrainPath.close()
                open = false
            }
        }
        if (open) {
            terrainPath.lineTo(lastX, plotBottom)
            terrainPath.close()
        }
        canvas.drawPath(terrainPath, terrainPaint)
        canvas.drawPath(ridgePath, ridgePaint)
    }

    private fun drawFlight(canvas: Canvas) {
        flightPath.rewind()
        for (j in 0 until columnCount) {
            val x = plotLeft + columnDistance[j] * xScale
            val y = yOf(columnFlight[j])
            if (j == 0) flightPath.moveTo(x, y) else flightPath.lineTo(x, y)
        }
        canvas.drawPath(flightPath, flightPaint)
    }

    private fun drawClearance(canvas: Canvas) {
        val label = clearanceLabel ?: return
        val x = plotLeft + minClearanceDistance * xScale
        canvas.drawLine(x, yOf(minClearanceGround), x, yOf(minClearanceFlight), markerPaint)
        val half = markerTextPaint.measureText(label) / 2f
        var textX = x
        if (textX - half < plotLeft) textX = plotLeft + half
        if (textX + half > plotRight) textX = plotRight - half
        val textY = max(plotTop + markerTextPaint.textSize, yOf(minClearanceFlight) - 4f * density)
        canvas.drawText(label, textX, textY, markerTextPaint)
    }

    private fun drawDistanceLabels(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.CENTER
        val baseline = plotBottom + textPaint.textSize + 3f * density
        for (k in distanceValues.indices) {
            val x = plotLeft + distanceValues[k] * xScale
            canvas.drawText(distanceLabels[k], x, baseline, textPaint)
        }
    }
}
