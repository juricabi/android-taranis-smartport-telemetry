package juricabi.com.telemetry.service

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import juricabi.com.telemetry.gl.AltitudeFrame
import juricabi.com.telemetry.manager.PreferenceManager
import juricabi.com.telemetry.utils.DebugLog

/**
 * The phone's listening in reverse: the drone's GPS republished as the
 * phone's own position, for a tracker app on this phone — Overland,
 * PureTrack — to broadcast the drone instead of the phone.
 *
 * Opt-in, and plain AOSP: the fixes go to Android's test-provider API
 * under the real GPS provider's name, which is the one name every tracker
 * listens to, with or without Play services. The only grant is being
 * picked under Developer options → Select mock location app — that is
 * what addTestProvider checks.
 *
 * Live only by construction: a replay never reaches the service that owns
 * this, and yesterday's flight must not become today's position.
 */
class MockPublisher(
    private val context: Context,
    private val preferenceManager: PreferenceManager,
    /** The notification says whether publishing is on; it re-reads [active] here. */
    private val onActiveChanged: () -> Unit
) {

    /**
     * Volatile because the provider is installed and removed on the main
     * thread while the fixes are published from the poller's.
     */
    @Volatile var active = false
        private set

    /**
     * What the link has said so far, gathered by the service that retains it —
     * the enrichment a bare position needs before it can stand for the phone.
     */
    data class Heard(
        val hasFix: Boolean,
        val gpsAltitude: Float,
        val relativeAltitude: Float,
        val speedKmh: Float,
        val heading: Float,
        val knownDisarmed: Boolean
    )

    fun refresh(linkUp: Boolean) {
        val wanted = linkUp && preferenceManager.isMockLocationEnabled()
        if (wanted == active) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (wanted) {
            try {
                lm.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    false, true, false, false, true, true, true,
                    Criteria.POWER_HIGH, Criteria.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                active = true
                DebugLog.note("Mock", "provider up")
            } catch (e: SecurityException) {
                DebugLog.note("Mock", "provider refused: not the chosen mock app")
                // Not the chosen mock location app. Said once, at the seam
                // where it fails; the settings screen shows the same state
                // and where to fix it.
                Toast.makeText(
                    context,
                    "Drone GPS is not published: pick this app under " +
                        "Developer options, Select mock location app",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            try {
                lm.removeTestProvider(LocationManager.GPS_PROVIDER)
            } catch (e: Exception) {
                // the choice of mock app was revoked first and took the
                // provider with it
            }
            active = false
            DebugLog.note("Mock", "provider down")
        }
        onActiveChanged()
    }

    // built once; the first question loads the system's geoid table
    private val altitudeConverter by lazy { AltitudeConverter() }

    /** On the poller's thread, like every other decode callback. */
    fun publish(latitude: Double, longitude: Double, heard: Heard) {
        if (!active) return
        // the map's own bar for a believable position: a fix, and not 0,0,
        // which is a receiver still warming up
        if (!heard.hasFix || (latitude == 0.0 && longitude == 0.0)) return
        val fix = Location(LocationManager.GPS_PROVIDER)
        fix.latitude = latitude
        fix.longitude = longitude
        fix.time = System.currentTimeMillis()
        fix.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        // The link does not say how good the drone's fix is, and a Location
        // without an accuracy is refused as incomplete. Five metres is an
        // ordinary satellite answer, inside the filters tracker apps apply.
        fix.accuracy = 5f
        // The link says altitude above the sea, which is what pilots and
        // trackers read; Android defines the field as height above the WGS84
        // ellipsoid, and since 14 derives an MSL of its own by subtracting
        // the geoid — fed sea-level metres over Velebit it filed the drone
        // 45 m low. The system's converter only answers ellipsoidal→MSL, but
        // asked about a height that is really MSL its answer measures the
        // geoid gap, which is enough to go the other way; both fields then
        // agree with the drone under Android's own model, whoever reads which.
        // The link's absolute claim first; failing that, a relative height
        // whose zero the flight has PROVEN — LTM says heights above a home
        // whose ground is known — makes the absolute the wire never sent.
        // Unproven relative heights stay unpublished: a guess is worse
        // than an omission.
        val reported = when {
            !heard.gpsAltitude.isNaN() -> heard.gpsAltitude
            !heard.relativeAltitude.isNaN() &&
                AltitudeFrame.aboveLaunch(AltitudeFrame.currentEpoch()) ->
                heard.relativeAltitude
            else -> Float.NaN
        }
        // A disarmed reading's datum cannot be known — old Betaflight says
        // sea level disarmed and above-launch once armed — so it is left
        // out here exactly as the 3D view leaves it out of the flight: the
        // position still goes, the height waits for arming.
        if (!reported.isNaN() && !heard.knownDisarmed) {
            // The flight being watched may have proven its heights are
            // measured from the launch — iNav over CRSF, old Betaflight
            // armed — and the scene publishes the ground it found under the
            // launch. The same answer lifts what leaves the phone, so the
            // tracker says the height the 3D view draws. Unsettled or
            // already above the sea, the lift is null or zero and this
            // publishes what the link said.
            val lift = AltitudeFrame.lift(AltitudeFrame.currentEpoch()) ?: 0f
            val msl = reported.toDouble() + lift
            fix.altitude = msl
            if (Build.VERSION.SDK_INT >= 34) try {
                val probe = Location(fix)
                altitudeConverter.addMslAltitudeToLocation(context, probe)
                fix.altitude = msl + (msl - probe.mslAltitudeMeters)
                fix.mslAltitudeMeters = msl
            } catch (e: Exception) {
                // no geoid answer on this phone: sea-level metres stay in the
                // raw field, which is how every tracker reads it anyway
            }
        }
        fix.speed = heard.speedKmh / 3.6f // decoders deliver km/h; Location wants m/s
        fix.bearing = heard.heading
        try {
            (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .setTestProviderLocation(LocationManager.GPS_PROVIDER, fix)
        } catch (e: Exception) {
            // Unselected as the mock app mid-flight. Every later fix would
            // throw the same way, so stop claiming to publish rather than
            // crash the poller's thread. Said by name: a field afternoon
            // went to publishing that stopped mid-flight with nothing in
            // the log to say why.
            DebugLog.note("Mock", "publish refused, giving up: $e")
            active = false
            onActiveChanged()
        }
    }
}
