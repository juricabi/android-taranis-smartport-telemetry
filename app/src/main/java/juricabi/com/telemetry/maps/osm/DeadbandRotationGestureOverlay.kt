package juricabi.com.telemetry.maps.osm

import android.view.MotionEvent
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import kotlin.math.abs
import kotlin.math.hypot

class DeadbandRotationGestureOverlay(mapView: MapView) : RotationGestureOverlay(mapView) {

    companion object {
        private const val ROTATION_THRESHOLD_DEG = 12f
        private const val PINCH_THRESHOLD_RATIO = 0.06f // 6% scale change
    }

    private enum class GestureMode { UNDECIDED, ZOOM, ROTATE }

    private var mode = GestureMode.UNDECIDED
    private var accumulatedRotation = 0f
    private var initialFingerDistance = 0f
    private var tracking = false

    override fun onRotate(deltaAngle: Float) {
        when (mode) {
            GestureMode.UNDECIDED -> {
                accumulatedRotation += deltaAngle
                if (abs(accumulatedRotation) >= ROTATION_THRESHOLD_DEG) {
                    mode = GestureMode.ROTATE
                    super.onRotate(accumulatedRotation)
                }
            }
            GestureMode.ROTATE -> super.onRotate(deltaAngle)
            GestureMode.ZOOM -> { /* suppress rotation during pinch-to-zoom */ }
        }
    }

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    initialFingerDistance = fingerDistance(event)
                    tracking = true
                    mode = GestureMode.UNDECIDED
                    accumulatedRotation = 0f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking && mode == GestureMode.UNDECIDED && event.pointerCount == 2) {
                    val dist = fingerDistance(event)
                    if (initialFingerDistance > 0f) {
                        val scaleChange = abs(dist - initialFingerDistance) / initialFingerDistance
                        if (scaleChange >= PINCH_THRESHOLD_RATIO) {
                            mode = GestureMode.ZOOM
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    reset()
                }
            }
        }
        return super.onTouchEvent(event, mapView)
    }

    private fun reset() {
        mode = GestureMode.UNDECIDED
        accumulatedRotation = 0f
        initialFingerDistance = 0f
        tracking = false
    }

    private fun fingerDistance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return hypot(dx, dy)
    }
}
