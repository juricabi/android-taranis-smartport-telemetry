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

    @Synchronized
    fun add(lat: Double, lon: Double, altitudeMsl: Float) {
        if (points.size >= LIMIT) return
        points.add(TerrainScene.TrackPoint(lat, lon, altitudeMsl))
        version++
    }

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
    }
}
