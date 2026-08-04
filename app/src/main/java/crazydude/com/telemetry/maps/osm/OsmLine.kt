package crazydude.com.telemetry.maps.osm

import crazydude.com.telemetry.maps.MapLine
import crazydude.com.telemetry.maps.Position
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

class OsmLine(private val mapView: MapView) : MapLine() {

    private val line = Polyline(mapView)

    init {
        // Tapping the flight, or a flight plan, opened an empty white bubble:
        // osmdroid gives every line one whether or not it has anything to say,
        // exactly as it does with markers. Only the aircraft from FlightRadar
        // have anything to put in one.
        line.infoWindow = null
        mapView.overlayManager.add(line)
    }

    override fun remove() {
        mapView.overlayManager.remove(line)
        mapView.invalidate()
    }

    override fun addPoints(points: List<Position>) {
        points.forEach { line.addPoint(it.toGeoPoint()) }
    }

    override fun setPoint(index: Int, position: Position) {
        val actualPoints = ArrayList(line.actualPoints)
        // a line that has been emptied has no point to write to
        if (index < 0 || index >= actualPoints.size) return
        actualPoints[index] = position.toGeoPoint()
        line.setPoints(actualPoints)
    }

    override fun clear() {
        spoints.clear()
        // A line whose map has been taken away has already been emptied by
        // osmdroid, and asking it for its points throws.
        try {
            // through its own setter, not by emptying the list it hands out:
            // that list is the one it draws from, and it keeps a projected copy
            // beside it which only its setters know to throw away
            line.setPoints(emptyList())
            mapView.invalidate()
        } catch (e: Exception) {
            // nothing left to clear
        }
    }

    override fun removeAt(index: Int) {
        try {
            val remaining = ArrayList(line.actualPoints)
            if (index < 0 || index >= remaining.size) return
            remaining.removeAt(index)
            line.setPoints(remaining)
            mapView.invalidate()
        } catch (e: Exception) {
            // nothing left to remove
        }
    }

    override val size: Int
        get() = try {
            line.actualPoints.size
        } catch (e: Exception) {
            0
        }
    override var color: Int
        get() = line.color
        // redrawn straight away: without this a colour picked in the settings
        // did not appear until something else happened to invalidate the map
        set(value) {
            line.color = value
            mapView.invalidate()
        }
}