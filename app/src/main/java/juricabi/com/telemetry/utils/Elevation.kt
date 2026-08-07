package juricabi.com.telemetry.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Ground elevation under a track, so a flight can be drawn against the terrain
 * it was flown over.
 *
 * Tiles come from AWS's open terrain set, which needs no key and packs the
 * height into an ordinary slippy-map PNG ("terrarium" encoding):
 * metres = red * 256 + green + blue / 256 - 32768. The data behind it is about
 * thirty metres to a sample, so zoom 12 already carries more pixels than there
 * are measurements — but tiles are fetched as far in as zoom 15, because a tile
 * covers less country the further in it goes, and that is what keeps the window
 * of ground around a model small enough to fetch.
 *
 * Lookups answer from memory only. Filling that memory is [prefetch]'s job, on
 * a background thread.
 */
object Elevation {

    /** What the altitude profile reads at, and the coarsest ground is built from. */
    const val TILE_ZOOM = 12

    private const val TAG = "Elevation"
    private const val TILE_SIZE = 256
    private const val MAX_ZOOM = 15
    private const val TILE_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium"

    /**
     * ~128 KB of heights each, so this is a forty-megabyte floor for the
     * life of the process — held knowingly: the cheapest memory in the
     * whole system, and what keeps a revisit from paying the network
     * again. The pyramid's working set spans every zoom at once plus the
     * warm-ahead; at sixty-four tiles the cache evicted what a build was
     * standing on between the fetch and the sample, the build failed its
     * rim check, and whole regions sat grey on five-second retries.
     */
    private const val MEMORY_TILES = 320

    /** A track's bounding box is normally a handful of tiles; this is a runaway guard. */
    private const val MAX_PREFETCH_TILES = 256

    private const val NO_DATA = Short.MIN_VALUE
    private const val EMPTY_PIXEL = -20000
    private const val MIN_METRES = -500
    private const val MAX_METRES = 9000

    private const val MERCATOR_LAT = 85.05112878

    /**
     * Keyed by a number rather than a string.
     *
     * Building one tile of ground asks this thirty-seven thousand times, once
     * per vertex, and every one of those used to leave a throwaway string
     * behind — a hundred thousand of them per tile, for the garbage collector
     * to sweep up while the ground was being drawn.
     */
    private val memory: LinkedHashMap<Long, ShortArray> =
        object : LinkedHashMap<Long, ShortArray>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ShortArray>?) =
                size > MEMORY_TILES
        }

    /**
     * Eight threads for the life of the process, rather than a pool per
     * request. The quadtree warms a dozen rings of tiles at a time, and the
     * pool is what turns that from a dozen round trips into one.
     */
    private val pool by lazy {
        Executors.newFixedThreadPool(8, java.util.concurrent.ThreadFactory { runnable ->
            val thread = Thread({
                // background, with the imagery pool: the ground yields cores
                // to the flight being drawn over it
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "elevation")
            thread.isDaemon = true
            thread
        })
    }

    /** Tiles the server has said are not there, so they are asked for once only. */
    private val failed = HashSet<Long>()

    /**
     * Tiles that could not be fetched just now, and when to ask again.
     *
     * Long enough that one load pass never retries a tile it has already
     * waited fifteen seconds on — with no signal, a pass that retried every
     * miss ground through the whole window a timeout at a time — and short
     * enough that the pass after the signal returns gets them all.
     */
    private val retryAt = HashMap<Long, Long>()
    private const val RETRY_AFTER_MS = 30_000L

    /** [download]'s way of saying the server answered no, against not answering. */
    private val ABSENT = ByteArray(0)

    @Volatile
    private var cacheDir: File? = null

    fun init(context: Context) {
        try {
            val dir = File(context.applicationContext.cacheDir, "terrain")
            if (!dir.isDirectory) dir.mkdirs()
            cacheDir = dir
        } catch (e: Exception) {
            // no disk cache is survivable; every tile just costs a download
            Log.w(TAG, "no terrain cache dir: ${e.message}")
        }
    }

    /**
     * Metres above sea level, or null if the tile under this point is not in
     * memory. Never touches the network or the disk: this runs thousands of
     * times per frame from onDraw.
     */
    fun elevationAt(lat: Double, lon: Double, zoom: Int = TILE_ZOOM): Float? {
        try {
            if (zoom < 0 || zoom > MAX_ZOOM) return null
            if (lat.isNaN() || lon.isNaN()) return null
            val fx = fracTileX(lon, zoom)
            val fy = fracTileY(lat, zoom)
            val tx = floor(fx).toInt()
            val ty = floor(fy).toInt()
            val tile = cached(zoom, tx, ty) ?: return null
            // samples are pixel centres, so the sample grid sits half a pixel
            // in from the tile's edge
            val px = (fx - tx) * TILE_SIZE - 0.5
            val py = (fy - ty) * TILE_SIZE - 0.5
            val x0 = floor(px).toInt()
            val y0 = floor(py).toInt()
            val ax = px - x0
            val ay = py - y0
            val h00 = sample(zoom, tx, ty, tile, x0, y0)
            val h10 = sample(zoom, tx, ty, tile, x0 + 1, y0)
            val h01 = sample(zoom, tx, ty, tile, x0, y0 + 1)
            val h11 = sample(zoom, tx, ty, tile, x0 + 1, y0 + 1)
            if (h00 == NO_DATA || h10 == NO_DATA || h01 == NO_DATA || h11 == NO_DATA) return null
            val top = h00 + (h10 - h00) * ax
            val bottom = h01 + (h11 - h01) * ax
            return (top + (bottom - top) * ay).toFloat()
        } catch (e: Exception) {
            return null
        }
    }


    /**
     * The box, fired into the pool and never waited for.
     *
     * [prefetch] joins its downloads, which is right for a progress dialog
     * and wrong for everyone else: asked from the screen's thread it parked
     * the screen on the network for as long as the fetches took.
     */
    fun warmBox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, zoom: Int) {
        val box = tileBox(minLat, minLon, maxLat, maxLon, zoom) ?: return
        if (box.count == 0 || box.count > MAX_PREFETCH_TILES) return
        for (x in box.x0..box.x1) {
            for (y in box.y0..box.y1) {
                pool.submit { load(zoom, x, y) }
            }
        }
    }

    /**
     * Fetch every tile over these bounds, blocking the caller - which must not
     * be the main thread. [onDone] reports usable against total tiles, so an
     * area with no terrain at all can be told from a partial one.
     */
    fun prefetch(
        minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        zoom: Int = TILE_ZOOM,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onDone: (Int, Int) -> Unit = { _, _ -> }
    ) {
        var total = 0
        val usable = AtomicInteger(0)
        try {
            val box = tileBox(minLat, minLon, maxLat, maxLon, zoom)
            if (box == null || box.count == 0) {
                onDone(0, 0)
                return
            }
            total = box.count
            if (total > MAX_PREFETCH_TILES) {
                Log.w(TAG, "refusing to prefetch $total tiles at zoom $zoom")
                onDone(0, total)
                return
            }
            val done = AtomicInteger(0)
            val jobs = ArrayList<java.util.concurrent.Future<*>>()
            try {
                for (x in box.x0..box.x1) {
                    for (y in box.y0..box.y1) {
                        jobs.add(pool.submit {
                            if (load(zoom, x, y)) usable.incrementAndGet()
                            onProgress(done.incrementAndGet(), total)
                        })
                    }
                }
                for (job in jobs) {
                    try {
                        job.get(5, TimeUnit.MINUTES)
                    } catch (e: Exception) {
                        job.cancel(true)
                    }
                }
            } finally {
                jobs.clear()
            }
        } catch (e: Exception) {
            Log.w(TAG, "prefetch failed: ${e.message}")
        }
        onDone(usable.get(), total)
    }

    fun tileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor(fracTileX(lon, zoom)).toInt().coerceIn(0, n - 1)
    }

    fun tileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor(fracTileY(lat, zoom)).toInt().coerceIn(0, n - 1)
    }

    private fun fracTileX(lon: Double, zoom: Int): Double {
        val wrapped = ((lon + 180.0) % 360.0 + 360.0) % 360.0
        return wrapped / 360.0 * (1 shl zoom)
    }

    private fun fracTileY(lat: Double, zoom: Int): Double {
        val rad = Math.toRadians(lat.coerceIn(-MERCATOR_LAT, MERCATOR_LAT))
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0 * (1 shl zoom)
    }

    private class TileBox(val x0: Int, val x1: Int, val y0: Int, val y1: Int) {
        val count = (x1 - x0 + 1) * (y1 - y0 + 1)
    }

    private fun tileBox(
        minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, zoom: Int
    ): TileBox? {
        if (zoom < 0 || zoom > MAX_ZOOM) return null
        if (minLat.isNaN() || minLon.isNaN() || maxLat.isNaN() || maxLon.isNaN()) return null
        val ax = tileX(minLon, zoom)
        val bx = tileX(maxLon, zoom)
        // y counts down from the north pole, so the northern edge is the low one
        val ay = tileY(maxLat, zoom)
        val by = tileY(minLat, zoom)
        return TileBox(min(ax, bx), max(ax, bx), min(ay, by), max(ay, by))
    }

    private fun key(zoom: Int, x: Int, y: Int): Long =
        (zoom.toLong() shl 58) or (x.toLong() shl 29) or (y.toLong() and 0x1FFFFFFF)

    private fun cached(zoom: Int, x: Int, y: Int): ShortArray? =
        synchronized(memory) { memory[key(zoom, x, y)] }

    /**
     * One sample, where x or y may be -1 or 256 and so land in the tile next
     * door. If that neighbour is not loaded the covering tile's edge is
     * repeated, rather than punching a hole along every seam.
     */
    private fun sample(
        zoom: Int, tx: Int, ty: Int, primary: ShortArray, x: Int, y: Int
    ): Short {
        if (x in 0 until TILE_SIZE && y in 0 until TILE_SIZE) return primary[y * TILE_SIZE + x]
        val n = 1 shl zoom
        val stepX = if (x < 0) -1 else if (x >= TILE_SIZE) 1 else 0
        val stepY = if (y < 0) -1 else if (y >= TILE_SIZE) 1 else 0
        val nx = ((tx + stepX) % n + n) % n      // longitude wraps, latitude does not
        val ny = ty + stepY
        val neighbour = if (ny >= 0 && ny < n) cached(zoom, nx, ny) else null
        if (neighbour == null) {
            return primary[y.coerceIn(0, TILE_SIZE - 1) * TILE_SIZE + x.coerceIn(0, TILE_SIZE - 1)]
        }
        val wrapX = ((x % TILE_SIZE) + TILE_SIZE) % TILE_SIZE
        val wrapY = ((y % TILE_SIZE) + TILE_SIZE) % TILE_SIZE
        return neighbour[wrapY * TILE_SIZE + wrapX]
    }

    /**
     * Have this tile in memory if it can be had, back from disk where it has
     * been evicted. The memory holds two dozen tiles and is shared with the
     * altitude profile and with a load still finishing on a scene already left
     * behind, so a tile put there by [prefetch] may be gone again by the time
     * the ground is built from it.
     */
    fun ensure(zoom: Int, x: Int, y: Int): Boolean = load(zoom, x, y)

    /**
     * Whether the tile is in memory at all — held even when every sample in
     * it is a void. Tells "the data really has a hole here" apart from "the
     * fetch failed", which no sample can: both answer null.
     */
    fun has(zoom: Int, x: Int, y: Int): Boolean = cached(zoom, x, y) != null

    /**
     * Start fetching a set of tiles through the pool and return at once.
     *
     * Waiting for them was six grey seconds at the start of the 3D view:
     * whoever asked could do nothing else, and the pictures the screen was
     * actually waiting on queued behind the wait. The tiles land in cache on
     * their own; whoever needs one before then blocks on that one alone.
     */
    fun warm(wanted: Collection<IntArray>) {
        for (t in wanted) {
            pool.submit { load(t[0], t[1], t[2]) }
        }
    }

    /** Fetches under way, so a racing second asker waits instead of repeating. */
    private val inFlight = HashSet<Long>()

    /** Memory, then disk, then the network. Returns true if the tile is now in memory. */
    private fun load(zoom: Int, x: Int, y: Int): Boolean {
        if (cached(zoom, x, y) != null) return true
        val key = key(zoom, x, y)
        // Known not to be there, or tried a moment ago. Without this the same
        // missing tile is fetched again on every load, and a fetch that is
        // going to fail takes fifteen seconds to find out.
        val skip = synchronized(memory) {
            failed.contains(key) ||
                android.os.SystemClock.elapsedRealtime() < (retryAt[key] ?: 0L)
        }
        if (skip) return false
        // The warm-ahead pool and a build often want the same tile in the
        // same breath, and both downloading it was twice the bandwidth and
        // twice the load on a server that throttles for less. The second
        // asker waits for the first and reads its result.
        synchronized(inFlight) {
            while (inFlight.contains(key)) {
                try {
                    (inFlight as java.lang.Object).wait(20_000)
                } catch (e: InterruptedException) {
                    return false
                }
            }
            if (cached(zoom, x, y) != null) return true
            // The fetch just waited on may be exactly the one that failed.
            // Without this recheck every waiter took its own full turn at a
            // server that had already said no — N serial timeouts instead of
            // one backoff.
            val blocked = synchronized(memory) {
                failed.contains(key) ||
                    android.os.SystemClock.elapsedRealtime() < (retryAt[key] ?: 0L)
            }
            if (blocked) return false
            inFlight.add(key)
        }
        try {
            return fetch(zoom, x, y, key)
        } finally {
            synchronized(inFlight) {
                inFlight.remove(key)
                (inFlight as java.lang.Object).notifyAll()
            }
        }
    }

    private fun fetch(zoom: Int, x: Int, y: Int, key: Long): Boolean {
        var bytes = readDisk(zoom, x, y)
        val fromDisk = bytes != null
        if (bytes == null) {
            bytes = download(zoom, x, y)
            // Only the server saying no is remembered for good. A timeout or a
            // dropped connection was remembered the same way, and one bad
            // moment during a load left holes in the ground for the rest of
            // the process — reopening the view retried nothing.
            if (bytes === ABSENT) {
                synchronized(memory) { failed.add(key) }
                return false
            }
            if (bytes == null) {
                synchronized(memory) {
                    retryAt[key] = android.os.SystemClock.elapsedRealtime() + RETRY_AFTER_MS
                }
                return false
            }
        }
        val heights = decode(bytes)
        if (heights == null) {
            // a half-written cache entry would otherwise be a permanent hole
            if (fromDisk) deleteDisk(zoom, x, y)
            return false
        }
        if (!fromDisk) writeDisk(zoom, x, y, bytes)
        synchronized(memory) {
            memory[key] = heights
            retryAt.remove(key)
        }
        return true
    }

    private fun decode(bytes: ByteArray): ShortArray? {
        var bitmap: Bitmap? = null
        try {
            val options = BitmapFactory.Options()
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            if (bitmap.width != TILE_SIZE || bitmap.height != TILE_SIZE) return null
            val pixels = IntArray(TILE_SIZE * TILE_SIZE)
            bitmap.getPixels(pixels, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
            val heights = ShortArray(pixels.size)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                // blue is a 1/256 m fraction, rounded away: whole metres is far
                // finer than 30m data deserves and halves the array
                val metres = red * 256 + green - 32768 + if (blue >= 128) 1 else 0
                heights[i] = if (metres < EMPTY_PIXEL) NO_DATA
                else metres.coerceIn(MIN_METRES, MAX_METRES).toShort()
            }
            return heights
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            bitmap?.recycle()
        }
    }

    private fun download(zoom: Int, x: Int, y: Int): ByteArray? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("$TILE_URL/$zoom/$x/$y.png").openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            val code = connection.responseCode
            // Only 404 is a verdict. S3 answers 403 for a key that is not
            // there — but also when it throttles a burst, and the difference
            // cannot be told from here. Remembering a 403 for good turned
            // one throttled cold-start warm into a block of ground that
            // stayed coarse for the whole run, rebuilt into the same
            // instant failure every thirty seconds. Terrarium is global, so
            // a real miss is an ask that should not have been made at all;
            // a 403 backs off and asks again like any other bad moment.
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return ABSENT
            if (code != HttpURLConnection.HTTP_OK) {
                // named in the field log: a run of these cost an evening to
                // find when the only trace was a counter going up
                DebugLog.note(TAG, "tile $zoom/$x/$y: HTTP $code")
                return null
            }
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) out.write(buffer, 0, read)
            }
            return out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "tile $zoom/$x/$y not fetched: ${e.message}")
            return null
        } finally {
            connection?.disconnect()
        }
    }

    // The disk cache is the point of the whole thing: terrain fetched at home
    // has to still be there at a flying field with no signal.
    private fun tileFile(zoom: Int, x: Int, y: Int): File? {
        val dir = cacheDir ?: return null
        return File(dir, "${zoom}_${x}_$y.png")
    }

    private fun readDisk(zoom: Int, x: Int, y: Int): ByteArray? {
        try {
            val file = tileFile(zoom, x, y) ?: return null
            if (!file.isFile || file.length() <= 0L) return null
            return file.readBytes()
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    private fun writeDisk(zoom: Int, x: Int, y: Int, bytes: ByteArray) {
        try {
            val file = tileFile(zoom, x, y) ?: return
            val dir = file.parentFile ?: return
            if (!dir.isDirectory && !dir.mkdirs()) return
            // Written aside and renamed, so a cut-off download is never read
            // back — under a name of this thread's own, as the imagery cache
            // already does, so two loads wanting the same tile cannot
            // interleave writes into one temp file and rename the mixture.
            val temp = File(dir, "${file.name}.${Thread.currentThread().id}.tmp")
            FileOutputStream(temp).use { it.write(bytes) }
            if (!temp.renameTo(file)) temp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "tile $zoom/$x/$y not cached: ${e.message}")
        }
    }

    private fun deleteDisk(zoom: Int, x: Int, y: Int) {
        try {
            tileFile(zoom, x, y)?.delete()
        } catch (e: Exception) {
            // ignore
        }
    }
}
