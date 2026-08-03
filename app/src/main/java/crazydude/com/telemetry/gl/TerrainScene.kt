package crazydude.com.telemetry.gl

import android.graphics.Bitmap
import crazydude.com.telemetry.utils.Elevation
import crazydude.com.telemetry.utils.Imagery

/**
 * The flight and the ground under it, turned into something drawable.
 *
 * Everything here runs off the GL thread and touches no GL call: it fetches
 * tiles, samples heights and fills plain arrays. The renderer takes what comes
 * out and uploads it.
 *
 * Positions are metres in a local frame with its origin at the middle of the
 * flight — x east, y up, z south — because working in degrees would lose all
 * precision by the time it reached a float.
 */
class TerrainScene {

    class TrackPoint(val lat: Double, val lon: Double, val altitudeMsl: Float)

    /** One terrain tile: a grid of ground, with the aerial view of it. */
    class TileMesh(
        val vertices: FloatArray,   // x, y, z, u, v, nx, ny, nz
        val indices: ShortArray,
        val texture: Bitmap?
    )

    companion object {
        /** Vertices across one tile: 193 gives about 9m between them at zoom 14. */
        private const val GRID = 193

        /**
         * Ground tiles at zoom 14 are about 1.7km across, so a texture two
         * levels in is roughly 1.7m per pixel. Three levels would be sharper
         * and four times the memory, which a phone will not thank us for.
         */
        private const val IMAGERY_DETAIL = 3

        /** Preferred ground detail, dropped a level at a time if that is too many tiles. */
        private const val PREFERRED_ZOOM = 15

        /**
         * A 2048px texture is 16MB, so fewer tiles at more detail. Nine covers
         * a flight with room around it.
         */
        private const val MAX_TILES = 9

        private const val METRES_PER_DEGREE_LAT = 111320.0
    }

    var originLat = 0.0
        private set
    var originLon = 0.0
        private set
    var originAltitude = 0f
        private set
    private var originFixed = false

    /** Flight path as x, y, z triples in the local frame. */
    var track = FloatArray(0)
        private set

    /** The same path dropped onto the ground, for a shadow and drop lines. */
    var shadow = FloatArray(0)
        private set

    var tiles: List<TileMesh> = emptyList()
        private set

    /** Kept between loads so extending the ground only builds what is new. */
    private val built = LinkedHashMap<Long, TileMesh>()
    private var builtZoom = -1

    /** Half the width of the flight, for placing the camera. */
    var extent = 500f
        private set

    var minLat = 0.0; private set
    var maxLat = 0.0; private set
    var minLon = 0.0; private set
    var maxLon = 0.0; private set

    private fun metresPerDegreeLon(lat: Double): Double =
        METRES_PER_DEGREE_LAT * Math.cos(Math.toRadians(lat))

    fun east(lon: Double): Float = ((lon - originLon) * metresPerDegreeLon(originLat)).toFloat()

    fun north(lat: Double): Float = ((lat - originLat) * METRES_PER_DEGREE_LAT).toFloat()

    /**
     * Work out the frame from the flight, then build the track arrays. Cheap;
     * no network. Returns false if there is nothing to show.
     */
    /**
     * Fix the frame without touching the track.
     *
     * A live flight cannot re-centre as it goes: the ground is built once in
     * this frame, so moving the origin afterwards would slide the terrain out
     * from under the path.
     */
    fun setOrigin(lat: Double, lon: Double, altitude: Float) {
        originLat = lat
        originLon = lon
        originAltitude = altitude
        // Only claim the area when there is nothing else to go on. Calling this
        // to fix an origin already worked out from a flight must not throw that
        // flight's extent away, or the ground gets built around a point.
        if (!originFixed) {
            minLat = lat; maxLat = lat
            minLon = lon; maxLon = lon
        }
        originFixed = true
    }

    /** Rebuild the path in the frame already chosen. Cheap; call as often as needed. */
    /** The reported height, brought to sea level whichever way it was measured. */
    fun aboveSeaLevel(reported: Float): Float = reported + launchGroundElevation

    /**
     * Rebuild the path in the frame already chosen.
     *
     * Thinned to a few thousand points however long the flight is: a screen
     * cannot show more, and rebuilding twenty thousand of them twice a second
     * is work that grows for the whole flight.
     */
    fun buildTrack(all: List<TrackPoint>) {
        val stride = Math.max(1, all.size / 3000)
        val points = if (stride == 1) {
            all
        } else {
            val thinned = ArrayList<TrackPoint>(all.size / stride + 2)
            var i = 0
            while (i < all.size) {
                thinned.add(all[i])
                i += stride
            }
            // the newest point always, so the model sits where it really is
            if (thinned.isEmpty() || thinned[thinned.size - 1] !== all[all.size - 1]) {
                thinned.add(all[all.size - 1])
            }
            thinned
        }
        val raw = FloatArray(points.size * 3)
        var i = 0
        for (p in points) {
            raw[i++] = east(p.lon)
            raw[i++] = aboveSeaLevel(p.altitudeMsl) - originAltitude
            raw[i++] = -north(p.lat)
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
        }
        track = smoothed(raw)
        buildShadow(points)
    }

    /**
     * Each point drawn a quarter of the way towards each of its neighbours.
     *
     * A position is good to a few metres and the next one is wrong by a
     * different few, so a line through them saws back and forth about a path
     * that was flown smoothly. The ends stay put: the first point is the launch
     * and the last is where the model is now.
     */
    private fun smoothed(raw: FloatArray): FloatArray {
        if (raw.size < 9) return raw
        val out = FloatArray(raw.size)
        for (axis in 0..2) {
            out[axis] = raw[axis]
            out[raw.size - 3 + axis] = raw[raw.size - 3 + axis]
        }
        var i = 3
        while (i < raw.size - 3) {
            out[i] = 0.25f * raw[i - 3] + 0.5f * raw[i] + 0.25f * raw[i + 3]
            i++
        }
        return out
    }

    fun setTrack(points: List<TrackPoint>): Boolean {
        if (points.size < 2) return false
        minLat = points[0].lat; maxLat = points[0].lat
        minLon = points[0].lon; maxLon = points[0].lon
        var lowest = points[0].altitudeMsl
        for (p in points) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
            if (p.altitudeMsl < lowest) lowest = p.altitudeMsl
        }
        if (!originFixed) {
            originLat = (minLat + maxLat) / 2
            originLon = (minLon + maxLon) / 2
            originAltitude = lowest
            originFixed = true
        }

        val out = FloatArray(points.size * 3)
        var i = 0
        for (p in points) {
            out[i++] = east(p.lon)
            out[i++] = aboveSeaLevel(p.altitudeMsl) - originAltitude
            // north is -z, so the view looks the way a map does with north up
            out[i++] = -north(p.lat)
        }
        track = out

        val halfWidth = Math.abs(east(maxLon) - east(minLon)) / 2
        val halfHeight = Math.abs(north(maxLat) - north(minLat)) / 2
        extent = Math.max(200f, Math.max(halfWidth, halfHeight))
        return true
    }

    /**
     * Fetch the ground and the imagery over it. Blocking, and slow the first
     * time; afterwards it comes from the cache. [onProgress] reports tiles.
     */
    fun loadTerrain(points: List<TrackPoint>,
                    onProgress: (Int, Int) -> Unit,
                    onTerrainReady: () -> Unit) {
        // Half a kilometre around the flight to begin with, so the first
        // picture arrives in seconds; more is fetched as the model approaches
        // the edge of what has been built.
        val padLat = Math.max(0.0045, (maxLat - minLat) * 0.5)
        val padLon = Math.max(0.0065, (maxLon - minLon) * 0.5)
        val southEdge = minLat - padLat
        val northEdge = maxLat + padLat
        val westEdge = minLon - padLon
        val eastEdge = maxLon + padLon

        loadedMinLat = if (builtZoom < 0) southEdge else Math.min(loadedMinLat, southEdge)
        loadedMaxLat = if (builtZoom < 0) northEdge else Math.max(loadedMaxLat, northEdge)
        loadedMinLon = if (builtZoom < 0) westEdge else Math.min(loadedMinLon, westEdge)
        loadedMaxLon = if (builtZoom < 0) eastEdge else Math.max(loadedMaxLon, eastEdge)

        // As much detail as the area allows: drop a zoom level at a time until
        // the tile count is something a phone can hold.
        var z = PREFERRED_ZOOM
        while (z > 9 && tileCount(southEdge, westEdge, northEdge, eastEdge, z) > MAX_TILES) {
            z--
        }
        Elevation.prefetch(southEdge, westEdge, northEdge, eastEdge, z,
            { done, total -> onProgress(done, total * 2) },
            { _, _ -> })
        zoom = z
        resolveAltitudeReference(points)
        onTerrainReady()

        // ground first, imagery second: the shape matters more than the picture,
        // and this way something is on screen while the textures arrive
        buildShadow(points)

        val x0 = Elevation.tileX(westEdge, z)
        val x1 = Elevation.tileX(eastEdge, z)
        val y0 = Elevation.tileY(northEdge, z)
        val y1 = Elevation.tileY(southEdge, z)
        // A change of detail makes every mesh the wrong shape, so start again;
        // otherwise keep what is built and add only what is missing.
        if (z != builtZoom) {
            built.clear()
            builtZoom = z
        }

        val total = (Math.abs(x1 - x0) + 1) * (Math.abs(y1 - y0) + 1)
        var done = 0
        for (tx in Math.min(x0, x1)..Math.max(x0, x1)) {
            for (ty in Math.min(y0, y1)..Math.max(y0, y1)) {
                val key = tileKey(tx, ty)
                if (!built.containsKey(key)) {
                    val mesh = buildTile(z, tx, ty)
                    if (mesh != null) built[key] = mesh
                }
                done++
                onProgress(total + done, total * 2)
                tiles = ArrayList(built.values)
            }
        }
        // oldest first out, so a long flight does not collect the whole county
        while (built.size > MAX_TILES) {
            val oldest = built.keys.iterator()
            if (!oldest.hasNext()) break
            built.remove(oldest.next())
        }
        tiles = ArrayList(built.values)
    }

    private fun tileKey(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    /** True when the altitudes turned out to be measured from the launch point. */
    var altitudeIsAboveLaunch = false
        private set

    private var altitudeResolved = false

    /**
     * Work out what the reported altitude is measured from.
     *
     * It cannot be assumed. Betaflight sends height above sea level while the
     * model is disarmed and height above the arming point once it is armed, so
     * the same field means two different things within one flight, and drawing
     * the second against sea level terrain would bury the model a hundred
     * metres underground.
     *
     * The ground answers it: at the first fix we know what the terrain there
     * is, so if the reported altitude is nothing like it, the reports are
     * measured from the launch and the ground under it is what they are
     * missing.
     */
    private fun resolveAltitudeReference(points: List<TrackPoint>) {
        if (points.isEmpty()) return

        // A model cannot fly below the ground.
        //
        // That is the whole test, and it is the only one that holds. Comparing
        // a single reading against the terrain under it does not: a model
        // seventy metres above a field a hundred metres up reads as a plausible
        // seventy metres above the sea, and the flight ends up buried.
        //
        // So take the lowest height reported anywhere on the flight and the
        // lowest ground beneath it. If the reports go well below the ground,
        // they are not sea level heights — they are measured from the launch,
        // and the ground under the launch is what they are missing.
        var lowestReported = points[0].altitudeMsl
        var lowestGround = Float.NaN
        var groundAtStart = Float.NaN
        for (p in points) {
            if (p.altitudeMsl < lowestReported) lowestReported = p.altitudeMsl
            val ground = Elevation.elevationAt(p.lat, p.lon, zoom) ?: continue
            if (groundAtStart.isNaN()) groundAtStart = ground
            if (lowestGround.isNaN() || ground < lowestGround) lowestGround = ground
        }
        if (lowestGround.isNaN()) return

        // thirty metres of slack for the terrain data, which is thirty metre
        // data, and for a fix that is only good to a few metres vertically
        val aboveLaunch = lowestReported < lowestGround - 30f
        val newOrigin = if (aboveLaunch) lowestReported + groundAtStart else lowestReported

        // Only the answer matters, not the exact origin. The lowest point of a
        // flight keeps dropping as it descends, and following that would move
        // the frame — and rebuild every tile in it — every few seconds. The
        // origin is only a reference; where it sits is arbitrary.
        // One way only. Heights that once went below the ground cannot later
        // turn out to have been sea level ones, and letting the answer flip
        // back — as more ground loads and the lowest ground beneath the track
        // drops — would move the whole world under the flight.
        if (altitudeResolved && (aboveLaunch == altitudeIsAboveLaunch || altitudeIsAboveLaunch)) {
            return
        }
        altitudeResolved = true
        altitudeIsAboveLaunch = aboveLaunch
        launchGroundElevation = if (aboveLaunch) groundAtStart else 0f
        originAltitude = newOrigin
        built.clear()
        builtZoom = -1
    }

    /** Added to every reported altitude when they are measured from the launch. */
    var launchGroundElevation = 0f
        private set

    private fun tileLon(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360.0 - 180.0

    private fun tileLat(y: Int, z: Int): Double {
        val n = Math.PI - 2.0 * Math.PI * y / (1 shl z)
        return Math.toDegrees(Math.atan(Math.sinh(n)))
    }

    private fun buildTile(z: Int, tx: Int, ty: Int): TileMesh? {
        val westLon = tileLon(tx, z)
        val eastLon = tileLon(tx + 1, z)
        val northLat = tileLat(ty, z)
        val southLat = tileLat(ty + 1, z)

        val heights = FloatArray(GRID * GRID)
        var any = false
        for (row in 0 until GRID) {
            val lat = northLat + (southLat - northLat) * row / (GRID - 1)
            for (col in 0 until GRID) {
                val lon = westLon + (eastLon - westLon) * col / (GRID - 1)
                val h = Elevation.elevationAt(lat, lon, z)
                if (h != null) {
                    heights[row * GRID + col] = h - originAltitude
                    any = true
                } else {
                    heights[row * GRID + col] = Float.NaN
                }
            }
        }
        if (!any) return null
        fillGaps(heights)

        val vertices = FloatArray(GRID * GRID * 8)
        val spacing = Math.abs(east(eastLon) - east(westLon)) / (GRID - 1)
        var v = 0
        for (row in 0 until GRID) {
            val lat = northLat + (southLat - northLat) * row / (GRID - 1)
            for (col in 0 until GRID) {
                val lon = westLon + (eastLon - westLon) * col / (GRID - 1)
                val h = heights[row * GRID + col]
                vertices[v++] = east(lon)
                vertices[v++] = h
                vertices[v++] = -north(lat)
                vertices[v++] = col.toFloat() / (GRID - 1)
                vertices[v++] = row.toFloat() / (GRID - 1)
                // normal from the neighbouring heights, for a little relief
                val hl = heights[row * GRID + Math.max(0, col - 1)]
                val hr = heights[row * GRID + Math.min(GRID - 1, col + 1)]
                val hu = heights[Math.max(0, row - 1) * GRID + col]
                val hd = heights[Math.min(GRID - 1, row + 1) * GRID + col]
                val nx = (hl - hr)
                val nz = (hu - hd)
                val ny = if (spacing > 0.01f) 2f * spacing else 1f
                val len = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
                vertices[v++] = nx / len
                vertices[v++] = ny / len
                vertices[v++] = nz / len
            }
        }

        val indices = ShortArray((GRID - 1) * (GRID - 1) * 6)
        var i = 0
        for (row in 0 until GRID - 1) {
            for (col in 0 until GRID - 1) {
                val a = (row * GRID + col).toShort()
                val b = (row * GRID + col + 1).toShort()
                val c = ((row + 1) * GRID + col).toShort()
                val d = ((row + 1) * GRID + col + 1).toShort()
                indices[i++] = a; indices[i++] = c; indices[i++] = b
                indices[i++] = b; indices[i++] = c; indices[i++] = d
            }
        }

        val texture = try {
            Imagery.mosaic(z, tx, ty, IMAGERY_DETAIL)
        } catch (e: Throwable) {
            null
        }
        return TileMesh(vertices, indices, texture)
    }

    /** A hole in the data becomes the average of what is around it, not a pit. */
    private fun fillGaps(heights: FloatArray) {
        var sum = 0.0
        var count = 0
        for (h in heights) {
            if (!h.isNaN()) {
                sum += h
                count++
            }
        }
        if (count == 0) return
        val average = (sum / count).toFloat()
        for (i in heights.indices) {
            if (heights[i].isNaN()) heights[i] = average
        }
    }

    /** The detail the ground was built at, chosen from how much area it covers. */
    var zoom = PREFERRED_ZOOM
        private set

    private fun tileCount(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
                          z: Int): Int {
        val x0 = Elevation.tileX(minLon, z)
        val x1 = Elevation.tileX(maxLon, z)
        val y0 = Elevation.tileY(maxLat, z)
        val y1 = Elevation.tileY(minLat, z)
        return (Math.abs(x1 - x0) + 1) * (Math.abs(y1 - y0) + 1)
    }

    /** The area the ground was built for, so a live flight can tell when it leaves it. */
    var loadedMinLat = 0.0; private set
    var loadedMaxLat = 0.0; private set
    var loadedMinLon = 0.0; private set
    var loadedMaxLon = 0.0; private set

    /**
     * Whether a position is close enough to the edge of the built ground to be
     * worth fetching more. Half a kilometre of warning, so the tiles are there
     * before the model needs them rather than after it has flown off the end.
     */
    fun nearEdge(lat: Double, lon: Double): Boolean {
        if (loadedMaxLat == loadedMinLat) return true
        val marginLat = 500.0 / METRES_PER_DEGREE_LAT
        val marginLon = 500.0 / metresPerDegreeLon(lat)
        return lat < loadedMinLat + marginLat || lat > loadedMaxLat - marginLat ||
            lon < loadedMinLon + marginLon || lon > loadedMaxLon - marginLon
    }

    fun groundAt(lat: Double, lon: Double): Float? = Elevation.elevationAt(lat, lon, zoom)

    fun buildShadow(points: List<TrackPoint>) {
        val out = FloatArray(points.size * 3)
        var i = 0
        for (p in points) {
            val ground = Elevation.elevationAt(p.lat, p.lon, zoom)
            out[i++] = east(p.lon)
            out[i++] = if (ground != null) ground - originAltitude else 0f
            out[i++] = -north(p.lat)
        }
        shadow = out
    }
}
