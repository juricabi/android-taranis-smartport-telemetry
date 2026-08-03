package crazydude.com.telemetry.utils

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
 * metres = red * 256 + green + blue / 256 - 32768. The data is ~30m SRTM, so
 * zoom 12 is as much detail as there is.
 *
 * Lookups answer from memory only. Filling that memory is [prefetch]'s job, on
 * a background thread.
 */
object Elevation {

    const val TILE_ZOOM = 12

    private const val TAG = "Elevation"
    private const val TILE_SIZE = 256
    private const val MAX_ZOOM = 15
    private const val TILE_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium"

    /** ~128 KB of heights each, so a couple of dozen is a few megabytes. */
    private const val MEMORY_TILES = 24

    /** A track's bounding box is normally a handful of tiles; this is a runaway guard. */
    private const val MAX_PREFETCH_TILES = 256

    private const val NO_DATA = Short.MIN_VALUE
    private const val EMPTY_PIXEL = -20000
    private const val MIN_METRES = -500
    private const val MAX_METRES = 9000

    private const val MERCATOR_LAT = 85.05112878

    private val memory: LinkedHashMap<String, ShortArray> =
        object : LinkedHashMap<String, ShortArray>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ShortArray>?) =
                size > MEMORY_TILES
        }

    /** Tiles that could not be had, so a redraw does not report "not ready" forever. */
    private val failed = HashSet<String>()

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

    /** True when every tile over these bounds has been resolved, one way or the other. */
    fun isReady(
        minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, zoom: Int = TILE_ZOOM
    ): Boolean {
        try {
            val box = tileBox(minLat, minLon, maxLat, maxLon, zoom) ?: return false
            if (box.count > MAX_PREFETCH_TILES) return false
            synchronized(memory) {
                for (x in box.x0..box.x1) {
                    for (y in box.y0..box.y1) {
                        val key = key(zoom, x, y)
                        if (!memory.containsKey(key) && !failed.contains(key)) return false
                    }
                }
            }
            return true
        } catch (e: Exception) {
            return false
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
            val pool = Executors.newFixedThreadPool(min(4, total))
            try {
                for (x in box.x0..box.x1) {
                    for (y in box.y0..box.y1) {
                        pool.execute {
                            val key = key(zoom, x, y)
                            if (load(zoom, x, y)) {
                                usable.incrementAndGet()
                            } else {
                                synchronized(memory) { failed.add(key) }
                            }
                            onProgress(done.incrementAndGet(), total)
                        }
                    }
                }
                pool.shutdown()
                pool.awaitTermination(5, TimeUnit.MINUTES)
            } finally {
                pool.shutdownNow()
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

    private fun key(zoom: Int, x: Int, y: Int) = "$zoom/$x/$y"

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

    /** Memory, then disk, then the network. Returns true if the tile is now in memory. */
    private fun load(zoom: Int, x: Int, y: Int): Boolean {
        if (cached(zoom, x, y) != null) return true
        var bytes = readDisk(zoom, x, y)
        val fromDisk = bytes != null
        if (bytes == null) bytes = download(zoom, x, y)
        if (bytes == null) return false
        val heights = decode(bytes)
        if (heights == null) {
            // a half-written cache entry would otherwise be a permanent hole
            if (fromDisk) deleteDisk(zoom, x, y)
            return false
        }
        if (!fromDisk) writeDisk(zoom, x, y, bytes)
        val key = key(zoom, x, y)
        synchronized(memory) {
            memory[key] = heights
            failed.remove(key)
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
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
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
            // written aside and renamed, so a cut-off download is never read back
            val temp = File(dir, file.name + ".tmp")
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
