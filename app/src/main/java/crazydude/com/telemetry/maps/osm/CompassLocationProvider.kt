package crazydude.com.telemetry.maps.osm

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider

class CompassLocationProvider(private val context: Context) : IMyLocationProvider, SensorEventListener {

    private val gpsProvider = GpsMyLocationProvider(context)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var compassBearing: Float = 0f
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false
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

    fun feed(location: Location?) {
        if (location == null) {
            fed = false
            return
        }
        fed = true
        accepted = location
        consumer?.onLocationChanged(location, this)
    }

    override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer?): Boolean {
        consumer = myLocationConsumer
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
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
        fed = false
        accepted = null
        hasGravity = false
        hasGeomagnetic = false
        hasBearing = false
        pushedBearing = -999f
        sensorManager.unregisterListener(this)
        gpsProvider.stopLocationProvider()
        consumer = null
    }

    override fun getLastKnownLocation(): Location? {
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

    // Averaging the sensor readings themselves is what makes the needle steady;
    // filtering only the final angle still passes the noise through.
    private fun smooth(target: FloatArray, values: FloatArray, initialised: Boolean) {
        if (!initialised) {
            System.arraycopy(values, 0, target, 0, 3)
            return
        }
        for (i in 0..2) {
            target[i] += (values[i] - target[i]) * 0.10f
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                smooth(gravity, event.values, hasGravity)
                hasGravity = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                smooth(geomagnetic, event.values, hasGeomagnetic)
                hasGeomagnetic = true
            }
        }
        if (hasGravity && hasGeomagnetic) {
            val r = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, null, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                var raw = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (raw < 0) raw += 360f

                if (!hasBearing) {
                    compassBearing = raw
                    hasBearing = true
                } else {
                    // shortest way round the circle, then ease towards it so the
                    // needle does not jitter on every sample
                    var diff = ((raw - compassBearing + 540f) % 360f) - 180f
                    compassBearing = (compassBearing + diff * 0.25f + 360f) % 360f
                }

                // the arrow used to turn only when a location update arrived, about
                // once a second, which looked like it was stepping
                val now = System.currentTimeMillis()
                var moved = ((compassBearing - pushedBearing + 540f) % 360f) - 180f
                if (moved < 0f) moved = -moved
                // Every push redraws the map, so this is not free — but three
                // degrees and a fifth of a second was coarse enough to see: the
                // needle turned in steps beside a 3D view that eases its arrow
                // every frame. Half a degree, sixteen times a second, is smooth
                // to the eye and still a fraction of what the map draws while
                // following a model.
                if (fed) return
                if (now - lastBearingPush > 60 && moved > 0.5f) {
                    // redrawing the map on every sample would run all day with
                    // the phone lying still, so only do it when it has turned
                    lastBearingPush = now
                    pushedBearing = compassBearing
                    accepted?.let { consumer?.onLocationChanged(injectBearing(Location(it)), this) }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
