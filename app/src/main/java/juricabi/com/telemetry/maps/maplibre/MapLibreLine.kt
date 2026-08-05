package juricabi.com.telemetry.maps.maplibre

import juricabi.com.telemetry.maps.MapLine
import juricabi.com.telemetry.maps.Position
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * A flight, drawn by the GPU.
 *
 * This was the whole reason for MapLibre. osmdroid reprojected every point of
 * every line on every frame, on the UI thread — and the map was invalidated on
 * every frame, because the marker is eased there. A few thousand points cost a
 * couple of milliseconds a frame before anything else was drawn, which is why
 * the ground view had come to be smoother than the map.
 *
 * Here the points are handed over once and live in a source; the renderer draws
 * them from its own buffers and the cost per frame stopped depending on how
 * many there are. That is what paid for [MapLine.commitPoints] no longer
 * thinning the track: the flight is drawn from every fix that was recorded.
 */
class MapLibreLine(
    private val id: String,
    private val whenReady: ((Style) -> Unit) -> Unit
) : MapLine() {

    private val sourceId = "line-src-$id"
    private val layerId = "line-lyr-$id"

    private val points = ArrayList<Position>()

    /** The same points, kept in the form the renderer is handed. */
    private val drawn = ArrayList<Point>()

    private var source: GeoJsonSource? = null
    private var layer: LineLayer? = null
    private var removed = false

    private var lineWidth = 4f
    private var lineColor = 0xFFFF0000.toInt()

    init {
        whenReady { style ->
            if (removed) return@whenReady
            val src = GeoJsonSource(sourceId)
            val lyr = LineLayer(layerId, sourceId).withProperties(
                PropertyFactory.lineColor(lineColor),
                PropertyFactory.lineWidth(lineWidth),
                // A flight doubles back on itself and crosses its own track;
                // butt ends and mitred joins leave notches at every one.
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round")
            )
            style.addSource(src)
            // On top of everything there is so far, which makes the order these
            // are drawn in the order they were made in — the rule osmdroid's
            // overlays follow. Inserted just above the tiles instead, every new
            // line went *under* the one before it, so the heading line was
            // beneath the flight it points out of and the two flickered where
            // they crossed.
            style.addLayer(lyr)
            source = src
            layer = lyr
            push()
        }
    }

    override var width: Float
        get() = lineWidth
        set(value) {
            lineWidth = value
            layer?.setProperties(PropertyFactory.lineWidth(value))
        }

    override var color: Int
        get() = lineColor
        set(value) {
            lineColor = value
            layer?.setProperties(PropertyFactory.lineColor(value))
        }

    override val size: Int
        get() = points.size

    override fun addPoints(points: List<Position>) {
        if (points.isEmpty()) return
        this.points.addAll(points)
        for (at in points) drawn.add(Point.fromLngLat(at.lon, at.lat))
        push()
    }

    override fun setPoint(index: Int, position: Position) {
        if (index < 0 || index >= points.size) return
        points[index] = position
        drawn[index] = Point.fromLngLat(position.lon, position.lat)
        push()
    }

    override fun clear() {
        spoints.clear()
        points.clear()
        drawn.clear()
        push()
    }

    override fun remove() {
        removed = true
        whenReady { style ->
            style.removeLayer(layerId)
            style.removeSource(sourceId)
        }
        layer = null
        source = null
        points.clear()
        drawn.clear()
    }

    /**
     * Hand the whole line over again.
     *
     * A source is replaced rather than appended to — there is no add-a-point on
     * one. That is a copy of the track per batch, which is why the batching
     * upstream matters: a replay gathers a whole seek and commits it once, so
     * this runs once for a jump rather than once per fix in it.
     *
     * The points are kept converted rather than converted again on every push.
     * Rebuilding the whole list each time meant one allocation per point per
     * batch, which was tolerable while the track was thinned to a few thousand
     * and is not now it keeps every fix of a long flight.
     */
    private fun push() {
        val src = source ?: return
        if (drawn.size < 2) {
            // A line of one point is not a line, and MapLibre draws nothing for
            // it — but it also warns about it on every frame it is asked.
            src.setGeoJson(LineString.fromLngLats(emptyList<Point>()))
            return
        }
        src.setGeoJson(LineString.fromLngLats(drawn))
    }
}
