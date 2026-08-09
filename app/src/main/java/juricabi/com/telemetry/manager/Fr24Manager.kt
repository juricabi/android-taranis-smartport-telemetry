package juricabi.com.telemetry.manager

import android.os.Handler
import android.os.Looper
import android.util.Log
import juricabi.com.telemetry.api.Fr24Client
import juricabi.com.telemetry.maps.Position
import juricabi.com.telemetry.proto.fr24.Flight
import juricabi.com.telemetry.proto.fr24.LocationBoundaries
import juricabi.com.telemetry.utils.GeoUtils
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.roundToInt

class Fr24Manager(
    private val preferenceManager: PreferenceManager,
    private val listener: Listener
) {

    data class AirplaneInfo(
        val flightId: Int,
        val lat: Float,
        val lon: Float,
        val altMeters: Int,
        val speedKmh: Int,
        val track: Int,
        val callsign: String,
        val flightNumber: String,
        val registration: String,
        val aircraftType: String,
        val route: String,
        val onGround: Boolean,
        val vspeedMs: Float
    ) {
        val displayName: String
            get() = callsign.ifEmpty { flightNumber.ifEmpty { registration.ifEmpty { "Unknown" } } }
    }

    interface Listener {
        fun onAirplanesUpdated(airplanes: List<AirplaneInfo>)
        fun onProximityWarning(airplane: AirplaneInfo, distanceMeters: Double, directionDeg: Double)
    }

    companion object {
        private const val TAG = "FR24"
        private const val FEET_TO_METERS = 0.3048
        private const val KNOTS_TO_KMH = 1.852
        private const val FPM_TO_MS = 0.00508
    }

    private val client = Fr24Client()
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val currentAirplanes = CopyOnWriteArrayList<AirplaneInfo>()
    private var phoneLocationProvider: (() -> Position?)? = null
    @Volatile private var running = false
    private val fetchInProgress = AtomicBoolean(false)
    private var fetchRunnable: Runnable? = null
    /** The place traffic is measured from — see [watchFrom]. */
    @Volatile private var watchLat: Double = 0.0
    @Volatile private var watchLon: Double = 0.0

    @Synchronized
    fun start(phoneLocationProvider: () -> Position?) {
        if (running || executor.isShutdown) return
        this.phoneLocationProvider = phoneLocationProvider
        running = true
        Log.d(TAG, "Started, poll interval=${preferenceManager.getFr24PollIntervalSec()}s")
        // Now, not one poll interval from now: the first sky used to be
        // empty for the whole interval on every start and every return.
        scheduleFetch(0L)
    }

    fun stop() {
        synchronized(this) {
            running = false
            fetchRunnable?.let { handler.removeCallbacks(it) }
            fetchRunnable = null
            phoneLocationProvider = null
            currentAirplanes.clear()
        }
        // A manager is discarded after stop. Leaving this executor alive leaked
        // one permanent thread on every pause/resume and replay transition.
        executor.shutdownNow()
    }

    /**
     * Where traffic is measured from: the model while one is flying, and the
     * person holding the phone when none is. Standing at the field with
     * nothing up is exactly when an aircraft crossing overhead is worth
     * knowing about, and it is the same sky either way.
     */
    fun watchFrom(lat: Double, lon: Double) {
        watchLat = lat
        watchLon = lon
    }

    /** No model, and no idea where the phone is: nothing to measure from. */
    fun watchNothing() {
        watchLat = 0.0
        watchLon = 0.0
    }

    private fun checkProximity() {
        val lat = watchLat
        val lon = watchLon
        if (lat == 0.0 && lon == 0.0) return

        val warningAltCeiling = preferenceManager.getFr24WarningAltCeilingM()
        val warningDistance = preferenceManager.getFr24WarningDistanceM()

        data class Candidate(val airplane: AirplaneInfo, val dist: Double)

        val candidates = currentAirplanes
            .filter { !it.onGround && it.altMeters <= warningAltCeiling }
            .mapNotNull { airplane ->
                val dist = GeoUtils.computeDistanceBetween(
                    lat, lon,
                    airplane.lat.toDouble(), airplane.lon.toDouble()
                )
                if (dist <= warningDistance) Candidate(airplane, dist) else null
            }
            .sortedBy { it.dist }

        val closest = candidates.firstOrNull() ?: return
        val direction = computeBearing(
            lat, lon,
            closest.airplane.lat.toDouble(), closest.airplane.lon.toDouble()
        )
        listener.onProximityWarning(closest.airplane, closest.dist, direction)
    }

    private fun scheduleFetch(delayMs: Long = -1L) {
        if (!running) return
        val runnable = Runnable {
            val phonePos = phoneLocationProvider?.invoke()
            if (phonePos != null && fetchInProgress.compareAndSet(false, true)) {
                Log.d(TAG, "Starting fetch cycle, phone at ${phonePos.lat},${phonePos.lon}")
                try {
                    executor.execute {
                        try {
                            if (running) doFetch(phonePos.lat, phonePos.lon)
                        } finally {
                            fetchInProgress.set(false)
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    fetchInProgress.set(false)
                }
            } else {
                if (phonePos == null) {
                    Log.w(TAG, "Skipping fetch cycle: phone location not available")
                }
            }
            scheduleFetch()
        }
        fetchRunnable = runnable
        handler.postDelayed(runnable,
            if (delayMs >= 0) delayMs
            else preferenceManager.getFr24PollIntervalSec() * 1000L)
    }

    private fun doFetch(phoneLat: Double, phoneLon: Double) {
        try {
            val radiusKm = preferenceManager.getFr24RadiusKm()
            val radiusMeters = radiusKm * 1000.0
            val displayCeiling = preferenceManager.getFr24DisplayAltCeilingM()

            Log.d(TAG, "Fetching: radius=${radiusKm}km, displayCeiling=${displayCeiling}m")
            val bounds = computeBoundingBox(phoneLat, phoneLon, radiusMeters)
            val flights = client.fetchLiveFeed(bounds, displayCeiling)
            if (flights == null) {
                Log.w(TAG, "FR24 request failed; keeping the last traffic snapshot")
                return
            }
            Log.d(TAG, "FR24 returned ${flights.size} raw flights")

            val airplanes = flights.mapNotNull { flight ->
                if (flight.onGround) return@mapNotNull null
                val altMeters = (flight.alt * FEET_TO_METERS).roundToInt()
                if (altMeters > displayCeiling) return@mapNotNull null

                AirplaneInfo(
                    flightId = flight.flightid,
                    lat = flight.lat,
                    lon = flight.lon,
                    altMeters = altMeters,
                    speedKmh = (flight.speed * KNOTS_TO_KMH).roundToInt(),
                    track = flight.track,
                    callsign = flight.callsign,
                    flightNumber = flight.extraInfo?.flight ?: "",
                    registration = flight.extraInfo?.reg ?: "",
                    aircraftType = flight.extraInfo?.type ?: "",
                    route = buildString {
                        val r = flight.extraInfo?.route
                        if (r != null) {
                            if (r.from.isNotEmpty()) append(r.from)
                            if (r.to.isNotEmpty()) {
                                if (isNotEmpty()) append(" → ")
                                append(r.to)
                            }
                        }
                    },
                    onGround = flight.onGround,
                    vspeedMs = (flight.extraInfo?.vspeed ?: 0) * FPM_TO_MS.toFloat()
                )
            }

            synchronized(this) {
                if (!running) return
                currentAirplanes.clear()
                currentAirplanes.addAll(airplanes)
            }

            handler.post {
                if (running) {
                    listener.onAirplanesUpdated(airplanes)
                    checkProximity()
                }
            }

            Log.d(TAG, "Fetched ${airplanes.size} aircraft after filtering (from ${flights.size} raw)")
        } catch (e: Exception) {
            Log.e(TAG, "Fetch cycle failed", e)
        }
    }

    private fun computeBoundingBox(
        lat: Double,
        lon: Double,
        radiusMeters: Double
    ): LocationBoundaries {
        val north = GeoUtils.computeOffset(lat, lon, radiusMeters, 0.0)
        val south = GeoUtils.computeOffset(lat, lon, radiusMeters, 180.0)
        val east = GeoUtils.computeOffset(lat, lon, radiusMeters, 90.0)
        val west = GeoUtils.computeOffset(lat, lon, radiusMeters, 270.0)

        return LocationBoundaries.newBuilder()
            .setNorth(north.first.toFloat())
            .setSouth(south.first.toFloat())
            .setEast(east.second.toFloat())
            .setWest(west.second.toFloat())
            .build()
    }

    private fun computeBearing(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val y = Math.sin(dLon) * Math.cos(lat2Rad)
        val x = Math.cos(lat1Rad) * Math.sin(lat2Rad) -
                Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360) % 360
    }
}
