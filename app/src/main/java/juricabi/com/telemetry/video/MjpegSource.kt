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
    // the network that reaches the camera, when one specifically does — see
    // NetworkBinder; null leaves the connection on the phone's default route
    private val network: android.net.Network?,
    // the picture's extra quarter-turn, asked of the host at fit time — the
    // host owns the setting, the source only wears it
    private val turn: () -> Int,
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

    // What a stutter cost, and where: the longest the wire went without a
    // whole frame, and the slowest decode, screen draw and recording, since
    // the last report. Written down only when one of them stalled, so a
    // smooth stream says nothing and a field log still shows which half —
    // the network or the phone — held the picture up.
    @Volatile private var worstWireMs = 0L
    private var lastWireAt = 0L
    private var worstDecodeMs = 0L
    private var worstScreenMs = 0L
    private var worstRecordMs = 0L
    private var reportedAt = 0L
    private var skippedAtReport = 0
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0
    // the turn for a sideways camera; the canvas turns the bitmap, read on
    // the UI thread and used on the draw thread
    @Volatile private var rotation = 0

    // ---- recording -------------------------------------------------------
    //
    // Every frame is a JPEG, which no recording keeps, so the picture already
    // decoded for the screen is drawn once more onto a hardware encoder's
    // surface. The encoder is fed on the draw thread but built on a thread of
    // its own, at the size of the frame that asked for it: built on the draw
    // thread it froze the picture for the quarter-second it took (measured
    // 227 ms). The frames go to the screen alone until it is ready.

    @Volatile private var recordSink: RecordSink? = null
    private val encoderLock = Any()
    private var encoder: BitmapEncoder? = null
    private var building = false

    override fun record(sink: RecordSink?) {
        recordSink = sink
        if (sink == null) releaseEncoder()
    }

    private fun recordFrame(bitmap: android.graphics.Bitmap, sink: RecordSink) {
        synchronized(encoderLock) {
            // a frame caught in flight by a stop must not ask for an encoder
            // for a recording that is over
            if (recordSink !== sink) return
            // encoders take even sides; an odd camera loses its last line
            val w = bitmap.width and 1.inv()
            val h = bitmap.height and 1.inv()
            val e = encoder
            if (e == null || e.width != w || e.height != h) {
                if (!building) {
                    building = true
                    Thread({ buildEncoder(w, h, sink) }, "mjpeg-encoder").start()
                }
                return
            }
            try {
                e.encode(bitmap, sink)
            } catch (x: Exception) {
                fail(sink, x)
            }
        }
    }

    private fun buildEncoder(w: Int, h: Int, sink: RecordSink) {
        val made = try {
            BitmapEncoder(w, h)
        } catch (x: Exception) {
            synchronized(encoderLock) {
                building = false
                if (recordSink === sink) fail(sink, x)
            }
            return
        }
        synchronized(encoderLock) {
            building = false
            // the recording may have ended, or the camera changed size, while
            // it was being built; either way this one is not wanted
            if (recordSink !== sink) {
                made.release()
                return
            }
            encoder?.release()
            encoder = made
        }
        DebugLog.note("Video", "mjpeg recording encoder ${w}x$h")
    }

    /** Held under the encoder lock. */
    private fun fail(sink: RecordSink, x: Exception) {
        DebugLog.note("Video", "mjpeg recording encoder failed: ${x.message}")
        encoder?.release()
        encoder = null
        recordSink = null
        sink.trouble("the phone could not encode the stream's picture (${x.message})")
    }

    private fun releaseEncoder() {
        synchronized(encoderLock) {
            encoder?.release()
            encoder = null
        }
    }

    // refits the letterbox when the divider resizes the pane
    private val refitOnLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        refit()
    }

    override fun refit() {
        val v = view ?: return
        rotation = turn()
        // the canvas turns the picture, so the half is fitted to the sides
        // as they land — swapped for a quarter-turn
        val (w, h) = turnedSides(frameWidth, frameHeight, rotation)
        v.fitPicture(w, h)
    }

    override fun start(view: SurfaceView) {
        DebugLog.note("Video", "mjpeg start")
        reportedAt = android.os.SystemClock.elapsedRealtime()
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
        val target = URL(url)
        val conn =
            (network?.openConnection(target) ?: target.openConnection()) as HttpURLConnection
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
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastWireAt != 0L) worstWireMs = maxOf(worstWireMs, now - lastWireAt)
            lastWireAt = now
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
        val startedAt = android.os.SystemClock.elapsedRealtime()
        // the frame's own header says its size — read every time, because
        // some cameras switch size mid-stream when their screen is turned
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        // a recording wants every pixel the camera sent, not the pane's share
        val sample = if (recordSink != null) 1
        else sampleFor(maxOf(boundsOptions.outWidth, boundsOptions.outHeight), view)
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
        val decodedAt = android.os.SystemClock.elapsedRealtime()
        if (bitmap.width != frameWidth || bitmap.height != frameHeight) {
            frameWidth = bitmap.width
            frameHeight = bitmap.height
            // sizing the surface belongs to the UI thread; the frames do not
            view.post { refit() }
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
            // the surface is sized to the turned picture; turn the canvas
            // to match and lay the frame into the box that, once turned,
            // fills it — a quarter-turn swaps the box's sides
            val turn = rotation
            if (turn != 0) canvas.rotate(turn.toFloat(), canvas.width / 2f, canvas.height / 2f)
            val sideways = turn % 180 != 0
            val dw = if (sideways) canvas.height else canvas.width
            val dh = if (sideways) canvas.width else canvas.height
            val dst = Rect(
                (canvas.width - dw) / 2, (canvas.height - dh) / 2,
                (canvas.width + dw) / 2, (canvas.height + dh) / 2
            )
            canvas.drawBitmap(bitmap, null, dst, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
        if (!live) {
            live = true
            DebugLog.note("Video", "mjpeg first frame ${bitmap.width}x${bitmap.height}")
            events.onLive()
        }
        val shownAt = android.os.SystemClock.elapsedRealtime()
        // after the screen has it, so a recording never delays the picture
        recordSink?.let { recordFrame(bitmap, it) }
        val doneAt = android.os.SystemClock.elapsedRealtime()
        worstDecodeMs = maxOf(worstDecodeMs, decodedAt - startedAt)
        worstScreenMs = maxOf(worstScreenMs, shownAt - decodedAt)
        worstRecordMs = maxOf(worstRecordMs, doneAt - shownAt)
        if (doneAt - reportedAt >= 2000) {
            if (worstWireMs > 150 || maxOf(worstDecodeMs, worstScreenMs, worstRecordMs) > 100) {
                DebugLog.note(
                    "Video", "mjpeg stall in the last ${doneAt - reportedAt} ms: " +
                        "wire gap $worstWireMs, decode $worstDecodeMs, screen $worstScreenMs, " +
                        "record $worstRecordMs ms; ${skipped - skippedAtReport} frames dropped"
                )
            }
            reportedAt = doneAt
            skippedAtReport = skipped
            worstWireMs = 0
            worstDecodeMs = 0
            worstScreenMs = 0
            worstRecordMs = 0
        }
    }

    override fun stop() {
        DebugLog.note("Video", "mjpeg stop")
        running = false
        connection?.disconnect() // unblocks a read waiting on the network
        connection = null
        view?.removeOnLayoutChangeListener(refitOnLayout)
        view = null
        recordSink = null
        releaseEncoder()
    }
}
