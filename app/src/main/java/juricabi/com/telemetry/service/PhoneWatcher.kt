package juricabi.com.telemetry.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import juricabi.com.telemetry.manager.PreferenceManager

/**
 * Where the phone is and which way it faces, heard by the thing that outlives
 * the screen.
 *
 * The recording goes on while the app is in somebody's pocket — that is what
 * the notification is for — and the operator's own position is half of what a
 * replay puts back. Heard on the screen alone, it stopped the moment the
 * screen went away, which is most of a flight.
 *
 * The compass belongs here too. If the screen owns it, locking the phone
 * leaves a position in every CSV row but a heading in almost none of them,
 * and the recorded navigation marker disappears during replay.
 *
 * The watcher owns sensing, fix arbitration and the background wake lock;
 * what is written down and who else hears stays with its caller, through
 * [onFix] and [onHeading].
 */
class PhoneWatcher(
    private val context: Context,
    private val preferenceManager: PreferenceManager,
    /** A fix that must not enter arbitration at all — our own mock coming back. */
    private val refuseFix: (Location) -> Boolean,
    /** Every believed fix, before the screen hears it. */
    private val onFix: (Location) -> Unit,
    /** Every heading sample — NaN when sampling stops — before the screen hears it. */
    private val onHeading: (Float) -> Unit
) {

    private var phoneFix: Location? = null
    private var phoneHeading = Float.NaN

    /**
     * Who is listening, and why.
     *
     * The recording wants the phone's position for as long as a link is up,
     * screen or no screen. The screen wants it for as long as it is drawing,
     * link or no link — a map with nothing flying still shows where you are.
     * Either is reason enough to listen and neither alone is reason to stop.
     */
    private var wantedByLink = false
    private var wantedByScreen = false
    private var listening = false
    private var compassListening = false

    /** The screen, while it is drawing. Null when it goes away. */
    private var screenFixListener: ((Location) -> Unit)? = null
    private var screenHeadingListener: ((Float) -> Unit)? = null

    fun watch(
        fixListener: ((Location) -> Unit)?,
        headingListener: ((Float) -> Unit)?
    ) {
        screenFixListener = fixListener
        screenHeadingListener = headingListener
        wantedByScreen = fixListener != null || headingListener != null
        recompute()
        // whatever is known already, so a screen coming back does not wait for
        // the next fix or compass sample to draw its marker
        if (fixListener != null) phoneFix?.let { fixListener(it) }
        headingListener?.invoke(phoneHeading)
    }

    /** The link's standing interest, restated wherever it may have changed. */
    fun refresh(linkUp: Boolean) {
        wantedByLink = linkUp
        recompute()
    }

    /** Service teardown: everything off, the screen forgotten. */
    fun shutdown() {
        wantedByScreen = false
        screenFixListener = null
        screenHeadingListener = null
        stopListeningForPhone()
        stopListeningForPhoneCompass()
        releaseCompassWakeLock()
    }

    private fun recompute() {
        if (wantedByLink || wantedByScreen) listenForPhone() else stopListeningForPhone()

        val wantCompass = wantedByScreen ||
            (wantedByLink && preferenceManager.isBackgroundCompassEnabled())
        if (wantCompass) listenForPhoneCompass() else stopListeningForPhoneCompass()
        updateCompassWakeLock()
    }

    private val compassHandler = Handler(Looper.getMainLooper())
    private val phoneGravity = FloatArray(3)
    private val phoneGeomagnetic = FloatArray(3)
    private var hasPhoneGravity = false
    private var hasPhoneGeomagnetic = false

    private val phoneCompass = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    settle(phoneGravity, event.values, hasPhoneGravity)
                    hasPhoneGravity = true
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    settle(phoneGeomagnetic, event.values, hasPhoneGeomagnetic)
                    hasPhoneGeomagnetic = true
                }
            }
            if (!hasPhoneGravity || !hasPhoneGeomagnetic) return
            val rotation = FloatArray(9)
            if (!SensorManager.getRotationMatrix(
                    rotation, null, phoneGravity, phoneGeomagnetic
                )
            ) return
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotation, orientation)
            var degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (degrees < 0f) degrees += 360f
            publishPhoneHeading(degrees)
        }
    }

    /** A fifth of the way to each reading: a compass on its own jitters. */
    private fun settle(held: FloatArray, fresh: FloatArray, had: Boolean) {
        for (i in held.indices) {
            held[i] = if (had) held[i] + (fresh[i] - held[i]) * 0.2f else fresh[i]
        }
    }

    private fun publishPhoneHeading(heading: Float) {
        phoneHeading = heading
        onHeading(heading)
        screenHeadingListener?.invoke(heading)
    }

    private val phoneLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (refuseFix(location)) return
            if (!worthBelieving(location)) return
            phoneFix = location
            onFix(location)
            screenFixListener?.invoke(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /**
     * A mast answers at once and puts you hundreds of metres from where the
     * satellites say. Anything while nothing is known, anything once what is
     * known has gone stale, and otherwise only a fix at least as good.
     */
    private fun worthBelieving(fix: Location): Boolean {
        val held = phoneFix ?: return true
        if (System.currentTimeMillis() - held.time > 20000L) return true
        val newer = fix.time - held.time
        if (newer > 20000L) return true
        if (newer < -20000L) return false
        if (!fix.hasAccuracy()) return !held.hasAccuracy()
        if (!held.hasAccuracy()) return true
        return fix.accuracy <= held.accuracy || fix.provider == held.provider
    }

    private fun listenForPhone() {
        if (listening) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        listening = true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (provider in arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                lm.requestLocationUpdates(provider, 1000L, 0f, phoneLocationListener)
            } catch (e: Exception) {
                // a phone without that provider; the other one still runs
            }
        }
    }

    private fun stopListeningForPhone() {
        if (!listening) return
        listening = false
        // The fix is kept. It does not stop being true because nobody is
        // listening — and thrown away here, a screen coming back from the
        // settings drew the system's stale answer and waited seconds for the
        // satellites, over a fix this service had held moments before.
        // worthBelieving already ages it: past twenty seconds anything
        // fresher wins, so keeping it cannot pin the arrow to the past.
        try {
            (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .removeUpdates(phoneLocationListener)
        } catch (e: Exception) {
            // never started, or already gone
        }
    }

    /**
     * Which way the phone is facing, owned by the same foreground service that
     * records its position.
     *
     * Supplying a Handler pins callbacks to the main looper even when a poller
     * reports its connection from a worker thread.
     */
    private fun listenForPhoneCompass() {
        if (compassListening) return
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetic = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (accelerometer == null || magnetic == null) {
            publishPhoneHeading(Float.NaN)
            return
        }

        hasPhoneGravity = false
        hasPhoneGeomagnetic = false
        val registered = try {
            val gravity = sensors.registerListener(
                phoneCompass, accelerometer, SensorManager.SENSOR_DELAY_UI, compassHandler
            )
            val geomagnetic = sensors.registerListener(
                phoneCompass, magnetic, SensorManager.SENSOR_DELAY_UI, compassHandler
            )
            gravity && geomagnetic
        } catch (failure: RuntimeException) {
            false
        }
        if (!registered) {
            sensors.unregisterListener(phoneCompass)
            publishPhoneHeading(Float.NaN)
            return
        }
        compassListening = true
    }

    private fun stopListeningForPhoneCompass() {
        if (compassListening) {
            (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
                .unregisterListener(phoneCompass)
        }
        compassListening = false
        hasPhoneGravity = false
        hasPhoneGeomagnetic = false
        // Blank subsequent CSV rows instead of repeating the last direction
        // forever after sampling was deliberately stopped.
        publishPhoneHeading(Float.NaN)
    }

    private var compassWakeLock: PowerManager.WakeLock? = null

    /**
     * Non-wakeup compass sensors are allowed to sleep with the display. The
     * lock exists only for an active telemetry link in the background and is
     * released on resume, disconnect, preference change and service teardown.
     */
    private fun updateCompassWakeLock() {
        val shouldHold = compassListening && wantedByLink && !wantedByScreen &&
            preferenceManager.isBackgroundCompassEnabled()
        if (!shouldHold) {
            releaseCompassWakeLock()
            return
        }
        val lock = compassWakeLock ?: (
            context.getSystemService(Context.POWER_SERVICE) as PowerManager
        ).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:background-compass"
        ).also {
            it.setReferenceCounted(false)
            compassWakeLock = it
        }
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseCompassWakeLock() {
        compassWakeLock?.let {
            if (it.isHeld) it.release()
        }
    }
}
