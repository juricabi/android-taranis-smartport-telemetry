package juricabi.com.telemetry.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Satellite imagery over a patch of ground, stitched into a single bitmap so a
 * terrain mesh can be textured with the aerial view of the ground it models.
 *
 * Tiles come from ESRI's World Imagery service - the same one the map view
 * offers - on the ordinary web-mercator slippy scheme, 256px squares, no key
 * needed. This is the counterpart to [Elevation]: that gives the shape of the
 * ground, this gives its colour.
 *
 * [mosaic] blocks on the network, so it belongs on a background thread.
 */
object Imagery {

    private const val TAG = "Imagery"
    private const val TILE_SIZE = 256
    private const val MAX_ZOOM = 19

    /**
     * The deepest level a mosaic reaches for. Nineteen exists in the tile
     * scheme, but probed over the places this is flown ArcGIS serves its
     * "not yet available" watermark there and real ground at eighteen —
     * the same measured edge the 2D map stops at. Asking for nineteen
     * cost every fresh leaf a round of watermark fetches just to discover
     * this; the placeholder detector stays, for the patchy spots where
     * even eighteen is thin.
     */
    private const val DEEPEST_PICTURES = 18

    // ESRI orders the path z/y/x - row before column - unlike almost every
    // other slippy source. Swapping the last two still returns a valid tile,
    // just one from the wrong part of the world, so nothing fails loudly.
    private const val TILE_URL =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile"

    private const val USER_AGENT = "TelemetryViewer/1.0 (Android)"

    /** 3 is 8x8 children and a 2048px texture; past that no mesh shows the detail. */
    private const val MAX_EXTRA_ZOOM = 3

    // Sixteen, because this pool now feeds several mosaics at once: the
    // quadtree dresses four tiles in parallel, and at eight threads they
    // starved each other back to the serial pace this was meant to cure.
    // The server does its own throttling and answers sixteen as happily.
    private const val POOL_THREADS = 16
    private const val MOSAIC_TIMEOUT_MS = 120000L
    private const val HTTP_TIMEOUT_MS = 15000

    /** An unfetched square: flat grey reads as haze rather than as a hole. */
    private val MISSING_COLOR = Color.rgb(128, 128, 128)

    /** Squares the server has said are not there, so they are asked for once only. */
    private val failed = HashSet<Long>()

    /**
     * Squares that could not be fetched just now, and when to ask again.
     *
     * Long enough that one mosaic never retries a square it has already waited
     * fifteen seconds on — with no signal, every mosaic otherwise ground
     * through its sixty-four squares to its deadline — and short enough that
     * the pass after the signal returns gets them all.
     */
    private val retryAt = HashMap<Long, Long>()
    private const val RETRY_AFTER_MS = 30_000L

    /** [download]'s way of saying the server answered no, against not answering. */
    private val ABSENT = ByteArray(0)

    private val pool by lazy {
        Executors.newFixedThreadPool(POOL_THREADS, ThreadFactory { runnable ->
            val thread = Thread({
                // Sixteen decoding threads at normal priority outmuscled the
                // renderer and the replay for cores while an area loaded; the
                // ground can arrive a breath later, the flight cannot stutter.
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "imagery")
            thread.isDaemon = true
            thread
        })
    }

    @Volatile
    private var cacheDir: File? = null

    @Volatile
    private var prunedThisRun = false

    fun init(context: Context) {
        try {
            val dir = File(context.applicationContext.cacheDir, "imagery")
            if (!dir.isDirectory) dir.mkdirs()
            cacheDir = dir
            // once per process: init comes round with every 3D view, and the
            // prune walks a quarter-gigabyte directory
            if (!prunedThisRun) {
                prunedThisRun = true
                pool.submit { pruneCaches() }
            }
        } catch (e: Exception) {
            // no disk cache is survivable; every tile just costs a download
            Log.w(TAG, "no imagery cache dir: ${e.message}")
        }
    }

    /**
     * The source squares to the newest ~1000MB and the finished mosaics to
     * ~625MB — with the heights' 375MB, two gigabytes all told, which is
     * what the cache had organically grown to when it first got a ceiling
     * and the phone had carried without complaint. The ceiling is the
     * point, not the number: the squares had none at all, and kept every
     * 256-pixel piece of everywhere the camera had ever looked, forever.
     */
    private fun pruneCaches() {
        prune("m", ".jpg", 625L * 1024 * 1024, 500L * 1024 * 1024)
        prune(null, ".tile", 1000L * 1024 * 1024, 875L * 1024 * 1024)
    }

    private fun prune(prefix: String?, suffix: String, over: Long, downTo: Long) {
        try {
            val dir = cacheDir ?: return
            val files = dir.listFiles { f ->
                (prefix == null || f.name.startsWith(prefix)) && f.name.endsWith(suffix)
            } ?: return
            var total = files.sumOf { it.length() }
            if (total <= over) return
            for (f in files.sortedBy { it.lastModified() }) {
                total -= f.length()
                f.delete()
                if (total <= downTo) break
            }
        } catch (e: Exception) {
            // pruning is housekeeping; failing costs disk, not correctness
        }
    }

    /**
     * One bitmap covering exactly the tile (z, x, y), assembled from the
     * tiles [extraZoom] levels further in. Blocking; call from a worker.
     * Returns null if nothing could be fetched.
     *
     * [alive] is asked between fetches: a caller being torn down answers
     * false and the assembly stops there, instead of a dead view's worker
     * serving out two minutes of timeouts with the thread pool in its hand.
     */
    fun mosaic(z: Int, x: Int, y: Int, extraZoom: Int,
               alive: () -> Boolean = { true }): Bitmap? {
        val startedNs = System.nanoTime()
        var mosaic: Bitmap? = null
        // atomic: written by pool threads, read after the join — a plain
        // local has no happens-before through a cancelled job
        val sawPlaceholder = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            if (z < 0 || z > MAX_ZOOM) return null
            val n = 1 shl z
            if (x < 0 || x >= n || y < 0 || y >= n) return null
            val extra = extraZoom.coerceIn(0, min(MAX_EXTRA_ZOOM, DEEPEST_PICTURES - z))
            // The finished picture, remembered. Stitching a sharp mosaic is
            // dozens of decodes and the second the eye waits on every return
            // to ground it has already seen; reading the finished one back is
            // a single decode. A sharper remembered one serves a softer ask —
            // but scaled down to it. Returned at full size, the caller wore
            // hundreds of megabytes its budget never granted, and thinning
            // could not thin: the same picture came straight back at the
            // same size, two tiles refetched every 40ms for minutes.
            for (have in extra..MAX_EXTRA_ZOOM) {
                readMosaic(z, x, y, have)?.let {
                    val wantedSide = TILE_SIZE shl extra
                    val fit = if (it.width > wantedSide) {
                        val scaled = Bitmap.createScaledBitmap(
                            it, wantedSide,
                            Math.max(1, it.height * wantedSide / it.width), true)
                        it.recycle()
                        scaled
                    } else it
                    DebugLog.note(TAG, "mosaic $z/$x/$y+$extra cached " +
                        "${(System.nanoTime() - startedNs) / 1_000_000}ms")
                    return fit
                }
            }
            val side = 1 shl extra
            val childZoom = z + extra
            // Half the memory of ARGB_8888, and the ground has no use for an
            // alpha channel. The slight banding 565 can put into a smooth
            // gradient is invisible in aerial photography.
            mosaic = Bitmap.createBitmap(side * TILE_SIZE, side * TILE_SIZE, Bitmap.Config.RGB_565)
            val canvas = Canvas(mosaic)
            canvas.drawColor(MISSING_COLOR)
            val drawn = AtomicInteger(0)
            val jobs = ArrayList<Future<*>>(side * side)
            for (i in 0 until side) {
                for (j in 0 until side) {
                    val childX = x * side + i
                    val childY = y * side + j
                    val left = i * TILE_SIZE
                    val top = j * TILE_SIZE
                    jobs.add(pool.submit(Runnable {
                        try {
                            if (!alive()) return@Runnable
                            val child = fetch(childZoom, childX, childY) ?: return@Runnable
                            try {
                                // ArcGIS serves a "not yet available" watermark
                                // where its imagery ends, not an error — baked
                                // into the ground it is white squares with
                                // writing on them.
                                if (looksLikePlaceholder(child)) {
                                    sawPlaceholder.set(true)
                                    return@Runnable
                                }
                                // Canvas is not thread safe and every child lands on this one
                                synchronized(canvas) {
                                    canvas.drawBitmap(
                                        child, null,
                                        Rect(left, top, left + TILE_SIZE, top + TILE_SIZE), null
                                    )
                                }
                                drawn.incrementAndGet()
                            } finally {
                                // dropped the moment it is on the mosaic, so only the
                                // pool's worth of children is ever held at once
                                child.recycle()
                            }
                        } catch (e: Throwable) {
                            // nothing may escape a pool thread, and a mosaic abandoned
                            // below may already have been recycled under this draw
                        }
                    }))
                }
            }
            val deadline = System.currentTimeMillis() + MOSAIC_TIMEOUT_MS
            for (job in jobs) {
                if (!alive()) break
                try {
                    job.get(max(0L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    job.cancel(true)
                }
            }
            if (!alive()) {
                for (job in jobs) job.cancel(true)
                mosaic.recycle()
                return null
            }
            // Watermarks mean this level does not exist here: step back one
            // and let real pictures fill the whole square, scaled by the
            // mesh rather than written on by the server.
            if (sawPlaceholder.get() && extra > 0) {
                mosaic.recycle()
                // Not written to disk under the sharper key: this level has
                // no way to know whether the fallback came back whole, and a
                // holed one cached here was permanent — the fallback level
                // caches its own result under its own completeness rule.
                return mosaic(z, x, y, extra - 1, alive)
            }
            // a missing square is only haze on the texture, but all of them means
            // there is no imagery here at all and the caller should not texture
            if (drawn.get() == 0) {
                mosaic.recycle()
                return null
            }
            // only complete, expensive ones: a small mosaic is quicker to
            // stitch than to read back, and a holed one should heal next time
            if (extra >= 2 && drawn.get() == side * side) {
                writeMosaic(z, x, y, extra, mosaic)
            }
            DebugLog.note(TAG, "mosaic $z/$x/$y+$extra stitched " +
                "${(System.nanoTime() - startedNs) / 1_000_000}ms")
            return mosaic
        } catch (e: Exception) {
            mosaic?.recycle()
            Log.w(TAG, "mosaic $z/$x/$y failed: ${e.message}")
            return null
        } catch (e: OutOfMemoryError) {
            mosaic?.recycle()
            return null
        }
    }

    /** Disk, then the network. Returns the decoded child, or null if it could not be had. */
    private fun fetch(zoom: Int, x: Int, y: Int): Bitmap? {
        try {
            val n = 1 shl zoom
            if (x < 0 || x >= n || y < 0 || y >= n) return null
            // Asked for before and not there. Every square of a mosaic used to
            // be re-requested each time the mosaic was built, so ground with a
            // gap in its imagery paid the full fifteen second wait for that gap
            // again on every visit.
            val key = (zoom.toLong() shl 58) or (x.toLong() shl 29) or (y.toLong() and 0x1FFFFFFF)
            val skip = synchronized(failed) {
                failed.contains(key) ||
                    android.os.SystemClock.elapsedRealtime() < (retryAt[key] ?: 0L)
            }
            if (skip) return null
            var bytes = readDisk(zoom, x, y)
            val fromDisk = bytes != null
            if (bytes == null) {
                bytes = download(zoom, x, y)
                // Only the server saying no is remembered for good. A timeout
                // or a dropped connection was remembered the same way —
                // including the fetches interrupted when a mosaic hit its
                // deadline — and those squares stayed grey for the rest of the
                // process.
                if (bytes === ABSENT) {
                    synchronized(failed) { failed.add(key) }
                    return null
                }
                if (bytes == null) {
                    synchronized(failed) {
                        retryAt[key] = android.os.SystemClock.elapsedRealtime() + RETRY_AFTER_MS
                    }
                    return null
                }
            }
            val bitmap = decode(bytes)
            if (bitmap == null) {
                // a half-written cache entry would otherwise be a permanent grey square
                if (fromDisk) deleteDisk(zoom, x, y)
                return null
            }
            if (!fromDisk) writeDisk(zoom, x, y, bytes)
            // fetched after all, so the bad moment is forgotten — left in,
            // one entry per square ever missed accumulated for the whole
            // life of the process
            synchronized(failed) { retryAt.remove(key) }
            return bitmap
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    /**
     * ArcGIS's "map data not yet available" square: near-uniform light grey
     * with faint writing. Sixteen samples with a tight mean and no colour is
     * that tile; a real photograph this uniform and this pale is snow, and
     * snow at least varies more than paint.
     */
    private fun looksLikePlaceholder(bitmap: Bitmap): Boolean {
        var lowest = 255
        var highest = 0
        var first = 0
        var identical = 0
        for (sy in 0 until 4) {
            for (sx in 0 until 4) {
                val pixel = bitmap.getPixel(
                    sx * (bitmap.width - 1) / 3, sy * (bitmap.height - 1) / 3)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                // colour disqualifies at once
                if (Math.abs(r - g) > 8 || Math.abs(g - b) > 8) return false
                if (sy == 0 && sx == 0) first = pixel
                if (pixel == first) identical++
                if (r < lowest) lowest = r
                if (r > highest) highest = r
            }
        }
        // Painted, not photographed: the watermark's background is one exact
        // colour, so most samples are bit-identical. A snowfield or a salt
        // flat is pale and low-range too, but a photograph of one never
        // repeats the same pixel twelve times — requiring it is what keeps
        // real winter ground from being thrown away as a watermark.
        return identical >= 12 && lowest > 180 && highest - lowest < 40
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        try {
            val options = BitmapFactory.Options()
            // the children live only long enough to be drawn onto the 565
            // mosaic, so decoding them any deeper is memory for nothing
            options.inPreferredConfig = Bitmap.Config.RGB_565
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    private fun download(zoom: Int, x: Int, y: Int): ByteArray? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("$TILE_URL/$zoom/$y/$x").openConnection() as HttpURLConnection
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            // Only a plain "no such tile". ESRI uses 403 for auth and rate
            // decisions, which pass; treating those as missing would grey the
            // square for good over a moment's throttling.
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return ABSENT
            if (code != HttpURLConnection.HTTP_OK) return null
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) out.write(buffer, 0, read)
            }
            return out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "tile $zoom/$y/$x not fetched: ${e.message}")
            return null
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            connection?.disconnect()
        }
    }

    // The disk cache is the point of the whole thing: imagery pulled at home
    // has to still be there at a flying field with no signal.
    private fun tileFile(zoom: Int, x: Int, y: Int): File? {
        val dir = cacheDir ?: return null
        // ESRI answers JPEG over land and PNG over water, so no honest extension
        return File(dir, "${zoom}_${x}_$y.tile")
    }

    private fun mosaicFile(z: Int, x: Int, y: Int, extra: Int): File? {
        val dir = cacheDir ?: return null
        return File(dir, "m${z}_${x}_${y}_$extra.jpg")
    }

    private fun readMosaic(z: Int, x: Int, y: Int, extra: Int): Bitmap? {
        try {
            val file = mosaicFile(z, x, y, extra) ?: return null
            if (!file.isFile || file.length() <= 0L) return null
            val options = BitmapFactory.Options()
            options.inPreferredConfig = Bitmap.Config.RGB_565
            val bitmap = BitmapFactory.decodeFile(file.path, options)
            if (bitmap == null) {
                file.delete()
            } else {
                // touched, so the prune keeps what is actually being flown over
                file.setLastModified(System.currentTimeMillis())
            }
            return bitmap
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    private fun writeMosaic(z: Int, x: Int, y: Int, extra: Int, bitmap: Bitmap) {
        try {
            val file = mosaicFile(z, x, y, extra) ?: return
            val dir = file.parentFile ?: return
            if (!dir.isDirectory && !dir.mkdirs()) return
            val temp = File(dir, "${file.name}.${Thread.currentThread().id}.tmp")
            FileOutputStream(temp).use {
                // 85 keeps field tracks readable and the file under a
                // megabyte; the source tiles are JPEG to begin with
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
            }
            if (!temp.renameTo(file)) temp.delete()
        } catch (e: Exception) {
            // a mosaic that fails to cache costs only its next stitch
        } catch (e: OutOfMemoryError) {
            // same
        }
    }

    private fun readDisk(zoom: Int, x: Int, y: Int): ByteArray? {
        try {
            val file = tileFile(zoom, x, y) ?: return null
            if (!file.isFile || file.length() <= 0L) return null
            // touched, so the prune keeps what is actually being flown over
            file.setLastModified(System.currentTimeMillis())
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
            // written aside and renamed, so a cut-off download is never read back,
            // and so two mosaics wanting the same child cannot interleave writes
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
