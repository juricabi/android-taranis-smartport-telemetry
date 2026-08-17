package juricabi.com.telemetry.video

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.view.SurfaceView
import android.view.View
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import juricabi.com.telemetry.utils.DebugLog

/**
 * Scans a stream for whole JPEGs — the start mark FFD8 to the end mark FFD9 —
 * and hands each one over. Returns when the stream ends or onFrame says stop.
 *
 * Deliberately reads none of the multipart headers around the frames: the
 * cheap cameras this exists for misdeclare their boundaries and lengths, and
 * the JPEG marks are the one part of the stream they cannot get wrong.
 */
internal fun scanMjpegFrames(input: InputStream, onFrame: (ByteArray) -> Boolean) {
    // read in chunks and walk them locally: a stream's read() per byte is a
    // synchronized call four hundred thousand times a frame, and it showed
    val chunk = ByteArray(32 * 1024)
    val frame = ByteArrayOutputStream(64 * 1024)
    var previous = -1
    var inJpeg = false
    while (true) {
        val got = input.read(chunk)
        if (got < 0) return
        for (i in 0 until got) {
            val b = chunk[i].toInt() and 0xFF
            if (!inJpeg) {
                if (previous == 0xFF && b == 0xD8) {
                    inJpeg = true
                    frame.reset()
                    frame.write(0xFF)
                    frame.write(0xD8)
                }
            } else {
                frame.write(b)
                if (previous == 0xFF && b == 0xD9) {
                    inJpeg = false
                    if (!onFrame(frame.toByteArray())) return
                }
            }
            previous = b
        }
    }
}

/**
 * MJPEG over HTTP — the cheap end of network video: an ESP32-CAM, the IP
 * Webcam app, an mjpg-streamer box. Every frame is a whole JPEG, decoded and
 * drawn onto the view from one worker thread; there is nothing to buffer, so
 * the latency is whatever the camera and the network cost.
 */
class MjpegSource(
    private val url: String,
    private val events: VideoSource.Events
) : VideoSource {

    private var live = false

    // the whole address as tried, credentials kept out, for the trouble
    // toast — an error's own words name the host at best, and a typo in
    // the path never shows itself otherwise
    private val said = url.replace(Regex("//[^/@]+@"), "//<auth>@")

    @Volatile private var running = false
    // what the server answered, for the toast when no frame ever came — an
    // error page is HTTP 200 too, and "ended" for a stream that never began
    // sent the field re-tapping at a wrong path
    @Volatile private var answer: String? = null
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var view: SurfaceView? = null

    // The newest complete frame, waiting on the decoder — one slot, newest
    // wins. Reading the wire is the cheap half and decoding the dear one;
    // decoded inline, a camera faster than the decode banked its lead in
    // the TCP buffers, and the picture ran seconds behind after minutes of
    // watching. Every frame is a whole picture, so dropping costs nothing
    // but the frames nobody would have seen in time anyway.
    private val latest = java.util.concurrent.ArrayBlockingQueue<ByteArray>(1)
    private var skipped = 0
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0

    // refits the letterbox when the fullscreen toggle resizes the pane
    private val refitOnLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        refit()
    }

    override fun refit() {
        view?.fitPicture(frameWidth, frameHeight)
    }

    override fun start(view: SurfaceView) {
        DebugLog.note("Video", "mjpeg start")
        this.view = view
        view.addOnLayoutChangeListener(refitOnLayout)
        running = true
        Thread({
            try {
                stream()
                if (running) events.onTrouble(
                    if (live) "$said ended"
                    else "$said sent no MJPEG picture (${answer ?: "no answer"}) — " +
                        "is the path right?"
                )
            } catch (e: Exception) {
                // closing the connection under a blocked read is how stop()
                // works, so only a failure while still wanted is trouble
                if (running) events.onTrouble("$said — ${e.message ?: "stream failed"}")
            }
        }, "mjpeg-video").start()
        Thread({
            try {
                while (running) {
                    val frame = latest.poll(
                        100, java.util.concurrent.TimeUnit.MILLISECONDS
                    ) ?: continue
                    draw(frame)
                }
            } catch (e: InterruptedException) {
                // stopping; nothing left to draw
            }
        }, "mjpeg-draw").start()
    }

    private fun stream() {
        val conn = URL(url).openConnection() as HttpURLConnection
        connection = conn
        // stop() disconnects whatever it finds here; found nothing yet, its
        // half of the handshake is this flag
        if (!running) {
            conn.disconnect()
            return
        }
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        answer = "HTTP ${conn.responseCode}, ${conn.contentType}"
        DebugLog.note("Video", "mjpeg $answer")
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("HTTP " + conn.responseCode)
        }
        scanMjpegFrames(BufferedInputStream(conn.inputStream)) { bytes ->
            if (!latest.offer(bytes)) {
                latest.clear()
                latest.offer(bytes)
                if (++skipped % 300 == 1) DebugLog.note(
                    "Video",
                    "mjpeg $skipped frames behind the wire so far, dropped to stay live"
                )
            }
            running
        }
    }

    // each frame decodes into the last frame's pixels: a fresh 1080p bitmap
    // is eight megabytes, and allocating one per frame kept the collector
    // running against the decoder — the picture stuttered for it
    private val decodeOptions = BitmapFactory.Options().apply { inMutable = true }
    private val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }

    /**
     * Powers of two the decoder can skip while reading: a 1080p frame shown
     * in a half-screen pane spends most of its decode on pixels the pane
     * cannot show. The longer sides are compared so a filled or quarter-
     * turned picture stays sharp; a pane not yet laid out decodes whole.
     */
    private fun sampleFor(frameLong: Int, view: SurfaceView): Int {
        val paneLong = maxOf(view.width, view.height)
        if (paneLong == 0) return 1
        var sample = 1
        while (frameLong / (sample * 2) >= paneLong) sample *= 2
        return sample
    }

    private fun draw(bytes: ByteArray) {
        if (!running) return
        val view = view ?: return
        // the frame's own header says its size — read every time, because
        // some cameras switch size mid-stream when their screen is turned
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        val sample = sampleFor(maxOf(boundsOptions.outWidth, boundsOptions.outHeight), view)
        if (decodeOptions.inSampleSize != sample) {
            decodeOptions.inSampleSize = sample
            decodeOptions.inBitmap = null
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (e: IllegalArgumentException) {
            // the camera changed frame size and the old pixels no longer fit
            decodeOptions.inBitmap = null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } ?: return
        decodeOptions.inBitmap = bitmap
        if (bitmap.width != frameWidth || bitmap.height != frameHeight) {
            frameWidth = bitmap.width
            frameHeight = bitmap.height
            // setTransform belongs to the UI thread; the frames do not
            view.post { view.fitPicture(frameWidth, frameHeight) }
        }
        // Re-read the view before touching the canvas: the decode above took
        // tens of milliseconds, long enough for a stop-and-restart to swap
        // the source under us, and a draw into the successor's surface would
        // be the wrong picture at best.
        val target = this.view ?: return
        if (!running) return
        val holder = target.holder
        // null while the surface is not yet created or already gone; the
        // stream keeps going and the next frame after it returns lands
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawBitmap(bitmap, null, Rect(0, 0, canvas.width, canvas.height), null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
        if (!live) {
            live = true
            DebugLog.note("Video", "mjpeg first frame ${bitmap.width}x${bitmap.height}")
            events.onLive()
        }
    }

    override fun stop() {
        DebugLog.note("Video", "mjpeg stop")
        running = false
        connection?.disconnect() // unblocks a read waiting on the network
        connection = null
        view?.removeOnLayoutChangeListener(refitOnLayout)
        view = null
    }
}
