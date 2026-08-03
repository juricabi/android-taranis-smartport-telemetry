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
    private var hasBearing = false

    override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer?): Boolean {
        consumer = myLocationConsumer
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        // ask for frequent updates so a GPS fix replaces the first coarse one quickly
        gpsProvider.locationUpdateMinTime = 1000
        gpsProvider.locationUpdateMinDistance = 0f
        return gpsProvider.startLocationProvider { location, source ->
            if (isBetter(location)) {
                accepted = location
                myLocationConsumer?.onLocationChanged(injectBearing(location), source)
            }
        }
    }

    override fun stopLocationProvider() {
        accepted = null
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

    // The provider reports both GPS and network fixes. A network fix can be
    // hundreds of metres wide and would otherwise replace a good GPS one, which
    // shows up as a large accuracy circle. Keep the more accurate fix unless the
    // one we are holding has gone stale.
    private fun isBetter(location: Location?): Boolean {
        if (location == null) return false
        val current = accepted ?: return true
        if (location.accuracy <= current.accuracy) return true
        return location.time - current.time > 20000
    }

    private fun injectBearing(location: Location?): Location? {
        if (location == null) return null
        location.bearing = compassBearing
        return location
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
                hasGravity = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
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
                    compassBearing = (compassBearing + diff * 0.2f + 360f) % 360f
                }

                // the arrow used to turn only when a location update arrived, about
                // once a second, which looked like it was stepping
                val now = System.currentTimeMillis()
                if (now - lastBearingPush > 50) {
                    lastBearingPush = now
                    accepted?.let { consumer?.onLocationChanged(injectBearing(Location(it)), this) }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
