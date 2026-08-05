package juricabi.com.telemetry.maps.maplibre

import android.os.Handler
import android.os.Looper
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
 * every line on every frame, on the UI thread, so a long flight cost
 * milliseconds a frame before anything else was drawn. Here the points live in
 * the renderer's own buffers and what a frame costs stopped depending on how
 * many there are — which is what paid for [MapLine.commitPoints] no longer
 * thinning the track.
 *
 * **Drawing it is free; handing it over is not.** A source cannot be appended
 * to, only replaced, so a line kept as one source is copied to the renderer in
 * full every time it changes at all. Two things make that hurt:
 *
 * - a point arriving, which happens as fast as the fixes do
 * - a replay dragged backwards, which drops the whole flight and lays it down
 *   again at every step of the seek bar
 *
 * So the line is a run of pieces of [CHUNK] points, and only the pieces whose
 * contents have actually changed are handed over. A point arriving rewrites the
 * last piece. A seek backwards is a *prefix* of the flight already drawn, so it
 * rewrites the single piece the new end falls inside and empties those past it
 * — whatever the flight's length, and however hard the bar is dragged.
 *
 * Consecutive pieces share their joining point, or the line has a gap at every
 * seam.
 */
class MapLibreLine(
    private val id: String,
    /**
     * A layer to stay under, asked for each time a piece is made rather than
     * once when the line is.
     *
     * The flight line is made before the model's marker exists, so asked once
     * it would be told there is nothing to stay under — and then every piece
     * added as the flight grew would go on top of the model, which is what
     * happened: after enough laps the track was drawn over the aircraft.
     */
    private val below: () -> String?,
    private val whenReady: ((Style) -> Unit) -> Unit
) : MapLine() {

    companion object {
        /**
         * How many points a piece spans.
         *
         * The most that is ever handed over for one change. Small enough that
         * the copy is not felt, large enough that a long flight does not end up
         * with hundreds of layers: a hundred thousand points is fifty pieces.
         */
        private const val CHUNK = 2000

        /**
         * How long a cleared line waits before it is believed.
         *
         * Rewinding a replay empties the line and fills it again from the next
         * batch, a turn of the main loop apart. Emptying the renderer in
         * between throws away the very points the refill is about to match
         * itself against, which turns every step of a backward scrub into a
         * rebuild of the whole flight. So a clear settles rather than landing
         * at once: a refill overtakes it, and a line that really was emptied
         * goes dark a moment later.
         */
        private const val SETTLE_MS = 120L
    }

    private val points = ArrayList<Position>()

    /** The points as the renderer takes them. */
    private val drawn = ArrayList<Point>()

    /** What the renderer has been given, so it can be told only what changed. */
    private val rendered = ArrayList<Point>()

    /** The deepest piece ever put on the map; -1 while there are none. */
    private var created = -1

    private var removed = false
    private var lineWidth = 4f
    private var lineColor = 0xFFFF0000.toInt()

    private val settle = Handler(Looper.getMainLooper())
    private val settleEmpty = Runnable { push() }

    private fun sourceOf(piece: Int) = "line-src-$id-$piece"
    private fun layerOf(piece: Int) = "line-lyr-$id-$piece"

    init {
        whenReady { if (!removed) push() }
    }

    /**
     * A piece on the map, made once and used again after that.
     *
     * Never taken off and remade: a scrub empties and refills the line many
     * times a second, and removing a source while adding another under the same
     * name that fast asks the renderer to tear one down while it is still
     * reading it.
     */
    private fun pieceSource(style: Style, piece: Int): GeoJsonSource {
        style.getSourceAs<GeoJsonSource>(sourceOf(piece))?.let { return it }
        val src = GeoJsonSource(sourceOf(piece))
        style.addSource(src)
        val lyr = LineLayer(layerOf(piece), sourceOf(piece)).withProperties(
            PropertyFactory.lineColor(lineColor),
            PropertyFactory.lineWidth(lineWidth),
            // A flight doubles back on itself and crosses its own track; butt
            // ends and mitred joins leave notches at every one.
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round")
        )
        val above = if (piece > 0) layerOf(piece - 1) else null
        val under = below()
        when {
            // A piece after the first goes directly above the one before it, so
            // the whole line stays one band in the stack and a growing flight
            // keeps the depth it was given. Sent to the top, or to just under
            // the model, instead: every piece added as the flight went on
            // jumped over the lines home and ahead, which were made before the
            // model and are therefore below it.
            above != null && style.getLayer(above) != null ->
                style.addLayerAbove(lyr, above)
            // The first piece keeps out from under whatever has been named —
            // the model, for a line made after it, like the heading line put
            // back when it is switched on in the settings.
            under != null && style.getLayer(under) != null ->
                style.addLayerBelow(lyr, under)
            else -> style.addLayer(lyr)
        }
        if (piece > created) created = piece
        return src
    }

    /** Every piece, since a colour or a width belongs to the whole line. */
    private fun eachLayer(action: (LineLayer) -> Unit) {
        whenReady { style ->
            for (piece in 0..created) {
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
        settle.removeCallbacks(settleEmpty)
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
        // Not handed over yet — see SETTLE_MS. What the renderer still holds is
        // what the refill about to arrive will be compared against.
        settle.removeCallbacks(settleEmpty)
        settle.postDelayed(settleEmpty, SETTLE_MS)
    }

    override fun remove() {
        removed = true
        settle.removeCallbacks(settleEmpty)
        // How many there are now, not when this runs: whenReady may hold the
        // work until a style exists, by which time the count has been reset.
        val last = created
        whenReady { style ->
            for (piece in 0..last) {
                style.removeLayer(layerOf(piece))
                style.removeSource(sourceOf(piece))
            }
        }
        points.clear()
        drawn.clear()
        rendered.clear()
        created = -1
    }

    /** How many pieces a line of this many points needs. */
    private fun piecesFor(count: Int) = if (count < 2) 0 else ((count - 2) / CHUNK) + 1

    /**
     * Hand over only the pieces whose contents have changed.
     *
     * The first point that differs from what the renderer already holds says
     * where rewriting starts; everything before it is already right and is left
     * alone. Appending a fix touches the last piece. Rewinding a replay touches
     * the piece the new end falls in, and empties those past it.
     */
    private fun push() = whenReady { style ->
        val shared = firstDifference()
        if (shared == drawn.size && shared == rendered.size) return@whenReady

        val wanted = piecesFor(drawn.size)
        val had = piecesFor(rendered.size)
        val from = if (wanted == 0) 0 else Math.min(shared / CHUNK, wanted - 1)

        for (piece in from until wanted) {
            val start = piece * CHUNK
            val end = Math.min(start + CHUNK, drawn.size - 1)
            // A copy. Handed the sub-list itself, the renderer is given a
            // window onto a list this thread goes on writing to — and it reads
            // it on a worker of its own, which is a native crash the moment the
            // two overlap.
            pieceSource(style, piece)
                .setGeoJson(LineString.fromLngLats(ArrayList(drawn.subList(start, end + 1))))
        }
        // Whatever the line no longer reaches, emptied rather than taken off so
        // the next flight that grows this long finds it already there. A line
        // of one point is not a line either, and the renderer complains on
        // every frame it is asked to draw one.
        // At least one, so a line worn down to a single point — which is not a
        // line, and which the renderer complains about on every frame it is
        // asked to draw — is emptied rather than left as it was.
        for (piece in wanted until Math.max(had, 1)) {
            if (piece > created) break
            style.getSourceAs<GeoJsonSource>(sourceOf(piece))
                ?.setGeoJson(LineString.fromLngLats(emptyList<Point>()))
        }

        if (shared < rendered.size) rendered.subList(shared, rendered.size).clear()
        for (i in shared until drawn.size) rendered.add(drawn[i])
    }

    /** Where what is wanted and what is drawn stop agreeing. */
    private fun firstDifference(): Int {
        val common = Math.min(drawn.size, rendered.size)
        var i = 0
        while (i < common && drawn[i] === rendered[i]) i++
        return i
    }
}
