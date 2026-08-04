package crazydude.com.telemetry.maps.osm

import android.content.Context
import android.location.Location
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider

/**
 * The map's own arrow: where the phone is, from the system, and which way it is
 * facing, from whoever is reading the compass.
 *
 * The compass is not read here. It is read once by the screen and handed to
 * this, to the 3D view's arrow and to the recording — three readers of the same
 * two sensors was three sets of samples and three lots of filtering to arrive
 * at one number.
 */
class CompassLocationProvider(private val context: Context) : IMyLocationProvider {

    private val gpsProvider = GpsMyLocationProvider(context)

    private var compassBearing: Float = 0f
    private var consumer: IMyLocationConsumer? = null
    private var accepted: Location? = null
    private var lastBearingPush = 0L
    private var pushedBearing = -999f
    private var hasBearing = false

    /**
     * A replay handing back where the phone was, instead of where it is.
     *
     * Fed through the same provider the live arrow comes from, so the map draws
     * it with the same dot, the same arrow and the same ring — a hand-drawn
     * imitation beside the real one is exactly the kind of thing that looks
     * wrong without anybody being able to say why.
     */
    private var fed = false
    private var fedLocation: Location? = null

    fun feed(location: Location?) {
        fedLocation = location
        fed = location != null
        if (location == null) return
        accepted = location
        consumer?.onLocationChanged(location, this)
    }

    /**
     * Which way the phone is facing, as read by the screen.
     *
     * Every push of this redraws the map, so it is only passed on when the
     * angle has actually moved and not too often — with the phone lying still
     * it would otherwise redraw all day.
     */
    fun setBearing(degrees: Float) {
        if (degrees.isNaN()) return
        compassBearing = degrees
        hasBearing = true
        if (fed) return
        val now = System.currentTimeMillis()
        var moved = ((compassBearing - pushedBearing + 540f) % 360f) - 180f
        if (moved < 0f) moved = -moved
        if (now - lastBearingPush > 60 && moved > 0.5f) {
            lastBearingPush = now
            pushedBearing = compassBearing
            accepted?.let { consumer?.onLocationChanged(injectBearing(Location(it)), this) }
        }
    }

    override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer?): Boolean {
        consumer = myLocationConsumer
        // Whatever is being fed goes straight back out. Turning the overlay on
        // takes this provider round again, and without this the recorded place
        // was lost on the way, so the arrow blinked out between one feed and
        // the next while a replay was being dragged.
        fedLocation?.let { myLocationConsumer?.onLocationChanged(it, this) }
        // ask for frequent updates so a GPS fix replaces the first coarse one quickly
        gpsProvider.locationUpdateMinTime = 1000
        gpsProvider.locationUpdateMinDistance = 0f
        return gpsProvider.startLocationProvider { location, source ->
            // osmdroid's provider already ignores network fixes for a while
            // after a gps one, so take what it gives us; filtering on accuracy
            // here could latch onto one good fix and freeze the position.
            if (fed) return@startLocationProvider
            accepted = location
            myLocationConsumer?.onLocationChanged(injectBearing(location), source)
        }
    }

    override fun stopLocationProvider() {
        // fed and fedLocation are the replay's, and outlive this: only feeding
        // null gives the arrow back to the live one
        accepted = null
        hasBearing = false
        pushedBearing = -999f
        gpsProvider.stopLocationProvider()
        consumer = null
    }

    override fun getLastKnownLocation(): Location? {
        fedLocation?.let { return it }
        return gpsProvider.lastKnownLocation?.let { injectBearing(it) }
    }

    override fun destroy() {
        stopLocationProvider()
        gpsProvider.destroy()
    }

    private fun injectBearing(location: Location?): Location? {
        if (location == null) return null
        // Only once the compass has actually read something. Assigning a bearing
        // at all makes Location report that it has one, and osmdroid then draws
        // the direction arrow — which on a phone with no magnetometer sat
        // pointing due north for the whole session, and hid the plain dot that
        // is there for exactly this case.
        if (hasBearing) location.bearing = compassBearing
        return location
    }

}
