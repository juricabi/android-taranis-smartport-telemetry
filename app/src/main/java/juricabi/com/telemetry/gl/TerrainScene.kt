package juricabi.com.telemetry.gl

import android.graphics.Bitmap
import juricabi.com.telemetry.utils.DebugLog
import juricabi.com.telemetry.utils.Elevation
import juricabi.com.telemetry.utils.Imagery

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

    /** Prevent this scene publishing an altitude answer for a later flight. */
    private var altitudeEpoch = AltitudeFrame.currentEpoch()

    /**
     * A new flight over a standing world. The per-flight questions are
     * re-opened — the altitude epoch re-stamped, the reference unresolved —
     * and everything about the WORLD is kept: the origin stays until a far
     * flight re-anchors it down its own road, and the ground datum stays
     * because the ground under an unmoved origin has not moved. The
     * reference only moves the flight within the world, never the tiles,
     * so keeping them is not a shortcut but the honest answer.
     */
    fun beginNewFlight() {
        altitudeEpoch = AltitudeFrame.currentEpoch()
        altitudeResolved = false
        altitudeIsAboveLaunch = false
        // The applied lift too, or the dead flight's answer kept lifting
        // the new one: an above-launch flight settling 500m of lift left
        // heightOf adding it to a sea-level successor until — or unless —
        // the fresh reference resolved. The rebuild reset this by nuking
        // the scene; the kept world must reset it by hand.
        launchGroundElevation = 0f
        // and ask the fresh question now, not on the old flight's timer
        altitudeAskAt = 0L
        altitudeWarmAt = 0L
        track = FloatArray(0)
        shadow = FloatArray(0)
    }

    /** [altitudeMsl] is NaN where the link reported no height at all. */
    class TrackPoint(val lat: Double, val lon: Double, val altitudeMsl: Float)

    /** One terrain tile: a grid of ground, with the aerial view of it. */
    class TileMesh(
        /** Which tile of the world this is, so it is uploaded once and no more. */
        val key: Long,
        /**
         * Null once the tile has dressed: by then the geometry has long
         * been on the card, and the CPU copies were the heap's single
         * largest tenant — two hundred megabytes of arrays retained for
         * one upload that had already happened, held twice over with a
         * world parked behind the map. [heights] stays for the ground
         * queries.
         */
        @Volatile var vertices: FloatArray?,   // 10 floats per vertex
        @Volatile var indices: ShortArray?,
        val texture: Bitmap?,
        /** The box the tile fits in, for the renderer to cull against. */
        val minX: Float = 0f, val maxX: Float = 0f,
        val minY: Float = 0f, val maxY: Float = 0f,
        val minZ: Float = 0f, val maxZ: Float = 0f,
        /** False when a rim was built blind; the pager rebuilds it later. */
        val rimComplete: Boolean = true,
        /** Indices per quadrant run; the skirts follow the four runs. */
        val quadCount: Int = 0,
        /** The tile's own drawn heights, grid x grid, origin-relative. */
        val heights: FloatArray = FloatArray(0)
    )

    companion object {

        private const val METRES_PER_DEGREE_LAT = 111320.0

        /**
         * Within this of the origin, the standing world serves; past it,
         * the world re-anchors to its subject. Far inside the roots' reach
         * and far outside any one flight's drift.
         */
        const val KEEP_WORLD_WITHIN_M = 50_000.0

        /** What reported heights turned out to be measured from. */
        class Reference(
            val aboveLaunch: Boolean,
            /** What to add to them to make them heights above the sea. */
            val lift: Float
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
            // The lowest twentieth, and never the very lowest reading — which
            // is what taking a twentieth of a short flight came to. A receiver
            // that has just started reports a few wild heights, and one of
            // those deciding the frame for the whole session is the thing this
            // is here to prevent.
            val lowestReported = reported[Math.min(known - 1, Math.max(1, known / 20))]

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
            return Reference(aboveLaunch, if (aboveLaunch) groundAtStart else 0f)
        }

        // ------------------------------------------------- tile arithmetic
        //
        // One key for a tile at any zoom, packed the way Elevation and
        // Imagery pack theirs. The pager walks the tree with these; nothing
        // else should invent its own.

        fun tileKey(z: Int, x: Int, y: Int): Long =
            (z.toLong() shl 58) or (x.toLong() shl 29) or (y.toLong() and 0x1FFFFFFF)

        fun zoomOf(key: Long): Int = (key ushr 58).toInt()

        fun tileXOf(key: Long): Int = ((key ushr 29) and 0x1FFFFFFF).toInt()

        fun tileYOf(key: Long): Int = (key and 0x1FFFFFFF).toInt()

        fun parentOf(key: Long): Long =
            tileKey(zoomOf(key) - 1, tileXOf(key) shr 1, tileYOf(key) shr 1)

        /** The grid a tile of this zoom is built on, one place for the rule. */
        fun gridFor(z: Int): Int =
            if (z >= 16) 65 else if (z >= 15) 129 else if (z >= 12) 97 else 65

        /**
         * Index arrays by grid size — three ever exist. Cells grouped by
         * quadrant, four equal runs then the skirts, so the renderer can
         * draw any subset of quarters; the quadrant order matches the
         * child index the pager masks by: bit cy*2+cx, row 0 north,
         * col 0 west. Shared and never written after birth.
         */
        private val sharedIndices = HashMap<Int, ShortArray>()

        private fun indicesFor(grid: Int): ShortArray =
            synchronized(sharedIndices) {
                sharedIndices.getOrPut(grid) { buildIndices(grid) }
            }

        private fun buildIndices(grid: Int): ShortArray {
            val rim = 4 * (grid - 1)
            val indices = ShortArray((grid - 1) * (grid - 1) * 6 + rim * 6)
            val quadHalf = (grid - 1) / 2
            var i = 0
            for (q in 0 until 4) {
                val rowBase = (q shr 1) * quadHalf
                val colBase = (q and 1) * quadHalf
                for (row in rowBase until rowBase + quadHalf) {
                    for (col in colBase until colBase + quadHalf) {
                        val a = (row * grid + col).toShort()
                        val b = (row * grid + col + 1).toShort()
                        val c = ((row + 1) * grid + col).toShort()
                        val d = ((row + 1) * grid + col + 1).toShort()
                        indices[i++] = a; indices[i++] = c; indices[i++] = b
                        indices[i++] = b; indices[i++] = c; indices[i++] = d
                    }
                }
            }
            val rimIndices = IntArray(rim)
            var r = 0
            for (col in 0 until grid - 1) rimIndices[r++] = col
            for (row in 0 until grid - 1) rimIndices[r++] = row * grid + (grid - 1)
            for (col in grid - 1 downTo 1) rimIndices[r++] = (grid - 1) * grid + col
            for (row in grid - 1 downTo 1) rimIndices[r++] = row * grid
            val ringBase = grid * grid
            for (k in 0 until rim) {
                val kn = (k + 1) % rim
                val a = rimIndices[k].toShort()
                val b = rimIndices[kn].toShort()
                val c = (ringBase + k).toShort()
                val d = (ringBase + kn).toShort()
                indices[i++] = a; indices[i++] = c; indices[i++] = b
                indices[i++] = b; indices[i++] = c; indices[i++] = d
            }
            return indices
        }

        /**
         * Geometric error by zoom — 8m at the source's finest, doubling up —
         * and the screen error a tile is allowed before the pager splits it.
         * Here rather than in the pager because the renderer's morphing has
         * to mirror the pager's splitting exactly: a vertex must finish
         * lying on its parent's line just before the pager could stand the
         * parent's level beside it, and the two agreeing about where that
         * happens is what leaves no crack.
         */
        val ERROR_M = FloatArray(20) {
            (8.0 * Math.pow(2.0, 15.0 - it)).toFloat()
        }

        fun errorOf(z: Int): Float = ERROR_M[z]

        const val SPLIT_ERROR_PX = 16f

        /**
         * The un-split threshold, here beside its sibling because the
         * renderer's morph is derived from BOTH: a merged parent can stand
         * beside a still-split neighbour from the merge distance onward, so
         * the morph must be complete exactly there — not at some rounder
         * number past it. The gap between the two was the standing walls:
         * an annulus where a coarse tile legally stood beside a fine one
         * only part-way onto its line, and the edge force bridged the rest
         * in a single cell of cliff.
         */
        const val MERGE_ERROR_PX = 10f

        fun tileLon(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360.0 - 180.0

        fun tileLat(y: Int, z: Int): Double {
            val n = Math.PI - 2.0 * Math.PI * y / (1 shl z)
            return Math.toDegrees(Math.atan(Math.sinh(n)))
        }
    }

    /**
     * The frame everything else is measured in, and the detail it was built at.
     *
     * Written by the thread that loads the ground, and read by three others:
     * the screen, the compass — which stands this phone's arrow on the terrain
     * from the sensor thread — and the drawing thread, which asks how high the
     * ground under the camera is on every frame. None of them takes a lock for
     * it and none should: they are four numbers, and the answer being one load
     * out of date is worth nothing. Being half-written is another matter, which
     * is what volatile is for.
     */
    @Volatile var originLat = 0.0
        private set
    @Volatile var originLon = 0.0
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
    @Volatile var originAltitude = 0f
    private var datumFromGround = false
        private set
    private var originFixed = false

    /** Bumped when a flight re-anchors a world that was fixed elsewhere. */
    @Volatile var originGeneration = 0
        private set

    /**
     * A flight far beyond the world's edge re-anchors the world to itself.
     *
     * The origin is claimed once and the roots ring it — but the first
     * claim can be the phone's own spot, standing in before any flight
     * exists. A replay from another country then played out in a void:
     * the ground it needed was never on the root list, and the camera
     * followed the model into nothing. Fifty kilometres is far inside the
     * roots' reach and far outside any one flight's drift; past it,
     * everything built against the old frame is rebuilt, down the same
     * road a late datum already takes.
     */
    private fun reanchorIfFar(nMinLat: Double, nMaxLat: Double,
                              nMinLon: Double, nMaxLon: Double, lowest: Float) {
        val midLat = (nMinLat + nMaxLat) / 2
        val midLon = (nMinLon + nMaxLon) / 2
        val step = Math.hypot(
            (midLat - originLat) * METRES_PER_DEGREE_LAT,
            (midLon - originLon) * metresPerDegreeLon(originLat))
        if (step <= KEEP_WORLD_WITHIN_M) return
        originLat = midLat
        originLon = midLon
        minLat = nMinLat; maxLat = nMaxLat
        minLon = nMinLon; maxLon = nMaxLon
        // the old ground's height means nothing here; the pager probes the
        // new ground and settles it again, rebuilding as it always has
        datumFromGround = false
        if (!lowest.isNaN()) originAltitude = lowest
        originGeneration++
    }

    /** Flight path as x, y, z triples in the local frame. */
    @Volatile var track = FloatArray(0)
        private set

    /** The same path dropped onto the ground, for a shadow and drop lines. */
    @Volatile var shadow = FloatArray(0)
        private set

    /**
     * Whether this scene has been left behind. The pager checks its own copy
     * for its threads; this one guards the scene-side work.
     */
    @Volatile private var abandoned = false

    fun abandon() {
        abandoned = true
    }

    /** Whether the datum has been taken from the ground itself yet. */
    val datumSettled: Boolean get() = datumFromGround

    /**
     * Take the datum from the ground under the origin, once and for good.
     *
     * The height everything is drawn relative to used to come from the
     * reported altitudes, so settling what those meant moved the whole world.
     * From the ground it is settled the moment one tile of heights exists —
     * and if that moment comes late (a view opened offline), the caller is
     * told the world moved so it can rebuild what was framed the old way.
     *
     * Returns true when the datum was just settled to a different height
     * than tiles were being built with.
     */
    fun settleDatumFromGround(): Boolean {
        if (datumFromGround) return false
        // A probe, not a fetch: the pager warms the tile and asks again each
        // pass. Blocking here held the first mesh behind a network round trip
        // that could just as well fly while the roots were being built.
        val here = Elevation.elevationAt(originLat, originLon, 15) ?: return false
        val moved = here != originAltitude
        originAltitude = here
        datumFromGround = true
        return moved
    }

    /** Half the width of the flight, for placing the camera. */
    var extent = 500f
        private set

    var minLat = 0.0; private set
    var maxLat = 0.0; private set
    var minLon = 0.0; private set
    var maxLon = 0.0; private set

    /** The place a point in the local frame stands for. */
    fun latAt(z: Float): Double = originLat - z / METRES_PER_DEGREE_LAT

    fun lonAt(x: Float): Double = originLon + x / metresPerDegreeLon(originLat)

    /** How far a degree of longitude reaches at this latitude, in metres. */
    fun metresAcross(lat: Double): Double = metresPerDegreeLon(lat)

    /** And how far a degree of latitude reaches, which is the same everywhere. */
    fun metresUp(): Double = METRES_PER_DEGREE_LAT

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
    fun setOrigin(lat: Double, lon: Double) {
        originLat = lat
        originLon = lon
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
        // The frame is normally already chosen — but a flight that arrives
        // AFTER the view stood up somewhere else entirely comes through
        // here, not through setTrack, and it must be able to re-anchor too.
        if (points.isNotEmpty()) {
            var loLat = points[0].lat; var hiLat = loLat
            var loLon = points[0].lon; var hiLon = loLon
            for (p in points) {
                if (p.lat < loLat) loLat = p.lat
                if (p.lat > hiLat) hiLat = p.lat
                if (p.lon < loLon) loLon = p.lon
                if (p.lon > hiLon) hiLon = p.lon
            }
            reanchorIfFar(loLat, hiLat, loLon, hiLon, Float.NaN)
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
        // How far the flight reaches, which decides how far the camera may be
        // pulled back. It was worked out once, when the view opened — and a
        // replay opens with an empty flight, so it stayed at its smallest for
        // the whole of one: a twenty-kilometre flight could not be zoomed out
        // past two and a half.
        measure()
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
        } else {
            reanchorIfFar(minLat, maxLat, minLon, maxLon, lowest)
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

        measure()
        return true
    }

    /** True when the altitudes turned out to be measured from the launch point. */
    var altitudeIsAboveLaunch = false
        private set

    private var altitudeResolved = false

    /**
     * Work out what the reported heights mean, if that is not settled already.
     *
     * It cannot be assumed. Betaflight sends height above sea level while the
     * model is disarmed and height above the arming point once it is armed, so
     * the same field means two different things within one flight, and drawing
     * the second against sea level terrain would bury the model a hundred
     * metres underground.
     *
     * [referenceOf] answers it, from the whole flight weighed against the
     * ground beneath it. An earlier version compared the first reading against
     * the terrain under it, which is not sound — a launch from a valley floor
     * and a launch from a ridge look the same to it — and that is why the
     * altitude profile and this view once disagreed about one flight.
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
        // settled once and for good — no need to fetch anything again for it
        if (altitudeResolved && altitudeIsAboveLaunch) return
        // Re-asked while the answer is "above the sea" — the one-way door
        // stays open — but not on every batch: the walk below reads the
        // whole flight, on the screen's thread, and a sea-level flight asked
        // it twice a second for its entire length.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < altitudeAskAt) return
        altitudeAskAt = now + if (altitudeResolved) 5_000 else 1_000
        // At the level the altitude profile samples, so the two views cannot
        // be metres apart about the same flight — and fetched here, because
        // the ground under the flight is not necessarily the ground the
        // camera has loaded.
        if (points.isNotEmpty()) {
            var s = points[0].lat; var n = points[0].lat
            var w = points[0].lon; var e = points[0].lon
            for (p in points) {
                if (p.lat < s) s = p.lat; if (p.lat > n) n = p.lat
                if (p.lon < w) w = p.lon; if (p.lon > e) e = p.lon
            }
            // Fired and not waited for. This is asked on the screen's thread
            // with every batch of new points, and a flight whose heights stay
            // above the sea asks on every one — waiting here for the network
            // was the screen freezing under fast scrubbing. referenceOf reads
            // only what is in memory and answers "not yet" until these land.
            if (now >= altitudeWarmAt) {
                altitudeWarmAt = now + 3_000
                Elevation.warmBox(s, w, n, e, Elevation.TILE_ZOOM)
            }
        }
        val proposed = referenceOf(points, Elevation.TILE_ZOOM) ?: return
        val found = AltitudeFrame.settle(proposed, altitudeEpoch) ?: return
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
        // Published, so the altitude profile draws the flight at the height the
        // ground view draws it. The two sample the terrain at different
        // resolutions — the profile covers a whole flight, this covers a window
        // around the model — so working it out twice was two answers, metres
        // apart on a steep launch and further where they disagreed about
        // whether the heights were above the launch at all.
        // Only this. The ground stays where it is: what changes is where the
        // flight sits in it, which is the whole of what was in doubt.
        launchGroundElevation = found.lift
    }

    /** When the altitude reference may next warm its box; it re-asks per batch. */
    private var altitudeWarmAt = 0L

    /** When it may next walk the flight at all; the walk is the dear part. */
    private var altitudeAskAt = 0L

    /** Added to every reported altitude when they are measured from the launch. */
    var launchGroundElevation = 0f
        private set

    internal fun buildTile(z: Int, tx: Int, ty: Int): TileMesh? {
        // The heights end at zoom 15; a finer tile samples that same data
        // over its quarter of the span, and earns its keep through the
        // sharper photograph its size allows.
        var ez = Math.min(z, 15)
        val westLon = tileLon(tx, z)
        val eastLon = tileLon(tx + 1, z)
        val northLat = tileLat(ty, z)
        val southLat = tileLat(ty + 1, z)

        // Fine at the leaf level, coarser above it: the sharpness lives where
        // the camera can get close, and everything else is background. The
        // middle levels stay dense enough that their borders agree with a
        // finer neighbour's — sparse ones missed karst ridges by tens of
        // metres, and every miss stood as a wall along the join.
        // the z16 span is a quarter of z15's, so 65 samples already read the
        // source twice per pixel — a denser grid there is triangles for nothing
        val grid = if (z >= 16) 65 else if (z >= 15) 129 else if (z >= 12) 97 else 65
        val heights = FloatArray(grid * grid)
        var any = false
        var rimMissing = false
        var lowest = Float.MAX_VALUE
        var highest = -Float.MAX_VALUE
        // Down the pyramid until a level has heights. Terrarium is damaged
        // in stripes: whole columns at z14 and z15 answer a 270-byte void —
        // every sample NO_DATA — while z13 under them is whole. Refusing to
        // build put a permanent hole at the start of a real flight, and the
        // hole wore a coarse ancestor whose rim stood as a cliff beside the
        // honest ground next to it. Coarser truth beats no ground.
        while (true) {
            val etx = tx shr (z - ez)
            val ety = ty shr (z - ez)
            // The heights were prefetched, but into a memory shared with the
            // altitude profile and with a load still finishing on a scene
            // already left behind, either of which may have pushed this tile
            // out again. Read it back — from disk, nearly always — rather
            // than sample thirty-seven thousand nulls and hand back no tile.
            // All four through the pool first, side by side; the serial
            // ensures below then find them arriving rather than each paying
            // its own round trip in a row.
            Elevation.warm(listOf(
                intArrayOf(ez, etx, ety), intArrayOf(ez, etx + 1, ety),
                intArrayOf(ez, etx, ety + 1), intArrayOf(ez, etx + 1, ety + 1)))
            // Each ensure can wait out a slow network. A view being torn down
            // must not serve the full sentence — its threads hold the renderer
            // and everything it queued until they exit.
            if (abandoned) return null
            Elevation.ensure(ez, etx, ety)
            if (abandoned) return null
            // The east column and south row of the grid lie exactly on the
            // tile boundary, and a boundary coordinate resolves in the next
            // tile over — so the very edge of every tile is sampled from its
            // neighbours. When a neighbour was not loaded, the border came
            // back NaN, fillGaps swapped it for the tile-wide average, and
            // that baked a wall of stretched texture along the join.
            Elevation.ensure(ez, etx + 1, ety)
            if (abandoned) return null
            Elevation.ensure(ez, etx, ety + 1)
            if (abandoned) return null
            Elevation.ensure(ez, etx + 1, ety + 1)
            if (abandoned) return null
            any = false
            rimMissing = false
            lowest = Float.MAX_VALUE
            highest = -Float.MAX_VALUE
            for (row in 0 until grid) {
                val lat = northLat + (southLat - northLat) * row / (grid - 1)
                for (col in 0 until grid) {
                    val lon = westLon + (eastLon - westLon) * col / (grid - 1)
                    val h = Elevation.elevationAt(lat, lon, ez)
                    if (h != null) {
                        val rel = h - originAltitude
                        heights[row * grid + col] = rel
                        if (rel < lowest) lowest = rel
                        if (rel > highest) highest = rel
                        any = true
                    } else {
                        heights[row * grid + col] = Float.NaN
                        // Missing counts on the rim only while the data may
                        // yet arrive. A rim that nulls over a HELD tile is
                        // the dataset's own void — the mend loop rebuilt
                        // such tiles every twenty seconds forever, throwing
                        // a dressed picture away each time, for data that
                        // was never coming.
                        if ((row == 0 || col == 0 || row == grid - 1 || col == grid - 1) &&
                            !Elevation.has(ez, Elevation.tileX(lon, ez),
                                Elevation.tileY(lat, ez))) {
                            rimMissing = true
                        }
                    }
                }
            }
            if (any || ez <= 8) break
            // Only a level that is HERE and all void steps down — that is the
            // dataset's hole and no retry will fill it. Fetches that simply
            // failed keep the old road: no tile now, try again in thirty
            // seconds, and end up sharp once the signal returns — stepping
            // down on a bad-signal field built permanently coarse ground
            // where waiting would have built it right.
            if (!Elevation.has(ez, etx, ety) || !Elevation.has(ez, etx + 1, ety) ||
                !Elevation.has(ez, etx, ety + 1) || !Elevation.has(ez, etx + 1, ety + 1)) {
                return null
            }
            DebugLog.note("TerrainScene", "no heights at z$ez for $z/$tx/$ty, stepping down")
            ez--
        }
        if (!any) return null
        // Every lattice and edge-agreement tile this build will wait on
        // below, in ONE wave through the pool: fetched block by block they
        // were four round-trip generations, and fresh ground built at a
        // crawl exactly where the eye was waiting for it.
        run {
            val wave = ArrayList<IntArray>(16)
            val bx = tx shr (z - ez)
            val by = ty shr (z - ez)
            wave.add(intArrayOf(ez, bx - 1, by))
            wave.add(intArrayOf(ez, bx - 1, by + 1))
            wave.add(intArrayOf(ez, bx, by - 1))
            wave.add(intArrayOf(ez, bx + 1, by - 1))
            if (z > 8) {
                val pez = Math.min(z - 1, 15)
                val ptx = tx shr (z - pez)
                val pty = ty shr (z - pez)
                wave.add(intArrayOf(pez, ptx, pty))
                wave.add(intArrayOf(pez, ptx + 1, pty))
                wave.add(intArrayOf(pez, ptx, pty + 1))
                wave.add(intArrayOf(pez, ptx + 1, pty + 1))
                wave.add(intArrayOf(pez, ptx - 1, pty))
                wave.add(intArrayOf(pez, ptx - 1, pty + 1))
                wave.add(intArrayOf(pez, ptx, pty - 1))
                wave.add(intArrayOf(pez, ptx + 1, pty - 1))
            }
            if (z - 2 >= 8) {
                val gez = Math.min(z - 2, 15)
                val gtx = tx shr (z - gez)
                val gty = ty shr (z - gez)
                wave.add(intArrayOf(gez, gtx, gty))
                wave.add(intArrayOf(gez, gtx + 1, gty))
                wave.add(intArrayOf(gez, gtx, gty + 1))
                wave.add(intArrayOf(gez, gtx + 1, gty + 1))
            }
            Elevation.warm(wave)
        }
        // A rim with no data means a neighbour tile has not arrived. Refusing
        // to build was a storm: one failed fetch put a thirty second hole in
        // the data, and every tile touching it failed on five second retries
        // while the camera flew on. Built anyway, the world stays covered and
        // the flag sends it back to be built right once the data lands.
        fillGaps(heights)

        // The shade taps below read one pitch past the WEST and NORTH edges
        // too, and those land in tiles the rim ensures above never touched —
        // so the brightness crease stayed on exactly those two borders.
        // Tried, not required: a miss just degrades that tap to its
        // one-sided fallback, the same as before.
        run {
            val bx = tx shr (z - ez)
            val by = ty shr (z - ez)
            if (abandoned) return null
            Elevation.ensure(ez, bx - 1, by)
            Elevation.ensure(ez, bx - 1, by + 1)
            Elevation.ensure(ez, bx, by - 1)
            Elevation.ensure(ez, bx + 1, by - 1)
            if (abandoned) return null
        }

        // the rim again as a skirt ring, so the box and the buffers hold both
        val rim = 4 * (grid - 1)
        val vertexCount = grid * grid + rim
        // Short indices: a finer grid must fail here, not as garbage triangles.
        check(vertexCount <= 32767) { "grid $grid does not fit short indices" }

        // The parent's own line under this tile — and the grandparent's
        // beneath that — one lattice of each, so every vertex carries where
        // it would stand one and two levels up. The renderer slides
        // vertices onto the parent line as their distance nears the band
        // where the pager would draw the parent instead; and because the
        // parent as DRAWN is itself part-way to the grandparent inside its
        // own band, the slide targets that moving line, not the parent's
        // raw data — a one-level chain heaved the far half of every square
        // by several pixels at each swap. Node positions are absolute, so
        // cousins agree about shared edges. The lattice tiles are ensured
        // like the rim's, or a cold miss silently baked a vertex that
        // never morphs for the tile's whole residency.
        val ezSelf = ez
        val half = if (z <= 8) 0 else (gridFor(z - 1) - 1) / 2
        val parentNodes = if (half == 0) null else FloatArray((half + 1) * (half + 1))
        var ezP = 0
        var parentSpacing = 0f
        var pPitchLat = 0.0
        var pPitchLon = 0.0
        if (parentNodes != null) {
            val pz = z - 1
            ezP = Math.min(pz, 15)
            val ptx = tx shr (z - ezP)
            val pty = ty shr (z - ezP)
            if (abandoned) return null
            Elevation.ensure(ezP, ptx, pty)
            // the parent-shade taps step one parent pitch west and north
            // of a west or north child's edge — same crease, one level up
            Elevation.ensure(ezP, ptx + 1, pty)
            Elevation.ensure(ezP, ptx, pty + 1)
            Elevation.ensure(ezP, ptx + 1, pty + 1)
            Elevation.ensure(ezP, ptx - 1, pty)
            Elevation.ensure(ezP, ptx - 1, pty + 1)
            Elevation.ensure(ezP, ptx, pty - 1)
            Elevation.ensure(ezP, ptx + 1, pty - 1)
            if (abandoned) return null
            val gp = gridFor(pz)
            val pNorth = tileLat(ty shr 1, pz)
            val pSouth = tileLat((ty shr 1) + 1, pz)
            val pWest = tileLon(tx shr 1, pz)
            val pEast = tileLon((tx shr 1) + 1, pz)
            val hx = (tx and 1) * half
            val hy = (ty and 1) * half
            pPitchLat = (pSouth - pNorth) / (gp - 1)
            pPitchLon = (pEast - pWest) / (gp - 1)
            parentSpacing = Math.abs(east(westLon + pPitchLon) - east(westLon))
            for (j in 0..half) {
                val nodeLat = pNorth + (pSouth - pNorth) * (hy + j) / (gp - 1)
                for (i in 0..half) {
                    val nodeLon = pWest + (pEast - pWest) * (hx + i) / (gp - 1)
                    val ph = Elevation.elevationAt(nodeLat, nodeLon, ezP)
                    parentNodes[j * (half + 1) + i] =
                        if (ph != null) ph - originAltitude else Float.NaN
                }
            }
        }
        val quarter = if (z - 2 < 8) 0 else (gridFor(z - 2) - 1) / 4
        val grandNodes = if (quarter == 0) null else FloatArray((quarter + 1) * (quarter + 1))
        if (grandNodes != null) {
            val gz = z - 2
            val ezG = Math.min(gz, 15)
            val gtx = tx shr (z - ezG)
            val gty = ty shr (z - ezG)
            if (abandoned) return null
            Elevation.ensure(ezG, gtx, gty)
            Elevation.ensure(ezG, gtx + 1, gty)
            Elevation.ensure(ezG, gtx, gty + 1)
            Elevation.ensure(ezG, gtx + 1, gty + 1)
            if (abandoned) return null
            val gq = gridFor(gz)
            val gNorth = tileLat(ty shr 2, gz)
            val gSouth = tileLat((ty shr 2) + 1, gz)
            val gWest = tileLon(tx shr 2, gz)
            val gEast = tileLon((tx shr 2) + 1, gz)
            val ghx = (tx and 3) * quarter
            val ghy = (ty and 3) * quarter
            for (j in 0..quarter) {
                val nodeLat = gNorth + (gSouth - gNorth) * (ghy + j) / (gq - 1)
                for (i in 0..quarter) {
                    val nodeLon = gWest + (gEast - gWest) * (ghx + i) / (gq - 1)
                    val gh = Elevation.elevationAt(nodeLat, nodeLon, ezG)
                    grandNodes[j * (quarter + 1) + i] =
                        if (gh != null) gh - originAltitude else Float.NaN
                }
            }
        }

        // Shade is baked. The light has always been a compile-time constant
        // and the shade a scalar of it — carried as data instead of normals
        // it can MORPH with the heights (fine relief no longer rides on
        // geometry that has flattened to the coarse line) and it can agree
        // across edges: the rim's missing side is read one pitch beyond the
        // edge from the very tiles the rim ensures fetched, so both tiles
        // of a shared edge compute the identical shade — the permanent
        // brightness crease along every border goes with the one-sided
        // difference that made it.
        val vertices = FloatArray(vertexCount * 10)
        val span = Math.abs(east(eastLon) - east(westLon))
        val spacing = span / (grid - 1)
        val pitchLat = (southLat - northLat) / (grid - 1)
        val pitchLon = (eastLon - westLon) / (grid - 1)
        var v = 0
        for (row in 0 until grid) {
            val lat = northLat + (southLat - northLat) * row / (grid - 1)
            for (col in 0 until grid) {
                val lon = westLon + (eastLon - westLon) * col / (grid - 1)
                val h = heights[row * grid + col]
                vertices[v++] = east(lon)
                vertices[v++] = h
                vertices[v++] = -north(lat)
                vertices[v++] = col.toFloat() / (grid - 1)
                vertices[v++] = row.toFloat() / (grid - 1)
                val hl = if (col > 0) heights[row * grid + col - 1]
                    else beyondRim(lat, lon - pitchLon, ezSelf) ?: h
                val hr = if (col < grid - 1) heights[row * grid + col + 1]
                    else beyondRim(lat, lon + pitchLon, ezSelf) ?: h
                val hu = if (row > 0) heights[(row - 1) * grid + col]
                    else beyondRim(lat - pitchLat, lon, ezSelf) ?: h
                val hd = if (row < grid - 1) heights[(row + 1) * grid + col]
                    else beyondRim(lat + pitchLat, lon, ezSelf) ?: h
                val shade = shadeOf(hl - hr, hu - hd, spacing)
                vertices[v++] = shade
                // the parent's shade at this spot, from the parent's own
                // data at the parent's own pitch — its relief, not ours
                var parentShade = shade
                if (parentNodes != null) {
                    val pl = beyondRim(lat, lon - pPitchLon, ezP)
                    val pr = beyondRim(lat, lon + pPitchLon, ezP)
                    val pu = beyondRim(lat - pPitchLat, lon, ezP)
                    val pd = beyondRim(lat + pPitchLat, lon, ezP)
                    if (pl != null && pr != null && pu != null && pd != null) {
                        parentShade = shadeOf(pl - pr, pu - pd, parentSpacing)
                    }
                }
                vertices[v++] = parentShade
                // where this point stands on the parent's line — itself,
                // where there is no parent or the parent's data is a void
                var parentY = h
                if (parentNodes != null) {
                    val lerped = latticeAt(parentNodes, half,
                        col.toFloat() / (grid - 1), row.toFloat() / (grid - 1))
                    if (lerped != null) parentY = lerped
                }
                vertices[v++] = parentY
                var grandY = parentY
                if (grandNodes != null) {
                    val lerped = latticeAt(grandNodes, quarter,
                        col.toFloat() / (grid - 1), row.toFloat() / (grid - 1))
                    if (lerped != null) grandY = lerped
                }
                vertices[v++] = grandY
                // Which of the tile's edges this vertex lies on — west 1,
                // north 2, east 3, south 4, a corner both — packed as one
                // small number. The renderer forces the morph to completion
                // on an edge that faces a coarser drawn neighbour, and this
                // is how the shader knows which vertices that means.
                var ea = 0
                var eb = 0
                if (col == 0) ea = 1
                if (row == 0) { if (ea == 0) ea = 2 else eb = 2 }
                if (col == grid - 1) { if (ea == 0) ea = 3 else eb = 3 }
                if (row == grid - 1) { if (ea == 0) ea = 4 else eb = 4 }
                vertices[v++] = (ea * 5 + eb).toFloat()
            }
        }

        // The skirt: the rim dropped just far enough, wearing the rim's own
        // texel. Where a neighbour at another detail level meets this tile
        // a hair apart, the eye sees a sliver of the tile's own picture
        // instead of a crack of sky. Deep enough for the height a coarser
        // neighbour can be wrong by, and no deeper — a twentieth of a
        // coarse tile was a seven hundred metre curtain on the horizon.
        val skirtDepth = Math.min(span * 0.02f, 120f)
        val rimIndices = IntArray(rim)
        var r = 0
        for (col in 0 until grid - 1) rimIndices[r++] = col
        for (row in 0 until grid - 1) rimIndices[r++] = row * grid + (grid - 1)
        for (col in grid - 1 downTo 1) rimIndices[r++] = (grid - 1) * grid + col
        for (row in grid - 1 downTo 1) rimIndices[r++] = row * grid
        for (k in 0 until rim) {
            val src = rimIndices[k] * 10
            vertices[v++] = vertices[src]
            vertices[v++] = vertices[src + 1] - skirtDepth
            vertices[v++] = vertices[src + 2]
            vertices[v++] = vertices[src + 3]
            vertices[v++] = vertices[src + 4]
            // the shade floor, so the skirt reads as silhouette, not picture
            vertices[v++] = 0.70f
            vertices[v++] = 0.70f
            // the skirt hangs the same depth below wherever its rim morphs
            vertices[v++] = vertices[src + 7] - skirtDepth
            vertices[v++] = vertices[src + 8] - skirtDepth
            // and follows its rim vertex through an edge forced to the
            // coarser line, or the forcing would open the very crack it closes
            vertices[v++] = vertices[src + 9]
        }

        // One shared array per grid size: the triangles are a pure function
        // of the grid, and a fresh copy per tile was a fifth of the mesh
        // bytes plus steady allocation at thirty builds a second.
        val indices = indicesFor(grid)
        val quadHalf = (grid - 1) / 2

        // No picture: a tile is built for its shape, and its photograph is
        // hung on it afterwards by the pass that fetches them. The box is
        // padded by the GRANDPARENT's error: morphed vertices ride lines up
        // to two levels coarser than the tile's own extremes, and a box
        // sized to the raw heights let a fully-morphed tile poke out of it
        // and flicker at the frustum's edge.
        val pad = errorOf(Math.max(0, z - 2))
        return TileMesh(tileKey(z, tx, ty), vertices, indices, null,
            east(westLon), east(eastLon),
            lowest - skirtDepth - pad, highest + pad,
            -north(northLat), -north(southLat),
            rimComplete = !rimMissing,
            quadCount = quadHalf * quadHalf * 6,
            heights = heights)
    }

    /** A height just past this tile's edge, origin-relative, or null. */
    private fun beyondRim(lat: Double, lon: Double, ez: Int): Float? {
        val h = Elevation.elevationAt(lat, lon, ez) ?: return null
        return h - originAltitude
    }

    /** The fixed light, normalized once; shade is 0.70 + 0.30·|n·L|. */
    private fun shadeOf(dx: Float, dz: Float, spacing: Float): Float {
        val ny = if (spacing > 0.01f) 2f * spacing else 1f
        val len = Math.sqrt((dx * dx + ny * ny + dz * dz).toDouble()).toFloat()
        val lit = Math.abs(dx / len * -0.48795f + ny / len * 0.78072f +
            dz / len * -0.39036f)
        return 0.70f + 0.30f * lit
    }

    /** Bilinear over an ancestor lattice at this tile-fraction, or null on a void. */
    private fun latticeAt(nodes: FloatArray, cells: Int, fx: Float, fy: Float): Float? {
        val qx = fx * cells
        val qy = fy * cells
        val i0 = Math.min(qx.toInt(), cells - 1)
        val j0 = Math.min(qy.toInt(), cells - 1)
        val bx = qx - i0
        val by = qy - j0
        val p00 = nodes[j0 * (cells + 1) + i0]
        val p10 = nodes[j0 * (cells + 1) + i0 + 1]
        val p01 = nodes[(j0 + 1) * (cells + 1) + i0]
        val p11 = nodes[(j0 + 1) * (cells + 1) + i0 + 1]
        if (p00.isNaN() || p10.isNaN() || p01.isNaN() || p11.isNaN()) return null
        val top = p00 + (p10 - p00) * bx
        val bottom = p01 + (p11 - p01) * bx
        return top + (bottom - top) * by
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

    /**
     * The finest ground anyone has in memory here, wherever "here" is.
     *
     * Always from the finest level down. A remembered "level that answered
     * last time" seemed a kindness and was a trap: once a coarse level had
     * answered it went on answering after finer heights arrived, and the
     * arrow stood at the coarse ground's height while the fine mesh rose
     * around it — swallowed, with its ring underground. Sixteen hash misses
     * are nothing; a stale height is an arrow inside a hill.
     */
    fun groundAt(lat: Double, lon: Double): Float? {
        for (level in 15 downTo 0) {
            val h = Elevation.elevationAt(lat, lon, level)
            if (h != null) return h
        }
        return null
    }

    /** The half-width of everything flown, from the bounds gathered so far. */
    private fun measure() {
        val halfWidth = Math.abs(east(maxLon) - east(minLon)) / 2
        val halfHeight = Math.abs(north(maxLat) - north(minLat)) / 2
        extent = Math.max(200f, Math.max(halfWidth, halfHeight))
    }

    fun buildShadow(points: List<TrackPoint>) {
        val out = FloatArray(points.size * 3)
        var i = 0
        for (p in points) {
            // The reference level first, one lookup: it is warmed for the
            // whole flight, and a shadow does not need leaf sharpness. The
            // sixteen-level walk per point, three thousand points, twice a
            // second, was a measurable slice of every replay's screen thread.
            val ground = Elevation.elevationAt(p.lat, p.lon, Elevation.TILE_ZOOM)
                ?: groundAt(p.lat, p.lon)
            out[i++] = east(p.lon)
            out[i++] = if (ground != null) ground - originAltitude else 0f
            out[i++] = -north(p.lat)
        }
        shadow = out
    }
}
