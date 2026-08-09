package juricabi.com.telemetry.gl

import android.util.Log
import juricabi.com.telemetry.utils.DebugLog
import juricabi.com.telemetry.utils.Elevation
import juricabi.com.telemetry.utils.Imagery

/**
 * Which tiles of the world exist, at what detail, and which of them draw.
 *
 * The ground used to be a fixed window of one zoom that followed the model;
 * it could be sharp or it could cover ground, never both, and a fast
 * traverse flew off its edge. This is the other design — the one Google
 * Earth and Cesium use: a quadtree of web-mercator tiles, split wherever
 * their geometric error would show on screen, so the camera decides where
 * the detail is. Coarse tiles are the whole region for pennies; leaves are
 * metre-sharp under the model.
 *
 * Threading, in the house idiom: two daemon workers of its own. One builds
 * meshes and runs the selection; one fetches pictures, which are the slow
 * part and must never hold up shape. The renderer's offer/keepOnly/
 * setDrawSet are synchronized, so publishing from these threads is safe.
 * The scene stays what it was — the frame, the datum, the track — and the
 * renderer draws what it is told; only this class knows the tree.
 */
class TerrainPager(
    private val scene: TerrainScene,
    private val renderer: TerrainRenderer,
    totalRamBytes: Long
) {
    /**
     * What the pictures may hold on the card, counted as RGB_565 plus a
     * third for mipmaps. A sixteenth of the phone's RAM, not a constant:
     * every constant tried was under some real terrain — 280MB, then
     * 420MB, was under the Alps, whose cut alone wears 489MB — and a
     * budget under the working set turns the evictor into a treadmill,
     * each finished tile rebuilt seconds after it is thrown out, CPU and
     * rippling both. Clamped: a floor a small phone can still churn
     * against honestly, a ceiling before the driver's double accounting
     * starts to matter.
     */
    private val textureBudgetBytes =
        (totalRamBytes / 16).coerceIn(192L * 1024 * 1024, 768L * 1024 * 1024)

    /** Mesh backstop, scaled with the pictures: about a tile per megabyte. */
    private val residentMost = (textureBudgetBytes / (1024 * 1024)).toInt()

    /**
     * How sharp any picture may be on this phone. The mosaics are the
     * app's biggest allocations — a 2048-pixel stitch is eight megabytes
     * on the heap while it is assembled, and several are in flight at
     * once. A small phone keeps the same world at a step or two softer:
     * the heap traffic falls fourfold per step, and the texture bytes
     * with it. A phone with room keeps every step it can read.
     */
    private val sharpCeiling = when {
        totalRamBytes < 3L * 1024 * 1024 * 1024 -> 1
        totalRamBytes < 6L * 1024 * 1024 * 1024 -> 2
        else -> 3
    }

    companion object {
        private const val TAG = "TerrainPager"

        /**
         * One level past the elevation source's 15: a z16 tile carries the
         * same heights over a quarter of the span, and its texture budget
         * reaches z19 imagery — 0.2 m per pixel, which is what the 2D map
         * shows at full zoom. Anything less and standing next to the model
         * read sharper in 2D than 3D.
         */
        private const val LEAF_ZOOM = 16

        /**
         * Coarse enough that the whole region is a handful of builds, fine
         * enough that the first split is not absurd: a z8 tile is ~112 km
         * across here.
         */
        private const val ROOT_ZOOM = 8

        /**
         * Half of the region the roots cover, metres from the origin. Also
         * the world's edge for every control: past it there is only the
         * void, and [Terrain3DView] refuses to take the camera there. Was
         * 200 km; nobody looked at the outer ring and the whole app felt
         * the cost of carrying it.
         */
        const val ROOT_REACH_M = 150_000.0

        /**
         * Split when a tile's geometric error would cover more than this many
         * screen pixels — and un-split only below the smaller one, so a tile
         * at the threshold does not flicker between levels as the camera
         * breathes.
         *
         * Sixteen, measured, not guessed: at seven the cut was seven hundred
         * tiles on this screen — minutes of downloads, half a gigabyte of
         * textures and an upload queue that never drained. The sharpness of
         * the view lives in the photographs, which size themselves to the
         * screen regardless; this only sets how densely the ground is
         * triangulated, and sixteen pixels of height error is invisible
         * under a photograph.
         */
        private const val SPLIT_PX = 16f
        private const val MERGE_PX = 10f

        /**
         * Tiles behind the camera split this much more reluctantly. They
         * stay covered — the roots see to that — but detail chases the view,
         * not the whole compass; a turn shows soft ground that sharpens.
         */
        private const val BEHIND_TIMES = 2.5f

        /** Geometric error by zoom: 8m at the source's finest, doubling up. */
        private val ERROR_M = FloatArray(20) {
            (8.0 * Math.pow(2.0, 15.0 - it)).toFloat()
        }

        /** A mosaic that failed is asked again this much later, not sooner. */
        private const val TEXTURE_RETRY_MS = 15_000L

        private const val STATS_EVERY_MS = 5_000L

        private fun errorOf(z: Int): Float = ERROR_M[z]
    }

    /** Fired once, when the first tile stands with its picture on. */
    @Volatile var onFirstDressed: (() -> Unit)? = null

    /** The datum arrived late and moved the world; everything was rebuilt. */
    @Volatile var onWorldMoved: (() -> Unit)? = null

    /** "" when there is ground; a word for the screen when there is none. */
    @Volatile var onStatus: ((String) -> Unit)? = null

    /** How much of the wanted ground is dressed, after every pass. */
    @Volatile var onProgress: ((done: Int, total: Int) -> Unit)? = null

    private var dressedInCut = 0

    private class Paged(
        @Volatile var mesh: TerrainScene.TileMesh,
        @Volatile var dressed: Boolean,
        @Volatile var textureBytes: Long,
        /** How sharp the picture it wears is, so approaching can sharpen it. */
        @Volatile var extraDressed: Int = -1,
        /** False when the rim was built blind; rebuilt once data exists. */
        @Volatile var rimComplete: Boolean = true,
        /** What it wore before a mend rebuilt it, so re-dressing starts there. */
        val wornBefore: Int = 0
    )

    private val lock = Object()
    @Volatile private var abandoned = false

    /**
     * A paused screen must not keep loading. The GL thread is the only thing
     * that drains what these workers produce, and it stops with the screen —
     * backgrounding mid-load let the pager fill an undrainable queue with
     * hundreds of megabytes of pictures nobody could show.
     */
    @Volatile private var paused = false

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
        poke()
    }
    private var firstDressedFired = false

    /** Everything built, by key, in access order so eviction is LRU. */
    private val resident = LinkedHashMap<Long, Paged>(64, 0.75f, true)

    /** The cover as last published to the renderer, for [drawnGroundAt]. */
    @Volatile private var publishedCover: Set<Long>? = null

    /**
     * The height of the ground as currently drawn over a spot, or null while
     * nothing drawn covers it. Read from the drawn tile's own vertex grid,
     * because no level of the data agrees with the surface on screen: a
     * coarse tile's triangles bridge whole valleys between its grid points,
     * and an arrow stood on any data height sat inside the drawn
     * mountainside. Absolute metres, like Elevation answers.
     */
    fun drawnGroundAt(lat: Double, lon: Double): Float? {
        val drawn = publishedCover ?: return null
        for (z in LEAF_ZOOM downTo ROOT_ZOOM) {
            val tx = Elevation.tileX(lon, z)
            val ty = Elevation.tileY(lat, z)
            val key = TerrainScene.tileKey(z, tx, ty)
            if (!drawn.contains(key)) continue
            // one drawn tile covers any spot; missing here means a world
            // mid-rebuild, and the caller falls back to the data
            val p = synchronized(lock) { resident[key] } ?: return null
            val mesh = p.mesh
            val grid = TerrainScene.gridFor(z)
            val west = TerrainScene.tileLon(tx, z)
            val east = TerrainScene.tileLon(tx + 1, z)
            val north = TerrainScene.tileLat(ty, z)
            val south = TerrainScene.tileLat(ty + 1, z)
            val most = (grid - 1).toDouble()
            val fu = ((lon - west) / (east - west) * most).coerceIn(0.0, most)
            val fv = ((lat - north) / (south - north) * most).coerceIn(0.0, most)
            val c0 = Math.min(fu.toInt(), grid - 2)
            val r0 = Math.min(fv.toInt(), grid - 2)
            val du = (fu - c0).toFloat()
            val dv = (fv - r0).toFloat()
            fun lerp(idx: Int): Float {
                fun at(r: Int, c: Int) = mesh.vertices[(r * grid + c) * 10 + idx]
                return (at(r0, c0) * (1 - du) + at(r0, c0 + 1) * du) * (1 - dv) +
                    (at(r0 + 1, c0) * (1 - du) + at(r0 + 1, c0 + 1) * du) * dv
            }
            var worldY = lerp(1)
            // Still wearing an ancestor's picture: the renderer forces such
            // a tile onto the coarser line until its own picture lands, so
            // for that second the drawn surface is the highest of the lines
            // this vertex is strung between — stood on its own line alone,
            // the arrow dropped into the ground and climbed back out.
            if (!p.dressed) {
                worldY = Math.max(worldY, Math.max(lerp(7), lerp(8)))
            }
            if (worldY.isNaN()) return null
            return worldY + scene.originAltitude
        }
        return null
    }

    /** Height spans learnt from built tiles, inherited by their children. */
    private val heightRange = HashMap<Long, FloatArray>()

    /** The keys the previous pass split, for the hysteresis. */
    private var splitLastPass = HashSet<Long>()

    /** What the last pass decided; pager threads only. */
    private var cut = ArrayList<Long>()
    private var cover = HashSet<Long>()
    private var coverAncestors = HashSet<Long>()

    /** Pictures wanted: (key, extra), best first. Swapped whole each pass. */
    @Volatile private var wantedTextures: List<LongArray> = emptyList()
    private val textureRetryAt = HashMap<Long, Long>()

    /** A build that failed steps aside briefly, or it would block the queue. */
    private val buildRetryAt = HashMap<Long, Long>()

    /** The sharpness a tile sent back for a mend wore, until it rebuilds. */
    private val mendWorn = HashMap<Long, Int>()

    private var statsAt = 0L
    private var builds = 0
    private var buildFails = 0
    private var stripped = 0
    private var evictions = 0

    /** When the first mesh stood, and how often its pictures have refused. */
    @Volatile private var firstBuildAt = 0L
    @Volatile private var dressFails = 0

    private var meshWorker: Thread? = null
    private val texWorkers = ArrayList<Thread>()
    private var startedAt = 0L

    /** A picture per thread in flight; Imagery's own pool spreads each one. */
    private val texInFlight = HashSet<Long>()

    fun start() {
        if (meshWorker != null || abandoned) return
        startedAt = android.os.SystemClock.elapsedRealtime()
        meshWorker = Thread({ meshLoop() }, "terrain-mesh").apply {
            isDaemon = true; start()
        }
        // Four, because one was the whole problem: a mosaic is seconds, and
        // one at a time meant the view sharpened tile by slow tile.
        for (i in 0 until 4) {
            texWorkers.add(Thread({ texLoop() }, "terrain-tex-$i").apply {
                isDaemon = true; start()
            })
        }
    }

    /** Anything worth reacting to — a gesture, a fix — may poke the loop. */
    fun poke() {
        synchronized(lock) { lock.notifyAll() }
    }

    /**
     * The GL context is gone and took every upload with it. Nothing is
     * retained against this moment — retaining it was an OutOfMemory — so
     * the world is simply built again, from caches that are warm: heights
     * and pictures come off the disk, and the view is back in a couple of
     * seconds without a byte held in reserve.
     */
    fun redress() {
        DebugLog.note(TAG, "world rebuilt: pictures lost with the GL context")
        synchronized(lock) {
            resident.clear()
            lock.notifyAll()
        }
    }

    fun abandon() {
        abandoned = true
        synchronized(lock) {
            resident.clear()
            lock.notifyAll()
        }
    }

    // ------------------------------------------------------------- selection

    private var rootsPrefetched = false

    private fun meshLoop() {
        android.os.Process.setThreadPriority(
            android.os.Process.THREAD_PRIORITY_BACKGROUND)
        DebugLog.note(TAG, "mesh thread live after " +
            "${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
        while (!abandoned) {
            try {
                settleDatum()
                if (!rootsPrefetched) {
                    rootsPrefetched = true
                    // Only the roots nearest the camera, and only a handful:
                    // all twenty-five at once clogged the fetch pool for the
                    // first second, and the first build sat behind wallpaper
                    // for the far side of the region. The rest warm through
                    // the ordinary per-pass path as they come up.
                    val ex = renderer.eyeX
                    val ey = renderer.eyeY
                    val ez = renderer.eyeZ
                    val warmRoots = roots()
                        .sortedBy { distanceTo(it, ex, ey, ez) }
                        .take(6)
                        .map {
                            intArrayOf(ROOT_ZOOM,
                                TerrainScene.tileXOf(it), TerrainScene.tileYOf(it))
                        }
                    Elevation.warm(warmRoots)
                }
                if (paused) {
                    synchronized(lock) {
                        if (!abandoned && paused) lock.wait(500)
                    }
                    continue
                }
                pass()
                // A few builds per look around, not one: rebuilding the whole
                // pyramid after a zoom-out went one selection pass per tile,
                // and the selection was most of the wait.
                var built = false
                var burst = 0
                while (burst < 4 && buildNext()) {
                    built = true
                    burst++
                }
                stats()
                // The ground whatever came of it: meshes standing but every
                // picture refused — imagery down, blocked, uncached — must
                // still count as ready, or the flight and any held replay
                // wait forever over a world that is there and grey. The old
                // window released on completion for exactly this case.
                if (!firstDressedFired && firstBuildAt != 0L && dressFails >= 4 &&
                    android.os.SystemClock.elapsedRealtime() - firstBuildAt > 12_000) {
                    synchronized(lock) {
                        if (!firstDressedFired) {
                            firstDressedFired = true
                            onFirstDressed?.invoke()
                        }
                    }
                }
                // short, because a gesture poke should find this loop ready:
                // half a second of doze here stacked with the workers' own
                // and turned every camera move into a visible wait
                if (!built) synchronized(lock) {
                    if (!abandoned) lock.wait(200)
                }
            } catch (e: Throwable) {
                // A pass that dies must not kill the ground for the rest of
                // the session; the next one starts from clean state.
                DebugLog.note(TAG, "pass failed: ${e.message}")
                synchronized(lock) { if (!abandoned) lock.wait(1000) }
            }
        }
    }

    private var datumWarmFired = false

    private fun settleDatum() {
        if (scene.datumSettled) return
        if (!datumWarmFired) {
            datumWarmFired = true
            Elevation.warm(listOf(intArrayOf(15,
                Elevation.tileX(scene.originLon, 15),
                Elevation.tileY(scene.originLat, 15))))
        }
        val moved = scene.settleDatumFromGround()
        if (moved) {
            val hadTiles = synchronized(lock) {
                val had = resident.isNotEmpty()
                if (had) {
                    resident.clear()
                    heightRange.clear()
                }
                had
            }
            if (hadTiles) {
                DebugLog.note(TAG, "world rebuilt: datum settled and moved")
                renderer.keepOnly(emptySet())
                onWorldMoved?.invoke()
            }
        }
    }

    private var frameGeneration = 0

    /** Selection, cover, and the publish that hands both to the renderer. */
    private fun pass() {
        // A flight re-anchored the world: everything resident belongs to a
        // frame that no longer exists. The same road as a moved datum —
        // clear the books, let the renderer drop the tiles, tell the view.
        val gen = scene.originGeneration
        if (gen != frameGeneration) {
            frameGeneration = gen
            datumWarmFired = false
            val had = synchronized(lock) {
                val h = resident.isNotEmpty()
                if (h) {
                    resident.clear()
                    heightRange.clear()
                }
                h
            }
            if (had) {
                DebugLog.note(TAG, "world rebuilt: re-anchored to the flight")
                renderer.keepOnly(emptySet())
                onWorldMoved?.invoke()
            }
        }
        val eyeX = renderer.eyeX
        val eyeY = renderer.eyeY
        val eyeZ = renderer.eyeZ
        val screenH = renderer.viewHeightPx
        // pixels per radian at the screen's middle, from the vertical FOV
        val k = screenH / (2.0 * Math.tan(Math.toRadians(TerrainRenderer.FOV_Y / 2.0)))

        val newCut = ArrayList<Long>()
        val newSplit = HashSet<Long>()
        // keys only, snapshotted under the lock: visit must not read the
        // map while another thread clears it
        val built = synchronized(lock) { HashSet(resident.keys) }
        for (root in roots()) {
            visit(root, eyeX, eyeY, eyeZ, k, built, newCut, newSplit)
        }
        // nearest worst first: the order builds and pictures are wanted in
        newCut.sortByDescending { rho(it, eyeX, eyeY, eyeZ, k) }
        splitLastPass = newSplit
        cut = newCut

        val coverList = ArrayList<Long>(256)
        val maskKeys = ArrayList<Long>()
        val maskBits = ArrayList<Int>()
        val newAncestors = HashSet<Long>()
        val cutSet = HashSet(newCut)
        val newCover: HashSet<Long>
        val newMasks = HashMap<Long, Int>()
        synchronized(lock) {
            for (root in roots()) {
                cover(root, cutSet, coverList, maskKeys, maskBits)
            }
            newCover = HashSet(coverList)
            for (i in maskKeys.indices) newMasks[maskKeys[i]] = maskBits[i]
            for (key in cutSet) {
                var at = key
                while (TerrainScene.zoomOf(at) > ROOT_ZOOM) {
                    at = TerrainScene.parentOf(at)
                    newAncestors.add(at)
                }
            }
            cover = newCover
            coverAncestors = newAncestors
            evictLocked(cutSet)
            renderer.keepOnly(HashSet(resident.keys))
            var done = 0
            for (key in cutSet) if (resident[key]?.dressed == true) done++
            dressedInCut = done
        }
        // the k this cut was selected under rides with it, so the morph
        // bands belong to the cut they guard, not to this frame's surface
        publishedCover = newCover
        renderer.setDrawSet(newCover, newMasks, k)
        onProgress?.invoke(dressedInCut, cutSet.size)
        wantTextures(cutSet, eyeX, eyeY, eyeZ, k)

        // The heights for the next handful of builds, fired into the pool —
        // not waited for — so each build finds its tiles already in cache
        // instead of paying ring after ring of round trips itself.
        val warm = ArrayList<IntArray>()
        val seen = HashSet<Long>()
        synchronized(lock) {
            outer@ for (key in cut) {
                var at = key
                while (true) {
                    if (!resident.containsKey(at) && seen.add(at)) {
                        val z = TerrainScene.zoomOf(at)
                        // at the level the build will actually sample: the
                        // heights end at 15, and warming a z16 leaf at its
                        // own zoom was a guaranteed 404 ahead of every fetch
                        // the build truly needed
                        val ez = Math.min(z, 15)
                        val x = TerrainScene.tileXOf(at) shr (z - ez)
                        val y = TerrainScene.tileYOf(at) shr (z - ez)
                        warm.add(intArrayOf(ez, x, y))
                        warm.add(intArrayOf(ez, x + 1, y))
                        warm.add(intArrayOf(ez, x, y + 1))
                        warm.add(intArrayOf(ez, x + 1, y + 1))
                        if (seen.size >= 10) break@outer
                    }
                    if (TerrainScene.zoomOf(at) <= ROOT_ZOOM) break
                    at = TerrainScene.parentOf(at)
                }
            }
        }
        Elevation.warm(warm)
    }

    private fun roots(): List<Long> {
        val padLat = ROOT_REACH_M / scene.metresUp()
        val padLon = ROOT_REACH_M / scene.metresAcross(scene.originLat)
        val x0 = Elevation.tileX(scene.originLon - padLon, ROOT_ZOOM)
        val x1 = Elevation.tileX(scene.originLon + padLon, ROOT_ZOOM)
        val y0 = Elevation.tileY(scene.originLat + padLat, ROOT_ZOOM)
        val y1 = Elevation.tileY(scene.originLat - padLat, ROOT_ZOOM)
        val out = ArrayList<Long>()
        for (x in Math.min(x0, x1)..Math.max(x0, x1)) {
            for (y in Math.min(y0, y1)..Math.max(y0, y1)) {
                out.add(TerrainScene.tileKey(ROOT_ZOOM, x, y))
            }
        }
        return out
    }

    private fun visit(key: Long, ex: Float, ey: Float, ez: Float, k: Double,
                      built: HashSet<Long>, outCut: ArrayList<Long>, outSplit: HashSet<Long>) {
        val z = TerrainScene.zoomOf(key)
        var tau = if (splitLastPass.contains(key)) MERGE_PX else SPLIT_PX
        if (behind(key, ex, ey, ez)) tau *= BEHIND_TIMES
        // Only through standing ground. The cut used to name its final depth
        // outright, and cover() then held the whole square soft until every
        // descendant stood — a z8's worth of z13s built before anything
        // sharpened, the world updating in continents. Gated on built, the
        // cut is a frontier one level past what stands: every square
        // sharpens a quartet at a time, dissolving in as it lands, nearest
        // first — the ground draws the way the loading grid fills. The
        // single-level swaps are also the ones the morph and the stitch
        // cover best.
        if (z < LEAF_ZOOM && rho(key, ex, ey, ez, k) > tau && built.contains(key)) {
            outSplit.add(key)
            val x = TerrainScene.tileXOf(key)
            val y = TerrainScene.tileYOf(key)
            for (cx in 0..1) {
                for (cy in 0..1) {
                    visit(TerrainScene.tileKey(z + 1, x * 2 + cx, y * 2 + cy),
                        ex, ey, ez, k, built, outCut, outSplit)
                }
            }
        } else {
            outCut.add(key)
        }
    }

    /**
     * Whether the tile lies wholly behind the camera's shoulder.
     *
     * Every corner, not the middle: a large tile's near corner can be on
     * screen while its centre is a quarter turn behind, and holding such a
     * tile coarse put a visible detail step at the screen's edge — the
     * morph cannot cover a boundary that direction, not distance, decides.
     * A tile underfoot always has a corner ahead, so the old underfoot
     * exemption is subsumed — one rule for one thing.
     */
    private fun behind(key: Long, ex: Float, ey: Float, ez: Float): Boolean {
        val z = TerrainScene.zoomOf(key)
        val x = TerrainScene.tileXOf(key)
        val y = TerrainScene.tileYOf(key)
        val west = scene.east(TerrainScene.tileLon(x, z))
        val east = scene.east(TerrainScene.tileLon(x + 1, z))
        val north = -scene.north(TerrainScene.tileLat(y, z))
        val south = -scene.north(TerrainScene.tileLat(y + 1, z))
        val vx = renderer.viewDirX
        val vz = renderer.viewDirZ
        for (corner in 0..3) {
            val dx = (if (corner and 1 == 0) west else east) - ex
            val dz = (if (corner and 2 == 0) north else south) - ez
            val len = Math.sqrt((dx * dx + dz * dz).toDouble()).toFloat()
            // a corner at the eye is never behind
            if (len < 1f) return false
            if ((dx * vx + dz * vz) / len >= -0.25f) return false
        }
        return true
    }

    /** The tile's error in screen pixels, from the eye to its box. */
    private fun rho(key: Long, ex: Float, ey: Float, ez: Float, k: Double): Float {
        val z = TerrainScene.zoomOf(key)
        val d = distanceTo(key, ex, ey, ez)
        return (errorOf(z) * k / Math.max(1.0, d)).toFloat()
    }

    private fun distanceTo(key: Long, ex: Float, ey: Float, ez: Float): Double {
        val z = TerrainScene.zoomOf(key)
        val x = TerrainScene.tileXOf(key)
        val y = TerrainScene.tileYOf(key)
        val west = scene.east(TerrainScene.tileLon(x, z))
        val east = scene.east(TerrainScene.tileLon(x + 1, z))
        val north = -scene.north(TerrainScene.tileLat(y, z))
        val south = -scene.north(TerrainScene.tileLat(y + 1, z))
        val range = heightRangeOf(key)
        val cx = ex.coerceIn(west, east)
        val cy = ey.coerceIn(range[0], range[1])
        val cz = ez.coerceIn(north, south)
        val dx = (ex - cx).toDouble()
        val dy = (ey - cy).toDouble()
        val dz = (ez - cz).toDouble()
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** What is known of the tile's height span: its own, an ancestor's, or a guess. */
    private fun heightRangeOf(key: Long): FloatArray {
        synchronized(lock) {
            var at = key
            while (true) {
                heightRange[at]?.let { return it }
                if (TerrainScene.zoomOf(at) <= ROOT_ZOOM) break
                at = TerrainScene.parentOf(at)
            }
        }
        // nothing built near here yet: anywhere on Earth, roughly
        return WIDE_OPEN
    }

    private val WIDE_OPEN = floatArrayOf(-1000f, 4500f)

    // ----------------------------------------------------------------- cover

    /**
     * Children replace a parent the moment all four stand — not when their
     * own pictures have arrived. They borrow the parent's picture in the
     * renderer until then, so the swap costs nothing to look at; waiting for
     * four dressed subtrees meant a whole ancestry of downloads before one
     * sharp tile could show, which read as detail never arriving at all.
     */
    /** Roll a list back to [size]; the recursion's cheap undo. */
    private fun <T> trim(list: ArrayList<T>, size: Int) {
        while (list.size > size) list.removeAt(list.size - 1)
    }

    private fun cover(key: Long, cutSet: Set<Long>, out: ArrayList<Long>,
                      maskKeys: ArrayList<Long>, maskBits: ArrayList<Int>): Boolean {
        val z = TerrainScene.zoomOf(key)
        if (cutSet.contains(key) || z >= LEAF_ZOOM) {
            val p = resident[key] ?: return false
            // A merge must never soften what is on screen. A parent that
            // has not dressed yet borrowed wallpaper from far up while its
            // children's sharp pictures still sat on the card — so the
            // children keep standing for it until its own picture lands,
            // and the flip then arrives under the dissolve. An undressed
            // child borrows the same ancestor the parent would, so the
            // quartet is never softer than the parent it stands for.
            if (!p.dressed && z < LEAF_ZOOM) {
                val x = TerrainScene.tileXOf(key)
                val y = TerrainScene.tileYOf(key)
                var quartet = true
                for (cy in 0..1) {
                    for (cx in 0..1) {
                        if (!resident.containsKey(
                                TerrainScene.tileKey(z + 1, x * 2 + cx, y * 2 + cy))) {
                            quartet = false
                        }
                    }
                }
                if (quartet) {
                    for (cy in 0..1) {
                        for (cx in 0..1) {
                            out.add(TerrainScene.tileKey(z + 1, x * 2 + cx, y * 2 + cy))
                        }
                    }
                    return true
                }
            }
            out.add(key)
            return true
        }
        val x = TerrainScene.tileXOf(key)
        val y = TerrainScene.tileYOf(key)
        // Shared lists with rollback marks, not per-node scratch sets: this
        // runs five times a second over a hundred internal nodes, and a set
        // and a map born per child per node was steady garbage on the mesh
        // thread. A failed child undoes its own additions; a square that can
        // neither stand whole nor partial undoes its kept corners, since
        // nothing may draw over the tile that stands for the whole square.
        val outMark = out.size
        val maskMark = maskKeys.size
        var mask = 0
        var whole = true
        for (cx in 0..1) {
            for (cy in 0..1) {
                val childOut = out.size
                val childMask = maskKeys.size
                if (cover(TerrainScene.tileKey(z + 1, x * 2 + cx, y * 2 + cy),
                        cutSet, out, maskKeys, maskBits)) {
                    mask = mask or (1 shl (cy * 2 + cx))
                } else {
                    whole = false
                    trim(out, childOut)
                    trim(maskKeys, childMask)
                    trim(maskBits, childMask)
                }
            }
        }
        if (whole) return true
        // Standing quarters draw as children; this tile draws the rest —
        // the load's face was the whole-square wait, dressed corners
        // standing invisible while a 112km root finished its far side.
        if (mask != 0 && resident.containsKey(key)) {
            out.add(key)
            maskKeys.add(key)
            maskBits.add(mask)
            return true
        }
        trim(out, outMark)
        trim(maskKeys, maskMark)
        trim(maskBits, maskMark)
        if (resident.containsKey(key)) {
            out.add(key)
            return true
        }
        return false
    }

    // ------------------------------------------------------------- streaming

    /** Build the most wanted missing mesh; true if one was built. */
    private fun buildNext(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        var wanted: Long? = null
        synchronized(lock) {
            // The roots nearest the camera before anything at all: they are
            // the floor the view stands on. Only the nearest few — building
            // all twenty-five first kept the ground underfoot waiting on
            // wallpaper for the far side of the region.
            val missingRoots = roots().filter {
                !resident.containsKey(it) && now >= (buildRetryAt[it] ?: 0L)
            }
            if (missingRoots.isNotEmpty()) {
                val ex = renderer.eyeX
                val ey = renderer.eyeY
                val ez = renderer.eyeZ
                val nearest = missingRoots.minByOrNull { distanceTo(it, ex, ey, ez) }
                if (nearest != null && missingRoots.size > roots().size - 4) {
                    wanted = nearest
                }
            }
            // then the cut in need, NEAREST first — and every missing
            // ancestor before its child, since the ancestor is what stands
            // in. The cut's own order is the tree walk's, which sharpened
            // whichever corner the traversal happened to reach first while
            // the ground underfoot waited its turn.
            if (wanted == null) {
                val ex = renderer.eyeX
                val ey = renderer.eyeY
                val ez = renderer.eyeZ
                var best = -1L
                var bestD = Double.MAX_VALUE
                for (key in cut) {
                    var candidate = -1L
                    var at = key
                    while (true) {
                        if (!resident.containsKey(at) &&
                            now >= (buildRetryAt[at] ?: 0L)) {
                            candidate = at
                        }
                        if (TerrainScene.zoomOf(at) <= ROOT_ZOOM) break
                        at = TerrainScene.parentOf(at)
                    }
                    if (candidate != -1L) {
                        val d = distanceTo(key, ex, ey, ez)
                        if (d < bestD) {
                            bestD = d
                            best = candidate
                        }
                    }
                }
                if (best != -1L) wanted = best
            }
            if (wanted == null) {
                // Nothing missing: mend one tile that was built blind, by
                // letting the ordinary path build it again now that its
                // neighbours may exist. One per turn — mending is upkeep,
                // not a race.
                for (key in cut) {
                    val p = resident[key] ?: continue
                    if (!p.rimComplete && now >= (buildRetryAt[key] ?: 0L)) {
                        // the picture is the renderer's to recycle — the one
                        // recycle that survived the ownership rule here was
                        // the same GL-thread race the rule exists to end
                        DebugLog.note(TAG, "mend ${TerrainScene.zoomOf(key)}/" +
                            "${TerrainScene.tileXOf(key)}/${TerrainScene.tileYOf(key)}" +
                            (if (p.dressed) " was dressed" else ""))
                        if (p.dressed && p.extraDressed > 0) {
                            mendWorn[key] = p.extraDressed
                        }
                        resident.remove(key)
                        heightRange.remove(key)
                        return true
                    }
                }
            }
        }
        val key = wanted ?: return false
        if (abandoned) return false
        val z = TerrainScene.zoomOf(key)
        val mesh = scene.buildTile(z, TerrainScene.tileXOf(key), TerrainScene.tileYOf(key))
        if (mesh == null) {
            // No heights here at all — the fetch failed and its source backs
            // off for thirty seconds. Retrying sooner than that only counts
            // failures against a server that already said not now.
            synchronized(lock) {
                buildRetryAt[key] =
                    android.os.SystemClock.elapsedRealtime() + 30_000L
            }
            buildFails++
            DebugLog.note(TAG, "build failed $z/" +
                "${TerrainScene.tileXOf(key)}/${TerrainScene.tileYOf(key)}")
            // a verdict, not a hiccup: one transient timeout on a cold start
            // used to flash "No terrain here" and release the held flight
            // over bare mesh while the other roots were still building fine
            if (buildFails >= 6 && synchronized(lock) { resident.isEmpty() }) {
                onStatus?.invoke("No terrain here")
            }
            return true
        }
        onStatus?.invoke("")
        synchronized(lock) {
            if (abandoned) return false
            resident[key] = Paged(mesh, dressed = false, textureBytes = 0,
                rimComplete = mesh.rimComplete,
                wornBefore = mendWorn.remove(key) ?: 0)
            heightRange[key] = floatArrayOf(mesh.minY, mesh.maxY)
            // built blind: come back for it when its neighbours have landed
            if (!mesh.rimComplete) {
                buildRetryAt[key] =
                    android.os.SystemClock.elapsedRealtime() + 20_000L
            }
        }
        renderer.offer(mesh)
        builds++
        if (builds == 1) {
            firstBuildAt = android.os.SystemClock.elapsedRealtime()
            DebugLog.note(TAG, "first mesh after " +
                "${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
        }
        poke()
        return true
    }

    /**
     * The pictures the pass wants, sharpest need first, for the tex threads.
     *
     * Only the cut. The coarse ancestors standing in for it dressed first for
     * a while — they are what is visible, so their screen error is huge — and
     * every byte they fetched was a byte the tile actually wanted had to wait
     * for. They inherit downward instead: a cut tile's picture is exactly the
     * area an ancestor shows of it.
     */
    private fun wantTextures(cutSet: Set<Long>, ex: Float, ey: Float, ez: Float, k: Double) {
        val now = android.os.SystemClock.elapsedRealtime()
        val list = ArrayList<LongArray>()
        // The cut, and always every root. The root under the camera is never
        // in the cut — its square is exactly the refined part — and leaving
        // it bare left the whole refined region with nothing to borrow: the
        // world was photographic everywhere except where the flight was.
        val candidates = HashSet(cutSet)
        candidates.addAll(roots())
        var keptBytes = 0L
        synchronized(lock) {
            // everything already worn counts against the budget, wherever it
            // is — without this each pass granted a fresh full budget to the
            // still-undressed and the card crept far past the ceiling
            for (p in resident.values) keptBytes += p.textureBytes
            for (key in candidates) {
                val p = resident[key] ?: continue
                if (now < (textureRetryAt[key] ?: 0L)) continue
                val z = TerrainScene.zoomOf(key)
                val span = spanOf(key)
                val d = distanceTo(key, ex, ey, ez)
                val wantedPx = span * k / Math.max(1.0, d)
                // a root is wallpaper under finer tiles, never scenery: one
                // cheap fetch, not a sixty-four piece mosaic of the horizon
                var extra = if (z == ROOT_ZOOM) 0 else Math.round(
                    Math.log(Math.max(1.0, wantedPx / 256.0)) / Math.log(2.0)
                ).toInt().coerceIn(0, Math.min(3, 19 - z))
                // The horizon does not order for the whole table. A far z9
                // slab spans a third of the screen, so the arithmetic above
                // honestly asks 2048 pixels for it — sixty-four fetches per
                // horizon tile, dozens of tiles, was where the whole pipe
                // went while the ground underfoot queued: one field session
                // measured the card 50MB past its budget with nine hundred
                // evictions, fetching and refetching pictures nobody can
                // read at that sharpness through haze and foreshortening.
                // Coarse rings cap a step up; the near levels keep the lot.
                val farCeiling = when {
                    z <= 10 -> 1
                    z <= 12 -> 2
                    else -> sharpCeiling
                }
                if (extra > farCeiling) extra = farCeiling
                // First dress in a handful of fetches, full sharpness a
                // breath later through the upgrade path: a cold view reads
                // as ready seconds sooner, and ends just as sharp. A tile
                // back from a mend is no first dress: the renderer kept its
                // picture on and every read up to what it wore is cached —
                // dropped to one fetch, sharp ground faded soft and climbed
                // the whole ladder back on every rim repair.
                val firstAsk = Math.max(1, p.wornBefore)
                if (!p.dressed && extra > firstAsk) extra = firstAsk
                // Sharpening climbs one level per picture rather than leaping
                // to the top: each step is a quarter of the next one's
                // fetches, so the view visibly improves on the way instead of
                // once, all at the end. On ground already seen each step is
                // one cached read, so the climb stays quick.
                if (p.dressed && extra > p.extraDressed + 1) {
                    extra = p.extraDressed + 1
                }
                // A dressed tile comes back to be sharpened as the camera
                // closes — or thinned as it leaves, but only when someone
                // actually needs the memory: thinning on every zoom-out made
                // zooming back in pay full price for pictures it already had.
                if (p.dressed && extra == p.extraDressed) continue
                if (p.dressed && extra < p.extraDressed &&
                    (!starvedLastPass ||
                        p.textureBytes - bytesAt(extra) < 4L * 1024 * 1024)) {
                    continue
                }
                val priority = when {
                    // The roots dress before everything — twenty-five cheap
                    // pictures every finer tile can borrow from, which is the
                    // difference between a grey world and a soft one — and
                    // the root under the camera before the other roots.
                    !p.dressed && z == ROOT_ZOOM ->
                        Long.MAX_VALUE / 2 - d.toLong()
                    // Missing sharpness over distance. The cut equalises
                    // screen error by construction, so ranking by rho spread
                    // the dressing near-uniformly across the view — and with
                    // upgrades flatly behind first pictures, the ground
                    // underfoot reached its real sharpness last, after every
                    // far first dress: the eye waited the whole load for the
                    // one place it was looking. A missing first picture
                    // outweighs a missing step of sharpness, nearness weighs
                    // both, the horizon comes last, thinning after that.
                    else -> {
                        val missing = if (!p.dressed) extra + 2
                            else (extra - p.extraDressed).coerceAtLeast(0)
                        if (missing == 0) 0L
                        else missing * 1_000_000_000L / (d.toLong() + 100L)
                    }
                }
                // its old picture goes when the new one lands, so only the
                // difference weighs on the budget
                if (p.dressed) keptBytes -= p.textureBytes
                val thinning = if (p.dressed && extra < p.extraDressed) 1L else 0L
                list.add(longArrayOf(key, extra.toLong(), priority,
                    p.extraDressed.toLong(), thinning))
            }
        }
        list.sortByDescending { it[2] }
        // The budget decides who gets to be sharp, best first. Without this
        // every cut tile asked for the full mosaic — the screen maths says
        // they would all use it — and the card carried six hundred megabytes
        // of pictures with nothing evictable. The nearest tiles spend the
        // budget on 2048s; the rest take what is left, down to one fetch.
        var budgetLeft = Math.max(0, textureBudgetBytes - keptBytes)
        var starved = false
        for (entry in list) {
            var extra = entry[1].toInt()
            val wearing = entry[3].toInt()
            while (extra > 0 && bytesAt(extra) > budgetLeft) {
                extra--
                starved = true
            }
            // An upgrade squeezed below what the tile already wears is not an
            // upgrade any more — unhandled, it went out as an accidental
            // downgrade of exactly the sharpest ground, and the next pass
            // undid it, forever. A deliberate thinning is exempt: it is
            // below-what-it-wears by definition, and this guard cancelling
            // it made the entire reclaim mechanism unreachable — the card
            // sat over budget with no way back down.
            if (entry[4] == 0L && wearing >= 0 && extra <= wearing) {
                // the tile keeps the picture it wears, so the bytes credited
                // away when it was listed come back out of the budget before
                // a poorer tile is allowed to spend them
                budgetLeft = Math.max(0, budgetLeft - bytesAt(wearing))
                entry[1] = -1
                continue
            }
            budgetLeft = Math.max(0, budgetLeft - bytesAt(extra))
            entry[1] = extra.toLong()
        }
        starvedLastPass = starved
        wantedTextures = list
    }

    /** Whether the last pass had to hand out less than the screen wanted. */
    private var starvedLastPass = false

    /** RGB_565 plus a third for mipmaps, for a 256 shl extra square picture. */
    private fun bytesAt(extra: Int): Long {
        val side = (256 shl extra).toLong()
        return side * side * 2 * 4 / 3
    }

    private fun spanOf(key: Long): Double {
        val z = TerrainScene.zoomOf(key)
        val x = TerrainScene.tileXOf(key)
        return Math.abs(
            scene.east(TerrainScene.tileLon(x + 1, z)) -
                scene.east(TerrainScene.tileLon(x, z))
        ).toDouble()
    }

    private fun texLoop() {
        android.os.Process.setThreadPriority(
            android.os.Process.THREAD_PRIORITY_BACKGROUND)
        while (!abandoned) {
            if (paused) {
                synchronized(lock) {
                    if (!abandoned && paused) lock.wait(500)
                }
                continue
            }
            val job = synchronized(lock) {
                val found = wantedTextures.firstOrNull { entry ->
                    // Direction matters. "Not what it wears" alone spun
                    // forever: the cache hands back sharper than asked, the
                    // tile records the truth, and equality never comes.
                    val target = entry[1].toInt()
                    val was = entry[3].toInt()
                    val p = resident[entry[0]]
                    target >= 0 && !texInFlight.contains(entry[0]) && p != null &&
                        (!p.dressed ||
                            (target > was && p.extraDressed < target) ||
                            (target < was && p.extraDressed > target))
                }
                if (found != null) texInFlight.add(found[0])
                found
            }
            if (job == null) {
                synchronized(lock) { if (!abandoned) lock.wait(200) }
                continue
            }
            val key = job[0]
            try {
                fetchAndDress(key, job[1].toInt(), job[3].toInt())
            } catch (e: Throwable) {
                // a worker that dies of one bad tile is a quarter of the
                // dressing capacity gone for the life of the view
                DebugLog.note(TAG, "dress failed: ${e.message}")
            } finally {
                synchronized(lock) { texInFlight.remove(key) }
            }
        }
    }

    private fun fetchAndDress(key: Long, extra: Int, was: Int) {
        val z = TerrainScene.zoomOf(key)
        val bmp = try {
            Imagery.mosaic(z, TerrainScene.tileXOf(key), TerrainScene.tileYOf(key), extra) {
                !abandoned && !paused
            }
        } catch (e: Throwable) {
            null
        }
        if (abandoned) {
            bmp?.recycle()
            return
        }
        if (bmp == null) {
            // a mosaic aborted by a pause is not a failure — backing off for
            // it left the ground under the camera soft for fifteen seconds
            // after every resume
            if (!paused) {
                synchronized(lock) {
                    textureRetryAt[key] =
                        android.os.SystemClock.elapsedRealtime() + TEXTURE_RETRY_MS
                }
                dressFails++
                DebugLog.note(TAG, "no picture $z/" +
                    "${TerrainScene.tileXOf(key)}/${TerrainScene.tileYOf(key)}+$extra")
            }
            return
        }
        var fireFirst = false
        var published: TerrainScene.TileMesh? = null
        synchronized(lock) {
            val p = resident[key]
            // fresh, sharper, or deliberately thinner — whatever was queued.
            // The old picture is not touched here: once offered, a bitmap
            // belongs to the renderer, and recycling it from this thread
            // while the GL thread had it half way through an upload was the
            // crash that christened the field log.
            if (p != null && (!p.dressed ||
                    (extra > was && p.extraDressed < extra) ||
                    (extra < was && p.extraDressed > extra))) {
                val old = p.mesh
                p.mesh = TerrainScene.TileMesh(old.key, old.vertices, old.indices,
                    bmp, old.minX, old.maxX, old.minY, old.maxY, old.minZ, old.maxZ,
                    quadCount = old.quadCount)
                p.dressed = true
                // The larger of what was asked and what arrived. Softer than
                // asked is the imagery's ceiling where ArcGIS ends — record
                // the ask as satisfied or the pager re-asks for a sharpness
                // that does not exist, forever. Measured. Sharper than asked
                // no longer arrives: the cache scales its answer to the ask,
                // because pictures the budget never granted broke it.
                var worn = 0
                var w = bmp.width
                while (w > 256) {
                    worn++
                    w = w shr 1
                }
                p.extraDressed = Math.max(extra, worn)
                p.textureBytes = bmp.width.toLong() * bmp.height * 2 * 4 / 3
                published = p.mesh
                if (!firstDressedFired) {
                    firstDressedFired = true
                    fireFirst = true
                }
            }
        }
        val mesh = published
        if (mesh == null) {
            bmp.recycle()
            return
        }
        renderer.offer(mesh)
        if (fireFirst) {
            DebugLog.note(TAG, "first picture after " +
                "${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
            onFirstDressed?.invoke()
        }
        poke()
    }

    // ---------------------------------------------------------------- budget

    /** Caller holds [lock]. Drops what the camera has left behind. */
    private fun evictLocked(cutSet: Set<Long>) {
        var textureTotal = 0L
        for (p in resident.values) textureTotal += p.textureBytes
        if (resident.size <= residentMost && textureTotal <= textureBudgetBytes) return
        // Pictures first, whole tiles second. The frontier dresses every
        // level on its way down, and the covered-over ancestors then kept
        // those pictures forever under the spare-list — the working set
        // swelled to the ceiling and the eviction treadmill returned, a
        // thousand rebuilt tiles per session, measured. A tile that is not
        // drawn does not need its picture: stripped, the mesh stays and
        // the picture is twenty disk-milliseconds away — but only once all
        // four children are dressed, so no bare descendant's borrow-walk
        // still passes through it, and never a root. Down to nine tenths,
        // not to the line, or this strips one tile every pass forever.
        if (textureTotal > textureBudgetBytes) {
            val candidates = ArrayList<Pair<Long, Paged>>()
            for (e in resident.entries) {
                val p = e.value
                if (!p.dressed || p.textureBytes == 0L) continue
                if (cover.contains(e.key) || cutSet.contains(e.key)) continue
                if (TerrainScene.zoomOf(e.key) == ROOT_ZOOM) continue
                candidates.add(Pair(e.key, p))
            }
            for ((key, p) in candidates) {
                if (textureTotal <= textureBudgetBytes * 9 / 10) break
                val z = TerrainScene.zoomOf(key)
                val x = TerrainScene.tileXOf(key)
                val y = TerrainScene.tileYOf(key)
                var childrenDressed = true
                for (cy in 0..1) {
                    for (cx in 0..1) {
                        val c = resident[TerrainScene.tileKey(z + 1, x * 2 + cx, y * 2 + cy)]
                        if (c == null || !c.dressed) childrenDressed = false
                    }
                }
                if (!childrenDressed) continue
                textureTotal -= p.textureBytes
                p.textureBytes = 0
                p.dressed = false
                p.extraDressed = -1
                renderer.strip(key)
                stripped++
            }
        }
        val it = resident.entries.iterator()
        while (it.hasNext() &&
            (resident.size > residentMost || textureTotal > textureBudgetBytes)) {
            val entry = it.next()
            val key = entry.key
            // Never what is drawn, wanted, or standing in for the wanted —
            // and never a root: their pictures are what every bare tile
            // borrows, and a world with its roots is never grey anywhere.
            if (cover.contains(key) || cutSet.contains(key) ||
                coverAncestors.contains(key) ||
                TerrainScene.zoomOf(key) == ROOT_ZOOM) {
                continue
            }
            // the bitmap is the renderer's to recycle, wherever it is in
            // its queues; touching it from here raced the upload
            textureTotal -= entry.value.textureBytes
            heightRange.remove(key)
            textureRetryAt.remove(key)
            buildRetryAt.remove(key)
            it.remove()
            evictions++
        }
    }

    private fun stats() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - statsAt < STATS_EVERY_MS) return
        statsAt = now
        var dressed = 0
        var bytes = 0L
        val count = synchronized(lock) {
            for (p in resident.values) {
                if (p.dressed) dressed++
                bytes += p.textureBytes
            }
            resident.size
        }
        val runtime = Runtime.getRuntime()
        val heap = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        DebugLog.note(TAG, "cut=${cut.size} cover=${cover.size} resident=$count " +
            "dressed=$dressed textures=${bytes / (1024 * 1024)}MB heap=${heap}MB " +
            "builds=$builds fails=$buildFails evictions=$evictions stripped=$stripped")
    }
}
