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
 * Here the points live in the renderer's own buffers and the cost per frame
 * stopped depending on how many there are. That is what paid for
 * [MapLine.commitPoints] no longer thinning the track: the flight is drawn from
 * every fix that was recorded.
 *
 * **Drawing it costs nothing per frame; handing it over does not.** A source
 * cannot be appended to — it is replaced — so a line held as one source is
 * copied to the renderer in full every time a point arrives, and points arrive
 * at the rate the fixes do. That was bounded while the track was thinned to a
 * few thousand. Nothing bounds it now: two hours at ten fixes a second is
 * seventy thousand points handed over ten times a second, on the UI thread,
 * growing for as long as the flight lasts.
 *
 * So the line is kept as a run of sealed pieces and one live end. A piece is
 * sealed once it is [CHUNK] points long and is never written again; only the
 * end is replaced when a point arrives, and that is at most [CHUNK] points
 * however long the flight runs. Consecutive pieces share their joining point,
 * or the line would have a gap at every seam.
 */
class MapLibreLine(
    private val id: String,
    private val whenReady: ((Style) -> Unit) -> Unit
) : MapLine() {

    companion object {
        /**
         * How many points a piece holds before it is sealed.
         *
         * The most that is ever handed to the renderer at once. Small enough
         * that the copy is not felt, large enough that a long flight does not
         * end up with hundreds of layers: a hundred thousand points is fifty
         * pieces at this size.
         */
        private const val CHUNK = 2000
    }

    private val points = ArrayList<Position>()

    /** The same points, kept in the form the renderer is handed. */
    private val drawn = ArrayList<Point>()

    /**
     * How much of [drawn] is in a sealed piece already.
     *
     * The live end starts one point before this, so the two meet rather than
     * leaving a gap where a piece was closed.
     */
    private var sealed = 0
    private var pieces = 0

    private var tail: GeoJsonSource? = null
    private var removed = false

    private var lineWidth = 4f
    private var lineColor = 0xFFFF0000.toInt()

    private fun sourceOf(piece: Int) = "line-src-$id-$piece"
    private fun layerOf(piece: Int) = "line-lyr-$id-$piece"

    init {
        whenReady { style ->
            if (removed) return@whenReady
            tail = addPiece(style, 0)
            push(style)
        }
    }

    /**
     * A new piece on the map, above everything drawn so far.
     *
     * On top, which makes the order these are drawn the order they were made in
     * — the rule osmdroid's overlays followed. Inserted just above the tiles
     * instead, every new line went *under* the one before it, so the heading
     * line was beneath the flight it points out of and the two flickered where
     * they crossed.
     */
    private fun addPiece(style: Style, piece: Int): GeoJsonSource {
        val src = GeoJsonSource(sourceOf(piece))
        style.addSource(src)
        style.addLayer(
            LineLayer(layerOf(piece), sourceOf(piece)).withProperties(
                PropertyFactory.lineColor(lineColor),
                PropertyFactory.lineWidth(lineWidth),
                // A flight doubles back on itself and crosses its own track;
                // butt ends and mitred joins leave notches at every one.
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round")
            )
        )
        return src
    }

    /** Every piece, since a colour or a width belongs to the whole line. */
    private fun eachLayer(action: (LineLayer) -> Unit) {
        whenReady { style ->
            for (piece in 0..pieces) {
                style.getLayerAs<LineLayer>(layerOf(piece))?.let(action)
            }
        }
    }

    override var width: Float
        get() = lineWidth
        set(value) {
            lineWidth = value
            eachLayer { it.setProperties(PropertyFactory.lineWidth(value)) }
        }

    override var color: Int
        get() = lineColor
        set(value) {
            lineColor = value
            eachLayer { it.setProperties(PropertyFactory.lineColor(value)) }
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
        if (index < sealed) {
            // A point inside a piece that has been closed. Only the two-point
            // lines — the way home, the way ahead — are ever written to this
            // way, and they never reach a seam; a flight is only ever appended
            // to. Handled rather than assumed away, by starting again.
            rebuild()
        } else {
            push()
        }
    }

    override fun clear() {
        spoints.clear()
        points.clear()
        drawn.clear()
        rebuild()
    }

    override fun remove() {
        removed = true
        // How many there are now, not when this runs. The fields are reset
        // below and whenReady may hold the work until a style exists, which
        // would leave every piece but the first on the map for good.
        val last = pieces
        whenReady { style ->
            for (piece in 0..last) {
                style.removeLayer(layerOf(piece))
                style.removeSource(sourceOf(piece))
            }
        }
        tail = null
        points.clear()
        drawn.clear()
        sealed = 0
        pieces = 0
    }

    /** Take every piece but the first off, and lay the line down again. */
    private fun rebuild() = whenReady { style ->
        for (piece in 1..pieces) {
            style.removeLayer(layerOf(piece))
            style.removeSource(sourceOf(piece))
        }
        pieces = 0
        sealed = 0
        // Back to the first piece, which was not removed. Left pointing at the
        // last one, every push after this wrote to a source that had just been
        // taken off the map.
        tail = style.getSourceAs<GeoJsonSource>(sourceOf(0))
        push(style)
    }

    /**
     * Hand over the live end, sealing pieces behind it as it fills.
     *
     * Only the end is ever written. What is sealed has been given to the
     * renderer once and is never touched again, which is what stops the cost of
     * a point arriving from growing with the length of the flight.
     */
    private fun push() = whenReady { style -> push(style) }

    private fun push(style: Style) {
        var end = tail ?: return

        // Close off whole pieces while there are enough points for one. The
        // seam point belongs to both sides, so a piece runs to CHUNK inclusive
        // and the next starts there rather than after it.
        while (drawn.size - sealed > CHUNK) {
            style.getSourceAs<GeoJsonSource>(sourceOf(pieces))
                ?.setGeoJson(LineString.fromLngLats(drawn.subList(sealed, sealed + CHUNK + 1)))
            sealed += CHUNK
            pieces += 1
            end = addPiece(style, pieces)
            tail = end
        }

        val rest = drawn.subList(sealed, drawn.size)
        if (rest.size < 2) {
            // A line of one point is not a line, and MapLibre draws nothing for
            // it — but it also warns about it on every frame it is asked.
            end.setGeoJson(LineString.fromLngLats(emptyList<Point>()))
            return
        }
        end.setGeoJson(LineString.fromLngLats(ArrayList(rest)))
    }
}
