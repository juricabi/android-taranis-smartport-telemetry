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
        /** Vertices across one tile: 129 gives 128 cells, about 13m at zoom 14. */
        private const val GRID = 129

        /**
         * Ground tiles at zoom 14 are about 1.7km across, so a texture two
         * levels in is roughly 1.7m per pixel. Three levels would be sharper
         * and four times the memory, which a phone will not thank us for.
         */
        private const val IMAGERY_DETAIL = 2

        /** Preferred ground detail, dropped a level at a time if that is too many tiles. */
        private const val PREFERRED_ZOOM = 14

        /** Textures are 4MB each, so this is the real budget. */
        private const val MAX_TILES = 16

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
        originFixed = true
        minLat = lat; maxLat = lat
        minLon = lon; maxLon = lon
    }

    /** Rebuild the path in the frame already chosen. Cheap; call as often as needed. */
    fun buildTrack(points: List<TrackPoint>) {
        val out = FloatArray(points.size * 3)
        var i = 0
        for (p in points) {
            out[i++] = east(p.lon)
            out[i++] = p.altitudeMsl - originAltitude
            out[i++] = -north(p.lat)
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
        }
        track = out
        buildShadow(points)
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
            out[i++] = p.altitudeMsl - originAltitude
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
        // Roughly two kilometres of ground around the flight, so there is
        // somewhere to look when the camera swings out, and a hill beyond the
        // flight is still part of the picture.
        val padLat = Math.max(0.02, (maxLat - minLat) * 0.5)
        val padLon = Math.max(0.03, (maxLon - minLon) * 0.5)
        val southEdge = minLat - padLat
        val northEdge = maxLat + padLat
        val westEdge = minLon - padLon
        val eastEdge = maxLon + padLon

        loadedMinLat = southEdge; loadedMaxLat = northEdge
        loadedMinLon = westEdge; loadedMaxLon = eastEdge

        // As much detail as the area allows: drop a zoom level at a time until
        // the tile count is something a phone can hold.
        var z = PREFERRED_ZOOM
        while (z > 9 && tileCount(southEdge, westEdge, northEdge, eastEdge, z) > MAX_TILES) {
            z--
        }
        zoom = z

        Elevation.prefetch(southEdge, westEdge, northEdge, eastEdge, z,
            { done, total -> onProgress(done, total * 2) },
            { _, _ -> })
        onTerrainReady()

        // ground first, imagery second: the shape matters more than the picture,
        // and this way something is on screen while the textures arrive
        buildShadow(points)

        val x0 = Elevation.tileX(westEdge, z)
        val x1 = Elevation.tileX(eastEdge, z)
        val y0 = Elevation.tileY(northEdge, z)
        val y1 = Elevation.tileY(southEdge, z)
        val built = ArrayList<TileMesh>()
        val total = (Math.abs(x1 - x0) + 1) * (Math.abs(y1 - y0) + 1)
        var done = 0
        for (tx in Math.min(x0, x1)..Math.max(x0, x1)) {
            for (ty in Math.min(y0, y1)..Math.max(y0, y1)) {
                val mesh = buildTile(z, tx, ty)
                if (mesh != null) built.add(mesh)
                done++
                onProgress(total + done, total * 2)
                tiles = ArrayList(built)
            }
        }
        tiles = built
    }

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

    fun outsideLoaded(lat: Double, lon: Double): Boolean {
        if (loadedMaxLat == loadedMinLat) return true
        return lat < loadedMinLat || lat > loadedMaxLat || lon < loadedMinLon || lon > loadedMaxLon
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
