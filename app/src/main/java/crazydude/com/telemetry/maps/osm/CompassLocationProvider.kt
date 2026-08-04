package crazydude.com.telemetry.maps.osm

import android.location.Location
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider

/**
 * A place and a bearing, told to it, handed on when the map asks.
 *
 * Nothing is read here. The screen listens to the satellites once and reads the
 * compass once, and hands the same two numbers to this, to the 3D view's arrow
 * and to the recording. Before, there were two listeners on the satellites and
 * three readers of the same two sensors, each with its own filtering, all
 * answering one question slightly differently.
 *
 * There are two of these on the map: where the phone is now, and — replaying a
 * flight — where it stood while that flight was recorded. Which is which is a
 * matter of who is told what, and what colour arrow the overlay is given.
 */
class CompassLocationProvider : IMyLocationProvider {

    private var consumer: IMyLocationConsumer? = null
    private var here: Location? = null

    private var bearing = Float.NaN
    private var drawnBearing = Float.NaN
    private var bearingDrawnAt = 0L

    /**
     * Where to draw, null to draw nothing.
     *
     * A place that arrives with a bearing on it keeps it — that is a recording
     * saying which way the phone was facing at the time. One that arrives
     * without takes whichever way the compass is pointing now.
     */
    fun setLocation(location: Location?) {
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
        if (degrees.isNaN()) return
        bearing = degrees
        val turned = Math.abs(
            crazydude.com.telemetry.utils.GeoUtils.shortestTurn(drawnBearing, degrees)
        )
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
    }

    private fun draw() {
        val at = here ?: return
        consumer?.onLocationChanged(facing(at), this)
    }

    /**
     * A copy of the place, pointing the way it faces.
     *
     * A copy, because osmdroid keeps what it is handed and draws from it later:
     * the same object edited underneath it is the object it has already drawn.
     *
     * A bearing only once one is known: setting it at all is what makes the map
     * draw an arrow rather than nothing, so a phone with no magnetometer would
     * be left with an arrow pointing north for the whole session.
     */
    private fun facing(at: Location): Location {
        val copy = Location(at)
        if (!copy.hasBearing() && !bearing.isNaN()) copy.bearing = bearing
        return copy
    }
}
