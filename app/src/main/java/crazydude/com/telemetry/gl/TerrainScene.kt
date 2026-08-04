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

    /** [altitudeMsl] is NaN where the link reported no height at all. */
    class TrackPoint(val lat: Double, val lon: Double, val altitudeMsl: Float)

    /** One terrain tile: a grid of ground, with the aerial view of it. */
    class TileMesh(
        /** Which tile of the world this is, so it is uploaded once and no more. */
        val key: Long,
        val vertices: FloatArray,   // x, y, z, u, v, nx, ny, nz
        val indices: ShortArray,
        val texture: Bitmap?
    )

    companion object {
        /**
         * Vertices across one tile: 193 gives about 9m between them at zoom 14.
         *
         * Not the thing to trim for speed. Two thirds of a million triangles a
         * frame is a fraction of what the phone can draw, and what actually
         * cost time was uploading the textures and rebuilding the flight, not
         * this. It does set how long a tile takes to build, though — one height
         * lookup per vertex — so it is the place to look if the first load ever
         * feels slow.
         */
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
         * A 2048px texture is 16MB, so this is a memory budget before it is
         * anything else. The window needs nine; the rest is slack, so ground
         * the model has just left is still there if it turns back.
         */
        private const val MAX_TILES = 12

        /**
         * How far the ground reaches around the model, in metres.
         *
         * Nine hundred is a window of one and a three quarter kilometres, which
         * at the finest zoom is nine tiles. From behind the model the camera
         * sees well under a kilometre of it, so there is room to fly and room
         * to look about before more is wanted.
         */
        private const val WINDOW_RADIUS_M = 900.0

        private const val METRES_PER_DEGREE_LAT = 111320.0

        /** What reported heights turned out to be measured from. */
        class Reference(
            val aboveLaunch: Boolean,
            /** What to add to them to make them heights above the sea. */
            val lift: Float,
            /** The lowest of them, low outliers excepted. */
            val lowest: Float
        )

        /**
         * Work out what the reported altitude is measured from.
         *
         * It cannot be assumed. Betaflight sends height above sea level while
         * the model is disarmed and height above the arming point once it is
         * armed, so the same field means two different things within one
         * flight, and drawing the second against sea level terrain would bury
         * the model a hundred metres underground.
         *
         * A model cannot fly below the ground. That is the whole test, and it
         * is the only one that holds: comparing a single reading against the
         * terrain under it does not, since a model seventy metres above a field
         * a hundred metres up reads as a plausible seventy metres above the
         * sea. So take the lowest height reported anywhere on the flight and
         * the lowest ground beneath it, and if the reports go well below the
         * ground they are measured from the launch.
         *
         * The 3D view and the altitude profile both ask this, so that they
         * cannot disagree about where a flight is.
         */
        fun referenceOf(points: List<TrackPoint>, zoom: Int): Reference? {
            if (points.isEmpty()) return null

            // Not the very lowest reading: one bad fix — and a receiver that
            // has just started reports a few — would decide this for the whole
            // flight, and the answer is kept once it is made. The lowest
            // twentieth is still on the ground and cannot be one stray sample.
            // Fixes that carried no height say nothing about what heights
            // mean. A flight of nothing but those has no question to answer.
            var known = 0
            for (p in points) if (!p.altitudeMsl.isNaN()) known++
            if (known == 0) return null
            val reported = FloatArray(known)
            var at = 0
            for (p in points) if (!p.altitudeMsl.isNaN()) reported[at++] = p.altitudeMsl
            java.util.Arrays.sort(reported)
            val lowestReported = reported[reported.size / 20]

            var lowestGround = Float.NaN
            var groundAtStart = Float.NaN
            for (p in points) {
                val ground = Elevation.elevationAt(p.lat, p.lon, zoom) ?: continue
                if (groundAtStart.isNaN()) groundAtStart = ground
                if (lowestGround.isNaN() || ground < lowestGround) lowestGround = ground
            }
            if (lowestGround.isNaN()) return null

            // thirty metres of slack for the terrain data, which is thirty
            // metre data, and for a fix only good to a few metres vertically
            val aboveLaunch = lowestReported < lowestGround - 30f
            return Reference(aboveLaunch, if (aboveLaunch) groundAtStart else 0f, lowestReported)
        }
    }

    var originLat = 0.0
        private set
    var originLon = 0.0
        private set
    /**
     * The height everything is drawn relative to.
     *
     * Taken from the ground under the origin as soon as the terrain knows it,
     * and never changed after — which is what makes it a datum. It used to come
     * from the reported altitudes, so settling what those meant moved the whole
     * world: every tile had to be built again, the ground visibly dropped when
     * the link came up, and anything already drawn in the old frame was left
     * hanging in the new one.
     *
     * What the reported heights are measured from is a separate question, and
     * only moves the flight within the world — see [launchGroundElevation].
     */
    var originAltitude = 0f
    private var datumFromGround = false
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
        if (!datumFromGround) originAltitude = altitude
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
            raw[i++] = heightOf(p)
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

    /**
     * The height to draw a fix at: its own where it has one, the ground where
     * it has not.
     *
     * A link with no barometer and no GPS height says where it is and nothing
     * about how high, and those fixes are kept — a flight without heights is
     * still a flight, and dropping them left the map bare. Laid along the
     * ground they say exactly what is known and claim nothing that is not.
     */
    fun heightOf(p: TrackPoint): Float =
        if (p.altitudeMsl.isNaN()) {
            (groundAt(p.lat, p.lon) ?: originAltitude) - originAltitude
        } else {
            aboveSeaLevel(p.altitudeMsl) - originAltitude
        }

    fun setTrack(points: List<TrackPoint>): Boolean {
        if (points.size < 2) return false
        minLat = points[0].lat; maxLat = points[0].lat
        minLon = points[0].lon; maxLon = points[0].lon
        var lowest = Float.NaN
        for (p in points) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
            if (!p.altitudeMsl.isNaN() && (lowest.isNaN() || p.altitudeMsl < lowest)) {
                lowest = p.altitudeMsl
            }
        }
        if (!originFixed) {
            originLat = (minLat + maxLat) / 2
            originLon = (minLon + maxLon) / 2
            // a stand-in until the ground is loaded and says otherwise
            if (!datumFromGround && !lowest.isNaN()) originAltitude = lowest
            originFixed = true
        }

        val out = FloatArray(points.size * 3)
        var i = 0
        for (p in points) {
            out[i++] = east(p.lon)
            out[i++] = heightOf(p)
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
                    focusLat: Double, focusLon: Double,
                    onTile: () -> Unit,
                    onDone: () -> Unit) {
        // Ground around the model, not ground around the whole flight.
        //
        // Covering everything flown meant the tiles had to span it, and since
        // there is only ever a handful of them the detail dropped a level for
        // every few kilometres: fifteen kilometres out and back left the ground
        // under the model at thirteen metres a pixel — a blur — to keep a
        // picture of somewhere it flew twenty minutes ago. A window that
        // follows it stays sharp however far it goes.
        val padLat = WINDOW_RADIUS_M / METRES_PER_DEGREE_LAT
        val padLon = WINDOW_RADIUS_M / metresPerDegreeLon(focusLat)
        val southEdge = focusLat - padLat
        val northEdge = focusLat + padLat
        val westEdge = focusLon - padLon
        val eastEdge = focusLon + padLon

        // What is loaded is the window, not everything ever loaded. A union
        // would have the model believing there is ground under it long after
        // that ground had been dropped.
        loadedMinLat = southEdge
        loadedMaxLat = northEdge
        loadedMinLon = westEdge
        loadedMaxLon = eastEdge

        // As much detail as the budget allows. The window is always the same
        // size, so this settles at the finest zoom and stays there — but it is
        // still worked out rather than assumed.
        var z = PREFERRED_ZOOM
        while (z > 9 && tileCount(southEdge, westEdge, northEdge, eastEdge, z) > MAX_TILES) {
            z--
        }

        // The heights first, for the whole window: they are a fraction of the
        // size of the pictures, and the altitude reference cannot be worked out
        // without them — which has to happen before a single tile is built,
        // since every vertex is baked relative to it.
        Elevation.prefetch(southEdge, westEdge, northEdge, eastEdge, z,
            { _, _ -> }, { _, _ -> })
        zoom = z
        // The datum, once, from the ground itself rather than from anything the
        // model has said about where it is.
        if (!datumFromGround) {
            val here = Elevation.elevationAt(originLat, originLon, z)
            if (here != null) {
                originAltitude = here
                datumFromGround = true
            }
        }
        resolveAltitudeReference(points)
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

        // Nearest the model first, in both passes: whatever it is flying over
        // is the tile worth having before any other.
        val centreX = Elevation.tileX(focusLon, z)
        val centreY = Elevation.tileY(focusLat, z)
        val window = ArrayList<LongArray>()
        for (tx in Math.min(x0, x1)..Math.max(x0, x1)) {
            for (ty in Math.min(y0, y1)..Math.max(y0, y1)) {
                val dx = (tx - centreX).toLong()
                val dy = (ty - centreY).toLong()
                window.add(longArrayOf(tx.toLong(), ty.toLong(), dx * dx + dy * dy))
            }
        }
        window.sortBy { it[2] }

        val keys = HashSet<Long>()
        for (t in window) keys.add(tileKey(t[0].toInt(), t[1].toInt()))

        // Shape first, picture second.
        //
        // Building a tile once the heights are in memory is local work and
        // quick; its picture is sixty-four images fetched and stitched into
        // sixteen megabytes, which is seconds. Showing the shape as soon as it
        // exists puts ground under the model almost at once, and the photograph
        // arrives over it tile by tile instead of everything appearing at the
        // end together.
        for (t in window) {
            val tx = t[0].toInt()
            val ty = t[1].toInt()
            val key = tileKey(tx, ty)
            if (built.containsKey(key)) continue
            val mesh = buildTile(z, tx, ty, false)
            if (mesh != null) {
                built[key] = mesh
                publish(keys)
                onTile()
            }
        }

        for (t in window) {
            val tx = t[0].toInt()
            val ty = t[1].toInt()
            val key = tileKey(tx, ty)
            val standing = built[key] ?: continue
            if (standing.texture != null) continue
            val picture = try {
                Imagery.mosaic(z, tx, ty, IMAGERY_DETAIL)
            } catch (e: Throwable) {
                null
            } ?: continue
            // The shape is already worked out and has not moved: only the
            // picture is new. Building the tile again to hang it on would mean
            // another thirty-seven thousand height samples for nothing.
            built[key] = TileMesh(standing.key, standing.vertices, standing.indices, picture)
            publish(keys)
            onTile()
        }

        publish(keys)
        onDone()
    }

    /**
     * Hand out what is built, and drop what the window has left behind.
     *
     * Anything outside it goes first; only if that is not enough does the
     * oldest go. The old rule was oldest first regardless, which on a window
     * that moves could throw away the tile the model was standing on.
     */
    private fun publish(window: Set<Long>) {
        while (built.size > MAX_TILES) {
            var drop = -1L
            for (key in built.keys) {
                if (!window.contains(key)) { drop = key; break }
            }
            if (drop == -1L) {
                val oldest = built.keys.iterator()
                if (!oldest.hasNext()) break
                drop = oldest.next()
            }
            built.remove(drop)
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
    /**
     * Work out what the reported heights mean, if that is not settled already.
     *
     * Returns true when the answer moved the world, so ground built in the old
     * frame has to be built again.
     *
     * This used to be asked only while loading ground, which was enough while
     * the loaded area grew with the flight and so reloaded often. The ground
     * now sits in a window a whole flight can happen inside, and a view opened
     * before the link came up asks once, with no flight to look at, and never
     * asks again — so heights above the launch were drawn as heights above the
     * sea and the model spent the flight inside the hill.
     */
    fun resolveAltitudeIfNeeded(points: List<TrackPoint>): Boolean {
        if (altitudeResolved && altitudeIsAboveLaunch) return false
        if (points.isEmpty()) return false
        val wasAbove = altitudeIsAboveLaunch
        val wasResolved = altitudeResolved
        resolveAltitudeReference(points)
        return altitudeIsAboveLaunch != wasAbove || altitudeResolved != wasResolved
    }

    private fun resolveAltitudeReference(points: List<TrackPoint>) {
        val found = referenceOf(points, zoom) ?: return
        val aboveLaunch = found.aboveLaunch

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
        // Only this. The ground stays where it is: what changes is where the
        // flight sits in it, which is the whole of what was in doubt.
        launchGroundElevation = found.lift
    }

    /** Added to every reported altitude when they are measured from the launch. */
    var launchGroundElevation = 0f
        private set

    private fun tileLon(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360.0 - 180.0

    private fun tileLat(y: Int, z: Int): Double {
        val n = Math.PI - 2.0 * Math.PI * y / (1 shl z)
        return Math.toDegrees(Math.atan(Math.sinh(n)))
    }

    private fun buildTile(z: Int, tx: Int, ty: Int, withImagery: Boolean): TileMesh? {
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

        val texture = if (!withImagery) {
            null
        } else {
            try {
                Imagery.mosaic(z, tx, ty, IMAGERY_DETAIL)
            } catch (e: Throwable) {
                null
            }
        }
        return TileMesh(tileKey(tx, ty), vertices, indices, texture)
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
        // Well inside the half kilometre the loader pads by. They were within
        // a metre of each other, so the first fix that moved at all asked for
        // more ground, and went on asking on every fix for the whole flight.
        val marginLat = 200.0 / METRES_PER_DEGREE_LAT
        val marginLon = 200.0 / metresPerDegreeLon(lat)
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
