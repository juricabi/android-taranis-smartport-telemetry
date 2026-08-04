package crazydude.com.telemetry.maps.osm

import android.location.Location
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider

/**
 * The map's own arrow: where the phone is and which way it is facing, both
 * handed in from outside.
 *
 * Nothing is read here. The screen listens to the satellites once and reads the
 * compass once, and hands the same two numbers to this, to the 3D view's arrow
 * and to the recording. Before, there were two listeners on the satellites and
 * three readers of the same two sensors, each with its own filtering, all
 * answering one question slightly differently — and the map's answer was the
 * unfiltered one.
 *
 * A replay hands in where the phone *was* instead. While it is doing that, live
 * fixes are ignored, so the arrow cannot flick between then and now.
 */
class CompassLocationProvider : IMyLocationProvider {

    private var consumer: IMyLocationConsumer? = null

    /** The place being drawn, and whether it is a recorded one. */
    private var here: Location? = null
    private var replaying = false

    private var bearing = Float.NaN
    private var drawnBearing = Float.NaN
    private var bearingDrawnAt = 0L

    /** A fix, as the screen believes it: the same one everything else draws. */
    fun setLocation(location: Location) {
        if (replaying) return
        here = location
        draw()
    }

    /**
     * Where the phone stood at this point of a replay.
     *
     * Null hands the arrow back to the live one — and takes the recorded place
     * away with it, since a recorded place is not where anybody is now. The
     * screen pushes the live fix in behind it.
     */
    fun replay(location: Location?) {
        replaying = location != null
        here = location
        draw()
    }

    /**
     * Which way the phone is facing.
     *
     * Every angle drawn redraws the map, so one is drawn only when it has
     * really turned, and no oftener than sixteen times a second: a phone lying
     * still would otherwise redraw all day.
     */
    fun setBearing(degrees: Float) {
        if (degrees.isNaN() || replaying) return
        bearing = degrees
        val turned = Math.abs(((degrees - drawnBearing + 540f) % 360f) - 180f)
        val now = System.currentTimeMillis()
        if (drawnBearing.isNaN() || (now - bearingDrawnAt > 60L && turned > 0.5f)) {
            bearingDrawnAt = now
            drawnBearing = degrees
            draw()
        }
    }

    override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer?): Boolean {
        consumer = myLocationConsumer
        // Whatever is known already, so switching the overlay on draws the
        // arrow rather than waiting for the next fix to come round. osmdroid
        // takes this provider round from the start every time the overlay is
        // enabled, which while a seek bar is dragged is many times a second.
        draw()
        return true
    }

    /** Switched off with the overlay; what is known stays known. */
    override fun stopLocationProvider() {
        consumer = null
    }

    override fun getLastKnownLocation(): Location? = here?.let { facing(it) }

    override fun destroy() {
        consumer = null
        here = null
        replaying = false
    }

    private fun draw() {
        val at = here ?: return
        consumer?.onLocationChanged(facing(at), this)
    }

    /**
     * A copy of the place, pointing the way the phone points.
     *
     * A copy, because osmdroid keeps what it is handed and draws from it later:
     * the same object edited underneath it is the object it has already drawn.
     *
     * A bearing only once one is known: setting it at all is what makes the map
     * draw an arrow rather than nothing, so a phone with no magnetometer would
     * be left with an arrow pointing north for the whole session. A recorded
     * place arrives with its own bearing on it and is left alone.
     */
    private fun facing(at: Location): Location {
        val copy = Location(at)
        if (!replaying && !bearing.isNaN()) copy.bearing = bearing
        return copy
    }
}
