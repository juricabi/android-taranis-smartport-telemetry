package juricabi.com.telemetry.maps

import org.osmdroid.util.GeoPoint

data class Position(var lat: Double, var lon: Double) {

    fun toGeoPoint(): GeoPoint {
        return GeoPoint(lat, lon)
    }
}
