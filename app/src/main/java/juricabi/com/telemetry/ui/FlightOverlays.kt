package juricabi.com.telemetry.ui

import juricabi.com.telemetry.R
import juricabi.com.telemetry.manager.Fr24Manager
import juricabi.com.telemetry.maps.MapMarker
import juricabi.com.telemetry.maps.MapWrapper
import juricabi.com.telemetry.maps.Position
import juricabi.com.telemetry.utils.DebugLog

/**
 * The overlays both views draw of the same flight — the live phone arrow,
 * the replay's recorded operator, north-up, the surrounding air traffic —
 * said once here and heard by the 2D map and the 3D terrain together. One
 * flight, two views: every pair of calls that used to be maintained by hand
 * at its call site lives behind one verb now, and each view's own dialect
 * (the map wants NaN for an unknown accuracy, the terrain wants zero; the
 * map diffes traffic markers, the terrain swallows the list whole) is the
 * adapter's business, not the caller's.
 *
 * The camera, the model marker and its easing are deliberately NOT here:
 * the two views ride genuinely different machinery for those, and a shared
 * verb would only pretend otherwise.
 *
 * The views are read through providers because both are mortal — the map is
 * rebuilt per style, the terrain born and parked per flight.
 */
class FlightOverlays(
    private val map: () -> MapWrapper?,
    private val terrain: () -> Terrain3DView?,
    /** An aircraft's bubble text: title to snippet, said the same in both views. */
    private val describeAirplane: (Fr24Manager.AirplaneInfo) -> Pair<String, String>
) {

    // ------------------------------------------------------------- the phone

    /** Where this phone is; accuracy NaN when the fix does not say. */
    fun showPhone(latitude: Double, longitude: Double, accuracy: Float, heading: Float) {
        map()?.setPhoneLocation(Position(latitude, longitude), accuracy)
        // NaN is meaningful to the map: draw a dot rather than retain an old arrow.
        map()?.setPhoneBearing(heading)
        terrain()?.setMyPosition(
            latitude, longitude, if (accuracy.isNaN()) 0f else accuracy)
        terrain()?.setMyHeading(heading)
    }

    /** Which way this phone is facing, alone — a compass sample between fixes. */
    fun showPhoneHeading(degrees: Float) {
        map()?.setPhoneBearing(degrees)
        terrain()?.setMyHeading(degrees)
    }

    // ---------------------------------------------------------- the operator

    /** The recorded operator at this moment of the replay: arrow, ring, facing. */
    fun showOperator(latitude: Double, longitude: Double, accuracy: Float, heading: Float) {
        map()?.showRecordedLocation(Position(latitude, longitude), accuracy, heading)
        terrain()?.setLoggedPosition(latitude, longitude, accuracy, heading)
    }

    /** Nothing recorded of where anybody stood, so nothing orange drawn. */
    fun hideOperator() {
        map()?.showRecordedLocation(null, 0f, 0f)
        terrain()?.hideLoggedLocation()
    }

    // ------------------------------------------------------------- north up

    fun faceNorth() {
        map()?.resetMapOrientation()
        terrain()?.faceNorth()
    }

    // ------------------------------------------------------------- traffic

    private val airplaneMarkers = mutableMapOf<Int, MapMarker>()

    /**
     * The sky as last fetched. The terrain swallows the list and diffes it
     * itself; the map's markers are diffed here, which used to be thirty
     * lines inline beside the terrain's one.
     */
    fun showTraffic(airplanes: List<Fr24Manager.AirplaneInfo>) {
        terrain()?.setTraffic(airplanes)
        DebugLog.note("Fr24",
            "update: ${airplanes.size} aircraft, markers=${airplaneMarkers.size}, " +
            "map=${if (map() == null) "null" else "up"}")
        val currentIds = airplanes.map { it.flightId }.toSet()

        // Remove stale markers
        val staleIds = airplaneMarkers.keys.filter { it !in currentIds }
        staleIds.forEach { id ->
            airplaneMarkers.remove(id)?.remove()
        }

        // Update or create markers
        for (airplane in airplanes) {
            val (title, snippet) = describeAirplane(airplane)

            val existing = airplaneMarkers[airplane.flightId]
            if (existing != null) {
                existing.position = Position(airplane.lat.toDouble(), airplane.lon.toDouble())
                existing.rotation = airplane.track.toFloat()
                existing.title = title
                existing.snippet = snippet
            } else {
                val m = map()?.addMarker(
                    R.drawable.ic_airplane_fr24,
                    Position(airplane.lat.toDouble(), airplane.lon.toDouble())
                )
                if (m != null) {
                    m.rotation = airplane.track.toFloat()
                    m.title = title
                    m.snippet = snippet
                    airplaneMarkers[airplane.flightId] = m
                }
            }
        }
    }

    /** The sky scorched in both views — entering a replay, where it has no business. */
    fun clearTraffic() {
        forgetMapTraffic()
        terrain()?.setTraffic(emptyList())
    }

    /** The map is being torn down; its markers die with it. The terrain keeps its sky. */
    fun forgetMapTraffic() {
        airplaneMarkers.values.forEach { it.remove() }
        airplaneMarkers.clear()
    }
}
