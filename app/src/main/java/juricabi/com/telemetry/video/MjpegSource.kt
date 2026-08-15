package juricabi.com.telemetry.video

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.view.TextureView
import android.view.View
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Scans a stream for whole JPEGs — the start mark FFD8 to the end mark FFD9 —
 * and hands each one over. Returns when the stream ends or onFrame says stop.
 *
 * Deliberately reads none of the multipart headers around the frames: the
 * cheap cameras this exists for misdeclare their boundaries and lengths, and
 * the JPEG marks are the one part of the stream they cannot get wrong.
 */
internal fun scanMjpegFrames(input: InputStream, onFrame: (ByteArray) -> Boolean) {
    val frame = ByteArrayOutputStream(64 * 1024)
    var previous = -1
    var inJpeg = false
    while (true) {
        val b = input.read()
        if (b < 0) return
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

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var connection: HttpURLConnection? = null
    private var view: TextureView? = null
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0

    // refits the letterbox when the fullscreen toggle resizes the pane
    private val refit = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        view?.fitPicture(frameWidth, frameHeight)
    }

    override fun start(view: TextureView) {
        this.view = view
        view.addOnLayoutChangeListener(refit)
        running = true
        thread = Thread({
            try {
                stream()
                if (running) events.onTrouble("the stream ended")
            } catch (e: Exception) {
                // closing the connection under a blocked read is how stop()
                // works, so only a failure while still wanted is trouble
                if (running) events.onTrouble(e.message ?: "stream failed")
            }
        }, "mjpeg-video").also { it.start() }
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
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("HTTP " + conn.responseCode)
        }
        scanMjpegFrames(BufferedInputStream(conn.inputStream)) { bytes ->
            draw(bytes)
            running
        }
    }

    private fun draw(bytes: ByteArray) {
        if (!running) return
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        val view = view ?: return
        if (bitmap.width != frameWidth || bitmap.height != frameHeight) {
            frameWidth = bitmap.width
            frameHeight = bitmap.height
            // setTransform belongs to the UI thread; the frames do not
            view.post { view.fitPicture(frameWidth, frameHeight) }
        }
        // null while the view is not yet available; the stream keeps going
        // and the next frame after it appears lands
        val canvas = view.lockCanvas() ?: run { bitmap.recycle(); return }
        try {
            canvas.drawBitmap(bitmap, null, Rect(0, 0, canvas.width, canvas.height), null)
        } finally {
            view.unlockCanvasAndPost(canvas)
            bitmap.recycle()
        }
        if (!live) {
            live = true
            events.onLive()
        }
    }

    override fun stop() {
        running = false
        connection?.disconnect() // unblocks a read waiting on the network
        connection = null
        thread = null
        view?.removeOnLayoutChangeListener(refit)
        view = null
    }
}
