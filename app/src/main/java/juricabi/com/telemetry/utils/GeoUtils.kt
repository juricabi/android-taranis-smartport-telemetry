package juricabi.com.telemetry.utils

import kotlin.math.*

object GeoUtils {

    /**
     * The turn from one bearing to another, the short way: between -180 and
     * 180 degrees, negative to the left.
     *
     * It was written out in full in eight places, in two spellings — a modulo
     * form and a pair of while loops — and one of them had had its sign the
     * wrong way round at some point. There is only one way round a compass.
     */
    fun shortestTurn(from: Float, to: Float): Float =
        ((to - from) % 360f + 540f) % 360f - 180f

    /** [from] turned [part] of the way towards [to], the short way round. */
    fun turnTowards(from: Float, to: Float, part: Float): Float =
        ((from + shortestTurn(from, to) * part) % 360f + 360f) % 360f


    private const val EARTH_RADIUS = 6371009.0 // meters

    fun computeDistanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return EARTH_RADIUS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // Returns a Position offset from origin by distance (meters) in the given bearing (degrees)
    fun computeOffset(lat: Double, lon: Double, distance: Double, bearing: Double): Pair<Double, Double> {
        val angularDistance = distance / EARTH_RADIUS
        val bearingRad = Math.toRadians(bearing)
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val newLat = asin(
            sin(latRad) * cos(angularDistance) +
            cos(latRad) * sin(angularDistance) * cos(bearingRad)
        )
        val newLon = lonRad + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(latRad),
            cos(angularDistance) - sin(latRad) * sin(newLat)
        )
        return Pair(Math.toDegrees(newLat), Math.toDegrees(newLon))
    }
}
