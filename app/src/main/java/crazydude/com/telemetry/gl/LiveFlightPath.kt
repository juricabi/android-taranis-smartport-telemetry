package crazydude.com.telemetry.gl

/**
 * The flight so far, where both the map screen and the 3D screen can reach it.
 *
 * Held here rather than passed along, because the 3D view is a second window
 * onto a flight that is still arriving: it has to see points appear without
 * the map screen knowing it is watching.
 */
object LiveFlightPath {

    private const val LIMIT = 20000

    private val points = ArrayList<TerrainScene.TrackPoint>()

    /** Bumped on every change, so a watcher can tell without copying the list. */
    @Volatile
    var version = 0
        private set

    /**
     * [altitudeMsl] may be NaN, for a link that says where it is but not how
     * high — no barometer, and a GPS that reports position only. Those fixes
     * belong to the flight as much as any other and are kept; what is done
     * about the missing height is the drawing's business, not this one's.
     */
    @Synchronized
    fun add(lat: Double, lon: Double, altitudeMsl: Float) {
        if (points.size >= LIMIT) thin()
        points.add(TerrainScene.TrackPoint(lat, lon, altitudeMsl))
        version++
    }

    /**
     * Every second point, when the limit is reached, so a long session goes on
     * flying at half the detail rather than stopping.
     *
     * It used to refuse the point instead. Nothing was said and nothing looked
     * broken: the map's own line kept growing, while everything drawn from this
     * — the model in three dimensions, its camera, the lines that start at it,
     * the altitude profile — stood still where the flight had been half an hour
     * earlier. An hour of ten-a-second fixes is all it takes.
     */
    private fun thin() {
        val kept = ArrayList<TerrainScene.TrackPoint>(points.size / 2 + 2)
        var i = 0
        while (i < points.size) {
            kept.add(points[i])
            i += 2
        }
        // the newest is where the model is, and is worth more than its place in
        // the sequence
        val last = points[points.size - 1]
        if (kept[kept.size - 1] !== last) kept.add(last)
        points.clear()
        points.addAll(kept)
        // Everything that has been following this by index has to start again:
        // the point that was tenth is now somewhere else entirely.
        generation++
    }

    /**
     * Bumped when the path is thinned, which moves every point that was being
     * counted from. A watcher keeping its place by index has to rebuild.
     */
    @Volatile
    var generation = 0
        private set

    @Synchronized
    fun snapshot(): List<TerrainScene.TrackPoint> = ArrayList(points)

    @Synchronized
    fun size(): Int = points.size

    /** Everything added after [index], for a watcher that has kept up to there. */
    @Synchronized
    fun since(index: Int): List<TerrainScene.TrackPoint> =
        if (index >= points.size) emptyList()
        else ArrayList(points.subList(Math.max(0, index), points.size))

    @Synchronized
    fun latest(): TerrainScene.TrackPoint? = if (points.isEmpty()) null else points[points.size - 1]

    @Synchronized
    fun clear() {
        points.clear()
        version++
        generation++
    }
}
