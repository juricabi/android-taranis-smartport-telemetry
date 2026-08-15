package juricabi.com.telemetry.video

import android.graphics.Matrix
import android.view.TextureView
import juricabi.com.telemetry.manager.PreferenceManager

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
         * The first real frame is showing — a renderer clearing a surface
         * does not count. The screen takes its waiting card down.
         */
        fun onLive()

        /**
         * The picture stopped but may return — a camera unplugged mid-watch.
         * The screen puts the waiting card back where the picture was, and
         * onLive takes it down again. Unlike onTrouble, this does not give
         * the watching up.
         */
        fun onIdle() {}

        /** The source is dead; the screen should fold the picture away. */
        fun onTrouble(what: String)

        /** Sound was dropped to keep the picture; the button should agree. */
        fun onAudioLost() {}
    }

    fun start(view: TextureView)
    fun stop()

    /**
     * Re-lays the current picture into the view — fit or fill, and the
     * remembered turn. The screen calls this when either button is tapped;
     * only the source knows the picture's size.
     */
    fun refit() {}

    /** Whether this source could play the stream's sound at all. */
    val hasAudio: Boolean get() = false

    /** The stream's sound, on or off. A no-op unless hasAudio. */
    fun setAudio(on: Boolean) {}
}

/**
 * Lay the picture into the view instead of stretching it over it — a
 * TextureView's own idea of fitting is to fill, and a 4:3 receiver frame
 * pulled over a 20:9 phone screen is not a picture anyone can fly by.
 *
 * The two remembered choices are read here, the one place the transform is
 * made: letterbox or fill the view (cropping the overflow), and an extra
 * quarter-turn for a camera mounted sideways. Every source funnels every
 * size and layout change through this.
 */
internal fun TextureView.fitPicture(videoWidth: Int, videoHeight: Int) {
    if (videoWidth <= 0 || videoHeight <= 0 || width == 0 || height == 0) return
    val preferences = PreferenceManager(context)
    val rotation = preferences.getVideoRotation()
    val fill = preferences.isVideoFillEnabled()
    // the picture's sides as they will lie on screen after the turn
    val sideways = rotation % 180 != 0
    val shownWidth = if (sideways) videoHeight else videoWidth
    val shownHeight = if (sideways) videoWidth else videoHeight
    val scale =
        if (fill) maxOf(width.toFloat() / shownWidth, height.toFloat() / shownHeight)
        else minOf(width.toFloat() / shownWidth, height.toFloat() / shownHeight)
    val onScreenWidth = shownWidth * scale
    val onScreenHeight = shownHeight * scale
    // The content starts as the video stretched over the whole view; scale
    // it so that after the rotation its extents land at the on-screen size.
    // A quarter-turn swaps which pre-rotation extent becomes which.
    val matrix = Matrix()
    matrix.postScale(
        (if (sideways) onScreenHeight else onScreenWidth) / width,
        (if (sideways) onScreenWidth else onScreenHeight) / height,
        width / 2f, height / 2f
    )
    matrix.postRotate(rotation.toFloat(), width / 2f, height / 2f)
    setTransform(matrix)
}
