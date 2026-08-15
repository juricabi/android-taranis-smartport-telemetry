package juricabi.com.telemetry.video

import android.graphics.Matrix
import android.view.TextureView

/**
 * One live picture, whatever carries it: a USB (UVC) receiver or goggles, or
 * an RTSP stream off the network.
 *
 * A source owns the view from start to stop, and only one is ever active. It
 * reports trouble through the callback it was built with rather than
 * throwing: the picture is optional equipment, and a missing camera must
 * never take the telemetry down with it.
 */
interface VideoSource {

    /** What a source tells the screen, from whatever thread it lives on. */
    interface Events {
        /**
         * The first real frame is showing. The screen gives the picture its
         * half only now — split earlier, a refused connection put an empty
         * black half over the flight for as long as the retries took.
         */
        fun onLive()

        /**
         * The picture stopped but may return — a camera unplugged mid-watch.
         * The screen folds the half away and keeps waiting; onLive earns it
         * back. Unlike onTrouble, this does not give the watching up.
         */
        fun onIdle() {}

        /** The source is dead; the screen should fold the picture away. */
        fun onTrouble(what: String)

        /** Sound was dropped to keep the picture; the button should agree. */
        fun onAudioLost() {}
    }

    fun start(view: TextureView)
    fun stop()

    /** Whether this source could play the stream's sound at all. */
    val hasAudio: Boolean get() = false

    /** The stream's sound, on or off. A no-op unless hasAudio. */
    fun setAudio(on: Boolean) {}
}

/**
 * Letterbox the picture into the view instead of stretching it over it — a
 * TextureView's own idea of fitting is to fill, and a 4:3 receiver frame
 * pulled over a 20:9 phone screen is not a picture anyone can fly by.
 */
internal fun TextureView.fitPicture(videoWidth: Int, videoHeight: Int) {
    if (videoWidth <= 0 || videoHeight <= 0 || width == 0 || height == 0) return
    val scale = minOf(
        width.toFloat() / videoWidth, height.toFloat() / videoHeight
    )
    val matrix = Matrix()
    matrix.setScale(videoWidth * scale / width, videoHeight * scale / height)
    matrix.postTranslate(
        (width - videoWidth * scale) / 2f, (height - videoHeight * scale) / 2f
    )
    setTransform(matrix)
}
